package dx.compiler

import java.nio.file.{Files, Path}
import com.typesafe.config.{Config, ConfigFactory}
import dx.api.{
  DxApi,
  DxApplet,
  DxAppletDescribe,
  DxDataObject,
  DxExecutable,
  DxFile,
  DxPath,
  DxProject,
  DxRecord,
  DxUtils,
  DxWorkflow,
  DxWorkflowDescribe,
  Field
}
import dx.core.{Constants, getVersion}
import dx.core.io.{DxWorkerPaths, StreamFiles}
import dx.core.ir._
import dx.util.CodecUtils
import dx.translator.Extras
import spray.json.{JsValue, _}
import dx.util.{FileSourceResolver, FileUtils, JsUtils, Logger, TraceLevel}

import scala.jdk.CollectionConverters._

object Compiler {
  val RuntimeConfigFile = "dxCompiler_runtime.conf"
  val RegionToProjectFile = "dxCompiler.regionToProject"
}

/**
  * Compile IR to native applets and workflows.
  * @param extras extra configuration
  * @param runtimePathConfig path configuration on the runtime environment
  * @param runtimeTraceLevel trace level to use at runtime
  * @param includeAsset whether to package the runtime asset with generated applications
  * @param archive whether to archive existing applications
  * @param force whether to delete existing executables
  * @param leaveWorkflowsOpen whether to leave generated workflows in the open state
  * @param locked whether to generate locked workflows
  * @param projectWideReuse whether to allow project-wide reuse of applications
  * @param streamFiles which files to stream vs download
  * @param waitOnUpload whether to wait for each file upload to complete
  * @param useManifests whether to use manifest files for all application inputs and outputs
  * @param complexPathValues whether File and Directory values should be treated as objects
  * @param fileResolver the FileSourceResolver
  * @param dxApi the DxApi
  * @param logger the Logger
  */
