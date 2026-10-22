#!/bin/bash
# check-installer-home.sh — 安装器/垫片默认 home 防回归钉（再生治本批 2026-10-22）
# ------------------------------------------------------------------
# 病史：数据根 10-20 迁移 ~/.stand 后，旧默认 home 的安装器/垫片在 10-20 20:16
# 又落了一次 ~/.nebflow（153M 再生物），删后仍再生（旧 PATH 垫片 + 旧 jar）。
# 本检查把「默认 home = STAND_HOME（env）否则 brand.conf homeDirName（~/.stand）、
# 安装器永不写 ~/.nebflow」钉死在 CI：
#   1. install.sh / install.ps1 / uninstall.sh / uninstall.ps1 的 HOME_DIR 值
#      == brand.conf homeDirName（渲染一致性）
#   2. install.sh 有 STAND_HOME 第一优先级 base（STAND_BASE），INSTALL_DIR/
#      config/manifest/JDK/PATH 全部从 base 派生
#   3. 四个安装器脚本零 ~/.nebflow 默认写点：禁止 HOME_DIR=.nebflow、
#      禁止 $HOME/.nebflow 字面写目标、禁止读 NEBFLOW_HOME 当安装落点
#   4. install.sh 的 setup_path 会退役旧 ~/.nebflow/bin PATH 行（再生根源）
#   5. brand.conf homeDirName 必须是 .stand（本批语义；改名走 L2 再品牌流程）
# Exit 0 = 全绿；非 0 = 回归。
set -u

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail() { echo "FAIL: $*" >&2; exit 1; }
brand_value() { # <key> -> 去行内注释后的值
    sed -n "s/^$1[[:space:]]*=[[:space:]]*//p" "$REPO_ROOT/brand.conf" | head -1 | sed 's/[[:space:]]*#.*//; s/[[:space:]]*$//'
}

HOME_DIR_NAME="$(brand_value homeDirName)"
[ -n "$HOME_DIR_NAME" ] || fail "brand.conf homeDirName 无法解析"
[ "$HOME_DIR_NAME" = ".stand" ] || fail "brand.conf homeDirName=${HOME_DIR_NAME}，本批钉死 .stand（改名走 L2 流程并同步本检查）"

for f in release/install.sh release/install.ps1 release/uninstall.sh release/uninstall.ps1; do
    [ -f "$REPO_ROOT/$f" ] || fail "缺文件 $f"
done

# -- 1. HOME_DIR 值 == brand（渲染一致性） ------------------------------------
grep -q '^HOME_DIR=\.stand$' "$REPO_ROOT/release/install.sh" \
    || fail "install.sh HOME_DIR 与 brand.conf homeDirName 不一致"
grep -q '^HOME_DIR=\.stand$' "$REPO_ROOT/release/uninstall.sh" \
    || fail "uninstall.sh HOME_DIR 与 brand.conf homeDirName 不一致"
grep -q '^\$HomeDir = "\.stand"$' "$REPO_ROOT/release/install.ps1" \
    || fail "install.ps1 \$HomeDir 与 brand.conf homeDirName 不一致"
grep -q '^\$HomeDir = "\.stand"$' "$REPO_ROOT/release/uninstall.ps1" \
    || fail "uninstall.ps1 \$HomeDir 与 brand.conf homeDirName 不一致"

# -- 2. STAND_HOME 第一优先级 + base 派生 -------------------------------------
grep -q 'STAND_BASE="${STAND_HOME:-${HOME}/${HOME_DIR}}"' "$REPO_ROOT/release/install.sh" \
    || fail "install.sh 缺 STAND_HOME 第一优先级 base（STAND_BASE）"
for key in 'INSTALL_DIR="${INSTALL_DIR:-${STAND_BASE}/bin}"' \
           'local config_dir="${STAND_BASE}"' \
           'local manifest="${STAND_BASE}/install-manifest.json"' \
           'local jdk_dir="${STAND_BASE}/jdk-21"' \
           'local java_bin="${STAND_BASE}/jdk-21/bin/java"' \
           'JAVA_BIN="${STAND_BASE}/jdk-21/bin/java"'; do
    grep -qF "$key" "$REPO_ROOT/release/install.sh" || fail "install.sh 缺 base 派生点: $key"
done
grep -q 'STAND_BASE="${STAND_HOME:-${HOME}/${HOME_DIR}}"' "$REPO_ROOT/release/uninstall.sh" \
    || fail "uninstall.sh 缺 STAND_HOME base"
grep -q 'if (\$env:STAND_HOME)' "$REPO_ROOT/release/install.ps1" \
    || fail "install.ps1 缺 STAND_HOME base"
grep -q 'if (\$env:STAND_HOME)' "$REPO_ROOT/release/uninstall.ps1" \
    || fail "uninstall.ps1 缺 STAND_HOME base"

# -- 3. 零 ~/.nebflow 默认写点 -------------------------------------------------
for f in release/install.sh release/install.ps1 release/uninstall.sh release/uninstall.ps1; do
    grep -nE '^(HOME_DIR|\$HomeDir) *= *"?\.nebflow"?' "$REPO_ROOT/$f" \
        && fail "$f 把默认 home 钉回了 .nebflow"
    # 禁止 $HOME/.nebflow / $env:USERPROFILE\.nebflow 形态的路径字面（legacy
    # 根对安装器是只读历史，唯一合法出现是 setup_path 的退役正则——不含 $HOME 前缀）
    if grep -nE '\$\{?HOME\}?/\.nebflow|USERPROFILE.{0,4}\\\.nebflow' "$REPO_ROOT/$f"; then
        fail "$f 出现 legacy 根路径字面（HOME/.nebflow）——安装器永不写 legacy 根"
    fi
    # 禁止拿 NEBFLOW_HOME 当安装落点（legacy 只读属于运行时 paths.scala）；
    # 注释里的提及放行（本检查自身、脚本导语都在解释这条规则）
    if grep -n 'NEBFLOW_HOME' "$REPO_ROOT/$f" | grep -vE '^[^:]+:[[:space:]]*#'; then
        fail "$f 代码出现 NEBFLOW_HOME——安装器不读它当落点"
    fi
done

# -- 4. setup_path 退役旧 PATH 行 + 新行用 base --------------------------------
grep -q 'export PATH=.*\\.nebflow/bin' "$REPO_ROOT/release/install.sh" \
    || fail "install.sh setup_path 缺旧 ~/.nebflow/bin PATH 退役逻辑（再生根源）"
grep -qF 'local path_entry="${STAND_BASE}/bin"' "$REPO_ROOT/release/install.sh" \
    || fail "install.sh PATH 新行未从 STAND_BASE 派生"

# -- 5. 薄 nebflow 垫片转发到本安装的 wrapper（不指向 legacy 根）-----------------
grep -qF 'exec "${INSTALL_DIR}/${WRAPPER_NAME}"' "$REPO_ROOT/release/install.sh" \
    || fail "install.sh nebflow 垫片未转发本安装 wrapper"

echo "check-installer-home: OK (default home = \${STAND_HOME:-~/${HOME_DIR_NAME}}, legacy root read-only)"
