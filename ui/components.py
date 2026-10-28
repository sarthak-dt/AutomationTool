"""Pure HTML builders for the UI (no Streamlit import, so they are unit-testable).

All dynamic text is HTML-escaped. CSS classes are defined in ``ui/theme.py``.
"""
from __future__ import annotations

from html import escape as E
from typing import Any

import numpy as np
import pandas as pd

from services import timeutil
from services.models import Status

PILL = {
    "MATCH": "pass", "MISMATCH": "fail", "MISSING_IN_PYTHON": "fail", "MISSING_IN_JAVA": "fail",
    "NOT_COMPARABLE": "warn", "ERROR": "fail",
}
PILL_TEXT = {
    "MATCH": "MATCH", "MISMATCH": "MISMATCH", "MISSING_IN_PYTHON": "MISSING IN PYTHON",
    "MISSING_IN_JAVA": "MISSING IN JAVA", "NOT_COMPARABLE": "NOT COMPARABLE", "ERROR": "ERROR",
}


def fmt(x: Any) -> str:
    if x is None or (isinstance(x, float) and np.isnan(x)):
        return ""
    return f"{x:.8g}" if isinstance(x, (float, np.floating)) else str(x)


def val_with_unit(v: Any, unit: Any) -> str:
    t = fmt(v)
    if t == "":
        return "Missing"
    u = str(unit or "").strip()
    return f"{t} {u}" if u else t


def status_pill(status: str) -> str:
    return f'<span class="status {PILL.get(status, "warn")}">{E(PILL_TEXT.get(status, status))}</span>'


def metric_card_html(label: str, value: Any, sub: str = "", icon: str = "▤", sub_class: str = "muted small") -> str:
    return (
        f'<div class="metric"><div class="metric-top"><span>{E(label)}</span><span class="metric-icon">{E(icon)}</span></div>'
        f'<strong>{E(str(value))}</strong><div class="{E(sub_class)}">{E(sub)}</div></div>'
    )


def metrics_row_html(cards: list[tuple[str, Any, str, str]]) -> str:
    return '<div class="metrics">' + "".join(metric_card_html(*c) for c in cards) + "</div>"


def panel_head_html(title: str, subtitle: str = "", tag: str = "") -> str:
    tag_html = f'<span class="tag">{E(tag)}</span>' if tag else ""
    sub = f"<p>{E(subtitle)}</p>" if subtitle else ""
    return f'<div class="panel-head"><div><h2>{E(title)}</h2>{sub}</div>{tag_html}</div>'


def notice_html(text_html: str, kind: str = "notice") -> str:
    """``kind``: 'notice' (amber) or 'info' (blue). ``text_html`` must already be escaped/safe."""
    return f'<div class="{E(kind)}">{text_html}</div>'


def source_card_html(icon: str, title: str, text: str) -> str:
    return f'<div class="source-card"><div class="source-icon">{E(icon)}</div><h3>{E(title)}</h3><p>{E(text)}</p></div>'


def file_item_html(name: str, detail: str, tag: str, icon: str = "📄") -> str:
    return (
        f'<div class="file-meta">{icon} <strong>{E(name)}</strong><br><small>{E(detail)}</small></div>'
        f'<span class="tag">{E(tag)}</span>'
    )


def chips_html(names: list[str]) -> str:
    return '<div class="filechips">' + "".join(f'<span class="filechip">{E(n)}</span>' for n in names) + "</div>"


def comparison_table_html(rows: pd.DataFrame, limit: int = 400, starts: dict | None = None) -> str:
    """Signal-level comparison table, same columns/look as the HTML prototype.

    ``starts`` (file -> start Timestamp) shows readable date-times instead of seconds.
    """
    starts = starts or {}
    head = ("<tr><th>Signal</th><th>Timestamp</th><th>Python reference</th><th>Java parser</th>"
            "<th>Abs. error</th><th>Status</th></tr>")
    body = []
    for r in rows.head(limit).itertuples(index=False):
        ts = timeutil.display_time(r.timestamp, starts.get(getattr(r, "file", None)))
        err = "—" if pd.isna(r.abs_diff) else f"{r.abs_diff:.4g}"
        py = "Missing" if r.status == Status.MISSING_IN_PYTHON else val_with_unit(r.python_value, r.unit_python)
        jv = "Missing" if r.status == Status.MISSING_IN_JAVA else val_with_unit(r.java_value, r.unit_java)
        note = getattr(r, "note", "") or ""
        title = f' title="{E(str(note))}"' if note else ""
        body.append(
            f"<tr{title}><td>{E(str(r.signal))}</td><td>{ts}</td><td>{E(py)}</td><td>{E(jv)}</td><td>{err}</td>"
            f"<td>{status_pill(r.status)}</td></tr>"
        )
    return f'<div class="table-wrap"><table class="tbl"><thead>{head}</thead><tbody>{"".join(body)}</tbody></table></div>'


