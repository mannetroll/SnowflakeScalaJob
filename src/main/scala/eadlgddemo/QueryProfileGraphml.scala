package eadlgddemo

import java.io.StringWriter
import java.nio.file.Path
import java.util.Locale
import javax.xml.stream.XMLOutputFactory
import com.fasterxml.jackson.databind.JsonNode
import com.snowflake.snowpark.Session
import scala.collection.JavaConverters._
import scala.collection.mutable

final case class ProfileOperator(step: Int, id: Int, kind: String, parents: Vector[Int],
    attributes: JsonNode, statistics: JsonNode, timing: JsonNode, raw: JsonNode) {
  def nodeId: String = s"s${step}_n$id"
}

final case class OperatorProfile(queryId: String, operators: Vector[ProfileOperator])

/** Exports observed Snowflake operators, including every execution step, to yEd GraphML. */
object QueryProfileGraphml {
  val GraphmlNamespace = "http://graphml.graphdrawing.org/xmlns"
  val YworksNamespace = "http://www.yworks.com/xml/graphml"
  private val QueryId = "(?i)^(?:Query\\s*-\\s*)?([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$".r

  def normalizeQueryId(input: String): String = input.trim match {
    case QueryId(id) => id.toLowerCase(Locale.ROOT)
    case _ => throw new IllegalArgumentException("QUERY_ID must be a Snowflake query UUID, optionally prefixed by 'Query - '")
  }

  /** Retrieves profile metadata only; never executes the profiled SQL or reruns the job. */
  def fetch(session: Session, input: String): JsonNode = {
    val id = normalizeQueryId(input)
    val rows = Json.rows(session.sql(
      s"SELECT * FROM TABLE(GET_QUERY_OPERATOR_STATS(${Sql.literal(id)})) ORDER BY STEP_ID, OPERATOR_ID"))
    require(rows.nonEmpty, s"No operator statistics for $id. Use an eligible completed query from the past 14 days and a role with MONITOR or OPERATE on its warehouse.")
    Json.mapper.readTree(Json.text(Map("queryId" -> id, "operators" -> rows)))
  }

  // JDBC/Snowpark versions can return VARIANT/ARRAY columns as JSON values or JSON strings.
  private def decoded(node: JsonNode): JsonNode = {
    if (node == null || node.isNull) Json.mapper.nullNode()
    else if (node.isTextual) Json.mapper.readTree(node.asText())
    else node
  }

  private def number(node: JsonNode, field: String): Int = {
    require(node != null && node.isIntegralNumber && node.canConvertToInt && node.intValue() >= 0,
      s"$field must be a nonnegative integer")
    node.intValue()
  }

  def parse(input: String, document: JsonNode): OperatorProfile = {
    val id = normalizeQueryId(input)
    require(document != null, "Profile JSON is empty")
    if (document.hasNonNull("queryId")) {
      require(normalizeQueryId(document.get("queryId").asText()) == id, "Profile queryId does not match QUERY_ID")
    }
    val rows = if (document.isArray) document else document.path("operators")
    require(rows.isArray && rows.size() > 0, "Profile must contain a nonempty operators array")
    val operators = rows.elements().asScala.map { row =>
      require(row.hasNonNull("QUERY_ID") && normalizeQueryId(row.get("QUERY_ID").asText()) == id,
        "Operator QUERY_ID does not match QUERY_ID")
      val parents = decoded(row.get("PARENT_OPERATORS"))
      require(parents.isNull || parents.isArray, "PARENT_OPERATORS must be an array or null")
      def obj(field: String): JsonNode = {
        val value = decoded(row.get(field))
        require(value.isNull || value.isObject, s"$field must be an object or null")
        if (value.isNull) Json.mapper.createObjectNode() else value
      }
      val kind = row.path("OPERATOR_TYPE").asText("")
      require(kind.nonEmpty, "Missing OPERATOR_TYPE")
      ProfileOperator(number(row.get("STEP_ID"), "STEP_ID"), number(row.get("OPERATOR_ID"), "OPERATOR_ID"),
        kind, if (parents.isNull) Vector.empty else parents.elements().asScala.map(number(_, "parent ID")).toVector.distinct,
        obj("OPERATOR_ATTRIBUTES"), obj("OPERATOR_STATISTICS"), obj("EXECUTION_TIME_BREAKDOWN"), row)
    }.toVector.sortBy(o => (o.step, o.id))
    require(operators.map(_.nodeId).distinct.size == operators.size, "Duplicate operator ID within a step")
    val keys = operators.map(o => (o.step, o.id)).toSet
    operators.foreach(o => o.parents.foreach { parent =>
      require(keys((o.step, parent)), s"Missing parent $parent in step ${o.step}")
    })
    val profile = OperatorProfile(id, operators)
    operators.groupBy(_.step).values.foreach(depths) // Also reject cycles before writing any files.
    profile
  }

