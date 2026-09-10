#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────
# JDK 基线防漂移门禁（D 轨 2026-09-10；同批收尾补正）
#
# ── 两个常量，不是一个数 ────────────────────────────────────────────────
#   JDK_BASELINE        = 21   运行时门槛：JVM 必须 >= 21 才允许起 gateway
#   JDK_BYTECODE_TARGET = 17   产物字节码 / 编译期 API 面目标，必须 < 门槛
# 两者**刻意不相等**。把 build.sbt 的 `-release` 钉回 21 是**回退，不是修复**：
# 字节码 major 65 会让 17 JVM 连 nebflow.Main 都加载不了，于是
#   · JvmRequirement 的人话报错（`requires Java 21` + 平台升级指引 + Jar:/Java:）
#     整句不可达 —— 闸根本没机会执行；
#   · `nebflow update | doctor | version` 这些**自修入口**一起被焊死
#     （立项约束：闸放 GatewayMain.run 首行，不放 Main.run）。
# 目标低一档不会放开 API 面：`-release` 同时钉 API 面与字节码目标，17 的 API 面
# 是 21 的**子集**，API 漂移从机制上更不可能。Scala 3.5.2 也无法把「API 面 21」
# 与「输出字节码 17」拆开（`-release` 与 `-java-output-version` 是同一个设置；
# 同时给会 "Flag -java-output-version set repeatedly"，单独给
# `-java-output-version:17` 同样把 API 面降到 17 —— 两者均实测），所以唯一能拿到
# ≤ 61 产物的机制就是整编译单元 `-release:17`。
#
# ── 病根 ────────────────────────────────────────────────────────────────
# 「运行时门槛 = 21」是**同一个事实**横跨五个声明面：
#   release/install.sh   JDK_MAJOR_REQUIRED      运行时门槛（Unix 装/启）
#   release/install.ps1  $JdkMajorRequired       运行时门槛（Windows 装/启）
#   README.md            Java 徽章 / build-from-source 行
#   Dockerfile:1         基镜像 tag
#   build.sbt            -release:<n>            字节码与 API 面（== 目标，非门槛）
# 五处各自为政且无门禁时，任何一处回漂都不会被发现，直到用户在 17 机器上
# 装出坏环境（或构建产物在 17 上加载失败）。跨平台不同步是**独立的故障模式**
# ——G5 形态实测过：install.sh 与 install.ps1 的值不一致，单看任一份都「正常」。
# 本门禁把门槛收敛成一个变量（JDK_BASELINE），五个面各自断言 == 它（build.sbt
# 断言 == 字节码目标），并额外断言两份安装器**互相相等**（只断言各自 == 基线时，
# 两侧同时漂到同一个错值 是「一致但错」；两者都红反而更早暴露）。
#
# ── 断言（默认静态模式）────────────────────────────────────────────────
#   A-1 release/install.sh   JDK_MAJOR_REQUIRED == $JDK_BASELINE（全文件恰一处）
#   A-2 release/install.ps1  $JdkMajorRequired  == $JDK_BASELINE（全文件恰一处）
#   A-3 A-1 的值 == A-2 的值（平台间同值）
#   A-4 README Java 徽章 / README build-from-source 行 / Dockerfile:1 的 FROM
#       == $JDK_BASELINE，且这些声明行内不含 `Java-17` / `Java 17` / `17-jre`
#       / `17-jdk`
#   A-5 build.sbt 的 `-release:<n>` == $JDK_BYTECODE_TARGET（全文件恰一处）
#   A-7a $JDK_BYTECODE_TARGET **严格小于** $JDK_BASELINE（数值）
#
# ── A-7b / A-6 真复现（--jar <path>，可选追加）─────────────────────────
#   A-7b 读 jar 内 `nebflow/Main.class` 的 class-file major，断言
#        == $JDK_BYTECODE_TARGET + 44（构造产物的人话报错可达性的**读数值**）。
#   A-6  把自带 jar 放进 eclipse-temurin:17-jre 容器里跑**五条命令**：
#        §1 bare 无参启动 —— 必须命中**人话报错**：`requires Java 21` +
#           `Upgrade it, then start again:` + `openjdk@21` + `Jar:` + `Java:`
#           各行，且**非零退出**。这里**只认人话报错**：旧版把
#           `UnsupportedClassVersionError` 也当通过，等于承认「闸跑不到」，
#           等于放弃验收 ① —— 已收敛掉。
#        §2 `version` / `help` / `doctor` / `update` 四条自修入口逐条跑：未超时、
#           输出非空、输出里**零** `UnsupportedClassVersionError` /
#           `class file version`、`version` 必须打出 `nebflow v…`。
#           （断网容器里 `update` 的判据 = 进入自身逻辑并给出它自己的错误，
#           而非 class-load 失败。）
#        §3 任一命令超时 = **红** ——「跑起来不结束」既不是拒绝也不是通过。
# 容器纪律：`--network none`（无 --network host、无端口映射、宿主 8080 不可达）；
# 具名容器 + EXIT trap 收尸；REPRO_TIMEOUT 秒/命令（默认 90）。
#
# ── 已知边界（不覆盖，人工审查域）───────────────────────────────────────
#   · .github/workflows/*.yml 的 setup-java java-version（现状全为 21，
#     由 ea01fdb5 统一；不在本清单内，回漂靠人工审查）
#   · packaging/build-{dmg,msi,linux}.sh 的构建机 JDK 断言（C 轨自带断言）
#   · 字符串/注释内的同形文本一律不豁免也不特别处理（本门禁直接对声明行取值）
#
# ── 用法 ────────────────────────────────────────────────────────────────
#   bash scripts/check-jdk-baseline.sh                  # 静态断言（CI 与本地同逻辑）
#   bash scripts/check-jdk-baseline.sh --jar <jar>      # 追加 A-7b + A-6 真复现
#   JDK_BASELINE=22 bash scripts/check-jdk-baseline.sh  # 门槛整体抬升：只改这一处
#                                                       #（字节码目标仍须 < 门槛）
#   REPRO_TIMEOUT=10 bash scripts/check-jdk-baseline.sh --jar <jar>   # 每命令超时下调
# 退出码：0 = 干净；1 = 断言失败（门禁红）；3 = 环境错误（缺文件/缺 docker/容器
#         起不来）——「跑不动」必须显式失败，绝不伪装成「干净」。
# ─────────────────────────────────────────────────────────────────────────

