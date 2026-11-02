[CmdletBinding()]
param(
    [string]$AppName = "StellarAI",
    [string]$AppVersion = "",
    [string]$Vendor = "StellarAI",
    [string]$Description = "StellarAI Automotive Engineering Platform",
    [string]$InstallDirName = "StellarAI",
    [string]$Icon = "",
    [switch]$SkipMaven,
    [switch]$SkipTests,
    [switch]$SkipSmoke,
    [switch]$ValidateInstall,
    [switch]$AppImageOnly,
    [string]$InstallerOutputDir = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Require-Command([string]$Name, [string]$Hint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name was not found. $Hint"
    }
}

function Add-PathFront([string]$Dir) {
    if ([string]::IsNullOrWhiteSpace($Dir)) { return }
    if (-not (Test-Path $Dir)) { return }
    if ($env:Path -like "*$Dir*") { return }
    $env:Path = "$Dir;$env:Path"
}

function Write-Section([string]$Message) {
    Write-Host ""
    Write-Host $Message -ForegroundColor Cyan
}

function Read-StellarAppVersion {
    $props = Join-Path $PSScriptRoot "stellar-desktop\src\main\resources\stellar-version.properties"
    if (Test-Path $props) {
        foreach ($line in Get-Content $props) {
            if ($line -match '^\s*application\.version\s*=\s*(.+)\s*$') {
                return $Matches[1].Trim()
            }
        }
    }
    return "1.0.4"
}

if ([string]::IsNullOrWhiteSpace($AppVersion)) {
    $AppVersion = Read-StellarAppVersion
}

function ConvertTo-JpackageVersion([string]$ProductVersion) {
    if ($ProductVersion -match '^(\d+\.\d+\.\d+)') {
        return $Matches[1]
    }
    return "1.0.4"
}

# jpackage/WiX require numeric major.minor.micro. A pre-release suffix, if present, is stripped.
$WindowsPackageVersion = ConvertTo-JpackageVersion $AppVersion

# Use System.Diagnostics.Process so argument quoting and ExitCode are correct
# under Windows PowerShell 5.1 (Start-Process -ArgumentList mishandles spaces).
function Invoke-External {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$ArgumentList = @()
    )
    $resolved = (Get-Command $FilePath -ErrorAction Stop).Source
    $quoted = foreach ($a in @($ArgumentList)) {
        if ($null -eq $a) { continue }
        $s = [string]$a
        if ($s -match '[\s"]') {
            '"' + ($s.Replace('"', '\"')) + '"'
        } else {
            $s
        }
    }
    $argString = ($quoted -join ' ')
    Write-Host ("exec: {0} {1}" -f $resolved, $argString) -ForegroundColor DarkGray

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $resolved
    $psi.Arguments = $argString
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $false
    $psi.RedirectStandardError = $false
    $psi.CreateNoWindow = $false
    $psi.WorkingDirectory = (Get-Location).Path

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    if (-not $proc.Start()) {
        throw "Failed to start process: $resolved"
    }
    $proc.WaitForExit()
    return [int]$proc.ExitCode
}

function Assert-InstallerExe([System.IO.FileInfo]$Installer, [System.IO.FileInfo]$AppLauncher) {
    if (-not $Installer -or -not (Test-Path $Installer.FullName)) {
        throw "Installer EXE was not produced."
    }
    if ($Installer.Length -lt 10MB) {
        throw "Installer EXE is unexpectedly small ($([math]::Round($Installer.Length/1MB,2)) MB). Packaging likely incomplete."
    }
    if ($AppLauncher -and (Test-Path $AppLauncher.FullName)) {
        if ([string]::Equals($Installer.FullName, $AppLauncher.FullName, [StringComparison]::OrdinalIgnoreCase)) {
            throw "FATAL: Installer path equals app-image launcher path. Refusing to treat launcher as installer."
        }
        if ($Installer.Length -lt ($AppLauncher.Length * 10)) {
            throw "FATAL: Candidate installer is not substantially larger than the app-image launcher. Wrong artifact."
        }
    }

    $vi = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($Installer.FullName)
    $desc = [string]$vi.FileDescription
    $product = [string]$vi.ProductName
    if ($desc -notmatch '(?i)installer' -and $product -notmatch '(?i)installer') {
        throw @"
FATAL: Produced EXE does not look like a Windows installer.
  Path: $($Installer.FullName)
  ProductName: $product
  FileDescription: $desc

This is likely the app-image launcher, not jpackage --type exe output.
"@
    }

    # App-image launcher is a thin stub (~hundreds of KB). Installer embeds runtime (~100+ MB).
    Write-Host "Installer identity OK:" -ForegroundColor Green
    Write-Host "  ProductName     = $product"
    Write-Host "  FileDescription = $desc"
    Write-Host ("  Size            = {0:N1} MB" -f ($Installer.Length / 1MB))
}

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

Write-Host "=== StellarAI Windows Packaged Runtime Build ===" -ForegroundColor Cyan
Write-Host "Repository: $Root"
Write-Host "Product version: $AppVersion"
Write-Host "jpackage numeric version: $WindowsPackageVersion (Windows/MSI cannot use -rc suffixes)"
if ($AppImageOnly) {
    Write-Host "Mode: APP-IMAGE ONLY (installer not required)."
} else {
    Write-Host "Goal: app-image + installer when WiX 3 candle.exe/light.exe are available."
}

Require-Command "java" "Install JDK 21 and set JAVA_HOME/PATH."
Require-Command "mvn" "Install Apache Maven 3.9+ and add it to PATH."
Require-Command "jpackage" "Use a full JDK 21 installation; jpackage ships with the JDK."

# java -version writes to stderr; probe via cmd so PowerShell Stop does not treat it as failure.
$javaVersionOutput = @(& cmd.exe /d /c "java -version 2>&1")
if ($LASTEXITCODE -ne 0 -or -not $javaVersionOutput) {
    throw "Unable to execute java -version. Verify JDK 21 and PATH."
}
$javaVersion = [string]($javaVersionOutput | Select-Object -First 1)
if ($javaVersion -notmatch '"21[\.]') {
    throw "JDK 21 is required. Detected: $javaVersion"
}
Write-Host "Java detected: $javaVersion" -ForegroundColor Green

