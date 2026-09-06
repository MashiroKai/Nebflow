#!/usr/bin/env bash
# upload-deps.sh — one-shot uploader for third-party installer deps -> COS deps/
#
# Batch 2 of script-install-release v2 (.nebflow/Spec/script-install-release-v2.md):
# every platform dependency (ripgrep / JDK 21 msi / Git for Windows / Homebrew
# installer snapshot / Temurin linux tarballs) is mirrored on the release COS
# bucket under the `deps/` prefix, with a deps/checksums.txt manifest (sha256)
# generated at upload time. Install scripts (release/install.sh, release/
# install.ps1) try COS first, then demoted upstream sources.
#
# deps are LOW-FREQUENCY assets: this script is run manually (local or CI
# workflow_dispatch) when a dep version changes — it is intentionally NOT part
# of the release CI (D4).
#
# Bucket comes from brand.conf (cosBucket) — single source of truth.
# Upload goes through the accelerate endpoint, mirroring release.yml's COS job.
#
# Usage:
#   scripts/upload-deps.sh --list       # print the dependency manifest table
#   scripts/upload-deps.sh --fetch      # download upstream -> deps-staging/ + checksums.txt
#   scripts/upload-deps.sh --dry-run    # validate manifest + staging + report upstream reachability (no credentials needed)
#   scripts/upload-deps.sh --upload     # upload staging -> COS deps/ (needs coscmd + COS_SECRET_ID/COS_SECRET_KEY), then HEAD-verifies each object
#
# Notes:
# - homebrew-install.sh is a SNAPSHOT of Homebrew/install HEAD; re-run
#   --fetch before --upload to refresh snapshot + checksums together.
# - No linux aarch64 ripgrep entry: upstream ships no aarch64-linux-musl
#   tarball (verified 404, 2026-09-06); the installer uses the distro package
#   manager on that platform instead.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGING="${ROOT}/deps-staging"
MODE="${1:-}"

# Bucket from brand.conf (parser parity with scripts/render-brand.sh)
brand_value() {
  sed -n "s/^$1[[:space:]]*=[[:space:]]*//p" "${ROOT}/brand.conf" | sed 's/[[:space:]]#.*$//; s/[[:space:]]*$//' | head -1
}
COS_BUCKET="$(brand_value cosBucket)"
[[ -n "$COS_BUCKET" ]] || { echo "ERROR: cannot read cosBucket from brand.conf" >&2; exit 2; }

COS_CLIENT_BASE="https://${COS_BUCKET}.cos.ap-nanjing.myqcloud.com"   # what installers fetch
COS_UPLOAD_ENDPOINT="cos.accelerate.myqcloud.com"                      # what CI uploads through (release.yml precedent)

# name|upstream_url
DEPS=(
  "ripgrep-14.1.1-x86_64-pc-windows-msvc.zip|https://github.com/BurntSushi/ripgrep/releases/download/14.1.1/ripgrep-14.1.1-x86_64-pc-windows-msvc.zip"
  "ripgrep-14.1.1-aarch64-apple-darwin.tar.gz|https://github.com/BurntSushi/ripgrep/releases/download/14.1.1/ripgrep-14.1.1-aarch64-apple-darwin.tar.gz"
  "ripgrep-14.1.1-x86_64-apple-darwin.tar.gz|https://github.com/BurntSushi/ripgrep/releases/download/14.1.1/ripgrep-14.1.1-x86_64-apple-darwin.tar.gz"
  "ripgrep-14.1.1-x86_64-unknown-linux-musl.tar.gz|https://github.com/BurntSushi/ripgrep/releases/download/14.1.1/ripgrep-14.1.1-x86_64-unknown-linux-musl.tar.gz"
  "OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.msi|https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/windows/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.msi"
  "Git-2.55.0.3-64-bit.exe|https://registry.npmmirror.com/-/binary/git-for-windows/v2.55.0.windows.3/Git-2.55.0.3-64-bit.exe"
  "homebrew-install.sh|https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh"
  "OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz|https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/linux/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz"
  "OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.12.1_1.tar.gz|https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/aarch64/linux/OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.12.1_1.tar.gz"
)

