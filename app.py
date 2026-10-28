"""Automotive Decoder Comparator - UI that mirrors the HTML prototype.

    streamlit run app.py        (or ./run.sh)

Pages (left navigation, same as the prototype): Dashboard, New comparison,
S3 browser, DBC manager, Results, Reports.  The measurement source can be
S3 Upload or Manual Upload (chosen on *New comparison*).

Everything shown comes from real runs; no sample data is ever displayed.
"""
from __future__ import annotations

from typing import Any

import pandas as pd
import streamlit as st

from config import ALLOWED_MEASUREMENT_EXTS, APP_NAME, APP_VERSION, load_config
from services import history_service as hist
from services import report_service as rep
from services.dbc_service import DbcFile, add_uploads, conflicts_summary, detect_conflicts
from services.file_service import ValidationError, cleanup_stale_workspaces, human_size, validate_upload
from services.java_decoder_client import JavaApiConfig, JavaDecoderClient
from services import timeutil
from services.demo import build_demo_job
from services.java_import import JavaCsvError, apply_java_csv
from services.job_runner import MeasurementRef, run_job
from services.logging_utils import clear_secrets, register_secret, setup_logging
from services.models import ComparisonSettings, EngineState, Status
from services.python_decoder import library_status
from services.s3_service import S3Config, S3Error, S3Service
from ui import components as C
from ui import theme
from ui.compat import stretch

CFG = load_config()
setup_logging(CFG.log_level)

S3, MANUAL = "S3 Upload", "Manual Upload"
LOG_TYPES = [e.lstrip(".") for e in ALLOWED_MEASUREMENT_EXTS]
MAX_FILE_ROWS = 200

NAV = {
    "dashboard": "▦   Dashboard",
    "newrun": "▶   New comparison",
    "s3": "☁   S3 browser",
    "dbc": "▤   DBC manager",
    "results": "⇄   Results",
    "reports": "⇩   Reports",
}
TITLES = {
    "dashboard": ("Overview", "Monitor decoder comparisons and inspect signal-level differences."),
    "newrun": ("New comparison", "Select measurement files from S3 (or upload them) and upload one or multiple DBCs."),
    "s3": ("S3 connection & browser", "Configure bucket access, browse a folder and select measurement files."),
    "dbc": ("DBC manager", "Upload DBC files from your computer and select multiple files for decoding."),
    "results": ("Results explorer", "Inspect signal-level matches, mismatches and missing signals."),
    "reports": ("Reports & exports", "Download reports and decoded output files of the last job."),
}
FILTERS = {
    "All signals": None,
    "Mismatches only": [Status.MISMATCH],
    "Matched only": [Status.MATCH],
    "Missing only": [Status.MISSING_IN_JAVA, Status.MISSING_IN_PYTHON],
    "Other issues": [Status.NOT_COMPARABLE, Status.ERROR],
}


# ---------------------------------------------------------------------------
# state helpers
# ---------------------------------------------------------------------------
def init_state() -> None:
    ss = st.session_state
    defaults: dict[str, Any] = {
        "nav": "dashboard",
        "dbc_library": [], "dbc_sel": set(), "dbc_seen": set(), "dbc_up_n": 0, "dbc_msgs": [],
        "manual_files": [], "manual_seen": set(), "manual_up_n": 0,
        "s3_svc": None, "s3_listing": None, "s3_folder": "", "s3_files": [],
        "job": None, "cache": {}, "housekeeping": False,
        # persistent widget values (prefix w_ -> kept alive across pages)
        "w_s3_bucket": CFG.s3_bucket, "w_s3_region": CFG.s3_region, "w_s3_endpoint": CFG.s3_endpoint_url,
        "w_s3_port": CFG.s3_port, "w_s3_access": CFG.s3_access_key_id, "w_s3_secret": CFG.s3_secret_access_key,
        "w_s3_style": CFG.s3_addressing_style if CFG.s3_addressing_style in ("auto", "virtual", "path") else "auto",
        "w_s3_secure": "https",
        "w_source": S3, "w_resample": "none", "w_abs": 0.1, "w_rel": 1e-6, "w_time": 1e-3,
        "w_missing": "flag", "w_unit": "flag", "w_auto": True,
    }
    for k, v in defaults.items():
        ss.setdefault(k, v)


def keep_widget_state() -> None:
    """Streamlit drops state of widgets not rendered on the current page; re-assigning keeps it."""
    ss = st.session_state
    for k in list(ss.keys()):
        if isinstance(k, str) and k.startswith("w_"):
            ss[k] = ss[k]


def goto(page: str) -> None:
    st.session_state["_goto"] = page


def java_client() -> JavaDecoderClient:
    return JavaDecoderClient(JavaApiConfig.from_app_config(CFG))


def usable_selected_dbcs() -> list[DbcFile]:
    ss = st.session_state
    return [d for d in ss.dbc_library if d.name in ss.dbc_sel and d.info.usable]


def panel(key: str):
    """White card container (styled by ``[class*="st-key-panel"]``)."""
    try:
        return st.container(key=f"panel_{key}")
    except TypeError:  # very old Streamlit without container keys
        return st.container(border=True)


def html(s: str) -> None:
    st.markdown(s, unsafe_allow_html=True)


def cached(key: str, fn):
    c = st.session_state.cache
    if key not in c:
        c[key] = fn()
    return c[key]


