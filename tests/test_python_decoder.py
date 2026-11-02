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


# ------------------------------------------------------------------ channels
def cframe(ts, can_id, data, channel):
    f = frame(ts, can_id, data)
    f.channel = channel
    return f


def test_frame_channel_conversion():
    assert pd_.frame_channel(NS(channel=0)) == 1 and pd_.frame_channel(NS(channel=1)) == 2
    assert pd_.frame_channel(NS(channel="can0")) == 1 and pd_.frame_channel(NS(channel="vcan3")) == 4
    assert pd_.frame_channel(NS(channel=None)) == 0 and pd_.frame_channel(NS(channel="can")) == 0
    assert pd_.frame_channel(NS()) == 0 and pd_.frame_channel(NS(channel=True)) == 0


def test_each_dbc_decodes_only_its_channel_and_rows_carry_channel_and_can_id():
    db1 = NS(messages=[FakeMsg(0x100, "M1", [fsig("Sig")], scale=1.0)])
    db2 = NS(messages=[FakeMsg(0x100, "M2", [fsig("Sig")], scale=10.0)])  # same ID, other DBC, other channel
    lk = pd_.ChannelLookups([db1, db2], [1, 2])
    frames = [cframe(0.0, 0x100, [5], 0), cframe(0.1, 0x100, [5], 1), cframe(0.2, 0x100, [6], 0)]  # python-can ch 0 = channel 1
    df, stats, warns = pd_.decode_frames(frames, lk)
    ch1 = df[df["channel"] == 1].sort_values("timestamp")
    ch2 = df[df["channel"] == 2]
    assert ch1["value"].tolist() == [5.0, 6.0] and ch1["message"].iloc[0] == "M1"
    assert ch2["value"].tolist() == [50.0] and ch2["message"].iloc[0] == "M2"
    assert set(df["can_id"]) == {"0x100"} and stats["channels_seen"] == [1, 2]
    assert not warns


def test_dbc_bound_to_channel_ignores_other_channels_and_any_dbc_applies_everywhere():
    db1 = NS(messages=[FakeMsg(0x100, "M1", [fsig("A")])])
    db_any = NS(messages=[FakeMsg(0x200, "Mx", [fsig("B")])])
    lk = pd_.ChannelLookups([db1, db_any], [1, 0])
    frames = [cframe(0.0, 0x100, [1], 1), cframe(0.1, 0x100, [1], 0), cframe(0.2, 0x200, [7], 1), cframe(0.3, 0x200, [7], 0)]
    df, stats, _ = pd_.decode_frames(frames, lk)
    assert df[df["signal"] == "A"]["channel"].tolist() == [1]  # 0x100 on channel 2 has no definition
    assert sorted(df[df["signal"] == "B"]["channel"].tolist()) == [1, 2]
    assert stats["frames_without_definition"] == 1


def test_unknown_channel_frames_use_only_any_dbcs_and_wrong_binding_is_explained():
    db1 = NS(messages=[FakeMsg(0x100, "M1", [fsig("A")])])
    lk = pd_.ChannelLookups([db1], [5])
    df, stats, warns = pd_.decode_frames([cframe(0.0, 0x100, [1], 0)], lk)
    assert df.empty and any("DBC channel binding" in w for w in warns)


def test_channel_lookups_validation_and_defaults():
    with pytest.raises(ValueError):
        pd_.ChannelLookups([NS(messages=[])], [1, 2])
    lk = pd_.ChannelLookups([NS(messages=[FakeMsg(1, "m", [fsig("a")])])])
    assert not lk.uses_channels and (1, False) in lk.get(3)
    zero = NS(messages=[FakeMsg(1, "zero", [fsig("a")])])
    any_channel = NS(messages=[FakeMsg(2, "any", [fsig("b")])])
    lk = pd_.ChannelLookups([zero, any_channel], [(0,), None])
    assert lk.get(0)[(1, False)].name == "zero"
    assert lk.get_unknown_channel() == {(2, False): any_channel.messages[0]}


def test_signal_meta_unique_vs_ambiguous():
    db = NS(messages=[FakeMsg(0x10, "M1", [fsig("A"), fsig("Dup")]), FakeMsg(0x20, "M2", [fsig("Dup")])])
    meta = pd_._signal_meta([db])
    assert meta["A"] == ("0x10", "M1") and meta["Dup"] == ("", "")


def test_mf4_can_channels_reads_bus_channel_and_degrades_to_empty():
    class Sig:
        def __init__(self, v):
            self.samples = np.array(v)

    class Mdf:
        def __init__(self, v=None, fail=False):
            self.v, self.fail = v, fail

        def get(self, name):
            if self.fail:
                raise KeyError(name)
            assert name == "CAN_DataFrame.BusChannel"
            return Sig(self.v)

    assert pd_.mf4_can_channels(Mdf([1, 1, 2, 2, 1])) == [1, 2]
    assert pd_.mf4_can_channels(Mdf([0, 0])) == []
    assert pd_.mf4_can_channels(Mdf(fail=True)) == []

    class MdfWithBusMap:
        bus_logging_map = {"CAN": {0: {0x100: 0}, 2: {0x200: 1}}}

    assert pd_.mf4_can_channels(MdfWithBusMap()) == [0, 2]


def test_discover_can_channels_returns_only_channels_found_in_measurement(tmp_path, monkeypatch):
    import sys
    from types import ModuleType

    class FakeMdf:
        bus_logging_map = {"CAN": {1: {}, 2: {}, 3: {}, 6: {}, 7: {}, 8: {}}}

        def __init__(self, _path):
            pass

        def close(self):
            pass

    fake_asammdf = ModuleType("asammdf")
    fake_asammdf.MDF = FakeMdf
    monkeypatch.setitem(sys.modules, "asammdf", fake_asammdf)
    assert pd_.discover_can_channels(tmp_path / "channels.mf4") == [1, 2, 3, 6, 7, 8]


