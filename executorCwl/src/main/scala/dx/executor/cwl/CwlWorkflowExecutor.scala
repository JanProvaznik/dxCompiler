package dx.executor.cwl

import dx.api.DxExecution
import dx.core.Constants
import dx.core.ir.Type._
import dx.core.ir.Value._
import dx.core.ir.{Block, DxName, ExecutableLink, ParameterLink, Type, TypeSerde, Value, ValueSerde}
import dx.core.languages.Language
import dx.core.languages.cwl.{
  CwlBlock,
  CwlBlockInput,
  CwlDxName,
  CwlUtils,
  DxHintSchema,
  OptionalBlockInput,
  RequiredBlockInput,
  RequirementEvaluator,
  Target,
  TargetParam
}
import dx.cwl.{
  ArrayValue,
  CwlArray,
  CwlOptional,
  CwlRecord,
  CwlType,
  CwlValue,
  DirectoryValue,
  Evaluator,
  EvaluatorContext,
  FileValue,
  HintUtils,
  Identifiable,
  LinkMergeMethod,
  Loadable,
  NullValue,
  ObjectValue,
  Parser,
  ParserResult,
  PickValueMethod,
  ScatterMethod,
  Workflow,
  WorkflowOutputParameter,
  WorkflowStepInput
}
import dx.executor.{JobMeta, WorkflowExecutor}
import dx.util.TraceLevel
import spray.json._

import java.net.URI

object CwlWorkflowExecutor {
  def create(jobMeta: JobMeta, separateOutputs: Boolean): CwlWorkflowExecutor = {
    // when parsing a packed workflow as a String, we need to use a baseuri -
    // it doesn't matter what it is
    val parser = Parser.create(Some(URI.create("file:/null")), hintSchemas = Vector(DxHintSchema))
    parser.detectVersionAndClass(jobMeta.sourceCode) match {
      case Some((version, "Workflow")) if Language.parse(version) == Language.CwlV1_2 =>
        ()
      case _ =>
        throw new Exception(
            s"""source code does not appear to be a CWL Workflow document of a supported version
               |${jobMeta.sourceCode}""".stripMargin
        )
    }
    val wfName = jobMeta.getExecutableAttribute("name") match {
      case Some(JsString(name)) => name
      case _                    => throw new Exception("missing executable name")
    }
    val workflow =
      parser.parseString(jobMeta.sourceCode, defaultFrag = Some(wfName), isPacked = true) match {
        case ParserResult(wf: Workflow, _, _, _) => wf
        case other =>
          throw new Exception(s"expected CWL document to contain a Workflow, not ${other}")
      }
    CwlWorkflowExecutor(workflow, jobMeta, separateOutputs)
  }
}

