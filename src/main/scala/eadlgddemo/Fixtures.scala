package eadlgddemo

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.parquet.avro.{AvroParquetReader, AvroParquetWriter}
import org.apache.parquet.conf.PlainParquetConfiguration
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.{InputFile, OutputFile, PositionOutputStream, SeekableInputStream}

final case class Field(name: String, avroType: String, landingType: String) {
  def physicalType: String = avroType match {
    case "long" => "INT64"
    case "int" => "INT32"
    case "string" => "BINARY (UTF8)"
  }
}
final case class Dataset(name: String, fields: Vector[Field], key: Vector[String]) {
  def schema: Schema = new Schema.Parser().parse(Json.text(Map("type" -> "record", "name" -> name,
    "fields" -> fields.map(f => Map("name" -> f.name, "type" -> f.avroType)))))
  def ddl: String = fields.map(f => s"${f.name} ${f.landingType} NOT NULL").mkString(", ")
}
object Datasets {
  private def l(n: String): Field = Field(n, "long", "NUMBER(18,0)")
  private def i(n: String): Field = Field(n, "int", "NUMBER(10,0)")
  private def s(n: String): Field = Field(n, "string", "VARCHAR(40)")
  val customers: Dataset = Dataset("CUSTOMERS", Vector(l("CUSTOMER_ID"), s("SEGMENT"), s("COUNTRY"),
    i("RATING"), i("PD_BPS"), i("REPORTING_DAY")), Vector("CUSTOMER_ID"))
  val accounts: Dataset = Dataset("ACCOUNTS", Vector(l("ACCOUNT_ID"), l("CUSTOMER_ID"), s("PRODUCT_TYPE"),
    l("DRAWN_CENTS"), l("LIMIT_CENTS"), i("DISCOUNT_BPS"), i("REPORTING_DAY")), Vector("ACCOUNT_ID"))
  val assets: Dataset = Dataset("COLLATERAL_ASSETS", Vector(l("COLLATERAL_ID"), s("ASSET_TYPE"),
    l("VALUATION_CENTS"), i("HAIRCUT_BPS"), l("REALIZATION_COST_CENTS"), i("MONTHS_TO_REALIZATION")), Vector("COLLATERAL_ID"))
  val links: Dataset = Dataset("ACCOUNT_COLLATERAL", Vector(l("ACCOUNT_ID"), l("COLLATERAL_ID"),
    i("ALLOCATION_WEIGHT")), Vector("ACCOUNT_ID", "COLLATERAL_ID"))
  val recoveries: Dataset = Dataset("EXPECTED_RECOVERIES", Vector(l("RECOVERY_EVENT_ID"), i("VERSION"),
    l("ACCOUNT_ID"), l("RECOVERY_CENTS"), l("COST_CENTS"), i("MONTHS_TO_RECOVERY"),
    l("REVISION_ORDER")), Vector("RECOVERY_EVENT_ID", "VERSION"))
  val all: Vector[Dataset] = Vector(customers, accounts, assets, links, recoveries)
}

