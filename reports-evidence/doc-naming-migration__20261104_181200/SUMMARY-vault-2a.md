# 阶段 2a 执行/复核摘要：stand vault 旧制改名 + 引用重写

- 范围：`/Users/kaiyu/.stand/projects/stand/.stand/docs`（含 `design/` `evidence/` `issues/` `reviews/`）
- 映射唯一来源：`manifest.md` §7 逐行迁移表（239 行，scope=vault）
- 状态：**已完成并复核**（改名 239 / 回滚 0）

## 读数

| 项 | 值 |
| --- | --- |
| 改名 | 239（旧名残留 0 / 新名缺失 0） |
| 回滚 | 0 |
| 新名冲突 / 语言段违规 | 0 / 0 |
| wikilink 改写 | 649 |
| embed 改写 | 0（vault 内无 `![[ ]]` 嵌入） |
| markdown 链接改写 | 2 |
| index.md 内改写 | 41（手写索引，机械改链接，未重排） |
| html 样张内 wikilink 改写 | 9（5 个文件，非 .md 资产本体不改名） |
| 迁移后残留断链（改名导致） | **0** |
| 迁移前基线断链 | 57 occurrences / 48 targets（词条示例链接等存量） |
| 日级占位（`_000000`） | 29 |
| 4 位 HHMM 补 `00` | 3 |
| `check_wiki_docs.py --project stand` | 通过，349 篇，零违规 |

## 逐篇等价证明

把 rename-map 施加到迁移前快照（`/tmp/pre-2a/docs`，取自 18:36 备份）后与磁盘逐字比对：
333 篇完全相同；3 篇不一致，均为**迁移后被其他节点续写正文**（`ui-batch-delivery-audit`、
`presence-direct-root-cause`、`mergequeue-three-gaps-r2-r1-r5`），其链接改写部分与预测一致。

## 唯一新增悬空（非本阶段引入）

`design-scale-migration__20261104_184032__delegate-nebflow-7ecba82c.md` 的 `[[design-language-tokens]]`：
该文档 18:40:32 由其他节点新建，vault 内从无同名文档（迁移前快照亦无），属其作者待补的引用，
不改名所致，未代为改写。

## 工具

- `rename-vault.py` — vault 专用改名器（`--check` / `--apply` 幂等 / `--emit-map` / `--rollback <新名>` / `--rollback-all`）
- `rewrite-links.py` — 阶段 2 引用重写器（`.md` 正文）
- `rewrite-links-assets.py` — html 样张内 wikilink 文件名改写（只改文件名）
- `verify-rename-vault.py` — 回归验证（存在性 / 冲突 / 逐篇等价 / 断链基线对比 / 29+3 清单）
- `verify-links.py` — 链接健康度只读扫描
- `rename-map.tsv`（vault+reports-evidence 全表）、`rename-map-vault.tsv`（本阶段 239 行）
- `verify-vault.json`、`links-before.json`、`links-after.json`、`links-final.json`

## 回滚点

- `/tmp/doc-naming-backup-20261104_183642.tgz` — 改名/改写前（`tar xzf ... -C <dir>` 得 `docs/`）
- `/tmp/doc-naming-backup-20261104_184431.tgz` — 改名后、html 引用修复前