set -uo pipefail
cd "$(dirname "$0")/.."

JDK_BASELINE="${JDK_BASELINE:-21}"
JDK_BYTECODE_TARGET="${JDK_BYTECODE_TARGET:-17}"
REPRO_TIMEOUT="${REPRO_TIMEOUT:-90}"
JDK17_IMAGE="${JDK17_IMAGE:-eclipse-temurin:17-jre}"   # 门槛的**前一档** JVM
CF_MAJOR_OFFSET=44   # class-file major = 44 + feature version (Java 8 及以上)
JAR_PATH=""
CNAME=""     # 当前 A-6 容器名（全局：EXIT trap 收尸要用）
RC=""        # 上一条 A-6 命令的退出码（run_jdk17 写）
TIMED_OUT="" # 上一条 A-6 命令是否超时（run_jdk17 写）
LOG_DIR=""

usage() { sed -n '2,77p' "$0"; exit 0; }
err() { echo "✗ jdk-baseline gate ERROR: $*" >&2; exit 3; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --jar) JAR_PATH="${2:-}"; shift 2 || err "--jar needs a path" ;;
    --jar=*) JAR_PATH="${1#--jar=}"; shift ;;
    -h|--help) usage ;;
    *) err "unknown argument: $1 (try --help)" ;;
  esac
done

printf '%s' "$JDK_BASELINE" | grep -qE '^[0-9]+$' || err "JDK_BASELINE must be an integer, got: $JDK_BASELINE"
printf '%s' "$JDK_BYTECODE_TARGET" | grep -qE '^[0-9]+$' || err "JDK_BYTECODE_TARGET must be an integer, got: $JDK_BYTECODE_TARGET"
printf '%s' "$REPRO_TIMEOUT" | grep -qE '^[0-9]+$' || err "REPRO_TIMEOUT must be an integer, got: $REPRO_TIMEOUT"

