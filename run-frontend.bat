@echo off
REM Launch Copiloto AI Chat (Java 25)
cd /d "%~dp0"
if not exist "pom.xml" (
    echo Error: pom.xml not found. Please run from the repository root.
    pause
    exit /b 1
)

echo Stopping any running Copiloto instances...
taskkill /F /FI "COMMANDLINE eq *Copiloto*" 2>NUL

echo Building and launching Copiloto...
call mvn compile javafx:run -q
if errorlevel 1 (
    echo Build failed. Check output above for errors.
    pause
)
