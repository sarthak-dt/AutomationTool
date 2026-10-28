"""Clearly labelled DEMO job, only to preview the Results layout.

The values are illustrative and are NOT decoded from any file. They still go
through the real comparison engine, so the table/cards behave exactly like a
real run. A demo job is never written to the history and cannot be exported.
"""
from __future__ import annotations

from datetime import datetime

import numpy as np
import pandas as pd

from services import comparison_engine as ce
from services.job_runner import FileOutcome, JobResult
from services.models import ComparisonSettings, DecoderResult, EngineState, normalize_frame

DEMO_NOTE = "DEMO DATA: illustrative values, not decoded from any of your files."


def _signal(name: str, values, unit: str, discrete: bool = False) -> pd.DataFrame:
    t = np.round(np.arange(1.0, 1.0 + 0.2 * len(values), 0.2)[: len(values)], 3)
    return pd.DataFrame({"signal": name, "timestamp": t, "value": np.asarray(values, dtype=float),
                         "unit": unit, "is_discrete": discrete, "message": "Demo"})


def build_demo_job() -> JobResult:
    settings = ComparisonSettings(abs_tol=0.1, rel_tol=0.0, auto_discrete=False)
    speed = [63.0, 64.0, 65.0, 66.0, 67.0]
    py = normalize_frame(pd.concat([
        _signal("Engine_RPM", [1480, 1490, 1500, 1510, 1520], "rpm"),
        _signal("Vehicle_Speed", speed, "km/h"),
        _signal("Coolant_Temp", [87, 87.5, 88, 88, 88.5], "°C"),
        _signal("Throttle_Pos", [24.5, 24.6, 24.6, 24.7, 24.8], "%"),
        _signal("Brake_Status", [0, 0, 1, 1, 0], "", True),
    ], ignore_index=True))
    java_speed = list(speed)
    java_speed[2] -= 0.5  # the one illustrative mismatch
    java = normalize_frame(pd.concat([
        _signal("Engine_RPM", [1480, 1490, 1500, 1510, 1520], "rpm"),
        _signal("Vehicle_Speed", java_speed, "km/h"),
        _signal("Coolant_Temp", [87, 87.5, 88, 88, 88.5], "°C"),
        _signal("Throttle_Pos", [24.48, 24.58, 24.58, 24.68, 24.78], "%"),
    ], ignore_index=True))
    cmp = ce.compare_results(DecoderResult("python", EngineState.OK, py), DecoderResult("java", EngineState.OK, java), settings, "demo_log.mf4")
    rows, sig = ce.combine([cmp])
    now = datetime.now()
    job = JobResult(
        job_id="DEMO", started_at=now.isoformat(timespec="seconds"), finished_at=now.isoformat(timespec="seconds"),
        settings=settings.to_dict(), dbc_names=["demo.dbc"], rows=rows, signal_summary=sig,
        summary=ce.summarize(rows, performed=True), python_data={"demo_log.mf4": py}, java_data={"demo_log.mf4": java},
        java_state=EngineState.OK, java_note=DEMO_NOTE, is_demo=True,
    )
    job.files.append(FileOutcome(name="demo_log.mf4", source="demo", size=0, python_state=EngineState.OK, java_state=EngineState.OK,
                                 comparison_performed=True))
    job.log_lines.append(DEMO_NOTE)
    return job
