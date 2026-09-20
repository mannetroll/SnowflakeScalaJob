# Verification record — 2026-09-20

The complete single-run ScalaTest **passed on Snowflake** using role `SYSADMIN`,
warehouse `SCALING_BENCH_WH` (confirmed X-Small), and schema
`RISKDEMO.EAD_LGD_DEMO`. It loaded all five Parquet datasets, executed three native
`cacheResult()` checkpoints and one final transient CTAS, and retrieved all four
operator profiles. Local verification used macOS arm64, Gradle 8.14.3 and JDK 17.

| Check | Observed result |
| --- | --- |
| Main and test Scala compilation | Passed against the pinned real Snowpark/JDBC dependencies |
| `./gradlew test` | 19 discovered: 17 passed, 2 live tests explicitly skipped; 0 failures/errors |
| `./gradlew offlineTest` | 17 passed, 0 skipped, 0 failures/errors; no Snowflake connection |
| `./gradlew test --tests '*EadLgdSnowflakeIntegrationTest' --rerun-tasks` | Exactly 1 discovered and skipped because the live gate was unset |
| `./gradlew generateFixtures` | Five real Parquet files generated at the full 100,000-customer scale |
| `./gradlew installDist` | Standalone application distribution built |
| Installed launcher, `CUSTOMER_COUNT=10 ... generate` | Successfully wrote all five smoke fixtures without Snowflake |
| Native Windows host execution | Not performed; Windows path conventions tested locally and checked against JDBC 3.24.2 source |
| Live `EadLgdSnowflakeSingleRunTest` | 1 passed, 0 skipped, 0 failures/errors; 63.186 seconds |
| Live uploads/COPY, Snowpark numerical validation and CTAS | Passed at 100,000 customers; 28 input and 38 business/output checks |
| Actual Snowflake query IDs and operator profiles | All four data-query IDs captured; four nonempty profiles verified |
| Retained final output after the test session closed | Confirmed TRANSIENT, 100,000 rows, owner SYSADMIN |
| Separate two-execution server determinism comparison | Not run; `determinismVerified=false` is explicitly recorded |

Commands here used `GRADLE_USER_HOME="$PWD/.gradle-home"` to keep build caches
inside the writable workspace. This override is optional for ordinary use. The
wrapper executable, batch script, JAR, properties and distribution checksum are
included. Main runtime versions were confirmed in the installed distribution:
Scala 2.12.20, Snowpark 1.21.0, JDBC 3.24.2, Parquet 1.15.2, Avro 1.12.0,
Hadoop APIs 3.4.1 and Jackson 2.18.0.

The offline tests cover strict configuration/gating, SQL identifiers, connection
property mapping, key paths, Windows PUT paths, exception preservation, readable
Parquet schemas/keys/nullability, deterministic checksums, manifests, relational
integrity, missing cases, shared assets, versioned events, default generator
cardinalities, data-query classification, and independent reference calculations.
Profile-acceptance tests cover exactly four phases for one execution, all eight
phases for determinism, and rejection of missing/mismatched/unverified evidence.
These tests do not simulate a Snowflake integration run.

`EadLgdSnowflakeSingleRunTest` executes one complete job: five loads, CP1, CP2, CP3
and one final CTAS. It asserts one output, exactly those four phases, and verified
account/customer counts. The application `run` command uses the same single-run
mode. `EadLgdSnowflakeIntegrationTest` explicitly enables the separate two-execution
determinism check. Both suites have been discovered locally and cancel before
connecting when the gate is unset. Single-run execution records correctness and
profile verification independently from the unrequested determinism comparison.

The full local fixture run is
`build/ead-lgd/R32E1B1D1A90946EEB19B5CA7B3B8CC76/`:

| Dataset | Actual rows | Bytes |
| --- | ---: | ---: |
| CUSTOMERS | 100,000 | 1,032,955 |
| ACCOUNTS | 300,000 | 9,061,345 |
| COLLATERAL_ASSETS | 179,994 | 4,741,271 |
| ACCOUNT_COLLATERAL | 359,987 | 5,381,489 |
| EXPECTED_RECOVERIES | 585,266 | 14,789,663 |

Total: 35,006,723 bytes. Per-file SHA-256 and complete schemas are in that run's
`fixture-manifest.json`. The successful live run regenerated and uploaded fresh
fixtures with these same counts. Build artifacts are intentionally ignored and
can be removed by `clean`; this record preserves the observed results.

## Successful live acceptance

Run ID: `R570F5E1751AE4ABA8B8A30CC39919CD7`.

The user explicitly authorized `SYSADMIN` after the initially requested role was
unavailable. `RISKDEMO` and `RISKDEMO.EAD_LGD_DEMO` were created using the approved
role. The existing warehouse was inspected and left at X-Small; no grants or
warehouse settings were changed. The live ScalaTest used the existing local
key-pair credentials with explicit live opt-in:

