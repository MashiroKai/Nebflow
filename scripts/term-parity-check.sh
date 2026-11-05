#!/usr/bin/env bash
# 跨语言一致性断言（term-system v2 · ②）——同一个 fixture vault 上，
# Scala `WikiIndex.termsOf` 与抽取出口 `check_wiki_docs.py --dump-terms` 的
# (name, rel, anchor, tags) 必须逐条相等。
#
# 两步：
#   1) Scala 侧已由 spec 落盘：sbt "testOnly nebflow.core.wiki.TermParitySpec"
#      → target/term-parity-scala.json
#   2) 本脚本跑 python dump → target/term-parity-python.json，然后规范化比对。
#
# 用法：scripts/term-parity-check.sh     （退出码 = 比对结论；2 = 前置文件缺失）
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIXTURE="$ROOT/src/test/resources/wiki-term-parity"
PY_SCRIPT="$ROOT/src/main/resources/seed/plugins/visual-report/skills/wiki-docs/scripts/check_wiki_docs.py"
SCALA_JSON="$ROOT/target/term-parity-scala.json"
PY_JSON="$ROOT/target/term-parity-python.json"

if [ ! -f "$SCALA_JSON" ]; then
  echo "MISSING $SCALA_JSON — 先跑: sbt \"testOnly nebflow.core.wiki.TermParitySpec\"" >&2
  exit 2
fi
if [ ! -d "$FIXTURE" ]; then
  echo "MISSING fixture vault: $FIXTURE" >&2
  exit 2
fi

mkdir -p "$ROOT/target"
python3 "$PY_SCRIPT" --root "$FIXTURE" --dump-terms --json > "$PY_JSON"
rc=$?
echo "PYTHON_DUMP_EXIT=$rc"
if [ "$rc" -ne 0 ]; then
  echo "python dump 失败（见 $PY_JSON）" >&2
  exit 2
fi

python3 "$ROOT/scripts/term-parity-compare.py" "$SCALA_JSON" "$PY_JSON"
exit $?
