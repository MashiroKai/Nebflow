# 对照 fixture — 分段断言的判别力样本（`wdreplayfix` 批，2026-09-12）

`contrast-fixture.jsonl` 是 `WatchdogReplaySegmentationSpec` 的输入，**唯一目的是证明**
`WatchdogCriteriaReplaySpec` 的**分段断言**（R1′ 修法）**不是把断言改恒真**：
在含**窗口后新语料**的语料上，「旧形态（全语料一律断言旧判据保真）」判红、
「新形态（窗口前段旧判据保真 / 窗口后段新判据保真 / 全语料口径不变式）」判红项**只**命中注入的违反。

## 出处（可复现）

- 14 行**逐行原样**取自生产语料 `~/.nebflow/logs/watchdog/2026-09-12_events.jsonl`
  （`type=stuck-detected` 子集，**只读**；行内容零改写，除下面那一行注入）。
- 选取规则（`(sessionId, ts)` 精确匹配）与生成脚本见 impl 节点证据目录
  `.nebflow/evidence/20260912_211400_wdreplayfix-impl/gen-contrast-fixture.py`（**未提交**，scratch 申报）。
- 生成时刻语料 = 596 行 `stuck-detected`。fixture sha256 =
  `9bae1d8f641420d41531bec0a3ebdeaaea1ebe7370d6623965abff9d650c7667`。

| 段 | 行 | 出处 | 作用 |
|---|---|---|---|
| 窗口前段（ts < 1789216132324） | 6 行（`node-8d8e7f73` / `node-eb8a9c70` / `node-ce49b950` / `node-8920254c` / `node-35ad3b69` / `dispatcher-1f54460c`） | 旧构建文案（`still inside its authorised window` / `no progress signal (last=`） | 旧判据保真度自证（应 0 条不一致） |
| 窗口前段（出处不明） | 1 行（`r2-stuck-processing`，无工具相位分支） | 两构建**逐字相同** ⇒ 无出处标记 | 只计数、**不参与任何断言**（显式申报） |
| 窗口后段（ts ≥ 1789216132324） | 5 行：`node-f57766c0`×2（第 i 刀：超授权但仍在推进）、`node-a79dcc26`、`node-a07ed69c`×2（第 ii 刀：窗随授权联动） | 修复构建文案（`window Ns` 读数） | 新判据保真度自证 + 旧形态反向对照的判红来源 |
| 窗口后段（类① 真停摆） | 1 行（`node-6e7f475c`） | 修复构建文案 | 真卡死仍被捕获（`newTrue` 不归零） |
| 窗口后段（**注入违反**） | 1 行 | — | 见下 |

## 注入的违反（1 处）

`sessionId = node-fixture-fp2true`（`ts = 1789217002500`，由 `node-a79dcc26@1789216972430`
复制后**两处最小改动**）：

1. `sessionId` 改名（便于判读「命中的是哪一行」）；
2. `note` 删掉正信号读数 `progress signal 23s ago,`（保留 `window 360s` **出处标记**，
   否则该行会变成「出处不明」而被排除出断言——注入必须留在断言面内）。

⇒ 重放读数为类①（无新鲜正信号）而日志 `class` 仍是 `false-positive`
⇒ **`false-positive → true-stuck` 翻转 1 条**（口径不变式必须判红），
且该行同时命中「窗口后段: 新判据重放复现日志 class」（1 条）。

## 期望读数（`WatchdogReplaySegmentationSpec` 断言的原值）

| 读数 | 期望 | 出处 |
|---|---|---|
| 窗口起点（自校准） | `1789216132324`（首个 `window Ns` 出处行 = `node-f57766c0`） | `deriveWindowStart` |
| 窗口前段 行 / 参与断言 | 7 / 6（1 行出处不明） | `pre.rows` / `pre.asserted` |
| 窗口后段 行 / 参与断言 | 7 / 7 | `post.rows` / `post.asserted` |
| 前段旧判据不一致 | **0** | `pre.legacyMismatch` |
| 后段新判据不一致 | **1**（注入行） | `post.newMismatch` |
| 不变量：fp→true 翻转 | **1**（`node-fixture-fp2true@1789217002500`） | `fpToTrue` |
| 不变量：violations | 0 | `leakedTrueStuck` |
| **旧形态读数**（全语料一律断言旧判据保真） | **4 条**（第 i 刀 2 + 第 ii 刀 1 + 注入 1） | `allLegacyMismatch` |
| 前段注入一处正信号不一致后 | 前段判红 **1** 条并点名 `node-8d8e7f73` | 判别力实证（非空洞） |

**禁改口径**：新增/修改本 fixture 时必须同步更新 `README.md` 的期望读数表与
`WatchdogReplaySegmentationSpec` 的断言值——两者是**同一组读数**的两处载体。
禁止为了让某个用例变绿而删行、改 `class` 字段或把出入不明的行塞进断言行。