def default_graph_choice(job: Any) -> tuple[str, str] | None:
    sig = job.signal_summary
    if sig is not None and len(sig):
        real = sig[sig["signal"] != "(file)"]
        bad = real[real["status"] == Status.MISMATCH]
        pick = bad.iloc[0] if len(bad) else (real.iloc[0] if len(real) else None)
        if pick is not None:
            return str(pick["file"]), str(pick["signal"])
    for fname, df in job.python_data.items():
        if df is not None and not df.empty:
            return fname, str(df["signal"].iloc[0])
    return None


# ---------------------------------------------------------------------------
# chrome: sidebar + top bar
# ---------------------------------------------------------------------------
def sidebar() -> None:
    ss = st.session_state
    with st.sidebar:
        html('<div class="brand"><div class="brand-icon">⌁</div><div><b>DecoderLab</b><small>Automotive validation suite</small></div></div>')
        html('<div class="nav-label">WORKSPACE</div>')
        st.radio("Main navigation", list(NAV), format_func=lambda k: NAV[k], key="nav", label_visibility="collapsed")
        libs = library_status()
        missing = [k for k, v in libs.items() if v == "missing" and k != "plotly"]
        usable, _ = java_client().availability()
        py_dot, py_txt = ("ok", "Python engine ready") if not missing else ("bad", "Missing: " + ", ".join(missing))
        j_dot, j_txt = ("ok", "Java API connected") if usable else ("warn", "Java API not connected")
        s3_dot, s3_txt = ("ok", "S3 connected") if ss.s3_svc else ("warn", "S3 not connected")
        html(
            '<div class="sidebar-foot">'
            f'<div class="status-line"><span class="dot {py_dot}"></span>{theme.escape(py_txt)}</div>'
            f'<div class="status-line"><span class="dot {j_dot}"></span>{j_txt}</div>'
            f'<div class="status-line"><span class="dot {s3_dot}"></span>{s3_txt}</div>'
            f"<p>White theme · Live data · v{APP_VERSION}</p></div>"
        )
        if missing:
            st.caption("Run ./setup.sh to install the missing libraries.")


def topbar(page: str) -> None:
    title, sub = TITLES[page]
    usable, _ = java_client().availability()
    left, right = st.columns([5, 4])
    with left:
        html(f'<div class="eyebrow">AUTOMOTIVE / DECODER VALIDATION</div><div class="page-title">{theme.escape(title)}</div>'
             f'<div class="page-sub">{theme.escape(sub)}</div>')
    with right:
        a, b, c = st.columns([2.2, 1.2, 1.6])
        pill = ('<span class="topbar-pill live">● JAVA CONNECTED</span>' if usable
                else '<span class="topbar-pill demo">● JAVA MOCK / UNAVAILABLE</span>')
        a.markdown(f'<div style="text-align:right;padding-top:8px">{pill}</div>', unsafe_allow_html=True)
        try:
            pop = b.popover("How it works")
        except AttributeError:
            pop = b.expander("How it works")
        with pop:
            st.markdown(
                "**S3 / Manual** supplies the measurement (log) files. **DBC files** are always uploaded from your computer. "
                "Select the files, click **Run comparison**, then inspect **Results** and download **Reports**.\n\n"
                "Until the Java API is connected, only the Python decoder runs and no comparison is performed."
            )
        c.button("＋ New comparison", type="primary", key=f"top_new_{page}", on_click=goto, args=("newrun",), **stretch(st.button))


# ---------------------------------------------------------------------------
# Dashboard
# ---------------------------------------------------------------------------
def page_dashboard() -> None:
    entries = hist.load_history(CFG.data_dir)
    agg = hist.aggregate(entries)
    rate = (f"{round(100 * agg['jobs_clean'] / agg['jobs_compared'], 1)}% pass rate" if agg["jobs_compared"] else "no Java comparison yet")
    html(C.metrics_row_html([
        ("Comparison jobs", agg["jobs"], f"{agg['python_only_jobs']} Python-only", "▤", "muted small"),
        ("Passed jobs", agg["jobs_clean"], rate, "✓", "trend"),
        ("Signal mismatches", agg["mismatches"], "Across compared jobs", "⚠", "muted small"),
        ("Files processed", agg["files"], "MF4 · BLF · ASC · LOG", "▧", "muted small"),
    ]))
    job = st.session_state.job
    left, right = st.columns([1.25, 0.75], gap="medium")
    with left:
        with panel("overlay"):
            choice = default_graph_choice(job) if job is not None else None
            html(C.panel_head_html("Signal overlay", "Python reference vs Java decoder (real output)", choice[1] if choice else ""))
            if choice is None:
                st.info("No job has been run in this session yet. Start with **New comparison**.")
            elif rep.plotly_available():
                st.plotly_chart(rep.build_signal_figure(job, *choice), **stretch(st.plotly_chart))
                if not job.comparison_performed:
                    st.caption("Only the Python trace is shown: the Java decoder is MOCK / UNAVAILABLE.")
            else:
                df = job.python_data[choice[0]]
                st.line_chart(df[df["signal"] == choice[1]].set_index("timestamp")["value"])
                st.caption("Install plotly for the full overlay chart.")
    with right:
        with panel("agree"):
            if job is None:
                html(C.panel_head_html("Signal agreement", "Latest comparison"))
                st.caption("No comparison yet.")
            elif not job.comparison_performed:
                html(C.panel_head_html("Signal agreement", "Latest comparison", "n/a"))
                html(C.notice_html("<b>Java decoder: MOCK / UNAVAILABLE.</b> No comparison was performed, so there is no agreement figure."))
            else:
                body, rest = C.agreement_html(job)
                html(body)
                if rest:
                    with st.expander("Show all signals"):
                        html(rest)
    with panel("recent"):
        a, b = st.columns([4, 1])
        with a:
            html(C.panel_head_html("Recent comparisons", "Jobs run with this installation."))
        b.button("View results →", key="dash_view", on_click=goto, args=("results",), **stretch(st.button))
        if entries:
            html(C.recent_jobs_table_html(entries[::-1][:25]))
            st.button("Clear history", key="dash_clear", on_click=lambda: hist.clear_history(CFG.data_dir))
        else:
            st.caption("History is empty.")


