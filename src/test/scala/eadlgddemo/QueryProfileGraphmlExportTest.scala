package eadlgddemo

import java.nio.file.{Files, Paths}
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
final class QueryProfileGraphmlExportTest extends AnyFunSuite {
  test("export an existing Snowflake query profile to yEd GraphML with full node names") {
    if (!DemoConfig.liveEnabled()) cancel("Set RUN_SNOWFLAKE_IT=true to retrieve an existing Snowflake profile")
    val input = sys.props.get("queryId").orElse(sys.env.get("QUERY_ID"))
      .getOrElse(cancel("Supply QUERY_ID or Gradle -PqueryId=<query-id>"))
    val id = QueryProfileGraphml.normalizeQueryId(input)
    val output = Paths.get(sys.props.get("queryProfileOutput").orElse(sys.env.get("QUERY_PROFILE_OUTPUT"))
      .getOrElse(s"build/query-profiles/$id.graphml")).toAbsolutePath.normalize()
    require(output.getFileName.toString.endsWith(".graphml"), "QUERY_PROFILE_OUTPUT must end in .graphml")

    val session = SessionFactory.open(ConnectionConfig.load(), DemoConfig(statementTimeoutSeconds = 60))
    val document = Lifecycle.protecting {
      session.setQueryTag(s"""{"job":"query_profile_graphml_export","sourceQueryId":"$id"}""")
      QueryProfileGraphml.fetch(session, id)
    }(session.close())
    // Keep the source statistics for auditing and offline re-export after Snowflake's retention expires.
    val rawOutput = output.resolveSibling(output.getFileName.toString.stripSuffix(".graphml") + ".operators.json")
    Json.write(rawOutput, document)
    val profile = QueryProfileGraphml.write(id, document, output)

    val factory = DocumentBuilderFactory.newInstance()
    factory.setNamespaceAware(true)
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val graph = factory.newDocumentBuilder().parse(output.toFile)
    assert(Files.size(output) > 0)
    assert(graph.getElementsByTagNameNS(QueryProfileGraphml.YworksNamespace, "ShapeNode").getLength == profile.operators.size)
    assert(graph.getElementsByTagNameNS(QueryProfileGraphml.GraphmlNamespace, "edge").getLength ==
      profile.operators.map(_.parents.size).sum)
    println(s"Query: $id\nGraphML: $output\nSource statistics: $rawOutput\n" +
      s"${profile.operators.size} operators across ${profile.operators.map(_.step).distinct.size} steps. Open the .graphml file in yEd.")
  }
}
