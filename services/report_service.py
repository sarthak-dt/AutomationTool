"""Report generation: CSV, JSON, XLSX, signal graphs and the downloadable ZIP.

Reports never contain credentials: settings/context are passed through
``redact_mapping`` and S3 access keys are never part of a ``JobResult``.
Graphs are interactive HTML files built with plotly (optional dependency; if it
is missing the ZIP simply omits graphs and says so in ``README.txt``).
"""
from __future__ import annotations

import io
import json
import zipfile
from datetime import datetime
from typing import Any

import numpy as np
import pandas as pd

from types import SimpleNamespace

from services import timeutil
from services.channel_gate import channel_gate
from services.comparison_engine import ROW_COLUMNS
from services.file_service import sanitize_filename
from services.logging_utils import redact_mapping
from services.models import EngineState, Status

MAX_GRAPHS_IN_ZIP = 30


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
def df_records(df: pd.DataFrame) -> list[dict[str, Any]]:
    """DataFrame -> JSON-safe list of dicts (NaN -> null)."""
    if df is None or df.empty:
        return []
    return json.loads(df.to_json(orient="records", double_precision=15))


def _csv_bytes(df: pd.DataFrame) -> bytes:
    return df.to_csv(index=False).encode("utf-8")


OUTPUT_COLUMNS = ["file", "can_id", "channel", "signal", "timestamp", "value", "unit", "message", "is_discrete"]


def python_output_frame(job: Any) -> pd.DataFrame:
    parts = []
    for fname, df in job.python_data.items():
        if df is not None and not df.empty:
            parts.append(df.assign(file=fname)[OUTPUT_COLUMNS])
    if not parts:
        return pd.DataFrame(columns=OUTPUT_COLUMNS)
    return pd.concat(parts, ignore_index=True)


def java_output_frame(job: Any) -> pd.DataFrame:
    parts = []
    for fname, df in job.java_data.items():
        if df is not None and not df.empty:
            parts.append(df.assign(file=fname)[OUTPUT_COLUMNS])
    if not parts:
        return pd.DataFrame(columns=OUTPUT_COLUMNS)
    return pd.concat(parts, ignore_index=True)


def comparison_available(job: Any) -> bool:
    return bool(job is not None and job.comparison_performed)


# ---------------------------------------------------------------------------
# individual reports
# ---------------------------------------------------------------------------
EXPORT_COLUMNS = [
    "Index", "File Name", "Date/Time", "Timestamp", "CAN ID", "Signal Name",
    "DBC Name",
    "Python Channel", "Java Channel", "Python Decoded Value", "Java Decoded Value",
    "Absolute Error", "Status", "Message",
]


def _comparison_message(row: pd.Series) -> str:
    raw_note = row.get("note")
    note = "" if pd.isna(raw_note) else str(raw_note).strip()
    status = str(row.get("status") or "")
    if note:
        return note
    if status == Status.MISMATCH:
        p_value, j_value = row.get("python_value"), row.get("java_value")
        if pd.notna(p_value) and pd.notna(j_value):
            return f"Decoded values differ: Python={p_value}, Java={j_value}; check the decoder outputs."
        return "Channel or decoded-output mismatch; see the Python and Java channel/value columns."
    if status == Status.NOT_COMPARABLE:
        return "The comparison could not be verified; channel metadata or compatible values are unavailable."
    if status == Status.MISSING_IN_PYTHON:
        return "Signal/sample is missing from the Python decoder output."
    if status == Status.MISSING_IN_JAVA:
        return "Signal/sample is missing from the Java decoder output."
    if status == Status.ERROR:
        return "A decoder or comparison error occurred. Check the job report for details."
    return ""


def export_frame(rows: pd.DataFrame | None, starts: dict[str, Any] | None = None) -> pd.DataFrame:
    """Comparison rows in the reporting layout: index, datetime, timestamp, CAN ID, channel, signal,
    Python value/unit, Java value/unit, abs error, status (+ details)."""
    if rows is None or len(rows) == 0:
        return pd.DataFrame(columns=EXPORT_COLUMNS)
    starts = starts or {}
    r = rows.reset_index(drop=True)
    for col, default in (("java_channel", 0), ("channel", 0), ("can_id", "")):
        if col not in r.columns:
            r[col] = default
    dt = pd.Series("", index=r.index, dtype=object)
    for name, g in r.groupby("file"):
        if name in starts:
            dt.loc[g.index] = timeutil.datetimes(starts[name], g["timestamp"])
    out = pd.DataFrame(
        {
            "Index": np.arange(1, len(r) + 1),
            "File Name": r["file"],
            "Date/Time": dt,
            "Timestamp": r["timestamp"],
            "CAN ID": r["can_id"],
            "Signal Name": r["signal"],
            "DBC Name": r["dbc_name"] if "dbc_name" in r.columns else "",
            "Python Channel": r["channel"].where(r["status"] != Status.MISSING_IN_PYTHON, 0),
            "Java Channel": r["java_channel"].where(r["status"] != Status.MISSING_IN_JAVA, 0),
            "Python Decoded Value": r["python_value"],
            "Java Decoded Value": r["java_value"],
            "Absolute Error": r["abs_diff"],
            "Status": r["status"],
            "Message": r.apply(_comparison_message, axis=1),
        }
    )
    return out[EXPORT_COLUMNS]


