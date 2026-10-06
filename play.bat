@echo off
rem Start Zombiecraft (Black Ops Zombies in Minecraft) from source. Needs Java 25 only to RUN the build tool; the game itself targets Java 21.
rem First run downloads Minecraft and the build tools (a few hundred MB) and takes several minutes.
setlocal
if not defined JAVA_HOME (
  for /d %%D in ("C:\Program Files\Java\jdk-25*" "C:\Program Files\Eclipse Adoptium\jdk-25*" "C:\Program Files\Microsoft\jdk-25*") do set "JAVA_HOME=%%~D"
)
if not defined JAVA_HOME (
  echo Java 25 was not found. Install a JDK 25 ^(for example from https://adoptium.net^) or set JAVA_HOME, then run this again.
  pause
  exit /b 1
)
echo Using %JAVA_HOME%
cd /d "%~dp0mod"
call gradlew.bat runClient --console=plain
pause