$mvnVersionLine = [string]((& cmd.exe /d /c "mvn -version 2>&1") | Select-Object -First 1)
Write-Host "Maven detected: $mvnVersionLine" -ForegroundColor Green

$jpackageVersion = [string]((& cmd.exe /d /c "jpackage --version 2>&1") | Select-Object -First 1)
Write-Host "jpackage detected: $jpackageVersion" -ForegroundColor Green

# Prefer repo-local portable WiX 3.14 (tools\wix314), then Program Files installs.
# JDK 21 jpackage requires WiX 3.x candle.exe/light.exe - WiX 4/5/7 (wix.exe) cannot create the EXE.
$wix3SearchRoots = @(
    (Join-Path $Root "tools\wix314"),
    "${env:ProgramFiles(x86)}\WiX Toolset v3.14\bin",
    "${env:ProgramFiles}\WiX Toolset v3.14\bin",
    "${env:ProgramFiles(x86)}\WiX Toolset v3.11\bin",
    "${env:ProgramFiles}\WiX Toolset v3.11\bin"
)
$wixBinDir = $null
foreach ($wixBin in $wix3SearchRoots) {
    if ((Test-Path -LiteralPath (Join-Path $wixBin "candle.exe") -PathType Leaf) -and (Test-Path -LiteralPath (Join-Path $wixBin "light.exe") -PathType Leaf)) {
        $wixBinDir = $wixBin
        Add-PathFront $wixBin
        break
    }
}
$hasWix3 = $false
if ($wixBinDir) {
    $hasWix3 = $true
    Write-Host "WiX 3 detected: $wixBinDir" -ForegroundColor Green
} elseif ((Get-Command candle.exe -ErrorAction SilentlyContinue) -and (Get-Command light.exe -ErrorAction SilentlyContinue)) {
    $hasWix3 = $true
    $wixBinDir = Split-Path -Parent (Get-Command candle.exe).Source
    Write-Host "WiX 3 detected on PATH: $wixBinDir" -ForegroundColor Green
}
if (-not $hasWix3) {
    Write-Host @"
WiX Toolset 3.x (candle.exe / light.exe) is NOT available.
JDK 21 jpackage cannot create EXE/MSI without WiX 3.

APP-IMAGE qualification will continue.
Installer status: NOT TESTED / BLOCKED BY BUILD TOOLING
Prerequisite: WiX 3 candle.exe and light.exe.
  1) winget install --id WiXToolset.WiXToolset -e
  2) Or extract WiX 3.14 binaries into: $Root\tools\wix314\

This script will NOT download or install WiX.
"@ -ForegroundColor Yellow
}

# Optional icon - never invent one.
if (-not $Icon) {
    $candidates = @(
        (Join-Path $Root "stellar-desktop\src\main\resources\icons\stellarai.ico"),
        (Join-Path $Root "stellar-desktop\src\main\resources\icon.ico"),
        (Join-Path $Root "packaging\stellarai.ico"),
        (Join-Path $Root "StellarAI.ico")
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { $Icon = $c; break }
    }
}
if ($Icon -and (Test-Path $Icon)) {
    Write-Host "Icon: $Icon" -ForegroundColor Green
} else {
    Write-Host "Icon: none (no .ico found; packaging without custom icon)" -ForegroundColor Yellow
    $Icon = ""
}

# release/ separates FINAL installer from intermediate app-image.
$ReleaseRoot = Join-Path $Root "release"
$IntermediateRoot = Join-Path $ReleaseRoot "intermediate"
$InputDir = Join-Path $IntermediateRoot "input"
$DepsDir = Join-Path $IntermediateRoot "deps"
$AppImageDir = Join-Path $IntermediateRoot "app-image"
$InstallerDir = Join-Path $ReleaseRoot "installer"
if (-not [string]::IsNullOrWhiteSpace($InstallerOutputDir)) {
    $InstallerDir = $InstallerOutputDir
}
$LegacyDist = Join-Path $Root "dist-windows"

function Remove-TreeBestEffort {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [int]$Attempts = 4,
        [switch]$Required
    )
    if (-not (Test-Path -LiteralPath $Path)) { return $true }

    Write-Host "Cleaning previous packaging output: $Path" -ForegroundColor Yellow

    for ($i = 1; $i -le $Attempts; $i++) {
        try {
            Get-ChildItem -LiteralPath $Path -Recurse -Force -ErrorAction SilentlyContinue |
                Where-Object { -not $_.PSIsContainer } |
                ForEach-Object {
                    try { $_.Attributes = 'Normal' } catch { }
                    try { Remove-Item -LiteralPath $_.FullName -Force -ErrorAction Stop } catch { }
                }
            Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Stop
            return $true
        } catch {
            if ($i -eq $Attempts) { break }
            Write-Host ("  retry {0}/{1}: {2}" -f $i, $Attempts, $_.Exception.Message) -ForegroundColor DarkYellow
            Start-Sleep -Seconds ([Math]::Min(2 * $i, 6))
        }
    }

    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $parent = Split-Path -Parent $Path
    $leaf = Split-Path -Leaf $Path
    $quarantine = Join-Path $parent ($leaf + ".locked-" + $stamp)
    try {
        Move-Item -LiteralPath $Path -Destination $quarantine -Force -ErrorAction Stop
        Write-Host "WARNING: could not delete locked folder; moved aside to:" -ForegroundColor Yellow
        Write-Host "  $quarantine" -ForegroundColor Yellow
        return $true
    } catch {
        Write-Host "WARNING: leaving locked folder in place:" -ForegroundColor Yellow
        Write-Host "  $Path" -ForegroundColor Yellow
        Write-Host "  $($_.Exception.Message)" -ForegroundColor DarkYellow
        if ($Required) {
            throw @"
Cannot clean required packaging output because it is locked:
  $Path

Close Explorer on that folder, exit StellarAI-Setup, stop stuck msiexec if needed:
  Get-Process msiexec | Stop-Process -Force

Then re-run this script.
"@
        }
        return $false
    }
}

function Clear-CurrentVersionInstallerFiles {
    param([string]$Dir, [string]$Version)
    if (-not (Test-Path -LiteralPath $Dir)) { return }
    # Rebuilds replace only this version. Older setups, including StellarAI-Setup-1.0.1.exe, stay.
    foreach ($name in @("StellarAI-Setup-$Version.exe", "StellarAI-$Version.exe")) {
        $path = Join-Path $Dir $name
        if (Test-Path -LiteralPath $path) {
            Write-Host "Replacing current-version installer output only: $path" -ForegroundColor Yellow
            Remove-Item -LiteralPath $path -Force
        }
    }
}

