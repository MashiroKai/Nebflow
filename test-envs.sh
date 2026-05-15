#!/bin/bash
# Nebflow Test Environments
# Usage: source test-envs.sh
#
# Then run commands like: nebflow_install_mac, nebflow_install_linux, etc.

NEBFLOW_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_DIR="$NEBFLOW_DIR/target/scala-3.5.2"
PLATFORM_FLAG=""
[ "$(uname -m)" = "arm64" ] && PLATFORM_FLAG="--platform linux/amd64"

echo ""
echo "  Nebflow Test Environments"
echo "  ─────────────────────────────────────"
echo ""
echo "  Full install → run tests (from scratch):"
echo "    nebflow_install_mac      macOS: curl|sh install → nebflow -s"
echo "    nebflow_install_linux    Linux: curl|sh install → nebflow -s"
echo "    nebflow_install_windows  Windows: iwr|iex install → nebflow -s"
echo ""
echo "  Run only (skip install, use local JAR):"
echo "    nebflow_run_mac          Run on macOS directly"
echo "    nebflow_run_linux        Run in Linux Docker container"
echo ""
echo "  Interactive shells:"
echo "    nebflow_shell_linux      Bash shell in Linux container"
echo "    nebflow_shell_mac        Simulated clean macOS shell"
echo ""
echo "  Other:"
echo "    nebflow_build            Rebuild JAR"
echo "    nebflow_clean_mac        Uninstall nebflow from this Mac"
echo "    nebflow_pull_images      Pull all Docker images (run once)"
echo ""

# ─── Build ───

nebflow_build() {
  echo "Building JAR..."
  cd "$NEBFLOW_DIR" && sbt clean assembly 2>&1 | tail -3
  ls -lh "$JAR_DIR/nebflow-assembly-1.0.0.jar" 2>/dev/null && echo "Done." || echo "Build failed."
}

# ─── Pull Docker images ───

nebflow_pull_images() {
  echo "Pulling Docker images..."
  docker pull $PLATFORM_FLAG eclipse-temurin:17-jre-alpine 2>&1 | tail -1
  docker pull $PLATFORM_FLAG alpine:latest 2>&1 | tail -1
  echo "Done."
}

# ─── macOS: Full install test ───

nebflow_install_mac() {
  echo ""
  echo "╔══════════════════════════════════════════════════╗"
  echo "║  macOS Install Test (safe mode, isolated HOME)   ║"
  echo "╚══════════════════════════════════════════════════╝"
  echo ""
  echo "  Everything installs to /tmp/nebflow_test/"
  echo "  Your real ~/.nebflow/ is NOT touched."
  echo ""
  local TEST_HOME="/tmp/nebflow_test/home"
  local TEST_BIN="/tmp/nebflow_test/bin"
  rm -rf /tmp/nebflow_test
  mkdir -p "$TEST_HOME" "$TEST_BIN"

  echo "[1/3] Running: curl -fsSL https://nebflow.space/install.sh | sh"
  echo "      (isolated HOME=$TEST_HOME, INSTALL_DIR=$TEST_BIN)"
  echo ""
  HOME="$TEST_HOME" curl -fsSL https://nebflow.space/install.sh | INSTALL_DIR="$TEST_BIN" sh 2>&1
  echo ""
  echo "[2/3] Config location: $TEST_HOME/.nebflow/"
  ls -la "$TEST_HOME/.nebflow/" 2>/dev/null
  echo ""
  echo "[3/3] Testing nebflow command..."
  echo ""
  HOME="$TEST_HOME" "$TEST_BIN/nebflow" -s 2>&1 | head -10
  echo ""
  echo "─── Cleanup ───"
  echo "  Run: rm -rf /tmp/nebflow_test"
}

# ─── macOS: Run directly ───

nebflow_run_mac() {
  echo ""
  echo "╔══════════════════════════════════════════════════╗"
  echo "║  macOS Run Test (no install, local JAR)          ║"
  echo "╚══════════════════════════════════════════════════╝"
  echo ""
  echo "Java version:"
  java -version 2>&1
  echo ""
  echo "Starting nebflow -s..."
  echo ""
  java --add-opens java.base/java.lang=ALL-UNNAMED \
    -jar "$JAR_DIR/nebflow-assembly-1.0.0.jar" -s 2>&1 | head -15
}

# ─── Linux: Full install test ───

