#!/bin/bash
set -e

VERSION="${VERSION:-1.0.0}"
INSTALL_DIR="${INSTALL_DIR:-/usr/local/bin}"
JAR_NAME="nebflow-assembly-${VERSION}.jar"
DOWNLOAD_URL="https://github.com/MashiroKai/Nebflow/releases/download/v${VERSION}/${JAR_NAME}"

echo "==> Nebflow installer"
echo "    Version: ${VERSION}"
echo "    Install to: ${INSTALL_DIR}"

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
    local config_dir="${HOME}/.config/nebflow"
    local config_file="${config_dir}/nebflow.json"
    if [ ! -f "${config_file}" ]; then
        echo "==> Creating default config at ${config_file}..."
        mkdir -p "${config_dir}"
        cat > "${config_file}" << 'EOF'
{
  "llm": {
    "providers": {
      "anthropic": {
        "baseUrl": "https://api.anthropic.com",
        "apiKey": "${ANTHROPIC_API_KEY}",
        "protocol": "anthropic"
      }
    },
    "model": {
      "default": "anthropic/claude-sonnet-4-6"
    }
  },
  "mcpServers": {}
}
EOF
        echo "    Please edit ${config_file} to set your API key."
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