def bar_html(pct: float | None, cls: str = "") -> str:
    width = 0 if pct is None or pd.isna(pct) else min(max(float(pct), 0.0), 100.0)
    return f'<div class="progress"><span class="{E(cls)}" style="width:{width}%"></span></div>'


def agreement_html(job: Any, max_signals: int | None = 12) -> tuple[str, str]:
    """('Signal agreement' panel body, remaining-signals html)."""
    s = job.summary
    pct = s.get("match_percentage")
    clean = pct is not None and s.get("mismatch", 0) == 0 and s.get("error", 0) == 0
    kind, label = ("pass", "PASS") if clean else (("warn", "REVIEW") if pct is not None else ("warn", "N/A"))
    head = (
        f'<div class="panel-head"><div><h2>Signal agreement</h2><p>Latest comparison</p></div>'
        f'<span class="status {kind}">{label}</span></div>'
        f'<div class="big-row"><strong class="big-pct">{"n/a" if pct is None else f"{pct}%"}</strong>'
        f'<span class="muted small">sample agreement</span></div>{bar_html(pct)}'
    )
    sig = job.signal_summary
    rows_html: list[str] = []
    if sig is not None and len(sig):
        sig = sig[sig["signal"] != "(file)"].sort_values(["match_pct", "signal"], na_position="first")
        for r in sig.itertuples(index=False):
            p = r.match_pct
            if pd.isna(p):
                txt, cls, width = PILL_TEXT.get(r.status, r.status).title(), "red", 0
            else:
                txt, cls, width = f"{p:.1f}%", ("" if p >= 99 else "amber" if p >= 90 else "red"), p
            rows_html.append(
                f'<div class="signal-row"><strong>{E(r.signal)}</strong><small>{E(txt)}</small>{bar_html(width, cls)}</div>'
            )
    first = rows_html if max_signals is None else rows_html[:max_signals]
    rest = [] if max_signals is None else rows_html[max_signals:]
    return head + '<div class="signal-list">' + "".join(first) + "</div>", '<div class="signal-list">' + "".join(rest) + "</div>" if rest else ""


def recent_jobs_table_html(entries: list[dict[str, Any]]) -> str:
    head = ("<tr><th>Job ID</th><th>Input file</th><th>DBC files</th><th>Signals</th><th>Agreement</th>"
            "<th>Status</th><th>Run time</th></tr>")
    body = []
    for e in entries:
        files = e.get("files") or []
        first = files[0] if files else "—"
        extra = f" (+{len(files) - 1} more)" if len(files) > 1 else ""
        performed = bool(e.get("comparison_performed"))
        if not performed:
            pill = '<span class="status warn">PYTHON ONLY</span>'
            agree = "n/a"
        else:
            pct = e.get("match_percentage")
            agree = "n/a" if pct is None else f"{pct}%"
            if e.get("error", 0):
                pill = '<span class="status fail">ERROR</span>'
            elif e.get("mismatch", 0):
                pill = '<span class="status warn">REVIEW</span>'
            else:
                pill = '<span class="status pass">PASS</span>'
        signals = e.get("signals_total") if performed else e.get("python_signals", 0)
        secs = int(round(e.get("duration_s", 0) or 0))
        body.append(
            f"<tr><td>{E(str(e.get('job_id', '')))}</td><td>{E(first + extra)}</td><td>{e.get('n_dbcs', 0)} selected</td>"
            f"<td>{signals}</td><td>{agree}</td><td>{pill}</td><td>{secs // 60:02d}:{secs % 60:02d}</td></tr>"
        )
    return f'<div class="table-wrap"><table class="tbl"><thead>{head}</thead><tbody>{"".join(body)}</tbody></table></div>'


def python_only_table_html(df: pd.DataFrame, limit: int = 400, starts: dict | None = None) -> str:
    """Same layout as the comparison table, for jobs where the Java output is unavailable.

    Only real Python samples are listed; the Java column says "Unavailable" and no
    match/mismatch verdict is shown.
    """
    head = ("<tr><th>Signal</th><th>Timestamp</th><th>Python reference</th><th>Java parser</th>"
            "<th>Abs. error</th><th>Status</th></tr>")
    starts = starts or {}
    body = []
    for r in df.head(limit).itertuples(index=False):
        body.append(
            f"<tr><td>{E(str(r.signal))}</td><td>{timeutil.display_time(r.timestamp, starts.get(getattr(r, 'file', None)))}</td><td>{E(val_with_unit(r.value, r.unit))}</td>"
            '<td class="muted">Unavailable</td><td>—</td><td><span class="status warn">NOT COMPARED</span></td></tr>'
        )
    return f'<div class="table-wrap"><table class="tbl"><thead>{head}</thead><tbody>{"".join(body)}</tbody></table></div>'
