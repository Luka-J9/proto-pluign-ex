package com.dimensions.compiler

import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.Descriptors._
import protocbridge.Artifact
import protocgen.{CodeGenApp, CodeGenResponse, CodeGenRequest}
import scalapb.compiler.{DescriptorImplicits, FunctionalPrinter, ProtobufGenerator}
import scalapb.options.compiler.Scalapb
import scala.collection.JavaConverters._
import dimensions.internalmodel.internalmodel.InternalmodelProto
import dimensions.internalmodel.{Internalmodel => JIE}
import dimensions.internalmodel.internalmodel.InternalMappingOptions
import com.google.protobuf.Message
import scala.collection.mutable.Buffer

object CodeGenerator extends CodeGenApp {
  override def registerExtensions(registry: ExtensionRegistry): Unit = {
    Scalapb.registerAllExtensions(registry)
    JIE.registerAllExtensions(registry)
  }

  // When your code generator will be invoked from SBT via sbt-protoc, this will add the following
  // artifact to your users build whenver the generator is used in `PB.targets`:
  override def suggestedDependencies: Seq[Artifact] =
    Seq(
      Artifact(
        BuildInfo.organization,
        "internal-model-core",
        BuildInfo.version,
        crossVersion = true
      )
    )

  // This is called by CodeGenApp after the request is parsed.
  def process(request: CodeGenRequest): CodeGenResponse =
    ProtobufGenerator.parseParameters(request.parameter) match {
      case Right(params) =>
        // Implicits gives you extension methods that provide ScalaPB names and types
        // for protobuf entities.
        val implicits =
          DescriptorImplicits.fromCodeGenRequest(params, request)

        // Process each top-level message in each file.
        // This can be customized if you want to traverse the input in a different way.
        CodeGenResponse.succeed(
          for {
            file <- request.filesToGenerate
            desc = InternalMappingOptions.fromJavaProto(
              file.getOptions().getExtension(JIE.internalModel)
            )
            message <- file.getMessageTypes().asScala
          } yield new MessagePrinter(message, implicits, desc).result
        )
      case Left(error) =>
        CodeGenResponse.fail(error)
    }
}

class MessagePrinter(
    message: Descriptor,
    implicits: DescriptorImplicits,
    name: InternalMappingOptions
) {
  import implicits._

  case class ClassName(str: String)
  val convertedMapping = {
    (for {
      options <- name.myOption
      elements = options.mapping.map(cm =>
        Map(cm.name -> cm.fieldMap.map(e => (e.name, e._class)).toMap)
      )
    } yield (elements.reduce(_ ++ _))).getOrElse(Map.empty)
  }

  private val MessageObject =
    message.scalaType.sibling("Internal" + message.scalaType.name)

  def scalaFileName =
    MessageObject.fullName.replace('.', '/') + ".scala"

  def result: CodeGeneratorResponse.File = {
    val b = CodeGeneratorResponse.File.newBuilder()
    b.setName(scalaFileName)
    b.setContent(content)
    b.build()
  }

  def printObject(fp: FunctionalPrinter): FunctionalPrinter = {
    convertedMapping.get(message.getFullName()) match {
      case None => fp
      case Some(value) =>
        val header = fp
          .add("import scala.util.Try")
          .newline
          .add(s"case class ${MessageObject.name}(")
          .indented(
            _.print(message.getFields().asScala) { (fp, fd) => printField(fp, fd, value) }
          )
          .add(")")
          .add("")
          .add(s"object ${MessageObject.name} {")
          .indented(
            _.add(
              s"def fromOriginal(v: ${message.scalaType.fullName}): Try[${MessageObject.name}] = {"
            )
          )
        validateFieldsPringer(header, message.getFields().asScala, value)
          .add("}")
          .add("}")
    }
  }

  def validateFieldsPringer(
      fp: FunctionalPrinter,
      fds: Buffer[FieldDescriptor],
      matcher: Map[String, String]
  ): FunctionalPrinter = {
    val header = fp.add("for {")
    fds
      .map(fd => (fd, matcher.get(fd.getName())))
      .foldRight(header) {
        case ((fd, Some(value)), printer) =>
          printer.add(
            s"${fd.scalaName} <- implicitly[com.dimensions.InternalValidator[${fd.scalaTypeName}, ${value}]].validate(v.${fd.scalaName})"
          )
        case ((fd, None), printer) => printer
      }
      .add(s"} yield(${MessageObject.name}(${fds
          .map { fd =>
            if (!matcher.contains(fd.getName())) {
              s"v.${fd.scalaName}"
            } else {
              fd.scalaName
            }
          }
          .mkString(", ")}))")

  }
  // fp
  //   .add(s"object ${MessageObject.name} {")
  //   .indented(
  //     _.print(message.getFields().asScala){ (fp, fd) => printField(fp, fd) }
  //     .add(s"//${message.getFullName}")
  //     .print(message.getNestedTypes().asScala) {
  //       (fp, m) => new MessagePrinter(m, implicits, name).printObject(fp)
  //     }
  //   )
  //   .add("}")

  def printField(
      fp: FunctionalPrinter,
      fd: FieldDescriptor,
      matcher: Map[String, String]
  ): FunctionalPrinter = {
    matcher.get(fd.getName()) match {
      case None        => fp.add(s"${fd.getName()}: ${fd.scalaTypeName},")
      case Some(value) => fp.add(s"${fd.getName()}: $value,")
    }
  }

  def content: String = {
    val fp = new FunctionalPrinter()
      .add(
        s"package ${message.getFile.scalaPackage.fullName}",
        ""
      )
      .call(printObject)
    fp.result()
  }
}
