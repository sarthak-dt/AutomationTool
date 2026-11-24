# Automotive Decoder Comparator — User Manual

**Version:** 1.0.0

This application decodes automotive measurement files with a Python reference decoder and compares its output with a Java decoder. Measurements can come from S3 or local uploads; DBC files are uploaded locally.

## 1. Start here

Run `./run.sh` (Windows: `run.bat`) from the `decoder_comparator` directory. The app opens in a browser, usually at `http://localhost:8501`. To start the compact one-page interface instead, run `streamlit run app_simple.py`.

The full app has **New comparison**, **S3 browser**, **DBC manager**, and **Reports** pages. It opens Reports after a run.

1. Choose measurement files from **S3 browser** or **Manual Upload** on New comparison.
2. Upload and select valid `.dbc` files in **DBC manager**. Assign channels if needed.
3. On **New comparison**, select the channels and comparison options, then run.
4. Review the run information and comparison results; download the CSV files or report ZIP from **Reports**.

Manual uploads remain available while navigating within the current Streamlit session. Use **Clear uploaded measurement files** on New comparison to remove them. If more than four measurements are selected, choose a batch of up to four files, run it, and download that batch's report before selecting another batch. Each batch is a separate job.

## 2. Choose measurement files

Supported measurement formats are `.mf4`, `.blf`, `.log`, and `.asc`. Upload limits and the maximum files per job are configurable; defaults are 2048 MB per measurement and 25 files overall. S3 listings are limited to 5000 keys by default.

### S3 browser

Provide the bucket and region, then click **Test connection**. Endpoint URL is blank for AWS S3; set it for compatible services such as MinIO. Endpoint port is optional and cannot conflict with a port already in the URL. Choose **Auto**, **Virtual-hosted**, or **Path** addressing; compatible services often need Path. Leave both access key fields blank to use the AWS credential chain or an IAM role. Keep TLS verification enabled except for a trusted internal endpoint with a self-signed certificate.

Select one folder per job, tick the measurement files, and click **Use selected files**. Changing folders clears the current selection. Unsupported file types are hidden. Files are not processed until you run the job.

### Manual upload

Select measurement files on New comparison. Uploads are kept for the current Streamlit session, including while you visit DBC manager. The uploader checks file types, names, and configured size limits.

## 3. DBC files and CAN channels

Upload `.dbc` files from your computer in **DBC manager**; DBCs are not read from S3. Each DBC is validated with `cantools`. Invalid files cannot be selected. Identical content with the same name is skipped; different content with a duplicate name is stored under a renamed filename.

Use the **Channels** selector beside a DBC to bind it to one or more CAN channels. Leave it empty to apply the DBC to every channel. DBCs assigned to disjoint channels do not conflict. Where multiple selected DBCs define the same CAN ID on the same channel, the first selected definition is used by the Python decoder. Review the conflict list:

| Conflict | Meaning |
|---|---|
| `MESSAGE_ID_CONFLICT` | Same CAN ID has different definitions; the later DBC is ignored for that ID. |
| `DUPLICATE_MESSAGE` | Same CAN ID has identical definitions. |
| `MESSAGE_NAME_COLLISION` | Same message name appears on different CAN IDs. |
| `SIGNAL_NAME_COLLISION` | Same signal name appears in different messages; samples may be combined under that name. |

On New comparison, use **Discover available channels** to populate **CAN channels to decode**. Leave the selection empty to decode all channels found in the selected files. MF4 channel numbers are preserved as stored, including physical Channel 0. For BLF/ASC/LOG, channels are shown with 1-based numbering.

**DBC Match to This File** checks CAN IDs in a selected MF4, BLF, or ASC against the selected DBCs, grouped by channel. It shows matches and IDs found on only one side.

The Python and Java output channel selectors are useful when an output has lost channel metadata. Only assign a channel when it is verified from the source measurement; the Java channel selection may filter the Java result.

## 4. Decoder behavior

| Input | Python decoding |
|---|---|
| BLF / ASC / LOG | `python-can` reads frames and `cantools` decodes them with the selected DBCs. |
| MF4 with raw CAN bus logging | `asammdf` extracts bus logging and decodes it with the selected DBCs. |
| MF4 with already-decoded channels | Channels are read as-is; DBCs are not applied. |

Python CAN timestamps are relative to the first frame. Java timestamps are shifted to that reference before comparison and export. Values are numeric; value tables are not applied. One-bit and value-table signals are treated as discrete.

Limitations: CAN and CAN-FD only; no LIN, FlexRay, or Ethernet. J1939 transport-protocol reassembly and container PDUs are not handled for BLF/ASC/LOG. Text and array channels in MF4 are skipped with a warning. Large files are processed in memory and subject to `MAX_MEASUREMENT_SIZE_MB` (default 2048).

## 5. Java decoder setup and availability

The Java API is called at `JAVA_API_BASE_URL` plus `JAVA_API_ENDPOINT_PATH`. Defaults are `http://127.0.0.1:8091` and `/api/v1/decoder/validate`. Configure the Java service and any required authentication in `.env` (see `.env.example`); restart the app after changing settings. `JAVA_API_ENABLED=false` intentionally disables Java calls.

Check the sidebar or New comparison page for Java availability. If the API is unavailable, the app can run the Python decoder alone, but it cannot produce a live comparison or Java output. The reason is shown in the app. When multiple DBCs are selected, the app makes one Java API request per DBC and combines returned outputs; this takes longer and can encounter service limits.

