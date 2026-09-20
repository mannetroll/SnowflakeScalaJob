-- Each live run writes actual IDs into its sql/inspect-queries.sql.
-- Replace this placeholder with a printed data-query ID, not an empty cache CREATE ID.
SET DEMO_QUERY_ID = '<actual-query-id>';
SELECT * FROM TABLE(GET_QUERY_OPERATOR_STATS($DEMO_QUERY_ID));
SELECT QUERY_ID, QUERY_TEXT, QUERY_TAG, EXECUTION_STATUS, START_TIME, END_TIME,
       TOTAL_ELAPSED_TIME, COMPILATION_TIME, EXECUTION_TIME, ROWS_INSERTED,
       ROWS_PRODUCED, BYTES_SCANNED
FROM TABLE(INFORMATION_SCHEMA.QUERY_HISTORY(
  END_TIME_RANGE_START => DATEADD('day', -7, CURRENT_TIMESTAMP()),
  RESULT_LIMIT => 10000, INCLUDE_CLIENT_GENERATED_STATEMENT => TRUE))
WHERE QUERY_ID = $DEMO_QUERY_ID;
-- For removal, use the run's exact-object cleanup.sql. Never drop by wildcard.
