# Verification record — 2026-09-20

Implementation is present and compiles. Local verification was performed on macOS
arm64 using the Gradle 8.14.3 wrapper and the JDK 17 toolchain. Live Snowflake
acceptance has **not** been executed or certified.

| Check | Observed result |
| --- | --- |
| Main and test Scala compilation | Passed against the pinned real Snowpark/JDBC dependencies |
| `./gradlew test` | 15 discovered: 14 passed, 1 live test explicitly skipped; 0 failures/errors |
| `./gradlew offlineTest` | 14 passed, 0 skipped, 0 failures/errors; no Snowflake connection |
| `./gradlew test --tests '*EadLgdSnowflakeIntegrationTest' --rerun-tasks` | Exactly 1 discovered and skipped because the live gate was unset |
| `./gradlew generateFixtures` | Five real Parquet files generated at the full 100,000-customer scale |
| `./gradlew installDist` | Standalone application distribution built |
| Installed launcher, `CUSTOMER_COUNT=10 ... generate` | Successfully wrote all five smoke fixtures without Snowflake |
| Native Windows host execution | Not performed; Windows path conventions tested locally and checked against JDBC 3.24.2 source |
| Live uploads/COPY, Snowpark numerical validation, CTAS, server determinism | Not executed |
| Actual Snowflake query IDs and operator profiles | Not obtained or verified |

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
These tests do not simulate a Snowflake integration run.

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
`fixture-manifest.json`. These local files have **not** been uploaded. Build
artifacts are intentionally ignored and can be removed by `clean`; this record
preserves their observed counts. Live runs regenerate fresh isolated fixtures.

## Remaining live acceptance

`RUN_SNOWFLAKE_IT` was unset. The existing local configuration contains account,
user and private-key-path entries, but lacks role, warehouse, database and schema.
Credentials were not exercised. The prompt explicitly requires live opt-in, so
no billable Snowflake query was submitted.

Supply `SNOWFLAKE_ROLE`, `SNOWFLAKE_WAREHOUSE`, `SNOWFLAKE_DATABASE` and
`SNOWFLAKE_SCHEMA` for an approved sandbox, plus any required key passphrase.
Then run:

```bash
RUN_SNOWFLAKE_IT=true CUSTOMER_COUNT=100000 KEEP_OBJECTS=true \
REQUIRE_QUERY_PROFILES=true \
./gradlew test --tests '*EadLgdSnowflakeIntegrationTest' --rerun-tasks --info
```

The [README](README.md) includes full Unix/PowerShell configuration and IntelliJ
instructions. That live test must still establish all five real loads, CP1/CP2/CP3
cardinalities and calculations, allocation conservation, customer reconciliation,
two independent outputs, exact data-query IDs and real operator statistics.
Profile evidence may require MONITOR or OPERATE on the selected warehouse. A
skipped test or explicit correctness-only mode is not profiling acceptance.