# ---------------------------------------------------------------------------
# New comparison
# ---------------------------------------------------------------------------
def manual_uploader() -> list[MeasurementRef]:
    ss = st.session_state
    ups = st.file_uploader("Log files (MF4, BLF, ASC, LOG)", type=LOG_TYPES, accept_multiple_files=True, key=f"manual_up_{ss.manual_up_n}")
    ss.manual_files = []
    for f in ups or []:
        try:
            clean = validate_upload(f.name, f.size, ALLOWED_MEASUREMENT_EXTS, CFG.max_measurement_bytes)
        except ValidationError as exc:
            st.error(str(exc))
            continue
        ss.manual_files.append(MeasurementRef(name=clean, size=f.size, data=f.getvalue()))
    return list(ss.manual_files)


def page_newrun() -> None:
    ss = st.session_state
    usable, java_reason = java_client().availability()
    with panel("newrun"):
        html(C.panel_head_html("Configure a comparison", "Measurement files come from S3 or a manual upload. DBC files are uploaded manually.", "Step 1 of 3"))
        st.radio("Measurement source", [S3, MANUAL], horizontal=True, key="w_source")
        source = ss.w_source
        c1, c2 = st.columns(2, gap="medium")
        refs: list[MeasurementRef] = []
        with c1:
            if source == S3:
                html(C.source_card_html("☁", "Measurement source: AWS S3", "Connect to your bucket, browse one folder, and select one or multiple measurement files."))
                refs = [MeasurementRef(name=o.name, size=o.size, s3_object=o) for o in ss.s3_files]
                if refs:
                    st.success(f"{len(refs)} S3 file(s) selected in `/{ss.s3_folder}` ({human_size(sum(r.size for r in refs))})")
                else:
                    st.warning("No S3 files selected yet.")
                st.button("Configure S3 connection", type="primary", key="nr_s3", on_click=goto, args=("s3",))
            else:
                html(C.source_card_html("⇧", "Measurement source: Manual upload", "Upload one or multiple MF4 / BLF / ASC / LOG files from your computer."))
                refs = manual_uploader()
        with c2:
            html(C.source_card_html("▤", "DBC source: Manual upload", "Upload one or multiple DBC files from your computer. Select which DBCs to apply for the run."))
            dbcs = usable_selected_dbcs()
            if dbcs:
                st.success(f"{len(dbcs)} DBC file(s): " + ", ".join(d.name for d in dbcs))
            else:
                st.warning("No DBC files selected yet.")
            st.button("Upload / select DBC files", key="nr_dbc", on_click=goto, args=("dbc",))
        flow = ("Connect S3 → choose a folder → select multiple measurement files → upload/select multiple DBCs → run comparison."
                if source == S3 else "Upload measurement files → upload/select multiple DBCs → run comparison.")
        html(C.notice_html("Expected flow: " + theme.escape(flow), "info"))

        s1, s2 = st.columns(2)
        s1.selectbox("Resampling interval", ["none", "1s"], key="w_resample",
                     format_func=lambda v: {"none": "No resampling", "1s": "1 second"}[v])
        s2.number_input("Absolute numeric tolerance", min_value=0.0, step=0.01, format="%g", key="w_abs")
        with st.expander("More options"):
            o1, o2, o3 = st.columns(3)
            o1.number_input("Relative tolerance", min_value=0.0, format="%g", key="w_rel", help="Also allows |diff| ≤ rel × max(|python|, |java|).")
            o2.number_input("Timestamp tolerance [s]", min_value=0.0, format="%g", key="w_time", help="Max time distance when pairing samples (resampling off).")
            o3.selectbox("NaN values", ["flag", "skip"], key="w_missing", format_func=lambda v: {"flag": "Flag as MISSING", "skip": "Skip rows"}[v])
            o4, o5, _ = st.columns(3)
            o4.selectbox("Unit mismatch", ["flag", "strict"], key="w_unit", format_func=lambda v: {"flag": "Note only", "strict": "NOT_COMPARABLE"}[v])
            o5.checkbox("Auto-detect discrete signals", key="w_auto")
        if usable:
            html(C.notice_html("Java engine is configured and will be called for every file.", "info"))
        else:
            html(C.notice_html("<b>Java engine is MOCK / UNAVAILABLE.</b> " + theme.escape(java_reason) +
                               " The run decodes with the Python engine only; no comparison is performed and no Java values are shown."))
        dbcs = usable_selected_dbcs()
        ready = bool(refs) and bool(dbcs)
        a, b = st.columns([4, 1])
        a.markdown(
            f'<span class="muted small">{len(refs)} measurement file(s) ({human_size(sum(r.size for r in refs))}), {len(dbcs)} DBC file(s) selected.</span>'
            if ready else '<span class="muted small">Select at least one measurement file and one valid DBC file.</span>',
            unsafe_allow_html=True,
        )
        run = b.button("▶ Run comparison", type="primary", disabled=not ready, key="run_btn", **stretch(st.button))
    if run:
        settings = ComparisonSettings(
            abs_tol=float(ss.w_abs), rel_tol=float(ss.w_rel), time_tol=float(ss.w_time), resample_1s=ss.w_resample == "1s",
            missing_policy=ss.w_missing, unit_policy=ss.w_unit, auto_discrete=bool(ss.w_auto),
        )
        if any(r.s3_object for r in refs) and ss.s3_svc is None:
            st.error("S3 files are selected but the S3 connection is not active. Test the connection again.")
            return
        bar = st.progress(0.0, text="Starting...")
        try:
            job = run_job(
                refs, dbcs, settings, CFG, java_client(),
                s3_service=ss.s3_svc if any(r.s3_object for r in refs) else None,
                s3_context={"bucket": ss.s3_svc.config.bucket, "folder": ss.s3_folder} if ss.s3_svc and source == S3 else {"source": "manual upload"},
                progress=lambda f, m: bar.progress(f, text=m),
            )
        except ValidationError as exc:
            bar.empty()
            st.error(str(exc))
            return
        except Exception as exc:
            bar.empty()
            st.error(f"The job failed unexpectedly: {type(exc).__name__}: {exc}")
            return
        ss.job, ss.cache = job, {}
        hist.append_job(CFG.data_dir, job)
        goto("results")
        st.rerun()


