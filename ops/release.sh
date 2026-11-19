#!/usr/bin/env bash
# release.sh — 发版流水线（工程化 M2，作者令 2026-10-25「工程实践按节奏实施」②）
#
# 一条命令流水线 + 显式批准门。对齐发布纪律红线：staged 自动化、晋级必须作者
# 逐次显式批准（approve 是唯一换手入口）；本脚本全程零信号 —— 绝不触碰在产
# 进程 / 6138 端口，指针切换只写文件，重启交给 bin/stand（already-running 守卫）。
#
# 子命令:
#   stage     预检(main 干净/与 origin 齐平 — 不阻塞但列漂移) → sbt assembly →
#             boot-smoke 吃 exit code(FAIL 即中止, 零候选产出) → 装 versions/<v>/ +
#             写 staged-release.json(版本/commit/jar/ smoke 证据行/时间戳) → 候批摘要
#   approve   作者逐次批准唯一入口: 验 staged + smoke 证据在案 → 对 staged jar 再跑
#             一次 boot-smoke 探针(FAIL = 拒晋级, 指针不动, 具名报错) → 原子切
#             current-version + previous-version → 打印一行回退指令
#   rollback  指针回退上一版(staged-release.json 记 previous; 幂等; 不跑 smoke —
#             回退是回到已知好版本, 快路径)
#   tag       只在 approve 之后允许; 三方对账(tag 名 == VERSION 文件@approved commit
#             == jar 内 runtime version, `java -jar <jar> version`), 不匹配即拒;
#             打在 approved commit 上; 永不 push(push 永远显式)
#   status    只读: 指针 / previous / staged 状态
#
# 布局(默认 = 生产 layout, 与 ops/bin/stand 的 POINTER-SELECT 同源):
#   <root>/current-version            指针(bin/stand 读取)
#   <root>/previous-version           上一版(换手前指针值)
#   <root>/versions/<v>/stand-assembly-<v>.jar   不可变版本包
#   <root>/staged-release.json        当前 staging 记录(候批卡 + approve/回退账本)
#
# 可测接缝(env, 全部可选; 测试沙箱用, 生产默认值见括号):
#   STAND_RELEASE_ROOT/--root     发布根(默认 $HOME/.stand/bin)
#   STAND_RELEASE_REPO/--repo     构建/tag 所在仓(默认本脚本所在仓)
#   STAND_RELEASE_BUILD_CMD       构建命令片段, 以 repo 为 cwd 执行, env 带
#                                 RELEASE_VERSION/RELEASE_REPO(默认 "sbt assembly")
#   STAND_RELEASE_SMOKE_CMD       冒烟命令: 单一可执行路径(可含空格)直接调用;
#                                 含空格且非文件则按 shell 片段语义。追加
#                                 <jar> --port N --timeout S 三个参数
#                                 (默认 = 本脚本所在仓的 scripts/boot-smoke.sh,
#                                 与 --repo 解耦)
#   STAND_RELEASE_JAVA_BIN        tag 对账用 java(默认 pinned jdk-21, 缺失降级
#                                 PATH java 但 WARN 不静默 — 作者令 2026-09-21)
#
# bash-3.2 兼容(macOS stock bash)。依赖: git, shasum(或 sha256sum);
# stage 另需构建/冒烟两命令各自的依赖。零 wall-clock 超时判死: 唯一的等待类
# 操作(boot-smoke)内部是状态探测 + 预算钟(M1 契约), 本脚本不另设睡等循环。
set -u

PROGNAME="$(basename "$0")"
PROGDIR="$(cd "$(dirname "$0")" && pwd)"
DEFAULT_REPO="$(cd "$PROGDIR/.." && pwd)"
PROD_PORT=6138        # 铁律: 在产实例端口
HOST_GW_PORT=8080     # 铁律: 宿主 gateway 端口
PINNED_JDK="$HOME/.stand/jdk-21/bin/java"

CMD=""
RELEASE_ROOT="${STAND_RELEASE_ROOT:-$HOME/.stand/bin}"
REPO="${STAND_RELEASE_REPO:-$DEFAULT_REPO}"
BUILD_CMD="${STAND_RELEASE_BUILD_CMD:-sbt assembly}"
SMOKE_CMD="${STAND_RELEASE_SMOKE_CMD:-}"
SMOKE_PORT=8089
SMOKE_TIMEOUT=120
TAG_NAME=""

