package eadlgddemo

import java.nio.file.Path
import scala.util.control.NonFatal

final case class RunResult(runId: String, directory: Path, outputs: Vector[String], profilesVerified: Boolean)
object DemoRunner {
  def run(config: DemoConfig, connection: ConnectionConfig): RunResult = {
    require(DemoConfig.liveEnabled(), "A live run requires RUN_SNOWFLAKE_IT=true")
    val runId = DemoConfig.runId()
    val dir = DemoConfig.directory(runId)
    var summary = s"Run $runId started. Live correctness and profiles have not yet been verified.\n"
    Json.writeText(dir.resolve("run-summary.md"), summary)
    try {
      val manifest = FixtureGenerator.generate(config, dir)
      val session = SessionFactory.open(connection, config)
      Lifecycle.protecting {
        val registry = new ObjectRegistry(session, dir, config.keepObjects)
        Lifecycle.protecting {
          val profiler = new QueryProfiler(session, connection, runId, dir, registry)
          val inputs = new InputLoader(session, connection, profiler, registry, runId, dir).load(manifest)
          val job = new EadLgdJob(session, connection, profiler, registry, dir)
          val validator = new ResultValidator(session, connection, profiler, registry, runId, dir)
          val first = job.run(JobInput(inputs, config, runId, "E1"))
          validator.validate(first, inputs, config)
          val second = job.run(JobInput(inputs, config, runId, "E2"))
          validator.validate(second, inputs, config)
          validator.compare(first, second)
          val lines = profiler.phases.map(p => s"${p.execution} ${p.phase}: ${p.dataQueryIds.mkString(", ")} (${p.businessRows.map(_.toString).getOrElse("unverified")} business rows)")
          summary = s"""Educational EAD/LGD demo — $runId

Customers: ${config.customerCount}; accounts: ${config.customerCount.toLong * 3}; seed: ${config.seed}; date: ${config.reportingDate}.
All five Parquet inputs uploaded, loaded and validated before CP1: ${inputs.completedAtServerTime}.
Two independent executions: correctness and bidirectional determinism checks passed.
Profiles verified: ${profiler.profilesVerified}. Required: ${config.requireQueryProfiles}.

${lines.mkString("\n")}

Final outputs: ${first.finalTable}, ${second.finalTable}
KEEP_OBJECTS=${config.keepObjects}: ${if (config.keepObjects) "final outputs retained" else "final outputs removed during cleanup"}.
Temporary checkpoints end with this session. Cleanup SQL: sql/cleanup.sql.
Artifacts: ${dir.toAbsolutePath}

Find each query ID in Snowsight Query History, then open Query Profile.
Each profile describes one query; upstream checkpoint work has separate profiles.
"""
          Json.writeText(dir.resolve("run-summary.md"), summary)
          lines.foreach(println)
          println(s"Final output: ${first.finalTable}\nDeterminism output: ${second.finalTable}\nArtifacts: ${dir.toAbsolutePath}")
          profiler.enforceProfiles(config.requireQueryProfiles)
          session.setQueryTag(profiler.tag("CLEANUP"))
          RunResult(runId, dir, Vector(first.finalTable, second.finalTable), profiler.profilesVerified)
        } {
          Lifecycle.protecting {
            session.setQueryTag(s"""{"job":"eadlgddemo","run":"$runId","execution":"CLEANUP","phase":"CLEANUP"}""")
          } { registry.cleanup() }
        }
      } { session.close() }
    } catch {
      case NonFatal(e) =>
        try Json.writeText(dir.resolve("run-summary.md"), summary + s"\nRun failed: ${e.toString}\nPartial artifacts are retained. See cleanup.sql for exact created objects.\n")
        catch { case NonFatal(diagnostic) => e.addSuppressed(diagnostic) }
        throw e
    }
  }
}
object Main {
  def main(args: Array[String]): Unit = {
    val config = DemoConfig.fromEnv()
    args.toList match {
      case List("generate") =>
        val dir = DemoConfig.directory(DemoConfig.runId())
        val manifest = FixtureGenerator.generate(config, dir)
        manifest.files.foreach(f => println(s"${f.dataset.name}: ${f.rowCount} rows; ${f.sizeBytes} bytes; ${f.sha256}"))
        println(s"Local fixtures only; no Snowflake connection. Artifacts: ${dir.toAbsolutePath}")
      case Nil | List("run") =>
        require(DemoConfig.liveEnabled(), "Live execution requires RUN_SNOWFLAKE_IT=true. Use generate for local fixtures.")
        DemoRunner.run(config, ConnectionConfig.load())
      case _ => throw new IllegalArgumentException("Usage: generate | run")
    }
  }
}
