package eadlgddemo

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
final class ProfileAcceptanceTest extends AnyFunSuite {
  // Local metadata fixtures only: these are not Snowflake query IDs or live evidence.
  private def phases(execution: String): Vector[PhaseEvidence] = QueryProfiler.RequiredPhases.map { phase =>
    val id = s"unit-$execution-$phase"
    val rows = if (phase == "FINAL_CTAS") 100L else 300L
    PhaseEvidence(execution, phase, "unit-tag", Vector(s"UNIT_${execution}_$phase"),
      Vector(id), Vector.empty, rows, Some(rows), Vector(Map("queryId" -> id, "verified" -> true)))
  }

  test("one complete execution accepts exactly three checkpoint profiles and one CTAS profile") {
    val single = phases("E1")
    assert(QueryProfiler.completeProfiles(single, Set("E1")))
    assert(!QueryProfiler.completeProfiles(single.dropRight(1), Set("E1")))
    assert(!QueryProfiler.completeProfiles(single :+ single.head, Set("E1")))
    assert(!QueryProfiler.completeProfiles(single.dropRight(1) :+ single.head, Set("E1")))
  }

  test("determinism acceptance requires complete profiles for both independent executions") {
    val first = phases("E1")
    val second = phases("E2")
    assert(!QueryProfiler.completeProfiles(first, Set("E1", "E2")))
    assert(QueryProfiler.completeProfiles(first ++ second, Set("E1", "E2")))
    assert(!QueryProfiler.completeProfiles(first ++ second, Set("E1")))
  }

  test("missing operator evidence, mismatched IDs and unverified row counts fail profile acceptance") {
    val single = phases("E1")
    val finalPhase = single.last
    def accepts(replacement: PhaseEvidence): Boolean =
      QueryProfiler.completeProfiles(single.dropRight(1) :+ replacement, Set("E1"))
    assert(!accepts(finalPhase.copy(profiles = Vector.empty)))
    assert(!accepts(finalPhase.copy(profiles = Vector(Map("queryId" -> finalPhase.dataQueryIds.head, "verified" -> false)))))
    assert(!accepts(finalPhase.copy(profiles = Vector(Map("queryId" -> "unrelated-unit-id", "verified" -> true)))))
    assert(!accepts(finalPhase.copy(businessRows = None)))
    assert(!accepts(finalPhase.copy(businessRows = Some(99L))))
    assert(!QueryProfiler.completeProfiles(Vector.empty, Set.empty))
  }
}
