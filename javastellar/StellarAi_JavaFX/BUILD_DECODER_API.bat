@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title StellarAI Decoder API build

call "%~dp0decoder-api-common.bat" java
if errorlevel 1 goto fail
call "%~dp0decoder-api-common.bat" config-build
if errorlevel 1 goto fail

for %%I in ("%JAVA_EXE%") do set "JAVA_BIN=%%~dpI"
if not exist "%JAVA_BIN%javac.exe" (
  echo Java 21 was found, but javac was not beside it:
  echo   %JAVA_EXE%
  echo BUILD_DECODER_API.bat needs JDK 21. Set JAVA_HOME to the JDK folder and run this again.
  goto fail
)
for %%I in ("%JAVA_BIN%..") do set "JAVA_HOME=%%~fI"
echo Building the Decoder API server JAR.
echo Java: %JAVA_EXE%
echo Maven: %MVN_CMD%
echo This build does not start the API and does not run tests.
echo.

"%MVN_CMD%" -f "%~dp0pom.xml" -pl stellar-server -am -DskipTests package
if errorlevel 1 goto fail

echo.
echo Build finished.
echo Server JAR:
echo   %~dp0stellar-server\target\stellar-server-0.1.0-SNAPSHOT.jar
echo.
echo Copy decoder-api.config.example to decoder-api.config if that file is not already present.
echo Set allowed-roots to the directories the API may read, then double-click START_DECODER_API.bat.
call "%~dp0decoder-api-common.bat" pause
exit /b 0

:fail
echo.
echo The Decoder API build did not finish. Review the messages above.
call "%~dp0decoder-api-common.bat" pause
exit /b 1
