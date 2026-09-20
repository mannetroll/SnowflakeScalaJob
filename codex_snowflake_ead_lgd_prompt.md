Snowflake connecy using in a snowflake.properties
uv run python connect/snowflake_connection.py 


# Codex task: build a testable Scala/Snowpark EAD–LGD profiling job

Implement the working code in the current repository, not just a design or pseudocode. Build an IntelliJ-compatible Gradle Scala job that generates synthetic credit-risk inputs, uploads five real Parquet datasets to Snowflake, loads them into tables, executes at least three Snowpark materialization checkpoints, and finishes with a genuine CREATE TABLE AS SELECT (CTAS). Run the pipeline through a discoverable ScalaTest integration test and produce the query IDs and profiling evidence needed to inspect the checkpoints and final CTAS in Snowsight.

## 1. Repository context and boundaries

Read `AGENTS.md`, the build files, wrapper properties, README, and existing Snowflake/test helpers first. Reuse compatible infrastructure. Add an isolated `eadlgddemo` package or module; do not rewrite production transformations, publishing, release configuration, or unrelated tests. If the workspace is empty, create a standalone Gradle project. Do not assume screenshot-only helpers exist in the workspace.

Use Snowpark Scala, not Spark/PySpark, pandas, a local SQL engine, or a mock Snowflake connection for the integration path. All business joins, aggregations, windows, checkpoints, and final output creation must execute in Snowflake. Local code may generate fixtures and calculate small independent test expectations.

Use a compatible, explicitly pinned combination of Gradle, JDK, Scala, Snowpark, Parquet, and ScalaTest dependencies. Preserve existing compatible versions; for a new project prefer JDK 17 and a supported Scala binary version. Verify the selected Snowpark Scala API rather than mixing in Python/Java API methods. Document the resolved version matrix. Add required JVM/Arrow options to the actual test JVM and IntelliJ instructions, not by hand-editing `gradlew.bat` or disabling security checks.

## 2. Checkpoint and profile semantics

The required native Snowpark Scala API is `DataFrame.cacheResult()` (singular). Do not invent `.cacheResults()`. If the repository already has a labeled wrapper, inspect it and verify that it delegates to real Snowpark caching. Otherwise, implement a small labeled profiling helper around the native call.

Create at least three distinct, meaningful checkpoints. Each must execute against Snowflake, return a materialized DataFrame, and feed subsequent processing. Do not discard the cached return value and continue from the original lazy DataFrame. Do not count a variable assignment, view, `explain()`, local collection, or repeated caching of the same unchanged data as a checkpoint. Caching is itself an action; do not add a redundant action just to trigger it.

A Query Profile describes a query, not the complete cross-query pipeline. Three checkpoints are a requirement of this demo, not a prerequisite for Snowflake to display a graph. Capture the data-processing statement(s) for CP1, CP2, CP3, and FINAL_CTAS separately. Do not promise that the final profile will expand through earlier materialized checkpoint tables.

Keep meaningful joins, aggregation, and a used window expression after CP3 so that the final CTAS is not merely a copy of the last cached result. A separate logical pipeline diagram may explain cross-query dependencies, but it must not be presented as an actual Snowflake execution profile. Exact operator topology is optimizer-dependent.

## 3. Invent the synthetic case and five Parquet inputs

Create a clearly labeled educational EAD/LGD scenario, not a validated regulatory, accounting, or production credit model. Use one currency, EUR, a fixed default reporting date `2025-11-30`, and a fixed configurable seed. Generate exactly 100,000 customers by default, with three accounts per customer. Support a small smoke-test size without changing the default acceptance run.

Generate these five logical datasets locally as actual Parquet files, preferably one file per dataset at the default size:

