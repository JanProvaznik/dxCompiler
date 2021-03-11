package dx.core.languages.cwl

import dx.api.DxPath
import dx.core.io.DxWorkerPaths
import dx.core.ir.{Type, Value}
import dx.core.ir.Type._
import dx.core.ir.Value.{
  VArchive,
  VArray,
  VBoolean,
  VFile,
  VFloat,
  VFolder,
  VHash,
  VInt,
  VListing,
  VNull,
  VString,
  PathValue => IRPathValue,
  DirectoryValue => IRDirectoryValue
}
import dx.cwl._
import dx.util.CollectionUtils.IterableOnceExtensions
import spray.json._

import java.util.UUID
import scala.annotation.tailrec
import scala.collection.immutable.TreeSeqMap

object CwlUtils {

  def toIRSchema(cwlRecord: CwlRecord): TSchema = {
    if (cwlRecord.name.isEmpty) {
      throw new Exception(s"cannot convert schema without name ${cwlRecord}")
    }
    TSchema(
        cwlRecord.name.get,
        cwlRecord.fields.map {
          case (key, value) => key -> toIRType(value.cwlType)
        }
    )
  }

  def toIRType(cwlType: CwlType): Type = {
    cwlType match {
      case CwlOptional(t)  => TOptional(toIRType(t))
      case CwlMulti(types) => TMulti(types.map(toIRType))
      case CwlAny          => TMulti.Any
      case CwlBoolean      => TBoolean
      case CwlInt          => TInt
      case CwlLong         => TInt
      case CwlDouble       => TFloat
      case CwlFloat        => TFloat
      case CwlString       => TString
      case CwlFile         => TFile
      case CwlDirectory    => TDirectory
      case a: CwlArray     => TArray(toIRType(a.itemType))
      case e: CwlEnum      => TEnum(e.symbols)
      case r: CwlRecord if r.name.isDefined =>
        toIRSchema(r)
      case _ =>
        throw new Exception(s"Cannot convert CWL type ${cwlType} to IR")
    }
  }

  def toIRPath(path: PathValue): IRPathValue = {
    path match {
      case f: FileValue =>
        VFile(
            f.location.getOrElse(
                if (f.contents.isDefined) {
                  f.basename.getOrElse(UUID.randomUUID().toString)
                } else {
                  throw new Exception("both 'location' and 'contents' are missing")
                }
            ),
            f.basename,
            f.contents,
            f.checksum,
            f.size,
            f.secondaryFiles.map(toIRPath)
        )
      case d: DirectoryValue =>
        d.location match {
          case Some(uri) if Value.isDxFolderUri(uri) => VFolder(uri, d.basename)
          case Some(uri) if Value.isDxFileUri(uri)   => VArchive(uri, d.basename)
          case None if d.listing.nonEmpty            => VListing(d.basename.get, d.listing.map(toIRPath))
          case None if d.path.nonEmpty               => VFolder(d.path.get)
          case _                                     => throw new Exception()
        }
    }
  }

  def toIRValue(cwlValue: CwlValue): (Type, Value) = {
    cwlValue match {
      case BooleanValue(b)   => (TBoolean, VBoolean(value = b))
      case IntValue(i)       => (TInt, VInt(i))
      case LongValue(l)      => (TInt, VInt(l))
      case FloatValue(f)     => (TFloat, VFloat(f))
      case DoubleValue(d)    => (TFloat, VFloat(d))
      case StringValue(s)    => (TString, VString(s))
      case f: FileValue      => (TFile, toIRPath(f))
      case d: DirectoryValue => (TDirectory, toIRPath(d))
      case ArrayValue(items) =>
        val (itemTypes, itemValues, optional) =
          items.foldLeft(Set.empty[Type], Vector.empty[Value], false) {
            case ((types, values, optional), item) =>
              item match {
                case NullValue => (types, values :+ VNull, true)
                case _ =>
                  val (t, v) = toIRValue(item)
                  if (types.isEmpty || types.contains(t)) {
                    (types + t, values :+ v, optional)
                  } else {
                    throw new Exception(s"array ${items} contains values of multiple types")
                  }
              }
          }
        if (itemTypes.isEmpty) {
          throw new Exception("cannot determine type of array with only null items")
        }
        val itemType = itemTypes.head match {
          case t if optional => ensureOptional(t)
          case t             => t
        }
        (TArray(itemType), VArray(itemValues))
      case ObjectValue(m) =>
        (THash,
         VHash(
             m.map {
                 case (key, NullValue) => key -> VNull
                 case (key, value) =>
                   val (_, v) = toIRValue(value)
                   key -> v
               }
               .to(TreeSeqMap)
         ))
      case _ => throw new Exception(s"Invalid CWL value ${cwlValue})")
    }
  }

