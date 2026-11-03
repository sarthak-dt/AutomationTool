"""Report generation: CSV, JSON, XLSX, signal graphs and the downloadable ZIP.

Reports never contain credentials: settings/context are passed through
``redact_mapping`` and S3 access keys are never part of a ``JobResult``.
Graphs are interactive HTML files built with plotly (optional dependency; if it
is missing the ZIP simply omits graphs and says so in ``README.txt``).
"""
from __future__ import annotations

import io
import json
import re
import tempfile
import zipfile
from datetime import datetime
from typing import Any

import numpy as np
import pandas as pd
from openpyxl import Workbook
from openpyxl.writer.excel import ExcelWriter

from types import SimpleNamespace

from services.channel_gate import channel_gate
from services.comparison_engine import ROW_COLUMNS
from services.file_service import sanitize_filename
from services.logging_utils import redact_mapping
from services.models import EngineState, Status

MAX_GRAPHS_IN_ZIP = 30
EXCEL_MAX_ROWS = 1_048_576  # Excel's per-worksheet limit, including the header row.
EXCEL_COMPARISON_ROWS_PER_SHEET = EXCEL_MAX_ROWS - 1
EXCEL_WRITE_CHUNK_ROWS = 10_000
COMPARISON_CSV_ROWS_PER_PART = 1_000_000  # Keeps each CSV below Excel's worksheet row cap.
XLSX_COMPRESSION_LEVEL = 9  # Maximum lossless DEFLATE compression.
MAX_WORKBOOK_PART_BYTES = 25_000_000
WORKBOOK_PART_TARGET_ROWS = 400_000


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
    "Index", "File Name", "Timestamp", "CAN ID", "Signal Name",
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


