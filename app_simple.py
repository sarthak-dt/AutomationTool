"""Automotive Decoder Comparator - compact one-page UI.

    streamlit run app_simple.py

Flow:  1) choose Manual Upload or S3 Upload  ->  2) pick log + DBC files
       3) Run  ->  4) dashboard with results and downloads.

The multi-page UI that mirrors the HTML prototype is ``app.py``.
Nothing here shows sample data: results appear only after a real job ran.
"""
from __future__ import annotations

import hashlib
from datetime import datetime
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
import streamlit as st

from config import ALLOWED_MEASUREMENT_EXTS, APP_NAME, APP_VERSION, load_config
from services import history_service as hist
from services import report_service as rep
from services.dbc_service import (
    DbcFile,
    add_uploads,
    conflicts_summary,
    dbc_channel_bindings,
    dbc_channel_label,
    detect_conflicts_by_channel,
)
from services.file_service import ValidationError, cleanup_stale_workspaces, human_size, validate_upload
from services.java_decoder_client import JavaApiConfig, JavaDecoderClient
from services.job_runner import MeasurementRef, match_dbc_to_measurement, run_job
from services.logging_utils import clear_secrets, register_secret, setup_logging
from services.models import ComparisonSettings, EngineState, Status
from services.python_decoder import library_status
from services.s3_service import S3Config, S3Error, S3Object, S3Service
from ui import theme
from ui.compat import stretch

CFG = load_config()
setup_logging(CFG.log_level)

MANUAL, S3 = "Manual Upload", "S3 Upload"
MAX_ROWS = 2000
LOG_TYPES = [e.lstrip(".") for e in ALLOWED_MEASUREMENT_EXTS]
CAN_CHANNEL_NUMS = list(range(0, 256))


# ---------------------------------------------------------------------------
# state
# ---------------------------------------------------------------------------
def init_state() -> None:
    ss = st.session_state
    defaults = {
        "dbc_library": [], "dbc_seen": set(), "dbc_up_n": 0,
        "manual_files": [], "manual_seen": set(), "manual_up_n": 0,
        "s3_svc": None, "s3_listing": None, "s3_folder": "", "s3_sel": [],
        "job": None, "cache": {}, "housekeeping": False,
        "w_decode_channels": [],
        "s3_bucket": CFG.s3_bucket, "s3_region": CFG.s3_region, "s3_endpoint": CFG.s3_endpoint_url,
        "s3_port": CFG.s3_port, "s3_access": CFG.s3_access_key_id, "s3_secret": CFG.s3_secret_access_key,
        "s3_style": CFG.s3_addressing_style if CFG.s3_addressing_style in ("auto", "virtual", "path") else "auto",
        "s3_verify": True,
    }
    for k, v in defaults.items():
        ss.setdefault(k, v)


def java_client() -> JavaDecoderClient:
    return JavaDecoderClient(JavaApiConfig.from_app_config(CFG))


def usable_dbcs() -> list[DbcFile]:
    return [d for d in st.session_state.dbc_library if d.info.usable]


def fmt(x: Any) -> str:
    if x is None or (isinstance(x, float) and np.isnan(x)):
        return ""
    return f"{x:.8g}" if isinstance(x, (float, np.floating)) else str(x)


def cached(key: str, fn):
    c = st.session_state.cache
    if key not in c:
        c[key] = fn()
    return c[key]


# ---------------------------------------------------------------------------
# sidebar: status + settings
# ---------------------------------------------------------------------------
def sidebar() -> ComparisonSettings:
    with st.sidebar:
        st.markdown(
            f'<div class="brand"><div class="brand-icon">⌁</div><div><b>DecoderLab</b><small>{APP_NAME} v{APP_VERSION}</small></div></div>',
            unsafe_allow_html=True,
        )
        libs = library_status()
        missing = [k for k, v in libs.items() if v == "missing" and k != "plotly"]
        usable, reason = java_client().availability()
        rows = [
            ("ok" if not missing else "bad", "Python decoder ready" if not missing else "Missing: " + ", ".join(missing)),
            ("ok" if usable else "warn", "Java decoder connected" if usable else "Java decoder: MOCK / UNAVAILABLE"),
        ]
        st.markdown(
            '<div class="side-status">' + "".join(f'<div style="margin:3px 0"><span class="dot {d}"></span>{theme.escape(t)}</div>' for d, t in rows) + "</div>",
            unsafe_allow_html=True,
        )
        if missing:
            st.caption("Run `./setup.sh` to install the missing libraries.")
        with st.expander("Comparison settings"):
            abs_tol = st.number_input("Absolute tolerance", min_value=0.0, value=1e-6, format="%g", help="|python − java| ≤ this counts as MATCH.")
            rel_tol = st.number_input("Relative tolerance", min_value=0.0, value=1e-6, format="%g")
            time_tol = st.number_input("Timestamp tolerance [s]", min_value=0.0, value=1e-3, format="%g", help="Max time distance when pairing samples.")
            resample = st.checkbox("Resample to 1 second grid", value=False)
        decode_channels = st.multiselect(
            "CAN channels to decode",
            CAN_CHANNEL_NUMS,
            format_func=lambda channel: f"Channel {channel}",
            key="w_decode_channels",
            help="Select one or more channels; leave empty to decode all available channels.",
        )
        st.caption("MF4 channel numbers are preserved as stored, including physical channel 0.")
        st.caption("Full multi-page UI: `streamlit run app.py`.")
    return ComparisonSettings(
        abs_tol=float(abs_tol),
        rel_tol=float(rel_tol),
        time_tol=float(time_tol),
        resample_1s=bool(resample),
        decode_channels=tuple(sorted(int(channel) for channel in decode_channels)),
    )


