package eadlgddemo

import java.nio.file.Path

/** Expected table dependencies are separate from Snowflake's observed lineage. */
object TableLineage {
  def write(inputs: LoadedInputs, output: String, execution: String, dir: Path): Unit = {
    val sources = inputs.tables.values.toVector.sorted
    Json.write(dir.resolve(s"$execution-expected-table-lineage.json"), Map(
      "evidence" -> "expected dependencies, not observed GET_LINEAGE results",
      "nodes" -> (sources :+ output),
      "edges" -> sources.map(source => Map("source" -> source, "target" -> output))))
    val graph = new StringBuilder
    graph.append("""<?xml version="1.0" encoding="UTF-8"?>
<graphml xmlns="http://graphml.graphdrawing.org/xmlns" xmlns:y="http://www.yworks.com/xml/graphml">
  <key id="graphics" for="node" yfiles.type="nodegraphics"/>
  <key id="edge_graphics" for="edge" yfiles.type="edgegraphics"/>
  <key id="name" for="node" attr.name="table_name" attr.type="string"/>
  <key id="evidence" for="graph" attr.name="evidence" attr.type="string"/>
  <graph id="table_lineage" edgedefault="directed">
    <data key="evidence">Expected dependencies; inspect Snowflake GET_LINEAGE for observed edges</data>
""")
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
    Json.writeText(dir.resolve(s"$execution-expected-table-lineage.graphml"), graph.toString)
    Json.writeText(dir.resolve(s"sql/$execution-inspect-lineage.sql"),
      s"""-- Run after KEEP_OBJECTS=true. Requires Enterprise Edition and lineage privileges.
         |-- Snowflake lineage metadata may not be available immediately after CTAS.
         |-- Returned rows are observed edges; the JSON manifest contains expectations only.
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
}