die() { echo "upload-deps.sh: $*" >&2; exit 1; }

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else die "need shasum or sha256sum to build checksums.txt"; fi
}

cmd_list() {
  printf "%-58s %s\n" "FILE (COS key: deps/<name>)" "UPSTREAM"
  for entry in "${DEPS[@]}"; do
    local name="${entry%%|*}" url="${entry#*|}"
    printf "%-58s %s\n" "$name" "$url"
  done
  echo ""
  echo "COS client base : ${COS_CLIENT_BASE}/deps/"
  echo "COS upload via  : ${COS_UPLOAD_ENDPOINT} (bucket ${COS_BUCKET}, from brand.conf)"
}

fetch_one() {
  local name="$1" url="$2"
  local target="${STAGING}/${name}"
  echo "[fetch] ${name}"
  # Direct first, then ghproxy front (github.com / raw.githubusercontent are
  # unreachable on some domestic networks; the staging operator deserves the
  # same fallback the installers have).
  if command -v curl >/dev/null 2>&1; then
    curl -fL --connect-timeout 15 --max-time 1800 --retry 2 -o "$target" "$url" \
      || curl -fL --connect-timeout 15 --max-time 1800 --retry 2 -o "$target" "https://ghproxy.net/${url}" \
      || return 1
  else
    wget --timeout=1800 -q -O "$target" "$url" \
      || wget --timeout=1800 -q -O "$target" "https://ghproxy.net/${url}" \
      || return 1
  fi
  [ -s "$target" ] || { echo "  -> empty download, removing"; rm -f "$target"; return 1; }
}

cmd_fetch() {
  mkdir -p "$STAGING"
  local failed=0
  for entry in "${DEPS[@]}"; do
    fetch_one "${entry%%|*}" "${entry#*|}" || { echo "[warn] fetch failed: ${entry%%|*}" >&2; failed=1; }
  done
  [ "$failed" = "0" ] || die "some downloads failed - fix the table or network and re-run"
  build_checksums
  echo "[ok] staging complete: ${STAGING}"
}

build_checksums() {
  : > "${STAGING}/checksums.txt"
  local entry name size
  for entry in "${DEPS[@]}"; do
    name="${entry%%|*}"
    [ -s "${STAGING}/${name}" ] || die "staging file missing: ${name} (run --fetch first)"
    # Size sentinel: upstream error pages can come back as HTTP 200 with a
    # tiny body; installers re-verify sha256 at download time, but refuse to
    # stage obviously-broken artifacts here in the first place.
    size="$(stat -f%z "${STAGING}/${name}" 2>/dev/null || stat -c%s "${STAGING}/${name}")"
    if [ "$name" != "homebrew-install.sh" ] && [ "$size" -lt 1000000 ]; then
      die "staged file suspiciously small (${size}B): ${name} - upstream likely served an error page; re-run --fetch"
    fi
    printf "%s  %s\n" "$(sha256_of "${STAGING}/${name}")" "$name" >> "${STAGING}/checksums.txt"
  done
  echo "[ok] ${STAGING}/checksums.txt written ($(wc -l < "${STAGING}/checksums.txt" | tr -d ' ') entries)"
}