/** JVM NIO only: no Hadoop FileSystem, native library, shell or winutils invocation. */
object LocalParquet {
  def output(path: Path): OutputFile = new OutputFile {
    override def supportsBlockSize(): Boolean = false
    override def defaultBlockSize(): Long = 0L
    override def create(blockSizeHint: Long): PositionOutputStream = stream(false)
    override def createOrOverwrite(blockSizeHint: Long): PositionOutputStream = stream(true)
    private def stream(overwrite: Boolean): PositionOutputStream = {
      val opts = if (overwrite) Array(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        else Array(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      val channel = FileChannel.open(path, opts: _*)
      new PositionOutputStream {
        override def getPos: Long = channel.position()
        override def write(b: Int): Unit = write(Array(b.toByte), 0, 1)
        override def write(b: Array[Byte], off: Int, len: Int): Unit = {
          val buffer = ByteBuffer.wrap(b, off, len)
          while (buffer.hasRemaining) channel.write(buffer)
        }
        override def close(): Unit = channel.close()
      }
    }
  }
  def input(path: Path): InputFile = new InputFile {
    override def getLength: Long = Files.size(path)
    override def newStream(): SeekableInputStream = {
      val channel = FileChannel.open(path, StandardOpenOption.READ)
      new SeekableInputStream {
        override def getPos: Long = channel.position()
        override def seek(pos: Long): Unit = { channel.position(pos); () }
        override def read(): Int = {
          val b = ByteBuffer.allocate(1)
          if (channel.read(b) < 0) -1 else b.array()(0) & 0xff
        }
        override def read(bytes: Array[Byte], off: Int, len: Int): Int = channel.read(ByteBuffer.wrap(bytes, off, len))
        override def read(buffer: ByteBuffer): Int = channel.read(buffer)
        override def readFully(bytes: Array[Byte]): Unit = readFully(bytes, 0, bytes.length)
        override def readFully(bytes: Array[Byte], off: Int, len: Int): Unit = readFully(ByteBuffer.wrap(bytes, off, len))
        override def readFully(buffer: ByteBuffer): Unit = while (buffer.hasRemaining) {
          if (channel.read(buffer) < 0) throw new java.io.EOFException("Truncated Parquet file")
        }
        override def close(): Unit = channel.close()
      }
    }
  }
  def read(path: Path): Vector[GenericRecord] = {
    val reader = AvroParquetReader.builder[GenericRecord](input(path)).withConf(new PlainParquetConfiguration()).build()
    Lifecycle.protecting(Iterator.continually(reader.read()).takeWhile(_ != null).toVector)(reader.close())
  }
}

final case class FixtureFile(dataset: Dataset, path: Path, rowCount: Long, sizeBytes: Long, sha256: String)
final case class FixtureManifest(config: DemoConfig, files: Vector[FixtureFile]) {
  def write(dir: Path): Unit = Json.write(dir.resolve("fixture-manifest.json"), Map(
    "seed" -> config.seed, "reportingDate" -> config.reportingDate.toString, "currency" -> "EUR",
    "customerCount" -> config.customerCount, "accountsPerCustomer" -> 3,
    "dateEncoding" -> "INT32 epoch days; DATEADD(day, REPORTING_DAY, DATE '1970-01-01')",
    "moneyEncoding" -> "INT64 cents", "rateEncoding" -> "INT32 basis points",
    "files" -> files.map(f => Map("dataset" -> f.dataset.name, "path" -> f.path.toString,
      "rows" -> f.rowCount, "sizeBytes" -> f.sizeBytes, "sha256" -> f.sha256,
      "physicalKey" -> f.dataset.key, "fields" -> f.dataset.fields.map(c => Map(
        "name" -> c.name, "parquetType" -> c.physicalType, "landingType" -> c.landingType, "nullable" -> false))))))
}

object FixtureGenerator {
  def checksum(path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(path)
    val bytes = new Array[Byte](65536)
    Lifecycle.protecting { var n = in.read(bytes); while (n != -1) { digest.update(bytes, 0, n); n = in.read(bytes) } }(in.close())
    digest.digest().map(b => f"${b & 0xff}%02x").mkString
  }
  def generate(config: DemoConfig, dir: Path): FixtureManifest = {
    val input = Files.createDirectories(dir.resolve("input"))
    val files = Datasets.all.map { dataset =>
      val path = input.resolve(dataset.name.toLowerCase + ".parquet")
      val schema = dataset.schema
      val writer = AvroParquetWriter.builder[GenericRecord](LocalParquet.output(path))
        .withSchema(schema).withConf(new PlainParquetConfiguration())
        .withCompressionCodec(CompressionCodecName.UNCOMPRESSED).withRowGroupSize(8L * 1024 * 1024)
        .withDictionaryEncoding(true).build()
      var count = 0L
      Lifecycle.protecting { rows(dataset, config).foreach { values =>
        require(values.size == dataset.fields.size)
        val record = new GenericData.Record(schema)
        values.zipWithIndex.foreach { case (v, j) => record.put(j, v) }
        writer.write(record)
        count += 1
      } }(writer.close())
      FixtureFile(dataset, path, count, Files.size(path), checksum(path))
    }
    val manifest = FixtureManifest(config, files)
    manifest.write(dir)
    manifest
  }

  // SplitMix64-style stable keyed generation: a value does not depend on traversal order.
  private def noise(seed: Long, id: Long, salt: Long, bound: Long): Long = {
    var z = seed + id * 0x9E3779B97F4A7C15L + salt
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL
    Math.floorMod(z ^ (z >>> 31), bound)
  }
  def account(customer: Long, slot: Int): Long = (customer - 1) * 3 + slot + 1
  def asset(customer: Long, slot: Int): Long = (customer - 1) * 2 + slot + 1
  private def ordinaryAssets(c: Long): Int = if (c % 5 == 0) 1 else 2
  def rows(dataset: Dataset, config: DemoConfig): Iterator[Vector[Any]] = {
    val day = config.reportingDate.toEpochDay.toInt
    (1L to config.customerCount.toLong).iterator.flatMap { c =>
      val fixed = c <= 4
      dataset.name match {
        case "CUSTOMERS" => Iterator(Vector(c, Vector("RETAIL", "SME", "PRIVATE")((c % 3).toInt),
          Vector("SE", "DE", "FR")((c % 3).toInt), if (fixed) 3 else 1 + noise(config.seed, c, 1, 9).toInt,
          if (fixed) 200 else 50 + noise(config.seed, c, 2, 951).toInt, day))
        case "ACCOUNTS" => (0 until 3).iterator.map { slot =>
          val a = account(c, slot)
          val product = Vector("REVOLVING", "TERM", "MORTGAGE")(slot)
          val ordinaryDrawn = 10000L + noise(config.seed, a, 3, 9900000)
          val drawn = if (c == 1) 0L else if (c == 2 && slot == 0) 15000L
            else if (fixed) 10000L else ordinaryDrawn
          val limit = if (c == 1) 0L else if (fixed) 10000L
            else if (a % 17 == 0) drawn / 2 else drawn + noise(config.seed, a, 4, 5000000)
          Vector(a, c, product, drawn, limit, if (fixed) 0 else 300 + noise(config.seed, a, 5, 701).toInt, day)
        }
        case "COLLATERAL_ASSETS" =>
          if (c == 3) Iterator(Vector(asset(c, 0), "PROPERTY", 24000L, 0, 0L, 0))
          else if (c == 4) Iterator(Vector(asset(c, 0), "VEHICLE", 5000L, 0, 0L, 0))
          else if (fixed) Iterator.empty
          else (0 until ordinaryAssets(c)).iterator.map { slot =>
            val id = asset(c, slot)
            Vector(id, if (slot == 0) "PROPERTY" else "VEHICLE", 10000L + noise(config.seed, id, 6, 14000000),
              500 + noise(config.seed, id, 7, 2501).toInt, noise(config.seed, id, 8, 50000),
              6 + noise(config.seed, id, 9, 31).toInt)
          }
        case "ACCOUNT_COLLATERAL" =>
          if (c == 3) Iterator(Vector(account(c, 0), asset(c, 0), 1), Vector(account(c, 1), asset(c, 0), 3))
          else if (c == 4) Iterator(Vector(account(c, 0), asset(c, 0), 1))
          else if (fixed) Iterator.empty
          else (0 until ordinaryAssets(c)).iterator.flatMap { slot =>
            val slots = if (c % 10 == 0) Vector(0, 1) else Vector(slot, slot + 1)
            slots.iterator.map(s => Vector(account(c, s), asset(c, slot), s + 1))
          }
        case "EXPECTED_RECOVERIES" =>
          if (c == 1 || c == 3) Iterator.empty
          else if (c == 2) Vector(
            Vector(2 * account(c, 1) - 1, 1, account(c, 1), 10000L, 0L, 0, 1L),
            Vector(2 * account(c, 2) - 1, 1, account(c, 2), 25000L, 0L, 0, 1L)).iterator
          else if (c == 4) Vector(
            Vector(2 * account(c, 0) - 1, 1, account(c, 0), 1000L, 0L, 0, 1L),
            Vector(2 * account(c, 0) - 1, 2, account(c, 0), 3000L, 0L, 0, 2L),
            Vector(2 * account(c, 1) - 1, 1, account(c, 1), 4000L, 0L, 0, 1L)).iterator
          else (0 until 3).iterator.flatMap { slot =>
            val a = account(c, slot)
            if (a % 29 == 0) Iterator.empty
            else (0 until 2).iterator.flatMap { e =>
              val id = 2 * a - 1 + e
              val amount = 1000L + noise(config.seed, id, 10, 500000)
              val cost = noise(config.seed, id, 11, 10000)
              val months = 3 + noise(config.seed, id, 12, 34).toInt
              val row = Vector(id, 1, a, amount, cost, months, 1L)
              if (id % 97 == 0) Iterator(row, Vector(id, 2, a, amount + 1000L, cost, months, 2L))
              else Iterator(row)
            }
          }
      }
    }
  }
}
