"""Python decoder: frame decoding logic (fakes) + real-library end-to-end (skipped if libs missing)."""
from types import SimpleNamespace as NS

import numpy as np
import pytest

from services import python_decoder as pd_
from services.models import ComparisonSettings, EngineState

from tests.test_dbc_service import SAMPLE_DBC


class FakeMsg:
    def __init__(self, frame_id, name, signals, ext=False, fail=False, scale=1.0):
        self.frame_id, self.name, self.is_extended_frame = frame_id, name, ext
        self.signals = signals
        self._fail, self._scale = fail, scale

    def decode(self, data, decode_choices=False, scaling=True):
        if self._fail:
            raise ValueError("bad length")
        assert decode_choices is False and scaling is True
        return {s.name: data[i] * self._scale for i, s in enumerate(self.signals)}


def fsig(name, unit="", length=8, choices=None):
    return NS(name=name, unit=unit, length=length, choices=choices)


def frame(ts, can_id, data, ext=False, err=False, remote=False):
    return NS(timestamp=ts, arbitration_id=can_id, is_extended_id=ext, data=bytes(data), is_error_frame=err, is_remote_frame=remote)


def lookup_of(*msgs):
    return pd_.build_message_lookup([NS(messages=list(msgs))])


def test_decode_frames_basic_relative_timestamps_and_units():
    m = FakeMsg(0x100, "Eng", [fsig("RPM", "rpm"), fsig("Flag", length=1)])
    frames = [frame(1000.0, 0x100, [10, 1]), frame(1000.5, 0x100, [20, 0]), frame(1001.0, 0x100, [30, 1])]
    df, stats, warns = pd_.decode_frames(frames, lookup_of(m))
    rpm = df[df["signal"] == "RPM"]
    assert rpm["timestamp"].tolist() == [0.0, 0.5, 1.0]
    assert rpm["value"].tolist() == [10.0, 20.0, 30.0]
    assert rpm["unit"].iloc[0] == "rpm" and rpm["message"].iloc[0] == "Eng"
    assert rpm["can_id"].tolist() == ["0x100", "0x100", "0x100"]
    assert not rpm["is_discrete"].iloc[0]
    assert df[df["signal"] == "Flag"]["is_discrete"].all()  # 1-bit signal
    assert stats["frames_decoded"] == 3 and stats["samples"] == 6 and not warns


def test_timestamp_origin_is_first_frame_even_if_undecodable():
    m = FakeMsg(0x100, "Eng", [fsig("RPM")])
    frames = [frame(50.0, 0x999, [0]), frame(52.0, 0x100, [5])]
    df, stats, _ = pd_.decode_frames(frames, lookup_of(m))
    assert df["timestamp"].tolist() == [2.0]
    assert stats["frames_without_definition"] == 1


def test_standard_vs_extended_ids_are_distinct():
    std = FakeMsg(0x100, "Std", [fsig("A")], ext=False)
    ext = FakeMsg(0x100, "Ext", [fsig("B")], ext=True)
    df, _, _ = pd_.decode_frames([frame(0, 0x100, [1], ext=False), frame(1, 0x100, [2], ext=True)], lookup_of(std, ext))
    assert set(df["signal"]) == {"A", "B"}


def test_can_id_tracks_each_sample_for_reused_signal_name():
    first = FakeMsg(0x100, "First", [fsig("Shared")])
    second = FakeMsg(0x200, "Second", [fsig("Shared")])
    df, _, _ = pd_.decode_frames(
        [frame(0, 0x100, [1]), frame(1, 0x200, [2])],
        lookup_of(first, second),
    )
    assert df["value"].tolist() == [1.0, 2.0]
    assert df["can_id"].tolist() == ["0x100", "0x200"]


def test_first_database_wins_for_same_id():
    a = FakeMsg(0x100, "FromA", [fsig("SigA")])
    b = FakeMsg(0x100, "FromB", [fsig("SigB")])
    lookup = pd_.build_message_lookup([NS(messages=[a]), NS(messages=[b])])
    df, _, _ = pd_.decode_frames([frame(0, 0x100, [1])], lookup)
    assert set(df["signal"]) == {"SigA"}


def test_error_remote_frames_skipped_and_decode_errors_reported():
    ok = FakeMsg(0x100, "Ok", [fsig("A")])
    bad = FakeMsg(0x200, "Bad", [fsig("B")], fail=True)
    frames = [frame(0, 0x100, [1]), frame(1, 0x100, [1], err=True), frame(2, 0x100, [1], remote=True), frame(3, 0x200, [1])]
    df, stats, warns = pd_.decode_frames(frames, lookup_of(ok, bad))
    assert stats["frames_skipped_error_or_remote"] == 2 and stats["decode_errors"] == 1
    assert len(df) == 1
    assert any("failed to decode" in w and "Bad" in w for w in warns)


def test_max_frames_truncates_with_warning():
    m = FakeMsg(0x100, "M", [fsig("A")])
    df, stats, warns = pd_.decode_frames([frame(i, 0x100, [i]) for i in range(10)], lookup_of(m), max_frames=4)
    assert len(df) == 4 and stats["truncated"]
    assert any("partial" in w for w in warns)


def test_no_matching_frames_warns_clearly():
    m = FakeMsg(0x100, "M", [fsig("A")])
    df, _, warns = pd_.decode_frames([frame(0, 0x555, [1])], lookup_of(m))
    assert df.empty
    assert any("No frame matched" in w for w in warns)


def test_empty_log_warns():
    df, stats, warns = pd_.decode_frames([], {})
    assert df.empty and any("no frames" in w for w in warns)