case class Compiler(extras: Option[Extras],
                    runtimePathConfig: DxWorkerPaths,
                    runtimeTraceLevel: Int,
                    includeAsset: Boolean,
                    runtimeAssetName: String,
                    runtimeJar: String,
                    archive: Boolean,
                    force: Boolean,
                    leaveWorkflowsOpen: Boolean,
                    projectWideReuse: Boolean,
                    separateOutputs: Boolean,
                    streamFiles: StreamFiles.StreamFiles,
                    waitOnUpload: Boolean,
                    useManifests: Boolean,
                    complexPathValues: Boolean,
                    instanceTypeSelection: InstanceTypeSelection.InstanceTypeSelection,
                    defaultInstanceType: Option[String],
                    fileResolver: FileSourceResolver = FileSourceResolver.get,
                    dxApi: DxApi = DxApi.get,
                    logger: Logger = Logger.get) {
  // logger for extra trace info
  private val logger2: Logger = logger.withTraceIfContainsKey("Native")

  // temp dir where applications will be compiled - it is deleted on shutdown
  private lazy val appCompileDirPath: Path = {
    val p = Files.createTempDirectory("dxCompiler_Compile")
    sys.addShutdownHook({
      FileUtils.deleteRecursive(p)
    })
    p
  }

  private case class BundleCompiler(bundle: Bundle, project: DxProject, folder: String) {
    private val parameterLinkSerializer =
      ParameterLinkSerializer(fileResolver, dxApi = dxApi, pathsAsObjects = complexPathValues)
    // directory of the currently existing applets - we don't want to build them
    // if we don't have to.
    private val executableDir =
      DxExecutableDirectory(bundle, project, folder, projectWideReuse, dxApi, logger)

    private def getAssetLink: JsValue = {
      // get billTo and region from the project, then find the runtime asset
      // in the current region.
      val projectRegion = project.describe(Set(Field.Region)).region match {
        case Some(s) => s
        case None    => throw new Exception(s"Cannot get region for project ${project}")
      }
      // Find the runtime asset with the correct version. Look inside the
      // project configured for this region. The regions live in dxCompiler.regionToproject
      val config = ConfigFactory.load(Compiler.RuntimeConfigFile)
      val regionToProjectOption: Vector[Config] =
        config.getConfigList(Compiler.RegionToProjectFile).asScala.toVector
      val regionToProjectConf: Map[String, String] = regionToProjectOption.map { pair =>
        val region = pair.getString("region")
        val project = pair.getString("path")
        region -> project
      }.toMap
      // The mapping from region to project name is list of (region, proj-name) pairs.
      // Get the project for this region.
      val assetSourcePath = regionToProjectConf.get(projectRegion) match {
        case Some(dest) => dest
        case None =>
          throw new Exception(s"Region ${projectRegion} is currently unsupported")
      }
      val sourcePathRegexp = "(?:(.*):)?(.+)".r
      val (regionalProjectName, assetFolder) = assetSourcePath match {
        case sourcePathRegexp(null, project)   => (project, "/")
        case sourcePathRegexp(project, folder) => (project, folder)
        case _ =>
          throw new Exception(s"Bad syntax for destination ${assetSourcePath}")
      }
      val regionalProject = dxApi.resolveProject(regionalProjectName)
      val assetUri = DxPath.format(regionalProject.id, assetFolder, runtimeAssetName)
      logger.trace(s"Looking for asset id at ${assetUri}")
      val dxAsset = dxApi.resolveDataObject(assetUri, Some(regionalProject)) match {
        case dxRecord: DxRecord => dxRecord
        case other =>
          throw new Exception(s"Found dx object of wrong type ${other} at ${assetUri}")
      }
      // We need the executor asset cloned into this project, so it will be available
      // to all subjobs we run. If for some reason we're running in the same project
      // where the asset lives, no clone operation will be performed.
      dxApi.cloneAsset(runtimeAssetName, dxAsset, regionalProject, project)
      // Extract the archive from the details field
      val desc = dxAsset.describe(Set(Field.Details))
      val dxLink =
        try {
          JsUtils.get(desc.details.get, Some("archiveFileId"))
        } catch {
          case _: Throwable =>
            throw new Exception(
                s"record does not have an archive field in details ${desc.details}"
            )
        }
      val dxFile = DxFile.fromJson(dxApi, dxLink)
      JsObject(
          "name" -> JsString(dxFile.describe().name),
          "id" -> JsObject(DxUtils.DxLinkKey -> JsString(dxFile.id))
      )
    }

    private lazy val runtimeAsset: Option[JsValue] = if (includeAsset) {
      Some(getAssetLink)
    } else {
      None
    }

    // Add a checksum to a request
    private def checksumRequest(name: String,
                                desc: Map[String, JsValue]): (Map[String, JsValue], String) = {
      logger2.trace(
          s"""|${name} -> checksum request
              |fields = ${JsObject(desc).prettyPrint}

              |""".stripMargin
      )
      // We need to exclude source code from the details for the checksum calculations see APPS-994
      val detailsNoSource: Map[String, JsValue] = {
        desc.get("details") match {
          case Some(JsObject(details)) => details.removed(Constants.SourceCode)
          case None                    => Map.empty
          case other =>
            throw new Exception(s"Bad properties json value ${other}")
        }
      }
      val filteredDesc: Map[String, JsValue] = desc.removed("details") ++ Map {
        "details" -> JsObject(detailsNoSource)
      }
      // We need to sort the hash-tables. They are naturally unsorted,
      // causing the same object to have different checksums.
      val digest =
        CodecUtils.md5Checksum(JsUtils.makeDeterministic(JsObject(filteredDesc)).prettyPrint)
      // Add the checksum to the properties
      val existingDetails: Map[String, JsValue] =
        desc.get("details") match {
          case Some(JsObject(details)) => details
          case None                    => Map.empty
          case other =>
            throw new Exception(s"Bad properties json value ${other}")
        }
      val updatedDetails = existingDetails ++
        Map(
            Constants.Version -> JsString(getVersion),
            Constants.Checksum -> JsString(digest)
        )
      // Add properties and attributes we don't want to fall under the checksum
      // This allows, for example, moving the dx:executable, while
      // still being able to reuse it.
      val updatedRequest = desc ++ Map(
          "project" -> JsString(project.id),
          "folder" -> JsString(folder),
          "parents" -> JsBoolean(true),
          "details" -> JsObject(updatedDetails)
      )
      (updatedRequest, digest)
    }

    // Create linking information for a dx:executable
    private def createLinkForCall(irCall: Callable, dxObj: DxExecutable): ExecutableLink = {
      val callInputs: Map[DxName, Type] = irCall.inputVars.map(p => p.name -> p.dxType).toMap
      val callOutputs: Map[DxName, Type] = irCall.outputVars.map(p => p.name -> p.dxType).toMap
      ExecutableLink(irCall.name, callInputs, callOutputs, dxObj)
    }

    /**
      * Get an existing application if one exists with the given name and matching the
      * given digest.
      * @param name the application name
      * @param digest the application digest
      * @return the existing executable, or None if the executable does not exist, has
      *         change, or a there are multiple executables that cannot be resolved
      *         unambiguously to a single executable.
      */
    private def getExistingExecutable(name: String, digest: String): Option[DxDataObject] = {
      // return the application if it already exists in the project
      executableDir
        .lookupInProject(name, digest)
        .map { executable =>
          executable.desc match {
            case Some(desc: DxAppletDescribe) =>
              logger.trace(
                  s"Found existing applet ${desc.id} with name ${name} in folder ${desc.folder}"
              )
            case Some(desc: DxWorkflowDescribe) =>
              logger.trace(
                  s"Found existing workflow ${desc.id} with name ${name} in folder ${desc.folder}"
              )
            case _ => throw new Exception(s"invalid executable ${executable}")
          }
          executable.dataObj
        }
        .orElse {
          val (matching, nonMatching) =
            executableDir.lookup(name).partition(_.checksum.contains(digest)) match {
              case (matching, nonMatching) if matching.size > 1 =>
                logger.trace(
                    s"""Existing executable(s) ${matching.map(_.dataObj.id).mkString(",")} 
                       |with ${name} have the same digest; selecting most recent""".stripMargin
                      .replaceAll("\n", " ")
                )
                val sorted = matching.sortBy(_.createdDate)
                (Some(sorted.last.dataObj), nonMatching ++ sorted.dropRight(1))
              case (Vector(matching), nonMatching) => (Some(matching.dataObj), nonMatching)
              case (_, nonMatching)                => (None, nonMatching)
            }

          if (nonMatching.nonEmpty) {
            val idStr = nonMatching.map(_.dataObj.id).mkString(",")
            if (archive) {
              logger.trace(
                  s"""Executable(s) ${idStr} with name ${name} have changed;
                     |archiving existing executable(s) before rebuilding""".stripMargin
                    .replaceAll("\n", " ")
              )
              try {
                executableDir.archive(nonMatching)
              } catch {
                case t: Throwable =>
                  throw new Exception(s"unable to archive existing executable(s) ${idStr}", t)
              }
            } else if (force) {
              logger.trace(
                  s"""Executable(s) ${idStr} with name ${name} have changed;
                     |deleting existing executable(s) before rebuilding""".stripMargin
                    .replaceAll("\n", " ")
              )
              try {
                executableDir.remove(nonMatching)
              } catch {
                case t: Throwable =>
                  throw new Exception(s"unable to delete existing executable(s) ${idStr}", t)
              }
            } else {
              throw new Exception(
                  s"""executable(s) ${idStr} with name ${name} exist in ${project.id}:${folder};
                     |compile with the -a flag to archive them or -f to delete them""".stripMargin
                    .replaceAll("\n", " ")
              )
            }
          }

          matching.foreach { m =>
            logger.trace(s"Existing executable ${m.id} with name ${name} has not changed")
          }

          matching
        }
    }

    private def getIdFromResponse(response: JsObject): String = {
      response.fields.get("id") match {
        case Some(JsString(x)) => x
        case None              => throw new Exception("API call did not returnd an ID")
        case other             => throw new Exception(s"API call returned invalid ID ${other}")
      }
    }

    /**
      * Builds an applet if it doesn't exist or has changed since the last
      * compilation, otherwise returns the existing applet.
      * @param applet the applet IR
      * @param dependencyDict previously compiled executables that can be linked
      * @return
      */
    private def maybeBuildApplet(
        applet: Application,
        dependencyDict: Map[String, CompiledExecutable]
    ): (DxApplet, Vector[ExecutableLink]) = {
      logger2.trace(s"Compiling applet ${applet.name}")
      val appletCompiler =
        ApplicationCompiler(
            typeAliases = bundle.typeAliases,
            runtimeAsset = runtimeAsset,
            runtimeJar = runtimeJar,
            runtimePathConfig = runtimePathConfig,
            runtimeTraceLevel = runtimeTraceLevel,
            separateOutputs = separateOutputs,
            streamFiles = streamFiles,
            waitOnUpload = waitOnUpload,
            extras = extras,
            parameterLinkSerializer = parameterLinkSerializer,
            useManifests = useManifests,
            complexPathValues = complexPathValues,
            instanceTypeSelection = instanceTypeSelection,
            defaultInstanceType,
            fileResolver = fileResolver,
            dxApi = dxApi,
            logger = logger2,
            project = project,
            folder = folder
        )
      // limit the applet dictionary to actual dependencies
      val dependencies: Map[String, ExecutableLink] = applet.kind match {
        case ExecutableKindWfFragment(call, _, _, _) =>
          call.map { name =>
            val CompiledExecutable(irCall, dxObj, _, _) = dependencyDict(name)
            name -> createLinkForCall(irCall, dxObj)
          }.toMap
        case _ => Map.empty
      }
      // Calculate a checksum of the inputs that went into the making of the applet.
      val (appletApiRequest, digest) = checksumRequest(
          applet.name,
          appletCompiler.apply(applet, dependencies)
      )
      // write the request to a file, in case we need it for debugging
      if (logger2.traceLevel >= TraceLevel.Verbose) {
        val requestFile = s"${applet.name}_req.json"
        FileUtils.writeFileContent(appCompileDirPath.resolve(requestFile),
                                   JsObject(appletApiRequest).prettyPrint)
      }
      // fetch existing applet or build a new one
      val dxApplet = getExistingExecutable(applet.dxName, digest) match {
        case Some(dxApplet: DxApplet) =>
          // applet exists and it has not changed
          dxApplet
        case None =>
          // build a new applet
          val response = dxApi.appletNew(appletApiRequest)
          val id = getIdFromResponse(response)
          val dxApplet = dxApi.applet(id)
          executableDir.insert(applet.name, dxApplet, digest)
          dxApplet
        case other =>
          throw new Exception(s"expected applet ${other}")
      }
      (dxApplet, dependencies.values.toVector)
    }

    /**
      * Builds a workflow if it doesn't exist or has changed since the last
      * compilation, otherwise returns the existing workflow.
      * @param workflow the workflow to compile
      * @param dependencyDict previously compiled executables that can be linked
      * @return
      */
    private def maybeBuildWorkflow(
        workflow: Workflow,
        dependencyDict: Map[String, CompiledExecutable]
    ): (DxWorkflow, JsValue) = {
      logger2.trace(s"Compiling workflow ${workflow.name}")
      val workflowCompiler =
        WorkflowCompiler(separateOutputs,
                         extras,
                         parameterLinkSerializer,
                         useManifests,
                         complexPathValues,
                         fileResolver,
                         project,
                         instanceTypeSelection,
                         dxApi,
                         logger2)
      // Calculate a checksum of the inputs that went into the making of the applet.
      val (workflowApiRequest, execTree) = workflowCompiler.apply(workflow, dependencyDict)
      val (requestWithChecksum, digest) = checksumRequest(workflow.name, workflowApiRequest)
      // Add properties we do not want to fall under the checksum.
      // This allows, for example, moving the dx:executable, while
      // still being able to reuse it.
      val updatedRequest = requestWithChecksum ++ Map(
          "project" -> JsString(project.id),
          "folder" -> JsString(folder),
          "parents" -> JsBoolean(true)
      )
      val dxWf = getExistingExecutable(workflow.dxName, digest) match {
        case Some(wf: DxWorkflow) =>
          // workflow exists and has not changed
          wf
        case None =>
          val response = dxApi.workflowNew(updatedRequest)
          val id = getIdFromResponse(response)
          val dxWorkflow = dxApi.workflow(id)
          if (!leaveWorkflowsOpen) {
            // Close the workflow
            dxWorkflow.close()
          }
          executableDir.insert(workflow.name, dxWorkflow, digest)
          dxWorkflow
        case other =>
          throw new Exception(s"expected a workflow, got ${other}")
      }
      (dxWf, execTree)
    }

    def apply: CompilerResults = {
      logger.trace(
          s"Generate dx:applets and dx:workflows for ${bundle} in ${project.id}${folder}"
      )
      val executables = bundle.dependencies.foldLeft(Map.empty[String, CompiledExecutable]) {
        case (accu, name) =>
          bundle.allCallables(name) match {
            case application: Application =>
              val execRecord = application.kind match {
                case _: ExecutableKindNative if useManifests =>
                  throw new Exception("cannot use manifest files with native app(let)s")
                case ExecutableKindNative(ExecutableType.App | ExecutableType.Applet,
                                          Some(id),
                                          _,
                                          _,
                                          _) =>
                  // native app(let)s do not depend on other data-objects
                  CompiledExecutable(application, dxApi.executable(id))
                case ExecutableKindNative(ExecutableType.Applet, _, _, project, Some(path)) =>
                  val applet = dxApi.resolveDataObject(path, project.map(dxApi.project)) match {
                    case applet: DxApplet => applet
                    case _ =>
                      throw new Exception(
                          s"${path} in ${project.getOrElse("current project")} is not an applet"
                      )
                  }
                  CompiledExecutable(application, applet)
                case ExecutableKindNative(ExecutableType.App, _, Some(name), _, _) =>
                  CompiledExecutable(application, dxApi.resolveApp(name))
                case ExecutableKindWorkflowCustomReorg(id) =>
                  // for now, we assume the user has built their reorg applet to handle manifest
                  // input if useManifests = true
                  CompiledExecutable(application, dxApi.executable(id))
                case _ =>
                  val (dxApplet, dependencies) = maybeBuildApplet(application, accu)
                  CompiledExecutable(application, dxApplet, dependencies)
              }
              accu + (application.name -> execRecord)
            case wf: Workflow =>
              val (dxWorkflow, execTree) = maybeBuildWorkflow(wf, accu)
              accu + (wf.name -> CompiledExecutable(wf, dxWorkflow, execTree = Some(execTree)))
          }
      }
      val primary: Option[CompiledExecutable] = bundle.primaryCallable.flatMap { c =>
        executables.get(c.name)
      }
      CompilerResults(primary, executables)
    }
  }

  /**
    * Compile the IR bundle to a native applet or workflow.
    * @param bundle the IR bundle
    * @param project the destination project
    * @param folder the destination folder
    */
  def apply(bundle: Bundle, project: DxProject, folder: String): CompilerResults = {
    BundleCompiler(bundle, project, folder).apply
  }
}
