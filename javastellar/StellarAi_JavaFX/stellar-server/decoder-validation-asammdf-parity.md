# Java decoder API versus asammdf

Schema 2.0 was not changed. No Java source was edited for this check.

Verdict: **PARTIAL**. Comparable MF4 numeric and CC type 8 text samples match. KILOMETRAGE stays one Java series for three asammdf channel groups. asammdf 8.8.28 does not decode ASC. A Tata Sierra file was not found.

## Environment

Isolated virtual environment, not part of the Java build:

```text
C:\Users\DT - User\AppData\Local\Programs\Python\Python313\python.exe -m venv D:\stellar-decoder-qa\parity-venv
D:\stellar-decoder-qa\parity-venv\Scripts\python.exe -m pip install --upgrade pip
D:\stellar-decoder-qa\parity-venv\Scripts\python.exe -m pip install asammdf
```

| Component | Version |
| --- | --- |
| Python | 3.13.15 |
| asammdf | 8.8.28 |
| NumPy | 2.5.4 |

Environment path: `D:\stellar-decoder-qa\parity-venv`.

## Inputs

No file named Tata Sierra was present under `DriveTechData`. These real recordings were used instead. SHA-256 was taken before the calls, and size plus modification time were unchanged afterwards.

| Role | File | SHA-256 |
| --- | --- | --- |
| Native MDF channels | `C:\Users\DT - User\Downloads\DriveTechData\dbcfiles\b1-3.mf4` | `4d7c3d01433f96e9ea5dd5e08f25cd9ba30fd4ead35436f1f2f80ef1f23bb2a0` |
| CAN bus logging | `C:\Users\DT - User\Downloads\DriveTechData\MF4Files\Bengaluru_MF4\MF4_KA04NC7760_Bengaluru_0002360_20230814_171438.mf4` | `e892a6d2d6cc07741c15822b101d777e1f10c743903c794608903f3fa07b2972` |
| DBC for that bus log | `C:\Users\DT - User\Downloads\DriveTechData\dbcfiles\2.dbc` | `d9ee1e1e827b40e6652a847aa3719eb2e7843df17742d1a6ad6dbb66ad88d335` |

The bus file has 1,051,734 CAN frames on bus channels 1, 2 and 3. `HVBAT_STATE` exists only on channel 1 in `2.dbc`, message `HVBAT_205h`, id `0x205` (517). `1.dbc` and `3.dbc` do not define it.

## Java request

`POST http://127.0.0.1:8091/api/v1/decoder/validate` on the decoder profile. The Python client `stellar-server/decoder_validation_client.py` followed every cursor. It was not modified.

Bus request:

```json
{
  "filePath": "C:\\Users\\DT - User\\Downloads\\DriveTechData\\MF4Files\\Bengaluru_MF4\\MF4_KA04NC7760_Bengaluru_0002360_20230814_171438.mf4",
  "dbcPath": "C:\\Users\\DT - User\\Downloads\\DriveTechData\\dbcfiles\\2.dbc",
  "signal": "HVBAT_STATE",
  "pageSize": 10000
}
```

The client made 4 pages and assembled 34,557 samples with indexes `0..34556`.

## asammdf calls

Native channels, no raster:

```text
MDF.get(name, group=<channel group>, raw=False)
MDF.get(name, group=<channel group>, raw=True)
```

`ignore_invalidation_bits=False`. No `raster`.

Bus signal:

```text
MDF.get_bus_signal(
    "CAN",
    "CAN1.HVBAT_205h.HVBAT_STATE",
    database=<2.dbc>,
    raw=False,
    ignore_invalidation_bits=False,
    ignore_value2text_conversion=True)
```

The same call with `raw=True` supplied the integer codes. `CAN2` and `CAN3` do not contain this message.

Timestamp tolerance is 1 nanosecond, for the conversion of asammdf's float seconds. Physical tolerance is an absolute difference above `1e-6` and a relative difference above `1e-9`. Raw integers are exact. Series are compared in stored order.