| Dataset | Default scale | Required content |
| --- | ---: | --- |
| CUSTOMERS | 100,000 | Unique customer ID, segment, country, rating, synthetic PD, reporting date |
| ACCOUNTS | 300,000 | Unique account ID, customer ID, product type, drawn balance, credit limit, discount rate, reporting date |
| COLLATERAL_ASSETS | Approximately 180,000 | Unique collateral ID, asset type, valuation, haircut, realization cost, months to realization |
| ACCOUNT_COLLATERAL | Approximately 360,000 | Account ID, collateral ID, positive allocation weight; unique account/collateral pairs |
| EXPECTED_RECOVERIES | Approximately 600,000 | Recovery event ID, account ID, projected unsecured recovery amount, projected cost, months to recovery, deterministic version/tie-break fields |

Use `(RECOVERY_EVENT_ID, VERSION)` as the recovery input row key and one row per event ID after version selection. Define all other physical and logical keys explicitly.

Include revolving credit, term loans, and mortgages. Some assets should secure multiple accounts, and some accounts should have multiple assets. Restrict shared assets to accounts belonging to the same customer for this demo. Generate missing collateral and missing recovery cases deliberately. Include known examples of zero EAD, over-limit balances, full recovery, partial recovery, and recoveries exceeding EAD. Include a few versioned recovery events to exercise deterministic deduplication.

Define all column names, physical Parquet types, landing-table types, and nullability explicitly. Use a JVM Parquet writer with streaming generation; no external Python process or Spark cluster. Make local writing work on Windows without requiring an undeclared Hadoop installation or downloaded `winutils.exe`.

Use decimal-safe encodings: for example, integer cents for money and integer basis points for rates in Parquet, then explicit decimal conversion in Snowflake. Define date handling and rounding. Use deterministic identifiers, generation order, and version selection; do not depend on wall-clock time or unseeded randomness for business values.

Write generated files under `build/ead-lgd/<run-id>/input/`, not into committed test resources. Produce a fixture manifest with seed, reporting date, row counts, schemas, sizes, and file checksums. Add generated data and local secrets to `.gitignore`.

## 4. Complete ingestion before starting the transformation

The integration-test setup must generate all five datasets, upload all five to an internal Snowflake stage, load all five into explicit landing tables, and verify the complete load before invoking the business job. A successful PUT alone is not enough.

Use run-isolated names and stage paths. Prefer Snowpark's supported file upload API; use cross-platform file URIs and inspect upload results. Load Parquet with explicit target schemas and `COPY INTO`, with `ON_ERROR = ABORT_STATEMENT`. Use either direct column-name matching or an explicit COPY transformation, as supported by Snowflake; do not combine `MATCH_BY_COLUMN_NAME` with a COPY SELECT transformation.

Check COPY results, expected row counts, required-column nulls, primary-key uniqueness, and foreign-key integrity. Do not rely on declarations alone to validate the synthetic relationships. Reject partial, skipped, or malformed loads. Avoid accidental duplicate loads on reruns through run isolation and explicit file selection.

Record a phase boundary proving that all five loads completed before CP1 began. The business job must read the loaded Snowflake tables, not the local files or locally generated Scala collections.

## 5. Implement the transformation and the three checkpoints

Separate input loading, business transformations, profiling, and test assertions. Implement an `EadLgdJob` with an explicit input/configuration object and a result object containing the final table name, checkpoint metadata, query IDs, and artifact paths.

### CP1_ACCOUNT_EAD

Join ACCOUNTS to CUSTOMERS, validate and normalize types, apply a documented toy product-to-CCF mapping, and calculate:

```text
undrawn = max(credit_limit - drawn_balance, 0)
ead = drawn_balance + ccf * undrawn
```

Treat CCF values as invented assumptions, not regulatory constants. Preserve over-limit drawn balances rather than capping them at the limit. Specify allowed null handling; invalid required values should fail validation. Materialize the account-grain result through native `.cacheResult()`. Expect exactly one row per account.

### CP2_ACCOUNT_COLLATERAL

Join ACCOUNT_COLLATERAL to COLLATERAL_ASSETS. Normalize positive allocation weights within each collateral asset using a window expression so that allocated fractions sum to one. Calculate the asset's net realizable value after haircut and realization cost, discount it using a documented convention, allocate it to accounts, and aggregate to one row per account.

Use an asset-level discounting convention consistently across its allocations so that allocation conservation is testable. A fixed configurable toy collateral discount rate is acceptable. Do not allocate the entire asset value independently to every linked account.

