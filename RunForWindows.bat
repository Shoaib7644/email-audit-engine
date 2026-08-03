@echo off

echo ==================================

echo Email Audit Engine

echo ==================================
 
java -version 2>&1 | findstr /C:"21." >nul
 
set "JARDIR=%~dp0"

set "JAR=%JARDIR%email-audit-engine-1.0-SNAPSHOT-jar-with-dependencies.jar"
 
if not exist "%JAR%" (

    set "JAR=%JARDIR%target\email-audit-engine-1.0-SNAPSHOT-jar-with-dependencies.jar"

)
 
if not exist "%JAR%" (

    echo ERROR: Could not find email-audit-engine-1.0-SNAPSHOT-jar-with-dependencies.jar

    echo Looked in: %JARDIR%

    echo Looked in: %JARDIR%target\

    pause

    exit /b 1

)
 
echo Using jar: %JAR%

java -jar "%JAR%"

pause