  def toIRValue(cwlValue: CwlValue, cwlType: CwlType): (Type, Value) = {
    println(cwlType, cwlValue)
    (cwlType, cwlValue) match {
      case (CwlAny, _)                 => (TMulti.Any, toIRValue(cwlValue)._2)
      case (CwlOptional(_), NullValue) => (toIRType(cwlType), VNull)
      case (CwlOptional(t), _) =>
        val (irType, irValue) = toIRValue(cwlValue, t)
        (TOptional(irType), irValue)
      case (CwlMulti(types), _) =>
        types
          .collectFirstDefined {
            case CwlAny => None
            case t =>
              try {
                Some(toIRValue(cwlValue, t))
              } catch {
                case _: Throwable => None
              }
          }
          .getOrElse(
              if (types.contains(CwlAny)) {
                toIRValue(cwlValue)
              } else {
                throw new Exception(
                    s"cannot translate ${cwlValue} as any of ${types.mkString("\n")}"
                )
              }
          )
      case (CwlBoolean, BooleanValue(b))     => (TBoolean, VBoolean(b))
      case (CwlInt, IntValue(i))             => (TInt, VInt(i))
      case (CwlLong, LongValue(l))           => (TInt, VInt(l))
      case (CwlFloat, FloatValue(f))         => (TFloat, VFloat(f))
      case (CwlDouble, DoubleValue(d))       => (TFloat, VFloat(d))
      case (t: CwlNumber, n: NumericValue)   => toIRValue(n.coerceTo(t), t)
      case (CwlString, StringValue(s))       => (TString, VString(s))
      case (CwlFile, f: FileValue)           => (TFile, toIRPath(f))
      case (CwlFile, StringValue(s))         => (TFile, VFile(s))
      case (CwlDirectory, d: DirectoryValue) => (TDirectory, toIRPath(d))
      case (CwlDirectory, StringValue(s))    => (TDirectory, VFolder(s))
      case (array: CwlArray, ArrayValue(items)) =>
        val (irItems, optional) =
          items.foldLeft(Vector.empty[Value], false) {
            case ((irItems, _), NullValue) => (irItems :+ VNull, true)
            case ((irItems, optional), i) =>
              val (t, v) = toIRValue(i, array.itemType)
              (irItems :+ v, optional || Type.isOptional(t))
          }
        val irItemType = toIRType(array.itemType)
        val irType = TArray(if (optional) {
          Type.ensureOptional(irItemType)
        } else {
          irItemType
        })
        (irType, VArray(irItems))
      case (record: CwlRecord, ObjectValue(fields)) =>
        val (types, values) =
          fields.foldLeft(TreeSeqMap.empty[String, Type], TreeSeqMap.empty[String, Value]) {
            case ((types, values), (name, value)) if record.fields.contains(name) =>
              val (irType, irValue) = toIRValue(value, record.fields(name).cwlType)
              (types + (name -> irType), values + (name -> irValue))
            case (name, _) =>
              throw new Exception(s"invalid field ${name}")
          }
        val irType = if (record.name.isDefined) {
          TSchema(record.name.get, types)
        } else {
          THash
        }
        (irType, VHash(values))
      case (enum: CwlEnum, StringValue(s)) if enum.symbols.contains(s) =>
        (TEnum(enum.symbols), VString(s))
      case _ => throw new Exception(s"Invalid CWL value ${cwlValue})")
    }
  }

