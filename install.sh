#!/bin/bash
set -e

VERSION="${VERSION:-1.0.0}"
INSTALL_DIR="${INSTALL_DIR:-/usr/local/bin}"
JAR_NAME="nebflow-assembly-${VERSION}.jar"
DOWNLOAD_URL="https://nebflow-releases-1411212853.cos.ap-nanjing.myqcloud.com/nebflow-assembly-${VERSION}.jar"

echo ""
echo "  ███╗   ██╗███████╗██████╗ ███████╗██╗      ██████╗ ██╗    ██╗"
echo "  ████╗  ██║██╔════╝██╔══██╗██╔════╝██║     ██╔═══██╗██║    ██║"
echo "  ██╔██╗ ██║█████╗  ██████╔╝█████╗  ██║     ██║   ██║██║ █╗ ██║"
echo "  ██║╚██╗██║██╔══╝  ██╔══██╗██╔══╝  ██║     ██║   ██║██║███╗██║"
echo "  ██║ ╚████║███████╗██████╔╝██║     ███████╗╚██████╔╝╚███╔███╔╝"
echo "  ╚═╝  ╚═══╝╚══════╝╚═════╝ ╚═╝     ╚══════╝ ╚═════╝  ╚══╝╚══╝"
echo ""
echo "  Installer v${VERSION}"
echo ""

# Check Java version
check_java() {
    if ! command -v java &> /dev/null; then
        echo "ERROR: Java not found. Please install JDK 17 or higher."
        exit 1
    fi
    local java_version
    java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    if [ "$java_version" = "1" ]; then
        java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f2)
    fi
    if [ "$java_version" -lt 17 ]; then
        echo "ERROR: Java ${java_version} detected. JDK 17+ is required."
        exit 1
    fi
    echo "    Java: $(java -version 2>&1 | head -n1)"
}

# Download JAR
download_jar() {
    local target="${INSTALL_DIR}/${JAR_NAME}"
    echo "==> Downloading ${JAR_NAME}..."
    if command -v curl &> /dev/null; then
        curl -fsSL --progress-bar "${DOWNLOAD_URL}" -o "${target}"
    elif command -v wget &> /dev/null; then
        wget --show-progress -q "${DOWNLOAD_URL}" -O "${target}"
    else
        echo "ERROR: curl or wget is required."
        exit 1
    fi
}

# Create wrapper script
create_wrapper() {
    local wrapper="${INSTALL_DIR}/nebflow"
    echo "==> Creating wrapper script..."
    cat > "${wrapper}" << 'WRAPPER'
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls -1 "${SCRIPT_DIR}"/nebflow-assembly-*.jar 2>/dev/null | head -n1)
if [ -z "$JAR" ]; then
    echo "ERROR: nebflow JAR not found in ${SCRIPT_DIR}"
    exit 1
fi
exec java --add-opens java.base/java.lang=ALL-UNNAMED -jar "$JAR" "$@"
WRAPPER
    chmod +x "${wrapper}"
}

# Create config template
create_config() {
    local config_dir="${HOME}/.nebflow"
    local config_file="${config_dir}/nebflow.json"
    if [ ! -f "${config_file}" ]; then
        echo "==> Creating default config at ${config_file}..."
        mkdir -p "${config_dir}"
        echo '{}' > "${config_file}"
        echo "    Config created: ${config_file}"
        echo "    Run 'nebflow -s' to start and configure via web UI."
    fi
}

# Run
check_java
download_jar
create_wrapper
create_config

echo ""
echo "==> Done! Nebflow v${VERSION} installed."
echo "    Run: nebflow --help"
echo "    Config: ~/.config/nebflow/nebflow.json"
