# Grep 工具 xerj 借鉴优化提案（一页）

批次：记忆检索工具批 · Grep 优化子项 · 2026-10-21 · delegate-nebflow-c00e0fd9
对象：平台 Grep 工具（引擎侧 `GrepTool.scala` 254 行 + `RgHelper.scala` 210 行，ripgrep 子进程封装）。
参照：xerj（xerj-org/xerj，Rust 本地检索引擎；本次直接拉仓分析，无既有报告，详见主报告 §3）。

## 一、xerj 可借鉴技术盘点（对 Grep 工具面）

| xerj 技术 | 对 Grep 的适用性 |
|---|---|
| BM25 相关性排序（核心产出：ranked hits，非裸行号） | rg `files_with_matches` 按目录遍历序返回，对 agent 无信息量；命中面大时「哪个文件最相关」要靠 agent 自己再摸 |
| 命中片段呈现（passage 带上下文，不隐身） | rg `--max-columns 500` 超限行默认**整行隐藏**（`[Omitted long matching line]`），CJK 长行（日志/JSONL/会话导出）命中常被完全抹掉 |
| CJK bigram 分词 / 前缀 / fuzzy / wildcard 查询型 | rg 正则面已全覆盖且更快（SIMD 字面优化），无增益 |
| 持久索引 + WAL/autoindex 加速 | 见「不采用」② |

## 二、本批已落地（小步、带测试，`GrepToolRankedSpec` 6/6 绿）

1. **`ranked` opt-in 参数**（files_with_matches 模式，默认 false 零回归）
   xerj「检索即排序」的最小移植：同一次 rg 遍历改走 `--count-matches`，命中计数降序**稳定**排序（同计数保持 rg 原序），输出剥回纯路径行——wire 契约零变化。命中面大时最相关文件排最前。纯函数 `parseCountLine`/`rankCountLines` 抽出直测 + 真 rg 端到端。
2. **`--max-columns-preview`**
   xerj「命中不隐身」原则：超 500 列命中行给截断预览 + 标记，而非整行抹掉。只影响超长行形态，无契约变化。CJK 长行场景直接受益。

## 三、不采用 / 候批（理由写明）

1. **content 模式 BM25 行级排序**：破坏 `-A/-B/-C` 上下文契约（上下文以行序为纲），rg 无打分通道，后处理排序代价 > 收益 → 不采用。
2. **持久索引加速 Grep**（xerj autoindex 思路）：新鲜度是正确性风险（索引滞后 = 漏命中，比慢更糟）；仓库级 rg 全扫已是毫秒-秒级；且索引守护进程与实例架构冲突 → 不采用。「建库式检索」职责已由本次 `stand-recall`（对话语料）承担，各归其位。
3. **`--smart-case` 默认开**：全小写 pattern 自动大小写不敏感。对代码搜索是噪声放大（`error` 会淹没 `Error` 语义），改变全体 agent 的既有行为面 → **候批**（作者点头则一行改动 + 一个回归用例）。
4. **神经/向量检索、外部 rerank、ES API/MCP 化**：90MB 模型依赖、数据出机红线、常驻服务形态 → 全部不适用于 Grep 工具；向量面属 `stand-recall` 未来 opt-in（见主报告 §3 未采用表）。

## 四、验证口径

- `sbt "testOnly nebflow.core.tools.GrepToolRankedSpec"`：纯函数 4 例 + 真 rg 端到端 2 例（marker 断言对 rg 新旧版本文案差异免疫：新 `[Omitted long matching line]` / 旧 `[... omitted end of long line]`）。
- 行为零回归锚点：`ranked` 缺省时走原 `--files-with-matches` 路径（同 spec 内断言默认输出不变）。
