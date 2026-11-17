"""Build table lineage and join details from a Snowflake CTAS query profile."""

import json
from collections import defaultdict


def variant(value):
    if value is None:
        return None
    return json.loads(value) if isinstance(value, str) else value


def object_name(value):
    parts = value.split(".")
    if len(parts) == 4 and parts[0].startswith("SY"):
        return ".".join(parts[1:])
    return value


def table_lineage(profile):
    operators = profile["operators"]
    writes = [operator for operator in operators if operator["OPERATOR_TYPE"] == "CreateTableAsSelect"]
    if not writes:
        raise ValueError("This query profile has no CreateTableAsSelect operator. Enter the final CTAS query ID.")
    write = writes[0]
    step = write["STEP_ID"]
    target = object_name(variant(write["OPERATOR_ATTRIBUTES"])["table_name"])
    scans = defaultdict(lambda: {"count": 0, "columns": set()})
    joins = []
    for operator in operators:
        if operator["STEP_ID"] != step:
            continue
        kind = operator["OPERATOR_TYPE"]
        attributes = variant(operator["OPERATOR_ATTRIBUTES"]) or {}
        if kind == "TableScan" and attributes.get("table_name"):
            name = object_name(attributes["table_name"])
            scans[name]["count"] += 1
            scans[name]["columns"].update(attributes.get("columns", []))
        elif kind in ("Join", "CartesianJoin"):
            stats = variant(operator["OPERATOR_STATISTICS"]) or {}
            joins.append({
                "operator": operator["OPERATOR_ID"],
                "type": attributes.get("join_type", kind),
                "condition": attributes.get("equality_join_condition")
                or attributes.get("additional_join_condition") or "—",
                "input_rows": stats.get("input_rows"),
                "output_rows": stats.get("output_rows"),
            })
    if not scans:
        raise ValueError("The CTAS step has no named TableScan sources.")
    statistics = variant(write["OPERATOR_STATISTICS"]) or {}
    return {
        "query_id": profile["queryId"],
        "step": step,
        "target": target,
        "rows_inserted": (statistics.get("dml") or {}).get("number_of_rows_inserted"),
        "sources": [
            {"name": name, "scans": details["count"], "columns": sorted(details["columns"])}
            for name, details in sorted(scans.items())
        ],
        "joins": sorted(joins, key=lambda join: join["operator"]),
    }
