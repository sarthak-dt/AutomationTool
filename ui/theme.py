"""White theme + small HTML helpers (all user-supplied text is HTML-escaped)."""
from __future__ import annotations

from html import escape
from typing import Any

CSS = """
<style>
:root{--bg:#f5f7fb;--line:#e2e8f0;--text:#172033;--muted:#667085;--blue:#2563eb;--blue-soft:#eff6ff;
--green:#15803d;--green-bg:#ecfdf3;--red:#b42318;--red-bg:#fef3f2;--amber:#92400e;--amber-bg:#fffaeb;}
.stApp{background:var(--bg);}
section[data-testid="stSidebar"]{background:#fff;border-right:1px solid var(--line);}
.block-container{padding-top:1.6rem;max-width:1500px;}
.brand{display:flex;align-items:center;gap:10px;margin-bottom:6px;}
.brand-icon{width:39px;height:39px;border-radius:10px;background:var(--blue);display:grid;place-items:center;color:#fff;font-size:22px;font-weight:900;}
.brand b{display:block;font-size:14px;color:var(--text);} .brand small{color:var(--muted);font-size:11px;}
.eyebrow{color:var(--blue);text-transform:uppercase;letter-spacing:1.3px;font-size:10px;font-weight:800;}
.page-title{margin:2px 0 0;font-size:28px;font-weight:700;letter-spacing:-.6px;color:var(--text);}
.page-sub{color:var(--muted);margin:4px 0 18px;font-size:14px;}
.card{background:#fff;border:1px solid var(--line);border-radius:12px;padding:16px;box-shadow:0 1px 2px #1018280a;}
.metric-top{display:flex;justify-content:space-between;align-items:center;color:var(--muted);font-size:12px;}
.metric-icon{width:30px;height:30px;border-radius:8px;background:var(--blue-soft);color:var(--blue);display:grid;place-items:center;}
.metric-value{font-size:27px;font-weight:700;letter-spacing:-.6px;margin-top:10px;color:var(--text);}
.metric-sub{font-size:11px;color:var(--muted);margin-top:2px;}
.pill{display:inline-flex;align-items:center;gap:6px;border-radius:999px;padding:4px 9px;font-size:10px;font-weight:800;border:1px solid var(--line);background:#fff;color:#475467;}
.pill.pass{background:var(--green-bg);color:var(--green);border-color:#abefc6;}
.pill.fail{background:var(--red-bg);color:var(--red);border-color:#fecdca;}
.pill.warn{background:var(--amber-bg);color:var(--amber);border-color:#fedf89;}
.pill.info{background:var(--blue-soft);color:#175cd3;border-color:#b2ddff;}
.notice{padding:12px 14px;border-radius:9px;font-size:13px;margin:8px 0;border:1px solid;}
.notice.warn{background:var(--amber-bg);border-color:#fedf89;color:#93370d;}
.notice.info{background:#eff8ff;border-color:#b2ddff;color:#175cd3;}
.notice.fail{background:var(--red-bg);border-color:#fecdca;color:var(--red);}
.notice.pass{background:var(--green-bg);border-color:#abefc6;color:var(--green);}
.side-status{border:1px solid var(--line);background:#f9fafb;padding:12px;border-radius:10px;font-size:12px;color:#475467;}
.dot{width:8px;height:8px;border-radius:50%;display:inline-block;margin-right:7px;}
.dot.ok{background:#12b76a;} .dot.warn{background:#f79009;} .dot.bad{background:#f04438;}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;}
div[data-testid="stMetric"]{background:#fff;border:1px solid var(--line);border-radius:12px;padding:12px 14px;}
.stButton>button[kind="primary"]{background:var(--blue);border-color:var(--blue);}
.tbl-wrap{overflow:auto;background:#fff;border:1px solid var(--line);border-radius:12px;max-height:520px;}
table.tbl{width:100%;border-collapse:collapse;white-space:nowrap;}
table.tbl th{position:sticky;top:0;text-align:left;color:#667085;font-size:10px;letter-spacing:.7px;text-transform:uppercase;font-weight:700;padding:11px 10px;border-bottom:1px solid var(--line);background:#f9fafb;}
table.tbl td{padding:11px 10px;border-bottom:1px solid #eaecf0;font-size:12px;color:var(--text);}
table.tbl tbody tr:hover{background:#f9fafb;}
.status{display:inline-flex;align-items:center;gap:5px;padding:4px 8px;border-radius:999px;font-size:10px;font-weight:800;}
.status.pass{background:var(--green-bg);color:var(--green);} .status.fail{background:var(--red-bg);color:var(--red);}
.status.warn{background:var(--amber-bg);color:var(--amber);} .status.info{background:var(--blue-soft);color:#175cd3;}
.bar{height:7px;background:#eaecf0;border-radius:9px;overflow:hidden;margin-top:6px;}
.bar span{display:block;height:100%;border-radius:9px;background:linear-gradient(90deg,#60a5fa,#12b76a);}
.bar span.amber{background:#f79009;} .bar span.red{background:#f04438;}
.sig-row{margin:12px 0;} .sig-row .h{display:flex;justify-content:space-between;font-size:12px;} .sig-row small{color:var(--muted);}
.big-pct{font-size:38px;font-weight:700;letter-spacing:-.8px;color:var(--text);}
</style>
"""


