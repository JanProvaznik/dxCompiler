package dx.compiler

import java.nio.file.{Path, Paths}

import com.typesafe.config.ConfigFactory
import dx.api.{DxApi, DxApplet, DxDataObject, DxProject}
import dx.compiler.ExecTreeFormat.ExecTreeFormat
import dx.compiler.Main.CompilerFlag.CompilerFlag
import dx.compiler.ir.{Bundle, LanguageSupport}
import dx.compiler.wdl.WdlLanguageSupportFactory
import dx.core.getVersion
import dx.core.io.{DxFileAccessProtocol, DxPathConfig}
import dx.core.languages.Language
import dx.core.languages.Language.Language
import dx.core.util.MainUtils._
import spray.json._
import wdlTools.util.{Enum, FileSourceResolver, Logger, TraceLevel}

/**
  * Compiler CLI.
  */
object Main {
  private val DefaultRuntimeTraceLevel: Int = TraceLevel.Verbose

  private case class LanguageOptionSpec() extends OptionSpec {

    /**
      * Parses a language argument. Accepts the following:
      *  'cwl'
      *  'cwlv1.2'
      *  'cwl 1.2'
      *  'draft2' -> WDL draft2
      */
    override def parseValues(name: String, values: Vector[String], curValue: Option[Opt]): Opt = {
      if (curValue.nonEmpty) {
        throw OptionParseException(s"Option ${name} specified multiple times")
      }
      val language = values match {
        case Vector(language) =>
          Language.parse(Some(language))
        case Vector(language, version) =>
          Language.parse(Some(version), Some(language))
        case _ =>
          throw OptionParseException(s"Unexpected value ${values} to option ${name}")
      }
      SingleValueOption[Language](language)
    }
  }

  private val SimpleOptions: OptionSpecs = Map(
      "help" -> FlagOptionSpec.Default,
      "quiet" -> FlagOptionSpec.Default,
      "verbose" -> FlagOptionSpec.Default,
      "verboseKey" -> StringOptionSpec.List
  )

  private def parseCommandLine(
      args: Vector[String],
      spec: OptionSpecs,
      deprecated: Set[String] = Set.empty
  ): Either[Termination, Options] = {
    if (args.isEmpty) {
      return Left(BadUsageTermination("WDL file to compile is missing"))
    }
    val options =
      try {
        splitCommandLine(args, SimpleOptions ++ spec, deprecated)
      } catch {
        case e: OptionParseException =>
          return Left(BadUsageTermination("Error parsing command line options", Some(e)))
      }
    if (options.getFlag("help")) {
      return Left(BadUsageTermination())
    }
    Right(options)
  }

  private val CommonOptions: OptionSpecs = Map(
      "destination" -> StringOptionSpec.One,
      "force" -> FlagOptionSpec.Default,
      "f" -> FlagOptionSpec.Default.copy(alias = Some("force")),
      "overwrite" -> FlagOptionSpec.Default.copy(alias = Some("force")),
      "folder" -> StringOptionSpec.One,
      "project" -> StringOptionSpec.One,
      "language" -> LanguageOptionSpec()
  )

  private def initCommon(options: Options): FileSourceResolver = {
    val logger = initLogger(options)
    val imports: Vector[Path] = options.getList[Path]("imports")
    val fileResolver = FileSourceResolver.create(
        imports,
        Vector(DxFileAccessProtocol()),
        logger
    )
    FileSourceResolver.set(fileResolver)
    fileResolver
  }

  private def getLanguageSupport(
      options: Options,
      fileResolver: FileSourceResolver,
      sourceFile: Option[Path] = None,
      defaultValue: Option[Language] = None
  ): LanguageSupport[_] = {
    val supportedLanguages = Vector(
        WdlLanguageSupportFactory(fileResolver)
    )
    val languageOpt = options.getValue[Language]("language").orElse(defaultValue)
    val extrasPath = options.getValue[Path]("extras")
    languageOpt match {
      case Some(language) =>
        supportedLanguages
          .collectFirst { factory =>
            factory.create(language, extrasPath) match {
              case Some(support) => support
            }
          }
          .getOrElse(
              throw OptionParseException(s"Language ${language} is not supported")
          )
      case None if sourceFile.isDefined =>
        supportedLanguages
          .collectFirst { factory =>
            factory.create(sourceFile.get, extrasPath) match {
              case Some(support) => support
            }
          }
          .getOrElse(
              throw OptionParseException(
                  s"Could not detect language from source file ${sourceFile}"
              )
          )
      case _ =>
        throw OptionParseException("Could not determine language")
    }
  }

