#!/usr/bin/env bash
# rebrand.sh — one-shot product rename (batch 4 of the rebrand
# parameterization, 2026-08-17).
#
# Input: the repo-root brand.conf ALREADY carries the NEW brand values (it is
# the only edit point). This script then mechanically rewrites everything
# that could not be parameterized in batches 1-3, verifies the result, and
# prints the human checklist for the external steps (DNS, GitHub, COS...).
#
# The LEGACY name "nebflow" is hardcoded here as the historical fact — the
# runtime compat layer (batch 3) keeps old data working after the rename.
#
# Usage:
#   scripts/rebrand.sh --dry-run            # replacement manifest, no writes
#   scripts/rebrand.sh --apply [--skip-external] [--skip-packaging]
#                       [--skip-smoke] [--skip-tests]
#
# Apply chain:
#   0. preflight  — brand.conf complete/valid; (unless --skip-external)
#                   DNS resolves, GitHub org reachable
#   1. rewrite    — package tree rename, package/import lines, class-5 web
#                   identifiers, docs (README/CODEBASE)
#   2. verify     — rg residual scans against the compat whitelist
#   3. build      — sbt clean assembly + test (unless --skip-tests)
#   4. smoke      — real jar boot + /api/health + index byte assertions +
#                   legacy-fixture migration smoke (unless --skip-smoke)
#   5. package    — dmg/msi via packaging scripts (unless --skip-packaging)
#   6. render     — release/{install,uninstall}.* brand blocks
#                   (scripts/render-brand.sh)
#   7. checklist  — REBRAND-CHECKLIST.md + terminal printout
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONF="$ROOT/brand.conf"
MODE=""
SKIP_EXTERNAL=0; SKIP_PACKAGING=0; SKIP_SMOKE=0; SKIP_TESTS=0
for arg in "$@"; do
  case "$arg" in
    --dry-run)        MODE="dry" ;;
    --apply)          MODE="apply" ;;
    --skip-external)  SKIP_EXTERNAL=1 ;;
    --skip-packaging) SKIP_PACKAGING=1 ;;
    --skip-smoke)     SKIP_SMOKE=1 ;;
    --skip-tests)     SKIP_TESTS=1 ;;
    *) echo "unknown option: $arg (use --dry-run | --apply [--skip-*])" >&2; exit 1 ;;
  esac
done
[[ -n "$MODE" ]] || { echo "usage: $0 --dry-run | --apply [--skip-external] [--skip-packaging] [--skip-smoke] [--skip-tests]" >&2; exit 1; }

# ── brand.conf parsing (parity with project/Branding.scala) ───────────────
brand_value() {
  sed -n "s/^$1[[:space:]]*=[[:space:]]*//p" "$CONF" | sed 's/[[:space:]]#.*$//; s/[[:space:]]*$//' | head -1
}

die() { echo "rebrand: $*" >&2; exit 1; }

# BSD sed wants `sed "${SED_I[@]}"`, GNU sed wants `sed -i` — detect once.
if sed --version 2>/dev/null | grep -q GNU; then SED_I=(-i); else SED_I=(-i ''); fi

# ── 0. preflight ───────────────────────────────────────────────────────────
[[ -f "$CONF" ]] || die "brand.conf not found at repo root"
PRODUCT_NAME="$(brand_value productName)"
LOWER_NAME="$(brand_value lowerName)"
FULL_NAME="$(brand_value fullName)"
DOMAIN="$(brand_value domain)"
GH_ORG="$(brand_value githubOrg)"
GH_REPO="$(brand_value githubRepo)"
HOME_DIR="$(brand_value homeDirName)"
ENV_PREFIX="$(brand_value envPrefix)"
COS_BUCKET="$(brand_value cosBucket)"
for v in PRODUCT_NAME LOWER_NAME FULL_NAME DOMAIN GH_ORG GH_REPO HOME_DIR ENV_PREFIX COS_BUCKET; do
  val="${!v}"
  [[ -n "$val" ]] || die "brand.conf incomplete: $v is empty/missing"
