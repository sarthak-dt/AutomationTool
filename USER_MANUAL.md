# Automotive Decoder Comparator — User Manual

**Version:** 1.0.0

Compare decoded automotive signals from a **Python reference decoder** (asammdf / python-can / cantools) with a **custom Java decoder**.

> **Java engine status.** Until the Java API is connected, the sidebar shows **Java API: MOCK / UNAVAILABLE**. In that state a job runs the **Python engine only**: no comparison is performed, no Java values are shown, and comparison reports are disabled. Nothing is ever simulated.

## 1. Workflow

Pages are in the left navigation. On **New comparison** choose the measurement source (**S3 Upload** or **Manual Upload**); DBC files are always uploaded from your computer. A compact one-page variant is available with `streamlit run app_simple.py`.

1. **S3 browser** — enter connection details, *Test connection*, open a folder, click **Select this folder**, tick the measurement files, click **Use selected files**.
2. **DBC manager** — upload one or more `.dbc` files, tick the ones to use.
3. **New comparison** — review the selection, set tolerances, click **Run comparison**.
4. **Results** — inspect tables, graphs, mismatches and run info.
5. **Reports** — download CSV / JSON / Excel / report ZIP.

## 2. S3 connection

| Field | Meaning |
|---|---|
| Bucket name | Bucket that holds the measurements. |
| Region | e.g. `us-east-1`. Must match the bucket for AWS S3. |
| Endpoint URL | Empty for AWS S3. Set for S3-compatible storage (MinIO, Ceph, …). |
| Endpoint port | Optional; merged into the URL. Not allowed to contradict a port inside the URL. |
| Access key ID / Secret | Leave **both empty** to use an IAM role or the default AWS credential chain (recommended). |
| Request style | *Auto*, *Virtual-hosted* (`bucket.host`) or *Path* (`host/bucket`). Many S3-compatible servers need **Path**. |
| Verify TLS | Keep on. Turn off only for a trusted internal endpoint with a self-signed certificate. |

**Selecting files.** Only **one folder** can be selected per job. Navigate with the folder buttons / **Up**, click **Select this folder**, then tick files. Supported types: MF4, BLF, LOG, ASC (other files are hidden and counted). Nothing is processed until you click **Run comparison**; other folders are never touched. Changing the selected folder clears the file selection.

**Credential safety.** Credentials live only in the server memory of your browser session, are scrubbed from logs and error messages, and never appear in reports or the job history. **Clear credentials** wipes them immediately.

## 3. DBC files

* Upload from your computer; DBCs are never read from S3.
* Each file is validated with `cantools`: invalid files are shown with the error and cannot be selected.
* Same filename + different content → stored as `name (2).dbc` with a warning. Same filename + identical content → skipped.
* **Selection order matters.** If several selected DBCs define the same CAN ID, the first one is used (Python engine). Every overlap is listed under *Conflicts*:

| Conflict | Severity | Meaning |
|---|---|---|
| `MESSAGE_ID_CONFLICT` | error | Same CAN ID defined differently; later DBC ignored for that ID. |
| `DUPLICATE_MESSAGE` | info | Same CAN ID defined identically. |
| `MESSAGE_NAME_COLLISION` | warning | Same message name on different CAN IDs. |
| `SIGNAL_NAME_COLLISION` | warning | Same signal name in different messages; samples are merged under one name. |

## 4. Python decoding — what is supported

| Input | How it is decoded |
|---|---|
| BLF / ASC / LOG | `python-can` reads frames, `cantools` decodes them with the selected DBCs. |
| MF4 with raw CAN bus logging | `asammdf` `extract_bus_logging` with the selected DBCs. |
| MF4 already containing decoded channels | Channels are read as-is. **DBCs are not applied** (a warning says so). |

Timestamps are seconds, relative to the first frame of the file. Output is numeric (value tables are not applied; 1-bit signals and signals with value tables are flagged *discrete*).

**Limitations:** CAN / CAN-FD only (no LIN, FlexRay, Ethernet); no J1939 transport-protocol reassembly or container PDUs for BLF/ASC/LOG; text/array channels in MF4 are skipped (counted in a warning); very large files are processed in memory.

## 5. Comparison rules

| Status | Meaning |
|---|---|
| `MATCH` | Values agree within tolerance. |
| `MISMATCH` | Values differ beyond tolerance. |
| `MISSING_IN_PYTHON` | Signal / sample only in the Java output. |
| `MISSING_IN_JAVA` | Signal / sample only in the Python output. |
| `NOT_COMPARABLE` | Numeric vs text, strict unit mismatch, or no common time range. |
| `ERROR` | A decoder or the comparison failed (message in *note*). |

