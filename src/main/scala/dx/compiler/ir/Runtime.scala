package dx.compiler.ir

import dx.api.DxFile

/**
  * Representation of the parts of dxapp.json `runSpec` that can be specified.
  */
object Runtime {
  sealed abstract class Requirement
  final case class RestartRequirement(max: Option[Int] = None,
                                      default: Option[Int] = None,
                                      errors: Map[String, Int] = Map.empty)
      extends Requirement
  final case class TimeoutRequirement(days: Option[Int] = None,
                                      hours: Option[Int] = None,
                                      minutes: Option[Int] = None)
      extends Requirement
  final case class IgnoreReuseRequirement(value: Boolean) extends Requirement
  final case class AccessRequirement(network: Vector[String] = Vector.empty,
                                     project: Option[String] = None,
                                     allProjects: Option[String] = None,
                                     developer: Option[Boolean] = None,
                                     projectCreation: Option[Boolean] = None)
      extends Requirement

  /**
    * Specification of instance type.
    *  An instance could be:
    *  Default: the platform default, useful for auxiliary calculations.
    *  Static:  instance type is known at compile time. We can start the
    *           job directly on the correct instance type.
    *  Dynamic: WDL specifies a calculation for the instance type, based
    *           on information known only at runtime. The generated applet
    *           will need to evalulate the expressions at runtime, and then
    *           start another job on the correct instance type.
    */
  sealed trait InstanceType
  case object DefaultInstanceType extends InstanceType
  case class StaticInstanceType(
      dxInstanceType: Option[String],
      memoryMB: Option[Int],
      diskGB: Option[Int],
      cpu: Option[Int],
      gpu: Option[Boolean]
  ) extends InstanceType
  case object DynamicInstanceType extends InstanceType

  /**
    * A task may specify a container image to run under. Currently, DNAnexus only
    * supports Docker images. There are three supported options:
    *   None:    no image
    *   Network: the image resides on a network site and requires download
    *   DxFile: the image is a platform file
    */
  sealed trait ContainerImage
  case object NoImage extends ContainerImage
  case object NetworkDockerImage extends ContainerImage
  case class DxFileDockerImage(url: String, tarball: DxFile) extends ContainerImage
}
