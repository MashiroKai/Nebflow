#!/bin/bash

# -- Brand values (L2 rebrand): rendered from repo-root brand.conf at -----
# -- release time (scripts/render-brand.sh); do not edit by hand. ----------
PRODUCT_NAME=stand
LOWER_NAME=stand
HOME_DIR=.stand
WRAPPER_NAME=stand
# No `set -e`: a single failed removal is recorded as residue, never a hard abort.

# Install base (再生治本批 2026-10-22): STAND_HOME first-priority, same rule as
# install.sh - the uninstaller never touches a NEBFLOW_HOME-pointed legacy root.
STAND_BASE="${STAND_HOME:-${HOME}/${HOME_DIR}}"

INSTALL_DIR="${INSTALL_DIR:-${STAND_BASE}/bin}"
CONFIG_DIR="${STAND_BASE}"

echo ""
echo "  ${PRODUCT_NAME} Uninstaller"
echo ""

if [ ! -f "${INSTALL_DIR}/${WRAPPER_NAME}" ]; then
    echo "  ${PRODUCT_NAME} is not installed."
    exit 1
fi

# Exit codes (mirror of uninstall.ps1): 0 = fully removed, 1 = not installed,
# 3 = partial (residue listed, rest removed). A failed item never aborts the run.
PARTIAL_EXIT=3
RESIDUE=""
add_residue() { RESIDUE="${RESIDUE}    $1
"; }

# Remove one file; a file that cannot go is recorded as residue, not fatal.
remove_item() {
    if [ -e "$1" ] || [ -L "$1" ]; then
        if ! rm -f "$1" 2>/dev/null; then
            add_residue "$1  [could not be removed]"
        fi
    fi
}

# Stop running same-home instances (graceful launcher stop, then sweep)
echo "[1/4] Stopping running ${PRODUCT_NAME} processes..."
if [ -x "${INSTALL_DIR}/${WRAPPER_NAME}" ]; then
    "${INSTALL_DIR}/${WRAPPER_NAME}" stop >/dev/null 2>&1 || true
fi
SURVIVORS=$(ps -axo pid=,command= 2>/dev/null | grep -E "(${LOWER_NAME}|nebflow)-assembly-[0-9].*\.jar" | grep -F "${INSTALL_DIR}" | grep -v grep || true)
if [ -n "${SURVIVORS}" ]; then
    echo "    Still running after stop (these jars stay locked):"
    echo "${SURVIVORS}" | sed 's/^/    /'
    add_residue "running process(es) listed above — stop them and re-run"
else
    echo "    No ${PRODUCT_NAME} process left."
fi

# Remove JAR and wrapper, item by item
echo "[2/4] Removing ${PRODUCT_NAME} files..."
remove_item "${INSTALL_DIR}/${WRAPPER_NAME}"
remove_item "${INSTALL_DIR}/nebflow"   # legacy command shim (2026-10-20 rename)
for _j in "${INSTALL_DIR}"/${LOWER_NAME}-assembly-*.jar "${INSTALL_DIR}"/nebflow-assembly-*.jar; do
    [ -e "$_j" ] && remove_item "$_j"
done
if [ -d "${INSTALL_DIR}" ]; then
    rmdir "${INSTALL_DIR}" 2>/dev/null || true
fi
if [ -d "${INSTALL_DIR}" ] && [ -n "$(ls -A "${INSTALL_DIR}" 2>/dev/null)" ]; then
    echo "    Partly removed: ${INSTALL_DIR}"
else
    echo "    Removed from ${INSTALL_DIR}"
fi

# PATH: this installer writes no PATH entry of its own (the user's shell rc is
# theirs), so there is nothing to clean; kept as an explicit step for parity.
echo "[3/4] Cleaning PATH..."
echo "    Done"

# Ask about config (user's choice; a failed delete is residue, not fatal)
echo "[4/4] Config..."
if [ -d "${CONFIG_DIR}" ]; then
    echo "    Config directory found: ${CONFIG_DIR}"
    read -p "    Delete config too? (y/N) " answer
    if [ "$answer" = "y" ] || [ "$answer" = "Y" ]; then
        if rm -rf "${CONFIG_DIR}" 2>/dev/null; then
            echo "    Config deleted."
        else
            add_residue "${CONFIG_DIR}  [config delete failed]"
        fi
    else
        echo "    Config kept."
    fi
fi

echo ""
if [ -z "${RESIDUE}" ] && [ ! -e "${INSTALL_DIR}/${WRAPPER_NAME}" ]; then
    echo "  ${PRODUCT_NAME} uninstalled."
    exit 0
fi
echo "  ${PRODUCT_NAME} partially uninstalled. Items that could not be removed:"
printf '%s' "${RESIDUE}"
echo "  Stop the listed processes (or reboot), then re-run the uninstaller or delete the items by hand."
exit ${PARTIAL_EXIT}
