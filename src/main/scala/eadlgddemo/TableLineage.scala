package eadlgddemo

import java.nio.file.Path

/** Expected table dependencies are separate from Snowflake's observed lineage. */
object TableLineage {
  def write(inputs: LoadedInputs, output: String, execution: String, dir: Path,
      profile: Option[OperatorProfile]): Unit = {
    val sources = inputs.tables.values.toVector.sorted
    Json.write(dir.resolve(s"$execution-expected-table-lineage.json"), Map(
      "evidence" -> "expected dependencies, not observed GET_LINEAGE results",
      "nodes" -> (sources :+ output),
      "edges" -> sources.map(source => Map("source" -> source, "target" -> output))))
    Json.writeText(dir.resolve(s"$execution-expected-table-lineage.graphml"),
      graphml(sources, output, "Expected dependencies; inspect Snowflake GET_LINEAGE for observed edges"))
    profile.foreach { p =>
      val scanned = p.operators.filter(_.kind == "TableScan")
        .map(_.attributes.path("table_name").asText().replace("\"", "").toUpperCase(java.util.Locale.ROOT))
        .distinct.sorted
      require(scanned == sources, s"Final CTAS scans $scanned instead of all five named inputs $sources")
      Json.write(dir.resolve(s"$execution-profile-table-lineage.json"), Map(
        "evidence" -> "final CTAS TableScan operators; source-to-output edges inferred from the query plan, not GET_LINEAGE",
        "queryId" -> p.queryId,
        "nodes" -> (scanned :+ output),
        "edges" -> scanned.map(source => Map("source" -> source, "target" -> output))))
      Json.writeText(dir.resolve(s"$execution-profile-table-lineage.graphml"),
        graphml(scanned, output, s"Final CTAS ${p.queryId}: observed TableScan sources; edges to CTAS target inferred from the query plan"))
    }
    Json.writeText(dir.resolve(s"sql/$execution-inspect-lineage.sql"),
      s"""-- Run after KEEP_OBJECTS=true. Requires Enterprise Edition and lineage privileges.
         |-- Snowflake lineage metadata may not be available immediately after CTAS.
         |-- Returned rows are observed edges; the JSON manifests contain plan-derived expectations only.
         |SELECT * FROM TABLE(SNOWFLAKE.CORE.GET_LINEAGE(
         |  ${Sql.literal(output)}, 'TABLE', 'UPSTREAM', 5));
         |
         |SELECT * FROM TABLE(SNOWFLAKE.CORE.GET_LINEAGE(
         |  ${Sql.literal(output + ".TOTAL_EAD")}, 'COLUMN', 'UPSTREAM', 5));
         |
         |SELECT * FROM TABLE(SNOWFLAKE.CORE.GET_LINEAGE(
         |  ${Sql.literal(output + ".LGD_AMOUNT")}, 'COLUMN', 'UPSTREAM', 5));
         |""".stripMargin)
  }

  private def graphml(sources: Vector[String], output: String, evidence: String): String = {
    val graph = new StringBuilder
    graph.append("""<?xml version="1.0" encoding="UTF-8"?>
<graphml xmlns="http://graphml.graphdrawing.org/xmlns" xmlns:y="http://www.yworks.com/xml/graphml">
  <key id="graphics" for="node" yfiles.type="nodegraphics"/>
  <key id="edge_graphics" for="edge" yfiles.type="edgegraphics"/>
  <key id="name" for="node" attr.name="table_name" attr.type="string"/>
  <key id="evidence" for="graph" attr.name="evidence" attr.type="string"/>
  <graph id="table_lineage" edgedefault="directed">
    <data key="evidence">""")
    graph.append(evidence).append("</data>\n")
    (sources :+ output).zipWithIndex.foreach { case (name, index) =>
      val x = if (index == 5) 650 else 30
      val y = if (index == 5) 290 else 30 + index * 130
      graph.append(s"""    <node id="table_$index">
      <data key="name">$name</data>
      <data key="graphics"><y:ShapeNode>
        <y:Geometry x="$x" y="$y" width="540" height="70"/>
        <y:Fill color="${if (index == 5) "#DCFCE7" else "#E0F2FE"}" transparent="false"/>
        <y:BorderStyle color="#52677F" type="line" width="1.0"/>
        <y:NodeLabel>$name</y:NodeLabel>
        <y:Shape type="roundrectangle"/>
      </y:ShapeNode></data>
    </node>
""")
    }
    sources.indices.foreach { index =>
      graph.append(s"""    <edge id="edge_$index" source="table_$index" target="table_5">
      <data key="edge_graphics"><y:PolyLineEdge>
        <y:LineStyle color="#71839A" type="line" width="1.5"/>
        <y:Arrows source="none" target="standard"/>
        <y:BendStyle smoothed="false"/>
      </y:PolyLineEdge></data>
    </edge>
""")
    }
    graph.append("  </graph>\n</graphml>\n")
    graph.toString
  }
}