  private def depths(operators: Vector[ProfileOperator]): Map[Int, Int] = {
    val level = mutable.Map.empty[Int, Int]
    var pending = operators
    while (pending.nonEmpty) {
      val (ready, rest) = pending.partition(_.parents.forall(level.contains))
      require(ready.nonEmpty, "Operator graph contains a cycle")
      ready.foreach(o => level(o.id) = if (o.parents.isEmpty) 0 else o.parents.map(level).max + 1)
      pending = rest
    }
    level.toMap
  }

  private def value(node: JsonNode, field: String): Option[String] =
    Option(node.get(field)).filterNot(_.isNull).map(n => if (n.isTextual) n.asText() else n.toString)

  private def names(o: ProfileOperator): Vector[String] = o.attributes.fields().asScala.toVector
    .filter(e => e.getKey == "table_name" || e.getKey.endsWith("_name") || e.getKey.endsWith("_names"))
    .sortBy(_.getKey).flatMap { entry =>
      val n = entry.getValue
      if (n.isArray) n.elements().asScala.map(_.asText()).toVector else if (n.isTextual) Vector(n.asText()) else Vector.empty
    }.distinct

  private def label(o: ProfileOperator): String = {
    val rows = Vector("input_rows" -> "Input rows", "output_rows" -> "Output rows")
      .flatMap { case (key, title) => value(o.statistics, key).map(v => s"$title: $v") }
    val inserted = value(o.statistics.path("dml"), "number_of_rows_inserted").map("Rows inserted: " + _).toVector
    val percent = value(o.timing, "overall_percentage").map { p =>
      "Execution: " + (BigDecimal(p) * 100).bigDecimal.stripTrailingZeros.toPlainString + "% of step"
    }.toVector
    (Vector(s"${o.kind} [${o.id}] | Step ${o.step}") ++ names(o) ++
      value(o.attributes, "join_type").map("Join type: " + _).toVector ++ rows ++ inserted ++ percent).mkString("\n")
  }

  private case class Box(x: Double, y: Double, width: Double, height: Double)
  private case class StepLayout(step: Int, operators: Vector[ProfileOperator], boxes: Map[Int, Box], bounds: Box)

  /** Roots at the top, sources below; groups are placed side by side. No invented edges between steps. */
  private def layout(profile: OperatorProfile): Vector[StepLayout] = {
    var offset = 0.0
    profile.operators.groupBy(_.step).toVector.sortBy(_._1).map { case (step, operators) =>
      val level = depths(operators)
      val sizes = operators.map { o =>
        val lines = label(o).split("\n", -1)
        (o.id, (math.max(360.0, lines.map(_.length).max * 8.5 + 40.0), lines.length * 20.0 + 28.0))
      }.toMap
      val layers = operators.groupBy(o => level(o.id)).toVector.sortBy(_._1).map(_._2.sortBy(_.id))
      val widths = layers.map(nodes => nodes.map(o => sizes(o.id)._1).sum + (nodes.size - 1) * 70.0)
      val contentWidth = widths.max
      val boxes = mutable.Map.empty[Int, Box]
      var y = 90.0
      layers.zip(widths).foreach { case (nodes, width) =>
        val ordered = nodes.sortBy { o =>
          val center = if (o.parents.isEmpty) 0.0 else o.parents.map { p =>
            val b = boxes(p); b.x + b.width / 2
          }.sum / o.parents.size
          (center, o.id)
        }
        var x = offset + 35.0 + (contentWidth - width) / 2
        ordered.foreach { o =>
          val (w, h) = sizes(o.id)
          boxes(o.id) = Box(x, y, w, h)
          x += w + 70.0
        }
        y += nodes.map(o => sizes(o.id)._2).max + 90.0
      }
      val bounds = Box(offset, 0, contentWidth + 70, y - 55)
      offset += bounds.width + 100
      StepLayout(step, operators, boxes.toMap, bounds)
    }
  }