Left-join these account-level collateral results to CP1, default missing collateral to zero, and materialize CP2 through native `.cacheResult()`. Preserve the account grain and all 300,000 accounts.

### CP3_ACCOUNT_LGD

Deduplicate EXPECTED_RECOVERIES deterministically by event ID and version/tie-break fields. Join to account attributes as needed, discount projected net unsecured cash flows, and aggregate recoveries to account grain before joining to CP2. Explicitly define these cash flows as excluding collateral realization proceeds so that the two recovery components are not double-counted.

Implement a documented toy calculation:

```text
recovery_capacity = max(collateral_pv + unsecured_recovery_pv, 0)
capped_recovery = min(ead, recovery_capacity)
lgd_amount = ead - capped_recovery
lgd = 0 when ead = 0, otherwise lgd_amount / ead
```

Materialize the account-level result through native `.cacheResult()`. Preserve one row per account. Keep money and rates in defined decimal types, cast/round discounted cash flows before large sums as needed, and document any approximation used for discount factors.

The orchestration should visibly contain three actual native cache calls, conceptually:

```scala
val cp1 = profiler.checkpoint("CP1_ACCOUNT_EAD") {
  accountEadDf.cacheResult()
}
val cp2 = profiler.checkpoint("CP2_ACCOUNT_COLLATERAL") {
  accountCollateralDf.cacheResult()
}
val cp3 = profiler.checkpoint("CP3_ACCOUNT_LGD") {
  accountLgdDf.cacheResult()
}
```

Implement the helper and the real dependencies between these DataFrames. Keep cache handles alive until all dependent actions finish, and clean them up safely afterward.

## 6. Final customer-level CTAS

After CP3, build a still-lazy customer-level result with customer aggregation, a join back to CUSTOMERS, and a meaningful window calculation. For example, derive the highest-EAD account per customer with `ROW_NUMBER` ordered by EAD descending and account ID ascending, then join it to customer totals. Consume the ranked result in the final output so the window is not decorative dead code.

Output exactly one row per customer and reporting date, including customer attributes, account count, drawn balance, undrawn amount, total EAD, capped recoveries, LGD amount, EAD-weighted LGD, synthetic PD, illustrative expected loss, highest-EAD account ID, and relevant quality flags.

Define customer LGD as `SUM(lgd_amount) / SUM(ead)`, with zero for zero total EAD, not an unweighted average of account LGDs. Define illustrative expected loss as customer PD multiplied by total LGD amount. State rounding and null conventions explicitly.

Execute an actual, separately tagged:

```sql
CREATE TRANSIENT TABLE <database>.<schema>.<run_specific_output> AS
SELECT ...;
```

An implementation may register the final lazy DataFrame as a temporary view and CTAS from that view in the same session. Do not materialize the whole final result first and then profile a trivial copy. Do not replace the requested CTAS with INSERTs, a view-only output, or an unverified assumption about what `saveAsTable` generated. Persist the actual executed CTAS SQL and its query ID.

## 7. Query IDs, profiles, and observable evidence

Use a dedicated, non-concurrently-shared Snowpark session for each integration-test execution. Apply query tags containing the job name, run ID, execution ID, and phase. Separate fixture setup, each checkpoint, final CTAS, validation, and observability queries. Set `USE_CACHED_RESULT = FALSE` for profiling executions; this is separate from explicit Snowpark checkpoint materialization.

Capture query IDs from supported APIs or carefully scoped Information Schema query history. Do not invent a Scala query-listener API. When using `QUERY_HISTORY_BY_SESSION`, include client-generated statements, use an adequate result limit and bounded time window, and filter by session, run/execution/phase tag, statement purpose, and target object. Use bounded polling when necessary.

Do not assume one native cache call always emits exactly one statement. Record all associated statements and identify the actual data-materialization query or queries, rather than an empty CREATE, ALTER SESSION, metadata query, validation COUNT, or the history lookup itself. Do not blindly use `LAST_QUERY_ID()` after intervening statements.

For the three checkpoint materializations and final CTAS, retrieve:

