# Automotive Decoder Comparator — User Manual

**Version:** 1.0.0

Compare decoded automotive signals from a **Python reference decoder** (asammdf / python-can / cantools) with a **custom Java decoder**.

> **Java engine status.** Until the Java API is connected, the sidebar shows **Java API: MOCK / UNAVAILABLE**. In that state a job runs the **Python engine only**: no comparison is performed, no Java values are shown, and comparison reports are disabled. Nothing is ever simulated.

## 1. Workflow

The left navigation contains **New comparison**, **S3 browser**, **DBC manager**, and **Reports**. On **New comparison** choose the measurement source (**S3 Upload** or **Manual Upload**); DBC files are always uploaded from your computer. After a run, the app opens **Reports** directly. A compact one-page variant is available with `streamlit run app_simple.py`.

1. **S3 browser** — enter connection details, *Test connection*, open a folder, click **Select this folder**, tick the measurement files, click **Use selected files**.
2. **DBC manager** — upload one or more `.dbc` files, tick the ones to use.
3. **New comparison** — review the selection, set tolerances, click **Run comparison**.
4. **Reports** — download the timestamped multi-sheet Excel comparison workbook, JSON, and decoded Python / Java output CSV files.

Manual measurement uploads are retained in the current Streamlit session while navigating to the DBC manager. Clear them from
**New comparison** using **Clear uploaded measurement files**. The comparison workbook continues rows on numbered sheets at Excel's row
limit, contains only comparison sheets, and preserves continuous `Index` values. Python and Java output CSVs use relative timestamps in seconds and omit readable datetime
columns and the internal `is_discrete` column. For smaller downloads, **Reports** also provides numbered comparison CSV parts of up to one
million rows each, with continuous `Index` values. When a selected channel is confirmed absent from a measurement, the workbook includes an informational `NOT_COMPARABLE` row
with the available channels and states that no decoding was performed. This explanation does not change comparison totals.

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

### Sample timestamps

Sample timestamps are exported as seconds since the first frame, using the same reference for Python and Java. Generated comparison,
Python output, and Java output CSVs do not include a readable date/time column. Exported JSON omits absolute date/time fields. The
internal `is_discrete` field is not included in Python or Java output CSVs.

## 5b. Channel-wise decoding and comparison

**1. Bind each DBC to one or more CAN channels** (DBC manager, *Channels* next to each DBC): e.g. DBC-1 → Channel 1, DBC-2 → Channel 0,
DBC-3 → Channel 2. Leave a DBC's channel selection empty to apply it to every channel. You can bind the same DBC to multiple selected
channels, or assign different DBCs to different channels. DBCs assigned to disjoint channels do not conflict.

**2. Choose CAN channels to decode** (*New comparison*): select one or more channels to decode only those channels; leave the selection
empty to decode every available channel. MF4 bus channel numbers are kept as stored by the file, including physical Channel 0. For
BLF/ASC/LOG, python-can's 0-based channel is shown 1-based (interface names such as `can0` → Channel 1). Every decoded row retains its
actual channel and CAN ID (e.g. `0x7E8`).

**3. Use “DBC Match to This File”** (*New comparison*) to select an MF4, BLF, or ASC and see matched CAN IDs, IDs present in the file but absent from
the assigned DBCs, and DBC IDs not observed in the file. The table is grouped by CAN channel and respects both DBC assignments and the
channel selection.

**4. Comparison channel rules on *New comparison*:** pick the *Python decoder channel* and the *Java decoder channel* (or *All channels*).
* Same number on both sides → compatible; only that channel of each output is compared.
* Different numbers → **NOT_COMPARABLE**. Values are never compared across different channel numbers.
* *All channels* (default): values are compared only when both decoders report the same known channel.
* Signals present on different channels, or without channel metadata on either side, are **NOT_COMPARABLE**; their values are not compared.
* A channel mismatch is reported even when decoded values happen to be equal.

## 5. Reports

| File | Content |
|---|---|
| `*_comparison.xlsx` | Comparison rows with values, differences, tolerance, status, and notes; large results continue on numbered sheets. |
| `signal_summary.csv` | One row per file + signal with rolled-up status and match %. |
| `result.json` | Summary, per-file status, signal summary, all rows (strict JSON, `null` for empty values). |
| `python_output.csv` | Real decoded Python samples. |
| `java_output.csv` | Only present when the Java API returned real data. |

Downloaded files include the job completion timestamp in their filenames. When the Java engine is unavailable, the comparison workbook is disabled.

## 6. Troubleshooting

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