done

# camelCase and PascalCase derivations for JS identifiers.
# perl for case conversion — BSD sed has no \U (portability trap).
CAMEL="$(printf '%s' "$LOWER_NAME" | perl -pe 's/(?:^|[-_])([a-z])/\U$1/g')"
PASCAL="$(printf '%s' "${CAMEL:0:1}" | tr 'a-z' 'A-Z')${CAMEL:1}"

LEGACY_LOWER="nebflow"      # hardcoded historical facts — never derived
LEGACY_PASCAL="Nebflow"

echo "== rebrand: $LEGACY_PASCAL -> $PRODUCT_NAME (lower: $LEGACY_LOWER -> $LOWER_NAME) =="

no_op_run() {
  [[ "$LOWER_NAME" == "$LEGACY_LOWER" ]] && { echo "(brand.conf still carries the current values — every step below would no-op)"; }
}

# ── the replacement manifest (shared by --dry-run and --apply) ─────────────
# Each entry: <glob> <sed-expr...> — applied with LC_ALL=C for byte safety.
# Class-5 web identifiers use EXACT tokens (not a broad prefix) so the
# localStorage legacy constants in branding.js are never touched.
manifest() {
  cat <<'MANIFEST'
# 1. Scala package tree: src/{main,test}/scala/nebflow -> new package
DIR_RENAME|src/main/scala/nebflow|src/main/scala/@LOWER@
DIR_RENAME|src/test/scala/nebflow|src/test/scala/@LOWER@
# 2. package/import/fully-qualified refs in all Scala/sbt sources.
#    No \b word boundaries — BSD and GNU sed disagree on them; line-start
#    anchors and dotted contexts are exact enough.
SED|**/*.scala|s/^package nebflow/package @LOWER@/
SED|**/*.scala|s/^import nebflow/import @LOWER@/
SED|**/*.scala|s/nebflow\./@LOWER@./g
SED|**/*.scala|s/private\[nebflow\]/private[@LOWER@]/g
SED|**/*.scala|s/"nebflow is /"@LOWER@ is /g
SED|build.sbt|s/nebflow\.Main/@LOWER@.Main/g
SED|project/Branding.scala|s/nebflow\.core\.Branding/@LOWER@.core.Branding/g
# 3. Class-5 web identifiers (exact tokens — protects branding.js LEGACY_*)
SED|src/main/resources/web/**|s/nebflow-toast/@LOWER@-toast/g
SED|src/main/resources/web/**|s/nebflow-login-modal/@LOWER@-login-modal/g
SED|src/main/resources/web/**|s/nebflow-session-change/@LOWER@-session-change/g
SED|src/main/resources/web/**|s/nebflow-col-resize/@LOWER@-col-resize/g
SED|src/main/resources/web/**|s/nebflow-dark/@LOWER@-dark/g
SED|src/main/resources/web/**|s/nebflow-light/@LOWER@-light/g
SED|src/main/resources/web/**|s/nebflowLoginIn/@CAMEL@LoginIn/g
# 4. window.Nebflow: definition moves to the new PascalCase global; a
#    permanent alias assignment keeps old embeds working (frontend ruling).
SPECIAL|window-global|src/main/resources/web/js/main.js
# 5. Docs: brand words + command examples
SED|README.md|s/nebflow/@LOWER@/g
SED|README.md|s/Nebflow/@PRODUCT@/g
SED|CODEBASE.md|s/nebflow/@LOWER@/g
SED|CODEBASE.md|s/Nebflow/@PRODUCT@/g
MANIFEST
}

expand() { sed -e "s/@LOWER@/$LOWER_NAME/g" -e "s/@CAMEL@/$CAMEL/g" -e "s/@PRODUCT@/$PRODUCT_NAME/g"; }

# ── --dry-run: manifest + affected-file preview, no writes ────────────────
if [[ "$MODE" == "dry" ]]; then
  no_op_run
  echo
  echo "==== replacement manifest (dry run — nothing is written) ===="
  manifest | expand
  echo
  echo "==== affected files per section ===="
  echo "-- package tree:"
  echo "   src/main/scala/$LEGACY_LOWER  (git mv -> src/main/scala/$LOWER_NAME)"
  echo "   src/test/scala/$LEGACY_LOWER  (git mv -> src/test/scala/$LOWER_NAME)"
  echo "-- Scala files containing package/import lines: $(rg -l "^package nebflow|^import nebflow" --glob '*.scala' "$ROOT/src" 2>/dev/null | wc -l | tr -d ' ') files"
  echo "-- class-5 web identifiers: $(rg -l "nebflow-toast|nebflow-login-modal|nebflow-session-change|nebflow-col-resize|nebflow-dark|nebflow-light|nebflowLoginIn" "$ROOT/src/main/resources/web" 2>/dev/null | wc -l | tr -d ' ') files"
  echo "-- window global: src/main/resources/web/js/main.js (def) -> window.$PASCAL + permanent alias window.$LEGACY_PASCAL = window.$PASCAL"
  echo "-- docs: README.md ($(rg -ci "$LEGACY_LOWER" "$ROOT/README.md" 2>/dev/null || echo 0) hits), CODEBASE.md ($(rg -ci "$LEGACY_LOWER" "$ROOT/CODEBASE.md" 2>/dev/null || echo 0) hits)"
  echo
  echo "==== sample replacement previews ===="
  for f in src/main/resources/web/css/modal.css src/main/resources/web/js/monacoEditor.js; do
    echo "-- $f:"
    rg -n "nebflow-(toast|login-modal|dark|light)" "$ROOT/$f" 2>/dev/null | head -3 | sed 's/^/     /' || true
  done
  echo "-- README.md:"
  rg -n "\b$LEGACY_LOWER\b" "$ROOT/README.md" 2>/dev/null | head -3 | sed 's/^/     /' || true
  echo
  echo "dry run complete — run with --apply to execute (expects sbt, java, and optionally jpackage)."
  exit 0
fi

# ══ --apply ════════════════════════════════════════════════════════════════
echo "[0/7] preflight"
if [[ $SKIP_EXTERNAL -eq 0 ]]; then
  dns="$(dig +short "$DOMAIN" 2>/dev/null | head -1 || true)"
  [[ -n "$dns" ]] || die "DNS for $DOMAIN does not resolve (use --skip-external during offline prep)"
  code="$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 8 "https://api.github.com/orgs/$GH_ORG" || true)"
  [[ "$code" == "200" ]] || die "GitHub org $GH_ORG not reachable ($code) — create it first, or --skip-external"
fi

echo "[1/7] rewrite"
# 1a. package tree
for pair in "src/main/scala/$LEGACY_LOWER:src/main/scala/$LOWER_NAME" "src/test/scala/$LEGACY_LOWER:src/test/scala/$LOWER_NAME"; do
  from="${pair%%:*}"; to="${pair##*:}"
  if [[ -d "$ROOT/$from" && "$from" != "$to" ]]; then
    ( cd "$ROOT" && git mv "$from" "$to" )
    echo "   git mv $from -> $to"
  fi
done
# 1b. sed passes (find-based so the renamed tree is covered)
while IFS='|' read -r kind glob expr; do
  [[ "$kind" == "SED" ]] || continue
  pat="${glob#**/}"   # we resolve globs ourselves per root below
  case "$glob" in
    **/*.scala) roots=("$ROOT/src" "$ROOT/project");;
    src/main/resources/web/**) roots=("$ROOT/src/main/resources/web");;
    *) roots=("$(dirname "$ROOT/$glob")"); pat="$(basename "$glob")";;
  esac
  for r in "${roots[@]}"; do
    [[ -e "$r" ]] || continue
    find "$r" -name "$pat" -type f 2>/dev/null | while read -r f; do
      if LC_ALL=C sed "${SED_I[@]}" "$expr" "$f" 2>/dev/null; then :; else
        die "sed failed on $f: $expr"
      fi
    done
  done
done < <(manifest | expand | grep '^SED|')

# 1c. window global: rename definition, append permanent alias
MAINJS="$ROOT/src/main/resources/web/js/main.js"
if [[ -f "$MAINJS" && "$PASCAL" != "$LEGACY_PASCAL" ]]; then
  sed "${SED_I[@]}" "s/^window\.$LEGACY_PASCAL = {/window.$PASCAL = {/" "$MAINJS"
  # alias only if not already present (idempotent re-runs)
  if ! grep -q "window\.$LEGACY_PASCAL = window\.$PASCAL" "$MAINJS"; then
    printf '\n// Rebrand compatibility (permanent): legacy global alias.\nwindow.%s = window.%s;\n' "$LEGACY_PASCAL" "$PASCAL" >> "$MAINJS"
  fi
  echo "   window.$PASCAL defined; window.$LEGACY_PASCAL alias appended"
fi

echo "[2/7] residual verification"
# Whitelist: the runtime compat layer (Branding/PathUtil) legitimately
# hardcodes legacy values; Protocol is the D2 wire contract; branding.js
# carries the frontend legacy-key migration; comments/i18n examples may
# mention the legacy path. Everything else must be gone.
resid="$(rg -l -i "$LEGACY_LOWER" --glob '*.scala' "$ROOT/src" 2>/dev/null | \
  grep -vE 'core/Branding\.scala|core/paths\.scala|neblink/Protocol\.scala' || true)"
if [[ -n "$resid" ]]; then
  echo "   WARN: Scala files still reference the legacy name (review each):"
  echo "$resid" | sed 's/^/     /'
fi

echo "[3/7] build (sbt clean assembly + test)"
if [[ $SKIP_TESTS -eq 0 ]]; then
  ( cd "$ROOT" && env SBT_OPTS="-Xmx3g" sbt -batch clean assembly test ) || die "sbt build/test failed"
else
  echo "   (--skip-tests)"
fi

echo "[4/7] smoke"
SMOKE_DIR="$(mktemp -d /tmp/rebrand-smoke.XXXXXX)"
trap 'rm -rf "$SMOKE_DIR"' EXIT
if [[ $SKIP_SMOKE -eq 0 ]]; then
  PORT=8199
  JAR="$(ls "$ROOT"/target/scala-*/${LOWER_NAME}-assembly-*.jar 2>/dev/null | head -1 || true)"
  [[ -n "$JAR" ]] || die "assembly jar not found for smoke"
  HOME_DIR_FX="$SMOKE_DIR/fixture-home"
  mkdir -p "$HOME_DIR_FX/$LEGACY_LOWER/sessions"
  echo "\"smoke-token-$RANDOM\"" > "$HOME_DIR_FX/$LEGACY_LOWER/auth.json"
  echo '{"llm":{"providers":{},"model":{"default":"","fallbacks":[]}}}' > "$HOME_DIR_FX/$LEGACY_LOWER/$LEGACY_LOWER.json"
  echo "smoke-client-id" > "$HOME_DIR_FX/$LEGACY_LOWER/client-id"
  ( cd "$ROOT" && NEBFLOW_HOME="$HOME_DIR_FX/$LEGACY_LOWER" nohup java --add-opens java.base/java.lang=ALL-UNNAMED -jar "$JAR" -s --port "$PORT" > "$SMOKE_DIR/boot.log" 2>&1 & echo $! > "$SMOKE_DIR/pid" )
  ok=""
  for i in $(seq 1 60); do
    curl -sf "http://localhost:$PORT/api/health" >/dev/null 2>&1 && { ok=1; break; }; sleep 1
  done
  [[ -n "$ok" ]] || { cat "$SMOKE_DIR/boot.log" | tail -20; kill "$(cat "$SMOKE_DIR/pid")" 2>/dev/null || true; die "smoke: server did not become healthy"; }
  BODY="$(curl -s "http://localhost:$PORT/")"
  FIRST15="$(printf '%s' "$BODY" | head -c 15)"
  [[ "$FIRST15" == "<!DOCTYPE html" ]] || { kill "$(cat "$SMOKE_DIR/pid")" 2>/dev/null || true; die "smoke: index is not raw HTML (encoder regression?)"; }
  printf '%s' "$BODY" | grep -q "\"productName\":\"$PRODUCT_NAME\"" || { kill "$(cat "$SMOKE_DIR/pid")" 2>/dev/null || true; die "smoke: brand contract not injected with the new productName"; }
  TOKEN="$(cat "$HOME_DIR_FX/$LEGACY_LOWER/auth.json" | tr -d '"')"
  CODE="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "http://localhost:$PORT/api/sessions")"
  kill "$(cat "$SMOKE_DIR/pid")" 2>/dev/null || true
  [[ "$CODE" == "200" ]] || die "smoke: legacy fixture token rejected ($CODE) — compat layer broken"
  echo "   boot OK / health OK / index bytes OK / brand injected / legacy fixture auth OK"
