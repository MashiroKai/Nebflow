#!/bin/bash
set -e

VERSION="${VERSION:-1.05.067}"
INSTALL_DIR="${INSTALL_DIR:-/usr/local/bin}"
JAR_NAME="nebflow-assembly-${VERSION}.jar"
DOWNLOAD_URL="https://nebflow-releases-1411212853.cos.ap-nanjing.myqcloud.com/${JAR_NAME}"

echo ""
echo "  ███╗   ██╗███████╗██████╗ ███████╗██╗      ██████╗ ██╗    ██╗"
echo "  ████╗  ██║██╔════╝██╔══██╗██╔════╝██║     ██╔═══██╗██║    ██║"
echo "  ██╔██╗ ██║█████╗  ██████╔╝█████╗  ██║     ██║   ██║██║ █╗ ██║"
echo "  ██║╚██╗██║██╔══╝  ██╔══██╗██╔══╝  ██║     ██║   ██║██║███╗██║"
echo "  ██║ ╚████║███████╗██████╔╝██║     ███████╗╚██████╔╝╚███╔███╔╝"
echo "  ╚═╝  ╚═══╝╚══════╝╚═════╝ ╚═╝     ╚══════╝ ╚═════╝  ╚══╝╚══╝"
echo ""
echo "  Nebflow v${VERSION} Installer"
echo ""

# Check Java version
check_java() {
    if ! command -v java &> /dev/null; then
        echo "ERROR: Java not found. JDK 17+ is required."
        echo ""
        echo "Install Java for your platform:"
        echo ""
        detect_os
        case "$OS_FAMILY" in
            mac)
                echo "  macOS (choose one):"
                echo "    brew install openjdk"
                echo "    https://adoptium.net/temurin/releases/?version=21&os=mac"
                ;;
            linux)
                echo "  Linux (choose one):"
                if command -v apt-get &> /dev/null; then
                    echo "    sudo apt install openjdk-21-jdk"
                elif command -v dnf &> /dev/null; then
                    echo "    sudo dnf install java-21-openjdk-devel"
                elif command -v yum &> /dev/null; then
                    echo "    sudo yum install java-21-openjdk-devel"
                else
                    echo "    Use your package manager to install JDK 17+"
                fi
                echo "    https://adoptium.net/temurin/releases/?version=21&os=linux"
                ;;
            windows)
                echo "  Windows:"
                echo "    winget install EclipseAdoptium.Temurin.21.JDK"
                echo "    https://adoptium.net/temurin/releases/?version=21&os=windows"
                ;;
            *)
                echo "  https://adoptium.net/temurin/releases/?version=21"
                ;;
        esac
        echo ""
        echo "After installing Java, re-run this script."
        exit 1
    fi
    local java_version
    java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    if [ "$java_version" = "1" ]; then
        java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f2)
    fi
    if [ "$java_version" -lt 17 ]; then
        echo "ERROR: Java ${java_version} detected, but JDK 17+ is required."
        echo ""
        echo "Upgrade Java:"
        echo "  https://adoptium.net/temurin/releases/?version=21"
        echo ""
        exit 1
    fi
    echo "    Java: $(java -version 2>&1 | head -n1)"
}

detect_os() {
    local uname_out="$(uname -s 2>/dev/null)"
    case "$uname_out" in
        Darwin*) OS_FAMILY="mac" ;;
        Linux*)  OS_FAMILY="linux" ;;
        MINGW*|MSYS*|CYGWIN*) OS_FAMILY="windows" ;;
        *)       OS_FAMILY="unknown" ;;
    esac
}

# Download with fallback: try primary, then GitHub Releases
download_jar() {
    local target="${INSTALL_DIR}/${JAR_NAME}"
    echo "==> Downloading ${JAR_NAME}..."
    if _download "${DOWNLOAD_URL}" "${target}"; then
        return 0
    fi
    # Primary down, fall back to GitHub Releases
    local mirror_url="https://github.com/MashiroKai/Nebflow/releases/download/v${VERSION}/${JAR_NAME}"
    echo "       Primary source unavailable, trying GitHub..."
    _download "${mirror_url}" "${target}" || {
        echo "ERROR: Download failed from both sources."
        exit 1
    }
}

_download() {
    local url="$1" target="$2"
    if command -v curl &> /dev/null; then
        curl -fsSL --connect-timeout 10 --max-time 120 --progress-bar "${url}" -o "${target}"
    elif command -v wget &> /dev/null; then
        wget --connect-timeout=10 --timeout=120 --show-progress -q "${url}" -O "${target}"
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
echo "    Config: ~/.nebflow/nebflow.json"
