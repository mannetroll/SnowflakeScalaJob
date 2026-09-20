package eadlgddemo

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import org.w3c.dom.{Document, Element}
import org.xml.sax.InputSource

@RunWith(classOf[JUnitRunner])
final class QueryProfileGraphmlTest extends AnyFunSuite {
  private val id = "11111111-2222-3333-4444-555555555555"
  private def fixture(): JsonNode = {
    val stream = getClass.getResourceAsStream("/query-profile-multistep.json")
    try Json.mapper.readTree(stream) finally stream.close()
  }
  private def xml(value: String): Document = {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setNamespaceAware(true)
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.newDocumentBuilder().parse(new InputSource(new StringReader(value)))
  }
  private def elements(d: Document, ns: String, tag: String): Vector[Element] = {
    val nodes = d.getElementsByTagNameNS(ns, tag)
    (0 until nodes.getLength).map(i => nodes.item(i).asInstanceOf[Element]).toVector
  }

  test("accepts bare query IDs and the pasted Snowsight heading, rejects malformed/injected input") {
    assert(QueryProfileGraphml.normalizeQueryId(" Query - 01C73375-0005-833E-0001-91AE00232802 ") ==
      "01c73375-0005-833e-0001-91ae00232802")
    assert(QueryProfileGraphml.normalizeQueryId(id) == id)
    Vector("", "not-a-query", id + "'; SELECT 1;--", id + ".graphml").foreach { bad =>
      intercept[IllegalArgumentException](QueryProfileGraphml.normalizeQueryId(bad))
    }
  }

  test("all execution steps survive, operator IDs stay distinct, shared branches point to both parents") {
    val profile = QueryProfileGraphml.parse(id, fixture())
    val graph = xml(QueryProfileGraphml.render(profile))
    val ns = QueryProfileGraphml.GraphmlNamespace
    val yns = QueryProfileGraphml.YworksNamespace
    assert(elements(graph, yns, "GroupNode").size == 2)
    assert(elements(graph, yns, "ShapeNode").size == 7)
    val nodeIds = elements(graph, ns, "node").map(_.getAttribute("id"))
    assert(nodeIds.distinct.size == nodeIds.size)
    assert(nodeIds.contains("s1_n0") && nodeIds.contains("s2_n0"))
    val edges = elements(graph, ns, "edge").map(e => (e.getAttribute("source"), e.getAttribute("target"))).toSet
    assert(edges == Set("s2_n1" -> "s2_n0", "s2_n2" -> "s2_n1", "s2_n3" -> "s2_n2",
      "s2_n4" -> "s2_n2", "s2_n5" -> "s2_n3", "s2_n5" -> "s2_n4"))
    assert(edges.forall { case (source, target) => nodeIds.contains(source) && nodeIds.contains(target) })
    assert(elements(graph, yns, "Arrows").forall(_.getAttribute("target") == "standard"))
  }

  test("full names and SQL attributes survive XML escaping and node boxes fit without overlap") {
    val document = fixture()
    val profile = QueryProfileGraphml.parse(id, document)
    val graph = xml(QueryProfileGraphml.render(profile))
    val labels = elements(graph, QueryProfileGraphml.YworksNamespace, "NodeLabel").map(_.getTextContent)
    assert(labels.exists(_.contains("DB.SCHEMA.OUTPUT_WITH_A_VERY_LONG_FULL_NAME_THAT_MUST_REMAIN_VISIBLE_IN_YED_0123456789")))
    assert(labels.exists(_.contains("DB.\"Schéma & <risk>\".\"Full Account Name\"")))
    assert(labels.exists(_.contains("Execution: 60% of step")))
    val nodes = elements(graph, QueryProfileGraphml.GraphmlNamespace, "node").filter(_.getAttribute("id").startsWith("s2_n"))
    val boxes = nodes.map { node =>
      val geometry = node.getElementsByTagNameNS(QueryProfileGraphml.YworksNamespace, "Geometry").item(0).asInstanceOf[Element]
      val label = node.getElementsByTagNameNS(QueryProfileGraphml.YworksNamespace, "NodeLabel").item(0).getTextContent
      def n(key: String): Double = geometry.getAttribute(key).toDouble
      assert(n("width") >= label.split("\n").map(_.length).max * 8.5)
      (n("x"), n("y"), n("width"), n("height"))
    }
    boxes.combinations(2).foreach { pair =>
      val (x, y, w, h) = pair.head
      val (a, b, c, d) = pair.last
      assert(x + w <= a || a + c <= x || y + h <= b || b + d <= y)
    }
    val attrs = elements(graph, QueryProfileGraphml.GraphmlNamespace, "data")
      .filter(_.getAttribute("key") == "operator_attributes").map(e => Json.mapper.readTree(e.getTextContent))
    assert(attrs.exists(_ == profile.operators.find(_.kind == "Join").get.attributes))
  }

  test("layout and output are deterministic even when operator rows arrive in reverse order") {
    val normal = fixture()
    val reversed = fixture().asInstanceOf[ObjectNode]
    val rows = reversed.get("operators").asInstanceOf[ArrayNode]
    val copy = Json.mapper.createArrayNode()
    (0 until rows.size()).reverse.foreach(i => copy.add(rows.get(i)))
    reversed.set[JsonNode]("operators", copy)
    assert(QueryProfileGraphml.render(QueryProfileGraphml.parse(id, normal)) ==
      QueryProfileGraphml.render(QueryProfileGraphml.parse(id, reversed)))
  }

  test("rejects empty evidence, mismatched query IDs, duplicate operators, missing parents and cycles") {
    intercept[IllegalArgumentException](QueryProfileGraphml.parse(id, Json.mapper.createArrayNode()))
    intercept[IllegalArgumentException](QueryProfileGraphml.parse("aaaaaaaa-2222-3333-4444-555555555555", fixture()))
    val mutations: Vector[JsonNode => Unit] = Vector(
      d => { val a = d.get("operators").asInstanceOf[ArrayNode]; a.add(a.get(0)); () },
      d => { d.get("operators").get(2).asInstanceOf[ObjectNode].put("PARENT_OPERATORS", "[999]"); () },
      d => { d.get("operators").get(1).asInstanceOf[ObjectNode].put("PARENT_OPERATORS", "[1]"); () },
      d => { d.get("operators").get(2).asInstanceOf[ObjectNode].put("QUERY_ID", "aaaaaaaa-2222-3333-4444-555555555555"); () }
    )
    mutations.foreach { mutate =>
      val d = fixture(); mutate(d)
      intercept[IllegalArgumentException](QueryProfileGraphml.parse(id, d))
    }
  }

  test("replaces XML-illegal display characters while retaining the raw source JSON") {
    val d = fixture()
    val row = d.get("operators").get(2).asInstanceOf[ObjectNode]
    row.get("OPERATOR_ATTRIBUTES").asInstanceOf[ObjectNode].put("table_name", "DB.TABLE_\u0001_😀")
    val graph = xml(QueryProfileGraphml.render(QueryProfileGraphml.parse(id, d)))
    val labels = elements(graph, QueryProfileGraphml.YworksNamespace, "NodeLabel").map(_.getTextContent)
    assert(labels.exists(_.contains("DB.TABLE_\ufffd_😀")))
    val descriptions = elements(graph, QueryProfileGraphml.GraphmlNamespace, "data")
      .filter(_.getAttribute("key") == "description").map(_.getTextContent)
    assert(descriptions.exists(_.contains("\\u0001")))
  }
}