# ---------------------------------------------------------------------------
# S3 browser
# ---------------------------------------------------------------------------
def s3_clear() -> None:
    ss = st.session_state
    ss.w_s3_access = ss.w_s3_secret = ""
    ss.s3_svc = ss.s3_listing = None
    ss.s3_files = []
    clear_secrets()


def s3_open(prefix: str) -> None:
    ss = st.session_state
    try:
        ss.s3_listing = ss.s3_svc.list_prefix(prefix)
        ss.s3_folder = ss.s3_listing.prefix
    except (S3Error, ValidationError) as exc:
        ss.s3_error = str(exc)


def s3_config_from_form() -> S3Config:
    ss = st.session_state
    endpoint = ss.w_s3_endpoint.strip()
    if endpoint and "://" not in endpoint and ss.w_s3_secure == "http":
        endpoint = "http://" + endpoint
    return S3Config(
        bucket=ss.w_s3_bucket.strip(), region=ss.w_s3_region.strip(), endpoint_url=endpoint, port=str(ss.w_s3_port).strip(),
        access_key_id=ss.w_s3_access.strip(), secret_access_key=ss.w_s3_secret.strip(), addressing_style=ss.w_s3_style,
    )


def page_s3() -> None:
    ss = st.session_state
    with panel("s3conn"):
        html(C.panel_head_html("S3 connection settings", "Enter your object storage connection details.", "Connection profile"))
        a, b = st.columns(2, gap="medium")
        a.text_input("S3 bucket name *", key="w_s3_bucket", placeholder="my-automotive-data-bucket")
        b.text_input("Region *", key="w_s3_region", placeholder="us-east-1")
        a.text_input("S3 endpoint URL", key="w_s3_endpoint", placeholder="https://s3.example.com", help="Optional for standard AWS S3; used for S3-compatible storage.")
        b.text_input("Endpoint port", key="w_s3_port", placeholder="443", help="Optional; typically 443 for HTTPS or 9000 for some S3-compatible services.")
        a.text_input("Access key ID", key="w_s3_access", placeholder="Leave empty to use an IAM role", autocomplete="off")
        b.text_input("Secret access key", key="w_s3_secret", type="password", placeholder="Leave empty to use an IAM role")
        a.selectbox("Request style *", ["auto", "virtual", "path"], key="w_s3_style",
                    format_func=lambda v: {"auto": "Auto (recommended)", "virtual": "Virtual-hosted style", "path": "Path style"}[v])
        b.selectbox("Connection security", ["https", "http"], key="w_s3_secure",
                    format_func=lambda v: {"https": "HTTPS", "http": "HTTP (only trusted local networks)"}[v],
                    help="Applies when the endpoint URL has no http:// or https:// prefix. Plain AWS S3 always uses HTTPS.")
        html(C.notice_html("<strong>Security:</strong> credentials are kept only in this session's server memory. They are never written to disk, logs or reports. "
                           "Prefer IAM roles or a secrets manager; leave both key fields empty to use the default AWS credential chain."))
        x, y, z = st.columns([4, 1, 1])
        x.markdown('<span class="muted small">Connection is tested with boto3 (list the bucket).</span>', unsafe_allow_html=True)
        z.button("Clear credentials", key="s3_clear", on_click=s3_clear, **stretch(st.button))
        if y.button("Test connection", type="primary", key="s3_test", **stretch(st.button)):
            cfg = s3_config_from_form()
            register_secret(cfg.secret_access_key)
            register_secret(cfg.access_key_id)
            svc = S3Service(cfg, allowed_exts=ALLOWED_MEASUREMENT_EXTS, max_keys=CFG.max_s3_keys_listed)
            with st.spinner("Connecting..."):
                res = svc.test_connection()
            if res.ok:
                ss.s3_svc, ss.s3_files, ss.s3_folder = svc, [], ""
                s3_open("")
                st.success(res.message)
            else:
                ss.s3_svc = ss.s3_listing = None
                st.error(res.message)

    with panel("s3browse"):
        here = ss.s3_listing.prefix if ss.s3_listing is not None else ss.s3_folder
        html(C.panel_head_html("Browse folders and select measurement files", "Only measurement files are sourced from S3.", f"Folder: /{here}"))
        if ss.s3_svc is None:
            st.info("Connect first (Test connection) to browse the bucket.")
            return
        err = ss.pop("s3_error", None)
        if err:
            st.error(f"Could not open the folder: {err}")
        if ss.s3_listing is None:
            s3_open(ss.s3_folder)
        listing = ss.s3_listing
        if listing is None:
            return
        here = listing.prefix
        root_label = "/ (bucket root)" if not here else "/" + here + "  (this folder)"
        options = [root_label] + (["⬆  Parent folder"] if here else []) + [f[len(here):].rstrip("/") + "/" for f in listing.folders]
        choice = st.selectbox("Choose a folder", options, key=f"s3_pick_{here}")
        if choice != options[0]:
            target = "/".join(here.rstrip("/").split("/")[:-1]) if choice.startswith("⬆") else here + choice
            s3_open(target)
            st.rerun()
        if listing.truncated:
            st.warning(f"Listing truncated at {CFG.max_s3_keys_listed} entries.")
        files = listing.files[:MAX_FILE_ROWS]
        if not files:
            st.info(f"No {', '.join(ALLOWED_MEASUREMENT_EXTS)} files in this folder."
                    + (f" ({listing.skipped_unsupported} other file(s) hidden.)" if listing.skipped_unsupported else ""))
            return
        saved = {o.key for o in ss.s3_files}
        chosen = []
        for o in files:
            r1, r2 = st.columns([9, 1])
            ss.setdefault(f"w_s3f_{o.key}", o.key in saved)
            on = r1.checkbox(f"📄 **{o.name}** — {o.size_text} · {o.ext.lstrip('.').upper()} measurement"
                             + (f" · {o.last_modified:%Y-%m-%d %H:%M}" if o.last_modified else ""),
                             key=f"w_s3f_{o.key}")
            r2.markdown(f'<span class="tag">{theme.escape(o.ext.lstrip(".").upper())}</span>', unsafe_allow_html=True)
            if on:
                chosen.append(o)
        if len(listing.files) > MAX_FILE_ROWS:
            st.caption(f"Showing the first {MAX_FILE_ROWS} of {len(listing.files)} files.")
        if listing.skipped_unsupported:
            st.caption(f"{listing.skipped_unsupported} unsupported file(s) hidden.")

        def select_all() -> None:
            all_on = all(st.session_state.get(f"w_s3f_{o.key}", False) for o in files)
            for o in files:
                st.session_state[f"w_s3f_{o.key}"] = not all_on

        def use_files() -> None:
            st.session_state.s3_files = list(chosen)
            goto("newrun")

        x, y, z = st.columns([4, 1.3, 1.5])
        x.markdown(f'<span class="muted small">{len(chosen)} measurement file{"" if len(chosen) == 1 else "s"} selected</span>', unsafe_allow_html=True)
        y.button("Select all in folder", key="s3_all", on_click=select_all, **stretch(st.button))
        z.button("Use selected files", type="primary", key="s3_use", disabled=not chosen, on_click=use_files, **stretch(st.button))