## Results

| Signal | Java identity | Java count | asammdf count | Max timestamp error | Raw | Physical | Result |
| --- | --- | --- | ---: | ---: | --- | --- | --- |
| `HVBAT_STATE` | `HVBAT_STATE\|DBC_CAN\|HVBAT_205h\|517\|1\|...mf4\|2.dbc` | 34,557 | 34,557 | 6.1e-5 ns | exact | exact, abs diff 0 | MATCH |
| `DRV_CLUSTER_DSPEED` | `DRV_CLUSTER_DSPEED\|MF4_NATIVE\|MF4_MEASUREMENT\|0\|1\|b1-3.mf4\|` | 5,928 | 5,928 | 1.2e-4 ns | exact | exact, unit `Km/h` | MATCH |
| `BCK_LGHT_ILLUM_LVL` | same native pattern | 5,928 | 5,928 | 1.2e-4 ns | exact | exact, unit `%` | MATCH |
| `KILOMETRAGE` | one native identity | 1,749 | 576 + 594 + 579 | 1.2e-4 ns on the concatenation | exact on the concatenation | exact, unit `km` | LIMITATION |
| `FRPK` | one native identity | 2,966 | 2,966 | 1.2e-4 ns | exact | text match, `VALID`, sample `NaN` | MATCH |
| `INFO_CRASH` | one native identity | 2,967 | 2,967 | 1.2e-4 ns | exact | text match, `VALID`, sample `NaN` | MATCH |
| `RESET_AFE_TRIP` | one native identity | 11,840 | 11,840 | 1.2e-4 ns | exact | text match, `VALID`, sample `NaN` | MATCH |

`HVBAT_STATE` is channel 1, message `HVBAT_205h`, CAN id 517. That is the same signal asammdf addresses as `CAN1.HVBAT_205h.HVBAT_STATE`.

`KILOMETRAGE` is three asammdf channel groups, 5, 20 and 68. Java returns one series. Concatenating those groups in group order reproduces Java's timestamps, raw values and physical values, including unit `km`. The series are merged. This identity difference is unchanged.

A later file decode on 2026-10-27, asammdf 8.8.28, schema 2.0, application version 1.0.4:

| Signal | Samples | Text mismatch | Raw mismatch | Time mismatch | Validity |
| --- | ---: | ---: | ---: | ---: | --- |
| `FRPK` | 2,966 | 0 | 0 | 0 | `VALID` |
| `INFO_CRASH` | 2,967 | 0 | 0 | 0 | `VALID` |
| `RESET_AFE_TRIP` | 11,840 | 0 | 0 | 0 | `VALID` |
| `HVBAT_STATE` | 34,557 | n/a | 0 | 0 | physical difference 0 |
| `KILOMETRAGE` | 1,749 | n/a | 0 on the concatenation | 0 | unit `km` |

`FRPK` labels are `Serre II Tight` for raw 1 and `Desserre II Loose` for raw 0. The physical sample stays non-finite. HVBAT and the three text channels have an empty unit on both sides.

asammdf has no ASC module. Opening `ASCII_eCC21_KA04NC7760_Bengaluru_09_08_2023_0002360_20230814_145851.asc` raises `MdfException` because the header is `b'date Mon'`, not an MDF magic. ASC results are not an asammdf comparison.

The earlier all-signals cursor failure on `b1-3.mf4` was a storage page read. After that fix, a full walk returned 140 pages, 136 signals, and 1,393,601 samples. That all-signals walk was not repeated in the handoff check. Selected-signal requests above did retrieve every sample of those series.

## Client

`decoder_validation_client.py` collected the handoff file requests, including the 4-page `HVBAT_STATE` series and the 2-page native selection. No client change was required.

## Regression

No product code changed for the parity check. The handoff smoke tests and the read-only ACII walk are recorded in `decoder-validation-qa.md`. The project catalog file was not rewritten.
