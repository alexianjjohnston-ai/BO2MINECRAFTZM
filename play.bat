@echo off
rem Block Ops 2 launcher. Safe to download on its own: put this one file anywhere and run it. Every start it
rem   1. updates the game from GitHub (git clone or plain download, no git needed),
rem   2. installs what is missing (Java 25 for the build tool, OpenAssetTools for models/menu art),
rem   3. looks for your Black Ops II install, then starts the game.
rem First run downloads Minecraft and the build tools (a few hundred MB) and takes several minutes.
setlocal EnableDelayedExpansion
cd /d "%~dp0"

rem A newer launcher saved by the updater replaces this file, then restarts (the block is read whole before it runs).
if exist "%~dp0play.bat.new" (
  (copy /y "%~dp0play.bat.new" "%~f0" >nul & del "%~dp0play.bat.new" & call "%~f0" %* & exit /b)
)

rem First run from a lone play.bat: fetch the project into this folder.
if not exist "%~dp0tools\update.ps1" (
  echo [setup] Downloading the game files...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol='Tls12'; $z=Join-Path $env:TEMP 'zc.zip'; $t=Join-Path $env:TEMP 'zc-x'; Remove-Item $t -Recurse -Force -ErrorAction SilentlyContinue; Invoke-WebRequest 'https://github.com/alexianjjohnston-ai/BO2MINECRAFTZM/archive/refs/heads/main.zip' -OutFile $z -UseBasicParsing; Expand-Archive $z $t -Force; robocopy (Get-ChildItem $t -Directory)[0].FullName '%~dp0.' /E /NFL /NDL /NJH /NJS /NP /XF play.bat | Out-Null; Remove-Item $z,$t -Recurse -Force"
  if not exist "%~dp0tools\update.ps1" (
    echo Could not download the game. Check your internet connection and try again.
    pause
    exit /b 1
  )
)

echo [sync] Checking GitHub for updates...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\update.ps1"
if exist "%~dp0play.bat.new" (
  (copy /y "%~dp0play.bat.new" "%~f0" >nul & del "%~dp0play.bat.new" & call "%~f0" %* & exit /b)
)

rem --- Java 25 (only runs the build tool; the game itself targets Java 21) ---
if defined JAVA_HOME if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME="
for /d %%D in ("%~dp0.jdk\jdk-25*" "C:\Program Files\Java\jdk-25*" "C:\Program Files\Eclipse Adoptium\jdk-25*" "C:\Program Files\Microsoft\jdk-25*") do set "JAVA_HOME=%%~D"
if not defined JAVA_HOME (
  echo Java 25 could not be found or installed. Install a JDK 25 ^(for example from https://adoptium.net^) or set JAVA_HOME, then run this again.
  pause
  exit /b 1
)
echo Using %JAVA_HOME%

rem --- Look for Black Ops II every start (read-only). The game does its own full search too; this reports and passes a hint. ---
if not defined ZOMBIECRAFT_BO2_DIR (
  set "STEAMPATH="
  for /f "tokens=2,*" %%A in ('reg query "HKCU\Software\Valve\Steam" /v SteamPath 2^>nul ^| find "SteamPath"') do set "STEAMPATH=%%B"
  if defined STEAMPATH set "STEAMPATH=!STEAMPATH:/=\!"
  for %%R in ("!STEAMPATH!" "C:\SteamLibrary" "D:\SteamLibrary" "E:\SteamLibrary" "F:\SteamLibrary" "C:\Program Files (x86)\Steam" "D:\Steam" "E:\Steam" "D:\Games\Steam") do (
    if not defined ZOMBIECRAFT_BO2_DIR if exist "%%~R\steamapps\common\Call of Duty Black Ops II\sound\zmb_common.all.sabl" set "ZOMBIECRAFT_BO2_DIR=%%~R\steamapps\common\Call of Duty Black Ops II"
  )
)
if defined ZOMBIECRAFT_BO2_DIR (echo [bo2] Found Black Ops II: %ZOMBIECRAFT_BO2_DIR%) else (echo [bo2] Not found by the launcher; the game will search all drives itself. Set ZOMBIECRAFT_BO2_DIR to force a path.)

cd /d "%~dp0mod"
call "%~dp0mod\gradlew.bat" runClient --console=plain
pause
