#!/bin/bash
# release.sh 发版流水线 — 纯 bash 断言测试 (无 bats 依赖, #844 同款风格)
# 被测对象: <repo>/ops/release.sh (工程化 M2: stage/approve/rollback/tag/status)
#
# 方法: 全沙箱 —— STAND_RELEASE_ROOT 指临时发布根, STAND_RELEASE_REPO 指 fixture
#   git 仓(真 git, 假产物), STAND_RELEASE_BUILD_CMD/STAND_RELEASE_SMOKE_CMD 指
#   假构建/假冒烟(记录调用+可控结论), STAND_RELEASE_JAVA_BIN 指假 java(可控
#   runtime version 输出)。零真实 JVM / 零真实 sbt / 零真实 boot-smoke;
#   真实链路(stage=真 assembly+真 smoke → approve → rollback → 坏 jar 拒)由
#   E2E 批单独立证, 见 reports/<ts>_release-pipeline-m2/。
#   (可选) RELEASE_TEST_REAL_SMOKE=1 追加一条「真 boot-smoke 吃截断 jar」用例。
#
# 用法: bash ops/test/release-pipeline.test.sh   (exit 0 = 全绿)
set -u

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC_RELEASE="$REPO_ROOT/ops/release.sh"
SRC_SMOKE="$REPO_ROOT/scripts/boot-smoke.sh"

PASS=0; FAIL=0
SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/release-pipeline.XXXXXX")"
cleanup() { rm -rf "$SANDBOX" 2>/dev/null; }
trap cleanup EXIT

V="2026.10.26-beta.1"        # fixture 版本
PREV="2026.9.19"             # 预置的在产版本(指针初值)
JAR_NAME="stand-assembly-$V.jar"

