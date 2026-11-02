# Automotive Decoder Comparator

Streamlit application that compares a **Python reference decoder** (python-can / cantools / asammdf) with a **custom Java decoder** on measurement files read from **S3**, using **DBC files uploaded from your computer**.

## Quick start

```bash
unzip decoder_comparator.zip && cd decoder_comparator
./run.sh                 # creates .venv, installs requirements, starts the app
```

Windows: `run.bat`. Manual: `python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt && streamlit run app.py`.
Requires Python 3.10+. The app opens at <http://localhost:8501> (`PORT=9000 ./run.sh` to change).

Run the tests: `./verify.sh` (installs dev requirements first).

## Using the app

`app.py` provides **New comparison, S3 browser, DBC manager, and Reports**. It opens Reports directly after a run; Dashboard and Results are not separate pages.

1. **S3 browser** — enter the connection details, *Test connection*, choose a folder, tick files (name, size, type, date), *Use selected files*.
   Or choose **Manual Upload** on *New comparison* and upload the log files from your computer.
2. **DBC manager** — upload one or more `.dbc` files, tick the ones to use (invalid files and conflicts are flagged).
3. **New comparison** — set resampling / tolerance, click **Run comparison**.
4. **Reports** — opened automatically after the run. Download timestamped comparison CSV, JSON, and decoded Python / Java output CSV files.

`streamlit run app_simple.py` starts a compact one-page variant (source switch + dashboard on one page). It includes the same
CAN-channel selection, per-DBC channel assignment, and **DBC Match to This File** controls as the full application.
Differences from the prototype: sample numbers are replaced by real results, resampling offers *none* and *1 second* (the engine does not implement 100 ms), the default absolute tolerance is 0.1 as in the prototype.

## Channel-wise decoding

In the DBC manager, bind each DBC to one or more CAN channels; leave its channel selection empty to apply it to every channel. MF4 bus
channel numbers are preserved as stored, including physical channel 0. On *New comparison*, the **CAN channels to decode** selection
filters decoding; leave it empty to decode every available channel. Decoded samples retain their actual channel and CAN ID.

With **All channels**, a row is **MATCH** only when both decoders report the same known channel and the decoded values agree within tolerance. A known channel difference is **MISMATCH**, even if values agree; missing channel metadata is **NOT_COMPARABLE** and includes an explanation in the CSV `Message` column. If a derived signal-only MF4 has lost its channel metadata, use the Python output channel selector to explicitly assign the channel only after verifying it from the source measurement; the CSV `Message` field records that assignment.

**DBC Match to This File** checks the selected DBC message IDs against a selected MF4's CAN IDs and reports matched IDs and IDs
unmatched on either side, separately for each channel. On *New comparison*, the Python and Java comparison-channel controls remain
separate; different comparison channels are reported as MISMATCH. The channel numbers must match for a row to be MATCH.

The comparison CSV columns are `Index`, `File Name`, `Date/Time`, `Timestamp`, `CAN ID`, `Signal Name`, `DBC Name`, `Python Channel`,
`Java Channel`, `Python Decoded Value`, `Java Decoded Value`, `Absolute Error`, `Status`, and `Message`. An empty DBC channel
binding applies that DBC to every channel detected in each measurement file.

When more than four measurement files are selected, **New comparison** divides them into batches of up to four. Choose a batch,
run it, and download its report before selecting the next batch. Each batch is a separate job and produces a separate report.

## Java engine

The app calls `POST http://127.0.0.1:8091/api/v1/decoder/validate` with
`filePath`, `dbcPath`, and `pageSize: 10000`. The API accepts one DBC per
request, so the app calls it once for each selected DBC and combines the Java
outputs. It stages the measurement and current DBC under
`~/Downloads/.decoder-comparator-staging` for each call; that directory must be within
`STELLAR_DECODER_ALLOWED_ROOTS`. Java schema 2.0 responses are mapped and all
`nextCursor` pages are collected before comparison. Timestamped reports include
the Python and Java values, CAN ID, channel, absolute error, and match status.

Start the Java decoder in one terminal from
`~/Downloads/DecoderComparator/javastellar/StellarAi_JavaFX`:

```bash
export MAVEN_HOME="/tmp/apache-maven-3.9.9"
export PATH="$MAVEN_HOME/bin:$PATH"
export STELLAR_DECODER_ALLOWED_ROOTS="$HOME/Downloads"
export STELLAR_DECODER_SESSION_DIRECTORY="/tmp/stellar-decoder-session-sarthak"
unset STELLAR_DECODER_METADATA_DB
mvn -pl stellar-server spring-boot:run -Dspring-boot.run.profiles=decoder
```

Start Streamlit in a second terminal:

```bash
cd ~/Downloads/DecoderComparator/decoder_comparator
source .venv/bin/activate
python -m streamlit run app.py
```

The Java API accepts one DBC path per request; the app handles multiple DBCs
with sequential API calls and combines their results. Each DBC adds another
Java request. The Java process must be able to read the staged files and
`~/Downloads` must be included in its allowed roots. Set `JAVA_API_ENABLED=false`
only when intentionally running Python alone.

## Project layout

```
app.py                      Streamlit UI with comparison setup and timestamped report downloads
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

## Java parser response (schema 2.0)

`services/java_response.py` maps the Java parser response (columnar JSON:
`signals{...}` with `timestamps`, `samples`, `validity`). After a run, the app opens **Reports** for timestamped downloads.