# ---------------------------------------------------------------------------
# step 1/2: pick source and files
# ---------------------------------------------------------------------------
def dbc_section() -> None:
    ss = st.session_state
    st.markdown("**DBC files** (upload one or more from your computer)")
    ups = st.file_uploader("DBC files", type=["dbc"], accept_multiple_files=True, key=f"dbc_up_{ss.dbc_up_n}", label_visibility="collapsed")
    new = []
    for f in ups or []:
        fid = getattr(f, "file_id", None) or (f.name, f.size)
        if fid not in ss.dbc_seen:
            ss.dbc_seen.add(fid)
            new.append((f.name, f.getvalue()))
    if new:
        with st.spinner("Checking DBC files..."):
            ss.dbc_library, msgs = add_uploads(ss.dbc_library, new, CFG.max_dbc_files, CFG.max_dbc_bytes)
        for level, text in msgs:
            if level != "success":
                getattr(st, level)(text)
    lib: list[DbcFile] = ss.dbc_library
    if not lib:
        return
    st.dataframe(
        pd.DataFrame({
            "DBC": [d.name for d in lib],
            "Channels": [dbc_channel_label(d) for d in lib],
            "Status": [("✅ " if d.info.status == "VALID" else "⚠️ " if d.info.usable else "❌ ") + d.info.status.replace("_", " ").title() for d in lib],
            "Messages": [d.info.n_messages for d in lib],
            "Signals": [d.info.n_signals for d in lib],
            "Note": [d.info.error or "; ".join(d.info.warnings) for d in lib],
        }),
        hide_index=True, **stretch(st.dataframe),
    )
    c1, c2 = st.columns([3, 1])
    rm = c1.multiselect("Remove DBC", [d.name for d in lib], key="dbc_rm", label_visibility="collapsed", placeholder="Remove DBC files...")
    if c2.button("Remove", disabled=not rm, key="dbc_rm_btn"):
        ss.dbc_library = [d for d in lib if d.name not in rm]
        st.rerun()
    for dbc in usable_dbcs():
        key = f"w_dbc_channels_{dbc.name}"
        bindings = dbc_channel_bindings(dbc)
        ss.setdefault(key, [] if bindings is None else list(bindings))
        chosen = st.multiselect(
            f"Channels for {dbc.name}",
            CAN_CHANNEL_NUMS,
            key=key,
            help="Leave empty to apply this DBC to every channel.",
        )
        dbc.channel_bindings = tuple(sorted(int(channel) for channel in chosen))
        dbc.channel = dbc.channel_bindings[0] if dbc.channel_bindings else 0
    good = usable_dbcs()
    if len(good) > 1:
        conflicts = detect_conflicts_by_channel(good)
        s = conflicts_summary(conflicts)
        if s["error"] or s["warning"]:
            with st.expander(f"⚠️ {s['error']} conflict(s), {s['warning']} warning(s) between DBC files"):
                st.caption("If two DBCs define the same CAN ID, the first one in the list is used.")
                st.dataframe(pd.DataFrame([c.as_dict() for c in conflicts]), hide_index=True, **stretch(st.dataframe))