HTML_UI_CSS = """
<style>
/* ---- port of the HTML prototype's look ---- */
.stApp{background:#f5f7fb;}
header[data-testid="stHeader"]{background:transparent;}
.block-container{padding-top:1.4rem;max-width:1600px;}
section[data-testid="stSidebar"]{background:#fff;border-right:1px solid #e2e8f0;}
section[data-testid="stSidebar"] > div{padding-top:.4rem;}
.nav-label{color:#98a2b3;font-size:10px;letter-spacing:1.3px;padding:0 12px;margin:14px 0 5px;font-weight:800;}
section[data-testid="stSidebar"] div[role="radiogroup"]{gap:5px;display:grid;}
section[data-testid="stSidebar"] div[role="radiogroup"] > label{padding:11px 12px;border-radius:8px;margin:0;width:100%;color:#475467;font-weight:500;cursor:pointer;}
section[data-testid="stSidebar"] div[role="radiogroup"] > label > div:first-child{display:none;}
section[data-testid="stSidebar"] div[role="radiogroup"] > label:hover{background:#f9fafb;}
section[data-testid="stSidebar"] div[role="radiogroup"] > label:has(input:checked){background:#eff6ff;color:#2563eb;font-weight:700;}
section[data-testid="stSidebar"] div[role="radiogroup"] > label:has(input:checked) p{color:#2563eb;font-weight:700;}
.sidebar-foot{border:1px solid #e2e8f0;background:#f9fafb;padding:13px;border-radius:10px;margin-top:18px;}
.status-line{display:flex;align-items:center;gap:8px;color:#475467;font-size:12px;margin:4px 0;}
.sidebar-foot p{font-size:11px;color:#667085;margin:9px 0 0;}
.dot{width:8px;height:8px;border-radius:50%;display:inline-block;margin-right:7px;} .status-line .dot{margin-right:0;}
.dot.ok{background:#12b76a;} .dot.warn{background:#f79009;} .dot.bad{background:#f04438;}
.muted{color:#667085;} .small{font-size:11px;}
.topbar-pill{display:inline-flex;align-items:center;gap:7px;border:1px solid #e2e8f0;border-radius:999px;padding:6px 10px;font-size:11px;color:#475467;white-space:nowrap;background:#fff;}
.topbar-pill.demo{border-color:#fedf89;color:#93370d;background:#fffaeb;}
.topbar-pill.live{border-color:#abefc6;color:#15803d;background:#ecfdf3;}
.page-title{font-size:30px;font-weight:700;letter-spacing:-.7px;margin:4px 0 0;color:#172033;line-height:1.2;}
.page-sub{color:#667085;margin:7px 0 18px;font-size:14px;}
/* panels: container(key="panel_*") */
[class*="st-key-panel"]{background:#fff;border:1px solid #e2e8f0;border-radius:12px;box-shadow:0 1px 2px #1018280a;padding:18px;margin-bottom:16px;}
.panel-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:15px;}
.panel-head h2{font-size:15px;margin:0;font-weight:700;color:#172033;padding:0;}
.panel-head p{margin:3px 0 0;color:#667085;font-size:12px;}
.tag{border:1px solid #e2e8f0;border-radius:6px;color:#475467;padding:3px 6px;font-size:10px;background:#fff;white-space:nowrap;}
.metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:13px;margin:6px 0 18px;}
.metric{background:#fff;border:1px solid #e2e8f0;border-radius:12px;box-shadow:0 1px 2px #1018280a;padding:16px;}
.metric-top{display:flex;align-items:center;justify-content:space-between;color:#667085;font-size:12px;}
.metric-icon{width:31px;height:31px;border-radius:8px;background:#eff6ff;color:#2563eb;display:grid;place-items:center;}
.metric strong{display:block;font-size:27px;margin-top:12px;letter-spacing:-.6px;color:#172033;}
.trend{font-size:11px;color:#15803d;margin-top:3px;}
.status{display:inline-flex;align-items:center;gap:5px;padding:4px 8px;border-radius:999px;font-size:10px;font-weight:800;}
.status.pass,.pass{background:#ecfdf3;color:#15803d;} .status.fail,.fail{background:#fef3f2;color:#b42318;} .status.warn,.warn{background:#fffaeb;color:#92400e;}
.progress{height:7px;background:#eaecf0;border-radius:9px;overflow:hidden;}
.progress span{display:block;height:100%;background:linear-gradient(90deg,#60a5fa,#12b76a);border-radius:9px;}
.progress span.amber{background:#f79009;} .progress span.red{background:#f04438;}
.big-row{display:flex;align-items:baseline;gap:8px;margin:18px 0 8px;} .big-pct{font-size:38px;color:#172033;}
.signal-list{display:grid;gap:13px;margin-top:22px;}
.signal-row{display:grid;grid-template-columns:1fr auto;gap:8px;} .signal-row .progress{grid-column:1/-1;}
.signal-row strong{font-size:12px;} .signal-row small{color:#667085;}
.table-wrap{overflow:auto;max-height:520px;}
table.tbl{width:100%;border-collapse:collapse;white-space:nowrap;}
table.tbl th{position:sticky;top:0;text-align:left;color:#667085;font-size:10px;letter-spacing:.7px;text-transform:uppercase;font-weight:700;padding:11px 10px;border-bottom:1px solid #e2e8f0;background:#f9fafb;}
table.tbl td{padding:12px 10px;border-bottom:1px solid #eaecf0;font-size:12px;color:#172033;}
table.tbl tbody tr:last-child td{border-bottom:0;} table.tbl tbody tr:hover{background:#f9fafb;}
.source-card{border:1px solid #e2e8f0;background:#fff;border-radius:10px;padding:17px;margin-bottom:10px;}
.source-card h3{margin:10px 0 5px;font-size:14px;padding:0;} .source-card p{color:#667085;font-size:12px;margin:0 0 6px;}
.source-icon{font-size:22px;color:#2563eb;}
.notice{padding:12px 14px;border-radius:9px;background:#fffaeb;border:1px solid #fedf89;color:#93370d;font-size:12px;margin:12px 0;}
.info{padding:12px 14px;border-radius:9px;background:#eff8ff;border:1px solid #b2ddff;color:#175cd3;font-size:12px;margin:12px 0;}
.filechips{display:flex;gap:7px;flex-wrap:wrap;margin-top:10px;}
.filechip{display:inline-flex;gap:7px;align-items:center;border:1px solid #e2e8f0;border-radius:7px;padding:6px 9px;background:#fff;font-size:11px;}
.file-meta small{color:#667085;} .file-row{border-bottom:1px solid #e2e8f0;padding:6px 0;}
.footer-note{color:#98a2b3;font-size:11px;text-align:center;margin:24px 0 4px;}
.legend{display:flex;gap:16px;flex-wrap:wrap;color:#667085;font-size:11px;margin-top:6px;}
.legend span:before{content:"";display:inline-block;width:8px;height:8px;border-radius:2px;background:var(--c);margin-right:6px;}
@media(max-width:1050px){.metrics{grid-template-columns:repeat(2,1fr);}}
</style>
"""