  // XML 1.0 cannot represent arbitrary SQL control characters; keep the original in the JSON sidecar.
  private def xmlText(value: String): String = {
    val b = new java.lang.StringBuilder
    value.codePoints().toArray.foreach { c =>
      val valid = c == 9 || c == 10 || c == 13 || (c >= 32 && c <= 0xd7ff) ||
        (c >= 0xe000 && c <= 0xfffd) || (c >= 0x10000 && c <= 0x10ffff)
      b.appendCodePoint(if (valid) c else 0xfffd)
    }
    b.toString
  }

  def render(profile: OperatorProfile): String = {
    val text = new StringWriter
    val xml = XMLOutputFactory.newFactory().createXMLStreamWriter(text)
    def start(name: String, attrs: (String, String)*): Unit = {
      if (name.startsWith("y:")) xml.writeStartElement("y", name.drop(2), YworksNamespace)
      else xml.writeStartElement(name)
      attrs.foreach { case (k, v) => xml.writeAttribute(k, xmlText(v)) }
    }
    def end(): Unit = xml.writeEndElement()
    def empty(name: String, attrs: (String, String)*): Unit = { start(name, attrs: _*); end() }
    def content(name: String, value: String, attrs: (String, String)*): Unit = {
      start(name, attrs: _*); xml.writeCharacters(xmlText(value)); end()
    }
    def data(key: String, value: String): Unit = content("data", value, "key" -> key)
    def geometry(b: Box): Unit = empty("y:Geometry", "x" -> b.x.toString, "y" -> b.y.toString,
      "width" -> b.width.toString, "height" -> b.height.toString)
    def nodeLabel(s: String, group: Boolean = false): Unit = content("y:NodeLabel", s,
      "alignment" -> "left", "autoSizePolicy" -> "content", "fontFamily" -> "Monospaced",
      "fontSize" -> "13", "fontStyle" -> "plain", "textColor" -> "#162333",
      "modelName" -> "internal", "modelPosition" -> (if (group) "t" else "c"), "visible" -> "true")
    xml.writeStartDocument("UTF-8", "1.0")
    start("graphml")
    xml.writeDefaultNamespace(GraphmlNamespace)
    xml.writeNamespace("y", YworksNamespace)
    xml.writeNamespace("xsi", "http://www.w3.org/2001/XMLSchema-instance")
    xml.writeAttribute("xsi", "http://www.w3.org/2001/XMLSchema-instance", "schemaLocation",
      GraphmlNamespace + " https://www.yworks.com/xml/schema/graphml/1.1/ygraphml.xsd")
    empty("key", "id" -> "ng", "for" -> "node", "yfiles.type" -> "nodegraphics")
    empty("key", "id" -> "eg", "for" -> "edge", "yfiles.type" -> "edgegraphics")
    empty("key", "id" -> "description", "for" -> "node", "attr.name" -> "description", "attr.type" -> "string")
    Vector("query_id", "step_id", "operator_id", "operator_type", "full_name", "operator_attributes",
      "operator_statistics", "execution_time_breakdown").foreach { key =>
      empty("key", "id" -> key, "for" -> "node", "attr.name" -> key, "attr.type" -> "string")
    }
    empty("key", "id" -> "profile", "for" -> "graph", "attr.name" -> "description", "attr.type" -> "string")
    start("graph", "id" -> "query_profile", "edgedefault" -> "directed")
    data("profile", s"Snowflake query ${profile.queryId}. Observed operators from GET_QUERY_OPERATOR_STATS. Arrows follow data flow from child to parent; disconnected groups are separate execution steps. Percentages are per step. Full attributes and statistics are node properties.")
    layout(profile).foreach { step =>
      start("node", "id" -> s"step_${step.step}", "yfiles.foldertype" -> "group")
      data("description", s"Query ${profile.queryId}, execution step ${step.step}")
      start("data", "key" -> "ng")
      start("y:ProxyAutoBoundsNode")
      start("y:Realizers", "active" -> "0")
      start("y:GroupNode")
      geometry(step.bounds)
      empty("y:Fill", "color" -> "#F4F7FB", "transparent" -> "false")
      empty("y:BorderStyle", "color" -> "#9AA9BA", "type" -> "line", "width" -> "1.0")
      nodeLabel(s"Step ${step.step} | ${step.operators.size} operators\nQuery ${profile.queryId}", group = true)
      empty("y:Shape", "type" -> "roundrectangle")
      empty("y:State", "closed" -> "false", "closedWidth" -> "460", "closedHeight" -> "70", "innerGraphDisplayEnabled" -> "false")
      empty("y:Insets", "bottom" -> "25", "left" -> "25", "right" -> "25", "top" -> "65")
      end(); end(); end(); end()
      start("graph", "id" -> s"step_${step.step}_graph", "edgedefault" -> "directed")
      step.operators.foreach { o =>
        start("node", "id" -> o.nodeId)
        data("description", Json.text(o.raw))
        Vector("query_id" -> profile.queryId, "step_id" -> o.step.toString, "operator_id" -> o.id.toString,
          "operator_type" -> o.kind, "full_name" -> names(o).mkString("\n"),
          "operator_attributes" -> o.attributes.toString, "operator_statistics" -> o.statistics.toString,
          "execution_time_breakdown" -> o.timing.toString).foreach { case (k, v) => data(k, v) }
        start("data", "key" -> "ng")
        start("y:ShapeNode")
        geometry(step.boxes(o.id))
        val color = o.kind match {
          case "TableScan" => "#E0F2FE"
          case "CreateTableAsSelect" | "Insert" | "CREATE TABLE" => "#DCFCE7"
          case "Join" => "#EDE9FE"
          case "Aggregate" | "WindowFunction" => "#FEF3C7"
          case _ => "#FFFFFF"
        }
        empty("y:Fill", "color" -> color, "transparent" -> "false")
        empty("y:BorderStyle", "color" -> "#52677F", "type" -> "line", "width" -> "1.0")
        nodeLabel(label(o))
        empty("y:Shape", "type" -> "roundrectangle")
        end(); end(); end()
      }
      step.operators.foreach { o => o.parents.sorted.foreach { parent =>
        start("edge", "id" -> s"e_s${o.step}_${o.id}_$parent", "source" -> o.nodeId, "target" -> s"s${o.step}_n$parent")
        start("data", "key" -> "eg")
        start("y:PolyLineEdge")
        val source = step.boxes(o.id)
        val target = step.boxes(parent)
        start("y:Path", "sx" -> "0.0", "sy" -> (-source.height / 2).toString,
          "tx" -> "0.0", "ty" -> (target.height / 2).toString)
        val bendY = (source.y + target.y + target.height) / 2
        empty("y:Point", "x" -> (source.x + source.width / 2).toString, "y" -> bendY.toString)
        empty("y:Point", "x" -> (target.x + target.width / 2).toString, "y" -> bendY.toString)
        end()
        empty("y:LineStyle", "color" -> "#71839A", "type" -> "line", "width" -> "1.5")
        empty("y:Arrows", "source" -> "none", "target" -> "standard")
        value(o.statistics, "output_rows").foreach { rows =>
          content("y:EdgeLabel", s"$rows rows", "fontFamily" -> "Dialog", "fontSize" -> "11",
            "modelName" -> "six_pos", "modelPosition" -> "tail", "backgroundColor" -> "#FFFFFF")
        }
        empty("y:BendStyle", "smoothed" -> "false")
        end(); end(); end()
      }}
      end(); end()
    }
    end(); end()
    xml.writeEndDocument()
    xml.close()
    text.toString
  }

  def write(input: String, document: JsonNode, path: Path): OperatorProfile = {
    require(path.getFileName.toString.toLowerCase(Locale.ROOT).endsWith(".graphml"), "Output file must end in .graphml")
    val profile = parse(input, document)
    Json.writeText(path, render(profile))
    profile
  }
}
