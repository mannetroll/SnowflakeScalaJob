-- Run the whole script in one Snowflake session (Snowsight, SnowSQL or a connector).
-- Change this input. A copied "Query - <UUID>" heading is also accepted.
SET QP_QUERY_ID = '01c73375-0005-833e-0001-91ae00232802';

-- This project's approved context; adjust these four statements for another account.
USE ROLE SYSADMIN;
USE WAREHOUSE SCALING_BENCH_WH;
USE DATABASE RISKDEMO;
USE SCHEMA EAD_LGD_DEMO;

-- The original query is NOT executed. Only its existing operator statistics are read.
-- Requires an available profile and MONITOR or OPERATE on its warehouse, plus
-- permission to create temporary tables in the current schema and unload to @~.
-- Produces exactly named, uncompressed CSV files under a query-specific user-stage
-- directory. Repeating an export for this query overwrites these same two files.
-- GET is a client command: run the returned download statements in SnowSQL or a
-- supported connector. Snowsight worksheets cannot write files to your computer.
EXECUTE IMMEDIATE $$
DECLARE
  query_id VARCHAR DEFAULT LOWER(REGEXP_REPLACE(TRIM($QP_QUERY_ID),
    '^Query[[:space:]]*-[[:space:]]*', '', 1, 0, 'i'));
  suffix VARCHAR DEFAULT REPLACE(UUID_STRING(), '-', '_');
  profile_table VARCHAR DEFAULT 'QP_CSV_PROFILE_' || suffix;
  nodes_table VARCHAR DEFAULT 'QP_CSV_NODES_' || suffix;
  relationships_table VARCHAR DEFAULT 'QP_CSV_RELATIONSHIPS_' || suffix;
  stage_path VARCHAR;
  copy_sql VARCHAR;
  csv_options VARCHAR DEFAULT
    ' FILE_FORMAT = (TYPE = CSV COMPRESSION = NONE FIELD_OPTIONALLY_ENCLOSED_BY = ''"''
       ESCAPE_UNENCLOSED_FIELD = NONE EMPTY_FIELD_AS_NULL = FALSE NULL_IF = ())
      SINGLE = TRUE OVERWRITE = TRUE';
  node_count NUMBER;
  relationship_count NUMBER;
  invalid_count NUMBER;
  invalid_id EXCEPTION (-20001, 'QP_QUERY_ID must be a Snowflake query UUID, optionally prefixed by Query - ');
  empty_profile EXCEPTION (-20002, 'No operator statistics returned; use an eligible completed query from the past 14 days.');
  invalid_graph EXCEPTION (-20003, 'Operator evidence contains duplicate/missing IDs, mismatched query IDs or missing parents.');