def export_frame(
    rows: pd.DataFrame | None,
    starts: dict[str, Any] | None = None,
    job: Any = None,
) -> pd.DataFrame:
    """Comparison rows in the reporting layout: index, timestamp, CAN ID, channel, signal,
    Python value/unit, Java value/unit, abs error, status (+ details). ``starts`` is retained
    for caller compatibility; CSV export intentionally omits readable date/time values."""
    if rows is None or len(rows) == 0:
        out = pd.DataFrame(columns=EXPORT_COLUMNS)
    else:
        r = rows.reset_index(drop=True)
        for col, default in (("java_channel", 0), ("channel", 0), ("can_id", "")):
            if col not in r.columns:
                r[col] = default
        out = pd.DataFrame(
            {
                "Index": np.arange(1, len(r) + 1),
                "File Name": r["file"],
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
        )[EXPORT_COLUMNS]

    if job is not None:
        diagnostic_rows = _missing_channel_rows(job, len(out))
        if diagnostic_rows:
            out = pd.concat([out, pd.DataFrame(diagnostic_rows, columns=EXPORT_COLUMNS)], ignore_index=True)
    return out


def _missing_channel_rows(job: Any, first_index: int) -> list[dict[str, Any]]:
    """Build export-only explanations when selected channels are absent from a file."""
    settings = getattr(job, "settings", None) or {}
    requested = {int(ch) for ch in settings.get("decode_channels", [])}
    for key in ("python_channel", "java_channel"):
        channel = int(settings.get(key, 0) or 0)
        if channel > 0:
            requested.add(channel)
    diagnostics = []
    next_index = first_index + 1
    for outcome in getattr(job, "files", []):
        stats = getattr(outcome, "python_stats", None) or {}
        actual = stats.get("channels_in_file")
        if actual is None:
            continue  # channel presence could not be verified
        present = {int(ch) for ch in actual}
        absent_from_notices = set()
        for notice in stats.get("channel_notices", []):
            match = re.search(r"\bchannel\s+(\d+)\s+is not present\b", str(notice), flags=re.IGNORECASE)
            if match:
                absent_from_notices.add(int(match.group(1)))
        missing = (requested | absent_from_notices) - present
        for channel in sorted(missing):
            have = ", ".join(str(ch) for ch in sorted(present)) or "none detected"
            diagnostics.append(
                {
                    "Index": next_index,
                    "File Name": outcome.name,
                    "Timestamp": "",
                    "CAN ID": "",
                    "Signal Name": "No decoded signals",
                    "DBC Name": "",
                    "Python Channel": "",
                    "Java Channel": "",
                    "Python Decoded Value": "",
                    "Java Decoded Value": "",
                    "Absolute Error": "",
                    "Status": Status.NOT_COMPARABLE,
                    "Message": (
                        f"Selected channel {channel} is not present in this measurement file "
                        f"(channels detected: {have}); no decoding was performed for this channel."
                    ),
                }
            )
            next_index += 1
    return diagnostics


def comparison_csv(job: Any) -> bytes:
    return _csv_bytes(export_frame(job.rows, job=job))


def comparison_csv_part_count(
    job: Any, rows_per_part: int = COMPARISON_CSV_ROWS_PER_PART
) -> int:
    """Number of Excel-friendly CSV parts needed for all comparison rows."""
    if rows_per_part < 1:
        raise ValueError("rows_per_part must be >= 1")
    total_rows = comparison_export_row_count(job)
    return max(1, (total_rows + rows_per_part - 1) // rows_per_part)


def comparison_export_row_count(job: Any) -> int:
    """Total comparison rows, including report-only missing-channel rows."""
    comparison_rows = len(job.rows) if job.rows is not None else 0
    return comparison_rows + len(_missing_channel_rows(job, comparison_rows))


def comparison_csv_part(
    job: Any,
    part_number: int,
    rows_per_part: int = COMPARISON_CSV_ROWS_PER_PART,
) -> bytes:
    """Build one numbered CSV chunk without materializing the complete export."""
    part_count = comparison_csv_part_count(job, rows_per_part)
    if part_number < 1 or part_number > part_count:
        raise ValueError(f"part_number must be between 1 and {part_count}")
    start = (part_number - 1) * rows_per_part
    stop = start + rows_per_part
    comparison_rows = job.rows if job.rows is not None else pd.DataFrame(columns=ROW_COLUMNS)
    comparison_count = len(comparison_rows)
    comparison_start = min(start, comparison_count)
    comparison_stop = min(stop, comparison_count)
    with tempfile.TemporaryFile(mode="w+b") as temp_file:
        text_file = io.TextIOWrapper(temp_file, encoding="utf-8", newline="")
        wrote_header = False

        def write_frame(frame: pd.DataFrame) -> None:
            nonlocal wrote_header
            frame.to_csv(text_file, index=False, header=not wrote_header)
            wrote_header = True

        for chunk_start in range(comparison_start, comparison_stop, EXCEL_WRITE_CHUNK_ROWS):
            chunk_stop = min(comparison_stop, chunk_start + EXCEL_WRITE_CHUNK_ROWS)
            frame = export_frame(comparison_rows.iloc[chunk_start:chunk_stop])
            frame["Index"] = np.arange(chunk_start + 1, chunk_stop + 1)
            write_frame(frame)

        if stop > comparison_count:
            diagnostics = _missing_channel_rows(job, comparison_count)
            diagnostic_start = max(0, start - comparison_count)
            diagnostic_stop = min(len(diagnostics), stop - comparison_count)
            if diagnostic_start < diagnostic_stop:
                write_frame(pd.DataFrame(
                    diagnostics[diagnostic_start:diagnostic_stop], columns=EXPORT_COLUMNS
                ))

        if not wrote_header:
            write_frame(pd.DataFrame(columns=EXPORT_COLUMNS))
        text_file.flush()
        text_file.detach()
        temp_file.seek(0)
        return temp_file.read()


def signal_summary_csv(job: Any) -> bytes:
    return _csv_bytes(job.signal_summary)


def python_output_csv(job: Any) -> bytes:
    return _csv_bytes(_decoded_output_csv(python_output_frame(job)))


def java_output_csv(job: Any) -> bytes | None:
    """Only real Java output is ever exported. Returns None when there is none."""
    df = java_output_frame(job)
    return _csv_bytes(_decoded_output_csv(df)) if not df.empty else None


def _decoded_output_csv(frame: pd.DataFrame) -> pd.DataFrame:
    """Export decoder samples with relative seconds only, omitting internal fields."""
    return frame.drop(columns=["is_discrete", "datetime"], errors="ignore").copy()


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
    # JSON is a sample/timestamp report, so omit absolute wall-clock metadata too.
    payload.pop("started_at", None)
    payload.pop("finished_at", None)
    for file_result in payload.get("files", []):
        for stats_name in ("python_stats", "java_stats"):
            stats = file_result.get(stats_name)
            if isinstance(stats, dict):
                stats.pop("start_time", None)
                stats.pop("datetime", None)
    payload["signal_summary"] = df_records(job.signal_summary)
    payload["python_signals"] = df_records(job.python_signal_table)
    if include_rows:
        payload["rows"] = df_records(job.rows)
    return json.dumps(redact_mapping(payload), indent=2, allow_nan=False, default=str).encode("utf-8")


def excel_bytes(job: Any) -> bytes:
    """Create a single small comparison workbook or direct large jobs to workbook parts."""
    total_rows = comparison_export_row_count(job)
    if total_rows > WORKBOOK_PART_TARGET_ROWS:
        raise ValueError(
            "This comparison is too large for one workbook download. Download the numbered workbook parts instead."
        )
    data = _excel_bytes_range(job, 0, total_rows)
    if len(data) > MAX_WORKBOOK_PART_BYTES:
        raise ValueError(
            "The comparison workbook exceeds 25 MB. Download the numbered workbook parts instead."
        )
    return data


def excel_part_bytes(
    job: Any,
    start_row: int,
    max_bytes: int = MAX_WORKBOOK_PART_BYTES,
) -> tuple[bytes, int]:
    """Create a lossless workbook part no larger than max_bytes, with adaptive row count."""
    total_rows = comparison_export_row_count(job)
    if total_rows == 0 and start_row == 0:
        data = _excel_bytes_range(job, 0, 0)
        if len(data) > max_bytes:
            raise ValueError(f"The empty comparison workbook exceeds the {max_bytes:,}-byte limit.")
        return data, 0
    if start_row < 0 or start_row >= total_rows:
        raise ValueError(f"start_row must be between 0 and {max(0, total_rows - 1)}")
    if max_bytes < 1:
        raise ValueError("max_bytes must be >= 1")

    row_count = min(WORKBOOK_PART_TARGET_ROWS, total_rows - start_row)
    while True:
        stop_row = start_row + row_count
        data = _excel_bytes_range(job, start_row, stop_row)
        if len(data) <= max_bytes:
            return data, stop_row
        if row_count == 1:
            raise ValueError(
                f"A single comparison row exceeds the {max_bytes:,}-byte workbook part limit."
            )
        row_count = max(1, row_count // 2)


def _excel_bytes_range(job: Any, start_row: int, stop_row: int) -> bytes:
    """Stream a comparison-row interval into a comparison-only workbook."""
    workbook = Workbook(write_only=True)
    worksheets: list[tuple[Any, int]] = []
    current_sheet = None
    current_sheet_rows = 0
    sheet_number = 0
    index = start_row + 1

    def add_sheet() -> None:
        nonlocal current_sheet, current_sheet_rows, sheet_number
        sheet_number += 1
        current_sheet_rows = 0
        name = "Comparison" if sheet_number == 1 else f"Comparison {sheet_number}"
        current_sheet = workbook.create_sheet(title=name)
        current_sheet.freeze_panes = "A2"
        current_sheet.append(EXPORT_COLUMNS)
        worksheets.append((current_sheet, 0))

    def append_frame(frame: pd.DataFrame) -> None:
        nonlocal current_sheet, current_sheet_rows, index
        for values in frame.itertuples(index=False, name=None):
            if current_sheet is None or current_sheet_rows >= EXCEL_COMPARISON_ROWS_PER_SHEET:
                add_sheet()
            excel_row = [index, *(_excel_cell_value(value) for value in values[1:])]
            current_sheet.append(excel_row)
            current_sheet_rows += 1
            worksheets[-1] = (current_sheet, current_sheet_rows)
            index += 1

    # Export in small batches. A normal openpyxl worksheet retains every cell in
    # memory; write-only worksheets keep workbook-generation memory bounded.
    job_rows = job.rows if job.rows is not None else pd.DataFrame(columns=ROW_COLUMNS)
    comparison_count = len(job_rows)
    comparison_stop = min(stop_row, comparison_count)
    for start in range(start_row, comparison_stop, EXCEL_WRITE_CHUNK_ROWS):
        chunk_stop = min(comparison_stop, start + EXCEL_WRITE_CHUNK_ROWS)
        exported = export_frame(job_rows.iloc[start:chunk_stop])
        append_frame(exported)

    if stop_row > comparison_count:
        diagnostics = _missing_channel_rows(job, comparison_count)
        diagnostic_start = max(0, start_row - comparison_count)
        diagnostic_stop = min(len(diagnostics), stop_row - comparison_count)
        for start in range(diagnostic_start, diagnostic_stop, EXCEL_WRITE_CHUNK_ROWS):
            chunk_stop = min(diagnostic_stop, start + EXCEL_WRITE_CHUNK_ROWS)
            append_frame(pd.DataFrame(diagnostics[start:chunk_stop], columns=EXPORT_COLUMNS))

    if current_sheet is None:
        add_sheet()
    for worksheet, data_rows in worksheets:
        _style_comparison_sheet(worksheet, data_rows)

    # Save the ZIP container to disk so workbook construction does not also keep
    # its growing compressed byte buffer in memory. Streamlit needs bytes for its
    # download API, so only the final artifact is read back into memory.
    with tempfile.TemporaryFile(mode="w+b") as temp_file:
        archive = zipfile.ZipFile(
            temp_file,
            mode="w",
            compression=zipfile.ZIP_DEFLATED,
            allowZip64=True,
            compresslevel=XLSX_COMPRESSION_LEVEL,
        )
        ExcelWriter(workbook, archive).save()
        temp_file.seek(0)
        return temp_file.read()


def _excel_cell_value(value: Any) -> Any:
    """Convert pandas/numpy missing values to cells openpyxl can write."""
    if value is None or pd.isna(value):
        return None
    if isinstance(value, np.generic):
        value = value.item()
    if isinstance(value, float) and not np.isfinite(value):
        return str(value)
    return value


def _style_comparison_sheet(worksheet: Any, data_rows: int) -> None:
    """Highlight value mismatches and known/unknown Python/Java channel differences in Excel."""
    if data_rows <= 0:
        return

    from openpyxl.formatting.rule import FormulaRule
    from openpyxl.styles import Font, PatternFill
    from openpyxl.utils import get_column_letter

    columns = {str(name): index + 1 for index, name in enumerate(EXPORT_COLUMNS)}
    status_col = get_column_letter(columns["Status"])
    python_channel_col = get_column_letter(columns["Python Channel"])
    java_channel_col = get_column_letter(columns["Java Channel"])
    last_col = get_column_letter(len(EXPORT_COLUMNS))
    last_row = data_rows + 1

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
    if comparison_export_row_count(job) <= WORKBOOK_PART_TARGET_ROWS:
        readme_lines.append("  report.xlsx           comparison-only workbook (maximum 25 MB)")
    else:
        readme_lines.append("  report.xlsx           omitted: download the numbered workbook parts from the app")
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
        if comparison_export_row_count(job) <= WORKBOOK_PART_TARGET_ROWS:
            try:
                zf.writestr("report.xlsx", excel_bytes(job))
            except Exception as exc:  # keep the ZIP usable even if xlsx fails
                zf.writestr("report_xlsx_error.txt", f"Excel report could not be created: {type(exc).__name__}")
        zf.writestr("run_log.txt", "\n".join(redact_mapping(job.log_lines)) + "\n")
        for name, content in graphs.items():
            zf.writestr(name, content)
    return buf.getvalue()
