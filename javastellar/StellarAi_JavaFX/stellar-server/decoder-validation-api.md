# Decoder Validation API

`POST /api/v1/decoder/validate` is the only decoder-validation route. A successful response is schema `2.0`. Callers do not send `responseFormat`.

Application version remains `1.0.4`. Error bodies stay schema `1.0`.

## Startup

JDK 21 is required. Python is not required to run the API.

Double-click `BUILD_DECODER_API.bat` in the repository root once to package the server. Copy `decoder-api.config.example` to `decoder-api.config` and set `allowed-roots` to the measurement and DBC directories this API may read. Separate directories with semicolons. A whole drive is rejected. Then double-click `START_DECODER_API.bat`. The window shows `http://127.0.0.1:8091/api/v1/decoder/validate` and stays open while the API runs. Press Ctrl+C and answer Y, or close the window, to stop it. If the server JAR is missing, the start script prints that instruction and does not build. If port 8091 is already in use, it does not start a second instance.

`STELLAR_DECODER_ALLOWED_ROOTS`, `STELLAR_DECODER_SESSION_DIRECTORY`, and `STELLAR_DECODER_METADATA_DB` still override the config file when they are already set. Maven is required only for `BUILD_DECODER_API.bat`. The importable Postman collection is `stellar-server/StellarAI-Decoder-Validation.postman_collection.json`.

The manual command from the repository root remains available. In Windows PowerShell, quote the Maven `-D` argument:

```powershell
mvn -pl stellar-server spring-boot:run "-Dspring-boot.run.profiles=decoder"
```