SH_FILE=release/install.sh
PS_FILE=release/install.ps1
README_FILE=README.md
DOCKERFILE=Dockerfile
SBT_FILE=build.sbt
for f in "$SH_FILE" "$PS_FILE" "$README_FILE" "$DOCKERFILE" "$SBT_FILE"; do
  [ -f "$f" ] || err "missing file: $f — refusing to report a clean gate"
done

FAILED=0
pass() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1"; FAILED=1; }
info() { echo "      $1"; }
# 声明的行数（0 行时 grep -c 退出码为 1，但已经打印了 "0"）
count_lines() { printf '%s\n' "$1" | grep -c . || true; }

echo "jdk-baseline gate: baseline=${JDK_BASELINE} (cwd=$(pwd))"

# ── A-1 / A-2：两份安装器的门槛常量（各恰一处声明，且 == 基线）──────────
# 断言用的是 `grep -n` 的输出（带 `N:` 行号前缀），ERE 必须吃掉该前缀——
# 否则 `^` 锚在行号上，**任何**取值都会判红（R2 实测暴露过这一版假红）。
A1_ERE='^[0-9]+:[[:space:]]*JDK_MAJOR_REQUIRED[[:space:]]*=[[:space:]]*'"$JDK_BASELINE"'([[:space:]]*#.*)?$'
sh_decl=$(grep -nE '^[[:space:]]*JDK_MAJOR_REQUIRED[[:space:]]*=' "$SH_FILE" || true)
sh_count=$(count_lines "$sh_decl")
sh_val=$(printf '%s\n' "$sh_decl" | sed -nE 's/^[0-9]+:[[:space:]]*JDK_MAJOR_REQUIRED[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' | head -1)
if [ "$sh_count" = "1" ] && printf '%s\n' "$sh_decl" | grep -qE "$A1_ERE"; then
  pass "A-1 ${SH_FILE} JDK_MAJOR_REQUIRED == ${JDK_BASELINE} — actual: $(printf '%s' "$sh_decl" | sed 's/^[0-9]*://')"
else
  fail "A-1 ${SH_FILE} JDK_MAJOR_REQUIRED != ${JDK_BASELINE}"
  info "expr: grep -nE '$A1_ERE' $SH_FILE"
  info "actual: ${sh_count} declaration line(s): ${sh_decl:-<none>}"
fi

A2_ERE='^[0-9]+:[[:space:]]*[$]JdkMajorRequired[[:space:]]*=[[:space:]]*'"$JDK_BASELINE"'([[:space:]]*#.*)?$'
ps_decl=$(grep -nE '^[[:space:]]*[$]JdkMajorRequired[[:space:]]*=' "$PS_FILE" || true)
ps_count=$(count_lines "$ps_decl")
ps_val=$(printf '%s\n' "$ps_decl" | sed -nE 's/^[0-9]+:[[:space:]]*[$]JdkMajorRequired[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' | head -1)
if [ "$ps_count" = "1" ] && printf '%s\n' "$ps_decl" | grep -qE "$A2_ERE"; then
  pass "A-2 ${PS_FILE} \$JdkMajorRequired == ${JDK_BASELINE} — actual: $(printf '%s' "$ps_decl" | sed 's/^[0-9]*://')"
else
  fail "A-2 ${PS_FILE} \$JdkMajorRequired != ${JDK_BASELINE}"
  info "expr: grep -nE '$A2_ERE' $PS_FILE"
  info "actual: ${ps_count} declaration line(s): ${ps_decl:-<none>}"
fi

# ── A-3：install.sh 与 install.ps1 必须同值（G5 形态：跨平台漂移）────────
if [ -n "$sh_val" ] && [ -n "$ps_val" ] && [ "$sh_val" = "$ps_val" ]; then
  pass "A-3 platform parity: install.sh(${sh_val}) == install.ps1(${ps_val})"
else
  fail "A-3 platform drift: install.sh='${sh_val:-<none>}' != install.ps1='${ps_val:-<none>}'"
  info "G5 form: a user on one platform would get a different floor than the other."
fi

