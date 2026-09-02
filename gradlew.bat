@echo off
setlocal
set GRADLE_VERSION=9.5.0
set BASE_DIR=%~dp0
set CACHE_DIR=%BASE_DIR%.gradle-local
set DIST_DIR=%CACHE_DIR%\gradle-%GRADLE_VERSION%
set ZIP=%CACHE_DIR%\gradle-%GRADLE_VERSION%-bin.zip
if exist "%DIST_DIR%\bin\gradle.bat" goto run
if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"
echo Downloading Gradle %GRADLE_VERSION%...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP%'; Expand-Archive -Force '%ZIP%' '%CACHE_DIR%'"
if errorlevel 1 exit /b 1
:run
call "%DIST_DIR%\bin\gradle.bat" %*