Without allowed roots, every `filePath` and `dbcPath` is rejected with HTTP 403 `PATH_NOT_ALLOWED`. The portable setup for a fresh decode and for a saved project is in [QA engineer setup](#qa-engineer-setup).

The decoder profile binds `127.0.0.1:8091` and serves only this route. `/health` and `/api/v1/projects` are not published. The default server profile is a different process: it binds `0.0.0.0:8080` and does not publish this API. `stellar.decoder.allowed-roots` is a semicolon-separated list of absolute directories. `stellar.decoder.metadata-db` is required only for `projectName`.

A `filePath` request decodes through the existing pipeline into an isolated temporary session. It does not open the project catalog. A `projectName` request opens that catalog read-only and fails with `INTERNAL_ERROR` / `Project catalog is not available.` when the file is missing. The API does not migrate SQLite and does not create a saved project.

## Request

Send exactly one of `projectName` or `filePath`. `dbcPath` is required unless the measurement is native MF4/MDF. `signal` selects one series and returns `SIGNAL_AMBIGUOUS` when that plain name matches more than one decoded series. `signalNames` selects every series with those names, including same-name series from different files, messages, or buses. Omit both to return every decoded series. `signal` and `signalNames` cannot be combined. `pageSize` is the maximum number of samples in the response across all selected series, from 1 through 10,000. The default is 10,000. The existing cursor also places at most `pageSize` signal objects on a page. An empty series uses one of those slots and zero samples. Continuation still returns every sample. A cursor request may change `pageSize` and must not repeat a source or signal filter.

## Response

`signals` is a JSON object. Each value has parallel arrays of equal length:

- `timestamps` — stored nanoseconds divided by 1e9. The first sample is not subtracted.
- `timestampsNs` — the exact signed nanosecond value as a decimal string.
- `samples` — stored physical values. Finite values are JSON numbers. Non-finite values are objects `{"representation":"non-finite","value":"NaN"|"Infinity"|"-Infinity"}`.
- `rawSamples` — stored raw integers. JSON numbers inside +/-9007199254740991, decimal strings outside that range, or `null` when no raw value was stored.
- `textValues` — stored textual values, separate from `samples`. DBC value descriptions and supported MDF text conversions (CC types 7 and 8) are both stored here. A text conversion keeps `samples` non-finite.
- `validity` — the stored sample validity name.
- `sampleIndex` — decimal strings for the original index in that series.
- `sourceDatetime` — one value per sample, or `null` when the file has no recording date. The text form is `yyyy-MM-dd HH:mm:ss.SSS`. Sub-millisecond nanoseconds are discarded. A relative sample is the recording start stored in the file plus `timestampsNs`. An absolute timestamp already contains its date, so the start is not added again. For an MF4 file whose start count is UTC and whose time-offset flag is valid, that start is shown plus the file's timezone and daylight minutes. A local-time MF4 count is shown as stored, and an invalid offset flag is not applied. The result is not shifted to Asia/Kolkata or the server zone, and it is not the request time or the file-system time.

## Breaking change

Success responses no longer include `datetime`, `datetimeTimezone`, or `datetimeFormat`. Older clients that read MF4 time in `Asia/Kolkata` must read `sourceDatetime` instead. A UTC MF4 header with a valid offset shows that count plus the offset stored in the file. A header whose offset flag is clear keeps the UTC count. A local-time flag keeps the stored digits. ASC, BLF, and MDF 3 files that previously returned null now show the header clock plus the sample offset when that header date exists.

The envelope still carries `datetimeAvailability` (`AVAILABLE`, `PARTIAL`, or `UNAVAILABLE`) and `datetimeAvailabilityReason`. Elapsed text with no date stays null. A stored project gets `sourceDatetime` only when its saved measurement is still inside the read fence and, when a source fingerprint is stored, that fingerprint still matches the file. `stellar.decoder.datetime-zone` does not change `sourceDatetime`.

`sampleCount` is the full selected series. `returnedSampleCount` on a signal is the page slice. The envelope `totalSampleCount` is the selected dataset. The envelope `returnedSampleCount` is this page. `measurementFiles` and `dbcFiles` are file names only. A name that occurs once in the selected dataset is listed once. Channel and file identity stay on `sourceId`, `signalIdentity`, `messageName`, `canId`, and `channel`.

The plain signal name is the object key only when that name occurs once in the complete selected dataset. Otherwise the key is:

```text
name|sourceFile|messageName|canId|channel|sourceDatabase
```

A key that is still not unique gains `|sourceId` and, if needed, `#n`. Keys are chosen from the full selected dataset before paging, so a later page uses the same key.

`valueLimitations` states the rounding and non-finite rules. A JSON number second cannot preserve every nanosecond once the magnitude exceeds 2^53. `timestampsNs` remains exact. No inactive multiplex sample is inserted. Invalid samples are not rewritten as zero.

## Compatibility

Schema 1.0 returned `signals` as an array of objects with nested `raw` and `physical` values. That success shape is retired. Clients must read `schemaVersion` and treat `signals` as an object. `decoder_validation_client.py` appends the parallel arrays across `nextCursor` and checks lengths, identities, and sample indexes.

## Samples captured on 127.0.0.1:8091

These files are the HTTP bodies, not hand-written examples:

- `decoder-validation-samples/file-all-signals.json`
- `decoder-validation-samples/file-one-signal-torque.json`
- `decoder-validation-samples/file-many-signals.json`
- `decoder-validation-samples/file-page.json`
- `decoder-validation-samples/file-page-2.json`
- `decoder-validation-samples/file-duplicate-names.json`
- `decoder-validation-samples/project-catalog-missing.json`
- `decoder-validation-samples/project-all-signals.json`
- `decoder-validation-samples/project-one-signal.json`
- `decoder-validation-samples/project-many-signals.json`
- `decoder-validation-samples/project-page-2.json`
- `decoder-validation-samples/error-signal-ambiguous.json`
- `decoder-validation-samples/error-project-signal-ambiguous.json`

`errors.json` records the schema 1.0 error catalog. Those error codes are unchanged. These success captures were taken before `sourceDatetime` existed. They contain signal values, timestamps, and identities, and they do not contain `datetime` or `sourceDatetime`. They were not rewritten, because a new body would be a new capture rather than the recorded response.

## QA engineer setup

Application version stays `1.0.4`. Replace each placeholder with a directory or file on the QA machine. Do not copy a path from another computer. Separate `allowed-roots` entries with semicolons. In PowerShell, quote every Maven `-D` argument.

### Fresh MF4 or DBC decode

This path does not open a saved-project database. Leave `STELLAR_DECODER_METADATA_DB` unset. `<session-directory>` must be an empty writable folder. It receives temporary decode output and is not a customer project.

```powershell
$env:STELLAR_DECODER_ALLOWED_ROOTS = "<measurement-directory>;<dbc-directory>"
$env:STELLAR_DECODER_SESSION_DIRECTORY = "<session-directory>"
mvn -pl stellar-server spring-boot:run "-Dspring-boot.run.profiles=decoder"
```

POST `http://127.0.0.1:8091/api/v1/decoder/validate`. In JSON, a Windows path uses escaped backslashes.

```json
{
  "filePath": "<measurement-directory>\\sample.mf4",
  "dbcPath": "<dbc-directory>\\sample.dbc",
  "pageSize": 10000
}
```

Native MF4 or MDF may omit `dbcPath`. A DAT or LOG file is decoded by the reader that matches its contents: an MDF 3 or MDF 4 recording uses the MDF reader and may omit `dbcPath`; a timestamped text table uses the text reader and may also omit `dbcPath`. ASC and BLF require `dbcPath`. A text file that is not a timestamped table is `UNSUPPORTED_FORMAT`.

If `STELLAR_DECODER_ALLOWED_ROOTS` is empty, or either file sits outside those directories, the response is HTTP 403 `PATH_NOT_ALLOWED`. A missing file inside an allowed directory is HTTP 404 `FILE_NOT_FOUND`.

A successful body is schema `2.0` and `applicationVersion` `1.0.4`. The tester does not send a date, time, or timezone. MF4, ASC, BLF, and MDF 3 DAT/LOG files return `sourceDatetime` from the recording clock stored in the file, plus each relative sample offset, formatted `yyyy-MM-dd HH:mm:ss.SSS`. A text timestamp that already contains a date is printed as written. Elapsed text with no date returns `sourceDatetime` null and `datetimeAvailability` `UNAVAILABLE`.

### Stored project

Copy an existing SQLite catalog onto the QA machine, or use one the Desktop application has already created. The API opens `<existing-project-catalog>` read-only. It does not create, migrate, or rewrite that file. The measurement files recorded in the catalog must still be readable under `allowed-roots` or under the catalog's analysis folder when datetime or source checks need them. Signal samples themselves are read from the saved lossless stores.

```powershell
$env:STELLAR_DECODER_ALLOWED_ROOTS = "<measurement-directory>;<dbc-directory>"
$env:STELLAR_DECODER_METADATA_DB = "<existing-project-catalog>"
$env:STELLAR_DECODER_SESSION_DIRECTORY = "<session-directory>"
mvn -pl stellar-server spring-boot:run "-Dspring-boot.run.profiles=decoder"
```

```json
{"projectName":"<project-name>","pageSize":10000}
```

`<project-name>` is the name stored in that catalog. One series:

```json
{"projectName":"<project-name>","signal":"<signal-name>","pageSize":10000}
```

A plain `<signal-name>` that matches more than one series returns HTTP 409 `SIGNAL_AMBIGUOUS`. The response lists the distinct `canonicalId` values. `signalNames` returns every series with those names, including the same name from another file, message, or channel:

```json
{"projectName":"<project-name>","signalNames":["<signal-name>","<other-signal-name>"],"pageSize":10000}
```

An unset catalog path returns HTTP 500 `Project catalog is not configured.` A path that is set but is not a file returns HTTP 500 `Project catalog is not available.` The API does not create the database.

### Pagination and identity

Follow `nextCursor` with a body that contains only the cursor. `pageSize` may change. Do not repeat `projectName`, `filePath`, `signal`, or `signalNames`. Stop when `paginationComplete` is true. The cursor expires after 1800 seconds. The same sample index keeps the same `timestampsNs`, `datetime`, and physical value on every page.

When one plain name belongs to more than one series, the object key is `name|sourceFile|messageName|canId|channel|sourceDatabase`, plus `|sourceId` and `#n` if that is still not unique. `canonicalId` stays on each signal object.

### Optional Python client

Python is not part of API startup. Where Python 3 is already installed, this client only checks schema 2.0 pages. It does not decode measurements and it does not call asammdf.

```text
python stellar-server/decoder_validation_client.py --request @request.json
```

The client reads `signals` as an object and appends `timestamps`, `timestampsNs`, `samples`, `rawSamples`, `textValues`, `validity`, and `sampleIndex`. It rejects a short page, a changed identity, a duplicate identity, or a `sampleIndex` gap. Comparing results with asammdf is a separate optional check. Historical notes from one development machine are in `decoder-validation-qa.md` and `decoder-validation-asammdf-parity.md`. Those notes are not the setup procedure.

### Desktop from source

JDK 21 and Maven 3.9 or newer are required. JavaFX 21.0.6 is downloaded by Maven. From the repository root:

```powershell
mvn -pl stellar-desktop -am -DskipTests install
mvn -pl stellar-desktop org.openjfx:javafx-maven-plugin:run
```

Run the install command again after any sibling module changes. The first launch creates `%USERPROFILE%\.stellarai`. Create a new project there, then import a measurement and DBC from `<measurement-directory>` and `<dbc-directory>`.

### Desktop installer

Building the Setup program needs JDK 21, Maven, and WiX Toolset 3 (`candle.exe` and `light.exe`). WiX 4, 5, and 7 cannot build this installer. From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\BUILD_WINDOWS_TESTER_INSTALLER.ps1 -SkipTests -InstallerOutputDir <installer-output-directory>
```

The build writes `StellarAI-Setup-1.0.4.exe` in `<installer-output-directory>`. A QA machine that only runs the installer does not need JDK or Maven. After installation, launch StellarAI from the Start Menu. Packaged logs and scratch data are under `%LOCALAPPDATA%\StellarAI`. Analysis-storage settings remain under `%USERPROFILE%\.stellarai\config`.

### QA handoff checklist

Desktop:

- [ ] The application window opens from the source commands, or from the installed Start Menu shortcut.
- [ ] A new project can be created on this machine.
- [ ] An MF4 file and a DBC from this machine import.
- [ ] Decoded signals appear for that import.
- [ ] A signal can be added to the graph and the trace draws.
- [ ] The project saves, the application restarts, and the same project reopens with the same signals.
- [ ] An already processed project supplied for this QA drop opens without reprocessing.

Decoder API:

- [ ] The decoder profile listens on `127.0.0.1:8091`, and port 8091 was free before startup.
- [ ] Fresh `filePath` plus `dbcPath` returns HTTP 200 without `STELLAR_DECODER_METADATA_DB`.
- [ ] The body is schema `2.0` and `applicationVersion` is `1.0.4`.
- [ ] An MF4 with a valid header time returns `datetimeAvailability` `AVAILABLE` and one `sourceDatetime` per sample aligned with `timestampsNs`. A valid file offset is included. The body has no `datetime` or `datetimeTimezone` field.
- [ ] `nextCursor` returns the following samples once, with the same values as the first page for indexes already returned.
- [ ] A plain `signal` name that exists on more than one series returns HTTP 409 `SIGNAL_AMBIGUOUS`, and `signalNames` returns each `canonicalId`.
- [ ] An empty `allowed-roots` value, or a file outside the configured directories, returns HTTP 403 `PATH_NOT_ALLOWED`.
- [ ] With `<existing-project-catalog>` set, `projectName` returns the stored samples and the catalog file's size and last-write time stay unchanged.

## Known limitations

- `KILOMETRAGE` in `b1-3.mf4` is one Java series. asammdf keeps channel groups 5, 20, and 68 separate (576 + 594 + 579). Concatenating those groups in group order matches the Java samples. Identities are not split.
- asammdf 8.8.28 reads MDF. It has no ASC reader. `MDF` on a Bengaluru `.asc` file fails because the header is not an MDF magic. Do not treat ASC results as an asammdf comparison.
- A saved project returns the samples stored when it was processed. It is not reprocessed by this API.
- CC types other than 7 and 8 still return `CONVERSION_FAILED` when the conversion cannot be applied. A new decode of CC type 7 or 8 stores the source label, `VALID`, and a non-finite sample.
- A Vector BLF `SYSTEMTIME`, an ASC `date` line, and an MDF 3 HD date are shown as written. The API does not add a timezone. Elapsed text with no date stays null.
- A DAT or LOG file is read by the parser that matches its contents. MDF 3 uses the MDF reader and does not need a DBC. A timestamped text table also omits `dbcPath`. An ISO value is printed from its own clock. Elapsed columns such as `time_s` have no recording date, so `sourceDatetime` is null. The text reader still stores an unzoned ISO value in `timestampsNs` as if it were UTC; `sourceDatetime` prints those same clock digits.
- Native DAT and LOG signals keep the existing measurement identity tag `MF4_MEASUREMENT`.
- asammdf reads MDF. It does not verify ASC, BLF, DAT, or LOG.

Troubleshooting:

- `Project catalog is not configured.` means `stellar.decoder.metadata-db` was not set. `Project catalog is not available.` means the path was set and is not a database file. The API does not create or migrate that file.
- `SIGNAL_AMBIGUOUS` means the plain `signal` name matches more than one series. Use `signalNames` or select one recording with `filePath`.
- An empty `allowed-roots` list, or a path outside that list, returns HTTP 403 `PATH_NOT_ALLOWED`. The decoder profile does not publish `/health` or `/api/v1/projects`.
- Port 8091 must be free before startup. An error body stays schema 1.0.
