@echo off
rem Start Zombiecraft (Black Ops Zombies in Minecraft) from source. Needs Java 25 only to RUN the build tool; the game itself targets Java 21.
rem First run downloads Minecraft and the build tools (a few hundred MB) and takes several minutes.
setlocal EnableDelayedExpansion
if not defined JAVA_HOME (
  for /d %%D in ("C:\Program Files\Java\jdk-25*" "C:\Program Files\Eclipse Adoptium\jdk-25*" "C:\Program Files\Microsoft\jdk-25*") do set "JAVA_HOME=%%~D"
)
if not defined JAVA_HOME (
  echo Java 25 was not found. Install a JDK 25 ^(for example from https://adoptium.net^) or set JAVA_HOME, then run this again.
  pause
  exit /b 1
)
echo Using %JAVA_HOME%

rem --- Sync with GitHub every start (fast-forward only; never overwrites local work) ---
rem Works with git clones and with downloaded zips; also fetches the OpenAssetTools converter the game needs.
echo [sync] Checking GitHub for updates...
if exist "%~dp0tools\update.ps1" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\update.ps1"
) else (
  echo [sync] tools\update.ps1 missing - download the project again from https://github.com/alexianjjohnston-ai/BO2MINECRAFTZM
)

rem --- Look for Black Ops II every start (read-only). The game does its own full search too; this just reports and passes a hint. ---
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
call gradlew.bat runClient --console=plain
pause