* **Tolerance:** match if `|p − j| ≤ max(abs_tol, rel_tol × max(|p|, |j|))`.
* **Match %** = `MATCH / (MATCH + MISMATCH)`. Missing, not-comparable and error rows are counted separately and never as numeric mismatches. A signal absent from one engine yields **one** `MISSING_IN_*` row.
* **Timestamps (resampling off):** each Python sample is paired with the nearest Java sample within the *timestamp tolerance*; unpaired samples become `MISSING_IN_*`.
* **1-second resampling:** both outputs are evaluated at t = 0, 1, 2, … s inside their overlapping time range. Continuous signals use linear interpolation; discrete signals hold the last value.
* **Discrete signals** (flagged by an engine, listed by you, or — with *auto-detect* — integer-valued with ≤ N distinct values): compared exactly, tolerance ignored.
* **NaN values:** both NaN → match; one NaN → `MISSING_IN_*`; *Skip rows* drops them.
* **Units:** compared when both are present; a difference is noted, or becomes `NOT_COMPARABLE` with the strict policy.

## 5a. See the MATCH / MISMATCH table before the Java API exists

On **Results**, the panel *Compare with Java decoder output (CSV)* lets you upload the samples your Java decoder produced
(columns `signal, timestamp, value`, optional `unit` and `CAN ID`; add `file` when the job has several log files; timestamps in seconds with the
same reference as the Python output). The format is identical to `python_output.csv` from **Reports**. The app then compares your
real Java output with the Python decoding and shows the full table (MATCH / MISMATCH / MISSING pills, filter, Export CSV).
Without Java output the table still lists the decoded Python samples, marked **NOT COMPARED**. The report states that the Java data came from an uploaded CSV, not the Java API.

### Readable time (date and time)

Internally every sample time is *seconds since the first frame* (same reference for Python and Java). When the file's start time is known
(MF4 header start time; BLF absolute time in UTC) the tables show readable times such as `2023-08-14 17:14:38.010`, and `python_output.csv`
gets a `datetime` column next to `timestamp` (seconds). The Java CSV may use `timestamp` (seconds) **or** `datetime`. For files without a start time
(e.g. relative ASC logs) seconds are shown. The decoded output CSVs place `CAN ID` immediately after `file name` (`0x...` for Python-decoded CAN frames); it is blank when the source has no CAN ID, such as already-decoded MF4 channels. `is_discrete` is not exported (it is detected internally).

## 6. Reports

| File | Content |
|---|---|
| `comparison.csv` | One row per compared sample / signal with values, differences, tolerance, status, note. |
| `signal_summary.csv` | One row per file + signal with rolled-up status and match %. |
| `result.json` | Summary, per-file status, signal summary, all rows (strict JSON, `null` for empty values). |
| `python_output.csv` | Real decoded Python samples. |
| `java_output.csv` | Only present when the Java API returned real data. |
| `report.xlsx` | Workbook version of the above. |
| `graphs/*.html` | Interactive plots (mismatching signals first, max 30). |
| `job_summary.json`, `run_log.txt`, `README.txt` | Settings, per-file status, log (secrets redacted). |

When the Java engine is unavailable, comparison CSVs are disabled and the ZIP says so explicitly.

## 7. Troubleshooting

| Problem | What to check |
|---|---|
| *Cannot reach the endpoint* | Endpoint URL, port, VPN / proxy, HTTP vs HTTPS. |
| *Signature mismatch* | Wrong secret key, wrong region, or wrong request style (try **Path** for S3-compatible storage). |
| *Access denied* | Role/keys need `s3:ListBucket` on the bucket and `s3:GetObject` on the objects. |
| *Bucket in a different region* | Set the region of the bucket. |
| Bucket connects but no files | Supported extensions only (`.mf4 .blf .log .asc`); open the right sub-folder. |
| DBC rejected | Valid DBC text file? Size below limit? See the error shown under the table. |
| *No frame matched any message* | Wrong DBC for this vehicle, standard vs extended IDs, or wrong channel. |
| MF4 decoded 0 signals | The MF4 may not contain raw CAN logging, or the DBC IDs do not match. |
| Missing-library message in sidebar | Run `./setup.sh` (or `pip install -r requirements.txt`). |
| Comparison not performed | Java API is MOCK / UNAVAILABLE — configure `JAVA_API_*` in `.env`. |
| Many mismatches | Check scaling/offset, byte order, signedness, multiplexing, timestamp reference, resampling and tolerance. |
| Download button disabled | Run a job first; comparison downloads also need a real Java result. |

When asking for help, send the job ID, the time and the non-sensitive error text — **never** secret keys.

## 8. Security notes

* Use buckets and files you are authorised for; prefer an IAM role.
* Uploads are size-limited, file names sanitised, all temporary files are deleted after each job (and stale ones at start-up).
* Run the app on a trusted network or behind authentication (Streamlit has none built in).
* Check reports for sensitive measurement data before sharing.

## 9. Quick checklist

Before **Run comparison**: S3 connected · one folder selected · intended files ticked · DBCs uploaded and ticked · conflicts reviewed · tolerances set.
After: errors/warnings reviewed (*Run info*) · graphs inspected · reports downloaded and stored securely.
