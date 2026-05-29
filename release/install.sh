#!/bin/bash
set -e

# Parse flags
CHANNEL="stable"
for arg in "$@"; do
    case "$arg" in
        --beta|--channel=beta) CHANNEL="beta" ;;
        --cn) REGION="cn" ;;
        --global) REGION="global" ;;
    esac
done

# Resolve version
if [ "$CHANNEL" = "beta" ]; then
    echo "==> Resolving latest beta version..."
    BETA_VERSION=$(curl -fsSL "https://api.github.com/repos/MashiroKai/Nebflow-Release/releases" \
        2>/dev/null | grep -m1 '"tag_name".*-beta"' | sed 's/.*"v\(.*\)-beta".*/\1/')
    if [ -z "$BETA_VERSION" ]; then
        echo "ERROR: Could not find a beta release."
        echo "       Visit https://github.com/MashiroKai/Nebflow-Release/releases to check availability."
        exit 1
    fi
    VERSION="${VERSION:-$BETA_VERSION}"
else
    VERSION="${VERSION:-1.00.006}"
fi

INSTALL_DIR="${INSTALL_DIR:-${HOME}/.nebflow/bin}"
JAR_NAME="nebflow-assembly-${VERSION}.jar"
COS_URL="https://nebflow-releases-1411212853.cos.ap-nanjing.myqcloud.com/${JAR_NAME}"
GH_URL="https://github.com/MashiroKai/Nebflow-Release/releases/download/v${VERSION}/${JAR_NAME}"
GH_BETA_URL="https://github.com/MashiroKai/Nebflow-Release/releases/download/v${VERSION}-beta/${JAR_NAME}"

echo ""
echo "  ███╗   ██╗███████╗██████╗ ███████╗██╗      ██████╗ ██╗    ██╗"
echo "  ████╗  ██║██╔════╝██╔══██╗██╔════╝██║     ██╔═══██╗██║    ██║"
echo "  ██╔██╗ ██║█████╗  ██████╔╝█████╗  ██║     ██║   ██║██║ █╗ ██║"
echo "  ██║╚██╗██║██╔══╝  ██╔══██╗██╔══╝  ██║     ██║   ██║██║███╗██║"
echo "  ██║ ╚████║███████╗██████╔╝██║     ███████╗╚██████╔╝╚███╔███╔╝"
echo "  ╚═╝  ╚═══╝╚══════╝╚═════╝ ╚═╝     ╚══════╝ ╚═════╝  ╚══╝╚══╝"
echo ""
echo "  Nebflow v${VERSION} Installer (${CHANNEL})"
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

# Auto-detect region: check if user is in China
detect_region() {
    if [ -n "$REGION" ]; then
        return 0  # already set by --cn or --global flag
    fi
    # Method 1: check system timezone
    local tz=""
    if [ -f /etc/timezone ]; then
        tz=$(cat /etc/timezone 2>/dev/null)
    elif command -v timedatectl &> /dev/null; then
        tz=$(timedatectl show -p Timezone 2>/dev/null | cut -d= -f2)
    fi
    case "$tz" in
        Asia/Shanghai|Asia/Chongqing|Asia/Hong_Kong|Asia/Taipei|Asia/Macau|PRC|Asia/Urumqi)
            REGION="cn"
            return 0
            ;;
    esac

    # Method 2: check LANG/LC_ALL for zh_CN
    case "${LANG:-}${LC_ALL:-}" in
        *zh_CN*|*zh_TW*|*zh_HK*)
            REGION="cn"
            return 0
            ;;
    esac

    # Method 3: try a quick connectivity test (COS is fast in China, slow elsewhere)
    # Test latency to COS vs GitHub — pick whichever responds first
    local cos_ms=99999 gh_ms=99999
    if command -v curl &> /dev/null; then
        cos_ms=$(curl -o /dev/null -s -w '%{time_total}' --connect-timeout 2 --max-time 3 \
            "https://nebflow-releases-1411212853.cos.ap-nanjing.myqcloud.com/" 2>/dev/null | \
            awk '{printf "%d", $1 * 1000}')
        gh_ms=$(curl -o /dev/null -s -w '%{time_total}' --connect-timeout 2 --max-time 3 \
            "https://github.com/favicon.ico" 2>/dev/null | \
            awk '{printf "%d", $1 * 1000}')
    fi

    # If COS is significantly faster (> 2x), user is likely in China
    if [ "$cos_ms" -lt 500 ] && [ "$cos_ms" -lt $((gh_ms / 2)) ]; then
        REGION="cn"
    else
        REGION="global"
    fi
}

