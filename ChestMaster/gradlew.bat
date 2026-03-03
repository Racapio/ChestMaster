@echo off
setlocal enabledelayedexpansion

rem Lightweight wrapper: downloads the Gradle distribution declared in gradle\wrapper\gradle-wrapper.properties
rem and runs it. (This project archive didn't include the standard gradle-wrapper.jar.)

set APP_HOME=%~dp0
set PROPS_FILE=%APP_HOME%gradle\wrapper\gradle-wrapper.properties

if not exist "%PROPS_FILE%" (
  echo Missing %PROPS_FILE%
  exit /b 1
)

for /f "usebackq tokens=1,* delims==" %%A in ("%PROPS_FILE%") do (
  if "%%A"=="distributionUrl" set DIST_URL=%%B
)

if "%DIST_URL%"=="" (
  echo distributionUrl not found in %PROPS_FILE%
  exit /b 1
)

if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set DIST_DIR=%GRADLE_USER_HOME%\wrapper\dists

for %%F in ("%DIST_URL%") do set ZIP_NAME=%%~nxF
set BASE_NAME=%ZIP_NAME:.zip=%
set TARGET_DIR=%DIST_DIR%\%BASE_NAME%

if not exist "%TARGET_DIR%" mkdir "%TARGET_DIR%" >nul 2>&1

set GRADLE_HOME=
for /d %%D in ("%TARGET_DIR%\gradle-*") do (
  set GRADLE_HOME=%%D
  goto :found
)
:found

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  echo Downloading Gradle distribution: %DIST_URL%
  set TMP_ZIP=%TARGET_DIR%\%ZIP_NAME%
  powershell -NoProfile -Command "Try { (New-Object System.Net.WebClient).DownloadFile('%DIST_URL%','%TMP_ZIP%') } Catch { Exit 1 }"
  if errorlevel 1 (
    echo Failed to download Gradle.
    exit /b 1
  )
  echo Extracting Gradle...
  powershell -NoProfile -Command "Expand-Archive -Force '%TMP_ZIP%' '%TARGET_DIR%'" 
  del /q "%TMP_ZIP%" >nul 2>&1
  for /d %%D in ("%TARGET_DIR%\gradle-*") do (
    set GRADLE_HOME=%%D
    goto :found2
  )
)
:found2

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  echo Failed to prepare Gradle distribution.
  exit /b 1
)

call "%GRADLE_HOME%\bin\gradle.bat" %*
endlocal
