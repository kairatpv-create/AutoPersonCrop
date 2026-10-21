@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo === MyOrganizer 0.8.0 build ===
if not exist local.properties (
  if exist "%LOCALAPPDATA%\Android\Sdk" (
    echo sdk.dir=%LOCALAPPDATA:\=/%/Android/Sdk>local.properties
  ) else (
    echo Android SDK not found. Open Android Studio once and install Android SDK 35.
    pause
    exit /b 2
  )
)

set "GRADLE_CMD="
where gradle >nul 2>nul
if not errorlevel 1 set "GRADLE_CMD=gradle"

if exist "gradle\wrapper\gradle-wrapper.jar" if exist "gradlew.bat" set "GRADLE_CMD=call gradlew.bat"

if not defined GRADLE_CMD (
  set "BOOT=%~dp0.gradle-bootstrap"
  set "GVER=8.11.1"
  if not exist "%~dp0.gradle-bootstrap\gradle-8.11.1\bin\gradle.bat" (
    echo Gradle not found. Downloading Gradle 8.11.1 once...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $u='https://services.gradle.org/distributions/gradle-8.11.1-bin.zip'; $z='%~dp0.gradle-bootstrap\gradle.zip'; New-Item -ItemType Directory -Force -Path '%~dp0.gradle-bootstrap' ^| Out-Null; Invoke-WebRequest -UseBasicParsing $u -OutFile $z; Expand-Archive -Force $z '%~dp0.gradle-bootstrap'; Remove-Item $z"
    if errorlevel 1 (
      echo Gradle download failed. Open the project in Android Studio and choose Build ^> Build APK(s).
      pause
      exit /b 3
    )
  )
  set "GRADLE_CMD=call %~dp0.gradle-bootstrap\gradle-8.11.1\bin\gradle.bat"
)

%GRADLE_CMD% --no-daemon assembleDebug
if errorlevel 1 (
  echo.
  echo BUILD FAILED. Copy the error text and send it to ChatGPT.
  pause
  exit /b 1
)

copy /y "app\build\outputs\apk\debug\app-debug.apk" "MyOrganizer-v0.8.0-debug.apk" >nul
echo.
echo READY: %~dp0MyOrganizer-v0.8.0-debug.apk
start "" explorer.exe /select,"%~dp0MyOrganizer-v0.8.0-debug.apk"
pause
