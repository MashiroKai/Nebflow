@echo off
rem ===========================================================================
rem stand CLI - Windows entry (the PATH face)
rem
rem Authoritative copy : <stand repo>/ops/bin/stand.cmd
rem Deployed as        : %USERPROFILE%\.stand\bin\stand.cmd  (installer face)
rem                      D:\stand\bin\stand.cmd              (user-PATH face)
rem
rem File discipline: ASCII only + CRLF line endings. cmd.exe misparses a batch
rem file with LF-only endings (it runs comment text as commands), and non-ASCII
rem bytes are read in the OEM code page.
rem
rem WHY THIS FILE EXISTS / why it is thin:
rem   The installed assembly jar already implements the whole CLI
rem   (nebflow.cli.CommandRegistry: start / stop / restart / status / logs /
rem   health / version / doctor / autostart ...). This wrapper re-implements
rem   NOTHING. Its only jobs are:
rem     (1) find java,
rem     (2) find the installed assembly jar through the `current-version`
rem         pointer (the same pointer the installer / hot-update writes),
rem     (3) pin console + JVM text encoding to UTF-8,
rem     (4) hand argv to the jar verbatim and pass its exit code through.
rem
rem JAVA RESOLUTION - JAVA_HOME first, PATH second:
rem   * JAVA_HOME is the operator's explicit statement of which JDK stand
rem     should run on; honoring it keeps `stand` on the same JVM as the rest of
rem     the toolchain. (Mirrors nebflow.core.RestartHelper.resolveJavaBin,
rem     which pins java.home/bin/java and only then falls back to PATH.)
rem   * PATH `java` stays as the fallback so a zero-config box still works
rem     (on this machine the machine-PATH JDK is 21).
rem   * No version probe here: the jar keeps its own JDK-21 gate with a clear
rem     refusal message, and a `java -version` pre-flight would add ~0.4 s to
rem     every CLI call (status / logs / version are hot paths).
rem
rem NF_HOME CONSISTENCY (2026-11 home-injection batch, StaticRoutes.homeScriptTag):
rem   The gateway injects `window.NF_HOME = <JVM user.home>` - the REAL home of
rem   the process, deliberately NOT the data root and NOT the CWD. This launcher
rem   therefore never sets -Duser.home / HOME / USERPROFILE, and a `--home <dir>`
rem   (data root) passed by the caller must not move user.home either. One
rem   spelling only: whatever home the JVM runs under is what the web collapses
rem   to "~".
rem
rem ENCODING (Chinese Windows): a CP936 console has no mapping for U+2713, so
rem   `stand status` used to print "? Gateway running" and the ready line's
rem   em dash turned into mojibake. Two halves are needed and both are set here:
rem     console side : chcp 65001   (restored on exit)
rem     JVM side     : -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8
rem   (the -D pair also fixes redirected / piped output, where the JVM would
rem   otherwise fall back to native.encoding and still drop the check mark).
rem
rem START / DETACH: nothing to add. `stand start` is the JVM's own daemon
rem   spawn (nebflow.cli.DaemonSpawn): it spawns the child JVM, waits for the
rem   /api/health identity probe, prints exactly one line, then exits - the child
rem   is not tied to this shell (measured: it keeps serving after the launching
rem   console is destroyed). exit code 0 = serving.
rem ===========================================================================
setlocal EnableExtensions

rem --- 0) remember the console code page, then switch to UTF-8 ---------------
set "_STAND_OLDCP="
for /f "tokens=2 delims=:" %%c in ('chcp') do set "_STAND_OLDCP=%%c"
set "_STAND_OLDCP=%_STAND_OLDCP: =%"
chcp 65001 >nul 2>&1

set "_STAND_RC=1"

rem --- 1) locate java -------------------------------------------------------
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for /f "delims=" %%j in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%j"
if not defined JAVA_EXE (
  echo ERROR: no java found - set JAVA_HOME or put a JDK 21+ on PATH 1>&2
  goto :stand_done
)

rem --- 2) locate the installed assembly jar ---------------------------------
rem Candidates are probed in order; each one needs a `current-version` pointer
rem (the installer / hot-update contract). STAND_JAR overrides everything.
set "STAND_JAR_FOUND="
if defined STAND_JAR if exist "%STAND_JAR%" set "STAND_JAR_FOUND=%STAND_JAR%"
if not defined STAND_JAR_FOUND call :stand_probe_install "%USERPROFILE%\.stand\bin"
if not defined STAND_JAR_FOUND call :stand_probe_install "%LOCALAPPDATA%\stand"
if not defined STAND_JAR_FOUND if defined STAND_HOME call :stand_probe_install "%STAND_HOME%\bin"
if not defined STAND_JAR_FOUND if defined STAND_HOME call :stand_probe_install "%STAND_HOME%"
if not defined STAND_JAR_FOUND call :stand_probe_install "%ProgramData%\stand"

if not defined STAND_JAR_FOUND (
  echo ERROR: stand assembly jar not found - no current-version pointer under 1>&2
  echo        %USERPROFILE%\.stand\bin , %LOCALAPPDATA%\stand , %ProgramData%\stand 1>&2
  echo        override with:  set STAND_JAR=path-to-stand-assembly-jar 1>&2
  goto :stand_done
)

rem --- 3) run the jar (no subcommand is interpreted here) -------------------
"%JAVA_EXE%" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 --add-opens java.base/java.lang=ALL-UNNAMED -jar "%STAND_JAR_FOUND%" %*
set "_STAND_RC=%ERRORLEVEL%"

:stand_done
if defined _STAND_OLDCP chcp %_STAND_OLDCP% >nul 2>&1
endlocal & exit /b %_STAND_RC%

rem --- helpers --------------------------------------------------------------

rem stand_probe_install <dir>: sets STAND_JAR_FOUND from <dir>\current-version.
rem Layouts accepted (both are live today):
rem   pointer + versions/  : <dir>\current-version -> <dir>\versions\<v>\stand-assembly-<v>.jar
rem   flat                 : <dir>\current-version -> <dir>\stand-assembly-<v>.jar
:stand_probe_install
set "_SD=%~1"
if not defined _SD goto :eof
if not exist "%_SD%\current-version" goto :eof
set "_SV="
for /f "usebackq delims=" %%v in ("%_SD%\current-version") do if not defined _SV set "_SV=%%v"
if not defined _SV goto :eof
if exist "%_SD%\versions\%_SV%\stand-assembly-%_SV%.jar" set "STAND_JAR_FOUND=%_SD%\versions\%_SV%\stand-assembly-%_SV%.jar"
if not defined STAND_JAR_FOUND for %%f in ("%_SD%\versions\%_SV%\*.jar") do if not defined STAND_JAR_FOUND if exist "%%~ff" set "STAND_JAR_FOUND=%%~ff"
if not defined STAND_JAR_FOUND for %%f in ("%_SD%\*.jar") do if not defined STAND_JAR_FOUND if exist "%%~ff" set "STAND_JAR_FOUND=%%~ff"
goto :eof
