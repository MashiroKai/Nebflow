#!/usr/bin/env bash
# upload-deps.sh — one-shot uploader for third-party installer deps -> OSS deps/
#
# Batch 2 of script-install-release v2 (.nebflow/Spec/script-install-release-v2.md):
# every platform dependency (ripgrep / JDK 21 msi / Git for Windows / Homebrew
# installer snapshot / Temurin linux tarballs) is mirrored on the release
# bucket under the `deps/` prefix, with a deps/checksums.txt manifest (sha256)
# generated at upload time. Install scripts (release/install.sh, release/
# install.ps1) try the mirror first, then demoted upstream sources.
#
# Endpoint (installmirror batch, 2026-09-25): the write end is now the SAME
# bucket + prefix the installers read (release/install.sh:30 COS_BASE_CN,
# release/install.ps1:39 $CosBaseCn -> oss-cn-hangzhou.aliyuncs.com). Between
# 2026-09-06 and 2026-09-25 this script uploaded to a Tencent COS endpoint
# whose bucket did not even exist (<Code>NoSuchBucket</Code>): the deps/
# prefix stayed empty for 18 days and both installers silently degraded to
# unverified installs. The COS_ bucket-variable name stays (brand.conf key is
# `cosBucket`, name kept by ruling; only the value face changed).
#
# deps are LOW-FREQUENCY assets: run manually or from the release workflow
# when a dep version changes. release.yml now re-runs --fetch && --upload on
# every release and then asserts public availability of every object, so the
# prefix can no longer rot silently.
#
# Bucket comes from brand.conf (cosBucket) — single source of truth.
# Credentials come from the environment (OSS_ACCESS_KEY_ID /
# OSS_ACCESS_KEY_SECRET — same names release.yml maps from repo secrets).
# ossutil is invoked with per-command -i/-k/-e flags, so no credential ever
# touches a config file on disk.
#
# Usage:
#   scripts/upload-deps.sh --list              # print the dependency manifest table
#   scripts/upload-deps.sh --fetch             # download upstream -> deps-staging/ + checksums.txt
#   scripts/upload-deps.sh --fetch-one <name>  # fetch a single object (batched-leg mode)
#   scripts/upload-deps.sh --dry-run           # validate manifest + staging + upstream reachability (no credentials needed)
#   scripts/upload-deps.sh --upload            # upload staging -> OSS deps/ (needs OSS_ACCESS_KEY_ID/OSS_ACCESS_KEY_SECRET), then GET-verifies every object
#
# Notes:
# - homebrew-install.sh is a SNAPSHOT of Homebrew/install HEAD; re-run
#   --fetch before --upload to refresh snapshot + checksums together.
# - No linux aarch64 ripgrep entry: upstream ships no aarch64-linux-musl
#   tarball (verified 404, 2026-09-06); the installer uses the distro package
#   manager on that platform instead.
# - Staging directory override: DEPS_STAGING=<dir> (default <repo>/deps-staging).
#   Useful for a fresh re-fetch that must not touch an existing staging tree.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGING="${DEPS_STAGING:-${ROOT}/deps-staging}"
MODE="${1:-}"

# Bucket from brand.conf (parser parity with scripts/render-brand.sh)
brand_value() {
  sed -n "s/^$1[[:space:]]*=[[:space:]]*//p" "${ROOT}/brand.conf" | sed 's/[[:space:]]#.*$//; s/[[:space:]]*$//' | head -1
}
COS_BUCKET="$(brand_value cosBucket)"
[[ -n "$COS_BUCKET" ]] || { echo "ERROR: cannot read cosBucket from brand.conf" >&2; exit 2; }

# Same bucket + endpoint the installers read (single mirror, both faces).
OSS_ENDPOINT="oss-cn-hangzhou.aliyuncs.com"
OSS_CLIENT_BASE="https://${COS_BUCKET}.${OSS_ENDPOINT}"

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