# ── A-4：README 声明行 + Dockerfile 基镜像 ──────────────────────────────
BADGE_ERE='^[0-9]+:.*badge/Java-'"$JDK_BASELINE"'%2B'
NEG_ERE='Java-17|Java 17|17-jre|17-jdk'
badge_decl=$(grep -nE 'badge/Java-' "$README_FILE" || true)
badge_count=$(count_lines "$badge_decl")
badge_neg=$(printf '%s\n' "$badge_decl" | grep -nE "$NEG_ERE" || true)
if [ "$badge_count" = "1" ] && printf '%s\n' "$badge_decl" | grep -qE "$BADGE_ERE" && [ -z "$badge_neg" ]; then
  pass "A-4a ${README_FILE} Java badge == ${JDK_BASELINE} — actual: $(printf '%s' "$badge_decl")"
else
  fail "A-4a ${README_FILE} Java badge != ${JDK_BASELINE} (or still says 17)"
  info "expr: grep -nE '$BADGE_ERE' $README_FILE   + negative: grep -E '$NEG_ERE'"
  info "actual: ${badge_count} badge line(s): ${badge_decl:-<none>}"
fi

# build-from-source 行只有存在时才断言（措辞可改，不因缺行变红）
src_decl=$(grep -nE 'build from source \(Java [0-9]+\+' "$README_FILE" || true)
if [ -z "$src_decl" ]; then
  info "A-4b ${README_FILE} build-from-source line not present in that form — skipped"
elif printf '%s\n' "$src_decl" | grep -qE 'Java '"$JDK_BASELINE"'\+' && ! printf '%s\n' "$src_decl" | grep -qE "$NEG_ERE"; then
  pass "A-4b ${README_FILE} build-from-source == Java ${JDK_BASELINE}+ — actual: $(printf '%s' "$src_decl")"
else
  fail "A-4b ${README_FILE} build-from-source line not on Java ${JDK_BASELINE}+"
  info "expr: grep -nE 'build from source \(Java ${JDK_BASELINE}\+' $README_FILE"
  info "actual: ${src_decl:-<none>}"
fi

DOCK_ERE='^[0-9]+:FROM eclipse-temurin:'"$JDK_BASELINE"'-jre'
dock_from=$(grep -nE '^FROM ' "$DOCKERFILE" | head -1 || true)
if printf '%s\n' "$dock_from" | grep -qE "$DOCK_ERE" && ! printf '%s\n' "$dock_from" | grep -qE "$NEG_ERE"; then
  pass "A-4c ${DOCKERFILE}:1 base image on Java ${JDK_BASELINE} — actual: $(printf '%s' "$dock_from")"
else
  fail "A-4c ${DOCKERFILE}:1 base image not on Java ${JDK_BASELINE}"
  info "expr: grep -nE '$DOCK_ERE' $DOCKERFILE   + negative: grep -E '$NEG_ERE'"
  info "actual: ${dock_from:-<none>}"
fi

# ── A-5：build.sbt 的 -release:<n> == 字节码目标（不是门槛）──────────────
# 只看**代码行**：先剥掉 `//` 之后的内容，注释里出现同形文本（例如
# 「把这里改回 -release:21 是回退」这类说明）不该污染/触发断言。
rel_decl=$(sed 's://.*::' "$SBT_FILE" | grep -nE -- '-release:[0-9]+' || true)
rel_count=$(count_lines "$rel_decl")
rel_target_ere='-release:'"$JDK_BYTECODE_TARGET"'([^0-9]|$)'
if [ "$rel_count" = "1" ] && printf '%s\n' "$rel_decl" | sed 's/^[0-9]*://' | grep -qE -- "$rel_target_ere"; then
  pass "A-5 ${SBT_FILE} -release == ${JDK_BYTECODE_TARGET} (bytecode target, not the runtime floor) — actual: $(printf '%s' "$rel_decl" | sed 's/^[0-9]*:[[:space:]]*//')"
else
  fail "A-5 ${SBT_FILE} -release != ${JDK_BYTECODE_TARGET} (bytecode target)"
  info "expr: grep -nE -- '-release:${JDK_BYTECODE_TARGET}([^0-9]|\$)' $SBT_FILE"
  info "actual: ${rel_count} occurrence(s): ${rel_decl:-<none>}"
fi

# ── A-7a：字节码目标必须**严格低于**运行时门槛 ─────────────────────────
if [ "$JDK_BYTECODE_TARGET" -lt "$JDK_BASELINE" ]; then
  pass "A-7a bytecode target (${JDK_BYTECODE_TARGET}) < runtime baseline (${JDK_BASELINE}) — the jar stays loadable on the JVM below the floor, so the refusal banner is reachable"
