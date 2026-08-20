@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>&1
if errorlevel 1 (
  echo Java 21 ne naidena. Ustanovi JDK 21 i povtori zapusk.
  pause
  exit /b 1
)
java -jar PluginReconstructorUltra-0.2.0.jar gui
if errorlevel 1 pause