say()   { printf '[release] %s\n' "$*"; }
warn()  { printf '[release] WARN: %s\n' "$*" >&2; }
die()   { printf '[release] ERROR: %s\n' "$*" >&2; exit 1; }
verdict() { printf 'RELEASE-%s: %s\n' "$1" "$2"; }

now_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# ── 沙箱口/port 铁律(与 boot-smoke 同判据, 提前在入口拒, 不浪费一次构建) ─────
check_smoke_port() {
  case "$SMOKE_PORT" in ''|*[!0-9]*) die "smoke port not a number: $SMOKE_PORT" ;; esac
  [ "$SMOKE_PORT" -ge 1024 ] || die "refusing privileged smoke port $SMOKE_PORT (sandbox must use a high port)"
  [ "$SMOKE_PORT" = "$PROD_PORT" ] && die "refusing smoke port $SMOKE_PORT = production port (iron rule: never touch :6138)"
  [ "$SMOKE_PORT" = "$HOST_GW_PORT" ] && die "refusing smoke port $SMOKE_PORT = host gateway port"
  return 0
}

# ── 原子写(同目录 tmp + mv rename) ──────────────────────────────────────────
atomic_write() { # <dest> <content(单行, 含换行)>
  local dest="$1" tmp
  tmp="$dest.tmp.$$"
  printf '%s\n' "$2" > "$tmp" || { rm -f "$tmp"; die "cannot write $tmp"; }
  mv -f "$tmp" "$dest" || { rm -f "$tmp"; die "atomic rename failed for $dest"; }
}

# ── staged-release.json: 手写解析(零 jq 依赖), 写侧固定两空格缩进 ────────────
json_get() { # <file> <key> -> value(空串 = 无)
  sed -n "s/^  \"$2\": \"\(.*\)\",\{0,1\}\$/\1/p" "$1" 2>/dev/null | head -n 1
}
json_is_true() { # <file> <key> -> rc 0/1
  grep -q "^  \"$2\": true," "$1" 2>/dev/null
}
json_write() { # <file> — 字段取全局 JSON_* 变量
  local f="$1" tmp
  tmp="$f.tmp.$$"
  {
    printf '{\n'
    printf '  "version": "%s",\n'        "$JSON_VERSION"
    printf '  "commit": "%s",\n'          "$JSON_COMMIT"
    printf '  "commitShort": "%s",\n'     "$JSON_COMMIT_SHORT"
    printf '  "branch": "%s",\n'          "$JSON_BRANCH"
    printf '  "jar": "%s",\n'             "$JSON_JAR"
    printf '  "jarSha256": "%s",\n'       "$JSON_JAR_SHA"
    printf '  "smokeEvidence": "%s",\n'   "$JSON_SMOKE_EVIDENCE"
    printf '  "smokePort": "%s",\n'       "$JSON_SMOKE_PORT"
    printf '  "smokedAt": "%s",\n'        "$JSON_SMOKED_AT"
    printf '  "stagedAt": "%s",\n'        "$JSON_STAGED_AT"
    printf '  "previous": "%s",\n'        "$JSON_PREVIOUS"
    printf '  "approved": %s,\n'          "$JSON_APPROVED"
    printf '  "approvedAt": "%s",\n'      "$JSON_APPROVED_AT"
    printf '  "rolledBackAt": "%s"\n'     "$JSON_ROLLED_BACK_AT"
    printf '}\n'
  } > "$tmp" || { rm -f "$tmp"; die "cannot write $tmp"; }
  mv -f "$tmp" "$f" || { rm -f "$tmp"; die "atomic rename failed for $f"; }
}
json_sanitize() { # 值内禁 " 与 \(JSON 注入面: 证据行来自固定格式输出, 纯防御)
  printf '%s' "$1" | tr -d '"' | tr -d '\\'
}