def comparison_csv(job: Any) -> bytes:
    return _csv_bytes(export_frame(job.rows, timeutil.file_starts(job)))


def signal_summary_csv(job: Any) -> bytes:
    return _csv_bytes(job.signal_summary)


def python_output_csv(job: Any) -> bytes:
    # is_discrete is an internal helper flag (the comparison engine auto-detects it); not part of the exported sample file.
    df = python_output_frame(job).drop(columns=["is_discrete"], errors="ignore")
    starts = timeutil.file_starts(job)
    if starts and len(df):
        dt = pd.Series("", index=df.index, dtype=object)
        for name, g in df.groupby("file"):
            if name in starts:
                dt.loc[g.index] = timeutil.datetimes(starts[name], g["timestamp"])
        df.insert(df.columns.get_loc("timestamp"), "datetime", dt)  # readable time before 'timestamp' (seconds)
    return _csv_bytes(df)


def java_output_csv(job: Any) -> bytes | None:
    """Only real Java output is ever exported. Returns None when there is none."""
    df = java_output_frame(job)
    return _csv_bytes(df) if not df.empty else None


def job_summary_dict(job: Any) -> dict[str, Any]:
    java_label = job.java_state
    d = {
        "job_id": job.job_id,
        "started_at": job.started_at,
        "finished_at": job.finished_at,
        "duration_s": job.duration_s,
        "java_engine_state": java_label,
        "java_engine_note": job.java_note,
        "comparison_performed": bool(job.comparison_performed),
        "disclaimer": (
            "Java engine was MOCK / UNAVAILABLE: no comparison was performed and no Java values "
            "exist in this report."
            if job.java_state == EngineState.UNAVAILABLE
            else (
                "Java values come from an uploaded Java output file, not from a live Java API call."
                if "imported from" in (job.java_note or "")
                else "Java values come from the configured Java decoder API."
            )
        ),
        "channel_gate": channel_gate(SimpleNamespace(**(job.settings or {}))).as_dict(),
        "settings": job.settings,
        "s3": job.s3_context,
        "dbc_files": job.dbc_names,
        "dbc_conflicts": job.conflicts,
        "summary": job.summary,
        "files": [
            {
                "name": f.name,
                "source": f.source,
                "size_bytes": f.size,
                "python_state": f.python_state,
                "java_state": f.java_state,
                "python_error": f.python_error,
                "java_error": f.java_error,
                "comparison_performed": f.comparison_performed,
                "comparison_reason": f.comparison_reason,
                "warnings": f.warnings,
                "python_stats": f.python_stats,
                "java_stats": f.java_stats,
                "seconds": f.seconds,
                "error": f.error,
            }
            for f in job.files
        ],
    }
    return redact_mapping(d)


def result_json(job: Any, include_rows: bool = True) -> bytes:
    payload = job_summary_dict(job)
    payload["signal_summary"] = df_records(job.signal_summary)
    payload["python_signals"] = df_records(job.python_signal_table)
    if include_rows:
        payload["rows"] = df_records(job.rows)
    return json.dumps(redact_mapping(payload), indent=2, allow_nan=False, default=str).encode("utf-8")


def excel_bytes(job: Any) -> bytes:
    """Workbook with Summary, Signals, Comparison, Python signals sheets."""
    buf = io.BytesIO()
    summary = job_summary_dict(job)
    flat = [
        ("job_id", summary["job_id"]),
        ("started_at", summary["started_at"]),
        ("duration_s", summary["duration_s"]),
        ("java_engine_state", summary["java_engine_state"]),
        ("comparison_performed", summary["comparison_performed"]),
        ("disclaimer", summary["disclaimer"]),
        ("dbc_files", ", ".join(summary["dbc_files"])),
    ] + [(f"summary.{k}", v) for k, v in (summary["summary"] or {}).items()]
    with pd.ExcelWriter(buf, engine="openpyxl") as xw:
        pd.DataFrame(flat, columns=["key", "value"]).to_excel(xw, sheet_name="Summary", index=False)
        job.signal_summary.to_excel(xw, sheet_name="Signals", index=False)
        rows = export_frame(job.rows, timeutil.file_starts(job))
        # Excel row limit protection
        comparison = rows.head(1_000_000).astype({"python_value": object, "java_value": object})
        comparison.to_excel(xw, sheet_name="Comparison", index=False)
        _style_comparison_sheet(xw.sheets["Comparison"], comparison)
        job.python_signal_table.to_excel(xw, sheet_name="Python signals", index=False)
    return buf.getvalue()