  def toIR(cwl: Map[String, (CwlType, CwlValue)]): Map[String, (Type, Value)] = {
    cwl.map {
      case (name, (cwlType, cwlValue)) => name -> toIRValue(cwlValue, cwlType)
    }
  }

  def fromIRType(irType: Type,
                 typeAliases: Map[String, CwlSchema] = Map.empty,
                 isInput: Boolean): CwlType = {
    def inner(innerType: Type): CwlType = {
      innerType match {
        case TOptional(t)  => CwlOptional(inner(t))
        case TMulti.Any    => CwlAny
        case TMulti(types) => CwlMulti(types.map(inner))
        case TBoolean      => CwlBoolean
        case TInt          => CwlLong
        case TFloat        => CwlDouble
        case TString       => CwlString
        case TFile         => CwlFile
        case TDirectory    => CwlDirectory
        case TArray(t, _)  => CwlArray(inner(t))
        case TSchema(name, _) if typeAliases.contains(name) =>
          typeAliases(name)
        case TSchema(name, fields) if isInput =>
          CwlInputRecord(fields.map {
            case (name, t) => name -> CwlInputRecordField(name, inner(t))
          }, Some(name))
        case TSchema(name, members) =>
          CwlOutputRecord(members.map {
            case (name, t) => name -> CwlOutputRecordField(name, inner(t))
          }, Some(name))
        case TEnum(symbols) => CwlEnum(symbols)
        case _ =>
          throw new Exception(s"Cannot convert IR type ${irType} to CWL")
      }
    }
    inner(irType)
  }

  def fromIRPath(path: IRPathValue): PathValue = {
    path match {
      case f: VFile =>
        FileValue(Some(f.uri),
                  basename = f.basename,
                  checksum = f.checksum,
                  size = f.size,
                  contents = f.contents,
                  secondaryFiles = f.secondaryFiles.map(fromIRPath))
      case VFolder(uri, basename) =>
        DirectoryValue(Some(uri), basename = basename)
      case VArchive(uri, basename) =>
        DirectoryValue(Some(uri), basename = basename)
      case VListing(basename, listing) =>
        DirectoryValue(basename = Some(basename), listing = listing.map(fromIRPath))
    }
  }

  def fromIRValue(value: Value, name: Option[String], isInput: Boolean): (CwlType, CwlValue) = {
    def inner(innerValue: Value, innerName: Option[String]): (CwlType, CwlValue) = {
      innerValue match {
        case VNull               => (CwlNull, NullValue)
        case VBoolean(b)         => (CwlBoolean, BooleanValue(b))
        case VInt(i)             => (CwlLong, LongValue(i))
        case VFloat(f)           => (CwlDouble, DoubleValue(f))
        case VString(s)          => (CwlString, StringValue(s))
        case f: VFile            => (CwlFile, fromIRPath(f))
        case d: IRDirectoryValue => (CwlDirectory, fromIRPath(d))
        case VArray(array) =>
          val (types, values) = array.zipWithIndex.map {
            case (v, i) => inner(v, innerName.map(n => s"${n}[${i}]"))
          }.unzip
          (CwlArray(CwlType.flatten(types.distinct)), ArrayValue(values))
        case VHash(fields) =>
          val (types, values) = fields.map {
            case (key, value) =>
              val (cwlType, cwlValue) = inner(value, innerName.map(n => s"${n}[${key}]"))
              (key -> cwlType, key -> cwlValue)
          }.unzip
          // create an anonymous record schema
          val schemaType = if (isInput) {
            CwlInputRecord(
                types
                  .map {
                    case (name, t) => name -> CwlInputRecordField(name, t)
                  }
                  .to(TreeSeqMap)
            )
          } else {
            CwlOutputRecord(
                types
                  .map {
                    case (name, t) => name -> CwlOutputRecordField(name, t)
                  }
                  .to(TreeSeqMap)
            )
          }
          (schemaType, ObjectValue(values.to(TreeSeqMap)))
        case _ =>
          throw new Exception(
              s"cannot convert ${name.getOrElse("IR")} value ${value} to WDL value"
          )
      }
    }
    inner(value, name)
  }