# ── 指针 ────────────────────────────────────────────────────────────────────
pointer_file()     { printf '%s/current-version' "$RELEASE_ROOT"; }
previous_file()    { printf '%s/previous-version' "$RELEASE_ROOT"; }
staged_json_file() { printf '%s/staged-release.json' "$RELEASE_ROOT"; }
read_pointer() {
  local f; f="$(pointer_file)"
  [ -f "$f" ] || { printf ''; return 0; }
  tr -d ' \t\r\n' < "$f"
}

# ── sha256 (macOS/Linux 双面) ───────────────────────────────────────────────
sha256_of() {
  if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else die "no sha256 tool (need shasum or sha256sum)"; fi
}

# ── 版本口径(与 packaging/app-version.sh:36 同一契约 + -dev 尾允许) ─────────
version_is_valid() { # <v> -> rc 0/1
  local v="$1" re
  re='^[0-9]{4}\.[0-9]{1,2}\.[0-9]{1,2}(\.[0-9]+)?(-beta\.[0-9]+)?(-dev\.[0-9a-zA-Z]+)?$'
  [[ "$v" =~ $re ]] && return 0
  re='^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?(-beta\.[0-9]+)?(-dev\.[0-9a-zA-Z]+)?$'
  [[ "$v" =~ $re ]] && return 0
  return 1
}

# ── 冒烟(唯一等待面; 状态探测在 boot-smoke 内部) ─────────────────────────────
run_smoke() { # <jar> -> stdout/stderr 直通; rc 透传; 全文已存 SMOKE_LOG
  local jar="$1"
  SMOKE_LOG="$(mktemp "${TMPDIR:-/tmp}/release-smoke.XXXXXX")"
  local port_args=("--port" "$SMOKE_PORT" "--timeout" "$SMOKE_TIMEOUT")
  if [ -n "${SMOKE_CMD:-}" ]; then
    case "$SMOKE_CMD" in
      # 单一可执行路径(可含空格 — 本仓 checkout 路径就带空格): 直接 exec, 不经
      # shell 再切词; 其余含空格形态按 shell 片段语义(操作者自负责引号)
      *[[:space:]]*)
        if [ -f "$SMOKE_CMD" ] && [ -x "$SMOKE_CMD" ]; then
          "$SMOKE_CMD" "$jar" "${port_args[@]}" 2>&1 | tee "$SMOKE_LOG"; return "${PIPESTATUS[0]}"
        fi
        sh -c "$SMOKE_CMD \"\$@\"" sh "$jar" "${port_args[@]}" 2>&1 | tee "$SMOKE_LOG"
        return "${PIPESTATUS[0]}" ;;
      *) "$SMOKE_CMD" "$jar" "${port_args[@]}" 2>&1 | tee "$SMOKE_LOG"; return "${PIPESTATUS[0]}" ;;
    esac
  fi
  # 默认冒烟 = 本脚本所在仓的 boot-smoke(与 --repo 解耦: fixture/丢手仓无 scripts/)
  "$PROGDIR/../scripts/boot-smoke.sh" "$jar" "${port_args[@]}" 2>&1 | tee "$SMOKE_LOG"
  return "${PIPESTATUS[0]}"
}
smoke_evidence_line() { # 从 SMOKE_LOG 抓单行结论(取最后一条, FAIL 覆盖 PASS 不会被截)
  grep '^BOOT-SMOKE:' "$SMOKE_LOG" 2>/dev/null | tail -n 1
}

# ── java 选择(tag 对账面; 非 silently 降级 — 作者令 2026-09-21) ──────────────
pick_java() {
  if [ -n "${STAND_RELEASE_JAVA_BIN:-}" ]; then printf '%s' "$STAND_RELEASE_JAVA_BIN"; return 0; fi
  if [ -x "$PINNED_JDK" ]; then printf '%s' "$PINNED_JDK"; return 0; fi
  warn "pinned JDK missing at $PINNED_JDK — falling back to PATH java (non-silent degrade)"
  command -v java 2>/dev/null
}

# ── jar runtime 版本(`java -jar <jar> version` → 首行末 token) ───────────────
jar_runtime_version() { # <jar> <java_bin> -> 版本串(空 = 读不出; 形如 `stand <v>` 取末 token)
  local out
  out="$("$2" -jar "$1" version 2>/dev/null | head -n 1)"
  out="$(printf '%s' "$out" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
  case "$out" in
    ''|' '*|*' '*) printf '%s' "${out##* }" ;;
    *) printf '%s' "$out" ;;
  esac
}

