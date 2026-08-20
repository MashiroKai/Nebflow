#!/usr/bin/env bash
# 死日志门禁：src/main 的 Scala 源码禁止 NebulaLogger 双包装形态。
#
# 病根（qwen 批 qa 打回实录 2026-08-20 + 死日志专项 2026-08-21）：
# NebulaLogger 的 info/warn/error/debug 返回 IO[Unit]——再包一层
# IO(...) / IO.delay(...) 得到 IO[IO[Unit]]，外层运行后内层被丢弃，
# 日志永不执行（编译仍通过：handleErrorWith 经 B=Any 型擦除吞掉类型错位）。
# 全部出现在错误恢复路径 = 出问题时静默无日志。
#
# 禁模式：
#   IO(logger.info( / IO(logger.warn( / ...     — 局部 val logger 双包装
#   IO.delay(logger.info( / ...                 — 同上 delay 形态
#   IO(NebflowLogger    / IO.delay(NebflowLogger — inline 形态（含换行后的
#                                              链式调用，本 grep 命中起始行）
#   IO(nebflow.core.NebflowLogger               — 全限定 inline 形态
#
# 豁免：
#   src/main/scala/nebflow/core/logging.scala — NebulaLogger 定义处
#   IO(logger.*Sync(...)) — Sync 变体返回 Unit，IO 包装是正确用法
#   （模式以 `IO(logger.info(` 等具体方法名锚定，Sync 后缀天然不匹配）
#
# 范围：src/main 下 *.scala。CI 与本地同逻辑：ci.yml dead-logging-gate
# job 调用本脚本。本地复现：bash scripts/check-dead-logging.sh
#
# 已知边界（本门禁不覆盖，人工审查域）：
#   - 纯表达式块内的裸语句丢弃（logger.info(...) 后跟下一表达式）——
#     多行语句位不可靠 grep；修复形态=Sync 变体或 *> 序列化
#   - 未序列化的 IO 值经 `=` 丢弃等更深的 built-not-run 形态
set -euo pipefail
cd "$(dirname "$0")/.."

PATTERN='IO\(logger\.(info|warn|error|debug)\(|IO\.delay\(logger\.(info|warn|error|debug)\(|IO\(NebflowLogger|IO\.delay\(NebflowLogger|IO\(nebflow\.core\.NebflowLogger'

HITS=$(grep -rInE "$PATTERN" --include='*.scala' src/main \
  | grep -v 'src/main/scala/nebflow/core/logging.scala' || true)

if [ -n "$HITS" ]; then
  echo "✗ dead-logging gate FAILED — NebulaLogger double-wrap in src/main:"
  echo "$HITS"
  echo ""
  echo "NebulaLogger.info/warn/... already return IO[Unit] — wrapping them in"
  echo "IO(...) / IO.delay(...) builds IO[IO[Unit]] and the inner IO is never"
  echo "run (dead log). Drop the outer wrapper and chain the call into the IO"
  echo "chain (*> / .as / handleErrorWith fallback), or use the *Sync variant"
  echo "in pure-expression positions."
  exit 1
fi

echo "✓ dead-logging gate clean: no NebulaLogger double-wrap in src/main"