def manual_section() -> list[MeasurementRef]:
    ss = st.session_state
    st.markdown("**Measurement files** (MF4, BLF, ASC)")
    ups = st.file_uploader("Log files", type=LOG_TYPES, accept_multiple_files=True, key=f"manual_up_{ss.manual_up_n}", label_visibility="collapsed")
    seen_hashes = {hashlib.sha256(ref.data or b"").hexdigest() for ref in ss.manual_files}
    seen_names = {(ref.name.casefold(), int(ref.size)) for ref in ss.manual_files}
    s3_names = {(obj.name.casefold(), int(obj.size)) for obj in ss.s3_sel}
    skipped: dict[str, list[str]] = {}
    candidates: list[tuple[str, int, bytes, str]] = []
    for f in ups or []:
        try:
            clean = validate_upload(f.name, f.size, ALLOWED_MEASUREMENT_EXTS, CFG.max_measurement_bytes)
        except ValidationError as exc:
            st.error(str(exc))
            continue
        payload = f.getvalue()
        digest = hashlib.sha256(payload).hexdigest()
        candidates.append((clean, int(f.size), payload, digest))

    digest_counts: dict[str, int] = {}
    identity_counts: dict[tuple[str, int], int] = {}
    for clean, size, _, digest in candidates:
        digest_counts[digest] = digest_counts.get(digest, 0) + 1
        identity = (clean.casefold(), size)
        identity_counts[identity] = identity_counts.get(identity, 0) + 1
    duplicate_digests = {digest for digest, count in digest_counts.items() if count > 1}
    duplicate_identities = {identity for identity, count in identity_counts.items() if count > 1}

    for clean, size, payload, digest in candidates:
        identity = (clean.casefold(), size)
        fid = (clean, digest)
        if digest in duplicate_digests or identity in duplicate_identities:
            skipped.setdefault("Manual upload", []).append(clean)
            continue
        if fid in ss.manual_seen:
            continue  # Still present in Streamlit's uploader and already retained.
        if digest in seen_hashes or identity in seen_names:
            skipped.setdefault("Manual upload", []).append(clean)
            continue
        if identity in s3_names:
            skipped.setdefault("S3 selection", []).append(clean)
            continue
        seen_hashes.add(digest)
        seen_names.add(identity)
        ss.manual_seen.add(fid)
        ss.manual_files.append(MeasurementRef(name=clean, size=size, data=payload))
    for source_name, names in skipped.items():
        st.warning(f"Already present in {source_name}; duplicate file(s) skipped: " + ", ".join(dict.fromkeys(names)))
    if ss.manual_files:
        st.caption("Retained measurement files:")
        for ref in ss.manual_files:
            digest = hashlib.sha256(ref.data or b"").hexdigest()
            file_col, remove_col = st.columns([5, 1])
            file_col.caption(f"{ref.name} · {human_size(ref.size)}")
            remove_col.button(
                "Remove",
                key=f"simple_remove_manual_{digest}",
                on_click=remove_manual_file,
                args=(ref.name, digest),
                help=f"Remove {ref.name}",
            )
        st.button("Clear all uploaded measurement files", key="simple_manual_clear", on_click=clear_manual_files)
    return list(ss.manual_files)


def remove_manual_file(name: str, digest: str) -> None:
    ss = st.session_state
    ss.manual_files = [
        ref for ref in ss.manual_files
        if not (ref.name == name and hashlib.sha256(ref.data or b"").hexdigest() == digest)
    ]
    ss.manual_seen = {
        (ref.name, hashlib.sha256(ref.data or b"").hexdigest()) for ref in ss.manual_files
    }
    ss.manual_up_n += 1


def clear_manual_files() -> None:
    ss = st.session_state
    ss.manual_files = []
    ss.manual_seen = set()
    ss.manual_up_n += 1


def s3_clear() -> None:
    ss = st.session_state
    ss.s3_access = ss.s3_secret = ""
    ss.s3_svc = ss.s3_listing = None
    ss.s3_sel = []
    clear_secrets()


def s3_open(prefix: str) -> None:
    ss = st.session_state
    try:
        ss.s3_listing = ss.s3_svc.list_prefix(prefix)
        if ss.s3_listing.prefix != ss.s3_folder:
            ss.s3_sel = []  # a job uses files from one folder only
        ss.s3_folder = ss.s3_listing.prefix
    except (S3Error, ValidationError) as exc:
        st.session_state.s3_error = str(exc)