# ── 载入 staged-release.json 至全局(共用面) ──────────────────────────────────
load_staged() { # -> rc 1 = 无 staging
  local f; f="$(staged_json_file)"
  [ -f "$f" ] || return 1
  STAGED_FILE="$f"
  STAGED_VERSION="$(json_get "$f" version)"
  STAGED_COMMIT="$(json_get "$f" commit)"
  STAGED_COMMIT_SHORT="$(json_get "$f" commitShort)"
  STAGED_JAR="$(json_get "$f" jar)"
  STAGED_JAR_SHA="$(json_get "$f" jarSha256)"
  STAGED_SMOKE="$(json_get "$f" smokeEvidence)"
  STAGED_PREVIOUS="$(json_get "$f" previous)"
  STAGED_APPROVED_AT="$(json_get "$f" approvedAt)"
  STAGED_APPROVED=0; json_is_true "$f" approved && STAGED_APPROVED=1
  [ -n "$STAGED_VERSION" ] || return 1
  return 0
}

# ════════════════════════════════════════════════════════════════════════════
# stage
# ════════════════════════════════════════════════════════════════════════════
cmd_stage() {
  local version jar dest vdir pre_pointer t0 t1 rc evidence sha drift_lines build_log

  [ -f "$REPO/VERSION" ] || die "repo has no VERSION file: $REPO"
  version="$(tr -d ' \t\r\n' < "$REPO/VERSION")"
  [ -n "$version" ] || die "VERSION file is empty in $REPO"
  version_is_valid "$version" || die "VERSION '$version' does not parse (date/semver core ± beta ± dev tail)"

  mkdir -p "$RELEASE_ROOT/versions" || die "cannot create $RELEASE_ROOT/versions"

  # 版本包不可变: versions/<v> 已存在即拒(含 = 在产版本的情形), 要发请 bump VERSION
  vdir="$RELEASE_ROOT/versions/$version"
  if [ -e "$vdir" ]; then
    verdict STAGE "FAIL stage=preflight reason=versions/$version already exists (version packages are immutable — bump VERSION first)"
    exit 1
  fi

  say "stage: version=$version repo=$REPO root=$RELEASE_ROOT"

  # ── 预检: main 干净 + 与 origin 齐平 —— 不阻塞, 不齐就明说 ──
  drift_lines=""
  local branch dirty ahead behind
  branch="$(git -C "$REPO" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"
  dirty="$(git -C "$REPO" status --porcelain 2>/dev/null | grep -c .)"
  say "preflight: branch=$branch dirty_files=${dirty:-0}"
  if [ "${dirty:-0}" -gt 0 ]; then
    drift_lines="worktree not clean ($dirty files)"
    git -C "$REPO" status --porcelain 2>/dev/null | head -n 5 | sed 's/^/[release]   dirty: /'
    [ "$dirty" -gt 5 ] && say "  … and $((dirty - 5)) more"
  fi
  if git -C "$REPO" rev-parse -q --verify refs/remotes/origin/main >/dev/null 2>&1; then
    ahead="$(git -C "$REPO" rev-list --count origin/main..HEAD 2>/dev/null || echo '?')"
    behind="$(git -C "$REPO" rev-list --count HEAD..origin/main 2>/dev/null || echo '?')"
    say "preflight: vs origin/main (as of last fetch): ahead=$ahead behind=$behind"
    if [ "$ahead" != "0" ] || [ "$behind" != "0" ]; then
      drift_lines="$drift_lines main vs origin/main ahead=$ahead behind=$behind"
    fi
  else
    say "preflight: no origin/main ref known (no fetch performed — run 'git fetch' to refresh; not blocking)"
    drift_lines="$drift_lines origin/main unknown (stale refs?)"
  fi
  [ -n "$(printf '%s' "$drift_lines" | tr -d ' ')" ] && say "preflight drift (NON-blocking, reported for the candidate card): $drift_lines"

  # ── 构建(sbt assembly; 产物约定 target/scala-*/stand-assembly-<v>.jar) ──
  t0="$(date +%s)"
  say "building: ($BUILD_CMD) — cwd=$REPO"
  build_log="$(mktemp "${TMPDIR:-/tmp}/release-build.XXXXXX")"
  ( cd "$REPO" && RELEASE_VERSION="$version" RELEASE_REPO="$REPO" \
      sh -c "$BUILD_CMD" ) > "$build_log" 2>&1
  rc=$?
  t1="$(date +%s)"
  if [ "$rc" -ne 0 ]; then
    tail -n 20 "$build_log" 2>/dev/null | sed 's/^/[build] /'
    verdict STAGE "FAIL stage=build reason=build command exited $rc (version=$version)"
    exit 1
  fi
  jar="$(ls -1t "$REPO"/target/scala-*/stand-assembly-"$version".jar 2>/dev/null | head -n 1)"
  if [ -z "$jar" ] || [ ! -f "$jar" ]; then
    verdict STAGE "FAIL stage=build reason=build succeeded but no target/scala-*/stand-assembly-$version.jar produced"
    exit 1
  fi
  say "build ok in $((t1 - t0))s: $jar ($(du -h "$jar" | cut -f1))"

  # ── boot-smoke 闸(吃 exit code; FAIL 即中止, 零候选产出) ──
  say "smoke gate: port=$SMOKE_PORT timeout=${SMOKE_TIMEOUT}s"
  run_smoke "$jar"
  rc=$?
  evidence="$(smoke_evidence_line)"
  if [ "$rc" -ne 0 ] || [ -z "$evidence" ] || [ "${evidence#BOOT-SMOKE: PASS}" = "$evidence" ]; then
    [ -n "$evidence" ] && say "smoke verdict: $evidence"
    verdict STAGE "FAIL stage=smoke reason=boot-smoke did not pass (rc=$rc) — zero candidates produced"
    exit 1
  fi
  say "smoke PASS: $evidence"

  # ── 装版本包 + 候批卡(smoke 过了才动 root) ──
  mkdir -p "$vdir" || die "cannot create $vdir"
  dest="$vdir/stand-assembly-$version.jar"
  cp "$jar" "$dest" || die "cannot install jar into $vdir"
  sha="$(sha256_of "$dest")"
  pre_pointer="$(read_pointer)"

  JSON_VERSION="$version"
  JSON_COMMIT="$(git -C "$REPO" rev-parse HEAD 2>/dev/null || echo unknown)"
  JSON_COMMIT_SHORT="$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  JSON_BRANCH="$branch"
  JSON_JAR="$dest"
  JSON_JAR_SHA="$sha"
  JSON_SMOKE_EVIDENCE="$(json_sanitize "$evidence")"
  JSON_SMOKE_PORT="$SMOKE_PORT"
  JSON_SMOKED_AT="$(now_iso)"
  JSON_STAGED_AT="$(now_iso)"
  JSON_PREVIOUS="$pre_pointer"
  JSON_APPROVED="false"
  JSON_APPROVED_AT=""
  JSON_ROLLED_BACK_AT=""
  json_write "$(staged_json_file)"

  say "installed: $dest"
  say "pointer untouched: current-version=$( [ -n "$pre_pointer" ] && printf '%s' "$pre_pointer" || printf '(none)') (approval switches it, not stage)"
  verdict STAGE "OK version=$version commit=$JSON_COMMIT_SHORT jar=$dest sha256=$sha"
  printf 'CANDIDATE-READY: version=%s commit=%s jar=%s sha256=%s\n' "$version" "$JSON_COMMIT_SHORT" "$dest" "$sha"
  say "候批就绪 — approve with: $PROGDIR/$PROGNAME approve --root \"$RELEASE_ROOT\""
  [ -n "$drift_lines" ] && say "note: preflight drift on record: $drift_lines"
  exit 0
}

