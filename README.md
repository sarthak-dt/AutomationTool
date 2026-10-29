# Automotive Decoder Comparator

Streamlit application that compares a **Python reference decoder** (python-can / cantools / asammdf) with a **custom Java decoder** on measurement files read from **S3**, using **DBC files uploaded from your computer**.

## Quick start

```bash
unzip decoder_comparator.zip && cd decoder_comparator
./run.sh                 # creates .venv, installs requirements, starts the app
```

Windows: `run.bat`. Manual: `python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt && streamlit run app.py`.
Requires Python 3.12+. The app opens at <http://localhost:8501> (`PORT=9000 ./run.sh` to change).

Run the tests: `./verify.sh` (installs dev requirements first).

## Using the app

`app.py` mirrors the HTML prototype: left navigation with **Dashboard, New comparison, S3 browser, DBC manager, Results, Reports**.

1. **S3 browser** — enter the connection details, *Test connection*, choose a folder, tick files (name, size, type, date), *Use selected files*.
   Or choose **Manual Upload** on *New comparison* and upload the log files from your computer.
2. **DBC manager** — upload one or more `.dbc` files, tick the ones to use (invalid files and conflicts are flagged).
3. **New comparison** — set resampling / tolerance, click **Run comparison**.
4. **Results** — cards (compared signals, matched samples %, mismatch points, missing signals), *Signal-level comparison* table with MATCH / MISMATCH / MISSING pills, filter and Export CSV, signal agreement bars, graph, run details.
   No Java API yet? Upload your Java decoder's output as CSV on *Results* (panel *Compare with Java decoder output*) to get the real match/mismatch table.
   *Preview with demo data* (Results page, empty state) shows the layout with illustrative values, labelled **DEMO DATA**; it cannot be exported.
5. **Reports** — CSV, JSON, Excel, full ZIP, plus the decoded Python / Java output CSV files.

`streamlit run app_simple.py` starts a compact one-page variant (source switch + dashboard on one page).
Differences from the prototype: sample numbers are replaced by real results, resampling offers *none* and *1 second* (the engine does not implement 100 ms), the default absolute tolerance is 0.1 as in the prototype.

## Java engine: current state

The Java API is not defined yet, so **no request/response schema was invented**.

* `JAVA_API_ENABLED=false` (default) → the Java engine reports **MOCK / UNAVAILABLE**. Jobs run the Python engine only; no comparison is performed, no Java data is shown, comparison exports are disabled.
* **The only file to edit when the API is ready: `services/java_decoder_client.py`.** Search for `TODO(JAVA_API_INTEGRATION)`:
  1. `_build_request()` — how the measurement file, DBCs and settings are sent.
  2. `_map_response()` — convert the API response to the normalized payload documented at `validate_normalized_payload()`.
  3. `_auth_kwargs()` — only if the auth scheme is not none/bearer/api_key/basic.
  4. In `.env`: `JAVA_API_ENABLED=true`, `JAVA_API_BASE_URL`, `JAVA_API_ENDPOINT_PATH`, auth settings, timeouts.
  5. Run `./verify.sh`; adapt `tests/test_java_client.py` to the real schema.

Until steps 1–2 are implemented the adapter answers *unavailable*, never fake data.

## Project layout

```
app.py                      Streamlit UI mirroring the HTML prototype (6 pages)
app_simple.py               Compact one-page UI variant
config.py                   env-based configuration
ui/                         theme (CSS from the prototype), HTML components, Streamlit compat helper
services/
  s3_service.py             boto3 access, listing, download, friendly errors
  file_service.py           filename/path safety, upload validation, temp workspaces
  dbc_service.py            DBC upload, cantools validation, conflict detection
  python_decoder.py         python-can + cantools (BLF/ASC/LOG), asammdf (MF4)
  java_decoder_client.py    Java API adapter  <-- edit here
  comparison_engine.py      normalisation, tolerance, alignment, statuses
  job_runner.py             orchestration + cleanup
  report_service.py         CSV / JSON / XLSX / graphs / ZIP
  history_service.py        job history (summaries only)
  logging_utils.py          secret redaction
tests/                      pytest suite
```

## Comparison rules (summary)

Statuses: `MATCH`, `MISMATCH`, `MISSING_IN_PYTHON`, `MISSING_IN_JAVA`, `NOT_COMPARABLE`, `ERROR`.
Match if `|p−j| ≤ max(abs_tol, rel_tol·max(|p|,|j|))`; discrete signals compare exactly; samples are paired by nearest timestamp within a tolerance, or both outputs are resampled to a 1 s grid (linear for continuous, hold for discrete). Missing signals produce one `MISSING_IN_*` row and are never counted as numeric mismatches. Full rules: top of `services/comparison_engine.py` and *User manual*.

## Supported formats and limitations

BLF / ASC / LOG via python-can + cantools; MF4 with raw CAN logging via asammdf `extract_bus_logging`; MF4 with already-decoded channels is read as-is (DBCs not applied). CAN / CAN-FD only; no J1939 transport reassembly; MF4 text/array channels skipped; files are processed in memory (limit `MAX_MEASUREMENT_SIZE_MB`, default 2048). Timestamps are relative to the first frame (BLF/ASC/LOG) — the Java adapter must use the same convention.

## Security

No credentials in code or the ZIP; `.env` is git-ignored. S3 secrets are kept in session memory only, redacted from logs/errors/reports. Prefer an IAM role (leave key fields empty). Uploads are size/extension validated, filenames sanitised, paths contained, temp directories always removed. Streamlit has no built-in login: deploy behind your authentication / on a trusted network.

## Configuration

See `.env.example` (limits, S3 defaults, Java adapter settings).