function Resolve-WritableInstallerDir {
    param([string]$Preferred)
    $candidates = @(
        $Preferred,
        (Join-Path $ReleaseRoot ("installer-" + (Get-Date -Format "yyyyMMdd-HHmmss"))),
        (Join-Path $Root ("release-out\installer-" + (Get-Date -Format "yyyyMMdd-HHmmss")))
    )
    foreach ($candidate in $candidates) {
        try {
            # Do not delete an existing installer directory. It holds previous versioned EXEs.
            New-Item -ItemType Directory -Force -Path $candidate -ErrorAction Stop | Out-Null
            # Prove we can create and delete a temp file here.
            $probe = Join-Path $candidate (".write-probe-" + [guid]::NewGuid().ToString("N"))
            Set-Content -LiteralPath $probe -Value "ok" -Encoding ASCII -ErrorAction Stop
            Remove-Item -LiteralPath $probe -Force -ErrorAction Stop
            return (Resolve-Path -LiteralPath $candidate).Path
        } catch {
            Write-Host ("Installer dir not usable yet: {0} ({1})" -f $candidate, $_.Exception.Message) -ForegroundColor DarkYellow
        }
    }
    throw "No writable installer output directory could be created. Close locks on release\ and retry."
}

# Clean previous packaging output (best-effort). Never require deleting a locked installer folder.
Remove-TreeBestEffort -Path $LegacyDist -Attempts 3 | Out-Null
Remove-TreeBestEffort -Path $AppImageDir -Attempts 3 | Out-Null
Remove-TreeBestEffort -Path $InputDir -Attempts 3 | Out-Null
Remove-TreeBestEffort -Path $DepsDir -Attempts 3 | Out-Null
# Intermediate can be recreated even if a nested locked folder remains.
New-Item -ItemType Directory -Force -Path $IntermediateRoot | Out-Null
# Preserve previous installers. Remove only this product version's setup files.
Clear-CurrentVersionInstallerFiles -Dir $InstallerDir -Version $AppVersion
$InstallerDir = Resolve-WritableInstallerDir -Preferred $InstallerDir
Write-Host "Installer output directory: $InstallerDir" -ForegroundColor Green
New-Item -ItemType Directory -Force -Path $InputDir, $DepsDir, $AppImageDir | Out-Null

if (-not $SkipMaven) {
    if ($SkipTests) {
        Write-Section "[1/7] Building reactor without tests (mvn install -DskipTests)..."
        $mvnExit = Invoke-External -FilePath mvn -ArgumentList @('install', '-DskipTests')
        if ($mvnExit -ne 0) { throw "[1/7] Maven package failed. Installer was NOT created." }
    } else {
        Write-Section "[1/7] Building and testing reactor (mvn clean install)..."
        $mvnExit = Invoke-External -FilePath mvn -ArgumentList @('clean', 'install')
        if ($mvnExit -ne 0) { throw "[1/7] Maven build/tests failed. Installer was NOT created." }
    }
} else {
    Write-Section "[1/7] Skipping Maven (-SkipMaven). Reusing existing module artifacts."
}

Write-Section "[2/7] Collecting runtime dependencies..."
$depExit = Invoke-External -FilePath mvn -ArgumentList @(
    '-f', (Join-Path $Root "stellar-desktop\pom.xml"),
    'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
    '-DincludeScope=runtime',
    "-DoutputDirectory=$DepsDir"
)
if ($depExit -ne 0) { throw "[2/7] Runtime dependency resolution failed. Installer was NOT created." }

New-Item -ItemType Directory -Force -Path $InputDir | Out-Null
Get-ChildItem $DepsDir -Filter "*.jar" | Copy-Item -Destination $InputDir

# First-party modules MUST come from this reactor's module target/, never a stale ~/.m2 SNAPSHOT.
$FirstPartyModules = @(
    'stellar-desktop',
    'stellar-api',
    'stellar-domain',
    'stellar-parser-spi',
    'stellar-parser-mf4',
    'stellar-parser-asc',
    'stellar-parser-blf',
    'stellar-parser-text',
    'stellar-dbc-core',
    'stellar-a2l-core',
    'stellar-odx-pdx-core',
    'stellar-processing-core',
    'stellar-analysis-core',
    'stellar-storage-core'
)

function Get-ReactorModuleJar([string]$Module) {
    $target = Join-Path $Root "$Module\target"
    if (-not (Test-Path -LiteralPath $target)) {
        throw "[2/7] Missing $Module\target. Run without -SkipMaven so the current reactor is built."
    }
    $jar = Get-ChildItem -LiteralPath $target -Filter "$Module-*.jar" |
        Where-Object { $_.Name -notmatch '(sources|javadoc|tests)' } |
        Select-Object -First 1
    if (-not $jar) {
        throw "[2/7] $Module runtime JAR was not found in $target. Run without -SkipMaven."
    }
    return $jar
}

foreach ($mod in $FirstPartyModules) {
    Get-ChildItem -LiteralPath $InputDir -Filter "$mod-*.jar" -ErrorAction SilentlyContinue |
        Remove-Item -Force
    $reactorJar = Get-ReactorModuleJar $mod
    Copy-Item -LiteralPath $reactorJar.FullName -Destination (Join-Path $InputDir $reactorJar.Name) -Force
    Write-Host ("Reactor module: {0}  {1:yyyy-MM-dd HH:mm:ss}  {2} bytes" -f $reactorJar.Name, $reactorJar.LastWriteTime, $reactorJar.Length) -ForegroundColor Green
}