# ════════════════════════════════════════════════════════════════════════════
# approve
# ════════════════════════════════════════════════════════════════════════════
cmd_approve() {
  local cur prev rc evidence

  load_staged || { verdict APPROVE "REJECT reason=no staged-release.json in $RELEASE_ROOT — run stage first"; exit 1; }
  [ -n "$STAGED_SMOKE" ] || { verdict APPROVE "REJECT reason=staged record carries no smoke evidence"; exit 1; }
  case "$STAGED_SMOKE" in
    BOOT-SMOKE:\ PASS*) : ;;
    *) verdict APPROVE "REJECT reason=staged smoke evidence is not a PASS line: $STAGED_SMOKE"; exit 1 ;;
  esac
  [ -f "$STAGED_JAR" ] || { verdict APPROVE "REJECT reason=staged jar missing: $STAGED_JAR"; exit 1; }

  cur="$(read_pointer)"
  if [ "$STAGED_APPROVED" = "1" ] && [ "$cur" = "$STAGED_VERSION" ]; then
    say "already approved and current: $STAGED_VERSION (no-op)"
    verdict APPROVE "OK version=$STAGED_VERSION previous=$STAGED_PREVIOUS (already current, no-op)"
    exit 0
  fi

  # 完整性: staged jar 与候批卡上的 sha256 对账(换包/截断在这里拦)
  local sha_now; sha_now="$(sha256_of "$STAGED_JAR")"
  if [ -n "$STAGED_JAR_SHA" ] && [ "$sha_now" != "$STAGED_JAR_SHA" ]; then
    verdict APPROVE "REJECT reason=staged jar sha256 mismatch (recorded=$STAGED_JAR_SHA now=$sha_now) — jar changed after staging"
    exit 1
  fi

  # ── 晋级探针: 对 staged jar 再跑一次 boot-smoke; FAIL = 拒, 指针不动 ──
  say "promotion probe: boot-smoke on staged jar (port=$SMOKE_PORT timeout=${SMOKE_TIMEOUT}s)"
  run_smoke "$STAGED_JAR"; rc=$?
  evidence="$(smoke_evidence_line)"
  if [ "$rc" -ne 0 ] || [ "${evidence#BOOT-SMOKE: PASS}" = "$evidence" ]; then
    [ -n "$evidence" ] && say "probe verdict: $evidence"
    verdict APPROVE "REJECT reason=promotion probe FAILED on staged jar ($STAGED_VERSION) — pointer untouched, version stays staged"
    exit 1
  fi
  say "probe PASS: $evidence"

  # ── 原子换指针(只写文件; 重启交给 bin/stand, 本脚本零信号) ──
  prev="$cur"
  [ -z "$prev" ] && prev="$STAGED_PREVIOUS"
  atomic_write "$(pointer_file)" "$STAGED_VERSION"
  if [ -n "$prev" ] && [ "$prev" != "$STAGED_VERSION" ]; then
    atomic_write "$(previous_file)" "$prev"
  fi

  # 更新账本: approve 后 previous 以换手时刻的指针为准
  JSON_VERSION="$STAGED_VERSION"
  JSON_COMMIT="$STAGED_COMMIT"
  JSON_COMMIT_SHORT="$STAGED_COMMIT_SHORT"
  JSON_BRANCH="$(json_get "$STAGED_FILE" branch)"
  JSON_JAR="$STAGED_JAR"
  JSON_JAR_SHA="$STAGED_JAR_SHA"
  JSON_SMOKE_EVIDENCE="$STAGED_SMOKE"
  JSON_SMOKE_PORT="$(json_get "$STAGED_FILE" smokePort)"
  JSON_SMOKED_AT="$(json_get "$STAGED_FILE" smokedAt)"
  JSON_STAGED_AT="$(json_get "$STAGED_FILE" stagedAt)"
  JSON_PREVIOUS="$prev"
  JSON_APPROVED="true"
  JSON_APPROVED_AT="$(now_iso)"
  JSON_ROLLED_BACK_AT=""
  json_write "$STAGED_FILE"

  say "pointer switched: current-version -> $STAGED_VERSION (previous: ${prev:-none})"
  say "restart when ready via: bin/stand stop && bin/stand start  (release.sh never touches running processes)"
  verdict APPROVE "OK version=$STAGED_VERSION previous=${prev:-none}"
  say "回退指令(一行): $PROGDIR/$PROGNAME rollback --root \"$RELEASE_ROOT\""
  exit 0
}

