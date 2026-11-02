@echo off
rem Shared checks for START_DECODER_API.bat and BUILD_DECODER_API.bat.
rem Double-click START_DECODER_API.bat to run the Decoder Validation API.

if /i "%~1"=="java" goto require_java21
if /i "%~1"=="config-start" goto config_start
if /i "%~1"=="config-build" goto config_build
if /i "%~1"=="port" goto check_port
if /i "%~1"=="jar" goto find_jar
if /i "%~1"=="pause" goto maybe_pause

echo This file is used by START_DECODER_API.bat and BUILD_DECODER_API.bat.
echo Double-click START_DECODER_API.bat to run the Decoder API.
call :maybe_pause
exit /b 1

:maybe_pause
if /i "%STELLAR_DECODER_NO_PAUSE%"=="1" exit /b 0
echo.
pause
exit /b 0

:require_java21
set "JAVA_EXE="
if defined JAVA_HOME call :prefer_jdk "%JAVA_HOME%\bin\java.exe"
if defined JAVA_EXE exit /b 0
call :scan_jdk "%ProgramFiles%\Java\jdk-21*"
if defined JAVA_EXE exit /b 0
call :scan_jdk "%ProgramFiles%\Eclipse Adoptium\jdk-21*"
if defined JAVA_EXE exit /b 0
call :scan_jdk "%ProgramFiles%\Microsoft\jdk-21*"
if defined JAVA_EXE exit /b 0
call :scan_jdk "%ProgramFiles%\Amazon Corretto\jdk21*"
if defined JAVA_EXE exit /b 0
if exist "%ProgramFiles(x86)%\Java\." call :scan_jdk "%ProgramFiles(x86)%\Java\jdk-21*"
if defined JAVA_EXE exit /b 0
where java >nul 2>&1
if not errorlevel 1 for /f "delims=" %%J in ('where java') do call :prefer_jdk "%%J"
if defined JAVA_EXE exit /b 0
if defined JAVA_HOME call :prefer_runtime "%JAVA_HOME%\bin\java.exe"
if defined JAVA_EXE exit /b 0
where java >nul 2>&1
if not errorlevel 1 for /f "delims=" %%J in ('where java') do call :prefer_runtime "%%J"
if defined JAVA_EXE exit /b 0
call :scan_runtime "%ProgramFiles%\Java\jdk-21*"
if defined JAVA_EXE exit /b 0
:java_missing
echo Java 21 was not found.
echo Install JDK 21, then set JAVA_HOME to that JDK or add its bin folder to PATH.
echo This launcher looks for JAVA_HOME, java on PATH, and JDK 21 folders under Program Files.
exit /b 1

:prefer_jdk
if defined JAVA_EXE exit /b 0
if not exist "%~1" exit /b 1
call :java_is_21 "%~1"
if errorlevel 1 exit /b 1
set "JAVAC_OK="
for %%I in ("%~1") do if exist "%%~dpIjavac.exe" set "JAVAC_OK=1"
if not defined JAVAC_OK exit /b 1
set "JAVA_EXE=%~1"
exit /b 0

:prefer_runtime
if defined JAVA_EXE exit /b 0
if not exist "%~1" exit /b 1
call :java_is_21 "%~1"
if errorlevel 1 exit /b 1
set "JAVA_EXE=%~1"
exit /b 0

:scan_jdk
for /d %%D in ("%~1") do call :prefer_jdk "%%~D\bin\java.exe"
exit /b 0

:scan_runtime
for /d %%D in ("%~1") do call :prefer_runtime "%%~D\bin\java.exe"
exit /b 0

:java_is_21
set "JAVA_VER="
for /f "tokens=3" %%V in ('"%~1" -version 2^>^&1') do (
  set "JAVA_VER=%%V"
  goto java_ver_got
)
exit /b 1
:java_ver_got
set "JAVA_VER=%JAVA_VER:"=%"
if "%JAVA_VER:~0,3%"=="21." exit /b 0
exit /b 1

:config_start
if not exist "%~dp0decoder-api.config" (
  echo decoder-api.config was not found next to START_DECODER_API.bat.
  echo Copy decoder-api.config.example to decoder-api.config.
  echo Set allowed-roots to the measurement and DBC directories this API may read.
  echo Separate directories with semicolons. Do not set a whole drive such as C:\.
  exit /b 1
)
call :read_config
if not defined STELLAR_DECODER_ALLOWED_ROOTS set "STELLAR_DECODER_ALLOWED_ROOTS=%CFG_ROOTS%"
if not defined STELLAR_DECODER_SESSION_DIRECTORY set "STELLAR_DECODER_SESSION_DIRECTORY=%CFG_SESSION%"
if not defined STELLAR_DECODER_METADATA_DB if defined CFG_METADATA set "STELLAR_DECODER_METADATA_DB=%CFG_METADATA%"
if "%STELLAR_DECODER_ALLOWED_ROOTS%"=="" (
  echo allowed-roots is empty.
  echo Set allowed-roots in decoder-api.config, or set STELLAR_DECODER_ALLOWED_ROOTS before starting.
  echo Use absolute directories separated by semicolons. A whole drive is not accepted.
  exit /b 1
)
call :reject_drive_roots
if errorlevel 1 exit /b 1
if "%STELLAR_DECODER_SESSION_DIRECTORY%"=="" set "STELLAR_DECODER_SESSION_DIRECTORY=%~dp0decoder-sessions"
call :qualify_session
if not exist "%STELLAR_DECODER_SESSION_DIRECTORY%\" mkdir "%STELLAR_DECODER_SESSION_DIRECTORY%"
if not exist "%STELLAR_DECODER_SESSION_DIRECTORY%\" (
  echo The session directory could not be created:
  echo   %STELLAR_DECODER_SESSION_DIRECTORY%
  exit /b 1
)
exit /b 0