# ---------------------------------------------------------------------------
# DBC manager
# ---------------------------------------------------------------------------
def page_dbc() -> None:
    ss = st.session_state
    with panel("dbcup"):
        html(C.panel_head_html("Manual DBC upload", "Upload multiple DBC files locally. DBCs are not fetched from S3.", "Local files only"))
        ups = st.file_uploader("Choose one or multiple DBC files", type=["dbc"], accept_multiple_files=True,
                               key=f"dbc_up_{ss.dbc_up_n}", help=f"Max {CFG.max_dbc_size_mb} MB per file, up to {CFG.max_dbc_files} files.")
        new = []
        for f in ups or []:
            fid = getattr(f, "file_id", None) or (f.name, f.size)
            if fid not in ss.dbc_seen:
                ss.dbc_seen.add(fid)
                new.append((f.name, f.getvalue()))
        if new:
            before = {d.name for d in ss.dbc_library}
            with st.spinner("Checking DBC files..."):
                ss.dbc_library, ss.dbc_msgs = add_uploads(ss.dbc_library, new, CFG.max_dbc_files, CFG.max_dbc_bytes)
            for d in ss.dbc_library:
                if d.name not in before and d.info.usable:
                    ss.dbc_sel.add(d.name)
                    ss[f"w_dbc_{d.name}"] = True
        for level, text in ss.dbc_msgs:
            if level != "success":
                getattr(st, level)(text)
        lib: list[DbcFile] = ss.dbc_library
        if lib:
            html(f"<b>{len(lib)} local DBC file(s) uploaded:</b>" + C.chips_html([d.name for d in lib]))
        else:
            st.caption("No local DBC files uploaded.")
        html(C.notice_html("You can select multiple DBCs for one comparison. Duplicate message IDs and overlapping signals are reported below; "
                           "if two selected DBCs define the same CAN ID, the first one in the list is used.", "info"))

    with panel("dbcsel"):
        lib = ss.dbc_library
        html(C.panel_head_html("Select DBC files for this run", "DBC files uploaded in this session.", f"{len(lib)} available"))
        if not lib:
            st.info("Upload at least one DBC file above.")
            return
        sel: set[str] = set()
        for d in lib:
            r1, r2, r3 = st.columns([8, 2, 1])
            note = f" — {d.info.n_messages} messages · {d.info.n_signals} signals" if d.info.usable else " — invalid"
            ss.setdefault(f"w_dbc_{d.name}", d.name in ss.dbc_sel and d.info.usable)
            on = r1.checkbox(f"📄 **{d.name}**{note}", key=f"w_dbc_{d.name}", disabled=not d.info.usable)
            r2.markdown(f'<span class="status {C.PILL.get("MATCH") if d.info.status == "VALID" else "warn" if d.info.usable else "fail"}">'
                        f'{theme.escape(d.info.status.replace("_", " "))}</span>', unsafe_allow_html=True)

            def remove(name: str = d.name) -> None:
                ss.dbc_library = [x for x in ss.dbc_library if x.name != name]
                ss.dbc_sel.discard(name)
                ss.pop(f"w_dbc_{name}", None)

            r3.button("✕", key=f"dbc_rm_{d.name}", on_click=remove, help="Remove this DBC")
            if d.info.error:
                st.error(f"{d.name}: {d.info.error}")
            elif d.info.warnings:
                st.caption(f"⚠️ {d.name}: " + "; ".join(d.info.warnings))
            if on and d.info.usable:
                sel.add(d.name)
        ss.dbc_sel = sel

        def select_all() -> None:
            ok = [d for d in st.session_state.dbc_library if d.info.usable]
            all_on = all(d.name in st.session_state.dbc_sel for d in ok)
            for d in ok:
                st.session_state[f"w_dbc_{d.name}"] = not all_on
            st.session_state.dbc_sel = set() if all_on else {d.name for d in ok}

        x, y, z = st.columns([4, 1.3, 1.5])
        x.markdown(f'<span class="muted small">{len(sel)} DBC file(s) selected</span>', unsafe_allow_html=True)
        y.button("Select all DBCs", key="dbc_all", on_click=select_all, **stretch(st.button))
        z.button("Use selected DBCs", type="primary", key="dbc_use", disabled=not sel, on_click=goto, args=("newrun",), **stretch(st.button))

        chosen = usable_selected_dbcs()
        if len(chosen) > 1:
            conflicts = detect_conflicts(chosen)
            summ = conflicts_summary(conflicts)
            if conflicts:
                html(C.notice_html(f"<b>{summ['error']} conflict(s), {summ['warning']} warning(s), {summ['info']} info</b> between the selected DBC files. "
                                   "Nothing is ignored silently.", "notice" if summ["error"] or summ["warning"] else "info"))
                st.dataframe(pd.DataFrame([c.as_dict() for c in conflicts]), hide_index=True, **stretch(st.dataframe))
            else:
                st.success("No conflicting definitions between the selected DBC files.")