# ════════════════════════════════════════════════════════════════════════════
# rollback
# ════════════════════════════════════════════════════════════════════════════
cmd_rollback() {
  local cur target
  cur="$(read_pointer)"
  target=""
  if load_staged && [ -n "$STAGED_PREVIOUS" ]; then target="$STAGED_PREVIOUS"; fi
  if [ -z "$target" ]; then
    target="$(cat "$(previous_file)" 2>/dev/null | tr -d ' \t\r\n')"
  fi
  [ -n "$target" ] || { verdict ROLLBACK "FAIL reason=no previous version on record (staged-release.json / previous-version)"; exit 1; }

  if [ "$cur" = "$target" ]; then
    say "already at previous: current-version=$cur (idempotent no-op)"
    verdict ROLLBACK "OK current=$cur (already at previous, no-op)"
    exit 0
  fi

  if [ ! -f "$RELEASE_ROOT/versions/$target/stand-assembly-$target.jar" ]; then
    # 指针命名的包缺失时 bin/stand 会兜底扫 *.jar; 这里按严格面拒绝
    verdict ROLLBACK "FAIL reason=previous version package missing on disk: versions/$target/stand-assembly-$target.jar"
    exit 1
  fi

  atomic_write "$(pointer_file)" "$target"
  if load_staged; then
    JSON_VERSION="$STAGED_VERSION"; JSON_COMMIT="$STAGED_COMMIT"; JSON_COMMIT_SHORT="$STAGED_COMMIT_SHORT"
    JSON_BRANCH="$(json_get "$STAGED_FILE" branch)"; JSON_JAR="$STAGED_JAR"; JSON_JAR_SHA="$STAGED_JAR_SHA"
    JSON_SMOKE_EVIDENCE="$STAGED_SMOKE"; JSON_SMOKE_PORT="$(json_get "$STAGED_FILE" smokePort)"
    JSON_SMOKED_AT="$(json_get "$STAGED_FILE" smokedAt)"; JSON_STAGED_AT="$(json_get "$STAGED_FILE" stagedAt)"
    JSON_PREVIOUS="$STAGED_PREVIOUS"; JSON_APPROVED="$STAGED_APPROVED"
    [ "$JSON_APPROVED" = "1" ] && JSON_APPROVED="true" || JSON_APPROVED="false"
    JSON_APPROVED_AT="$STAGED_APPROVED_AT"
    JSON_ROLLED_BACK_AT="$(now_iso)"
    json_write "$STAGED_FILE"
  fi
  say "pointer rolled back: current-version -> $target (was: ${cur:-none})"
  say "restart when ready via: bin/stand stop && bin/stand start  (release.sh never touches running processes)"
  verdict ROLLBACK "OK current=$target"
  exit 0
}