def s3_section() -> list[MeasurementRef]:
    ss = st.session_state
    with st.expander("S3 connection", expanded=ss.s3_svc is None):
        a, b = st.columns(2)
        a.text_input("Bucket name *", key="s3_bucket", placeholder="my-automotive-bucket")
        b.text_input("Region *", key="s3_region", placeholder="us-east-1")
        a.text_input("Endpoint URL", key="s3_endpoint", placeholder="https://s3.example.com (empty for AWS)")
        b.text_input("Port", key="s3_port", placeholder="443")
        a.text_input("Access key ID", key="s3_access", help="Leave both key fields empty to use an IAM role.")
        b.text_input("Secret access key", key="s3_secret", type="password")
        a.selectbox("Request style", ["auto", "virtual", "path"], key="s3_style", format_func=lambda v: {"auto": "Auto", "virtual": "Virtual-hosted", "path": "Path"}[v])
        b.checkbox("Verify TLS certificate", key="s3_verify")
        c1, c2, _ = st.columns([1, 1, 3])
        if c1.button("Connect", type="primary", key="s3_connect"):
            cfg = S3Config(
                bucket=ss.s3_bucket.strip(), region=ss.s3_region.strip(), endpoint_url=ss.s3_endpoint.strip(),
                port=str(ss.s3_port).strip(), access_key_id=ss.s3_access.strip(), secret_access_key=ss.s3_secret.strip(),
                addressing_style=ss.s3_style, verify_ssl=bool(ss.s3_verify),
            )
            register_secret(cfg.secret_access_key)
            register_secret(cfg.access_key_id)
            svc = S3Service(cfg, allowed_exts=ALLOWED_MEASUREMENT_EXTS, max_keys=CFG.max_s3_keys_listed)
            with st.spinner("Connecting..."):
                res = svc.test_connection()
            if res.ok:
                ss.s3_svc, ss.s3_sel, ss.s3_folder = svc, [], ""
                s3_open("")
                st.success(res.message)
            else:
                ss.s3_svc = ss.s3_listing = None
                st.error(res.message)
        c2.button("Clear credentials", key="s3_clear", on_click=s3_clear)
        st.caption("Credentials stay in memory for this session only and never appear in logs or reports.")

    if ss.s3_svc is None:
        st.info("Connect to S3 to browse your files.")
        return []
    err = ss.pop("s3_error", None)
    if err:
        st.error(f"Could not open the folder: {err}")
    listing = ss.s3_listing
    if listing is None:
        s3_open(ss.s3_folder)
        listing = ss.s3_listing
        if listing is None:
            return []

    here = listing.prefix
    st.markdown(f"**Folder:** `s3://{ss.s3_svc.config.bucket}/{here}`")
    n1, n2, n3 = st.columns([1, 3, 1])
    if here:
        n1.button("⬆ Up", key="s3_up", on_click=s3_open, args=("/".join(here.rstrip("/").split("/")[:-1]),))
    options = ["(stay in this folder)"] + [f[len(here):].rstrip("/") + "/" for f in listing.folders]
    choice = n2.selectbox("Open sub-folder", options, key=f"s3_sub_{here}", label_visibility="collapsed")
    if choice != options[0]:
        s3_open(here + choice)
        st.rerun()
    n3.button("↻ Refresh", key="s3_refresh", on_click=s3_open, args=(here,))
    if listing.truncated:
        st.warning(f"Listing truncated at {CFG.max_s3_keys_listed} entries.")
    if not listing.files:
        st.info(f"No {', '.join(ALLOWED_MEASUREMENT_EXTS)} files in this folder." + (f" ({listing.skipped_unsupported} other file(s) hidden.)" if listing.skipped_unsupported else ""))
        return []

    chosen_keys = {o.key for o in ss.s3_sel}
    all_on = st.checkbox("Select all files in this folder", key=f"s3_all_{here}")
    df = pd.DataFrame({
        "Use": [all_on or o.key in chosen_keys for o in listing.files],
        "File": [o.name for o in listing.files],
        "Size": [human_size(o.size) for o in listing.files],
        "Type": [o.ext.lstrip(".").upper() for o in listing.files],
        "Modified": [o.last_modified.strftime("%Y-%m-%d %H:%M") if o.last_modified else "" for o in listing.files],
    })
    edited = st.data_editor(
        df, hide_index=True, disabled=["File", "Size", "Type", "Modified"],
        column_config={"Use": st.column_config.CheckboxColumn("Use", default=False)},
        key=f"s3_tbl_{here}_{all_on}", **stretch(st.data_editor),
    )
    manual_names = {(ref.name.casefold(), int(ref.size)) for ref in ss.manual_files}
    ss.s3_sel = []
    s3_names: set[tuple[str, int]] = set()
    duplicates = []
    for obj, use in zip(listing.files, edited["Use"].tolist()):
        if not bool(use):
            continue
        identity = (obj.name.casefold(), int(obj.size))
        if identity in manual_names or identity in s3_names:
            duplicates.append(obj.name)
            continue
        s3_names.add(identity)
        ss.s3_sel.append(obj)
    if duplicates:
        st.warning("Already present in Manual upload or selected S3 files; duplicate file(s) skipped: "
                   + ", ".join(dict.fromkeys(duplicates)))
    if listing.skipped_unsupported:
        st.caption(f"{listing.skipped_unsupported} unsupported file(s) hidden.")
    return [MeasurementRef(name=o.name, size=o.size, s3_object=o) for o in ss.s3_sel]


