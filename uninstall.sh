#!/bin/bash
set -e

INSTALL_DIR="${INSTALL_DIR:-/usr/local/bin}"
CONFIG_DIR="${HOME}/.nebflow"

echo ""
echo "  Nebflow Uninstaller"
echo ""

if [ ! -f "${INSTALL_DIR}/nebflow" ]; then
    echo "  Nebflow is not installed."
    exit 1
fi

# Remove JAR and wrapper
echo "[1/3] Removing Nebflow files..."
rm -f "${INSTALL_DIR}/nebflow"
rm -f "${INSTALL_DIR}"/nebflow-assembly-*.jar
echo "    Removed from ${INSTALL_DIR}"

# Remove symlink if exists
echo "[2/3] Cleaning PATH..."
echo "    Done"

# Ask about config
echo "[3/3] Config..."
if [ -d "${CONFIG_DIR}" ]; then
    echo "    Config directory found: ${CONFIG_DIR}"
    read -p "    Delete config too? (y/N) " answer
    if [ "$answer" = "y" ] || [ "$answer" = "Y" ]; then
        rm -rf "${CONFIG_DIR}"
        echo "    Config deleted."
    else
        echo "    Config kept."
    fi
fi

echo ""
echo "  Nebflow uninstalled."