  // compile

  case class SuccessIR(bundle: Bundle, override val message: String = "Intermediate representation")
      extends SuccessfulTermination

  case class SuccessPrettyTree(pretty: String) extends SuccessfulTermination {
    def message: String = pretty
  }
  case class SuccessJsonTree(jsValue: JsValue) extends SuccessfulTermination {
    lazy val message: String = jsValue match {
      case JsNull => ""
      case _      => jsValue.prettyPrint
    }
  }

  object CompilerAction extends Enum {
    type CompilerAction = Value
    val Compile, Config, DxNI, Version, Describe = Value
  }

  object CompilerFlag extends Enum {
    type CompilerFlag = Value
    val All, IR, NativeWithoutRuntimeAsset = Value
  }

  private case class CompileModeOptionSpec()
      extends SingleValueOptionSpec[CompilerFlag](choices = CompilerFlag.values) {
    override def parseValue(value: String): CompilerFlag =
      CompilerFlag.withNameIgnoreCase(value)
  }

  private case class ExecTreeFormatOptionSpec()
      extends SingleValueOptionSpec[ExecTreeFormat](choices = ExecTreeFormat.values) {
    override def parseValue(value: String): ExecTreeFormat =
      ExecTreeFormat.withNameIgnoreCase(value)
  }

  private def CompileOptions: OptionSpecs = Map(
      "archive" -> FlagOptionSpec.Default,
      "compileMode" -> CompileModeOptionSpec(),
      "defaults" -> PathOptionSpec.MustExist,
      "execTree" -> ExecTreeFormatOptionSpec(),
      "extras" -> PathOptionSpec.MustExist,
      "inputs" -> PathOptionSpec.ListMustExist,
      "input" -> PathOptionSpec.ListMustExist.copy(alias = Some("inputs")),
      "locked" -> FlagOptionSpec.Default,
      "leaveWorkflowsOpen" -> FlagOptionSpec.Default,
      "imports" -> PathOptionSpec.ListMustExist,
      "p" -> PathOptionSpec.ListMustExist.copy(alias = Some("imports")),
      "projectWideReuse" -> FlagOptionSpec.Default,
      "reorg" -> FlagOptionSpec.Default,
      "runtimeDebugLevel" -> IntOptionSpec.One.copy(choices = Set(0, 1, 2)),
      "streamAllFiles" -> FlagOptionSpec.Default
  )

  private val DeprecatedCompileOptions = Set(
      "fatalValidationWarnings"
  )

  private def resolveDestination(
      project: String,
      folder: Option[String] = None,
      path: Option[String] = None
  ): (DxProject, Either[String, DxDataObject]) = {
    val dxProject =
      try {
        DxApi.get.resolveProject(project)
      } catch {
        case t: Throwable =>
          throw new Exception(
              s"""|Could not find project ${project}, you probably need to be logged into
                  |the platform""".stripMargin,
              t
          )
      }
    val folderOrPath = (folder, path) match {
      case (Some(f), None) =>
        // Validate the folder.
        // TODO: check for folder existance rather than listing the contents, which could
        //   be very large.
        dxProject.listFolder(f)
        Left(f)
      case (None, Some(p)) =>
        // validate the file
        val dataObj = DxApi.get.resolveOnePath(p, Some(dxProject))
        Right(dataObj)
      case _ =>
        throw OptionParseException("must specify exactly one of (folder, path)")
    }
    Logger.get.trace(s"""|project ID: ${dxProject.id}
                         |path: ${folderOrPath}""".stripMargin)
    (dxProject, folderOrPath)
  }

