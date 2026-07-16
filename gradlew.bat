@echo off
setlocal
set GRADLE_VERSION=8.13
set CACHE_ROOT=%USERPROFILE%\.gradle\arda-wrapper
set INSTALL_DIR=%CACHE_ROOT%\gradle-%GRADLE_VERSION%
set GRADLE_BIN=%INSTALL_DIR%\bin\gradle.bat
set ZIP_PATH=%CACHE_ROOT%\gradle-%GRADLE_VERSION%-bin.zip
set DIST_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip

if not exist "%GRADLE_BIN%" (
  if not exist "%CACHE_ROOT%" mkdir "%CACHE_ROOT%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%ZIP_PATH%'"
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "Expand-Archive -Path '%ZIP_PATH%' -DestinationPath '%CACHE_ROOT%' -Force"
)

call "%GRADLE_BIN%" %*
exit /b %ERRORLEVEL%