ossutil_bin() {
  # Pinned ossutil 1.7.19 (same version release.yml installs for the jar
  # upload). Zip sha256: linux value is the one release.yml has pinned since
  # the 2026-09-14 bucket switch; mac-arm64 value measured at pinning time
  # (installmirror batch). Unmatched platforms must bring their own ossutil.
  if command -v ossutil >/dev/null 2>&1; then printf 'ossutil'; return 0; fi
  local os arch url want dir zip bin
  os="$(uname -s)"; arch="$(uname -m)"
  case "${os}/${arch}" in
    Linux/x86_64)
      url="https://gosspublic.alicdn.com/ossutil/1.7.19/ossutil-v1.7.19-linux-amd64.zip"
      want="dcc512e4a893e16bbee63bc769339d8e56b21744fd83c8212a9d8baf28767343" ;;
    Darwin/arm64)
      url="https://gosspublic.alicdn.com/ossutil/1.7.19/ossutil-v1.7.19-mac-arm64.zip"
      want="10ece4d328c5d2440833adc5f4167168e9b2a4c5d364f673b0c45bcc4fd02ec5" ;;
    *)
      die "no pinned ossutil build for ${os}/${arch} - install ossutil on PATH and re-run" ;;
  esac
  dir="${TMPDIR:-/tmp}/upload-deps-ossutil-1.7.19-${arch}"
  bin="${dir}/ossutil"
  if [ ! -x "$bin" ]; then
    mkdir -p "$dir"
    zip="${dir}/ossutil.zip"
    # Progress note goes to stderr: the caller captures this function's stdout
    # via command substitution, so a stdout echo would corrupt the binary path.
    echo "[i] downloading pinned ossutil 1.7.19 (${os}/${arch})" >&2
    curl -fL --connect-timeout 15 --max-time 300 --retry 2 -o "$zip" "$url" \
      || die "pinned ossutil download failed: ${url}"
    [ "$(sha256_of "$zip")" = "$want" ] || die "pinned ossutil zip sha256 mismatch - refusing to run"
    unzip -q -j -o "$zip" -d "$dir" || die "unzip failed for the pinned ossutil archive"
    bin="$(find "$dir" -type f -name 'ossutil*' ! -name '*.zip' | head -1)"
    [ -n "$bin" ] || die "ossutil binary not found inside the pinned archive"
    chmod +x "$bin" 2>/dev/null || true
    [ -x "$bin" ] || die "ossutil binary is not executable: ${bin}"
  fi
  printf '%s' "$bin"
}