else
  echo "   (--skip-smoke)"
fi

echo "[5/7] package (dmg/msi)"
if [[ $SKIP_PACKAGING -eq 0 ]]; then
  ( cd "$ROOT" && bash packaging/build-dmg.sh ) || die "dmg packaging failed"
  if [[ "$(uname)" == "Darwin" ]]; then :; else echo "   (msi packaging runs on Windows — skipped here)"; fi
else
  echo "   (--skip-packaging)"
fi

echo "[6/7] render install scripts"
( cd "$ROOT" && bash scripts/render-brand.sh ) || die "render-brand.sh failed"
( cd "$ROOT" && bash scripts/render-brand.sh --check ) || die "render-brand.sh --check failed after render"

echo "[7/7] checklist"
CHECKLIST="$ROOT/REBRAND-CHECKLIST.md"
cat > "$CHECKLIST" <<EOF2
# Rebrand human checklist — generated by scripts/rebrand.sh

Product: $LEGACY_PASCAL -> $PRODUCT_NAME (lower: $LEGACY_LOWER -> $LOWER_NAME)
Domain: $DOMAIN — GitHub: $GH_ORG/$GH_REPO — COS bucket: $COS_BUCKET

## DNS / network
[ ] Aliyun DNS: nebflow.space 301 -> $DOMAIN; A/CNAME records for $DOMAIN (+ device subdomain)
[ ] VPS: Caddyfile gains the new-domain server block (keep the old domain for the grey period); docker compose up -d

## GitHub
[ ] Create org $GH_ORG; Settings -> Transfer repository (old URL auto-redirects)
[ ] OAuth App (Ov23liu3nC6jzBmNIQNB): new/updated callback URLs; Client Secret refreshed into the VPS .env

## COS (release mirror)
[ ] New bucket $COS_BUCKET provisioned; CI dual-write switch on
[ ] Old bucket (nebflow-releases-1411212853) retained >= 12 months; push a last-version file that points users at the new bucket

## Website + device server
[ ] Website deploy with new config; NEBLINK_SERVER_URL env injected
[ ] neblink-server: CORS origins (src/brand.rs + NEBLINK_CORS_ORIGINS grey period), .env domains, git remote to the new org

## Assets
[ ] New logo trio (favicon.svg / logo.svg / og.png) — human design work

## Announcement
[ ] README rename note; version announcement; old-bucket version-file push

Credentials red line: VPS credentials live in NEBLINK_HANDOVER.md (gitignored)
in the main repo — reference the path, never copy contents into code or docs.
EOF2
echo "   wrote $CHECKLIST"
echo
echo "== apply complete. Human steps remain — see REBRAND-CHECKLIST.md =="