case class CwlWorkflowExecutor(workflow: Workflow, jobMeta: JobMeta, separateOutputs: Boolean)
    extends WorkflowExecutor[CwlBlock](jobMeta, separateOutputs) {
  private val logger = jobMeta.logger

  override val executorName: String = "dxExecutorCwl"

  override protected lazy val typeAliases: Map[String, Type.TSchema] = {
    HintUtils.getSchemaDefs(workflow.requirements).collect {
      case (name, schema: CwlRecord) => name -> CwlUtils.toIRSchema(schema)
    }
  }

  // TODO: deal with steps with multiple input sources that need to be picked/merged

  override protected def evaluateInputs(
      jobInputs: Map[DxName, (Type, Value)]
  ): Map[DxName, (Type, Value)] = {
    // This might be the input for the entire workflow or just a subblock.
    // If it is for a sublock, it may be for the body of a conditional or
    // scatter, in which case we only need the inputs of the body statements.
    val inputs = jobMeta.blockPath match {
      case Vector() =>
        if (logger.isVerbose) {
          logger.trace(
              s"""input parameters:
                 |${workflow.inputs
                   .map { inp =>
                     s"  ${CwlUtils.prettyFormatType(inp.cwlType)} ${inp.name}"
                   }
                   .mkString("\n")}""".stripMargin
          )
        }
        jobInputs
      case path =>
        val block: CwlBlock = Block.getSubBlockAt(CwlBlock.createBlocks(workflow), path)
        val inputTypes = block.inputs.map {
          case RequiredBlockInput(name, param) =>
            name -> CwlUtils.toIRType(param.cwlType)
          case OptionalBlockInput(name, param) =>
            val irType = CwlUtils.toIRType(param.cwlType)
            name -> Type.ensureOptional(irType)
        }.toMap
        if (logger.isVerbose) {
          logger.trace(
              s"""input parameters:
                 |${inputTypes
                   .map {
                     case (name, irType) =>
                       s"  ${TypeSerde.toString(irType)} ${name}"
                   }
                   .mkString("\n")}""".stripMargin
          )
        }
        jobInputs.collect {
          case (name, (_, v)) if inputTypes.contains(name) =>
            // coerce the input value to the target type
            val irType = inputTypes(name)
            name -> (irType, Value.coerceTo(v, irType))
          case i => i
        }
    }
    if (logger.isVerbose) {
      logger.trace(
          s"""input values:
             |${inputs
               .map {
                 case (name, (t, v)) =>
                   s"${TypeSerde.toString(t)} ${name} = ${ValueSerde.toString(v)}}"
               }
               .mkString("\n")}""".stripMargin
      )
    }
    inputs
  }

  override protected def evaluateOutputs(jobInputs: Map[DxName, (Type, Value)],
                                         addReorgStatus: Boolean): Map[DxName, (Type, Value)] = {
    // This might be the output for the entire workflow or just a subblock.
    // If it is for a sublock, it may be for the body of a conditional or
    // scatter, in which case we only need the outputs of the body statements.
    val outputParams = jobMeta.blockPath match {
      case Vector() =>
        workflow.outputs.map { param =>
          (CwlDxName.fromSourceName(param.name), param, param.cwlType)
        }
      case path =>
        Block.getSubBlockAt(CwlBlock.createBlocks(workflow), path).outputs.map { out =>
          (out.name, out.source, out.cwlType)
        }
    }
    logger.trace(s"outputParams=${outputParams}")
    val irOutputs: Map[DxName, (Type, Value)] = outputParams.map {
      case (dxName, param: WorkflowOutputParameter, _) if param.sources.nonEmpty =>
        val sources =
          param.sources.map { id =>
            val dxName = CwlDxName.fromDecodedName(id.frag.get)
            jobInputs.getOrElse(dxName, (TMulti.Any, VNull))
          }
        val isArray = CwlUtils.isArray(param.cwlType)
        val (irType, irValue) = sources match {
          case Vector(src) if param.linkMerge.isEmpty => src
          case Vector((t, v: VArray))                 => (t, v)
          case Vector((t, v)) if isArray              => (t, VArray(v))
          case Vector(_) =>
            throw new Exception(
                s"parameter ${param} has a linkMerge with a single source and a non-array type"
            )
          case _ =>
            val mergedValues = if (isArray) {
              param.linkMerge match {
                case LinkMergeMethod.MergeNested => sources
                case LinkMergeMethod.MergeFlattened =>
                  sources.flatMap {
                    case (TArray(itemType, _), VArray(items)) =>
                      Iterator.continually(itemType).zip(items).toVector
                    case value => Vector(value)
                  }
                case _ =>
                  throw new Exception("output type is array and no LinkMerge method is specified")
              }
            } else {
              sources
            }
            val pickedValues = if (param.pickValue.nonEmpty) {
              val nonNull = mergedValues.filterNot(_._2 == VNull)
              param.pickValue.get match {
                case PickValueMethod.FirstNonNull =>
                  Vector(
                      nonNull.headOption
                        .getOrElse(
                            throw new Exception(
                                s"all source values are null for parameter ${param}"
                            )
                        )
                  )
                case PickValueMethod.TheOnlyNonNull =>
                  if (nonNull.size == 1) {
                    Vector(nonNull.head)
                  } else {
                    throw new Exception(
                        s"there is not exactly one non-null value for parameter ${param}"
                    )
                  }
                case PickValueMethod.AllNonNull => nonNull
              }
            } else {
              mergedValues
            }
            if (isArray) {
              val (types, values) = pickedValues.unzip
              val irType = Type.merge(types)
              (irType, Value.coerceTo(VArray(values), irType))
            } else if (pickedValues.size == 1) {
              pickedValues.head
            } else if (pickedValues.size > 1) {
              throw new Exception(
                  s"multiple output sources for non-array parameter ${param} that does not specify pickValue"
              )
            } else {
              (TMulti.Any, VNull)
            }
        }
        dxName -> (irType, irValue)
      case (dxName, _, cwlType) if jobInputs.contains(dxName) =>
        val irType = CwlUtils.toIRType(cwlType)
        val irValue = Value.coerceTo(jobInputs(dxName)._2, irType)
        dxName -> (irType, irValue)
      case (dxName, _, cwlType) if CwlOptional.isOptional(cwlType) =>
        dxName -> (CwlUtils.toIRType(cwlType), VNull)
      case (dxName, _, _) =>
        throw new Exception(s"missing required output ${dxName}")
    }.toMap
    if (addReorgStatus) {
      irOutputs + (Constants.ReorgStatus -> (TString, VString(Constants.ReorgStatusCompleted)))
    } else {
      irOutputs
    }
  }

  case class CwlBlockContext(block: CwlBlock, cwlEnv: Map[DxName, (CwlType, CwlValue)])
      extends BlockContext {
    private val step = block.target
    private lazy val runInputs = step.run.inputs.map { i =>
      CwlDxName.fromDecodedName(i.id.flatMap(_.frag).get) -> i
    }.toMap
    private lazy val blockInputs = block.inputs.map(i => i.name -> i).toMap

    override lazy val env: Map[DxName, (Type, Value)] = CwlUtils.toIR(cwlEnv)

    private def evaluateStepInput(
        stepInput: WorkflowStepInput,
        fullEnv: Map[DxName, (CwlType, CwlValue)]
    ): Option[(DxName, (CwlType, CwlValue))] = {
      val dxName = CwlDxName.fromSourceName(stepInput.name)
      val cwlType = runInputs
        .get(dxName)
        .map(_.cwlType)
        .orElse(blockInputs.get(dxName).map(_.cwlType))
        .getOrElse {
          logger.warning(
              s"""step input ${stepInput} is Znot represented in either callee or block inputs,
                 |so will not be available for evaluation""".stripMargin.replaceAll("\n", " ")
          )
          return None
        }
      // resolve all the step input sources
      // if there are multiple sources, we need to merge and/or pick values
      val sources =
        stepInput.sources.map(src => fullEnv(CwlDxName.fromDecodedName(src.frag.get)))
      val isArray = CwlUtils.isArray(cwlType)
      val (sourceType, sourceValue) = sources match {
        case Vector(src) if stepInput.linkMerge.isEmpty => src
        case Vector((t, v: ArrayValue))                 => (t, v)
        case Vector((t, v)) if isArray                  => (t, ArrayValue(Vector(v)))
        case Vector(_) =>
          throw new Exception(
              s"parameter ${stepInput} has a linkMerge with a single source and a non-array type"
          )
        case _ =>
          val mergedValues = if (isArray) {
            stepInput.linkMerge match {
              case Some(LinkMergeMethod.MergeNested) => sources
              case Some(LinkMergeMethod.MergeFlattened) =>
                sources.flatMap {
                  case (array: CwlArray, ArrayValue(items)) =>
                    items.map(i => (array.itemType, i))
                  case (t, value) =>
                    Vector((t, value))
                }
              case _ =>
                throw new Exception("output type is array and no LinkMerge method is specified")
            }
          } else {
            sources
          }
          val pickedValues = if (stepInput.pickValue.nonEmpty) {
            val nonNull = mergedValues.filterNot(_._2 == NullValue)
            stepInput.pickValue.get match {
              case PickValueMethod.FirstNonNull =>
                Vector(
                    nonNull.headOption
                      .getOrElse(
                          throw new Exception(
                              s"all source values are null for parameter ${stepInput.name}"
                          )
                      )
                )
              case PickValueMethod.TheOnlyNonNull =>
                if (nonNull.size == 1) {
                  Vector(nonNull.head)
                } else {
                  throw new Exception(
                      s"there is not exactly one non-null value for parameter ${stepInput.name}"
                  )
                }
              case PickValueMethod.AllNonNull => nonNull
            }
          } else {
            mergedValues
          }
          if (isArray) {
            val (types, values) = pickedValues.unzip
            (CwlType.flatten(types), ArrayValue(values))
          } else if (pickedValues.size == 1) {
            pickedValues.head
          } else if (pickedValues.size > 1) {
            throw new Exception(
                s"""multiple output sources for non-array parameter ${stepInput.name} that does 
                   |not specify pickValue""".stripMargin.replaceAll("\n", " ")
            )
          } else {
            (cwlType, NullValue)
          }
      }
      if (!sourceType.coercibleTo(cwlType)) {
        throw new Exception(
            s"""effective type ${sourceType} of input value ${sourceValue} to parameter ${stepInput.name}
               |is not coercible to expected type ${cwlType}""".stripMargin
              .replaceAll("\n", " ")
        )
      }
      val valueWithDefault = if (sourceValue == NullValue && stepInput.default.isDefined) {
        stepInput.default.get
      } else {
        sourceValue
      }
      Some(dxName -> (cwlType, valueWithDefault))
    }

    private def evaluateCallInputs(
        extraEnv: Map[DxName, (CwlType, CwlValue)] = Map.empty
    ): Map[DxName, (CwlType, CwlValue)] = {
      assert(step.scatter.isEmpty)
      val fullEnv = cwlEnv ++ extraEnv
      // evaluate all the step inputs - there may be step inputs that
      // are not passed to the callee but are referred to in expressions
      // of other step inputs' valueFrom fields
      val stepInputs: Map[DxName, WorkflowStepInput] = step.inputs.map { i =>
        CwlDxName.fromSourceName(i.name) -> i
      }.toMap
      val stepInputValues = stepInputs.values.flatMap(evaluateStepInput(_, fullEnv)).toMap
      // now evaluate any valueFrom expressions
      lazy val eval = Evaluator.create(block.targetRequirements, block.targetHints)
      lazy val evalInputs = EvaluatorContext.createInputs(stepInputValues.map {
        case (name, (t, v)) =>
          val param: Identifiable with Loadable = stepInputs(name)
          param -> (t, v)
      }, fileResolver = jobMeta.fileResolver)
      logger.trace(s"evalInputs=${evalInputs}")
      val finalStepInputs = stepInputs.map {
        case (dxName, param) =>
          val (t, v) = stepInputValues(dxName)
          if (param.valueFrom.isDefined) {
            val ctx = EvaluatorContext(v, evalInputs)
            dxName -> eval.evaluate(param.valueFrom.get, t, ctx)
          } else {
            dxName -> (t, v.coerceTo(t))
          }
      }
      logger.trace(s"stepInputs=${stepInputs}")
      // collect all the step input values to pass to the callee
      step.run.inputs
        .map { param =>
          CwlDxName.fromDecodedName(param.id.flatMap(_.frag).get) -> param
        }
        .map {
          case (dxName, _) if finalStepInputs.contains(dxName) =>
            dxName -> finalStepInputs(dxName)
          case (dxName, param) if CwlOptional.isOptional(param.cwlType) =>
            logger.trace(s"no input for optional input ${param.name} to step ${step.name}")
            dxName -> (param.cwlType, NullValue)
          case (_, param) =>
            throw new Exception(
                s"missing required input ${param.name} to process ${step.run.name} at step ${step.name}"
            )
        }
        .toMap
    }

    private def launchCall(
        callInputs: Map[DxName, (CwlType, CwlValue)],
        nameDetail: Option[String] = None,
        folder: Option[String] = None
    ): (DxExecution, ExecutableLink, String) = {
      logger.traceLimited(
          s"""|call = ${step}
              |callInputs = ${callInputs}
              |""".stripMargin,
          minLevel = TraceLevel.VVerbose
      )
      val executableLink = getExecutableLink(step.run.name)
      val targetCallInput = Map(Target -> (TargetParam.dxType, VString(block.target.name)))
      val callInputsIR = CwlUtils.toIR(callInputs) ++ targetCallInput
      val requirementEvaluator = RequirementEvaluator(
          block.targetRequirements,
          block.targetHints,
          callInputs.map {
            case (dxName, tv) => dxName.decoded -> tv
          },
          jobMeta.workerPaths,
          step.run.inputs.map(i => i.name -> i).toMap,
          dxApi = jobMeta.dxApi
      )
      val instanceType =
        try {
          val request = requirementEvaluator.parseInstanceType
          val instanceType = jobMeta.instanceTypeDb.apply(request)
          logger.traceLimited(s"Precalculated instance type for ${step.run.name}: ${instanceType}")
          Some(instanceType)
        } catch {
          case e: Throwable =>
            logger.traceLimited(
                s"""|Failed to precalculate the instance type for task ${step.run.name}.
                    |${e}
                    |""".stripMargin
            )
            None
        }
      val (dxExecution, execName) =
        launchJob(executableLink,
                  step.name,
                  callInputsIR,
                  nameDetail,
                  instanceType.map(_.name),
                  folder = folder,
                  prefixOutputs = true)
      (dxExecution, executableLink, execName)
    }

    override protected def launchCall(blockIndex: Int): Map[DxName, ParameterLink] = {
      val callInputs = evaluateCallInputs()
      val (dxExecution, executableLink, callName) =
        launchCall(callInputs, folder = Some(blockIndex.toString))
      jobMeta.createExecutionOutputLinks(dxExecution, executableLink.outputs, Some(callName))
    }

    override protected def launchConditional(): Map[DxName, ParameterLink] = {
      launchCall(block.index).map {
        case (key, link) => key -> link.makeOptional
      }
    }

    private def getScatterName(items: Vector[CwlValue]): String = {
      def formatItem(item: CwlValue): String = {
        item match {
          case f: FileValue if f.location.isDefined => truncate(getFileName(f.location.get))
          case f: FileValue if f.path.isDefined     => truncate(f.path.get)
          case d: DirectoryValue if d.location.isDefined =>
            truncate(getFileName(d.location.get))
          case d: DirectoryValue if d.path.isDefined => truncate(d.path.get)
          case a: ArrayValue =>
            val itemStr =
              WorkflowExecutor.getComplexScatterName(a.items.iterator.map(i => Some(formatItem(i))))
            s"[${itemStr}]"
          case o: ObjectValue =>
            val memberStr = WorkflowExecutor.getComplexScatterName(
                o.fields.iterator.map {
                  case (k, v) => Some(s"${k}: ${formatItem(v)}")
                }
            )
            s"{${memberStr}}"
          case _ => CwlUtils.prettyFormatValue(item)
        }
      }
      items.map(formatItem).mkString(",")
    }

    private def launchScatterCallJobs(scatterParams: Vector[DxName],
                                      itemTypes: Vector[CwlType],
                                      collection: Vector[Vector[CwlValue]]): Vector[DxExecution] = {
      val otherInputs = cwlEnv.view.filterKeys(!scatterParams.contains(_))
      collection.map { item =>
        val callInputs = scatterParams.zip(itemTypes.zip(item)).toMap ++ otherInputs
        val callNameDetail = getScatterName(item)
        val (dxExecution, _, _) = launchCall(callInputs, Some(callNameDetail))
        dxExecution
      }
    }

    override protected def prepareScatterResults(
        dxSubJob: DxExecution
    ): Map[DxName, ParameterLink] = {
      val resultTypes: Map[DxName, Type] = block.outputs.map { param =>
        param.name -> CwlUtils.toIRType(param.cwlType)
      }.toMap
      // Return JBORs for all the outputs. Since the signature of the sub-job
      // is exactly the same as the parent, we can immediately exit the parent job.
      val links = jobMeta.createExecutionOutputLinks(dxSubJob, resultTypes)
      if (logger.isVerbose) {
        val linkStr = links.mkString("\n")
        logger.traceLimited(s"resultTypes=${resultTypes}")
        logger.traceLimited(s"promises=${linkStr}")
      }
      links
    }

    private def getScatterValues: Vector[Option[(CwlType, Vector[CwlValue])]] = {
      step.scatter.map { src =>
        val dxName = CwlDxName.fromDecodedName(src.frag.get)
        cwlEnv.get(dxName) match {
          case Some((t, NullValue)) if CwlOptional.isOptional(t) => None
          case Some((_, ArrayValue(items))) if items.isEmpty     => None
          case Some((array: CwlArray, ArrayValue(items))) =>
            Some(array.itemType, items)
          case None =>
            throw new Exception(s"scatter parameter ${src} is missing from env")
          case _ =>
            throw new Exception(s"scatter parameter ${src} not of type array")
        }
      }
    }

    override protected def launchScatter(): Map[DxName, ParameterLink] = {
      // construct the scatter inputs and launch one job for each, such
      // that the inputs for each job are an array of one element for
      // each scattered variable
      val scatterValues = getScatterValues
      if (scatterValues.exists(_.isEmpty)) {
        // at least one of the arrays is empty, so the scatter has no results
        return Map.empty
      }
      val (itemTypes, arrays) = scatterValues.flatten.unzip
      val scatterCollection = step.scatterMethod match {
        case ScatterMethod.Dotproduct if arrays.map(_.size).toSet.size == 1 =>
          arrays.transpose
        case ScatterMethod.FlatCrossproduct | ScatterMethod.NestedCrossproduct =>
          arrays.foldLeft(Vector(Vector.empty[CwlValue])) {
            case (accu, v) => accu.flatMap(i => v.map(j => i :+ j))
          }
      }
      val (chunkCollection, next) =
        if (jobMeta.scatterStart == 0 && scatterCollection.size <= jobMeta.scatterSize) {
          (scatterCollection, None)
        } else {
          val scatterEnd = jobMeta.scatterStart + jobMeta.scatterSize
          if (scatterEnd < scatterCollection.size) {
            (scatterCollection.slice(jobMeta.scatterStart, scatterEnd), Some(scatterEnd))
          } else {
            (scatterCollection.drop(jobMeta.scatterStart), None)
          }
        }
      val childJobs = launchScatterCallJobs(
          step.scatter.map(src => CwlDxName.fromDecodedName(src.frag.get)),
          itemTypes,
          chunkCollection
      )
      next match {
        case Some(index) =>
          // there are remaining chunks - call a continue sub-job
          launchScatterContinue(childJobs, index)
        case None =>
          // this is the last chunk - call collect sub-job to gather all the results
          launchScatterCollect(childJobs)
      }
    }

    private def nestArrays(array: Vector[Value], sizes: Vector[Int]): Value = {
      if (sizes.size == 1) {
        assert(array.size == sizes.head)
        Value.VArray(array)
      } else {
        Value.VArray(
            array
              .grouped(array.size / sizes.head)
              .map(group => nestArrays(group, sizes.tail))
              .toVector
        )
      }
    }

    override protected def getScatterOutputs(
        childOutputs: Vector[Map[DxName, JsValue]],
        execName: Option[String]
    ): Map[DxName, (Type, Value)] = {
      val targetOutputs = step.run.outputs.map(param => param.name -> param.cwlType).toMap
      val arraySizes = if (step.scatterMethod.get == ScatterMethod.NestedCrossproduct) {
        val (_, arrays) = getScatterValues.flatten.unzip
        Some(arrays.map(_.size))
      } else {
        None
      }
      step.outputs.map { out =>
        val irType = CwlUtils.toIRType(targetOutputs(out.name))
        val arrayValue =
          (createScatterOutputArray(
               childOutputs,
               CwlDxName.fromSourceName(out.name),
               irType,
               execName
           ),
           arraySizes) match {
            case (Value.VArray(a), Some(sizes)) => nestArrays(a, sizes)
            case (a, None)                      => a
            case (other, _) =>
              throw new Exception(s"invalid array value ${other}")
          }
        val dxName = CwlDxName.fromSourceName(out.name, namespace = Some(step.name))
        dxName -> (irType, arrayValue)
      }.toMap
    }
  }

  override protected def evaluateBlockInputs(
      jobInputs: Map[DxName, (Type, Value)]
  ): CwlBlockContext = {
    val block = Block.getSubBlockAt(CwlBlock.createBlocks(workflow), jobMeta.blockPath)
    val env: Map[DxName, (CwlType, CwlValue)] = block.inputs.map {
      case inp: CwlBlockInput if jobInputs.contains(inp.name) =>
        val (_, irValue) = jobInputs(inp.name)
        inp.name -> CwlUtils.fromIRValue(irValue, inp.cwlType, inp.name.decoded, isInput = true)
      case OptionalBlockInput(name, param) =>
        name -> (param.cwlType, NullValue)
      case param =>
        throw new Exception(s"missing required input ${param.name}")
    }.toMap
    CwlBlockContext(block, env)
  }
}