def _style_comparison_sheet(worksheet: Any, rows: pd.DataFrame) -> None:
    """Highlight value mismatches and known/unknown Python/Java channel differences in Excel."""
    if rows.empty:
        return

    from openpyxl.formatting.rule import FormulaRule
    from openpyxl.styles import Font, PatternFill
    from openpyxl.utils import get_column_letter

    columns = {str(name): index + 1 for index, name in enumerate(rows.columns)}
    status_col = get_column_letter(columns["Status"])
    python_channel_col = get_column_letter(columns["Python Channel"])
    java_channel_col = get_column_letter(columns["Java Channel"])
    last_col = get_column_letter(len(rows.columns))
    last_row = len(rows) + 1

    mismatch_fill = PatternFill(fill_type="solid", fgColor="FECACA")
    channel_fill = PatternFill(fill_type="solid", fgColor="FCA5A5")
    status_fill = PatternFill(fill_type="solid", fgColor="DC2626")
    status_font = Font(color="FFFFFF", bold=True)

    data_range = f"A2:{last_col}{last_row}"
    mismatch_formula = f'${status_col}2="MISMATCH"'
    channel_formula = (
        f"AND(${python_channel_col}2<>${java_channel_col}2,"
        f"OR(${python_channel_col}2>0,${java_channel_col}2>0))"
    )
    channel_range = f"{python_channel_col}2:{java_channel_col}{last_row}"
    worksheet.conditional_formatting.add(
        channel_range,
        FormulaRule(formula=[channel_formula], fill=channel_fill),
    )
    worksheet.conditional_formatting.add(
        f"{status_col}2:{status_col}{last_row}",
        FormulaRule(formula=[f"OR({mismatch_formula},{channel_formula})"], fill=status_fill, font=status_font),
    )
    worksheet.conditional_formatting.add(
        data_range,
        FormulaRule(formula=[f"OR({mismatch_formula},{channel_formula})"], fill=mismatch_fill),
    )
    worksheet.freeze_panes = "A2"
    worksheet.auto_filter.ref = f"A1:{last_col}{last_row}"


# ---------------------------------------------------------------------------
# graphs
# ---------------------------------------------------------------------------
def plotly_available() -> bool:
    try:
        import plotly  # noqa: F401

        return True
    except Exception:
        return False


def build_signal_figure(
    job: Any, file: str, signal: str, max_points: int = 20000
):
    """Plotly figure: Python trace, Java trace (only if real), mismatch markers."""
    import plotly.graph_objects as go

    fig = go.Figure()
    py = job.python_data.get(file)
    if py is not None and not py.empty:
        g = py[py["signal"] == signal].sort_values("timestamp")
        g = _decimate(g, max_points)
        fig.add_trace(
            go.Scatter(
                x=g["timestamp"], y=g["value"], mode="lines+markers" if len(g) < 400 else "lines",
                name="Python reference", line=dict(color="#2563eb", width=2),
            )
        )
    jv = job.java_data.get(file)
    if jv is not None and not jv.empty:
        g = jv[jv["signal"] == signal].sort_values("timestamp")
        g = _decimate(g, max_points)
        fig.add_trace(
            go.Scatter(
                x=g["timestamp"], y=g["value"], mode="lines", name="Java decoder",
                line=dict(color="#12b76a", width=2, dash="dash"),
            )
        )
    if job.rows is not None and len(job.rows):
        r = job.rows[(job.rows["file"] == file) & (job.rows["signal"] == signal) & (job.rows["status"] == Status.MISMATCH)]
        if len(r):
            r = _decimate(r.sort_values("timestamp"), 2000)
            fig.add_trace(
                go.Scatter(
                    x=r["timestamp"], y=pd.to_numeric(r["python_value"], errors="coerce"), mode="markers",
                    name="Mismatch", marker=dict(color="#b42318", size=8, symbol="x"),
                )
            )
    unit = ""
    if py is not None and not py.empty:
        u = py.loc[py["signal"] == signal, "unit"]
        unit = str(u.iloc[0]) if len(u) else ""
    fig.update_layout(
        title=f"{signal} - {file}",
        template="plotly_white",
        xaxis_title="Time [s]",
        yaxis_title=f"Value{f' [{unit}]' if unit else ''}",
        legend=dict(orientation="h", y=1.1),
        margin=dict(l=40, r=20, t=60, b=40),
        height=420,
    )
    return fig


