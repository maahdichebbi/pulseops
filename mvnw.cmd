@ECHO OFF
SETLOCAL ENABLEDELAYEDEXPANSION

SET "SCRIPT_DIR=%~dp0"
SET "WRAPPER_PROPERTIES=%SCRIPT_DIR%.mvn\wrapper\maven-wrapper.properties"

FOR /F "tokens=1,* delims==" %%A IN ('findstr /B "distributionUrl" "%WRAPPER_PROPERTIES%"') DO (
    SET "DISTRIBUTION_URL=%%B"
)

REM Extract filename from URL using string substitution on the last path segment
FOR /F "delims=" %%U IN ("!DISTRIBUTION_URL!") DO SET "DIST_ZIP_NAME=%%~nxU"
SET "DIST_NAME=!DIST_ZIP_NAME:.zip=!"

SET "MAVEN_CACHE=%USERPROFILE%\.m2\wrapper\dists\!DIST_NAME!"

IF NOT EXIST "!MAVEN_CACHE!\bin\mvn.cmd" (
    ECHO Downloading Maven !DIST_NAME!...
    IF NOT EXIST "!MAVEN_CACHE!" MKDIR "!MAVEN_CACHE!"
    SET "DIST_ZIP=!MAVEN_CACHE!\dist.zip"
    powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '!DISTRIBUTION_URL!' -OutFile '!DIST_ZIP!'; Expand-Archive -Path '!DIST_ZIP!' -DestinationPath '!MAVEN_CACHE!' -Force; Remove-Item '!DIST_ZIP!'"
    FOR /D %%D IN ("!MAVEN_CACHE!\apache-maven-*") DO (
        XCOPY /E /I /Q "%%D\*" "!MAVEN_CACHE!\" >nul
        RMDIR /S /Q "%%D"
    )
)

IF NOT EXIST "!MAVEN_CACHE!\bin\mvn.cmd" (
    ECHO ERROR: Maven binary not found in !MAVEN_CACHE!\bin\
    EXIT /B 1
)

CALL "!MAVEN_CACHE!\bin\mvn.cmd" %*
ENDLOCAL
