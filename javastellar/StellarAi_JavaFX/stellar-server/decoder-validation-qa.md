# Decoder validation QA — schema 2.0

## Root cause of metadata-only readings

A completed project read already returned decoded samples. Schema 1.0 put them in `signals[]` as objects with nested `raw` and `physical` fields, next to `sources`, `measurement` identity, and DBC fingerprints. A reader looking for `signals.<name>.samples` found the metadata fields and no asammdf object.

Inventory `READY` is not a success gate. `ReadOnlyProjectCatalog` opens a lossless store only for a valid lossless artifact whose job status is empty, `SUCCESS`, or `SUCCESS_WITH_WARNINGS`. A missing, unreadable, or out-of-fence store fails with `PROJECT_NOT_PROCESSED` rather than a success body with empty samples. A missing catalog file fails with `Project catalog is not available.`

The live ACII read confirms the data is present: `catalogSignalCount` 3236 and `catalogSampleCount` 1346530, with physical samples, raw integers, text labels, and nanosecond timestamps. The catalog file timestamp was unchanged by that read (`2026-10-27T07:32:12.7019320Z`).

## Live HTTP on 127.0.0.1:8091

File mode used a missing metadata database. `filePath` returned schema 2.0. `projectName` returned HTTP 500 `Project catalog is not available.` `/health` and `/api/v1/projects` returned HTTP 404.

| Request | HTTP | Result |
| --- | --- | --- |
| File, all signals, pageSize 1000 | 200 | 5 signals, 21 samples, `power.asc`, `power.dbc` |
| File, signal Torque | 200 | 1 signal |
| File, signalNames Selector and Torque | 200 | 2 signals |
| File, pageSize 4, then cursor | 200 | Selector continues at sampleIndex 2 |
| File, two signals named Speed | 200 | qualified keys, plain name rejected with `SIGNAL_AMBIGUOUS` |
| Project ACII, pageSize 2 | 200 | stored result, 2 samples of one series, cursor continues at index 2 with the same key |
| Project ACII, canonical HVBAT_STATE | 200 | key `HVBAT_STATE`, 3 samples |
| Project ACII, plain HVBAT_STATE | 409 | `SIGNAL_AMBIGUOUS` across three recordings |
| Project ACII, three signal names, pageSize 20 | 200 | 9 selected series, two qualified keys on the first page |

## asammdf

Independent parity was run later and is **PARTIAL**. See `decoder-validation-asammdf-parity.md`. Python 3.13.15, asammdf 8.8.28 and NumPy 2.5.4 ran from `D:\stellar-decoder-qa\parity-venv`. `HVBAT_STATE` on the Bengaluru bus MF4 with `2.dbc` matched all 34,557 samples. A Tata Sierra file was not found.

## Automated tests

`DecoderValidationApiTest` and `DecoderSampleJsonTest`: Tests run 15, Failures 0, exit 0 (`2026-10-27T08:18:44Z`). That run covers multiplexing, the 8,000-sample page split at indexes 0–5999 and 6000–7999, duplicate timestamps, enums, extended multiplexing, non-finite values, signalNames, colliding names, and a missing catalog.

`stellar-dbc-core`: Tests run 101, Failures 0, Skipped 1, module SUCCESS.  
`stellar-processing-core`: Tests run 564, Failures 0, Skipped 1, module SUCCESS.  
`stellar-storage-core`: Tests run 125, Failures 0, Skipped 0, module SUCCESS.  
Reactor exit 0 at `2026-10-27T08:23:01Z`.

`stellar-server`: Tests run 30, Failures 0, Errors 0, Skipped 0, BUILD SUCCESS, total time 22.997 s, exit 0 (`2026-10-27T08:26Z`). Desktop tests were not run. No desktop, parser, DBC, or storage-schema source was edited for this response change.

## Client handoff validation — 2026-10-27T10:47Z

No Java API, parser, DBC, storage, or UI source was edited for this check. `decoder_validation_client.py` already reads schema 2.0 and was not changed.

| Phase | Result |
| --- | --- |
| ACII full pagination | PASS |
| Python client | PASS |
| Independent asammdf parity | PARTIAL |
| Desktop smoke | PARTIAL |
| Handoff notes | PASS, in `decoder-validation-api.md` |

ACII `projectName` with `pageSize` 10000 returned schema 2.0 and `applicationVersion` 1.0.4. 135 pages, HTTP 200, `paginationComplete` true. Reported and collected totals are 3,236 signal identities and 1,346,530 samples. `sampleIndex` on every series is `0..sampleCount-1` with no gaps or duplicates. Parallel arrays on every page have one length. The Python client `collect` repeated that walk: 135 pages, 3,236 signals, 1,346,530 samples. It also collected `FAST_CHRG_NEG_CONT_COUNT` (1 signal, 5 samples) and `signalNames` `CONTACT_FREIN2` plus that signal (7 identities, 23,945 samples, 4 pages). Plain `signal` `CONTACT_FREIN2` returned HTTP 409 `SIGNAL_AMBIGUOUS`, which is the existing contract.

`D:\config\StellarAIAnalysis\database\stellar.db` stayed 13,541,376 bytes, last write `2026-10-27T07:32:12.7019320Z`. `stellar.db-wal` stayed 0 bytes. SQLite refreshed `stellar.db-shm` while the read-only connection was open. The user database `C:\Users\DT - User\.stellarai\database\stellar.db` stayed 729,088 bytes, last write `2026-10-11T21:12:25.1943989Z`. No project was reprocessed.

asammdf 8.8.28 from `D:\stellar-decoder-qa\parity-venv`: `HVBAT_STATE` 34,557 samples match; `FRPK`, `INFO_CRASH`, and `RESET_AFE_TRIP` text, raw, timestamps, and `VALID` match (17,773 samples); `KILOMETRAGE` remains one Java series against three channel groups. ASC is not an asammdf format. Details are in `decoder-validation-asammdf-parity.md`.

Automated desktop smoke, exit 0: `FinalIntegratedQaTest` 1, `MergedSignalPageAccessTest` 1, `SignalSamplePagingBoundaryTest` 3, `CategoricalArtifactRoundTripTest` 4, `StreamingSignalCsvExporterTest` 1, `LosslessGraphSignalStoreTest` 2, `SegmentBoundaryPaginationTest` 1. These use temporary projects. A manual pass through the installed ACII desktop window was not run.

## Functional matrix

Items 1–6, 8–21, and the file-mode half of item 7 were exercised by the API test and the live HTTP calls above. Item 7 project failure with a missing catalog was a live HTTP 500. Items 22–29 are covered by the existing server decoder tests (ambiguous signal, invalid DBC, unsupported format, schema, expired cursor, limits, recovery, profile isolation). Item 30 is by source diff: desktop classes were not modified. Item 31 is partial: the Python client was updated and not executed. Item 32 is NOT VERIFIED.
