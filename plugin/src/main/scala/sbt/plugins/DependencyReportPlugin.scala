package sbt.plugins

import sbt.*
import sbt.Def.spaceDelimited
import sbt.Keys.*
import sbt.internal.graph.*
import sbt.internal.graph.rendering.TreeView
import sbt.io.IO
import sbt.plugins.DependencyTreeKeys.*

trait DependencyReportKeys {
  val dependencyReport = inputKey[Unit]("Generates a report of the project dependencies")
}

object DependencyReportPlugin extends AutoPlugin {
  object autoImport extends DependencyReportKeys
  import autoImport.*

  override def trigger = allRequirements
  override def requires = MiniDependencyTreePlugin

  override lazy val projectSettings = Seq(
    dependencyReport := {
      val defaultFormat = "text"

      val args: Seq[String] = spaceDelimited("<arg>").parsed

      val format = args.find(_.startsWith("--format="))
        .map(_.stripPrefix("--format="))
        .getOrElse(defaultFormat)

      val toFile = args.find(_.startsWith("--toFile="))
        .map(path => new File(path.stripPrefix("--toFile=")))
        .getOrElse(target.value)

      val graph = args.find(_.startsWith("--config="))
        .map(_.stripPrefix("--config="))
        .getOrElse("Compile") match {
        case "Compile" => (Compile / dependencyTreeModuleGraph0).value
        case "Test"    => (Test / dependencyTreeModuleGraph0).value
        case other     => sys.error(s"Unsupported scope: $other")
      }

      def printAndPersistReport(report: String, fileExtension: String, toFile: File): File = {
        streams.value.log.info(report)
        persistReport(report, fileExtension, toFile)
      }

      val serializedReport = format match {
        case "text" =>
          val report = generateTextReport(graph, format, asciiGraphWidth.value)
          printAndPersistReport(report, "txt", toFile)
        case "json" =>
          val report = generateJsonReport(graph)
          printAndPersistReport(report, "json", toFile)
        case "html" => generateAndPersistHtmlReport(graph, toFile)
        case "graphml" => generateAndPersistGraphMLReport(graph, toFile)
        case _      => sys.error(s"Unsupported format: $format")
      }

      streams.value.log.info(s"Dependency report written to ${serializedReport.getAbsolutePath}")
    }
  )

  private def generateTextReport(graph: ModuleGraph, format: String, graphWidth: Int): String = {
    val subtype = format.stripPrefix("text:")
    subtype match {
      case "list" => rendering.FlatList.render(_.id.idString)(graph)
      case "stats" => rendering.Statistics.renderModuleStatsList(graph)
      case "info" => rendering.LicenseInfo.render(graph)
      case _ => rendering.AsciiTree.asciiTree(graph, graphWidth)
    }
  }

  private def createReportFileIfNotSupplied(toFile: File, fileExtension: String): File = {
    if (!toFile.isDirectory) toFile else new File(toFile, s"dependencies.$fileExtension")
  }

  private def persistReport(report: String, fileExtension: String, toFile: File): File = {
    val target = createReportFileIfNotSupplied(toFile, fileExtension)
    IO.write(target, report, IO.utf8)
    target
  }

  private def generateJsonReport(graph: ModuleGraph): String = {
    TreeView.createJson(graph)
  }

  private def generateAndPersistHtmlReport(graph: ModuleGraph, toFile: File): File = {
    val renderedTree = TreeView.createJson(graph)
    val target = createReportFileIfNotSupplied(toFile, "html")
    TreeView.createLink(renderedTree, target)
    target
  }

  private def generateAndPersistGraphMLReport(graph: ModuleGraph, toFile: File): File = {
    val target = createReportFileIfNotSupplied(toFile, "xml")
    rendering.GraphML.saveAsGraphML(graph, target.getAbsolutePath)
    target
  }
}