  private def resolveDestination(project: String, folder: String): (DxProject, String) = {
    resolveDestination(project, Some(folder)) match {
      case (dxProject, Left(folder)) => (dxProject, folder)
      case _                         => throw new Exception("expected folder")
    }
  }

  // There are three possible syntaxes:
  //    project-id:/folder
  //    project-id:
  //    /folder
  private def getDestination(options: Options): (DxProject, String) = {
    val destinationOpt: Option[String] = options.getValue[String]("destination")
    val folderOpt: Option[String] = options.getValue[String]("folder")
    val projectOpt: Option[String] = options.getValue[String]("project")
    val destRegexp = "(.+):(.*)".r
    val (project, folder) = (destinationOpt, projectOpt, folderOpt) match {
      case (Some(destRegexp(project, folder)), _, _) if folder.startsWith("/") =>
        (project, folder)
      case (Some(destRegexp(project, emptyFolder)), _, Some(folder)) if emptyFolder.trim.isEmpty =>
        (project, folder)
      case (Some(destRegexp(project, emptyFolder)), _, None) if emptyFolder.trim.isEmpty =>
        (project, "/")
      case (Some(destRegexp(_, folder)), _, None) =>
        throw OptionParseException(s"Invalid folder <${folder}>")
      case (Some(folder), Some(project), _) if folder.startsWith("/") =>
        (project, folder)
      case (Some(folder), None, _) if folder.startsWith("/") =>
        throw OptionParseException("Project is unspecified")
      case (Some(other), _, _) =>
        throw OptionParseException(s"Invalid destination <${other}>")
      case (None, Some(project), Some(folder)) =>
        (project, folder)
      case (None, Some(project), None) =>
        (project, "/")
      case _ =>
        throw OptionParseException("Project is unspecified")
    }
    resolveDestination(project, folder)
  }

  private[compiler] def compile(args: Vector[String]): Termination = {
    val sourceFile: Path = args.headOption
      .map(Paths.get(_))
      .getOrElse(
          throw OptionParseException(
              "Missing required positional argument <WDL file>"
          )
      )
    val options: Options =
      parseCommandLine(args.tail, CommonOptions ++ CompileOptions, DeprecatedCompileOptions) match {
        case Left(termination) => return termination
        case Right(options)    => options
      }
    val fileResolver = initCommon(options)
    // language-specific features
    val languageSupport = getLanguageSupport(options, fileResolver, Some(sourceFile))
    // Extras parsing is language-specific
    val extras = languageSupport.getExtras
    if (extras.isDefined && extras.get.customReorgAttributes.isDefined) {
      Set("reorg", "locked").foreach { opt =>
        if (options.contains(opt)) {
          throw OptionParseException(
              s"ERROR: cannot provide --reorg option when ${opt} is specified in extras."
          )
        }
      }
    }
    // other non-flag options
    val compileMode: CompilerFlag =
      options.getValueOrElse[CompilerFlag]("compileMode", CompilerFlag.All)
    val (dxProject, folder) = getDestination(options)
    val defaults: Option[Path] = options.getValue[Path]("defaults")
    val inputs: Vector[Path] = options.getList[Path]("inputs")
    val execTreeFormat: Option[ExecTreeFormat] = options.getValue[ExecTreeFormat]("execTree")
    val runtimeTraceLevel: Int =
      options.getValueOrElse[Int]("runtimeDebugLevel", DefaultRuntimeTraceLevel)
    // flags
    val Vector(
        archive,
        force,
        leaveWorkflowsOpen,
        locked,
        projectWideReuse,
        reorg,
        streamAllFiles
    ) = Vector(
        "archive",
        "force",
        "leaveWorkflowsOpen",
        "locked",
        "projectWideReuse",
        "reorg",
        "streamAllFiles"
    ).map(options.getFlag(_))

    try {
      // generate IR
      val translator = languageSupport.getTranslator
      val bundle = translator.apply(
          sourceFile,
          dxProject,
          inputs,
          defaults,
          locked,
          if (reorg) Some(true) else None
      )
      if (compileMode == CompilerFlag.IR) {
        return SuccessIR(bundle)
      }
      // compile to native
      val includeAsset = compileMode == CompilerFlag.NativeWithoutRuntimeAsset
      val dxPathConfig = DxPathConfig.apply(baseDNAxDir, streamAllFiles)
      val compiler = Compiler(
          includeAsset,
          extras,
          execTreeFormat,
          runtimeTraceLevel,
          archive,
          force,
          leaveWorkflowsOpen,
          locked,
          projectWideReuse,
          dxPathConfig,
          fileResolver
      )
      val (retval, treeDesc) = compiler.apply(bundle, folder, dxProject)
      treeDesc match {
        case None                   => Success(retval)
        case Some(Left(treePretty)) => SuccessPrettyTree(treePretty)
        case Some(Right(treeJs))    => SuccessJsonTree(treeJs)
      }
    } catch {
      case e: Throwable =>
        Failure(exception = Some(e))
    }
  }

