# Query profile CSV export

[export-query-profile-csv.sql](export-query-profile-csv.sql) takes a Snowflake
query ID and exports its existing Query Profile as two CSV files:

| File | Contents |
| --- | --- |
| `nodes.csv` | Complete operator information: IDs, full names, labels, row counts, parent IDs, attributes, statistics and timing details. JSON columns preserve the full source information. |
| `relationships.csv` | Connections between operators, with source and target node IDs. Each edge follows data flow from an input operator to its consuming parent. |

Node IDs include the query ID, step and operator number. Both execution steps of
a CTAS are included, and repeated operator numbers remain distinct.

## Role access

The configured `SYSADMIN` setup has been verified. To use another existing role,
an authorized administrator can grant:

```sql
GRANT USAGE, MONITOR ON WAREHOUSE SCALING_BENCH_WH
  TO ROLE PROFILE_EXPORT_ROLE;

GRANT USAGE ON DATABASE RISKDEMO
  TO ROLE PROFILE_EXPORT_ROLE;

GRANT USAGE ON SCHEMA RISKDEMO.EAD_LGD_DEMO
  TO ROLE PROFILE_EXPORT_ROLE;
```

Replace `PROFILE_EXPORT_ROLE` with the role assigned to your user. `MONITOR`
allows reading the profile of queries executed on that warehouse; `OPERATE`
also satisfies that requirement. `USAGE` on the export warehouse allows the
CSV export to run. If the original query used a different warehouse, grant
`MONITOR` on that warehouse as well.

See Snowflake's [operator statistics privileges](https://docs.snowflake.com/en/sql-reference/functions/get_query_operator_stats)
and [access control privileges](https://docs.snowflake.com/en/user-guide/security-access-control-privileges).

## Run the export

1. Open [export-query-profile-csv.sql](export-query-profile-csv.sql).
2. Set the query ID at the top:

   ```sql
   SET QP_QUERY_ID = '01c73375-0005-833e-0001-91ae00232802';
   ```

3. Set the context to your assigned role, warehouse, database and schema. For the
   dedicated role above:

   ```sql
   USE ROLE PROFILE_EXPORT_ROLE;
   USE WAREHOUSE SCALING_BENCH_WH;
   USE DATABASE RISKDEMO;
   USE SCHEMA EAD_LGD_DEMO;
   ```

4. Execute the whole script in one session using Snowsight, SnowSQL or a connector.

The script reads the existing profile without rerunning the original query. It
creates these uncompressed files on your Snowflake user stage:

```text
@~/query-profile-csv/<query-id>/nodes.csv
@~/query-profile-csv/<query-id>/relationships.csv
```

Repeating the export for the same query replaces its two files. The final result
includes the node and relationship counts, file paths and download commands.
The demonstrated query produces **13 nodes and 11 relationships**.

## Download the files

Execute the returned `GET` statements in SnowSQL or a connector supporting file
downloads. For the example query:

```sql
GET @~/query-profile-csv/01c73375-0005-833e-0001-91ae00232802/nodes.csv
  file:///tmp/query-profile-csv/;

GET @~/query-profile-csv/01c73375-0005-833e-0001-91ae00232802/relationships.csv
  file:///tmp/query-profile-csv/;
```

Change the destination directory as needed. For Windows, use a destination such
as `file://C:/temp/query-profile-csv/`. Use Snowsight for the export and a client
supporting `GET` for the local download.
