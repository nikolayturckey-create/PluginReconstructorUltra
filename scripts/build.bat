@echo off
setlocal
cd /d "%~dp0\.."
if exist build\classes rmdir /s /q build\classes
if exist dist rmdir /s /q dist
mkdir build\classes
mkdir dist
dir /s /b src\main\java\*.java > build\sources.txt
javac --release 21 -encoding UTF-8 -d build\classes @build\sources.txt
if errorlevel 1 exit /b 1
jar --create --file dist\PluginReconstructorUltra-0.2.0.jar --main-class dev.nik.reconstructor.Main -C build\classes .
if errorlevel 1 exit /b 1
copy /y dist\PluginReconstructorUltra-0.2.0.jar PluginReconstructorUltra-0.2.0.jar >nul
if errorlevel 1 exit /b 1
echo Built: %CD%\PluginReconstructorUltra-0.2.0.jar
