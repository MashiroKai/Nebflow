# 文档命名迁移 · 收口报告（第三手接手，2026-11-04 18:44–19:0x）

接手背景：本批已两度 owner-gone。第一手做完规则源与存量改名（`16fe35377` / `ff7089b98`），第二手把 vault 239 篇改名并留 manifest，随后交接并拒绝合并。本手（第三手）做收口。

## 1. 提交边界（独立 revert 粒度，未再拆未再合）

```
7cdeb3495 feat(wiki-docs): check_wiki_docs 加 R5 doc-naming
925637496 docs(naming): 迁移资产入库（manifest/映射表/脚本/读数）
ff7089b98 refactor(naming): 存量重命名 —— reports-evidence 338 条改名
16fe35377 文档命名新制：规则源改写（topic 前置 / 时间块后置）
```

规则源（`16fe35377`）与存量改名（`ff7089b98`）是两个独立提交，边界按要求保留。

## 2. 旧制引用清理读数

| 项 | 读数 | 证据 |
| --- | --- | --- |
| 改写（指向已改名对象） | 27 处 / 19 篇 | `rewrite-log.tsv` 逐条 |
| 残留（有意保留） | 101 处 / 43 篇 | `residue-classification.tsv` 逐条带判据 |
| └ 跨树引用（repo `docs/`、`reports/`、从未入 vault 的名字） | 86 | 无映射可换 |
| └ 截断省略写法（含 `…`） | 8 | 非可解析路径 |
| └ 冻结迁移账本 | 5 | `docs-landing-ledger-migration*` |
| └ 旧→新对照行 | 2 | 旧名是新名同现的样本 |
| **违规（仍指向已改名对象）** | **0** | ← 验收线 |
| vault `.md` 时间打头 | **0** | `find … | grep -cE '^20[0-9]{6}'` |
| vault `.md` 新制命名 | 242 | 339 篇中（其余为活文档/无时间块） |

## 3. 断链读数（`link-scan.py`）

```
files 407 (md 339) / links parsed 1274
A. BROKEN : 2   ← 既有悬空引用，非改名引入（目标文档从未入 vault）
B. SUSPECT: 2   ← 裸目标（词条引用 / 未落库文档名）
C. ORPHAN : 86  ← 只给数，不算错
```

改名导致的断链 = 0（改写前 `design/*.html` 的 9 条旧制双方括号引用已归零）。

两条既有断链：

- `canvas-ask-resize-supps__20261104_121000__delegate-nebflow-bb1a8bbb.md:8` → `20261103_ui-unification-canvas`
- `dispatcher-chain-lifecycle-rca__20261101_165531__kernel.md:4` → `20261028_102700_delegate机制与数据同步现状`

## 4. R5 落地与反向验证

`check_wiki_docs.py` 加 R5 `doc-naming`（运行时副本 + seed 副本 `cmp` 一致，sha256 `32624244e543…`）：

```
python3 …/check_wiki_docs.py --project stand
→ wiki-docs 校验：通过（351 篇文档，零违规）   exit 0
```

反向验证（`/tmp/r5-verify`，四篇）：

```
20261104_120000_foo.md      → R5 红：旧制文件名（时间打头）
report__20261104_120000.md  → R5 红：topic「report」是空词
ok-topic__20261104_120000.md→ 不报
living-doc.md               → 不报（活文档无时间块合法）
exit 1
```

## 5. 其他项目（只扫不改）

`other-projects-pending.tsv`：DOSTEL 1 / link-server 1 / stand-website `docs` 3 / 视觉工作室 3，共 8 篇待各自迁移；stand-website `.stand/docs` 已 0。本批对它们零写入（脚本作用域硬编码为 stand vault）。

## 6. 主仓门禁与合并

```
node scripts/check-component-contract.mjs  → exit 0（零新增，均不超基线）
node scripts/check-circular.mjs            → exit 0（236 files, acyclic）
git merge --no-ff delegate/delegate-nebflow-71767a56
→ 2996e6fbb  双亲 f3bc0a7be (main) + 462ea139f (branch)
git merge-base --is-ancestor delegate/delegate-nebflow-71767a56 HEAD → YES
```

## 7. 并发事实（记账）

- 18:54 第三方批把 `docs-migration-sha256-table` 150 行旧名改写为新名；本批对该文件显式跳过（冻结账本），改动非本批所为，可按 `rename-map.tsv` 反查还原。
- vault 文档数在收口期间 337 → 351（另两批并发落文档，均已按新制命名）。
- `reports-evidence/20261104_design-scale-migration/` 是并发批新落的旧制目录名，本批不改他人产物。
- 另一批在 stand-website `.stand/docs` 18:55 写入 6 篇（非本批）。

## 8. 回滚

vault 无 git ⇒ 回滚依 `manifest.md` §7（239 行）+ `rename-map.tsv` 反向映射；`reports-evidence/` 侧另有 `git revert ff7089b98`；引用侧按 `rewrite-log.tsv` 反向替换。