  def fromIRValues(values: Map[String, Value],
                   isInput: Boolean): Map[String, (CwlType, CwlValue)] = {
    values.map {
      case (name, value) => name -> fromIRValue(value, Some(name), isInput)
    }
  }

  def fromIRValue(value: Value,
                  cwlType: CwlType,
                  name: String,
                  isInput: Boolean): (CwlType, CwlValue) = {
    @tailrec
    def inner(innerValue: Value, innerType: CwlType, innerName: String): CwlValue = {
      (innerType, innerValue) match {
        case (CwlAny, _)                         => fromIRValue(innerValue, Some(name), isInput)._2
        case (CwlOptional(_) | CwlNull, VNull)   => NullValue
        case (CwlOptional(t), _)                 => inner(innerValue, t, innerName)
        case (CwlBoolean, VBoolean(b))           => BooleanValue(b)
        case (CwlInt, VInt(i)) if i.isValidInt   => IntValue(i)
        case (CwlLong, VInt(l))                  => LongValue(l)
        case (CwlFloat, VFloat(f))               => FloatValue(f.toFloat)
        case (CwlFloat, VInt(i))                 => FloatValue(i.toFloat)
        case (CwlDouble, VFloat(f))              => FloatValue(f)
        case (CwlDouble, VInt(i))                => FloatValue(i.toDouble)
        case (CwlString, VString(s))             => StringValue(s)
        case (CwlFile, VString(path))            => FileValue(path)
        case (CwlFile, f: VFile)                 => fromIRPath(f)
        case (CwlDirectory, VString(path))       => DirectoryValue(path)
        case (CwlDirectory, d: IRDirectoryValue) => fromIRPath(d)
        case (array: CwlArray, VArray(items)) =>
          ArrayValue(items.zipWithIndex.map {
            case (item, i) => innerMulti(item, array.itemType, s"${innerName}[${i}]")._2
          })
        case (record: CwlRecord, VHash(fields)) =>
          // ensure 1) members keys are a subset of memberTypes keys, 2) members
          // values are convertable to the corresponding types, and 3) any keys
          // in memberTypes that do not appear in members are optional
          val keys1 = fields.keySet
          val keys2 = record.fields.keySet
          val extra = keys2.diff(keys1)
          if (extra.nonEmpty) {
            throw new Exception(
                s"struct ${record.name} value has members that do not appear in the struct definition: ${extra}"
            )
          }
          val missingNonOptional =
            keys1.diff(keys2).map(key => key -> record.fields(key)).filterNot {
              case (_, field) if CwlOptional.isOptional(field.cwlType) => false
              case _                                                   => true
            }
          if (missingNonOptional.nonEmpty) {
            throw new Exception(
                s"struct ${record.name} value is missing non-optional members ${missingNonOptional}"
            )
          }
          ObjectValue(
              fields.map {
                case (key, value) =>
                  key -> innerMulti(value, record.fields(key).cwlType, s"${innerName}[${key}]")._2
              }
          )
        case (enum: CwlEnum, VString(s)) if enum.symbols.contains(s) => StringValue(s)
        case _ =>
          throw new Exception(s"cannot translate ${innerValue} to CwlValue of type ${innerType}")
      }
    }
    def innerMulti(innerValue: Value,
                   innerType: CwlType,
                   innerName: String): (CwlType, CwlValue) = {
      innerType match {
        case CwlMulti(types) =>
          types
            .collectFirstDefined {
              case CwlAny => None
              case t =>
                try {
                  Some(t, inner(value, t, name))
                } catch {
                  case _: Throwable => None
                }
            }
            .getOrElse(
                if (types.contains(CwlAny)) {
                  fromIRValue(innerValue, Some(name), isInput)
                } else {
                  throw new Exception(
                      s"cannot convert ${name} ${value} to CWL value of any type ${types.mkString(",")}"
                  )
                }
            )
        case _ => (innerType, inner(innerValue, innerType, innerName))
      }
    }
    innerMulti(value, cwlType, name)
  }