STATUS_KIND = {
    "MATCH": "pass",
    "MISMATCH": "fail",
    "MISSING_IN_PYTHON": "warn",
    "MISSING_IN_JAVA": "warn",
    "NOT_COMPARABLE": "info",
    "ERROR": "fail",
    "OK": "pass",
    "MOCK / UNAVAILABLE": "warn",
    "VALID": "pass",
    "VALID_WITH_WARNINGS": "warn",
    "INVALID": "fail",
}
STATUS_ICON = {
    "MATCH": "✅",
    "MISMATCH": "❌",
    "MISSING_IN_PYTHON": "⚠️",
    "MISSING_IN_JAVA": "⚠️",
    "NOT_COMPARABLE": "➖",
    "ERROR": "⛔",
}


def inject(st: Any) -> None:
    st.markdown(CSS, unsafe_allow_html=True)
    st.markdown(HTML_UI_CSS, unsafe_allow_html=True)


def pill(text: str, kind: str | None = None) -> str:
    kind = kind or STATUS_KIND.get(text, "")
    return f'<span class="pill {escape(kind)}">{escape(str(text))}</span>'


def page_header(st: Any, title: str, subtitle: str, eyebrow: str = "AUTOMOTIVE / DECODER VALIDATION") -> None:
    st.markdown(
        f'<div class="eyebrow">{escape(eyebrow)}</div><div class="page-title">{escape(title)}</div>'
        f'<div class="page-sub">{escape(subtitle)}</div>',
        unsafe_allow_html=True,
    )


def metric_card(label: str, value: Any, sub: str = "", icon: str = "▤") -> str:
    return (
        f'<div class="card"><div class="metric-top"><span>{escape(label)}</span>'
        f'<span class="metric-icon">{escape(icon)}</span></div>'
        f'<div class="metric-value">{escape(str(value))}</div>'
        f'<div class="metric-sub">{escape(sub)}</div></div>'
    )


def notice(st: Any, text: str, kind: str = "info", allow_html: bool = False) -> None:
    body = text if allow_html else escape(text)
    st.markdown(f'<div class="notice {escape(kind)}">{body}</div>', unsafe_allow_html=True)