# Download with automatic source selection
download_jar() {
    local target="${INSTALL_DIR}/${JAR_NAME}"
    mkdir -p "${INSTALL_DIR}"

    echo "==> Downloading ${JAR_NAME}..."

    # Remove old JAR files (keep .nebflow user data untouched)
    local old_jars
    old_jars=$(ls "${INSTALL_DIR}"/nebflow-assembly-*.jar 2>/dev/null || true)
    if [ -n "$old_jars" ]; then
        echo "    Removing old version(s)..."
        rm -f "${INSTALL_DIR}"/nebflow-assembly-*.jar
    fi

    # Beta always from GitHub
    if [ "$CHANNEL" = "beta" ]; then
        _download "${GH_BETA_URL}" "${target}" || {
            echo "ERROR: Download failed."
            exit 1
        }
        return 0
    fi

    detect_region
    echo "    Region: ${REGION} (use --cn or --global to override)"

    case "$REGION" in
        cn)
            # China: COS first (fast domestic CDN), GitHub fallback
            if _download "${COS_URL}" "${target}"; then
                return 0
            fi
            echo "       COS unavailable, trying GitHub..."
            _download "${GH_URL}" "${target}" || {
                echo "ERROR: Download failed from both sources."
                exit 1
            }
            ;;
        *)
            # Global: GitHub first (fast via public release repo), COS fallback
            if _download "${GH_URL}" "${target}"; then
                return 0
            fi
            echo "       GitHub unavailable, trying COS mirror..."
            _download "${COS_URL}" "${target}" || {
                echo "ERROR: Download failed from both sources."
                exit 1
            }
            ;;
    esac
}

_download() {
    local url="$1" target="$2"
    if command -v curl &> /dev/null; then
        curl -fsSL --connect-timeout 10 --max-time 120 "${url}" -o "${target}"
    elif command -v wget &> /dev/null; then
        wget --connect-timeout=10 --timeout=120 -q "${url}" -O "${target}"
    else
        echo "ERROR: curl or wget is required."
        exit 1
    fi
}

# Install ripgrep (rg) for Glob/Grep search support
install_rg() {
    if command -v rg &> /dev/null; then
        return 0
    fi
    local rg_local="${INSTALL_DIR}/rg"
    if [ -f "$rg_local" ]; then
        return 0
    fi

    echo "==> Installing ripgrep (rg) for search support..."
    local os arch url rg_ver="14.1.1"
    os="$(uname -s | tr '[:upper:]' '[:lower:]')"
    arch="$(uname -m)"
    case "$os" in
        linux)  url="https://github.com/BurntSushi/ripgrep/releases/download/$rg_ver/ripgrep-$rg_ver-x86_64-unknown-linux-musl.tar.gz" ;;
        darwin)
            if [ "$arch" = "arm64" ] || [ "$arch" = "aarch64" ]; then
                url="https://github.com/BurntSushi/ripgrep/releases/download/$rg_ver/ripgrep-$rg_ver-aarch64-apple-darwin.tar.gz"
            else
                url="https://github.com/BurntSushi/ripgrep/releases/download/$rg_ver/ripgrep-$rg_ver-x86_64-apple-darwin.tar.gz"
            fi
            ;;
        *) echo "       Skipping rg auto-install on $os" ; return 0 ;;
    esac
    local tmp_archive=$(mktemp)
    echo "       Downloading rg ${rg_ver}..."
    if _download "$url" "$tmp_archive"; then
        tar xzf "$tmp_archive" --to-stdout --wildcards "*/rg" > "$rg_local" 2>/dev/null && chmod +x "$rg_local"
        rm -f "$tmp_archive"
        if [ -f "$rg_local" ]; then
            echo "       rg installed to $rg_local"
        else
            echo "       rg extraction failed (tar may not support --wildcards)"
        fi
    else
        rm -f "$tmp_archive"
        echo "       rg download failed (search will rely on PATH install)"
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
        echo "    Run 'nebflow' to start using the CLI."
    fi
}

# Add install dir to PATH in shell profile
setup_path() {
    local profile=""
    if [ -f "${HOME}/.zshrc" ]; then
        profile="${HOME}/.zshrc"
    elif [ -f "${HOME}/.bashrc" ]; then
        profile="${HOME}/.bashrc"
    elif [ -f "${HOME}/.bash_profile" ]; then
        profile="${HOME}/.bash_profile"
    fi
    if [ -n "$profile" ] && ! grep -q "\.nebflow/bin" "$profile" 2>/dev/null; then
        echo "" >> "$profile"
        echo "# Added by nebflow installer" >> "$profile"
        echo "export PATH=\"\$HOME/.nebflow/bin:\$PATH\"" >> "$profile"
        echo "    Added ~/.nebflow/bin to PATH in $profile"
    fi
}

# Run
check_java
download_jar
install_rg
create_wrapper
create_config
setup_path

echo ""
echo "==> Done! Nebflow v${VERSION} installed."
echo "    Run: nebflow --help"
echo "    Config: ~/.nebflow/nebflow.json"
echo "    Please restart your terminal or run: export PATH=\"\$HOME/.nebflow/bin:\$PATH\""
