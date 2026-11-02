"""Tiny JSON history of past jobs (summaries only - never credentials or raw data)."""
from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Any

from services.logging_utils import redact_mapping

MAX_ENTRIES = 200


def history_path(data_dir: Path | str) -> Path:
    return Path(data_dir) / "job_history.json"


def load_history(data_dir: Path | str) -> list[dict[str, Any]]:
    p = history_path(data_dir)
    if not p.exists():
        return []
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
        return data if isinstance(data, list) else []
    except (OSError, json.JSONDecodeError):
        return []


def summarize_job(job: Any) -> dict[str, Any]:
    s = job.summary or {}
    return {
        "job_id": job.job_id,
        "started_at": job.started_at,
        "duration_s": job.duration_s,
        "files": [f.name for f in job.files],
        "n_files": len(job.files),
        "files_failed": sum(1 for f in job.files if f.error or f.python_state == "ERROR"),
        "n_dbcs": len(job.dbc_names),
        "java_state": job.java_state,
        "comparison_performed": bool(job.comparison_performed),
        "signals_total": s.get("signals_total", 0),
        "match": s.get("match", 0),
        "mismatch": s.get("mismatch", 0),
        "missing_in_python": s.get("missing_in_python", 0),
        "missing_in_java": s.get("missing_in_java", 0),
        "not_comparable": s.get("not_comparable", 0),
        "error": s.get("error", 0),
        "match_percentage": s.get("match_percentage"),
        "python_signals": int(sum(df["signal"].nunique() for df in job.python_data.values() if df is not None and not df.empty)),
    }


def append_job(data_dir: Path | str, job: Any) -> None:
    """Append a job summary (atomic write). Failures are swallowed: history is best-effort."""
    try:
        d = Path(data_dir)
        d.mkdir(parents=True, exist_ok=True)
        entries = load_history(d)
        entries.append(redact_mapping(summarize_job(job)))
        entries = entries[-MAX_ENTRIES:]
        fd, tmp = tempfile.mkstemp(dir=str(d), prefix=".hist_", suffix=".json")
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(entries, fh, indent=2)
        os.replace(tmp, history_path(d))
    except OSError:
        pass


def clear_history(data_dir: Path | str) -> None:
    try:
        history_path(data_dir).unlink(missing_ok=True)
    except OSError:
        pass


def aggregate(entries: list[dict[str, Any]]) -> dict[str, Any]:
    compared = [e for e in entries if e.get("comparison_performed")]
    return {
        "jobs": len(entries),
        "jobs_compared": len(compared),
        "jobs_clean": sum(1 for e in compared if e.get("mismatch", 0) == 0 and e.get("error", 0) == 0),
        "mismatches": sum(e.get("mismatch", 0) for e in compared),
        "files": sum(e.get("n_files", 0) for e in entries),
        "python_only_jobs": len(entries) - len(compared),
    }