def test_choices_mark_signal_discrete():
    m = FakeMsg(0x100, "M", [fsig("Gear", choices={0: "P", 1: "D"})])
    df, _, _ = pd_.decode_frames([frame(0, 0x100, [1])], lookup_of(m))
    assert df["is_discrete"].iloc[0]


def test_mf4_extracted_signal_reads_can_id_from_asammdf_source():
    signal = NS(
        name="Engine_RPM",
        samples=np.array([800.0, 900.0]),
        timestamps=np.array([0.0, 0.5]),
        unit="rpm",
        source=NS(path="CAN0.CAN_DataFrame.ID=0x1ABC EXT=False", name="CAN0 message ID=0x1ABC"),
    )
    warnings = []
    decoded = pd_._signals_to_frame([signal], warnings)
    assert decoded["can_id"].tolist() == ["0x1ABC", "0x1ABC"]
    assert not warnings


def test_mf4_channel_without_can_source_has_empty_can_id():
    signal = NS(
        name="Engine_RPM",
        samples=np.array([800.0]),
        timestamps=np.array([0.0]),
        unit="rpm",
    )
    decoded = pd_._signals_to_frame([signal], [])
    assert decoded["can_id"].tolist() == [""]


# ---------------------------------------------------------------- decode_measurement guards
def test_unsupported_extension_and_missing_file(tmp_path):
    s = ComparisonSettings()
    r = pd_.decode_measurement(tmp_path / "x.xyz", [], s)
    assert r.state == EngineState.ERROR and "Unsupported" in r.error
    r = pd_.decode_measurement(tmp_path / "x.asc", [tmp_path / "a.dbc"], s)
    assert r.state == EngineState.ERROR and "not found" in r.error.lower()


def test_can_log_requires_dbc(tmp_path):
    f = tmp_path / "x.asc"
    f.write_text("")
    r = pd_.decode_measurement(f, [], ComparisonSettings())
    assert r.state == EngineState.ERROR and "DBC" in r.error


def test_missing_libraries_give_clear_errors(tmp_path, monkeypatch):
    import builtins

    real_import = builtins.__import__

    def fake_import(name, *a, **k):
        if name in ("can", "asammdf"):
            raise ImportError(name)
        return real_import(name, *a, **k)

    f = tmp_path / "x.asc"
    f.write_text("")
    d = tmp_path / "a.dbc"
    d.write_text("x")
    monkeypatch.setattr(builtins, "__import__", fake_import)
    assert "python-can is not installed" in pd_.decode_measurement(f, [d], ComparisonSettings()).error
    m = tmp_path / "x.mf4"
    m.write_bytes(b"x")
    assert "asammdf is not installed" in pd_.decode_measurement(m, [d], ComparisonSettings()).error


# ---------------------------------------------------------------- real end-to-end (skipped without libs)
ASC_TEMPLATE = """date Tue Oct 27 10:00:00.000 am 2026
base hex  timestamps absolute
internal events logged
// version 9.0.0
Begin Triggerblock Tue Oct 27 10:00:00.000 am 2026
   0.000000 Start of measurement
{lines}
End TriggerBlock
"""


def _asc_line(t, can_id, data):
    return f"   {t:.6f} 1  {can_id:X}             Rx   d {len(data)} " + " ".join(f"{b:02X}" for b in data)


def test_real_asc_end_to_end(tmp_path):
    pytest.importorskip("cantools")
    pytest.importorskip("can")
    # EngineData: RPM = raw*0.25 (16 bit LE), Coolant = raw-40, Brake bit 24
    lines = []
    for i in range(5):
        raw_rpm = 4 * (800 + 100 * i)  # -> 800, 900, ... rpm
        data = [raw_rpm & 0xFF, raw_rpm >> 8, 40 + 50 + i, 1 if i >= 3 else 0, 0, 0, 0, 0]
        lines.append(_asc_line(1.0 + i * 0.5, 0x100, data))
    asc = tmp_path / "t.asc"
    asc.write_text(ASC_TEMPLATE.format(lines="\n".join(lines)))
    dbc = tmp_path / "a.dbc"
    dbc.write_text(SAMPLE_DBC)
    res = pd_.decode_measurement(asc, [dbc], ComparisonSettings())
    assert res.state == EngineState.OK, res.error
    rpm = res.data[res.data["signal"] == "Engine_RPM"].sort_values("timestamp")
    assert rpm["value"].tolist() == pytest.approx([800, 900, 1000, 1100, 1200])
    assert rpm["timestamp"].tolist() == pytest.approx([0, 0.5, 1.0, 1.5, 2.0])
    assert rpm["unit"].iloc[0] == "rpm"
    temp = res.data[res.data["signal"] == "Coolant_Temp"]["value"].tolist()
    assert temp == pytest.approx([50, 51, 52, 53, 54])
    assert res.data[res.data["signal"] == "Brake_Active"]["value"].tolist() == [0, 0, 0, 1, 1]


def test_real_mf4_decoded_channels_read_as_is(tmp_path):
    asammdf = pytest.importorskip("asammdf")
    from asammdf import MDF, Signal

    t = np.arange(0, 3, 0.5)
    mdf = MDF()
    mdf.append([Signal(samples=t * 100, timestamps=t, name="Engine_RPM", unit="rpm")])
    path = tmp_path / "decoded.mf4"
    mdf.save(str(path), overwrite=True)
    mdf.close()
    res = pd_.decode_measurement(path, [tmp_path / "unused.dbc"], ComparisonSettings())
    assert res.state == EngineState.OK, res.error
    assert any("NOT applied" in w for w in res.warnings)
    got = res.data[res.data["signal"] == "Engine_RPM"]
    assert got["value"].tolist() == pytest.approx((t * 100).tolist())
    assert got["unit"].iloc[0] == "rpm"