cmd_dry_run() {
  echo "== dry-run validation (no credentials needed) =="
  local rc=0 entry name url target expected actual
  # 1) manifest integrity: unique names, plausible URLs
  local -A seen=()
  for entry in "${DEPS[@]}"; do
    name="${entry%%|*}" url="${entry#*|}"
    [[ -n "$name" && -n "$url" ]] || { echo "[FAIL] malformed entry: ${entry}"; rc=1; continue; }
    [[ -z "${seen[$name]:-}" ]] || { echo "[FAIL] duplicate name: ${name}"; rc=1; }
    seen[$name]=1
    case "$url" in
      https://*) : ;;
      *) echo "[FAIL] non-https upstream for ${name}"; rc=1 ;;
    esac
  done
  echo "[i] manifest entries: ${#DEPS[@]} (unique+format checked)"
  # 2) staging presence + checksum agreement
  if [ -f "${STAGING}/checksums.txt" ]; then
    build_checksums   # regenerate + cross-check deterministically
    while read -r expected fname; do
      target="${STAGING}/${fname}"
      actual="$(sha256_of "$target")"
      if [ "$actual" = "$expected" ]; then
        echo "[ok] staged+checksum: ${fname} ($(du -h "$target" | cut -f1))"
      else
        echo "[FAIL] checksum mismatch in staging: ${fname}"; rc=1
      fi
    done < "${STAGING}/checksums.txt"
  else
    echo "[warn] no staging yet (${STAGING}/checksums.txt absent) - run --fetch first; only manifest validated"
  fi
  # 3) upstream reachability report (informational; github may be unreachable
  #    on domestic networks - that is exactly why the COS mirror exists)
  for entry in "${DEPS[@]}"; do
    name="${entry%%|*}" url="${entry#*|}"
    if curl -sIL --connect-timeout 8 --max-time 20 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null | grep -qE '^(200|302)'; then
      echo "[net] upstream reachable: ${name}"
    else
      echo "[net] upstream NOT verified (may be network-dependent): ${name}"
    fi
  done
  # 4) target COS keys report
  echo "[i] upload would publish to: ${COS_CLIENT_BASE}/deps/<name> + deps/checksums.txt"
  exit $rc
}

cmd_upload() {
  command -v coscmd >/dev/null 2>&1 || die "coscmd not installed (pip install coscmd)"
  : "${COS_SECRET_ID:?COS_SECRET_ID env required}" 
  : "${COS_SECRET_KEY:?COS_SECRET_KEY env required}"
  [ -f "${STAGING}/checksums.txt" ] || die "no checksums.txt in staging - run --fetch first"
  build_checksums   # regenerate so staging and manifest are guaranteed consistent
  coscmd config -a "$COS_SECRET_ID" -s "$COS_SECRET_KEY" -b "$COS_BUCKET" -e "$COS_UPLOAD_ENDPOINT"
  local entry name
  for entry in "${DEPS[@]}"; do
    name="${entry%%|*}"
    echo "[upload] deps/${name}"
    coscmd upload "${STAGING}/${name}" "deps/${name}" -f
  done
  echo "[upload] deps/checksums.txt"
  coscmd upload "${STAGING}/checksums.txt" "deps/checksums.txt" -f
  # Post-upload verification: every object must be publicly fetchable (200)
  local rc=0
  for entry in "${DEPS[@]}"; do
    name="${entry%%|*}"
    local code
    code="$(curl -sIL -o /dev/null -w '%{http_code}' --max-time 30 "${COS_CLIENT_BASE}/deps/${name}")"
    if [ "$code" = "200" ]; then echo "[ok] ${COS_CLIENT_BASE}/deps/${name}"
    else echo "[FAIL] ${COS_CLIENT_BASE}/deps/${name} -> HTTP ${code}"; rc=1; fi
  done
  code="$(curl -sIL -o /dev/null -w '%{http_code}' --max-time 30 "${COS_CLIENT_BASE}/deps/checksums.txt")"
  [ "$code" = "200" ] && echo "[ok] ${COS_CLIENT_BASE}/deps/checksums.txt" || { echo "[FAIL] checksums.txt -> HTTP ${code}"; rc=1; }
  [ $rc = 0 ] && echo "[done] all deps live on COS." || die "post-upload verification failed"
}

case "$MODE" in
  --list)    cmd_list ;;
  --fetch)   cmd_fetch ;;
  --dry-run) cmd_dry_run ;;
  --upload)  cmd_upload ;;
  *)         cmd_list >&2; die "usage: $0 --list | --fetch | --dry-run | --upload" ;;
esac