# ── 断言原语 ──────────────────────────────────────────────
t()    { echo "--- $*"; }
ok()   { PASS=$((PASS+1)); echo "  PASS: $*"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL: $*"; }
assert_rc()      { # <desc> <want> <got>
    if [ "$2" = "$3" ]; then ok "$1 (rc=$3)"; else bad "$1 (want rc=$2, got rc=$3)"; fi; }
assert_contains(){ case "$3" in *"$2"*) ok "$1" ;; *) bad "$1 — missing '$2' in: ${3:0:300}" ;; esac; }
assert_lacks()   { case "$3" in *"$2"*) bad "$1 — unexpectedly found '$2'" ;; *) ok "$1" ;; esac; }
assert_eq()      { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 — want '$2', got '$3'"; fi; }
assert_file()    { if [ -f "$2" ]; then ok "$1"; else bad "$1 — missing file: $2"; fi; }
assert_no_file() { if [ -e "$2" ]; then bad "$1 — unexpectedly exists: $2"; else ok "$1"; fi; }

# ── 沙箱脚手架 ────────────────────────────────────────────
fresh_sandbox() { # 每个 case 重建: fixture git 仓 + fake bin + 发布根(带预置指针)
    rm -rf "$SANDBOX"/*
    mkdir -p "$SANDBOX/fakebin" "$SANDBOX/root/versions" "$SANDBOX/fixture"

    # fixture git 仓: VERSION 文件 + 一次 commit (stage 记 commit / tag 打点用)
    ( cd "$SANDBOX/fixture" \
      && git init -q \
      && git config user.email release-test@example.invalid \
      && git config user.name release-test \
      && printf '%s\n' "$V" > VERSION \
      && git add VERSION \
      && git commit -qm "bump VERSION to $V" ) >/dev/null 2>&1

    # 预置在产版本: 指针指 PREV + 对应版本包在盘(rollback 的落点)
    printf '%s\n' "$PREV" > "$SANDBOX/root/current-version"
    mkdir -p "$SANDBOX/root/versions/$PREV"
    printf 'pretend-jar-%s\n' "$PREV" > "$SANDBOX/root/versions/$PREV/stand-assembly-$PREV.jar"

    # 假构建: 往 fixture target/ 落一个 jar(内容可控: good=真字节, truncate=截断)
    cat > "$SANDBOX/fakebin/fake-build.sh" <<'FAKEBUILD'
#!/bin/bash
# env: RELEASE_VERSION / RELEASE_REPO / FAKE_BUILD_MODE(good|truncate)
jar_dir="$RELEASE_REPO/target/scala-3.5.2"
mkdir -p "$jar_dir"
jar="$jar_dir/stand-assembly-$RELEASE_VERSION.jar"
if [ "${FAKE_BUILD_MODE:-good}" = "truncate" ]; then
    printf 'PK\x03\x04cor' > "$jar"      # 截断的 zip 头 — 真 jar 一读即炸
else
    printf 'fake-jar-bytes-for-%s\n' "$RELEASE_VERSION" > "$jar"
fi
exit 0
FAKEBUILD

    # 假冒烟: 结论可控(FAKE_SMOKE_RESULT=PASS|FAIL), 输出形同 boot-smoke 契约
    cat > "$SANDBOX/fakebin/fake-smoke.sh" <<'FAKESMOKE'
#!/bin/bash
# args: <jar> --port N --timeout S ; env: FAKE_SMOKE_RESULT
jar="$1"; shift
if [ "${FAKE_SMOKE_RESULT:-PASS}" = "PASS" ]; then
    echo "[smoke] jar=$jar (fake)"
    echo "BOOT-SMOKE: PASS jar=$(basename "$jar") port=9999 boot_ready=0s checks=111 (fake)"
    exit 0
else
    echo "[smoke] jar=$jar (fake)"
    echo "BOOT-SMOKE: FAIL stage=boot reason=injected-by-test"
    exit 1
fi
FAKESMOKE

    # 假 java: `-jar <jar> version` 输出 `stand <FAKE_JAR_VERSION>`; -version 走老路
    cat > "$SANDBOX/fakebin/fake-java" <<'FAKEJAVA'
#!/bin/bash
if [ "${1:-}" = "-version" ]; then
    echo "openjdk version \"21.0.5\" 2024-10-15"
    exit 0
fi
# 找 "version" 子命令: ... -jar <jar路径> version (version 在 jar 路径之后)
seen_jar=0
for a in "$@"; do
    if [ "$a" = "-jar" ]; then seen_jar=1; continue; fi
    if [ "$seen_jar" = "1" ] && [ "$a" = "version" ]; then
        echo "stand ${FAKE_JAR_VERSION:-MATCH}"
        exit 0
    fi
done
exit 3
FAKEJAVA

    chmod +x "$SANDBOX/fakebin/"*
}

# release.sh 调用面: 全部 --root/--repo 钉沙箱 + 三个 env 接缝
rel() { # $@: release.sh 参数; rc -> $?, 输出 -> RUN_OUT
    RUN_OUT="$( \
        STAND_RELEASE_BUILD_CMD="${RELEASE_TEST_BUILD_CMD:-$SANDBOX/fakebin/fake-build.sh}" \
        STAND_RELEASE_SMOKE_CMD="${RELEASE_TEST_SMOKE_CMD:-$SANDBOX/fakebin/fake-smoke.sh}" \
        STAND_RELEASE_JAVA_BIN="$SANDBOX/fakebin/fake-java" \
        FAKE_SMOKE_RESULT="${FAKE_SMOKE_RESULT:-PASS}" \
        FAKE_JAR_VERSION="${FAKE_JAR_VERSION:-$V}" \
        bash "$SRC_RELEASE" "$@" --root "$ROOT" --repo "$FIXTURE" 2>&1)"
    return $?
}
ROOT=""; FIXTURE=""

pointer_val() { tr -d ' \t\r\n' < "$ROOT/current-version" 2>/dev/null; }
json_get()    { sed -n "s/^  \"$2\": \"\(.*\)\",\{0,1\}\$/\1/p" "$1" 2>/dev/null | head -n 1; }

# ══════════════════════════════════════════════════════════
echo "== release.sh pipeline tests =="
echo "   repo: $REPO_ROOT"
echo "   sandbox: $SANDBOX"

# ── 0. 静态: 语法 + shellcheck ──
t "case 0: bash -n / shellcheck"
if bash -n "$SRC_RELEASE" 2>"$SANDBOX/syn.err"; then ok "syntax: release.sh"; else bad "syntax: $(cat "$SANDBOX/syn.err")"; fi
if [ -f "$SRC_SMOKE" ]; then ok "boot-smoke.sh present (M1 contract dependency)"; else bad "scripts/boot-smoke.sh missing"; fi
if command -v shellcheck >/dev/null 2>&1; then
    if shellcheck -S warning "$SRC_RELEASE" 2>/dev/null; then ok "shellcheck: release.sh"; else bad "shellcheck: release.sh"; fi
else
    echo "  (shellcheck not installed — skipped)"
fi

# ── 1. stage happy path: 装 versions/<v> + 候批卡 + 指针不动 ──
t "case 1: stage PASS -> versions/<v> installed, staged-release.json written, pointer untouched"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel stage; RC=$?
assert_rc "stage rc" 0 "$RC"
assert_contains "CANDIDATE-READY emitted" "CANDIDATE-READY: version=$V" "$RUN_OUT"
assert_contains "RELEASE-STAGE: OK emitted" "RELEASE-STAGE: OK version=$V" "$RUN_OUT"
assert_file "jar installed" "$ROOT/versions/$V/$JAR_NAME"
assert_file "staged-release.json written" "$ROOT/staged-release.json"
assert_eq "json.version" "$V" "$(json_get "$ROOT/staged-release.json" version)"
assert_contains "json.smokeEvidence is a PASS line" "BOOT-SMOKE: PASS" "$(json_get "$ROOT/staged-release.json" smokeEvidence)"
assert_eq "json.previous = pre-stage pointer" "$PREV" "$(json_get "$ROOT/staged-release.json" previous)"
assert_contains "json.commit is a sha" "$(cd "$FIXTURE" && git rev-parse --short HEAD)" "$(json_get "$ROOT/staged-release.json" commitShort)"
assert_eq "pointer untouched by stage" "$PREV" "$(pointer_val)"
assert_lacks "no rollback line at stage" "rollback" "$RUN_OUT"

# ── 2. stage 冒烟 FAIL → 零候选 ──
t "case 2: stage smoke FAIL -> rc!=0, ZERO candidates (no versions/<v>, no staged json)"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
FAKE_SMOKE_RESULT=FAIL rel stage; RC=$?
assert_rc "stage smoke-fail rc" 1 "$RC"
assert_contains "RELEASE-STAGE: FAIL emitted" "RELEASE-STAGE: FAIL stage=smoke" "$RUN_OUT"
assert_no_file "no versions/<v> dir" "$ROOT/versions/$V"
assert_no_file "no staged-release.json" "$ROOT/staged-release.json"
assert_eq "pointer untouched" "$PREV" "$(pointer_val)"

# ── 3. stage 版本包不可变: versions/<v> 已存在即拒 ──
t "case 3: stage refuses existing versions/<v> (immutable packages)"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
mkdir -p "$ROOT/versions/$V"
rel stage; RC=$?
assert_rc "immutable refuse rc" 1 "$RC"
assert_contains "immutable message" "already exists" "$RUN_OUT"
assert_lacks "no build attempted" "building:" "$RUN_OUT"

# ── 4. stage 预检漂移不阻塞(dirty worktree) 但如实上卡 ──
t "case 4: preflight drift (dirty) is NON-blocking and reported"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
touch "$FIXTURE/untracked-stray.txt"
rel stage; RC=$?
assert_rc "dirty stage still passes" 0 "$RC"
assert_contains "dirty reported" "dirty_files=1" "$RUN_OUT"
assert_contains "drift on record" "preflight drift" "$RUN_OUT"

# ── 5. approve happy: 探针过 → 指针切 + previous-version + 回退指令 ──
t "case 5: approve PASS -> pointer switched, previous recorded, rollback line printed"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel stage >/dev/null 2>&1
rel approve; RC=$?
assert_rc "approve rc" 0 "$RC"
assert_contains "RELEASE-APPROVE: OK" "RELEASE-APPROVE: OK version=$V" "$RUN_OUT"
assert_eq "pointer switched to staged version" "$V" "$(pointer_val)"
assert_eq "previous-version records old pointer" "$PREV" "$(tr -d ' \t\r\n' < "$ROOT/previous-version")"
assert_contains "rollback instruction present" "rollback" "$RUN_OUT"
assert_eq "json.approved=true" "true" "$(grep -q '"approved": true' "$ROOT/staged-release.json" && echo true || echo false)"
assert_contains "approve re-probed the staged jar" "promotion probe" "$RUN_OUT"

# ── 6. approve 探针 FAIL → 拒晋级, 指针不动 ──
t "case 6: approve probe FAIL -> REJECT, pointer untouched"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel stage >/dev/null 2>&1
FAKE_SMOKE_RESULT=FAIL rel approve; RC=$?
assert_rc "approve reject rc" 1 "$RC"
assert_contains "REJECT named" "RELEASE-APPROVE: REJECT" "$RUN_OUT"
assert_eq "pointer NOT switched" "$PREV" "$(pointer_val)"
assert_lacks "no previous-version file written" "previous-version" "$(ls "$ROOT" 2>/dev/null)"

# ── 7. approve 无 staging → 具名拒绝 ──
t "case 7: approve without staging -> named reject"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel approve; RC=$?
assert_rc "approve no-staging rc" 1 "$RC"
assert_contains "no staging reason" "no staged-release.json" "$RUN_OUT"

# ── 8. approve 完整性: staged jar 被换/截断 → sha256 对账拒 ──
t "case 8: approve rejects tampered staged jar (sha256 mismatch), pointer untouched"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel stage >/dev/null 2>&1
printf 'tampered' > "$ROOT/versions/$V/$JAR_NAME"
rel approve; RC=$?
assert_rc "approve tamper rc" 1 "$RC"
assert_contains "sha mismatch reason" "sha256 mismatch" "$RUN_OUT"
assert_eq "pointer NOT switched" "$PREV" "$(pointer_val)"

# ── 9. approve 幂等: 二次 approve = no-op ──
t "case 9: approve twice -> second is a no-op rc=0"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel stage >/dev/null 2>&1
rel approve >/dev/null 2>&1
rel approve; RC=$?
assert_rc "second approve rc" 0 "$RC"
assert_contains "no-op message" "already approved and current" "$RUN_OUT"
assert_eq "pointer still staged version" "$V" "$(pointer_val)"

# ── 10. rollback: 指针还原 + 幂等 ──
t "case 10: rollback -> pointer restored; second rollback idempotent"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel stage >/dev/null 2>&1
rel approve >/dev/null 2>&1
rel rollback; RC=$?
assert_rc "rollback rc" 0 "$RC"
assert_contains "RELEASE-ROLLBACK: OK" "RELEASE-ROLLBACK: OK current=$PREV" "$RUN_OUT"
assert_eq "pointer back to previous" "$PREV" "$(pointer_val)"
rel rollback; RC=$?
assert_rc "second rollback rc (idempotent)" 0 "$RC"
assert_contains "idempotent message" "already at previous" "$RUN_OUT"
assert_eq "pointer stays previous" "$PREV" "$(pointer_val)"

# ── 11. rollback 无 previous 记录 → 具名失败 ──
t "case 11: rollback with no previous record -> named fail"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rm -f "$ROOT/current-version" "$ROOT/previous-version"
rel rollback; RC=$?
assert_rc "rollback no-record rc" 1 "$RC"
assert_contains "no previous reason" "no previous version on record" "$RUN_OUT"

# ── 12. tag 纪律: approve 前拒打 ──
t "case 12: tag before approve -> refused"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel stage >/dev/null 2>&1
rel tag; RC=$?
assert_rc "tag pre-approve rc" 1 "$RC"
assert_contains "not approved reason" "not approved" "$RUN_OUT"
assert_eq "no tag created" "" "$(cd "$FIXTURE" && git tag -l | tr -d '\n')"

# ── 13. tag happy: approve 后, 三方对账过 → 打在 approved commit ──
t "case 13: tag after approve -> reconciled, annotated tag on approved commit"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel stage >/dev/null 2>&1
rel approve >/dev/null 2>&1
rel tag; RC=$?
assert_rc "tag rc" 0 "$RC"
assert_contains "RELEASE-TAG: OK" "RELEASE-TAG: OK tag=v$V" "$RUN_OUT"
assert_contains "reconciliation line" "reconciled" "$RUN_OUT"
assert_contains "not pushed declared" "NOT pushed" "$RUN_OUT"
assert_eq "tag exists at approved commit" "$(json_get "$ROOT/staged-release.json" commit)" \
    "$(cd "$FIXTURE" && git rev-parse "v$V^{commit}")"
assert_eq "tag is annotated" "tag" "$(cd "$FIXTURE" && git for-each-ref --format='%(objecttype)' "refs/tags/v$V")"

# ── 14. tag 对账 ②: jar runtime version 不匹配 → 拒 ──
t "case 14: tag rejects jar runtime version mismatch (dead-tag class)"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel stage >/dev/null 2>&1
rel approve >/dev/null 2>&1
FAKE_JAR_VERSION="2026.10.21-beta.2" rel tag; RC=$?
assert_rc "tag runtime-mismatch rc" 1 "$RC"
assert_contains "runtime mismatch reason" "runtime version reads" "$RUN_OUT"
assert_eq "no tag created" "" "$(cd "$FIXTURE" && git tag -l | tr -d '\n')"

# ── 15. tag 对账 ①: approved commit 上的 VERSION 与 staged 版本脱钩 → 拒 ──
t "case 15: tag rejects VERSION@commit mismatch (10-24 dead-tag checkpoint)"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel stage >/dev/null 2>&1
rel approve >/dev/null 2>&1
# 篡改账本版本(模拟账本与 commit 脱钩): version 改成别的
sed 's/^  "version": ".*"/  "version": "2026.10.27-beta.9"/' "$ROOT/staged-release.json" \
    > "$ROOT/staged-release.json.t" && mv -f "$ROOT/staged-release.json.t" "$ROOT/staged-release.json"
rel tag; RC=$?
assert_rc "tag VERSION@commit mismatch rc" 1 "$RC"
assert_contains "dead-tag reason" "VERSION at approved commit" "$RUN_OUT"
assert_eq "no tag created" "" "$(cd "$FIXTURE" && git tag -l | tr -d '\n')"

# ── 16. tag 重名 → 拒(tag 不可变) ──
t "case 16: tag refuses existing tag (immutable)"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel stage >/dev/null 2>&1
rel approve >/dev/null 2>&1
( cd "$FIXTURE" && git tag -a "v$V" -m preexisting HEAD ) >/dev/null 2>&1
rel tag; RC=$?
assert_rc "tag exists rc" 1 "$RC"
assert_contains "already exists reason" "already exists" "$RUN_OUT"

# ── 17. tag 自定义名: --tag 与 staged 版本不一致 → 拒; 一致 → 过 ──
t "case 17: --tag override must match staged version"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel stage >/dev/null 2>&1
rel approve >/dev/null 2>&1
rel tag --tag v9.9.9; RC=$?
assert_rc "tag name mismatch rc" 1 "$RC"
assert_contains "implies version reason" "implies version" "$RUN_OUT"
rel tag --tag "v$V"; RC=$?
assert_rc "explicit matching tag rc" 0 "$RC"

# ── 18. 端口铁律: 6138 / 8080 在入口拒, 不进构建 ──
t "case 18: iron-rule ports refused at entry (6138, 8080)"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel stage --smoke-port 6138; RC=$?
assert_rc "6138 refused rc" 1 "$RC"
assert_contains "6138 named" "production port" "$RUN_OUT"
rel stage --smoke-port 8080; RC=$?
assert_rc "8080 refused rc" 1 "$RC"
assert_contains "8080 named" "gateway port" "$RUN_OUT"
assert_lacks "no build ran" "building:" "$RUN_OUT"

# ── 19. status 只读面 ──
t "case 19: status reports pointer + staged state, rc=0"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel status; RC=$?
assert_rc "status rc (empty staging)" 0 "$RC"
assert_contains "pointer reported" "current-version: $PREV" "$RUN_OUT"
rel stage >/dev/null 2>&1
rel approve >/dev/null 2>&1
rel status; RC=$?
assert_rc "status rc" 0 "$RC"
assert_contains "staged version shown" "staged: version=$V" "$RUN_OUT"
assert_contains "approved flag shown" "approved=1" "$RUN_OUT"

# ── 20. 未知子命令/未知参数 → 具名 rc=1 ──
t "case 20: unknown subcommand / option -> named error rc=1"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
rel frobnicate; RC=$?
assert_rc "unknown subcommand rc" 1 "$RC"
assert_contains "unknown subcommand named" "unknown subcommand" "$RUN_OUT"
rel stage --no-such-flag; RC=$?
assert_rc "unknown option rc" 1 "$RC"
assert_contains "unknown option named" "unknown option" "$RUN_OUT"

# ── 21. (可选) 真 boot-smoke 吃截断 jar → FAIL 零候选 ──
t "case 21 (opt-in RELEASE_TEST_REAL_SMOKE=1): REAL boot-smoke rejects truncated jar at stage"
if [ "${RELEASE_TEST_REAL_SMOKE:-0}" = "1" ] && command -v java >/dev/null 2>&1; then
    fresh_sandbox
    ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
    REALP=$((21000 + RANDOM % 5000))
    RELEASE_TEST_SMOKE_CMD="$SRC_SMOKE" FAKE_BUILD_MODE=truncate \
        rel stage --smoke-port "$REALP" --smoke-timeout 20; RC=$?
    assert_rc "truncated jar stage rc" 1 "$RC"
    assert_contains "boot-smoke FAIL surfaced" "RELEASE-STAGE: FAIL stage=smoke" "$RUN_OUT"
    assert_no_file "zero candidates" "$ROOT/versions/$V"
else
    echo "  (skipped — set RELEASE_TEST_REAL_SMOKE=1 to include; the E2E batch covers it for real)"
fi

# ── 22. 路径含空格健壮性: 冒烟命令是带空格的单一可执行路径 → 直接 exec 不切词 ──
t "case 22: smoke cmd path with spaces (this repo's checkout path has one) -> quoted exec"
fresh_sandbox
ROOT="$SANDBOX/root"; FIXTURE="$SANDBOX/fixture"
mkdir -p "$SANDBOX/smoke dir"
cp "$SANDBOX/fakebin/fake-smoke.sh" "$SANDBOX/smoke dir/fake-smoke.sh"
RELEASE_TEST_SMOKE_CMD="$SANDBOX/smoke dir/fake-smoke.sh" rel stage; RC=$?
assert_rc "space-path stage rc" 0 "$RC"
assert_contains "space-path smoke ran (PASS verdict)" "BOOT-SMOKE: PASS" "$RUN_OUT"
assert_file "candidate installed" "$ROOT/versions/$V/$JAR_NAME"

echo
echo "== summary: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" -eq 0 ]