function Assert-NoDuplicateFirstPartyJars([string]$Dir) {
    $groups = @{}
    Get-ChildItem -LiteralPath $Dir -Filter "stellar-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '(sources|javadoc|tests)' } |
        ForEach-Object {
            $artifact = $null
            foreach ($mod in ($FirstPartyModules | Sort-Object { $_.Length } -Descending)) {
                if ($_.Name -eq "$mod.jar" -or $_.Name.StartsWith("$mod-")) {
                    $artifact = $mod
                    break
                }
            }
            if ($artifact) {
                if (-not $groups.ContainsKey($artifact)) { $groups[$artifact] = @() }
                $groups[$artifact] += $_.FullName
            }
        }
    $dupes = @()
    foreach ($key in $groups.Keys) {
        $unique = @($groups[$key] | Select-Object -Unique)
        if ($unique.Count -gt 1) {
            $dupes += "$key -> $($unique -join ' | ')"
        }
    }
    if ($dupes.Count -gt 0) {
        throw "[2/7] Duplicate first-party StellarAI JARs in ${Dir}:`n$($dupes -join "`n")"
    }
}

function Assert-PackagedBytecodeContract([string]$Dir) {
    $apiJar = Get-ChildItem -LiteralPath $Dir -Filter "stellar-api-*.jar" |
        Where-Object { $_.Name -notmatch '(sources|javadoc|tests)' } |
        Select-Object -First 1
    $storageJar = Get-ChildItem -LiteralPath $Dir -Filter "stellar-storage-core-*.jar" |
        Where-Object { $_.Name -notmatch '(sources|javadoc|tests)' } |
        Select-Object -First 1
    if (-not $apiJar) { throw "[2/7] stellar-api JAR missing from $Dir" }
    if (-not $storageJar) { throw "[2/7] stellar-storage-core JAR missing from $Dir" }

    $wp = & javap -classpath $apiJar.FullName -public com.stellar.platform.api.worker.WorkerProtocol 2>&1 | Out-String
    if ($wp -notmatch 'isSuccessfulTerminal\s*\(\s*java\.lang\.String\s*\)') {
        throw "[2/7] WorkerProtocol.isSuccessfulTerminal(String) missing from $($apiJar.FullName)`n$wp"
    }
    $iw = & javap -classpath $apiJar.FullName -public com.stellar.platform.api.worker.ProcessIsolatedLocalWorker 2>&1 | Out-String
    if ($iw -notmatch 'requestCancel\s*\(\s*java\.lang\.String\s*\)') {
        throw "[2/7] ProcessIsolatedLocalWorker.requestCancel(String) missing from $($apiJar.FullName)`n$iw"
    }
    $store = & javap -classpath $storageJar.FullName -public com.stellar.platform.storage.metadata.SqliteProjectMetadataStore 2>&1 | Out-String
    if ($store -notmatch 'checkpointWal\s*\(\s*\)') {
        throw "[2/7] SqliteProjectMetadataStore.checkpointWal() missing from $($storageJar.FullName)`n$store"
    }
    $processingJar = Get-ChildItem -LiteralPath $Dir -Filter "stellar-processing-core-*.jar" |
        Where-Object { $_.Name -notmatch '(sources|javadoc|tests)' } |
        Select-Object -First 1
    if (-not $processingJar) { throw "[2/7] stellar-processing-core JAR missing from $Dir" }
    $exportEntry = 'com/stellar/platform/processing/export/AlignedSignalCsvExporter$ExportResult.class'
    $listed = @(& jar tf $processingJar.FullName)
    if ($listed -notcontains $exportEntry) {
        throw "[2/7] $exportEntry is missing from $($processingJar.FullName). Signals FXML cannot open a project against this processing-core JAR."
    }
    Write-Host "Bytecode contract: isSuccessfulTerminal / requestCancel / checkpointWal / ExportResult present." -ForegroundColor Green
}

Assert-NoDuplicateFirstPartyJars $InputDir
Assert-PackagedBytecodeContract $InputDir

$DesktopJar = Get-ChildItem -LiteralPath $InputDir -Filter "stellar-desktop-*.jar" |
    Where-Object { $_.Name -notmatch '(sources|javadoc|tests)' } |
    Select-Object -First 1
if (-not $DesktopJar) { throw "[2/7] stellar-desktop runtime JAR was not found in the payload." }

# Verify bootstrap launcher is present in the desktop JAR (prevents JavaFX Application main-class bug).
$MainClass = "com.stellar.platform.desktop.StellarDesktopLauncher"
$launcherCheck = & jar tf $DesktopJar.FullName | Select-String -SimpleMatch "com/stellar/platform/desktop/StellarDesktopLauncher.class"
if (-not $launcherCheck) {
    throw "[2/7] $MainClass is missing from $($DesktopJar.Name). Installer was NOT created."
}
Write-Host "Main launcher verified: $MainClass" -ForegroundColor Green

# Hard gate: launcher must NOT extend javafx.application.Application (packaging class of defect).
$javapOut = & javap -classpath $DesktopJar.FullName -public $MainClass 2>&1 | Out-String
if ($javapOut -match 'extends\s+javafx\.application\.Application') {
    throw "[2/7] $MainClass extends Application and would trigger JavaFX runtime components are missing under jpackage."
}
Write-Host "Launcher Application-extend check passed." -ForegroundColor Green

# Require JavaFX Windows natives + controls/fxml/web modules on the payload classpath.
$requiredFx = @('javafx-controls', 'javafx-fxml', 'javafx-graphics', 'javafx-base', 'javafx-web')
foreach ($fxName in $requiredFx) {
    $hit = Get-ChildItem $InputDir -Filter "$fxName-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $hit) {
        throw "[2/7] Missing required JavaFX module JAR: $fxName-*.jar"
    }
}

$requiredModules = @(
    'stellar-desktop',
    'stellar-api',
    'stellar-processing-core',
    'stellar-storage-core',
    'stellar-parser-mf4',
    'stellar-parser-text',
    'stellar-dbc-core',
    'stellar-analysis-core',
    'sqlite-jdbc'
)
foreach ($mod in $requiredModules) {
    $hit = Get-ChildItem $InputDir -Filter "$mod-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $hit) {
        throw "[2/7] Missing required runtime JAR: $mod-*.jar"
    }
    Write-Host ("Packaged module: {0}" -f $hit.Name) -ForegroundColor Green
}
$leaflet = Get-ChildItem $InputDir -Filter "leaflet-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $leaflet) {
    Write-Host "WARNING: leaflet-*.jar not found; local map assets may be incomplete." -ForegroundColor Yellow
}

# Safety gate: tester payload must contain runtime artifacts only.
$forbidden = Get-ChildItem $InputDir -Recurse -File | Where-Object {
    $_.Extension -in '.java', '.kt', '.groovy', '.md', '.ps1' -or $_.Name -eq 'pom.xml'
}
if ($forbidden) {
    $names = ($forbidden.FullName -join "`n")
    throw "[2/7] Source/development files entered the installer payload. Packaging stopped:`n$names"
}