```bash
SNOWFLAKE_ROLE=SYSADMIN SNOWFLAKE_WAREHOUSE=SCALING_BENCH_WH \
SNOWFLAKE_DATABASE=RISKDEMO SNOWFLAKE_SCHEMA=EAD_LGD_DEMO \
RUN_SNOWFLAKE_IT=true CUSTOMER_COUNT=100000 KEEP_OBJECTS=true \
REQUIRE_QUERY_PROFILES=true \
./gradlew test --tests '*EadLgdSnowflakeSingleRunTest' --rerun-tasks --info
```

Gradle reported `BUILD SUCCESSFUL in 1m 8s`. The test result is recorded in
`build/test-results/test/TEST-eadlgddemo.EadLgdSnowflakeSingleRunTest.xml`.

All five PUT results were `UPLOADED`; all five COPY results were `LOADED` with
zero errors and the row counts listed above. Input validation completed at
`2026-09-20 16:53:16.082 UTC`, before CP1 began.

| Phase | Actual data-query ID | Business rows | Query elapsed (ms) | Operators |
| --- | --- | ---: | ---: | ---: |
| CP1_ACCOUNT_EAD | `01c73375-0005-833e-0001-91ae002326b6` | 300,000 | 972 | 6 |
| CP2_ACCOUNT_COLLATERAL | `01c73375-0005-833e-0001-91ae00232726` | 300,000 | 1,575 | 11 |
| CP3_ACCOUNT_LGD | `01c73375-0005-833e-0001-91ae0023279a` | 300,000 | 2,064 | 12 |
| FINAL_CTAS | `01c73375-0005-833e-0001-91ae00232802` | 100,000 | 1,144 | 13 |

Checkpoint IDs identify the INSERT statements emitted by native `cacheResult()`;
their companion temporary-table DDL is also recorded in the manifest. Query
elapsed times come from Snowflake query history and exclude the surrounding
loads, validation and profile retrieval. These are single observations, not a
performance benchmark. Each checkpoint feeds the next stage. The final CTAS
profile contains joins, aggregation and a window operator.

The retained final table is:

```text
RISKDEMO.EAD_LGD_DEMO.EAD_R570F5E1751AE4ABA8B8A30CC39919CD7_E1_OUTPUT
```

After the ScalaTest session closed, a separate metadata check confirmed that
the output still existed as a transient table with 100,000 rows. Temporary
checkpoints ended with their session.

Evidence is under
`build/ead-lgd/R570F5E1751AE4ABA8B8A30CC39919CD7/`: `load-results.json`,
`query-manifest.json`, four `profiles/*.json` exports, `validation-summary.json`,
`retained-output.json`, `run-summary.md`, and SQL for the executed CTAS, profile
inspection and cleanup. The manifest records `profilesVerified=true`; validation
records `correctnessVerified=true`. Paste the query IDs into Snowsight Query
History and open Query Profile to inspect each query.

The additional two-execution determinism suite remains available separately:

```bash
RUN_SNOWFLAKE_IT=true CUSTOMER_COUNT=100000 KEEP_OBJECTS=true \
REQUIRE_QUERY_PROFILES=true \
./gradlew test --tests '*EadLgdSnowflakeIntegrationTest' --rerun-tasks --info
```

The [README](README.md) includes Unix/PowerShell configuration and IntelliJ
instructions. This is an invented educational EAD/LGD model. Passing the checks
does not establish regulatory or production suitability.

## Query Profile GraphML export — 2026-09-20

Added `QueryProfileGraphmlExportTest`, accepting `QUERY_ID` or Gradle
`-PqueryId=<UUID>` and optionally `QUERY_PROFILE_OUTPUT` / `-PqueryProfileOutput`.
It reads `GET_QUERY_OPERATOR_STATS` for an existing query, closes the session,
and creates yEd GraphML plus a source-statistics JSON sidecar. It never executes
the original query or reruns the EAD/LGD job.

The live export ScalaTest passed for
`01c73375-0005-833e-0001-91ae00232802`, producing:

```text
build/query-profiles/01c73375-0005-833e-0001-91ae00232802.graphml
build/query-profiles/01c73375-0005-833e-0001-91ae00232802.operators.json
build/query-profiles/TEST-QueryProfileGraphmlExportTest-live.xml
```

The exported profile has 13 operators, two execution-step groups and 11 directed
edges. XML parsing and assertions confirm all reported operators and parent
connections. Full table/object names are visible labels; unabridged SQL
expressions and statistics remain in node properties. Layout positions, colors,
row-count edge labels and per-step execution percentages are included. Node IDs
include both step and operator ID, preserving the separate operator `0` in each
step. The generated file was opened through macOS Launch Services in the installed
yEd application; UI inspection was unavailable because macOS denied assistive
access. An attempted standalone XSD check was inconclusive: the validators also
reject yEd's own bundled group-node example against the published schema.

Six added offline tests cover ID validation, multiple steps, shared-parent edges,
full names, XML escaping, Unicode/control characters, deterministic layout,
non-overlapping node boxes, and rejection of incomplete or contradictory evidence.
`offlineTest`: 23 passed, zero skipped/failed. Standard `test` with the live gate
off: 23 passed, three live suites skipped, zero failures/errors. No dependencies
were added. Generated profiles remain under ignored `build/` and are removed by
`clean`.