```sql
SELECT *
FROM TABLE(GET_QUERY_OPERATOR_STATS('<actual_query_id>'));
```

Record actual operator IDs/types, parent relationships, statistics, and execution-time details where available. Include query text, query tag, timing, execution status, and output row-count evidence. CTAS command-result row count is not the number of business rows written; distinguish them.

Write these local artifacts under `build/ead-lgd/<run-id>/`:

```text
fixture-manifest.json
load-results.json
query-manifest.json
profiles/<phase>-<query-id>.json
sql/executed-ctas.sql
sql/inspect-queries.sql
validation-summary.json
run-summary.md
```

The query manifest must map logical checkpoints to the actual materialized objects and data-query IDs. Print a concise console summary of CP1, CP2, CP3, FINAL_CTAS, their IDs, final output table, and artifact directory.

Document how to find each query by ID in Snowsight Query History and open Query Profile. Do not fabricate deep links or screenshots. Explain that upstream checkpoint work lives in its own query profiles.

Profile availability and permissions must be handled honestly. Document the warehouse MONITOR or OPERATE requirement for operator statistics and the limited history/profile retention. Default `REQUIRE_QUERY_PROFILES=true`: report profiling acceptance as failed or blocked when evidence cannot be obtained, with the underlying reason. An explicit false setting may allow correctness-only execution, but must not claim that profiling was verified. Exact node shapes and timings must never be fabricated or hard-coded.

## 8. A real, discoverable integration test

Create `EadLgdSnowflakeIntegrationTest` using ScalaTest `AnyFunSuite` and the compatible existing shared fixture, or implement a dedicated fixture. Configure actual Gradle test discovery. For a new project, a compatible ScalaTestPlus JUnit runner plus Gradle JUnit configuration is acceptable. Simply adding `AnyFunSuite` source is insufficient: demonstrate that the command discovers and executes the test.

Ordinary local tests must not contact Snowflake. Require `RUN_SNOWFLAKE_IT=true` for the live suite. Without the flag, explicitly skip/cancel the integration test before connecting. With the flag enabled, missing configuration, authentication failures, and load failures must fail clearly, not silently skip. Do not initialize a Snowflake session in a class constructor before evaluating the gate.

For the default live acceptance run, generate and load all five datasets, run the complete job with 100,000 customers, verify all three checkpoints and final CTAS, and export the profiling evidence.

Test at least these correctness properties: exactly 100,000 unique customer outputs; exactly one row per account at each account-grain checkpoint; no orphan keys; EAD nonnegative; LGD between zero and one; recovery capped at EAD; conservation of collateral allocation; and customer/account reconciliations for EAD, recoveries, and LGD amount. Assert explicit intermediate cardinality bounds to catch accidental many-to-many multiplication. Do not multiply raw recovery events by raw collateral links.

Build a small fixed cohort with independently hand-calculated expectations, including zero EAD, no recovery, full recovery, shared collateral, over-limit balances, and duplicate recovery versions. Do not derive expected values solely by calling the production transformation again.

Verify determinism with two independent job executions over the same immutable loaded inputs: create fresh checkpoints and distinct outputs, do not reuse the first execution's cached results. Compare business columns using bidirectional differences plus uniqueness/count checks; exclude run IDs and operational timestamps. Avoid collecting the full output locally; small reference samples and aggregate validation results are sufficient.

Provide offline unit tests for the generator, manifest, validation/configuration, and independent reference calculations. Keep offline test success distinct from real Snowflake integration and profile verification.

## 9. Configuration, lifecycle, and operating safety

Use environment variables or an ignored local configuration file for connection details and credentials. Support the repository's approved authentication mechanism; never hard-code credentials, print secret values, or require disabling TLS/OCSP. Document the exact mapping to the pinned driver's supported connection properties.

Support `RUN_SNOWFLAKE_IT`, `CUSTOMER_COUNT`, `DATA_SEED`, `REPORTING_DATE`, `KEEP_OBJECTS`, and `REQUIRE_QUERY_PROFILES`, plus the required Snowflake account/connection URL, user, role, warehouse, database, schema, and authentication inputs. Validate SQL identifiers and escape values safely.