# Do not ship a developer's stellar.db
$dbLeak = Get-ChildItem $InputDir -Recurse -Filter "stellar.db" -ErrorAction SilentlyContinue
if ($dbLeak) {
    throw "[2/7] stellar.db must not be packaged. Remove it from the payload."
}

$fxWin = @(Get-ChildItem $InputDir -Filter "javafx-*-win.jar" -ErrorAction SilentlyContinue)
if ($fxWin.Count -lt 1) {
    throw "[2/7] No javafx-*-win.jar found. Windows JavaFX natives are required. Installer was NOT created."
}
Write-Host ("JavaFX Windows artifacts: {0}" -f ($fxWin.Name -join ", ")) -ForegroundColor Green

$sqliteJar = Get-ChildItem $InputDir -Filter "sqlite-jdbc-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $sqliteJar) {
    throw "[2/7] sqlite-jdbc JAR missing from runtime dependencies. Installer was NOT created."
}
Write-Host "SQLite JDBC packaged: $($sqliteJar.Name)" -ForegroundColor Green

$MainJar = $DesktopJar.Name
$desktopVersionOpt = "-Dstellar.desktop.version=$AppVersion"
$BaseArgs = @(
    '--name', $AppName,
    '--app-version', $WindowsPackageVersion,
    '--vendor', $Vendor,
    '--description', $Description,
    '--jlink-options', '--strip-debug --no-man-pages --no-header-files',
    '--input', $InputDir,
    '--main-jar', $MainJar,
    '--main-class', $MainClass,
    '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', '-XX:+UseG1GC',
    '--java-options', '-Xms128m',
    '--java-options', '-Xmx1536m',
    # Packaged data home prefers %LOCALAPPDATA%\StellarAI via StellarAiPaths when jpackage.app-path is set.
    '--java-options', $desktopVersionOpt,
    '--java-options', '-Dstellar.execution.mode=LOCAL'
)
if ($Icon) { $BaseArgs += @('--icon', (Resolve-Path $Icon).Path) }

Write-Section "[3/7] Creating self-contained Windows app-image (INTERMEDIATE - not for testers)..."
$AppImageArgs = $BaseArgs + @(
    '--type', 'app-image',
    '--dest', $AppImageDir,
    '--win-console'
)
$jpExit = Invoke-External -FilePath jpackage -ArgumentList $AppImageArgs
if ($jpExit -ne 0) { throw "[3/7] jpackage app-image creation failed. Installer was NOT created." }

$AppLauncher = Get-ChildItem $AppImageDir -Recurse -Filter "$AppName.exe" | Select-Object -First 1
if (-not $AppLauncher) { throw "[3/7] Packaged application launcher was not found." }
Write-Host "INTERMEDIATE app-image launcher (do NOT distribute): $($AppLauncher.FullName)" -ForegroundColor Yellow
Write-Host ('  Size = {0:N2} MB (thin launcher; expected to start the app directly)' -f ($AppLauncher.Length / 1MB))