# ════════════════════════════════════════════════════════════════════════════
# tag — 死 tag 教训的机械卡点(10-24: tag 上的 VERSION 与 tag 名脱钩无人知)
# ════════════════════════════════════════════════════════════════════════════
cmd_tag() {
  local expected tag ver_at_commit runtime_v jbin

  load_staged || { verdict TAG "FAIL reason=no staged-release.json — nothing approved to tag"; exit 1; }
  [ "$STAGED_APPROVED" = "1" ] || {
    verdict TAG "FAIL reason=staging $STAGED_VERSION not approved yet — tag only after approve (approval ledger has approved=false)"
    exit 1
  }
  [ -n "$STAGED_COMMIT" ] && [ "$STAGED_COMMIT" != "unknown" ] || { verdict TAG "FAIL reason=staged record has no commit"; exit 1; }
  git -C "$REPO" cat-file -e "$STAGED_COMMIT" 2>/dev/null || { verdict TAG "FAIL reason=approved commit $STAGED_COMMIT not found in repo $REPO"; exit 1; }

  tag="${TAG_NAME:-v$STAGED_VERSION}"
  expected="${tag#v}"
  [ "$expected" != "$tag" ] || { verdict TAG "FAIL reason=tag name must be v-prefixed (got '$tag', e.g. v$STAGED_VERSION)"; exit 1; }
  [ "$expected" = "$STAGED_VERSION" ] || {
    verdict TAG "FAIL reason=tag '$tag' implies version '$expected' but staged version is '$STAGED_VERSION'"
    exit 1
  }
  if git -C "$REPO" rev-parse -q --verify "refs/tags/$tag" >/dev/null 2>&1; then
    verdict TAG "FAIL reason=tag $tag already exists (tags are immutable — never overwrite)"
    exit 1
  fi

  # 对账 ①: approved commit 上的 VERSION 文件 == staged 版本(10-24 死 tag 卡点)
  ver_at_commit="$(git -C "$REPO" show "$STAGED_COMMIT":VERSION 2>/dev/null | tr -d ' \t\r\n')"
  [ "$ver_at_commit" = "$STAGED_VERSION" ] || {
    verdict TAG "FAIL reason=VERSION at approved commit reads '$ver_at_commit', staged version is '$STAGED_VERSION' (dead-tag class — refusing)"
    exit 1
  }

  # 对账 ②: jar 内 runtime version(`java -jar <jar> version`) == staged 版本
  [ -f "$STAGED_JAR" ] || { verdict TAG "FAIL reason=staged jar missing: $STAGED_JAR"; exit 1; }
  jbin="$(pick_java)"
  [ -n "$jbin" ] || { verdict TAG "FAIL reason=no java for runtime version reconciliation"; exit 1; }
  runtime_v="$(jar_runtime_version "$STAGED_JAR" "$jbin")"
  [ "$runtime_v" = "$STAGED_VERSION" ] || {
    verdict TAG "FAIL reason=jar runtime version reads '$runtime_v' (java -jar version), staged version is '$STAGED_VERSION' — refusing"
    exit 1
  }

  git -C "$REPO" tag -a "$tag" -m "stand $STAGED_VERSION" "$STAGED_COMMIT" 2>/dev/null \
    || { verdict TAG "FAIL reason=git tag failed for $tag at $STAGED_COMMIT"; exit 1; }

  say "reconciled: tag($tag) == VERSION@$STAGED_COMMIT_SHORT($ver_at_commit) == jar runtime($runtime_v)"
  say "tag created on the approved commit $STAGED_COMMIT_SHORT — NOT pushed (push is always an explicit author action)"
  verdict TAG "OK tag=$tag commit=$STAGED_COMMIT_SHORT"
  exit 0
}

