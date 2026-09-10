#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────
# JDK 基线防漂移门禁（D 轨，2026-09-10）
#
# ── 病根 ────────────────────────────────────────────────────────────────
# 「JDK 基线 = 21」是**同一个事实**横跨五个声明面：
#   release/install.sh   JDK_MAJOR_REQUIRED      运行时门槛（Unix 装/启）
#   release/install.ps1  $JdkMajorRequired       运行时门槛（Windows 装/启）
#   README.md            Java 徽章 / build-from-source 行
#   Dockerfile:1         基镜像 tag
#   build.sbt            -release:<n>            字节码与 API 面
# 五处各自为政且无门禁时，任何一处回漂都不会被发现，直到用户在 17 机器上
# 装出坏环境（或构建产物在 17 上加载失败）。跨平台不同步是**独立的故障模式**
# ——G5 形态实测过：install.sh 与 install.ps1 的值不一致，单看任一份都「正常」。
# 本门禁把基线收敛成一个变量（JDK_BASELINE），五个面各自断言 == 它，并额外
# 断言两份安装器**互相相等**（只断言各自 == 基线时，两侧同时漂到同一个错值
# 是「一致但错」；两者都红反而更早暴露）。
#
# ── 断言（默认静态模式；值与 A/B/C 三轨合并后的实际取值对齐）────────────
#   A-1 release/install.sh   JDK_MAJOR_REQUIRED == $JDK_BASELINE（全文件恰一处声明）
#   A-2 release/install.ps1  $JdkMajorRequired  == $JDK_BASELINE（全文件恰一处声明）
#   A-3 A-1 的值 == A-2 的值（平台间同值）
#   A-4 README Java 徽章 / README build-from-source 行 / Dockerfile:1 的 FROM
#       == $JDK_BASELINE，且这些声明行内不含 `Java-17` / `Java 17` / `17-jre`
#   A-5 build.sbt 的 `-release:<n>` == $JDK_BASELINE（全文件恰一处；无 `-release:17`）
#
# ── A-6 真复现（--jar <path>，可选追加）─────────────────────────────────
# 把自带 jar 放进 eclipse-temurin:17-jre 容器里跑一次，断言**闸行为**：
# 拒绝启动 = 非零退出 + 带 Java 21 / 字节码版本号的人话诊断。
# 这是唯一能让「17 不兼容」在 CI 里可见的断言——现有 docker-test 的健康检查
# 是 `curl -sf … || echo "Health check failed"`（不构成失败断言），容器在 17 上
# 永远假绿，无论门槛是 17 还是 21。
# 容器纪律：`--network none`（无 --network host、无端口映射、宿主 8080 不可达）；
# 具名容器 + EXIT trap 收尸；REPRO_TIMEOUT 秒（默认 90）内不退出 = **未拒绝 = 红**
# ——「跑起来不结束」绝不能被当成通过。
# 接受两类诊断（合并树上必须二者之一命中，否则 CI 红）：
#   · `requires Java 21`（GatewayMain 启动闸的人话报错）
#   · `UnsupportedClassVersionError` / `class file version`（-release:21 使字节码
#     目标抬到 major 65，17 JVM 连类都加载不了——此时闸根本没机会执行）
#
# ── 已知边界（不覆盖，人工审查域）───────────────────────────────────────
#   · .github/workflows/*.yml 的 setup-java java-version（现状 13 处全为 21，
#     由 ea01fdb5 统一；不在本清单内，回漂靠人工审查）
#   · packaging/build-{dmg,msi,linux}.sh 的构建机 JDK 断言（C 轨自带断言）
#   · 字符串/注释内的同形文本一律不豁免也不特别处理（本门禁直接对声明行取值）
#
# ── 用法 ────────────────────────────────────────────────────────────────
#   bash scripts/check-jdk-baseline.sh                  # 静态断言（CI 与本地同逻辑）
#   bash scripts/check-jdk-baseline.sh --jar <jar>      # 追加 A-6 真复现
#   JDK_BASELINE=22 bash scripts/check-jdk-baseline.sh  # 基线整体抬升：只改这一处
#   REPRO_TIMEOUT=10 bash scripts/check-jdk-baseline.sh --jar <jar>   # 复现超时下调
# 退出码：0 = 干净；1 = 断言失败（门禁红）；3 = 环境错误（缺文件/缺 docker/容器
#         起不来）——「跑不动」必须显式失败，绝不伪装成「干净」。
# ─────────────────────────────────────────────────────────────────────────

set -uo pipefail
cd "$(dirname "$0")/.."

JDK_BASELINE="${JDK_BASELINE:-21}"
REPRO_TIMEOUT="${REPRO_TIMEOUT:-90}"
JAR_PATH=""
CNAME=""   # A-6 容器名（全局：EXIT trap 收尸要用）