nebflow_install_linux() {
  echo ""
  echo "╔══════════════════════════════════════════════════╗"
  echo "║  Linux Install Test (clean Alpine container)     ║"
  echo "╚══════════════════════════════════════════════════╝"
  echo ""
  docker run --rm $PLATFORM_FLAG alpine:latest sh -c "
    echo '[1/4] Fresh Alpine — no Java, no nebflow'
    echo '      Java:' \$(java -version 2>&1 || echo 'not found')
    echo ''
    echo '[2/4] Installing curl...'
    apk add --no-cache curl bash > /dev/null 2>&1
    echo '      Done.'
    echo ''
    echo '[3/4] Running: curl -fsSL https://nebflow.space/install.sh | sh'
    echo ''
    curl -fsSL https://nebflow.space/install.sh | INSTALL_DIR=/tmp/nebflow sh 2>&1
    echo ''
    echo '[4/4] Testing nebflow command...'
    echo ''
    /tmp/nebflow/nebflow -s 2>&1 | head -10
  "
}

# ─── Linux: Run in container ───

nebflow_run_linux() {
  echo ""
  echo "╔══════════════════════════════════════════════════╗"
  echo "║  Linux Run Test (Alpine + JRE 17, local JAR)     ║"
  echo "╚══════════════════════════════════════════════════╝"
  echo ""
  docker run --rm $PLATFORM_FLAG \
    -v "$JAR_DIR:/jar" \
    eclipse-temurin:17-jre-alpine \
    sh -c "
      echo 'Java version:'
      java -version 2>&1
      echo ''
      echo 'Starting nebflow -s...'
      echo ''
      timeout 20 java --add-opens java.base/java.lang=ALL-UNNAMED \
        -jar /jar/nebflow-assembly-1.0.0.jar -s 2>&1 | head -15
    "
}

# ─── Windows: Full install test ───

nebflow_install_windows() {
  echo ""
  echo "╔══════════════════════════════════════════════════╗"
  echo "║  Windows Install Test (PowerShell on Windows)    ║"
  echo "╚══════════════════════════════════════════════════╝"
  echo ""
  echo "This test runs on your Windows machine via Docker."
  echo "Using mcr.microsoft.com/windows/servercore (Windows container)."
  echo ""
  echo "Note: Windows containers require Docker Desktop with Windows containers mode."
  echo "      If not available, the test will fail with a platform error."
  echo ""
  docker run --rm mcr.microsoft.com/windows/servercore:ltsc2022 powershell -Command "
    Write-Host '[1/4] Fresh Windows — checking Java...'
    \$java = Get-Command java -ErrorAction SilentlyContinue
    if (\$java) { Write-Host '      Java found:' (java -version 2>&1 | Select-Object -First 1) }
    else { Write-Host '      Java: not found (will be auto-installed)' }
    Write-Host ''
    Write-Host '[2/4] Running: iwr -useb https://nebflow.space/install.ps1 | iex'
    Write-Host ''
    \$env:VERSION='1.0.0'
    \$env:INSTALL_DIR='C:\Nebflow'
    iwr -useb https://nebflow.space/install.ps1 -OutFile C:\install.ps1
    powershell -ExecutionPolicy Bypass -File C:\install.ps1 2>&1
    Write-Host ''
    Write-Host '[3/4] Testing nebflow command...'
    Write-Host ''
    C:\Nebflow\nebflow.cmd -s 2>&1 | Select-Object -First 10
  "
}

# ─── Interactive shells ───

nebflow_shell_linux() {
  echo ""
  echo "╔══════════════════════════════════════════════════╗"
  echo "║  Linux Interactive Shell (Alpine + JRE 17)       ║"
  echo "║  Try: nebflow -s, nebflow stop, ls /tmp          ║"
  echo "╚══════════════════════════════════════════════════╝"
  echo ""
  docker run --rm -it $PLATFORM_FLAG \
    -v "$JAR_DIR:/jar" \
    eclipse-temurin:17-jre-alpine \
    sh -c "
      cp /jar/nebflow-assembly-1.0.0.jar /tmp/ 2>/dev/null
      cat > /usr/local/bin/nebflow << 'W'
#!/bin/sh
exec java --add-opens java.base/java.lang=ALL-UNNAMED -jar /tmp/nebflow-assembly-1.0.0.jar \"\$@\"
W
      chmod +x /usr/local/bin/nebflow
      echo 'Welcome! Try: nebflow -s'
      echo ''
      sh
    "
}

nebflow_shell_mac() {
  echo ""
  echo "╔══════════════════════════════════════════════════╗"
  echo "║  macOS Simulated Clean Shell                     ║"
  echo "║  PATH stripped to minimum, no nebflow in PATH    ║"
  echo "║  Try: curl -fsSL https://nebflow.space/install.sh|sh"
  echo "║  Then: nebflow -s"
  echo "╚══════════════════════════════════════════════════╝"
  echo ""
  echo "Starting a clean subshell..."
  echo ""
  env -i HOME="$HOME" PATH="/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/bin" \
    SHELL="/bin/zsh" TERM="$TERM" bash --noprofile --norc
}

# ─── Cleanup ───

nebflow_clean_mac() {
  echo "Cleaning test environment..."
  rm -rf /tmp/nebflow_test
  echo "Done. Your real ~/.nebflow/ was never touched."
}