cmd_list() {
  printf "%-58s %s\n" "FILE (mirror key: deps/<name>)" "UPSTREAM"
  for entry in "${DEPS[@]}"; do
    local name="${entry%%|*}" url="${entry#*|}"
    printf "%-58s %s\n" "$name" "$url"
  done
  echo ""
  echo "Mirror base (what installers fetch): ${OSS_CLIENT_BASE}/deps/"
  echo "Write target: oss://${COS_BUCKET}/deps/ via ${OSS_ENDPOINT}"
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

cmd_fetch_one() {
  local name="$1" entry found=""
  for entry in "${DEPS[@]}"; do
    if [ "${entry%%|*}" = "$name" ]; then found="${entry#*|}"; break; fi
  done
  [ -n "$found" ] || die "unknown dep name: ${name} (see --list)"
  mkdir -p "$STAGING"
  fetch_one "$name" "$found" || die "fetch failed: ${name}"
  echo "[ok] ${name} staged ($(stat -f%z "${STAGING}/${name}" 2>/dev/null || stat -c%s "${STAGING}/${name}")B sha256 $(sha256_of "${STAGING}/${name}"))"
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
  #    on domestic networks - that is exactly why the mirror exists)
  for entry in "${DEPS[@]}"; do
    name="${entry%%|*}" url="${entry#*|}"
    if curl -sIL --connect-timeout 8 --max-time 20 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null | grep -qE '^(200|302)'; then
      echo "[net] upstream reachable: ${name}"
    else
      echo "[net] upstream NOT verified (may be network-dependent): ${name}"
    fi
  done
  # 4) target mirror keys report
  echo "[i] upload would publish to: ${OSS_CLIENT_BASE}/deps/<name> + deps/checksums.txt"
  exit $rc
}

cmd_upload() {
  : "${OSS_ACCESS_KEY_ID:?OSS_ACCESS_KEY_ID env required}"
  : "${OSS_ACCESS_KEY_SECRET:?OSS_ACCESS_KEY_SECRET env required}"
  [ -f "${STAGING}/checksums.txt" ] || die "no checksums.txt in staging - run --fetch first"
  build_checksums   # regenerate so staging and manifest are guaranteed consistent
  local util
  util="$(ossutil_bin)"
  # Target-bucket existence precheck (2026-09-25 root-cause addendum): the
  # old write end pointed at a bucket that did not exist at all
  # (<Code>NoSuchBucket</Code>), so a "successful" upload was impossible.
  # Refuse to write when the target bucket root answers NoSuchBucket.
  local precheck body_code
  precheck="$(curl -sS --connect-timeout 6 --max-time 15 "${OSS_CLIENT_BASE}/" 2>/dev/null || true)"
  if [ -z "$precheck" ]; then
    die "target bucket root unreachable (${OSS_CLIENT_BASE}/) - precheck inconclusive, refusing to upload"
  fi
  body_code="$(printf '%s' "$precheck" | grep -oE '<Code>[^<]+' | head -1 | cut -d'>' -f2 || true)"
  if [ "$body_code" = "NoSuchBucket" ]; then
    die "target bucket ${COS_BUCKET} does not exist (NoSuchBucket) - fix bucket/endpoint before uploading"
  fi
  echo "[ok] target bucket precheck passed (${OSS_CLIENT_BASE}/ root code: ${body_code:-none})"
  local entry name
  for entry in "${DEPS[@]}"; do
    name="${entry%%|*}"
    echo "[upload] deps/${name}"
    "$util" cp "${STAGING}/${name}" "oss://${COS_BUCKET}/deps/${name}" -f \
      -i "$OSS_ACCESS_KEY_ID" -k "$OSS_ACCESS_KEY_SECRET" -e "$OSS_ENDPOINT"
  done
  echo "[upload] deps/checksums.txt"
  "$util" cp "${STAGING}/checksums.txt" "oss://${COS_BUCKET}/deps/checksums.txt" -f \
    -i "$OSS_ACCESS_KEY_ID" -k "$OSS_ACCESS_KEY_SECRET" -e "$OSS_ENDPOINT"
  # Post-upload verification is a GET, not a HEAD: every object must come
  # back byte-for-byte (HTTP 200 AND full sha256 equal to the freshly staged
  # manifest). "The upload command exited 0" is NOT acceptance - that is
  # exactly how the 2026-09 emptiness went unnoticed for 18 days.
  local rc=0 code expected actual got
  for entry in "${DEPS[@]}"; do
    name="${entry%%|*}"
    got="$(mktemp "${TMPDIR:-/tmp}/upload-deps-verify-XXXXXX")"
    code="$(curl -fsSL -o "$got" -w '%{http_code}' --connect-timeout 8 --max-time 900 \
      "${OSS_CLIENT_BASE}/deps/${name}" 2>/dev/null || true)"
    expected="$(awk -v f="$name" '$2 == f { print $1; exit }' "${STAGING}/checksums.txt")"
    actual="$(sha256_of "$got" 2>/dev/null || true)"
    rm -f "$got"
    if [ "$code" = "200" ] && [ -n "$expected" ] && [ "$actual" = "$expected" ]; then
      echo "[ok] GET sha256 match: deps/${name}"
    else
      echo "[FAIL] deps/${name} -> HTTP ${code:-none} sha256 ${actual:-none} (expected ${expected:-none})"; rc=1
    fi
  done
  got="$(mktemp "${TMPDIR:-/tmp}/upload-deps-verify-XXXXXX")"
  code="$(curl -fsSL -o "$got" -w '%{http_code}' --connect-timeout 8 --max-time 120 \
    "${OSS_CLIENT_BASE}/deps/checksums.txt" 2>/dev/null || true)"
  if [ "$code" = "200" ] && cmp -s "$got" "${STAGING}/checksums.txt"; then
    echo "[ok] GET byte-identical: deps/checksums.txt"
  else
    echo "[FAIL] deps/checksums.txt -> HTTP ${code:-none} or bytes differ from staging"; rc=1
  fi
  rm -f "$got"
  if [ "$rc" = "0" ]; then
    echo "[done] all deps live on ${OSS_CLIENT_BASE}/deps/ and sha256-verified."
  else
    die "post-upload verification failed"
  fi
}

case "$MODE" in
  --list)       cmd_list ;;
  --fetch)      cmd_fetch ;;
  --fetch-one)  shift; cmd_fetch_one "${1:-}";;
  --dry-run)    cmd_dry_run ;;
  --upload)     cmd_upload ;;
  *)            cmd_list >&2; die "usage: $0 --list | --fetch | --fetch-one <name> | --dry-run | --upload" ;;
esac