If the live API is unavailable, you can compare against a saved Java output on the results page using **Compare with Java decoder output (JSON / CSV)**. JSON must be a saved Java parser API response (schema 2.0). CSV requires `signal`, `value`, and `timestamp` in seconds or `datetime`; `unit` is optional, and `file` is required for multi-file jobs. Use the same timestamp reference as the Python output; the Reports page's Python output CSV shows the sample format. Click **Compare with this file**. Imported Java data is identified as uploaded in the run information, not as a live API result.

## 6. Comparison options and statuses

Available controls include no resampling or **1 second** resampling, absolute and relative numeric tolerances, timestamp tolerance (when resampling is off), NaN handling, unit mismatch policy, and discrete-signal auto-detection.

| Status | Meaning |
|---|---|
| `MATCH` | Values agree within tolerance, or discrete values are exactly equal, and the channel gate allows comparison. |
| `MISMATCH` | Values differ beyond tolerance, or known Python and Java channels differ. |
| `MISSING_IN_PYTHON` | A signal/sample is present only in Java output. |
| `MISSING_IN_JAVA` | A signal/sample is present only in Python output. |
| `NOT_COMPARABLE` | Values cannot be compared, such as incompatible types, strict unit mismatch, no common time range, or unverified channel metadata. |
| `ERROR` | A decoder or comparison failed; see the note. |

Numeric values match when `|python − java| ≤ max(abs_tol, rel_tol × max(|python|, |java|))`. Discrete signals compare exactly and ignore numeric tolerance. Auto-detection can classify integer-valued signals with a small number of distinct values as discrete. When units are present on both sides, a difference is either noted or marked `NOT_COMPARABLE` under the strict policy.

Without resampling, each Python sample is paired with the nearest Java sample within the timestamp tolerance; unpaired samples are reported as missing. With 1-second resampling, both outputs are evaluated at 0, 1, 2, … seconds over their overlapping range. Continuous values use linear interpolation; discrete values hold the last sample. If both values are NaN they match; one NaN is missing, unless **Skip rows** is selected.

### Channel comparison rules

By default, only samples with the same known CAN channel can match. A known channel difference is `MISMATCH`, even when decoded values are equal; the values are not compared across channels. Missing or unverified channel metadata is `NOT_COMPARABLE`. Choosing a specific channel on both sides restricts comparison to that channel; choosing different channels produces a channel mismatch. If a selected channel is confirmed absent from a measurement, the exported comparison CSV includes an informational `NOT_COMPARABLE` row explaining that no decoding was done for that channel. This diagnostic row does not affect comparison totals.

Match percentage is `MATCH / (MATCH + MISMATCH)`. Missing, not-comparable, and error rows are counted separately. A signal absent from one engine produces one `MISSING_IN_*` row.

## 7. Reports and downloads

Reports provides the comparison result, run details, and downloadable outputs. The report ZIP contains available report files and graphs; its README describes included files and any omissions. Email delivery is currently disabled. Use the normal download controls to save the ZIP.

| Download | Contents |
|---|---|
| Comparison CSV parts | Values, timestamps, CAN ID, channel, difference, status, and message. |
| Python output CSV parts | Decoded Python samples. |
| Java output CSV parts | Java samples, when real or imported Java output is available. |
| Report ZIP | A bundle of available reports, outputs, and graphs. |

Large CSV downloads are split into numbered `.csv.gz` parts of up to one million rows each. Comparison `Index` values continue across parts. Python and Java output CSVs contain relative timestamps in seconds and omit readable date/time and the internal `is_discrete` field. Gzip compression is lossless; decompress each part to restore CSV content. Downloaded files include the job completion timestamp in their filenames.

## 8. Troubleshooting

| Problem | Check |
|---|---|
| Cannot reach S3 endpoint | URL, port, VPN/proxy, and HTTP/HTTPS settings. |
| S3 signature mismatch | Secret key, region, and request style; try Path style for compatible services. |
| S3 access denied | Credentials need `s3:ListBucket` and `s3:GetObject` for the selected bucket and objects. |
| Bucket connects but no files appear | Supported extensions and selected subfolder. |
| DBC rejected | DBC syntax and size; read the validation error in DBC manager. |
| No frame matched any message | Check vehicle DBC, standard/extended CAN IDs, and channel bindings. |
| MF4 decodes no signals | It may not contain raw CAN logging, or its CAN IDs may not match the DBC. |
| Java API unavailable | Start/check the Java service, URL, endpoint, authentication, TLS, and network access. Python-only runs do not create comparisons. |
| Many mismatches | Check scaling, offset, byte order, signedness, multiplexing, timestamp reference, channels, resampling, and tolerances. |
| Download unavailable | Run a job first; Java output requires Java data. |
| Missing library warning | Run `./setup.sh` or install `requirements.txt` in the app environment. |

When requesting help, include the job ID, time, and non-sensitive error text. Never send secret keys or passwords.

## 9. Security and checklist

Prefer IAM roles for S3. Credentials entered in the UI stay in server memory for the session and are scrubbed from logs and reports; **Clear credentials** removes them. Upload names are sanitised, files are size-limited, and temporary workspaces are cleaned after jobs and at startup. Streamlit has no built-in login, so run the app on a trusted network or behind authentication. Review reports for sensitive measurement data before sharing.

Before running: confirm the intended measurements, selected DBCs and channel bindings, reviewed conflicts, Java availability, and tolerances. After running: review warnings and errors, check channel/value mismatches, and download and securely store the required reports.