# ---------------------------------------------------------------------------
# Results
# ---------------------------------------------------------------------------
def page_results() -> None:
    ss = st.session_state
    job = ss.job
    if job is None:
        st.info("No results yet. Run a comparison first, or preview the layout with demo data.")
        b1, b2, _ = st.columns([1.2, 1.6, 4])
        b1.button("＋ New comparison", type="primary", key="res_new", on_click=goto, args=("newrun",), **stretch(st.button))
        b2.button("Preview with demo data", key="res_demo", on_click=lambda: ss.update(job=build_demo_job(), cache={}), **stretch(st.button))
        return
    compared = job.comparison_performed
    if job.is_demo:
        html(C.notice_html("<b>● DEMO DATA</b> — illustrative values shown only to preview this page. They were not decoded from your files "
                           "and cannot be exported. Run a real comparison to replace them."))
        st.button("Clear demo", key="res_demo_clear", on_click=lambda: ss.update(job=None, cache={}))
    s = job.summary
    st.caption(f"{job.job_id} · {job.started_at} · {job.duration_s}s · {len(job.files)} file(s) · {len(job.dbc_names)} DBC(s)")
    if not compared:
        html(C.notice_html("<b>Java decoder: MOCK / UNAVAILABLE.</b> " + theme.escape(job.java_note or "") +
                           " No comparison was performed, so match / mismatch figures are not available. The Python decoding results below are real."))
    for f in job.files:
        if f.error or f.python_state == EngineState.ERROR:
            st.error(f"{f.name}: {f.error or f.python_error}")

    py = job.python_signal_table
    if compared:
        pct = s.get("match_percentage")
        html(C.metrics_row_html([
            ("Compared signals", s["signals_total"], "Signals in the decoded outputs", "≋", "muted small"),
            ("Matched samples", f"{pct}%" if pct is not None else "n/a", "Within configured tolerance", "✓", "trend"),
            ("Mismatch points", s["mismatch"], "Samples beyond tolerance", "⚠", "muted small"),
            ("Missing signals", s["signals_missing_in_python"] + s["signals_missing_in_java"], "Found in one output only", "∅", "muted small"),
        ]))
    else:
        html(C.metrics_row_html([
            ("Compared signals", "n/a", "Java output unavailable", "≋", "muted small"),
            ("Matched samples", "n/a", "No comparison performed", "✓", "muted small"),
            ("Decoded signals", int(py["signal"].nunique()) if len(py) else 0, "Python engine", "▤", "muted small"),
            ("Decoded samples", int(py["samples"].sum()) if len(py) else 0, "Python engine", "▧", "muted small"),
        ]))

    with panel("siglevel"):
        html(C.panel_head_html("Signal-level comparison" if compared else "Decoded signals (Python engine)",
                               "Sample values aligned by timestamp" if compared else "Real output of the Python reference decoder"))
        if compared:
            f1, f2, f3 = st.columns([2, 3, 1.2])
            choice = f1.selectbox("Show", list(FILTERS), key="w_res_filter", label_visibility="collapsed")
            text = f2.text_input("Search signal", key="w_res_text", placeholder="Search signal name...", label_visibility="collapsed")
            v = job.rows
            if FILTERS[choice]:
                v = v[v["status"].isin(FILTERS[choice])]
            if text.strip():
                v = v[v["signal"].str.contains(text.strip(), case=False, regex=False)]
            rank = {x: i for i, x in enumerate(Status.PRIORITY)}
            v = v.assign(_r=v["status"].map(rank), _d=-v["abs_diff"].fillna(-1)).sort_values(["_r", "_d", "signal", "timestamp"]).drop(columns=["_r", "_d"])
            f3.download_button("Export CSV (demo)" if job.is_demo else "Export CSV", v[rep.ROW_COLUMNS].to_csv(index=False).encode("utf-8"), f"{job.job_id}_signal_comparison.csv",
                               "text/csv", key="res_export", **stretch(st.download_button))
            st.caption(f"{len(v)} of {len(job.rows)} rows" + (" — first 400 shown, use Export CSV for all." if len(v) > 400 else ""))
            if v.empty:
                st.info("No rows for this filter.")
            else:
                html(C.comparison_table_html(v, starts=timeutil.file_starts(job)))
        else:
            text = st.text_input("Search signal", key="w_res_text_py", placeholder="Search signal name...", label_visibility="collapsed")
            pdf = rep.python_output_frame(job)
            if text.strip() and len(pdf):
                pdf = pdf[pdf["signal"].str.contains(text.strip(), case=False, regex=False)]
            if pdf.empty:
                st.warning("The Python engine decoded no signals. See *Run details* for the reason (e.g. wrong DBC for this log).")
            else:
                pdf = pdf.sort_values(["file", "signal", "timestamp"])
                st.caption(f"{len(pdf)} decoded samples" + (" — first 400 shown, download the CSV on Reports for all." if len(pdf) > 400 else "")
                           + " · Java output unavailable, so no MATCH / MISMATCH verdict yet.")
                html(C.python_only_table_html(pdf, starts=timeutil.file_starts(job)))

    if not job.is_demo:
      with panel("javacsv"):
        html(C.panel_head_html("Compare with Java decoder output (CSV)",
                               "See the real MATCH / MISMATCH table without the Java API: upload the samples your Java decoder produced.",
                               "Optional"))
        html(C.notice_html("CSV columns: <b>signal, timestamp, value</b> (+ optional <b>unit</b>; <b>file</b> is required when the job has several files). "
                           "Timestamps in seconds, same reference as the Python output. The <i>python_output.csv</i> from Reports shows the exact format.", "info"))
        up = st.file_uploader("Java output CSV", type=["csv"], key=f"java_csv_{ss.get('java_csv_n', 0)}", label_visibility="collapsed")
        if st.button("Compare with this CSV", type="primary", disabled=up is None, key="java_csv_go"):
            try:
                apply_java_csv(job, up.getvalue())
            except (JavaCsvError, ValueError) as exc:
                st.error(str(exc))
            else:
                ss.cache = {}
                st.rerun()
        if compared and "imported from an uploaded CSV" in (job.java_note or ""):
            st.caption("Currently showing a comparison against an uploaded CSV. Upload another file to replace it.")

    if compared:
        with panel("agree2"):
            body, rest = C.agreement_html(job, max_signals=None)
            html(body)

    with panel("graph"):
        html(C.panel_head_html("Signal graph", "Python reference" + (" vs Java decoder" if compared else " (Java MOCK / UNAVAILABLE)")))
        pairs = [(f, sg) for f, df in job.python_data.items() if df is not None and not df.empty for sg in sorted(df["signal"].unique())]
        if not pairs:
            st.info("No decoded signals to plot.")
        elif not rep.plotly_available():
            st.warning("Graphs need plotly: run ./setup.sh or `pip install plotly`.")
        else:
            files = sorted({p[0] for p in pairs})
            a, b = st.columns(2)
            fsel = a.selectbox("File", files, key="g_file")
            sigs = [sg for f, sg in pairs if f == fsel]
            dflt = default_graph_choice(job)
            idx = sigs.index(dflt[1]) if dflt and dflt[0] == fsel and dflt[1] in sigs else 0
            ssel = b.selectbox("Signal", sigs, index=idx, key=f"g_sig_{fsel}")
            st.plotly_chart(rep.build_signal_figure(job, fsel, ssel), **stretch(st.plotly_chart))

    with st.expander("Run details (files, warnings, DBC conflicts, log)"):
        st.dataframe(pd.DataFrame([{
            "file": f.name, "source": f.source, "size": human_size(f.size), "python": f.python_state or "-", "java": f.java_state or "-",
            "compared": f.comparison_performed, "seconds": f.seconds, "error": f.error or f.python_error or f.java_error or "",
        } for f in job.files]), hide_index=True, **stretch(st.dataframe))
        for name, w in [(f.name, w) for f in job.files for w in f.warnings][:100]:
            st.warning(f"{name}: {w}")
        if job.conflicts:
            st.dataframe(pd.DataFrame(job.conflicts), hide_index=True, **stretch(st.dataframe))
        st.json(job.settings)
        st.code("\n".join(job.log_lines) or "(empty)")


