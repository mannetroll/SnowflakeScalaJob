package eadlgddemo

import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
final class TableLineageTest extends AnyFunSuite {
  test("six named tables form five expected source-to-output lineage edges") {
    val dir = Files.createTempDirectory("table-lineage-")
    val tables = Datasets.all.map(d => d.name -> s"DB.SCHEMA.${d.name}").toMap
    val output = "DB.SCHEMA.OUTPUT"
    val scans = tables.values.toVector.sorted.zipWithIndex.map { case (table, index) =>
      val attributes = Json.mapper.createObjectNode().put("table_name", table)
      val empty = Json.mapper.createObjectNode()
      ProfileOperator(2, index, "TableScan", Vector.empty, attributes, empty, empty, empty)
    }
    val profile = OperatorProfile("11111111-2222-3333-4444-555555555555", scans)
    TableLineage.write(LoadedInputs(tables, Map.empty, ""), output, "E1", dir, Some(profile))

    val expected = Json.mapper.readTree(dir.resolve("E1-expected-table-lineage.json").toFile)
    assert(expected.path("nodes").elements().asScala.map(_.asText()).toSet == tables.values.toSet + output)
    assert(expected.path("edges").elements().asScala.map(e =>
      e.path("source").asText() -> e.path("target").asText()).toSet ==
      tables.values.map(_ -> output).toSet)

    val xml = DocumentBuilderFactory.newInstance()
    xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val graph = xml.newDocumentBuilder().parse(dir.resolve("E1-expected-table-lineage.graphml").toFile)
    assert(graph.getElementsByTagName("node").getLength == 6)
    assert(graph.getElementsByTagName("edge").getLength == 5)
    val observed = Json.mapper.readTree(dir.resolve("E1-profile-table-lineage.json").toFile)
    assert(observed.path("queryId").asText() == profile.queryId)
    assert(observed.path("edges").elements().asScala.map(e =>
      e.path("source").asText() -> e.path("target").asText()).toSet ==
      tables.values.map(_ -> output).toSet)
    val observedGraph = xml.newDocumentBuilder().parse(dir.resolve("E1-profile-table-lineage.graphml").toFile)
    assert(observedGraph.getElementsByTagName("node").getLength == 6)
    assert(observedGraph.getElementsByTagName("edge").getLength == 5)
    val sql = new String(Files.readAllBytes(dir.resolve("sql/E1-inspect-lineage.sql")), java.nio.charset.StandardCharsets.UTF_8)
    assert(sql.contains("SNOWFLAKE.CORE.GET_LINEAGE"))
    assert(sql.contains(output))
  }
}