  def fromIR(values: Map[String, (Type, Value)],
             typeAliases: Map[String, CwlSchema] = Map.empty,
             isInput: Boolean): Map[String, (CwlType, CwlValue)] = {
    values.map {
      case (name, (t, v)) =>
        val cwlType = fromIRType(t, typeAliases, isInput)
        name -> fromIRValue(v, cwlType, name, isInput)
    }
  }

  def prettyFormatType(cwlType: CwlType): String = {
    cwlType match {
      case CwlOptional(t)  => s"${prettyFormatType(t)}?"
      case CwlMulti(types) => s"(${types.map(prettyFormatType).mkString("|")})"
      case CwlAny          => "any"
      case CwlBoolean      => "boolean"
      case CwlInt          => "int"
      case CwlLong         => "long"
      case CwlFloat        => "float"
      case CwlDouble       => "double"
      case CwlString       => "string"
      case CwlFile         => "File"
      case CwlDirectory    => "Directory"
      case a: CwlArray if a.name.isDefined =>
        a.name.get
      case a: CwlArray =>
        s"array<${prettyFormatType(a.itemType)}>"
      case r: CwlRecord if r.name.isDefined =>
        r.name.get
      case r: CwlRecord =>
        s"record<${r.fields.values.map(f => s"${prettyFormatType(f.cwlType)} ${f.name}").mkString(", ")}>"
      case e: CwlEnum if e.name.isDefined =>
        e.name.get
      case e: CwlEnum =>
        s"enum<${e.symbols.mkString(",")}>"
    }
  }

  def prettyFormatValue(value: CwlValue): String = {
    value match {
      case NullValue           => "null"
      case BooleanValue(true)  => "true"
      case BooleanValue(false) => "false"
      case IntValue(i)         => i.toString
      case LongValue(l)        => l.toString
      case FloatValue(f)       => f.toString
      case DoubleValue(d)      => d.toString
      case StringValue(s)      => s
      case f: FileValue        => f.toString
      case d: DirectoryValue   => d.toString
      case ArrayValue(items)   => s"[${items.map(prettyFormatValue)}]"
      case ObjectValue(fields) =>
        val fieldStrs = fields.map {
          case (name, value) => s"${name}: ${prettyFormatValue(value)}"
        }
        s"{${fieldStrs.mkString(",")}}"
      case _ => throw new Exception(s"unrecognized CWL value ${value})")
    }
  }

  def prettyFormatEnv(env: Map[String, (CwlType, CwlValue)], indent: String = "  "): String = {
    env
      .map {
        case (name, (t, v)) =>
          s"${indent}${name}: ${prettyFormatType(t)} ${prettyFormatValue(v)}"
      }
      .mkString("\n")
  }

  /**
    * Does a WorkflowStep represent a simple call - i.e. with no
    * scatter or conditional?
    * @param step the WorkflowStep
    * @return
    */
  def isSimpleCall(step: WorkflowStep): Boolean = {
    step.scatter.isEmpty && step.when.isEmpty
  }

  def isDxFile(file: FileValue): Boolean = {
    file.location.exists(_.startsWith(DxPath.DxUriPrefix))
  }

  def toJson(values: Map[String, (CwlType, CwlValue)]): JsObject = {
    JsObject(values.map {
      case (name, (_, v)) => name -> v.toJson
    })
  }

  def createRuntime(workerPaths: DxWorkerPaths): Runtime = {
    Runtime.create(
        outdir = workerPaths.getOutputFilesDir(ensureExists = true),
        tmpdir = workerPaths.getTempDir(ensureExists = true)
    )
  }

  def createEvaluatorContext(runtime: Runtime,
                             env: Map[String, (CwlType, CwlValue)] = Map.empty,
                             self: CwlValue = NullValue): EvaluatorContext = {
    val values = env
      .map {
        case (key, (_, value)) => key -> value
      }
      .to(TreeSeqMap)
    EvaluatorContext(self, ObjectValue(values), runtime)
  }
}
