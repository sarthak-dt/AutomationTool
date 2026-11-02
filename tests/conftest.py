import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from services.models import DecoderResult, EngineState, normalize_frame  # noqa: E402


def make_frame(signal="Sig", t=None, v=None, unit="", discrete=False, message=""):
    t = np.arange(0, 5, 0.5) if t is None else np.asarray(t, dtype=float)
    v = (t * 10.0) if v is None else v
    return pd.DataFrame(
        {
            "signal": signal,
            "timestamp": t,
            "value": v,
            "unit": unit,
            "is_discrete": discrete,
            "message": message,
        }
    )


def result(engine, *frames, state=EngineState.OK, error=None):
    if frames:
        data = normalize_frame(pd.concat(frames, ignore_index=True))
    else:
        data = normalize_frame(None)
    return DecoderResult(engine=engine, state=state, data=data, error=error)


@pytest.fixture
def mk():
    return make_frame


@pytest.fixture
def res():
    return result