Use an existing, explicitly supplied warehouse and approved sandbox database/schema. Do not create or resize warehouses, grant privileges automatically, or request ACCOUNTADMIN. Document required object privileges and use a configurable statement timeout. Prefer a modest existing warehouse for the default demonstration; do not copy the screenshots' 3X-Large setting.

Use unique run-specific object names and clean up only exact objects registered as created by this run. Default `KEEP_OBJECTS=false`; with true, retain the final transient output tables for inspection and provide explicit cleanup SQL. Native cached tables remain session-scoped: keeping final outputs does not preserve temporary checkpoints after session termination. Persistent checkpoint snapshots are optional, separately labeled extra operations, never substitutes for native caching.

Close sessions, file writers, streams, and cached resources reliably, including failure paths. Preserve original exceptions and partial diagnostic artifacts if cleanup also fails. Restore session settings when reusing approved infrastructure.

## 10. Deliverables and execution instructions

Deliver complete compiling source, necessary Gradle changes, an executable wrapper for a new project, offline tests, the live integration suite, example non-secret configuration, README instructions, and inspection/cleanup SQL. Organize code into focused components such as configuration/session factory, Parquet generator, input loader, business job, checkpoint profiler, and profile exporter; reuse existing equivalents where appropriate.

Document both Windows PowerShell and Unix commands, and IntelliJ project import, JDK selection, Gradle reload, test runner, environment variables, and any required test JVM options. Ensure these command shapes work with the implemented test discovery:

```powershell
# Configure the documented Snowflake connection/authentication variables first.
$env:RUN_SNOWFLAKE_IT = "true"
$env:CUSTOMER_COUNT = "100000"
$env:KEEP_OBJECTS = "true"
$env:REQUIRE_QUERY_PROFILES = "true"
.\gradlew.bat test --tests '*EadLgdSnowflakeIntegrationTest' --rerun-tasks --info
```

```bash
# Configure the documented Snowflake connection/authentication variables first.
RUN_SNOWFLAKE_IT=true CUSTOMER_COUNT=100000 KEEP_OBJECTS=true \
REQUIRE_QUERY_PROFILES=true \
./gradlew test --tests '*EadLgdSnowflakeIntegrationTest' --rerun-tasks --info
```

Explain how to run offline tests and a smaller smoke run. Never claim that a zero-tests-discovered build, disabled integration suite, or mocked run satisfies acceptance.

Implement and run the checks available in the environment. Do not execute a billable Snowflake run unless the integration gate is explicitly enabled and credentials are available. In the final report, distinguish implementation completed, compilation checked, offline tests executed, live integration executed, and profiles verified. When credentials, permissions, or connectivity are missing, state exactly what remains unverified and provide the exact next command; do not invent query IDs or passing results.

Acceptance requires five real uploaded-and-loaded Parquet datasets before transformation, 100,000 customers by default, three consumed native Snowpark checkpoints, a genuine final CTAS, discoverable test execution, verified numerical/cardinality behavior, and actual checkpoint/final query IDs with profile evidence when the live environment supports it.

## Official implementation references

Check these against the dependencies actually selected for the workspace:

```text
Snowpark Scala prerequisites:
https://docs.snowflake.com/en/developer-guide/snowpark/scala/prerequisites
Snowpark Scala DataFrames, native caching, views, and file upload:
https://docs.snowflake.com/en/developer-guide/snowpark/scala/working-with-dataframes
Snowflake COPY INTO:
https://docs.snowflake.com/en/sql-reference/sql/copy-into-table
Information Schema query history:
https://docs.snowflake.com/en/sql-reference/functions/query_history
Operator statistics:
https://docs.snowflake.com/en/sql-reference/functions/get_query_operator_stats
Snowsight Query History and Query Profile:
https://docs.snowflake.com/en/user-guide/ui-snowsight-activity
ScalaTest JUnit runner:
https://www.scalatest.org/user_guide/using_junit_runner
Gradle Scala plugin:
https://docs.gradle.org/current/userguide/scala_plugin.html
```
