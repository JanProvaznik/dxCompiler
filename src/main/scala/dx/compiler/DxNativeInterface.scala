package dx.compiler

import java.nio.file.{Files, Path}

import dx.api.{DxApi, DxApp, DxApplet, DxFindApps, DxFindDataObjects, DxProject}
import dx.core.getVersion
import wdlTools.util.FileUtils

abstract class DxNativeInterface(dxApi: DxApi) {
  protected def getApplet(dxProject: DxProject, path: String): DxApplet = {
    path match {
      case id if id.startsWith("applet-") => dxApi.applet(id)
      case _ =>
        dxApi.resolveOnePath(path, Some(dxProject)) match {
          case applet: DxApplet => applet
          case _                => throw new Exception(s"DxNI only supports apps and applets")
        }
    }
  }

  private def searchApplets(dxProject: DxProject,
                            folder: String,
                            recursive: Boolean): Vector[DxApplet] = {
    val applets: Vector[DxApplet] =
      DxFindDataObjects(dxApi)
        .apply(Some(dxProject),
               Some(folder),
               recursive,
               classRestriction = Some("applet"),
               withInputOutputSpec = true)
        .keySet
        .collect {
          case applet: DxApplet
              if applet.describe().properties.exists(_.contains(ChecksumProperty)) =>
            applet
        }
        .toVector
    if (applets.isEmpty) {
      dxApi.logger.trace(s"Found no applets in project ${dxProject.id}/${folder}")
    }
    applets
  }

  private def searchApps: Vector[DxApp] = {
    val apps: Vector[DxApp] = DxFindApps(dxApi)
      .apply(published = Some(true), withInputOutputSpec = true)
    if (apps.isEmpty) {
      dxApi.logger.warning(s"Found no DX global apps")
    }
    apps
  }

  private def writeToFile(doc: Vector[String], outputPath: Path, force: Boolean): Unit = {
    if (Files.exists(outputPath)) {
      if (!force) {
        throw new Exception(
            s"""|Output file ${outputPath.toString} already exists,
                |use -force to overwrite it""".stripMargin
              .replaceAll("\n", " ")
        )
      }
      outputPath.toFile.delete
    }
    FileUtils.writeFileContent(outputPath, doc.mkString("\n"))
  }

  private def appletsHeader(dxProject: DxProject, path: String) = Vector(
      s"This file was generated by the Dx Native Interface (DxNI) tool ${getVersion}.",
      s"project name = ${dxProject.describe().name}",
      s"project ID = ${dxProject.getId}",
      s"path = ${path}"
  )

  val appsHeader = Vector(
      s"This file was generated by the Dx Native Interface (DxNI) tool ${getVersion}.",
      "These are interfaces to apps."
  )

  protected def generate(apps: Vector[DxApp] = Vector.empty,
                         applets: Vector[DxApplet] = Vector.empty,
                         headerLines: Vector[String]): Vector[String]

  /**
    * Generate only apps.
    * @param output output file
    * @param force overwrite existing file
    */
  def apply(output: Path, force: Boolean): Unit = {
    val apps = searchApps
    if (apps.nonEmpty) {
      val doc = generate(apps, headerLines = appsHeader)
      if (doc.nonEmpty) {
        writeToFile(doc, output, force)
      }
    }
  }

  def apply(output: Path,
            dxProject: DxProject,
            folder: Option[String] = None,
            path: Option[String] = None,
            applet: Option[DxApplet] = None,
            recursive: Boolean = false,
            force: Boolean = false): Unit = {
    val apps = searchApps
    val (applets: Vector[DxApplet], search) = (folder, path, applet) match {
      case (Some(folder), None, None) => (searchApplets(dxProject, folder, recursive), folder)
      case (None, Some(path), None)   => (Vector(getApplet(dxProject, path)), path)
      case (None, None, Some(applet)) => (Vector(applet), applet.id)
      case _                          => throw new Exception("must specify exactly one of (folder, path)")
    }
    val doc = generate(apps, applets, appletsHeader(dxProject, search))
    if (doc.nonEmpty) {
      writeToFile(doc, output, force)
    }
  }
}