  // DxNI

  private def DxNIOptions: OptionSpecs = Map(
      "appsOnly" -> FlagOptionSpec.Default,
      "apps" -> FlagOptionSpec.Default.copy(alias = Some("appsOnly")),
      "path" -> StringOptionSpec.One,
      "outputFile" -> PathOptionSpec.Default,
      "output" -> PathOptionSpec.Default.copy(alias = Some("outputFile")),
      "o" -> PathOptionSpec.Default.copy(alias = Some("outputFile")),
      "recursive" -> FlagOptionSpec.Default,
      "r" -> FlagOptionSpec.Default.copy(alias = Some("recursive"))
  )

  private[compiler] def dxni(args: Vector[String]): Termination = {
    val options = parseCommandLine(args, CommonOptions ++ DxNIOptions) match {
      case Left(termination) => return termination
      case Right(options)    => options
    }
    val fileResolver = initCommon(options)
    val languageSupport =
      getLanguageSupport(options, fileResolver, defaultValue = Some(Language.WdlDefault))
    val dxni = languageSupport.getDxNativeInterface
    val outputFile: Path = options.getRequiredValue[Path]("outputFile")
    // flags
    val Vector(
        appsOnly,
        force,
        recursive
    ) = Vector(
        "appsOnly",
        "force",
        "recursive"
    ).map(options.getFlag(_))
    if (appsOnly) {
      try {
        dxni.apply(outputFile, force)
        Success()
      } catch {
        case e: Throwable => Failure(exception = Some(e))
      }
    } else {
      val project: String = options.getRequiredValue[String]("project")
      val folderOpt = options.getValue[String]("folder")
      val pathOpt = options.getValue[String]("path")
      val (dxProject, folderOrFile) = resolveDestination(project, folderOpt, pathOpt)
      try {
        folderOrFile match {
          case Left(folder) =>
            dxni.apply(outputFile,
                       dxProject,
                       folder = Some(folder),
                       recursive = recursive,
                       force = force)
          case Right(applet: DxApplet) =>
            dxni.apply(outputFile,
                       dxProject,
                       applet = Some(applet),
                       recursive = recursive,
                       force = force)
          case _ =>
            throw OptionParseException(
                s"Invalid folder/path ${folderOrFile}"
            )
        }
        Success()
      } catch {
        case e: Throwable => Failure(exception = Some(e))
      }
    }
  }

  // describe

  private def DescribeOptions: OptionSpecs = Map(
      "pretty" -> FlagOptionSpec.Default
  )

  private[compiler] def describe(args: Vector[String]): Termination = {
    val workflowId = args.headOption.getOrElse(
        throw OptionParseException(
            "Missing required positional argument <WDL file>"
        )
    )
    val options = parseCommandLine(args.tail, DescribeOptions) match {
      case Left(termination) => return termination
      case Right(options)    => options
    }
    try {
      val wf = DxApi.get.workflow(workflowId)
      val execTreeJS = ExecTree.fromDxWorkflow(wf)
      if (options.getFlag("pretty")) {
        val prettyTree = ExecTree.generateTreeFromJson(execTreeJS.asJsObject)
        SuccessPrettyTree(prettyTree)
      } else {
        SuccessJsonTree(execTreeJS)
      }
    } catch {
      case e: Throwable => BadUsageTermination(exception = Some(e))
    }
  }