def _decimate(df: pd.DataFrame, max_points: int) -> pd.DataFrame:
    if len(df) <= max_points:
        return df
    idx = np.linspace(0, len(df) - 1, max_points).astype(int)
    return df.iloc[idx]


def graph_html_files(job: Any, limit: int = MAX_GRAPHS_IN_ZIP) -> dict[str, bytes]:
    """Interactive HTML graphs (mismatching signals first). Empty if plotly is missing."""
    if not plotly_available():
        return {}
    pairs: list[tuple[str, str]] = []
    if job.signal_summary is not None and len(job.signal_summary):
        order = {s: i for i, s in enumerate(Status.PRIORITY)}
        ss = job.signal_summary.assign(_o=job.signal_summary["status"].map(order)).sort_values(["_o", "file", "signal"])
        pairs = [(r.file, r.signal) for r in ss.itertuples() if r.signal != "(file)"]
    if not pairs:
        for fname, df in job.python_data.items():
            if df is not None and not df.empty:
                pairs.extend((fname, s) for s in sorted(df["signal"].unique()))
    out: dict[str, bytes] = {}
    for fname, sig in pairs[:limit]:
        try:
            fig = build_signal_figure(job, fname, sig)
            html = fig.to_html(include_plotlyjs="cdn", full_html=True)
            out[f"graphs/{sanitize_filename(fname)}__{sanitize_filename(sig)}.html"] = html.encode("utf-8")
        except Exception:
            continue
    return out


# ---------------------------------------------------------------------------
# ZIP
# ---------------------------------------------------------------------------
def build_report_zip(job: Any, include_graphs: bool = True) -> bytes:
    """Complete report bundle as ZIP bytes."""
    buf = io.BytesIO()
    graphs = graph_html_files(job) if include_graphs else {}
    java_csv = java_output_csv(job)
    readme_lines = [
        f"Automotive Decoder Comparator - report for {job.job_id}",
        f"Generated: {datetime.now().isoformat(timespec='seconds')}",
        "",
        f"Java engine state: {job.java_state}",
        f"Comparison performed: {job.comparison_performed}",
        "",
        "Files:",
        "  job_summary.json      job metadata, settings (secrets redacted), per-file status",
        "  result.json           summary + signal summary + all comparison rows",
        "  signal_summary.csv    one row per file/signal with rolled-up status",
        "  python_output.csv     decoded samples from the Python reference engine (real data)",
        "  python_signals.csv    per-signal statistics of the Python output",
    ]
    if job.comparison_performed:
        readme_lines.append("  comparison.csv        sample-level comparison rows")
    else:
        readme_lines += [
            "",
            "NOTE: The Java engine was MOCK / UNAVAILABLE for this job, so no comparison.csv exists and",
            "no Java values are included. Nothing in this bundle is simulated decoder output.",
        ]
    if java_csv is not None:
        readme_lines.append("  java_output.csv       decoded samples returned by the Java API")
    readme_lines.append("  report.xlsx           workbook with the same information")
    if graphs:
        readme_lines.append(f"  graphs/               interactive HTML graphs ({len(graphs)} signals, mismatches first)")
    elif include_graphs and not plotly_available():
        readme_lines.append("  (graphs omitted: plotly is not installed)")
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("README.txt", "\n".join(readme_lines) + "\n")
        zf.writestr("job_summary.json", json.dumps(job_summary_dict(job), indent=2, allow_nan=False, default=str))
        zf.writestr("result.json", result_json(job))
        zf.writestr("signal_summary.csv", signal_summary_csv(job))
        zf.writestr("python_output.csv", python_output_csv(job))
        zf.writestr("python_signals.csv", _csv_bytes(job.python_signal_table))
        if job.comparison_performed:
            zf.writestr("comparison.csv", comparison_csv(job))
        if java_csv is not None:
            zf.writestr("java_output.csv", java_csv)
        try:
            zf.writestr("report.xlsx", excel_bytes(job))
        except Exception as exc:  # keep the ZIP usable even if xlsx fails
            zf.writestr("report_xlsx_error.txt", f"Excel report could not be created: {type(exc).__name__}")
        zf.writestr("run_log.txt", "\n".join(redact_mapping(job.log_lines)) + "\n")
        for name, content in graphs.items():
            zf.writestr(name, content)
    return buf.getvalue()