if (-not $SkipSmoke) {
    Write-Section "[4/7] Smoke-launching packaged app for 12 seconds..."
    $SmokeStdout = Join-Path $IntermediateRoot "smoke-stdout.log"
    $SmokeStderr = Join-Path $IntermediateRoot "smoke-stderr.log"
    Remove-Item $SmokeStdout, $SmokeStderr -Force -ErrorAction SilentlyContinue

    $p = Start-Process `
        -FilePath $AppLauncher.FullName `
        -WorkingDirectory $AppLauncher.DirectoryName `
        -RedirectStandardOutput $SmokeStdout `
        -RedirectStandardError $SmokeStderr `
        -PassThru

    Start-Sleep -Seconds 12

    if ($p.HasExited) {
        $exitCode = $p.ExitCode
        if ($null -eq $exitCode) { $exitCode = -1 }
        Write-Host ("Packaged app exited during smoke test with code {0}." -f $exitCode) -ForegroundColor Red

        foreach ($log in @($SmokeStdout, $SmokeStderr)) {
            if (Test-Path $log) {
                $text = Get-Content $log -Raw -ErrorAction SilentlyContinue
                if ($text) {
                    Write-Host ("--- {0} ---" -f (Split-Path $log -Leaf)) -ForegroundColor Cyan
                    Write-Host $text
                }
            }
        }
        throw "[4/7] Packaged app smoke test failed with code $exitCode. The installer was NOT created."
    }

    Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    try { Wait-Process -Id $p.Id -TimeoutSec 15 -ErrorAction SilentlyContinue } catch { }
    Start-Sleep -Seconds 2

    if (Test-Path $SmokeStderr) {
        $stderrText = Get-Content $SmokeStderr -Raw -ErrorAction SilentlyContinue
        if ($stderrText) {
            $fatalPatterns = @(
                'JavaFX runtime components are missing',
                'ClassNotFoundException',
                'NoClassDefFoundError',
                'NoSuchMethodError',
                'LinkageError',
                'UnsatisfiedLinkError',
                'SQLite',
                'FXMLLoader',
                'Error initializing QuantumRenderer'
            )
            foreach ($pat in $fatalPatterns) {
                if ($stderrText -match [regex]::Escape($pat) -or ($pat -eq 'SQLite' -and $stderrText -match 'org\.sqlite|No suitable driver')) {
                    if ($pat -eq 'SQLite' -and $stderrText -notmatch 'org\.sqlite|No suitable driver|SQLiteException|jdbc:sqlite') {
                        continue
                    }
                    Write-Host $stderrText -ForegroundColor Red
                    throw "[4/7] Smoke stderr indicates a fatal packaged-app error ($pat). Installer was NOT created."
                }
            }
        }
    }
    Write-Host "Packaged launcher stayed alive: smoke test passed." -ForegroundColor Green
} else {
    Write-Section "[4/7] Skipping smoke launch (-SkipSmoke)."
    $SmokeStdout = Join-Path $IntermediateRoot "smoke-stdout.log"
    $SmokeStderr = Join-Path $IntermediateRoot "smoke-stderr.log"
}

Write-Section "[5/7] Rebuilding GUI app-image without console window..."
if (Test-Path $AppImageDir) {
    $removed = $false
    for ($i = 0; $i -lt 8 -and -not $removed; $i++) {
        try {
            Remove-Item $AppImageDir -Recurse -Force -ErrorAction Stop
            $removed = $true
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    if (-not $removed -and (Test-Path $AppImageDir)) {
        throw "[5/7] Could not replace app-image directory; a packaged process may still be locking files."
    }
}
New-Item -ItemType Directory -Force -Path $AppImageDir | Out-Null
$GuiAppImageArgs = $BaseArgs + @(
    '--type', 'app-image',
    '--dest', $AppImageDir
)
$jpGuiExit = Invoke-External -FilePath jpackage -ArgumentList $GuiAppImageArgs
if ($jpGuiExit -ne 0) { throw "[5/7] jpackage GUI app-image creation failed. Installer was NOT created." }
$AppLauncher = Get-ChildItem $AppImageDir -Recurse -Filter "$AppName.exe" | Select-Object -First 1
if (-not $AppLauncher) { throw "[5/7] GUI packaged application launcher was not found." }
$AppImageRoot = $AppLauncher.Directory.FullName
Write-Host "Validated app-image root: $AppImageRoot" -ForegroundColor Green
$PackagedAppDir = Join-Path $AppImageRoot "app"
if (-not (Test-Path -LiteralPath $PackagedAppDir)) {
    throw "[5/7] Packaged app directory missing: $PackagedAppDir"
}
Assert-NoDuplicateFirstPartyJars $PackagedAppDir
Assert-PackagedBytecodeContract $PackagedAppDir

function Get-Sha256File([string]$File) {
    $hash = Get-FileHash -LiteralPath $File -Algorithm SHA256
    return $hash.Hash
}

function Write-AppImageEvidence {
    param(
        [Parameter(Mandatory = $true)][string]$ImageRoot,
        [Parameter(Mandatory = $true)][System.IO.FileInfo]$Launcher
    )
    $qualified = Join-Path $ReleaseRoot ("StellarAI-" + $AppVersion)
    if (Test-Path -LiteralPath $qualified) {
        Remove-TreeBestEffort -Path $qualified -Attempts 4 | Out-Null
    }
    $copyExit = Invoke-External -FilePath robocopy -ArgumentList @(
        $ImageRoot,
        $qualified,
        '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/NC', '/NS', '/NP'
    )
    if ($copyExit -ge 8) {
        throw "robocopy failed copying app-image to $qualified (exit $copyExit)"
    }
    $qualifiedLauncher = Get-ChildItem -LiteralPath $qualified -Filter "$AppName.exe" | Select-Object -First 1
    $runtimeJava = Join-Path $qualified "runtime\bin\java.exe"
    if (-not (Test-Path -LiteralPath $runtimeJava)) {
        throw "Qualified app-image is missing bundled runtime Java: $runtimeJava"
    }
    $appDir = Join-Path $qualified "app"
    if (-not (Test-Path -LiteralPath $appDir)) {
        throw "Qualified app-image is missing app/ directory."
    }
    $workerMain = Get-ChildItem -LiteralPath $appDir -Filter "stellar-api-*.jar" | Select-Object -First 1
    if (-not $workerMain) {
        throw "Qualified app-image is missing stellar-api JAR (worker main class)."
    }
    Assert-NoDuplicateFirstPartyJars $appDir
    Assert-PackagedBytecodeContract $appDir

    $evidenceDir = Join-Path $ReleaseRoot "checksums"
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    $launcherHash = Get-Sha256File $qualifiedLauncher.FullName
    $launcherLine = "SHA256  $launcherHash  $($qualifiedLauncher.Name)  $($qualifiedLauncher.Length) bytes"
    $zipPath = Join-Path $ReleaseRoot ("StellarAI-" + $AppVersion + "-app-image.zip")
    if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
    $zipItem = $null
    $zipLine = "APP-IMAGE ZIP: NOT CREATED"
    try {
        Compress-Archive -Path (Join-Path $qualified '*') -DestinationPath $zipPath -Force
        $zipItem = Get-Item -LiteralPath $zipPath
        $zipHash = Get-Sha256File $zipPath
        $zipLine = "SHA256  $zipHash  $($zipItem.Name)  $($zipItem.Length) bytes"
    } catch {
        Write-Host ("WARNING: could not ZIP app-image ({0}). SHA-256 of launcher is still recorded." -f $_.Exception.Message) -ForegroundColor Yellow
    }
    @(
        "StellarAI $AppVersion APP-IMAGE (not an installer)",
        $launcherLine,
        $zipLine,
        "Layout: $qualified",
        "Bundled Java: $runtimeJava",
        "This ZIP is a portable app-image archive. It is NOT the Windows installer."
    ) | Set-Content -Path (Join-Path $evidenceDir "APP-IMAGE-SHA256.txt") -Encoding ASCII

    $statusPath = Join-Path $ReleaseRoot "PACKAGING-STATUS.txt"
    $wixStatus = if ($hasWix3) { "AVAILABLE at $wixBinDir" } else { "NOT AVAILABLE (candle.exe/light.exe missing)" }
    @"
StellarAI $AppVersion packaging status
=====================================
APP-IMAGE: $qualified
Launcher: $($qualifiedLauncher.FullName)
Bundled Java: $runtimeJava
WiX 3: $wixStatus
Installer: see below
"@ | Set-Content -Path $statusPath -Encoding UTF8

    Write-Host ""
    Write-Host "QUALIFIED APP-IMAGE:" -ForegroundColor Green
    Write-Host "  $qualified" -ForegroundColor White
    Write-Host "  $launcherLine"
    Write-Host "  $zipLine"
    Write-Host "  (ZIP is not an installer)" -ForegroundColor Yellow
    return [pscustomobject]@{
        QualifiedRoot = $qualified
        Launcher = $qualifiedLauncher
        Zip = $zipItem
        ZipLine = $zipLine
        StatusPath = $statusPath
    }
}

$AppImageEvidence = Write-AppImageEvidence -ImageRoot $AppImageRoot -Launcher $AppLauncher

Write-Section "[6/7] Creating Windows EXE installer (jpackage --type exe + WiX)..."
$installerBuilt = $false
$Setup = $null
$hashLine = $null
if ($AppImageOnly) {
    Write-Host "Skipping installer because -AppImageOnly was specified." -ForegroundColor Yellow
    $wixNote = if ($hasWix3) { "WiX 3 AVAILABLE at $wixBinDir (not used because -AppImageOnly)" } else { "WiX 3 NOT AVAILABLE (candle.exe/light.exe missing)" }
    Add-Content -Path $AppImageEvidence.StatusPath -Value "Installer: NOT PRODUCED (-AppImageOnly). $wixNote"
} elseif (-not $hasWix3) {
    Write-Host "Skipping installer: WiX 3 candle.exe/light.exe not found." -ForegroundColor Yellow
    Write-Host "Installer status: NOT TESTED / BLOCKED BY BUILD TOOLING" -ForegroundColor Yellow
    Add-Content -Path $AppImageEvidence.StatusPath -Value "Installer: NOT TESTED / BLOCKED BY BUILD TOOLING (WiX 3 candle.exe/light.exe missing)"
} else {
# Keep --name StellarAI so Start Menu / ARP show "StellarAI".
# Rename the produced setup EXE to StellarAI-Setup-<version>.exe for tester clarity.
# Upgrade UUID is the UpgradeCode from the 1.0.3 installer. A new UUID would
# register a different Windows product and would not upgrade that installation.
$InstallerArgs = @(
    '--type', 'exe',
    '--app-image', $AppImageRoot,
    '--dest', $InstallerDir,
    '--name', $AppName,
    '--app-version', $WindowsPackageVersion,
    '--vendor', $Vendor,
    '--description', $Description,
    '--install-dir', $InstallDirName,
    '--win-upgrade-uuid', 'F3FE0C68-EB1A-35D6-BF4B-96F4532DD20C',
    '--win-menu',
    '--win-shortcut',
    '--win-dir-chooser',
    '--win-per-user-install'
)
if ($Icon) { $InstallerArgs += @('--icon', (Resolve-Path $Icon).Path) }
$jpInstExit = Invoke-External -FilePath jpackage -ArgumentList $InstallerArgs
if ($jpInstExit -ne 0) { throw "[6/7] Native EXE installer creation failed. Installer was NOT created." }

$RawSetup = Get-ChildItem $InstallerDir -Filter "*.exe" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $RawSetup) { throw "[6/7] Installer EXE was not produced by jpackage." }

$FinalInstallerName = "StellarAI-Setup-$AppVersion.exe"
$FinalInstallerPath = Join-Path $InstallerDir $FinalInstallerName
if ($RawSetup.Name -ne $FinalInstallerName) {
    if (Test-Path $FinalInstallerPath) { Remove-Item $FinalInstallerPath -Force }
    Rename-Item -Path $RawSetup.FullName -NewName $FinalInstallerName
}
$Setup = Get-Item $FinalInstallerPath
Assert-InstallerExe -Installer $Setup -AppLauncher $AppLauncher

# Hard separation: never leave a copy of the installer named like the app launcher in installer/.
$confused = Get-ChildItem $InstallerDir -Filter "$AppName.exe" -ErrorAction SilentlyContinue
if ($confused) {
    throw "Unexpected $AppName.exe found under installer/. Only $FinalInstallerName should be distributed."
}

Write-Section "[7/7] Auditing payload + writing SHA-256..."
$sourceLeaks = Get-ChildItem $AppImageDir -Recurse -File | Where-Object {
    $_.Extension -eq '.java' -or $_.Name -eq 'pom.xml' -or $_.Name -eq 'stellar.db'
}
if ($sourceLeaks) { throw "[7/7] Forbidden files detected inside the packaged app image. Distribution blocked." }

$hash = Get-FileHash $Setup.FullName -Algorithm SHA256
$hashLine = "SHA256  $($hash.Hash)  $($Setup.Name)"
$shaPath = Join-Path $InstallerDir "SHA256.txt"
if (Test-Path -LiteralPath $shaPath) {
    $existingHash = Get-Content -LiteralPath $shaPath -Raw -ErrorAction SilentlyContinue
    if ($existingHash -and $existingHash -notmatch [regex]::Escape($Setup.Name)) {
        $keptHash = Join-Path $InstallerDir ("SHA256-kept-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".txt")
        Copy-Item -LiteralPath $shaPath -Destination $keptHash -Force
        Write-Host "Preserved previous installer hash: $keptHash" -ForegroundColor Yellow
    }
}
Set-Content -Path $shaPath -Value $hashLine -Encoding ASCII
Set-Content -Path (Join-Path $InstallerDir "SHA256-$AppVersion.txt") -Value $hashLine -Encoding ASCII
Add-Content -Path $AppImageEvidence.StatusPath -Value "Installer: $($Setup.FullName)"
Add-Content -Path $AppImageEvidence.StatusPath -Value $hashLine
$installerBuilt = $true

# Pointer file so nobody confuses intermediate launcher with the installer.
$readmePath = Join-Path $ReleaseRoot "README-DISTRIBUTE-THIS.txt"
@"
StellarAI Windows packaging output
=================================

FINAL TESTER INSTALLER (distribute ONLY this):
  $($Setup.FullName)

Do NOT distribute the intermediate app-image launcher as an installer:
  $($AppLauncher.FullName)

Qualified APP-IMAGE (not an installer; no WiX required to run from this folder):
  $($AppImageEvidence.QualifiedRoot)

The app-image EXE launches StellarAI immediately (no install wizard).
The Setup EXE runs the Windows installer (Welcome -> Next -> Install -> Finish).
"@ | Set-Content -Path $readmePath -Encoding UTF8
}

if (-not $installerBuilt) {
    Write-Section "[7/7] Auditing app-image payload (no installer)..."
    $sourceLeaks = Get-ChildItem $AppImageDir -Recurse -File | Where-Object {
        $_.Extension -eq '.java' -or $_.Name -eq 'pom.xml' -or $_.Name -eq 'stellar.db'
    }
    if ($sourceLeaks) { throw "[7/7] Forbidden files detected inside the packaged app image. Distribution blocked." }
    $readmePath = Join-Path $ReleaseRoot "README-DISTRIBUTE-THIS.txt"
@"
StellarAI Windows packaging output
=================================

APP-IMAGE (NOT an installer):
  $($AppImageEvidence.QualifiedRoot)

Portable ZIP (NOT an installer):
  $(if ($AppImageEvidence.Zip) { $AppImageEvidence.Zip.FullName } else { 'NOT CREATED' })

Installer EXE was NOT produced.
WiX 3 candle.exe/light.exe is required for StellarAI-Setup-$AppVersion.exe.
"@ | Set-Content -Path $readmePath -Encoding UTF8
}

if ($ValidateInstall) {
    if (-not $installerBuilt) {
        throw "-ValidateInstall requires a built installer. WiX 3 is missing or -AppImageOnly was used."
    }
    Write-Section "Optional silent install validation (-ValidateInstall)..."
    Write-Host "Running quiet install of $($Setup.Name) ..."
    $installProc = Start-Process -FilePath $Setup.FullName -ArgumentList "/quiet" -PassThru -Wait
    if ($installProc.ExitCode -ne 0) {
        throw "Silent install failed with exit code $($installProc.ExitCode)."
    }

    $installedLauncher = @(
        (Join-Path $env:LOCALAPPDATA "$InstallDirName\$AppName.exe"),
        (Join-Path $env:LOCALAPPDATA "$AppName\$AppName.exe"),
        (Join-Path ${env:ProgramFiles} "$InstallDirName\$AppName.exe")
    ) | Where-Object { Test-Path $_ } | Select-Object -First 1

    if (-not $installedLauncher) {
        # Search common per-user jpackage layout
        $guess = Get-ChildItem "$env:LOCALAPPDATA" -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match 'Stellar' } |
            ForEach-Object { Get-ChildItem $_.FullName -Filter "$AppName.exe" -Recurse -ErrorAction SilentlyContinue } |
            Select-Object -First 1
        if ($guess) { $installedLauncher = $guess.FullName }
    }

    if (-not $installedLauncher) {
        throw "Silent install completed but installed $AppName.exe was not found under LocalAppData/ProgramFiles."
    }
    Write-Host "Installed launcher: $installedLauncher" -ForegroundColor Green

    $arp = Get-ItemProperty HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\* -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -like "*$AppName*" } |
        Select-Object -First 1
    if (-not $arp) {
        Write-Host "WARNING: Add/Remove Programs entry not found under HKCU Uninstall (may still exist)." -ForegroundColor Yellow
    } else {
        Write-Host "ARP entry: $($arp.DisplayName) $($arp.DisplayVersion)" -ForegroundColor Green
    }

    $smokeInstalled = Start-Process -FilePath $installedLauncher -PassThru
    Start-Sleep -Seconds 10
    if ($smokeInstalled.HasExited) {
        throw "Installed application exited during post-install smoke (code $($smokeInstalled.ExitCode))."
    }
    Stop-Process -Id $smokeInstalled.Id -Force -ErrorAction SilentlyContinue

    if ($arp -and $arp.UninstallString) {
        Write-Host "Running uninstall..."
        # UninstallString is typically the same setup EXE with uninstall args, or msiexec.
        $un = [string]$arp.UninstallString
        cmd.exe /d /c "$un /quiet"
        Write-Host "Uninstall requested." -ForegroundColor Green
    } else {
        Write-Host "Skipping automated uninstall (no UninstallString); uninstall manually via Settings -> Apps." -ForegroundColor Yellow
    }
}

Write-Host ""
if ($installerBuilt) {
    Write-Host "=================================================" -ForegroundColor Green
    Write-Host "WINDOWS TESTER INSTALLER BUILD SUCCESSFUL" -ForegroundColor Green
    Write-Host "=================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "FINAL INSTALLER:" -ForegroundColor Green
    Write-Host ""
    Write-Host "  $($Setup.FullName)" -ForegroundColor White
    Write-Host ""
    Write-Host ("  Size: {0:N1} MB" -f ($Setup.Length / 1MB)) -ForegroundColor Green
    Write-Host "  $hashLine" -ForegroundColor Green
    Write-Host ""
    Write-Host "Give THIS file to the tester." -ForegroundColor Cyan
} else {
    Write-Host "=================================================" -ForegroundColor Green
    Write-Host "WINDOWS APP-IMAGE BUILD SUCCESSFUL" -ForegroundColor Green
    Write-Host "=================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "QUALIFIED APP-IMAGE (not an installer):" -ForegroundColor Green
    Write-Host "  $($AppImageEvidence.QualifiedRoot)" -ForegroundColor White
    Write-Host ""
    if ($AppImageOnly) {
        Write-Host "Installer: NOT PRODUCED (-AppImageOnly). APP-IMAGE qualification may continue." -ForegroundColor Yellow
        if ($hasWix3) {
            Write-Host "WiX 3 is available; rerun without -AppImageOnly to build StellarAI-Setup-$AppVersion.exe." -ForegroundColor Yellow
        } else {
            Write-Host "WiX 3 candle.exe/light.exe not found; installer remains BLOCKED BY BUILD TOOLING." -ForegroundColor Yellow
        }
    } elseif (-not $hasWix3) {
        Write-Host "Installer: NOT TESTED / BLOCKED BY BUILD TOOLING (WiX 3 candle.exe/light.exe)" -ForegroundColor Yellow
    } else {
        Write-Host "Installer: NOT PRODUCED." -ForegroundColor Yellow
    }
}
Write-Host ""
Write-Host "Do NOT call the app-image ZIP an installer." -ForegroundColor Yellow
Write-Host "  Intermediate launcher: $($AppLauncher.FullName)" -ForegroundColor Yellow
Write-Host "=================================================" -ForegroundColor Green
Write-Host ""
Write-Host "Layout:"
Write-Host ("  release\StellarAI-{0}\                 (qualified APP-IMAGE)" -f $AppVersion)
if ($installerBuilt) {
    Write-Host ("  {0}\StellarAI-Setup-{1}.exe   (FINAL installer)" -f $InstallerDir, $AppVersion)
}
Write-Host "  release\intermediate\app-image\StellarAI\...        (intermediate only)"
Write-Host "User data remains under %LOCALAPPDATA%\StellarAI\ (packaged) or %USERPROFILE%\.stellarai\ (dev) and is not packaged."
Write-Host "Logs: %LOCALAPPDATA%\StellarAI\logs\stellar-desktop.log (packaged)."
Write-Host "Analysis Storage path is user-configured and persists across application upgrades."
Write-Host 'Local/HTTP backend: -Dstellar.execution.mode=LOCAL|REMOTE -Dstellar.api.baseUrl=http://host:port'
