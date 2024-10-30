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

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    // Define the dependencyReport task for each configuration using inConfig
    inConfig(Compile)(baseDependencyReportSettings),
    inConfig(Test)(baseDependencyReportSettings),
  ).flatten

  private lazy val baseDependencyReportSettings: Seq[Setting[?]] = Seq(
    dependencyReport := {
      val defaultFormat = "text"

      val args: Seq[String] = spaceDelimited("<arg>").parsed

      val format = args.find(_.startsWith("--format="))
        .map(_.stripPrefix("--format="))
        .getOrElse(defaultFormat)

      val targetDir = target.value

      val graph = dependencyTreeModuleGraph0.value

      val configName = configuration.value.name

      def printAndPersistReport(report: String, fileExtension: String, targetDir: File): File = {
        streams.value.log.info(report)
        persistReport(report, fileExtension, targetDir)
      }

      def generateTextReport(graph: ModuleGraph, format: String, graphWidth: Int): String = {
        val subtype = format.stripPrefix("text:")
        subtype match {
          case "list" => rendering.FlatList.render(_.id.idString)(graph)
          case "stats" => rendering.Statistics.renderModuleStatsList(graph)
          case "info" => rendering.LicenseInfo.render(graph)
          case _ => rendering.AsciiTree.asciiTree(graph, graphWidth)
        }
      }

      def createReportFile(targetDir: File, fileExtension: String): File = {
        new File(targetDir, s"dependencies-$configName.$fileExtension")
      }

      def persistReport(report: String, fileExtension: String, targetDir: File): File = {
        val target = createReportFile(targetDir, fileExtension)
        IO.write(target, report, IO.utf8)
        target
      }

      def generateJsonReport(graph: ModuleGraph): String = {
        TreeView.createJson(graph)
      }

      def generateAndPersistHtmlReport(graph: ModuleGraph, targetDir: File): File = {
        val renderedTree = TreeView.createJson(graph)
        val target = createReportFile(targetDir, "html")
        TreeView.createLink(renderedTree, targetDir)
        target
      }

      def generateAndPersistGraphMLReport(graph: ModuleGraph, targetDir: File): File = {
        val target = createReportFile(targetDir, "xml")
        rendering.GraphML.saveAsGraphML(graph, target.getAbsolutePath)
        target
      }

      val serializedReport = format match {
        case "text" =>
          val report = generateTextReport(graph, format, asciiGraphWidth.value)
          printAndPersistReport(report, "txt", targetDir)
        case "json" =>
          val report = generateJsonReport(graph)
          printAndPersistReport(report, "json", targetDir)
        case "html" => generateAndPersistHtmlReport(graph, targetDir)
        case "graphml" => generateAndPersistGraphMLReport(graph, targetDir)
        case _      => sys.error(s"Unsupported format: $format")
      }

      streams.value.log.info(s"Dependency report written to ${serializedReport.getAbsolutePath}")
    }
  )
}
