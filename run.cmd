@echo off
setlocal enabledelayedexpansion

REM ============================================================================
REM  exam-tracker - one command launcher (Windows)
REM  ---------------------------------------------------------------------------
REM  !! KEEP THIS FILE PURE ASCII !!
REM  cmd.exe reads .cmd files using the OEM code page (GBK on Chinese Windows).
REM  A UTF-8 Chinese comment here would be mis-decoded, and the garbled bytes
REM  can leak out of the REM line and be executed as a command. The script
REM  would then print "is not recognized as an internal or external command"
REM  while the real startup actually succeeded - which sends you hunting for a
REM  bug that does not exist. English comments only.
REM
REM  Usage:
REM    run.cmd          start the app (offers to init the DB if it is missing)
REM    run.cmd init     create schema + demo data, then exit
REM    run.cmd skip     start without any database check
REM ============================================================================

cd /d "%~dp0"

set "APP_PORT=8090"
set "DB_PORT=3308"
set "DB_NAME=exam_tracker"
set "DB_USERNAME=dev"
set "DB_PASSWORD=dev123456"

if not "%SERVER_PORT%"=="" set "APP_PORT=%SERVER_PORT%"
if not "%DB_PORT%"=="" set "DB_PORT=%DB_PORT%"

set "MODE=%~1"
if "%MODE%"=="" set "MODE=start"

echo.
echo   exam-tracker  launcher
echo   ============================================================
echo.

REM --------------------------------------------------------------------------
REM  1. Java
REM --------------------------------------------------------------------------
where java >nul 2>nul
if errorlevel 1 (
    echo   [FAIL] java was not found on PATH.
    echo          Install JDK 17 or newer, then run this script again.
    echo.
    exit /b 1
)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VER=%%v"
echo   [ OK ] java %JAVA_VER%

REM --------------------------------------------------------------------------
REM  2. Locate (or build) the jar
REM --------------------------------------------------------------------------
set "JAR="
for %%f in ("target\exam-tracker-*.jar") do set "JAR=%%~ff"

if not defined JAR (
    echo   [INFO] No jar in target\ - building with the Maven Wrapper...
    echo.
    if not exist "mvnw.cmd" (
        echo   [FAIL] mvnw.cmd is missing and there is no jar to run.
        echo          Build it yourself:   mvn -DskipTests package
        echo          Or download a release jar from GitHub.
        echo.
        exit /b 1
    )
    call mvnw.cmd -q -DskipTests package
    if errorlevel 1 (
        echo.
        echo   [FAIL] Build failed - see the output above.
        echo.
        exit /b 1
    )
    for %%f in ("target\exam-tracker-*.jar") do set "JAR=%%~ff"
    if not defined JAR (
        echo   [FAIL] The build reported success but produced no jar.
        echo.
        exit /b 1
    )
)
echo   [ OK ] jar: %JAR%

REM --------------------------------------------------------------------------
REM  3. MySQL reachability
REM --------------------------------------------------------------------------
set "DB_UP=0"
if /i "%MODE%"=="skip" goto :run

echo   [INFO] Looking for MySQL on 127.0.0.1:%DB_PORT% ...
netstat -ano | findstr ":%DB_PORT% " | findstr /i "LISTENING" >nul 2>nul
if errorlevel 1 (
    echo   [WARN] Nothing is listening on port %DB_PORT%.
    echo          The app will fail to start without a database.
    echo.
    echo          Options:
    echo            1. Start your MySQL and re-run this script
    echo            2. Use a different port:  set DB_PORT=3306  then re-run
    echo            3. Docker:                see README section 4.5
    echo.
    if /i not "%MODE%"=="init" (
        choice /c YN /n /m "  Start anyway? [Y/N] "
        if errorlevel 2 (
            echo   Aborted.
            exit /b 1
        )
    )
) else (
    echo   [ OK ] MySQL is listening.
    set "DB_UP=1"
)

REM --------------------------------------------------------------------------
REM  4. Schema + demo data (only when the database is actually reachable)
REM --------------------------------------------------------------------------
if "%DB_UP%"=="1" (
    where mysql >nul 2>nul
    if errorlevel 1 (
        echo   [INFO] The mysql client is not on PATH - skipping the database check.
        echo          If this is a fresh install, run sql/schema.sql and sql/seed.sql manually.
    ) else (
        mysql -h 127.0.0.1 -P %DB_PORT% -u%DB_USERNAME% -p%DB_PASSWORD% -e "USE %DB_NAME%" >nul 2>nul
        if errorlevel 1 (
            echo   [WARN] Database "%DB_NAME%" is not reachable with user "%DB_USERNAME%".
            if /i "%MODE%"=="init" (
                set "DO_INIT=Y"
            ) else (
                choice /c YN /n /m "  Create it and load the demo data? [Y/N] "
                if errorlevel 2 (set "DO_INIT=N") else (set "DO_INIT=Y")
            )
            if /i "!DO_INIT!"=="Y" (
                echo   [INFO] Running sql/schema.sql ...
                mysql -h 127.0.0.1 -P %DB_PORT% -u%DB_USERNAME% -p%DB_PASSWORD% < sql\schema.sql
                if errorlevel 1 (
                    echo   [FAIL] schema.sql failed. Check the credentials and try again.
                    echo.
                    exit /b 1
                )
                echo   [INFO] Running sql/seed.sql ...
                mysql -h 127.0.0.1 -P %DB_PORT% -u%DB_USERNAME% -p%DB_PASSWORD% < sql\seed.sql
                if errorlevel 1 (
                    echo   [FAIL] seed.sql failed.
                    echo.
                    exit /b 1
                )
                echo   [ OK ] Database ready.
            )
        ) else (
            echo   [ OK ] Database "%DB_NAME%" already exists.
        )
    )
)

if /i "%MODE%"=="init" (
    echo.
    echo   Done. Start the app with:  run.cmd
    echo.
    exit /b 0
)

REM --------------------------------------------------------------------------
REM  5. Run
REM --------------------------------------------------------------------------
:run
echo.
echo   Starting exam-tracker ...
echo.
echo     Web UI  : http://127.0.0.1:%APP_PORT%/api/
echo     API doc : http://127.0.0.1:%APP_PORT%/api/doc.html
echo     Login   : demo / demo123456
echo.
echo   Press Ctrl+C to stop.
echo.

java -jar "%JAR%"

endlocal