usage() { sed -n '2,60p' "$0"; exit 0; }
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

# ── A-5：build.sbt 的 -release:<n> ──────────────────────────────────────
rel_decl=$(grep -nE -- '-release:[0-9]+' "$SBT_FILE" || true)
rel_count=$(count_lines "$rel_decl")
rel_neg=$(printf '%s\n' "$rel_decl" | grep -nE -- '-release:17([^0-9]|$)' || true)
if [ "$rel_count" = "1" ] && printf '%s\n' "$rel_decl" | grep -qE -- '-release:'"$JDK_BASELINE"'([^0-9]|$)' && [ -z "$rel_neg" ]; then
  pass "A-5 ${SBT_FILE} -release == ${JDK_BASELINE} — actual: $(printf '%s' "$rel_decl" | sed 's/^[0-9]*:[[:space:]]*//')"
else
  fail "A-5 ${SBT_FILE} -release != ${JDK_BASELINE}"
  info "expr: grep -nE -- '-release:${JDK_BASELINE}([^0-9]|\$)' $SBT_FILE"
  info "actual: ${rel_count} occurrence(s): ${rel_decl:-<none>}"
fi

# ── A-6：17 容器真复现（可选）────────────────────────────────────────────
indent_log() { sed 's/\r$//' | head -12 | sed 's/^/      | /'; }

cleanup_container() {
  if [ -n "$CNAME" ]; then docker rm -f "$CNAME" >/dev/null 2>&1 || true; fi
}

repro_on_java17() {
  local image="${JDK17_IMAGE:-eclipse-temurin:17-jre}"
  local abs_jar rc running waited out diag
  [ -f "$JAR_PATH" ] || err "A-6 jar not found: $JAR_PATH"
  command -v docker >/dev/null 2>&1 || err "A-6 docker not found — cannot reproduce, refusing to report green"
  abs_jar="$(cd "$(dirname "$JAR_PATH")" && pwd)/$(basename "$JAR_PATH")"
  CNAME="nb-jdk-baseline-repro-$$"
  echo "  · A-6 repro: image=${image} jar=${abs_jar} ($(wc -c < "$abs_jar" | tr -d ' ') bytes) timeout=${REPRO_TIMEOUT}s"
  trap cleanup_container EXIT INT TERM

  # --network none：无 --network host、无端口映射，宿主 8080 不可达
  if ! docker run -d --name "$CNAME" --network none -v "$abs_jar:/app/app.jar:ro" "$image" \
        java --add-opens java.base/java.lang=ALL-UNNAMED -jar /app/app.jar >/dev/null 2>&1; then
    err "A-6 could not start the ${image} container (image pull / docker daemon?) — repro unproven"
  fi

  waited=0
  running=true
  while [ "$waited" -lt "$REPRO_TIMEOUT" ]; do
    running=$(docker inspect -f '{{.State.Running}}' "$CNAME" 2>/dev/null || echo false)
    [ "$running" = "false" ] && break
    sleep 1
    waited=$((waited + 1))
  done
  rc=$(docker inspect -f '{{.State.ExitCode}}' "$CNAME" 2>/dev/null || echo unknown)
  out=$(docker logs "$CNAME" 2>&1 || true)

  if [ "$running" = "true" ]; then
    fail "A-6 jar did NOT refuse on Java 17 — still running after ${REPRO_TIMEOUT}s (rc pending; gate missing?)"
    printf '%s\n' "$out" | indent_log
    return 0
  fi
  if [ "$rc" = "0" ]; then
    fail "A-6 jar exited 0 on Java 17 — must refuse to run (Java 17 is below the baseline)"
    printf '%s\n' "$out" | indent_log
    return 0
  fi
  diag=$(printf '%s\n' "$out" | grep -m1 -E 'UnsupportedClassVersionError|class file version|requires Java 21' || true)
  if [ -n "$diag" ]; then
    pass "A-6 jar refused on Java 17 — exit=${rc}, diagnostic: ${diag}"
  else
    fail "A-6 jar exited ${rc} on Java 17 but with no Java-21 / class-version diagnostic"
    printf '%s\n' "$out" | indent_log
  fi
  printf '%s\n' "$out" | head -6 | indent_log
}

if [ -n "$JAR_PATH" ]; then
  repro_on_java17
fi

if [ "$FAILED" = 0 ]; then
  echo "jdk-baseline gate: PASS — every declaration surface is on Java ${JDK_BASELINE}"
  exit 0
fi
echo "jdk-baseline gate: FAIL — baseline drift detected (see ✗ above)"
exit 1