BEGIN
  IF (query_id IS NULL OR NOT REGEXP_LIKE(query_id,
      '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')) THEN
    RAISE invalid_id;
  END IF;
  stage_path := '@~/query-profile-csv/' || query_id || '/';

  -- Capture once, so both CSV files describe the same profile snapshot.
  -- RAW_OPERATOR also preserves future columns added by Snowflake.
  CREATE TEMPORARY TABLE IDENTIFIER(:profile_table) AS
  SELECT q.*, OBJECT_CONSTRUCT_KEEP_NULL(q.*) AS RAW_OPERATOR
  FROM TABLE(GET_QUERY_OPERATOR_STATS(:query_id)) q;

  SELECT COUNT(*) INTO :node_count FROM IDENTIFIER(:profile_table);
  IF (node_count = 0) THEN
    RAISE empty_profile;
  END IF;
  SELECT COUNT(*) INTO :invalid_count FROM IDENTIFIER(:profile_table)
  WHERE QUERY_ID IS NULL OR LOWER(QUERY_ID) <> :query_id
     OR STEP_ID IS NULL OR OPERATOR_ID IS NULL;
  IF (invalid_count > 0) THEN
    RAISE invalid_graph;
  END IF;

  CREATE TEMPORARY TABLE IDENTIFIER(:nodes_table) AS
  SELECT
    LOWER(QUERY_ID) || ':s' || STEP_ID || ':n' || OPERATOR_ID AS NODE_ID,
    QUERY_ID,
    STEP_ID,
    OPERATOR_ID,
    OPERATOR_TYPE,
    OPERATOR_ATTRIBUTES:table_name::VARCHAR AS TABLE_NAME,
    OPERATOR_TYPE || ' [' || OPERATOR_ID || '] | Step ' || STEP_ID ||
      COALESCE(' | ' || OPERATOR_ATTRIBUTES:table_name::VARCHAR, '') AS LABEL,
    COALESCE(ARRAY_SIZE(PARENT_OPERATORS), 0) = 0 AS IS_ROOT,
    OPERATOR_STATISTICS:input_rows::NUMBER AS INPUT_ROWS,
    OPERATOR_STATISTICS:output_rows::NUMBER AS OUTPUT_ROWS,
    OPERATOR_STATISTICS:dml:number_of_rows_inserted::NUMBER AS ROWS_INSERTED,
    EXECUTION_TIME_BREAKDOWN:overall_percentage::DOUBLE AS EXECUTION_FRACTION_OF_STEP,
    TO_JSON(PARENT_OPERATORS) AS PARENT_OPERATORS_JSON,
    TO_JSON(OPERATOR_ATTRIBUTES) AS OPERATOR_ATTRIBUTES_JSON,
    TO_JSON(OPERATOR_STATISTICS) AS OPERATOR_STATISTICS_JSON,
    TO_JSON(EXECUTION_TIME_BREAKDOWN) AS EXECUTION_TIME_BREAKDOWN_JSON,
    TO_JSON(RAW_OPERATOR) AS RAW_OPERATOR_JSON
  FROM IDENTIFIER(:profile_table);

  SELECT COUNT(*) - COUNT(DISTINCT NODE_ID) INTO :invalid_count FROM IDENTIFIER(:nodes_table);
  IF (invalid_count > 0) THEN
    RAISE invalid_graph;
  END IF;

  -- Snowflake's PARENT_OPERATORS are the consuming operators. Edges follow data
  -- flow: SOURCE = this child/input operator, TARGET = each parent/consumer.
  -- FLATTEN emits one relationship for every parent, including shared branches.
  -- Null/empty parent arrays emit zero edges; no fake edges connect query steps.
  CREATE TEMPORARY TABLE IDENTIFIER(:relationships_table) AS
  SELECT
    LOWER(q.QUERY_ID) || ':s' || q.STEP_ID || ':n' || q.OPERATOR_ID || ':p' || p.INDEX AS RELATIONSHIP_ID,
    LOWER(q.QUERY_ID) || ':s' || q.STEP_ID || ':n' || q.OPERATOR_ID AS SOURCE_NODE_ID,
    LOWER(q.QUERY_ID) || ':s' || q.STEP_ID || ':n' || p.VALUE::NUMBER AS TARGET_NODE_ID,
    q.QUERY_ID,
    q.STEP_ID,
    q.OPERATOR_ID AS SOURCE_OPERATOR_ID,
    p.VALUE::NUMBER AS TARGET_OPERATOR_ID,
    p.INDEX AS PARENT_INDEX,
    'DATA_FLOW' AS RELATIONSHIP_TYPE,
    q.OPERATOR_STATISTICS:output_rows::NUMBER AS SOURCE_OUTPUT_ROWS
  FROM IDENTIFIER(:profile_table) q,
       LATERAL FLATTEN(INPUT => q.PARENT_OPERATORS) p;

  SELECT COUNT(*) INTO :relationship_count FROM IDENTIFIER(:relationships_table);
  SELECT COUNT(*) INTO :invalid_count
  FROM IDENTIFIER(:relationships_table) r
  LEFT JOIN IDENTIFIER(:nodes_table) n ON r.TARGET_NODE_ID = n.NODE_ID
  WHERE n.NODE_ID IS NULL;
  IF (invalid_count > 0) THEN
    RAISE invalid_graph;
  END IF;

  copy_sql := 'COPY INTO ' || stage_path || 'nodes.csv FROM
    (SELECT * FROM ' || nodes_table || ' ORDER BY STEP_ID, OPERATOR_ID)' || csv_options || ' HEADER = TRUE';
  EXECUTE IMMEDIATE :copy_sql;

  -- Write the relationship header explicitly so even a profile with NO edges
  -- gets a valid header-only relationships.csv. There are no sentinel data rows.
  copy_sql := 'COPY INTO ' || stage_path || 'relationships.csv FROM (
    WITH csv_rows AS (
      SELECT 0 AS SORT_HEADER, -1 AS SORT_STEP, -1 AS SORT_OPERATOR, -1 AS SORT_PARENT,
        ''RELATIONSHIP_ID'' AS RELATIONSHIP_ID,
        ''SOURCE_NODE_ID'' AS SOURCE_NODE_ID, ''TARGET_NODE_ID'' AS TARGET_NODE_ID,
        ''QUERY_ID'' AS QUERY_ID, ''STEP_ID'' AS STEP_ID,
        ''SOURCE_OPERATOR_ID'' AS SOURCE_OPERATOR_ID, ''TARGET_OPERATOR_ID'' AS TARGET_OPERATOR_ID,
        ''PARENT_INDEX'' AS PARENT_INDEX, ''RELATIONSHIP_TYPE'' AS RELATIONSHIP_TYPE,
        ''SOURCE_OUTPUT_ROWS'' AS SOURCE_OUTPUT_ROWS
      UNION ALL
      SELECT 1, STEP_ID, SOURCE_OPERATOR_ID, PARENT_INDEX,
        RELATIONSHIP_ID, SOURCE_NODE_ID, TARGET_NODE_ID, QUERY_ID,
        TO_VARCHAR(STEP_ID), TO_VARCHAR(SOURCE_OPERATOR_ID), TO_VARCHAR(TARGET_OPERATOR_ID),
        TO_VARCHAR(PARENT_INDEX), RELATIONSHIP_TYPE, TO_VARCHAR(SOURCE_OUTPUT_ROWS)
      FROM ' || relationships_table || '
    )
    SELECT RELATIONSHIP_ID, SOURCE_NODE_ID, TARGET_NODE_ID, QUERY_ID, STEP_ID,
      SOURCE_OPERATOR_ID, TARGET_OPERATOR_ID, PARENT_INDEX, RELATIONSHIP_TYPE, SOURCE_OUTPUT_ROWS
    FROM csv_rows ORDER BY SORT_HEADER, SORT_STEP, SORT_OPERATOR, SORT_PARENT
  )' || csv_options || ' HEADER = FALSE';
  EXECUTE IMMEDIATE :copy_sql;

  DROP TABLE IDENTIFIER(:relationships_table);
  DROP TABLE IDENTIFIER(:nodes_table);
  DROP TABLE IDENTIFIER(:profile_table);

  -- Edit the local destination in these returned commands if needed.
  -- Windows example: file://C:/temp/query-profile-csv/
  RETURN OBJECT_CONSTRUCT(
    'query_id', query_id, 'node_count', node_count, 'relationship_count', relationship_count,
    'nodes_file', stage_path || 'nodes.csv',
    'relationships_file', stage_path || 'relationships.csv',
    'download_nodes_sql', 'GET ' || stage_path || 'nodes.csv file:///tmp/query-profile-csv/;',
    'download_relationships_sql', 'GET ' || stage_path || 'relationships.csv file:///tmp/query-profile-csv/;'
  );
END;
$$;