:config_build
set "MVN_CMD="
if exist "%~dp0decoder-api.config" call :read_config
if defined STELLAR_DECODER_MAVEN if exist "%STELLAR_DECODER_MAVEN%" set "MVN_CMD=%STELLAR_DECODER_MAVEN%"
if not defined MVN_CMD if defined CFG_MAVEN if exist "%CFG_MAVEN%" set "MVN_CMD=%CFG_MAVEN%"
if not defined MVN_CMD (
  where mvn >nul 2>&1
  if not errorlevel 1 (
    for /f "delims=" %%M in ('where mvn') do (
      if not defined MVN_CMD set "MVN_CMD=%%M"
    )
  )
)
if not defined MVN_CMD if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MVN_CMD=%MAVEN_HOME%\bin\mvn.cmd"
if not defined MVN_CMD if defined M2_HOME if exist "%M2_HOME%\bin\mvn.cmd" set "MVN_CMD=%M2_HOME%\bin\mvn.cmd"
if not defined MVN_CMD (
  echo Maven was not found.
  echo Install Maven 3.9 or newer and add it to PATH, set MAVEN_HOME, or set maven-cmd
  echo in decoder-api.config to the full path of mvn.cmd.
  echo Example: maven-cmd=C:\Program Files\apache-maven\bin\mvn.cmd
  exit /b 1
)
exit /b 0

:read_config
set "CFG_ROOTS="
set "CFG_SESSION="
set "CFG_METADATA="
set "CFG_MAVEN="
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%~dp0decoder-api.config") do (
  call :assign_config "%%A" "%%B"
)
exit /b 0

:assign_config
set "CFG_KEY=%~1"
set "CFG_VAL=%~2"
if /i "%CFG_KEY%"=="allowed-roots" set "CFG_ROOTS=%CFG_VAL%"
if /i "%CFG_KEY%"=="session-directory" set "CFG_SESSION=%CFG_VAL%"
if /i "%CFG_KEY%"=="metadata-db" set "CFG_METADATA=%CFG_VAL%"
if /i "%CFG_KEY%"=="maven-cmd" set "CFG_MAVEN=%CFG_VAL%"
exit /b 0

:qualify_session
set "SESS=%STELLAR_DECODER_SESSION_DIRECTORY%"
if "%SESS:~1,1%"==":" exit /b 0
if "%SESS:~0,2%"=="\\" exit /b 0
set "STELLAR_DECODER_SESSION_DIRECTORY=%~dp0%SESS%"
exit /b 0

:reject_drive_roots
setlocal EnableDelayedExpansion
set "ALL=!STELLAR_DECODER_ALLOWED_ROOTS!"
:root_next
if "!ALL!"=="" (
  endlocal
  exit /b 0
)
for /f "tokens=1* delims=;" %%A in ("!ALL!") do (
  set "ONE=%%A"
  set "ALL=%%B"
)
:trim_root
if "!ONE:~0,1!"==" " (
  set "ONE=!ONE:~1!"
  goto trim_root
)
:trim_root_end
if "!ONE:~-1!"==" " (
  set "ONE=!ONE:~0,-1!"
  goto trim_root_end
)
if "!ONE!"=="" goto root_next
if /i "!ONE:~1,1!"==":" (
  if "!ONE:~2!"=="" goto root_bad
  echo(!ONE:~2!| findstr /r /x "[\\/]" >nul
  if not errorlevel 1 goto root_bad
)
if not exist "!ONE!\" echo Warning: allowed root was not found: !ONE!
if defined ALL goto root_next
endlocal
exit /b 0
:root_bad
echo Allowed root is a whole drive: !ONE!
echo List the measurement and DBC directories explicitly, separated by semicolons.
echo Example: D:\measurements;D:\dbc
endlocal
exit /b 1

:check_port
set "PORT_HIT="
for /f "tokens=5" %%P in ('netstat -ano -p TCP ^| findstr /c:":8091 " ^| findstr /i "LISTENING"') do (
  set "PORT_HIT=%%P"
)
if defined PORT_HIT (
  echo Port 8091 is already in use by process %PORT_HIT%.
  echo The Decoder API is already running, or another program is using that port.
  echo Stop that process, then run START_DECODER_API.bat again.
  echo This launcher will not start a second instance.
  exit /b 1
)
exit /b 0

:find_jar
set "SERVER_JAR=%~dp0stellar-server\target\stellar-server-0.1.0-SNAPSHOT.jar"
if exist "%SERVER_JAR%" goto jar_present
echo The Decoder API server JAR was not found:
echo   %SERVER_JAR%
echo.
echo This launcher does not build the server.
echo Run BUILD_DECODER_API.bat once, then double-click START_DECODER_API.bat again.
echo After decoder code changes, run BUILD_DECODER_API.bat before starting.
exit /b 1
:jar_present
powershell -NoProfile -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; $zip=[IO.Compression.ZipFile]::OpenRead($env:SERVER_JAR); $boot=$false; foreach($entry in $zip.Entries){ if($entry.FullName.StartsWith('BOOT-INF/')){ $boot=$true; break } }; $zip.Dispose(); if(-not $boot){ exit 2 }"
if not errorlevel 1 exit /b 0
echo The server JAR is not an executable Spring Boot archive:
echo   %SERVER_JAR%
echo Run BUILD_DECODER_API.bat to package stellar-server, then start again.
exit /b 1