  private[compiler] def dispatchCommand(args: Vector[String]): Termination = {
    if (args.isEmpty) {
      return BadUsageTermination()
    }
    val action =
      try {
        CompilerAction.withNameIgnoreCase(args.head.replaceAll("_", ""))
      } catch {
        case _: NoSuchElementException =>
          return BadUsageTermination()
      }
    try {
      action match {
        case CompilerAction.Compile  => compile(args.tail)
        case CompilerAction.Describe => describe(args.tail)
        case CompilerAction.DxNI     => dxni(args.tail)
        case CompilerAction.Config   => Success(ConfigFactory.load().toString)
        case CompilerAction.Version  => Success(getVersion)
      }
    } catch {
      case e: Throwable =>
        BadUsageTermination(exception = Some(e))
    }
  }

  private val usageMessage =
    s"""|java -jar dxWDL.jar <action> <parameters> [options]
        |
        |Actions:
        |  version
        |    Prints the dxWDL version
        |  
        |  config
        |    Prints the current dxWDL configuration
        |  
        |  describe <DxWorkflow ID>
        |    Generate the execution tree as JSON for a given dnanexus workflow ID.
        |    Workflow needs to be have been previoulsy compiled by dxWDL.
        |    options
        |      -pretty                Print exec tree in pretty format instead of JSON
        |
        |  compile <WDL file>
        |    Compile a wdl file into a dnanexus workflow.
        |    Optionally, specify a destination path on the
        |    platform. If a WDL inputs files is specified, a dx JSON
        |    inputs file is generated from it.
        |    options
        |      -archive               Archive older versions of applets
        |      -compileMode <string>  Compilation mode, a debugging flag
        |      -defaults <string>     File with Cromwell formatted default values (JSON)
        |      -execTree [json,pretty] Write out a json representation of the workflow
        |      -extras <string>       JSON formatted file with extra options, for example
        |                             default runtime options for tasks.
        |      -inputs <string>       File with Cromwell formatted inputs
        |      -locked                Create a locked-down workflow
        |      -leaveWorkflowsOpen    Leave created workflows open (otherwise they are closed)
        |      -p | -imports <string> Directory to search for imported WDL files
        |      -projectWideReuse      Look for existing applets/workflows in the entire project
        |                             before generating new ones. The normal search scope is the
        |                             target folder only.
        |      -reorg                 Reorganize workflow output files
        |      -runtimeDebugLevel [0,1,2] How much debug information to write to the
        |                             job log at runtime. Zero means write the minimum,
        |                             one is the default, and two is for internal debugging.
        |      -streamAllFiles        mount all files with dxfuse, do not use the download agent
        |
        |  dxni
        |    Dx Native call Interface. Create stubs for calling dx
        |    executables (apps/applets/workflows), and store them as WDL
        |    tasks in a local file. Allows calling existing platform executables
        |    without modification. Default is to look for applets.
        |    options:
        |      -appsOnly              Search only for global apps.
        |      -path <string>         Path to a specific applet
        |      -o <string>            Destination file for WDL task definitions
        |      -r | recursive         Recursive search
        |
        |Common options
        |    -help                    Print this help message and exit
        |    -destination <string>    Full platform path (project:/folder)
        |    -f | force               Delete existing applets/workflows
        |    -folder <string>         Platform folder
        |    -project <string>        Platform project
        |    -language <string> [ver] Which language to use? (wdl or cwl; can optionally specify version)
        |    -quiet                   Do not print warnings or informational outputs
        |    -verbose                 Print detailed progress reports
        |    -verboseKey <module>     Detailed information for a specific module
        |""".stripMargin

  def main(args: Seq[String]): Unit = {
    terminate(dispatchCommand(args.toVector), Some(usageMessage))
  }
}