# ---------------------------------------------------------------------------
# run
# ---------------------------------------------------------------------------
def run_section(source: str, refs: list[MeasurementRef], settings: ComparisonSettings) -> None:
    ss = st.session_state
    dbcs = usable_dbcs()
    total = sum(r.size for r in refs)
    c1, c2 = st.columns([1, 3])
    ready = bool(refs) and bool(dbcs)
    go = c1.button("▶ Run comparison", type="primary", disabled=not ready, key="run")
    c2.markdown(f"**{len(refs)}** log file(s) ({human_size(total)}) · **{len(dbcs)}** DBC file(s)" if ready else
                f"Select at least one log file and one valid DBC file to continue. ({len(refs)} log, {len(dbcs)} DBC)")
    if not go:
        return
    bar = st.progress(0.0, text="Starting...")
    try:
        job = run_job(
            refs, dbcs, settings, CFG, java_client(),
            s3_service=ss.s3_svc if source == S3 else None,
            s3_context={"bucket": ss.s3_svc.config.bucket, "folder": ss.s3_folder} if source == S3 and ss.s3_svc else {"source": "manual upload"},
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
    bar.empty()
    ss.job, ss.cache = job, {}
    hist.append_job(CFG.data_dir, job)


def match_section(source: str, refs: list[MeasurementRef], settings: ComparisonSettings) -> None:
    matchable_refs = [ref for ref in refs if Path(ref.name).suffix.lower() in {".mf4", ".blf", ".asc", ".log"}]
    dbcs = usable_dbcs()
    with st.expander("DBC Match to This File"):
        if not matchable_refs:
            st.info("Select an MF4, BLF, or ASC measurement file to check its CAN IDs against the uploaded DBC files.")
            return
        selected = st.selectbox("Measurement file to check", matchable_refs, format_func=lambda ref: ref.name, key="dbc_match_file")
        if st.button("Check DBC Match to This File", key="dbc_match_btn", disabled=not dbcs):
            try:
                with st.spinner("Reading measurement CAN IDs and matching DBC message IDs by channel..."):
                    matches = match_dbc_to_measurement(
                        selected,
                        dbcs,
                        CFG,
                        s3_service=st.session_state.s3_svc if source == S3 else None,
                        selected_channels=settings.decode_channels,
                    )
                if matches.empty:
                    st.info("No CAN IDs were found on the selected channels.")
                else:
                    st.dataframe(matches, hide_index=True, **stretch(st.dataframe))
                    counts = matches["status"].value_counts().to_dict()
                    st.caption(
                        "Matched: {matched} · Unmatched in measurement: {file} · Unmatched in DBC: {dbc}".format(
                            matched=counts.get("MATCHED", 0),
                            file=sum(count for status, count in counts.items() if status.startswith("UNMATCHED IN ") and status != "UNMATCHED IN DBC"),
                            dbc=counts.get("UNMATCHED IN DBC", 0),
                        )
                    )
            except (ValidationError, OSError, ValueError, ImportError) as exc:
                st.error(f"DBC Match to This File failed: {exc}")
            except Exception as exc:
                st.error(f"DBC Match to This File failed: {type(exc).__name__}: {exc}")


# ---------------------------------------------------------------------------
# step 3: dashboard
# ---------------------------------------------------------------------------
def display_rows(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    out.insert(0, "Result", out["status"].map(lambda s: f"{theme.STATUS_ICON.get(s, '')} {s}"))
    for c in ("python_value", "java_value"):
        out[c] = out[c].map(fmt)
    for c in ("timestamp", "abs_diff", "rel_diff", "tolerance"):
        out[c] = out[c].map(lambda x: "" if pd.isna(x) else f"{x:.6g}")
    return out.drop(columns=["status"]).rename(columns={"python_value": "python", "java_value": "java"})


def dashboard(job: Any) -> None:
    st.markdown("---")
    st.markdown("## Results")
    st.caption(f"{job.job_id} · {job.started_at} · {job.duration_s}s · {len(job.files)} file(s) · {len(job.dbc_names)} DBC(s)")
    compared = job.comparison_performed
    s = job.summary

    if not compared:
        theme.notice(
            st,
            "<b>Java decoder: MOCK / UNAVAILABLE.</b> " + theme.escape(job.java_note or "") +
            " No comparison was performed, so there are no Java values or match percentage. The Python decoding results below are real.",
            "warn", allow_html=True,
        )
    failed = [f for f in job.files if f.error or f.python_state == EngineState.ERROR]
    for f in failed:
        st.error(f"{f.name}: {f.error or f.python_error}")

    py = job.python_signal_table
    cols = st.columns(5)
    if compared:
        pct = s.get("match_percentage")
        cards = [
            ("Compared signals", s["signals_total"], "found in the decoded outputs", "≋"),
            ("Matched samples", f"{pct}%" if pct is not None else "n/a", "within configured tolerance", "✓"),
            ("Mismatch points", s["mismatch"], "samples beyond tolerance", "⚠"),
            ("Missing signals", s["signals_missing_in_python"] + s["signals_missing_in_java"], "found in one output only", "∅"),
            ("Not comparable / errors", s["not_comparable"] + s["error"], "see Details", "!"),
        ]
    else:
        cards = [("Match", "n/a", "no Java output", "✓"), ("Signals decoded", int(py["signal"].nunique()) if len(py) else 0, "Python engine", "≋"),
                 ("Samples", int(py["samples"].sum()) if len(py) else 0, "Python engine", "▤"),
                 ("Files OK", sum(1 for f in job.files if f.python_state == EngineState.OK), f"of {len(job.files)}", "▧"),
                 ("Files failed", len(failed), "see errors above", "!")]
    for c, (label, value, sub, icon) in zip(cols, cards):
        c.markdown(theme.metric_card(label, value, sub, icon), unsafe_allow_html=True)
    st.write("")

    tabs = st.tabs(["Overview", "Signals", "Graph", "Details", "Downloads"])
    with tabs[0]:
        overview_tab(job, compared)
    with tabs[1]:
        signals_tab(job, compared)
    with tabs[2]:
        graph_tab(job)
    with tabs[3]:
        details_tab(job)
    with tabs[4]:
        downloads_tab(job, compared)


PILL = {"MATCH": "pass", "MISMATCH": "fail", "MISSING_IN_PYTHON": "fail", "MISSING_IN_JAVA": "fail", "NOT_COMPARABLE": "info", "ERROR": "fail"}
PILL_TEXT = {"MATCH": "MATCH", "MISMATCH": "MISMATCH", "MISSING_IN_PYTHON": "MISSING IN PYTHON", "MISSING_IN_JAVA": "MISSING IN JAVA",
             "NOT_COMPARABLE": "NOT COMPARABLE", "ERROR": "ERROR"}
E = theme.escape


def val_with_unit(v: Any, unit: Any) -> str:
    t = fmt(v)
    if t == "":
        return "Missing"
    u = str(unit or "").strip()
    return f"{t} {u}" if u else t


def comparison_table_html(rows: pd.DataFrame, limit: int = 400) -> str:
    """Signal-level table in the same layout as the HTML prototype."""
    head = "<tr><th>Channel</th><th>CAN ID</th><th>Signal</th><th>Timestamp</th><th>Python reference</th><th>Java parser</th><th>Abs. error</th><th>Status</th></tr>"
    body = []
    for r in rows.head(limit).itertuples(index=False):
        ts = "—" if pd.isna(r.timestamp) else f"{r.timestamp:.3f} s"
        err = "—" if pd.isna(r.abs_diff) else f"{r.abs_diff:.4g}"
        py = "Missing" if r.status == Status.MISSING_IN_PYTHON else val_with_unit(r.python_value, r.unit_python)
        jv = "Missing" if r.status == Status.MISSING_IN_JAVA else val_with_unit(r.java_value, r.unit_java)
        note = f' title="{E(str(r.note))}"' if getattr(r, "note", "") else ""
        body.append(
            f"<tr{note}><td>{int(r.channel)}</td><td>{E(str(r.can_id or '—'))}</td><td>{E(r.signal)}</td><td>{ts}</td><td>{E(py)}</td><td>{E(jv)}</td><td>{err}</td>"
            f'<td><span class="status {PILL.get(r.status, "info")}">{PILL_TEXT.get(r.status, E(r.status))}</span></td></tr>'
        )
    return f'<div class="tbl-wrap"><table class="tbl"><thead>{head}</thead><tbody>{"".join(body)}</tbody></table></div>'


def agreement_panel(job: Any) -> None:
    """'Signal agreement' panel: overall % plus one progress bar per signal."""
    s = job.summary
    pct = s.get("match_percentage")
    kind = "pass" if (pct is not None and s["mismatch"] == 0 and s["error"] == 0) else "fail" if pct is not None else "info"
    label = "PASS" if kind == "pass" else "REVIEW" if kind == "fail" else "N/A"
    st.markdown(
        f'<div style="display:flex;justify-content:space-between;align-items:center"><b>Signal agreement</b><span class="status {kind}">{label}</span></div>'
        f'<div style="display:flex;align-items:baseline;gap:8px;margin:10px 0 6px"><span class="big-pct">{"n/a" if pct is None else f"{pct}%"}</span>'
        f'<span style="color:#667085;font-size:12px">sample agreement</span></div>'
        f'<div class="bar"><span style="width:{pct or 0}%"></span></div>',
        unsafe_allow_html=True,
    )
    sig = job.signal_summary
    if sig is None or sig.empty:
        return
    sig = sig[sig["signal"] != "(file)"].sort_values(["match_pct", "signal"], na_position="first")

    def row(r: Any) -> str:
        p = r.match_pct
        if pd.isna(p):
            txt, width, cls = PILL_TEXT.get(r.status, r.status).title(), 0, "red"
        else:
            txt, width, cls = f"{p:.1f}%", p, ("" if p >= 99 else "amber" if p >= 90 else "red")
        return (f'<div class="sig-row"><div class="h"><b>{E(r.signal)}</b><small>{txt}</small></div>'
                f'<div class="bar"><span class="{cls}" style="width:{width}%"></span></div></div>')

    rows = list(sig.itertuples(index=False))
    st.markdown("".join(row(r) for r in rows[:12]), unsafe_allow_html=True)
    if len(rows) > 12:
        with st.expander(f"Show all {len(rows)} signals"):
            st.markdown("".join(row(r) for r in rows[12:]), unsafe_allow_html=True)


def overview_tab(job: Any, compared: bool) -> None:
    if compared:
        left, right = st.columns([1, 1], gap="large")
        with left:
            agreement_panel(job)
        with right:
            st.markdown("**Rows by status**")
            counts = job.rows["status"].value_counts().reindex(list(Status.ALL)).fillna(0).astype(int)
            st.bar_chart(counts, **stretch(st.bar_chart))
    else:
        st.markdown("**Decoded signals per file (Python engine)**")
        py = job.python_signal_table
        if py.empty:
            st.warning("The Python engine decoded no signals. Check the *Details* tab for the reason (e.g. wrong DBC for this log).")
        else:
            per = py.groupby("file").agg(signals=("signal", "nunique"), samples=("samples", "sum")).reset_index()
            st.dataframe(per, hide_index=True, **stretch(st.dataframe))


FILTERS = {"All signals": None, "Mismatches only": [Status.MISMATCH], "Matched only": [Status.MATCH],
           "Missing only": [Status.MISSING_IN_JAVA, Status.MISSING_IN_PYTHON],
           "Other issues": [Status.NOT_COMPARABLE, Status.ERROR]}


def signals_tab(job: Any, compared: bool) -> None:
    if not compared:
        py = job.python_signal_table
        text = st.text_input("Search signal", key="py_text", placeholder="e.g. Engine_RPM")
        v = py[py["signal"].str.contains(text.strip(), case=False, regex=False)] if text.strip() and len(py) else py
        st.caption("Decoded signals (Python reference engine)")
        st.dataframe(v, hide_index=True, **stretch(st.dataframe))
        return
    st.markdown("**Signal-level comparison** · sample values aligned by timestamp")
    f1, f2, f3 = st.columns([2, 3, 2])
    choice = f1.selectbox("Show", list(FILTERS), key="f_choice")
    text = f2.text_input("Search signal", key="f_text", placeholder="e.g. Engine_RPM")
    v = job.rows
    if FILTERS[choice]:
        v = v[v["status"].isin(FILTERS[choice])]
    if text.strip():
        v = v[v["signal"].str.contains(text.strip(), case=False, regex=False)]
    rank = {x: i for i, x in enumerate(Status.PRIORITY)}
    v = v.assign(_r=v["status"].map(rank), _d=-v["abs_diff"].fillna(-1)).sort_values(["_r", "_d", "signal", "timestamp"]).drop(columns=["_r", "_d"])
    f3.download_button("⬇ Export CSV", v[rep.ROW_COLUMNS].to_csv(index=False).encode("utf-8"), f"{job.job_id}_signal_comparison.csv", "text/csv",
                       key="export_filtered", **stretch(st.download_button))
    st.caption(f"{len(v)} of {len(job.rows)} rows" + (" — first 400 shown, use Export CSV for all." if len(v) > 400 else ""))
    if v.empty:
        st.info("No rows for this filter.")
    else:
        st.markdown(comparison_table_html(v), unsafe_allow_html=True)


def graph_tab(job: Any) -> None:
    if not rep.plotly_available():
        st.warning("Graphs need plotly: run `./setup.sh` or `pip install plotly`.")
        return
    pairs = [(f, sg) for f, df in job.python_data.items() if df is not None and not df.empty for sg in sorted(df["signal"].unique())]
    if not pairs:
        st.info("No decoded signals to plot.")
        return
    files = sorted({p[0] for p in pairs})
    a, b = st.columns(2)
    fsel = a.selectbox("File", files, key="g_file")
    sigs = [sg for f, sg in pairs if f == fsel]
    default = 0
    if job.signal_summary is not None and len(job.signal_summary):
        bad = job.signal_summary[(job.signal_summary["file"] == fsel) & (job.signal_summary["status"] == Status.MISMATCH)]
        if len(bad) and bad.iloc[0]["signal"] in sigs:
            default = sigs.index(bad.iloc[0]["signal"])
    ssel = b.selectbox("Signal", sigs, index=default, key="g_sig")
    st.plotly_chart(rep.build_signal_figure(job, fsel, ssel), **stretch(st.plotly_chart))
    if not job.comparison_performed:
        st.caption("Only the Python trace is shown: the Java decoder is MOCK / UNAVAILABLE.")


def details_tab(job: Any) -> None:
    st.markdown("**Files**")
    st.dataframe(
        pd.DataFrame([{
            "file": f.name, "source": f.source, "size": human_size(f.size), "python": f.python_state or "-", "java": f.java_state or "-",
            "compared": f.comparison_performed, "seconds": f.seconds, "error": f.error or f.python_error or f.java_error or "",
        } for f in job.files]),
        hide_index=True, **stretch(st.dataframe),
    )
    warns = [(f.name, w) for f in job.files for w in f.warnings]
    if warns:
        st.markdown("**Warnings**")
        for name, w in warns[:100]:
            st.warning(f"{name}: {w}")
    if job.conflicts:
        st.markdown("**DBC conflicts**")
        st.dataframe(pd.DataFrame(job.conflicts), hide_index=True, **stretch(st.dataframe))
    with st.expander("Settings used"):
        st.json(job.settings)
    with st.expander("Run log"):
        st.code("\n".join(job.log_lines) or "(empty)")


def downloads_tab(job: Any, compared: bool) -> None:
    jid = job.job_id
    if not compared:
        st.caption("Comparison reports are unavailable because the Java decoder is MOCK / UNAVAILABLE. Python outputs can be downloaded.")
    st.markdown("**Reports**")
    if compared:
        csv_part_count = rep.comparison_csv_part_count(job)
        st.markdown("**Comparison CSV parts**")
        st.caption(f"Each losslessly gzip-compressed part contains up to {rep.COMPARISON_CSV_ROWS_PER_PART:,} rows and keeps Index numbering continuous.")
        part_columns = st.columns(min(3, csv_part_count))
        for part_number in range(1, csv_part_count + 1):
            with part_columns[(part_number - 1) % len(part_columns)]:
                part_key = f"{jid}:comparison_csv_part"
                if st.button(f"Prepare CSV part {part_number} of {csv_part_count}", key=f"prepare_csv_part_{part_number}"):
                    st.session_state.cache[part_key] = {
                        "part": part_number,
                        "data": rep.comparison_csv_part(job, part_number, compress=True),
                    }
                prepared = st.session_state.cache.get(part_key)
                if prepared and prepared.get("part") == part_number:
                    part_filename = f"{jid}_comparison.csv.gz" if part_number == 1 else f"{jid}_comparison_{part_number}.csv.gz"
                    st.download_button(
                        f"Download CSV part {part_number}", prepared["data"], part_filename, "application/gzip",
                        key=f"download_csv_part_{part_number}", **stretch(st.download_button),
                    )
    st.markdown("**Decoded output files**")
    d, e = st.columns(2)

    def output_parts(slot: Any, engine: str, label: str, part_count: int, builder: Any) -> None:
        if part_count == 0:
            slot.caption("Not available: no real Java output.")
            return
        slot.caption(f"Each losslessly gzip-compressed CSV part contains up to {rep.COMPARISON_CSV_ROWS_PER_PART:,} rows.")
        part_key = f"{jid}:{engine}_output_part"
        for part_number in range(1, part_count + 1):
            if slot.button(
                f"Prepare {label} output CSV part {part_number} of {part_count}",
                key=f"prepare_{jid}_{engine}_output_part_{part_number}",
                **stretch(st.button),
            ):
                st.session_state.cache[part_key] = {
                    "part": part_number,
                    "data": builder(part_number, compress=True),
                }
            prepared = st.session_state.cache.get(part_key)
            if prepared and prepared.get("part") == part_number:
                suffix = "" if part_number == 1 else f"_{part_number}"
                st.download_button(
                    f"⬇ Download {label} output CSV part {part_number} of {part_count}",
                    prepared["data"], f"{jid}_{engine}_output{suffix}.csv.gz", "application/gzip",
                    key=f"download_{jid}_{engine}_output_part_{part_number}", **stretch(st.download_button),
                )

    python_part_count = rep.python_output_csv_part_count(job)
    with d:
        output_parts(
            d, "python", "Python", python_part_count,
            lambda part, compress=False: rep.python_output_csv_part(job, part, compress=compress),
        )
    java_part_count = rep.java_output_csv_part_count(job)
    with e:
        output_parts(
            e, "java", "Java", java_part_count,
            lambda part, compress=False: rep.java_output_csv_part(job, part, compress=compress),
        )
    st.markdown("**Everything in one file**")
    if st.button("Build report ZIP", key="zip_build"):
        with st.spinner("Building ZIP (graphs can take a moment)..."):
            st.session_state.cache[f"{jid}:zip"] = rep.build_report_zip(job)
    z = st.session_state.cache.get(f"{jid}:zip")
    if z:
        st.download_button(f"⬇ Download ZIP ({human_size(len(z))})", z, f"{jid}_report.zip", "application/zip", key="dl_zip", type="primary")


# ---------------------------------------------------------------------------
def main() -> None:
    st.set_page_config(page_title=APP_NAME, page_icon="⌁", layout="wide", initial_sidebar_state="expanded")
    theme.inject(st)
    init_state()
    ss = st.session_state
    if not ss.housekeeping:
        cleanup_stale_workspaces(CFG.temp_root or None, CFG.stale_temp_hours)
        ss.housekeeping = True
    settings = sidebar()

    theme.page_header(st, "Decoder comparison", "Decode a log with your DBC files in the Python and Java decoders and compare the results.")
    source = st.radio("Source", [MANUAL, S3], horizontal=True, key="source", label_visibility="collapsed")

    left, right = st.columns(2, gap="large")
    with left:
        st.markdown("### 1 · Log files")
        refs = manual_section() if source == MANUAL else s3_section()
    with right:
        st.markdown("### 2 · DBC files")
        dbc_section()

    match_section(source, refs, settings)
    st.markdown("### 3 · Run")
    run_section(source, refs, settings)
    if ss.job is not None:
        dashboard(ss.job)


main()
