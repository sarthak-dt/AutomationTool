@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title StellarAI Decoder Validation API 1.0.4

call "%~dp0decoder-api-common.bat" java
if errorlevel 1 goto fail
call "%~dp0decoder-api-common.bat" config-start
if errorlevel 1 goto fail
call "%~dp0decoder-api-common.bat" port
if errorlevel 1 goto fail
call "%~dp0decoder-api-common.bat" jar
if errorlevel 1 goto fail

echo.
echo StellarAI Decoder Validation API
echo Version: 1.0.4
echo Profile: decoder
echo URL: http://127.0.0.1:8091/api/v1/decoder/validate
echo Method: POST
echo.
echo Java:
echo   %JAVA_EXE%
echo Server JAR:
echo   %SERVER_JAR%
echo Allowed roots:
echo   %STELLAR_DECODER_ALLOWED_ROOTS%
if defined STELLAR_DECODER_METADATA_DB (
  echo Metadata database:
  echo   %STELLAR_DECODER_METADATA_DB%
) else (
  echo Metadata database: not set. projectName requests stay unavailable.
)
echo Session directory:
echo   %STELLAR_DECODER_SESSION_DIRECTORY%
echo.
echo Leave this window open while the API is running.
echo Stop the API by pressing Ctrl+C, then answer Y to terminate the batch job.
echo Closing this window also stops the API.
echo.

"%JAVA_EXE%" -jar "%SERVER_JAR%" --spring.profiles.active=decoder
set "EXITCODE=%ERRORLEVEL%"
echo.
if not "%EXITCODE%"=="0" (
  echo The Decoder API stopped with exit code %EXITCODE%.
  echo Review the messages above.
  call "%~dp0decoder-api-common.bat" pause
  exit /b %EXITCODE%
)
echo The Decoder API has stopped.
call "%~dp0decoder-api-common.bat" pause
exit /b 0

:fail
echo.
echo The Decoder API did not start. Review the messages above.
call "%~dp0decoder-api-common.bat" pause
exit /b 1