# ---------------------------------------------------------------------------
# Reports
# ---------------------------------------------------------------------------
def page_reports() -> None:
    ss = st.session_state
    job = ss.job
    if job is None:
        st.info("No job yet - nothing to export. Run a comparison first.")
        st.button("＋ New comparison", key="rep_new", on_click=goto, args=("newrun",))
        return
    if job.is_demo:
        st.warning("This is DEMO DATA (a layout preview). It cannot be exported. Run a real comparison first.")
        st.button("＋ New comparison", key="rep_new_demo", on_click=goto, args=("newrun",))
        return
    compared = rep.comparison_available(job)
    jid = job.job_id
    with panel("reports"):
        html(C.panel_head_html("Export comparison report", f"Outputs of {jid}."))
        if not compared:
            html(C.notice_html("<b>Java decoder: MOCK / UNAVAILABLE.</b> Comparison reports are unavailable; Python decoding outputs can still be downloaded."))
        a, b = st.columns(2, gap="medium")
        with a:
            html(C.source_card_html("▤", "CSV signal results", "Signal/sample rows, values, errors and status."))
            st.download_button("Download CSV", cached(f"{jid}:cmp", lambda: rep.comparison_csv(job)) if compared else b"", f"{jid}_comparison.csv",
                               "text/csv", disabled=not compared, key="dl_cmp")
            html(C.source_card_html("▦", "Excel report", "Workbook with summary, signals, comparison rows and Python signals."))
            try:
                st.download_button("Download Excel", cached(f"{jid}:xlsx", lambda: rep.excel_bytes(job)), f"{jid}_report.xlsx",
                                   "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", key="dl_xlsx")
            except Exception as exc:
                st.button("Download Excel", disabled=True, key="dl_xlsx_off")
                st.caption(f"Unavailable: {type(exc).__name__} (is openpyxl installed?)")
        with b:
            html(C.source_card_html("{ }", "JSON result", "Structured job metadata and comparison records."))
            st.download_button("Download JSON", cached(f"{jid}:json", lambda: rep.result_json(job)), f"{jid}_result.json", "application/json", key="dl_json")
            html(C.source_card_html("▧", "Full job ZIP", "Both engine outputs, comparison table, graphs and logs."))
            if st.button("Build ZIP", key="zip_build"):
                with st.spinner("Building ZIP (graphs can take a moment)..."):
                    ss.cache[f"{jid}:zip"] = rep.build_report_zip(job)
            z = ss.cache.get(f"{jid}:zip")
            if z:
                st.download_button(f"Download ZIP ({human_size(len(z))})", z, f"{jid}_report.zip", "application/zip", key="dl_zip", type="primary")
    with panel("outputs"):
        html(C.panel_head_html("Decoded output files", "Raw decoded samples of each engine."))
        c, d = st.columns(2, gap="medium")
        with c:
            html(C.source_card_html("🐍", "Python decoder output", "All decoded samples (file name, CAN ID, signal, time, decode value, unit)."))
            st.download_button("Download Python output CSV", cached(f"{jid}:py", lambda: rep.python_output_csv(job)), f"{jid}_python_output.csv", "text/csv", key="dl_py")
        with d:
            jcsv = rep.java_output_csv(job)
            html(C.source_card_html("☕", "Java decoder output", "Only available when the Java API returned real data."))
            st.download_button("Download Java output CSV", jcsv or b"", f"{jid}_java_output.csv", "text/csv", disabled=jcsv is None, key="dl_java")
            if jcsv is None:
                st.caption("Not available: no real Java output for this job.")


# ---------------------------------------------------------------------------
PAGES = {"dashboard": page_dashboard, "newrun": page_newrun, "s3": page_s3, "dbc": page_dbc,
         "results": page_results, "reports": page_reports}


def main() -> None:
    st.set_page_config(page_title=APP_NAME, page_icon="⌁", layout="wide", initial_sidebar_state="expanded")
    theme.inject(st)
    init_state()
    ss = st.session_state
    if ss.get("_goto"):
        ss["nav"] = ss.pop("_goto")
    keep_widget_state()
    if not ss.housekeeping:
        cleanup_stale_workspaces(CFG.temp_root or None, CFG.stale_temp_hours)
        ss.housekeeping = True
    sidebar()
    page = ss.nav if ss.nav in PAGES else "dashboard"
    topbar(page)
    PAGES[page]()
    html('<div class="footer-note">DecoderLab · All metrics and decoded values come from real runs of this installation.</div>')


main()
