#!/bin/bash

# -- Brand values (L2 rebrand): rendered from repo-root brand.conf at -----
# -- release time (scripts/render-brand.sh); do not edit by hand. ----------
PRODUCT_NAME=Nebflow
LOWER_NAME=nebflow
HOME_DIR=.nebflow
WRAPPER_NAME=nebflow
set -e

INSTALL_DIR="${INSTALL_DIR:-/usr/local/bin}"
CONFIG_DIR="${HOME}/${HOME_DIR}"

echo ""
echo "  ${PRODUCT_NAME} Uninstaller"
echo ""

if [ ! -f "${INSTALL_DIR}/${WRAPPER_NAME}" ]; then
    echo "  ${PRODUCT_NAME} is not installed."
    exit 1
fi

# Remove JAR and wrapper
echo "[1/3] Removing ${PRODUCT_NAME} files..."
rm -f "${INSTALL_DIR}/${WRAPPER_NAME}"
rm -f "${INSTALL_DIR}"/${LOWER_NAME}-assembly-*.jar "${INSTALL_DIR}"/nebflow-assembly-*.jar
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
echo "  ${PRODUCT_NAME} uninstalled."