else
  fail "A-7a bytecode target (${JDK_BYTECODE_TARGET}) is NOT below the runtime baseline (${JDK_BASELINE})"
  info "then a baseline-1 JVM cannot even load nebflow.Main: UnsupportedClassVersionError"
  info "→ the human-readable refusal is unreachable and update/doctor/version are welded shut"
fi

# ── A-7b：产物字节码（--jar）───────────────────────────────────────────
class_major_in_jar() {
  # $1 = jar, $2 = 条目名 → 打印 class-file major（字节 6..7，大端）
  unzip -p "$1" "$2" 2>/dev/null | od -An -tu1 -j6 -N2 | awk '{ if (NF >= 2) print $1 * 256 + $2; else print "" }'
}

verify_jar_bytecode() {
  local major expected abs_jar
  command -v unzip >/dev/null 2>&1 || err "A-7b unzip not found — cannot read the artifact"
  [ -f "$JAR_PATH" ] || err "A-7b jar not found: $JAR_PATH"
  abs_jar="$(cd "$(dirname "$JAR_PATH")" && pwd)/$(basename "$JAR_PATH")"
  expected=$(( JDK_BYTECODE_TARGET + CF_MAJOR_OFFSET ))
  major="$(class_major_in_jar "$abs_jar" nebflow/Main.class)"
  if [ "$major" = "$expected" ]; then
    pass "A-7b artifact bytecode: nebflow/Main.class major ${major} == ${JDK_BYTECODE_TARGET} (declared target) — loadable on a ${JDK_BYTECODE_TARGET} JVM"
  else
    fail "A-7b artifact bytecode: nebflow/Main.class major '${major:-<unreadable>}' != ${expected} (declared target ${JDK_BYTECODE_TARGET})"
    info "artifact: ${abs_jar}"
    info "a major of $(( JDK_BASELINE + CF_MAJOR_OFFSET )) (baseline bytecode) makes the refusal unreachable on the floor's predecessor"
  fi
}

# ── A-6：17 容器真复现（--jar）─────────────────────────────────────────
indent_log() { sed 's/\r$//' | head -12 | sed 's/^/      | /'; }

cleanup_container() {
  if [ -n "$CNAME" ]; then docker rm -f "$CNAME" >/dev/null 2>&1 || true; fi
}

LINKAGE_ERE='UnsupportedClassVersionError|class file version'

# 在 17 容器里跑一条命令；结果落 $2 日志，全局 RC / TIMED_OUT 回填。
# $1 = 标签（也用于容器名/日志名），$2 = 日志路径，其余 = 传给 jar 的参数。
run_jdk17() {
  local label="$1" log="$2" abs_jar="$3"; shift 3
  local name="nb-jdk-baseline-${label}-$$" waited=0 running=true
  RC=""
  TIMED_OUT=""
  : > "$log" || err "cannot write log: $log"
  docker rm -f "$name" >/dev/null 2>&1 || true
  CNAME="$name"
  # --network none：无 --network host、无端口映射，宿主 8080 不可达
  if ! docker run -d --name "$name" --network none -v "$abs_jar:/app/app.jar:ro" "$JDK17_IMAGE" \
        java --add-opens java.base/java.lang=ALL-UNNAMED -jar /app/app.jar "$@" >/dev/null 2>&1; then
    CNAME=""
    err "A-6 could not start the ${JDK17_IMAGE} container (image pull / docker daemon?) — repro unproven"
  fi
  while [ "$waited" -lt "$REPRO_TIMEOUT" ]; do
    running=$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null || echo false)
    [ "$running" = "false" ] && break
    sleep 1
    waited=$((waited + 1))
  done
  if [ "$running" = "true" ]; then
    TIMED_OUT=1
  else
    RC=$(docker inspect -f '{{.State.ExitCode}}' "$name" 2>/dev/null || echo unknown)
  fi
  docker logs "$name" > "$log" 2>&1 || true
  docker rm -f "$name" >/dev/null 2>&1 || true
  CNAME=""
}