def _write_raw_can_mf4(path, frames):
    can = pytest.importorskip("can")
    writer = can.MF4Writer(path)
    start = 1_800_000_000.0
    try:
        for index, frame_data in enumerate(frames):
            channel, can_id, data, *extended = frame_data
            writer.on_message_received(
                can.Message(
                    timestamp=start + index * 0.1,
                    arbitration_id=can_id,
                    data=bytes(data),
                    channel=channel,
                    is_extended_id=bool(extended[0]) if extended else False,
                )
            )
    finally:
        writer.stop()


def test_real_mf4_any_dbc_decodes_each_actual_channel_once_and_selection_filters(tmp_path):
    pytest.importorskip("asammdf")
    pytest.importorskip("can")
    path = tmp_path / "multichannel.mf4"
    dbc = tmp_path / "vehicle.dbc"
    dbc.write_text(SAMPLE_DBC)
    payload = [0x80, 0x0C, 90, 1, 0, 0, 0, 0]
    _write_raw_can_mf4(path, [(channel, 0x100, payload) for channel in (0, 1, 2)])

    all_channels = pd_.decode_measurement(path, [dbc], ComparisonSettings())
    assert all_channels.state == EngineState.OK, all_channels.error
    rpm = all_channels.data[all_channels.data["signal"] == "Engine_RPM"]
    assert set(rpm["channel"]) == {0, 1, 2}
    assert len(rpm) == 3
    assert all_channels.stats["channels_in_file"] == [0, 1, 2]
    assert set(rpm["can_id"]) == {"0x100"}

    only_zero = pd_.decode_measurement(
        path, [dbc], ComparisonSettings(decode_channels=(0,))
    )
    zero_rpm = only_zero.data[only_zero.data["signal"] == "Engine_RPM"]
    assert set(zero_rpm["channel"]) == {0}
    assert len(zero_rpm) == 1

    selected = pd_.decode_measurement(
        path, [dbc], ComparisonSettings(decode_channels=(0, 2))
    )
    selected_rpm = selected.data[selected.data["signal"] == "Engine_RPM"]
    assert set(selected_rpm["channel"]) == {0, 2}
    assert len(selected_rpm) == 2


def test_real_mf4_uses_each_assigned_dbc_on_its_channel_including_channel_zero(tmp_path):
    pytest.importorskip("asammdf")
    path = tmp_path / "assigned.mf4"
    dbc0 = tmp_path / "channel0.dbc"
    dbc1 = tmp_path / "channel1.dbc"
    dbc0.write_text(SAMPLE_DBC.replace("Engine_RPM", "Channel0_RPM"))
    dbc1.write_text(SAMPLE_DBC.replace("Engine_RPM", "Channel1_RPM"))
    payload = [0x80, 0x0C, 90, 1, 0, 0, 0, 0]
    _write_raw_can_mf4(path, [(0, 0x100, payload), (1, 0x100, payload)])

    result = pd_.decode_measurement(
        path,
        [dbc0, dbc1],
        ComparisonSettings(),
        dbc_channels=[(0,), (1,)],
    )
    assert result.state == EngineState.OK, result.error
    rpm = result.data[result.data["signal"].str.endswith("_RPM")]
    assert set(zip(rpm["channel"], rpm["signal"])) == {
        (0, "Channel0_RPM"),
        (1, "Channel1_RPM"),
    }


def test_real_mf4_dbc_match_reports_matched_and_unmatched_ids_per_channel(tmp_path):
    pytest.importorskip("asammdf")
    path = tmp_path / "match.mf4"
    dbc = tmp_path / "vehicle.dbc"
    dbc.write_text(SAMPLE_DBC)
    payload = [0x80, 0x0C, 90, 1, 0, 0, 0, 0]
    _write_raw_can_mf4(
        path,
        [(0, 0x100, payload), (1, 0x100, payload), (1, 0x100, payload, True), (1, 0x999, payload)],
    )

    result = pd_.match_mf4_dbcs(path, [dbc])
    rows = result.set_index(["channel", "can_id", "extended"])["status"].to_dict()
    assert rows[(0, "0x100", False)] == "MATCHED"
    assert rows[(1, "0x100", False)] == "MATCHED"
    assert rows[(1, "0x100", True)] == "UNMATCHED IN MF4"
    assert rows[(1, "0x999", False)] == "UNMATCHED IN MF4"
    assert rows[(0, "0x200", False)] == "UNMATCHED IN DBC"
    assert rows[(1, "0x200", False)] == "UNMATCHED IN DBC"

    only_channel_one = pd_.match_mf4_dbcs(path, [dbc], selected_channels=(1,))
    assert set(only_channel_one["channel"]) == {1}
    assert "0x999" in set(only_channel_one["can_id"])
    missing = pd_.match_mf4_dbcs(path, [dbc], selected_channels=(7,))
    assert missing.iloc[0]["status"] == "CHANNEL NOT PRESENT IN MF4"

    from config import AppConfig
    from services.dbc_service import build_dbc_file
    from services.job_runner import MeasurementRef, match_dbc_to_measurement

    uploaded = MeasurementRef(name=path.name, size=path.stat().st_size, data=path.read_bytes())
    uploaded_dbc = build_dbc_file("vehicle.dbc", "vehicle.dbc", dbc.read_bytes())
    wrapped = match_dbc_to_measurement(
        uploaded,
        [uploaded_dbc],
        AppConfig(temp_root=str(tmp_path / "work")),
        selected_channels=(0, 1),
    )
    assert set(wrapped["channel"]) == {0, 1}
    assert "MATCHED" in set(wrapped["status"])