# ════════════════════════════════════════════════════════════════════════════
# status
# ════════════════════════════════════════════════════════════════════════════
cmd_status() {
  local cur
  cur="$(read_pointer)"
  say "root: $RELEASE_ROOT"
  say "current-version: ${cur:-(none)}"
  say "previous-version: $(cat "$(previous_file)" 2>/dev/null | tr -d ' \t\r\n' || true)"
  if load_staged; then
    say "staged: version=$STAGED_VERSION commit=$STAGED_COMMIT_SHORT approved=$STAGED_APPROVED"
    say "staged jar: $STAGED_JAR"
    say "staged smoke: $STAGED_SMOKE"
  else
    say "staged: (none)"
  fi
  local n=0 p
  for p in "$RELEASE_ROOT/versions"/*/; do [ -d "$p" ] && n=$((n + 1)); done
  say "versions on disk: $n"
  exit 0
}

# ════════════════════════════════════════════════════════════════════════════
usage() {
  sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

# ── arg 解析 ────────────────────────────────────────────────────────────────
[ $# -gt 0 ] || usage
case "$1" in -h|--help) usage ;; esac
CMD="$1"; shift
while [ $# -gt 0 ]; do
  case "$1" in
    --root)          RELEASE_ROOT="$2"; shift 2 ;;
    --repo)          REPO="$2"; shift 2 ;;
    --smoke-port)    SMOKE_PORT="$2"; shift 2 ;;
    --smoke-timeout) SMOKE_TIMEOUT="$2"; shift 2 ;;
    --tag)           TAG_NAME="$2"; shift 2 ;;
    -h|--help)       usage ;;
    *) die "unknown option for $CMD: $1 (see --help)" ;;
  esac
done

case "$CMD" in
  stage)    check_smoke_port; cmd_stage ;;
  approve)  check_smoke_port; cmd_approve ;;
  rollback) cmd_rollback ;;
  tag)      cmd_tag ;;
  status)   cmd_status ;;
  *)        die "unknown subcommand: $CMD (stage|approve|rollback|tag|status)" ;;
esac