repro_on_java17() {
  local abs_jar log first missing pat
  [ -f "$JAR_PATH" ] || err "A-6 jar not found: $JAR_PATH"
  command -v docker >/dev/null 2>&1 || err "A-6 docker not found — cannot reproduce, refusing to report green"
  abs_jar="$(cd "$(dirname "$JAR_PATH")" && pwd)/$(basename "$JAR_PATH")"
  LOG_DIR="${JDK_GATE_LOG_DIR:-${TMPDIR:-/tmp}/nb-jdk-baseline-logs-$$}"
  mkdir -p "$LOG_DIR" || err "cannot create log dir: $LOG_DIR"
  echo "  · A-6 repro: image=${JDK17_IMAGE} jar=${abs_jar} ($(wc -c < "$abs_jar" | tr -d ' ') bytes) timeout=${REPRO_TIMEOUT}s/command"
  trap cleanup_container EXIT INT TERM

  # ── §1 bare：必须命中人话报错 + 非零退出 ─────────────────────────────
  log="$LOG_DIR/bare.log"
  run_jdk17 bare "$log" "$abs_jar"
  if [ -n "$TIMED_OUT" ]; then
    fail "A-6 [bare] still running after ${REPRO_TIMEOUT}s on Java 17 — a hang is neither a refusal nor a pass"
    indent_log < "$log"
  elif [ "$RC" = "0" ]; then
    fail "A-6 [bare] exited 0 on Java 17 — must refuse to run (Java 17 is below the baseline)"
    indent_log < "$log"
  else
    missing=""
    for pat in 'requires Java 21' 'Upgrade it, then start again:' 'openjdk@21' '^Jar: ' '^Java: '; do
      grep -qE -- "$pat" "$log" 2>/dev/null || missing="${missing}${missing:+; }$pat"
    done
    if [ -z "$missing" ]; then
      pass "A-6 [bare] human-readable refusal reached on Java 17 — exit=${RC}; $(grep -m1 -E 'requires Java 21' "$log" | tr -d '\r')"
      indent_log < "$log"
    else
      fail "A-6 [bare] exited ${RC} on Java 17 but the refusal is incomplete — missing: ${missing}"
      info "accepted form: 'requires Java 21' + upgrade guidance + 'Jar:'/'Java:' diagnostics"
      indent_log < "$log"
    fi
  fi

  # ── §2 四条自修命令必须活着（进入自身逻辑，零 linkage 失败）──────────
  for cmd in version help doctor update; do
    log="$LOG_DIR/$cmd.log"
    run_jdk17 "$cmd" "$log" "$abs_jar" "$cmd"
    first="$(sed -n '1p' "$log" 2>/dev/null | tr -d '\r')"
    if [ -n "$TIMED_OUT" ]; then
      fail "A-6 [$cmd] still running after ${REPRO_TIMEOUT}s on Java 17 — the self-repair path is wedged"
      indent_log < "$log"
    elif [ -z "$first" ]; then
      fail "A-6 [$cmd] produced no output on Java 17 (exit=${RC:-<none>}) — class-load failure?"
      indent_log < "$log"
    elif grep -qE "$LINKAGE_ERE" "$log"; then
      fail "A-6 [$cmd] hit a class-load failure on Java 17 — self-repair path welded shut: $(grep -m1 -E "$LINKAGE_ERE" "$log" | tr -d '\r')"
      indent_log < "$log"
    elif [ "$cmd" = "version" ] && ! grep -q 'nebflow v' "$log"; then
      fail "A-6 [version] ran on Java 17 (exit=${RC}) but did not print 'nebflow v…' — first line: ${first}"
      indent_log < "$log"
    else
      pass "A-6 [$cmd] ran on Java 17 (exit=${RC}, no UnsupportedClassVersionError) — first line: ${first}"
    fi
  done
  info "logs: $LOG_DIR"
}

if [ -n "$JAR_PATH" ]; then
  # A-7b 先于 A-6：先读产物字节码（静态、秒级），再看运行时行为
  verify_jar_bytecode
  repro_on_java17
fi

if [ "$FAILED" = 0 ]; then
  echo "jdk-baseline gate: PASS — runtime baseline ${JDK_BASELINE} declared on every surface, artifact bytecode target ${JDK_BYTECODE_TARGET} (< baseline, banner reachable)"
  exit 0
fi
echo "jdk-baseline gate: FAIL — baseline drift detected (see ✗ above)"
exit 1
