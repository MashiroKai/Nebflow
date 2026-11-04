# 20261104 root-migration-rehearsal — 证据目录地图

本目录是「B1 三层数据根 · 真机迁移演练」的全部可复核材料。**先读 `REPORT.md`**，本文件只说明「东西在哪、怎么复跑」。

## 结论入口

| 想核什么 | 看哪 |
|---|---|
| 今天重启会不会动真数据（**不会**） | `REPORT.md` §0 + `data/no-wiring-proof.txt` |
| 正向/幂等/回滚读数与 sha256 逐条结论 | `REPORT.md` §3 + `logs/06-phase-b.log`、`logs/06-compare.log`、`logs/07-*.log` |
| 风险清单（F1–F8） | `REPORT.md` §4 |
| 双写/句柄/写丢失判定 | `REPORT.md` §5 |
| 今天就能重启的 checklist | `REPORT.md` §6 |
| 接线后重启/迁移 checklist | `REPORT.md` §7 |
| 真根零改动证明 | `REPORT.md` §9 + `logs/08-realroot-diff.log` |

## 脚本（可复跑，按序）

| 脚本 | 作用 |
|---|---|
| `fingerprint.py` | 全树指纹（每文件 sha256，独立于被测 JVM）；`--aggregate` 复刻 `RootLayout` 的条目摘要算法，用于与 `migration-report.json` 逐条对照 |
| `01-copy.sh` | rsync 真根 → 副本（排除清单与理由写在脚本头；真根只读） |
| `03-census-repair.py` | 符号链接普查（断链/环/socket/越界）；`--apply` 只清理**副本** |
| `04-diff-tsv.py` | 两份指纹清单对比（changed/added/removed，可忽略布局受管路径） |
| `02-compare.py` | `migration-report.json` × python 清单 × 产物目录三方核对（逐文件摘要，双向完整性）——**逐文件面权威** |
| `07-entry-digest.py` | 按 `RootLayout` 的精确方案重算**条目级**摘要并与报告对照（从 TSV 推导，副本删掉后仍可复跑）——**条目级面权威**；`fingerprint.py --aggregate` 只是近似（文件条目/空目录两个边界不复刻） |
| `05-realroot-diff.py` | 真根 before/after 对比 + 差异归属分类 |
| `05-phase-a.sh` | 阶段 A：普查 → 基线 → **在未去风险的副本上跑 migrate（预期中断）** → 包容性 diff |
| `06-phase-b.sh` | 阶段 B：清理 → 去风险 → 基线 → **完整 migrate** → 三方核对 → rollback → diff |

## 数据与日志

- `data/` —— 驱动源码（**临时文件，未入库生产代码**，仅供复核）、`no-wiring-proof.txt`（零调用点证据）、`real-root-symlink-census.json`（真根 2,500 条链接的普查明细）
- `logs/` —— 每一步的原始输出；`*-run1-javaquote-bug.log` 是第一次阶段 B 因脚本路径未加引号（目录名含空格）而失败的留痕，**不是**产品缺陷
- `logback-driver.xml` —— 演练 JVM 专用日志配置（只出 console）：生产 `logback.xml` 会往 `${STAND_HOME:-~/.stand}/logs/stand.log` 追加，直接用会写进真根

## 大件清单（仓库外，避免把 140 万行塞进 git）

路径 `/Volumes/KINGSTON/rehearsal-evidence/`：

| 文件 | 内容 |
|---|---|
| `real-root-before.tsv` / `real-root-after.tsv` | 真根前后全量指纹（各 ~140 万个对象） |
| `real-root-before-agg.json` / `-after-agg.json` | 真根按顶层条目的聚合摘要（159 条） |
| `replica-baseline.tsv` / `replica-clean.tsv` | 副本基线指纹（未去风险 / 去风险后） |
| `replica-after-crash.tsv` / `replica-after-rollback.tsv` | 中断后 / 回滚后指纹 |
| `replica-clean-agg.json` | 副本按顶层条目的聚合摘要（与 `migration-report.json` 对照用） |
| `toplist.txt` / `rsync-excludes.txt` | 副本的条目白名单与排除清单 |
| `classpath.txt` | 驱动运行用的测试 classpath |

副本与迁移产物所在的 APFS 卷（sparse bundle，宿主外置盘）。**演练结束时已卸载**，bundle 仍留在盘上（占 **87 GB**）；确认不需要再复核后可整份删除：

```
# 若已卸载就不用再 detach
hdiutil attach /Volumes/KINGSTON/nebflow-root-rehearsal.sparsebundle
hdiutil detach /Volumes/rehearsal
# 彻底删除副本（证据清单在 KINGSTON/rehearsal-evidence 里，不受影响）
rm -rf /Volumes/KINGSTON/nebflow-root-rehearsal.sparsebundle
```

另存了 `migration-report.sample.json`（109 MB）= 副本迁移产出的**真实记账文件**原件，供复核格式与规模（真机规模约 2.3 倍）。

## 复跑方式（不改生产代码）

```bash
cd <worktree>
# 1) 临时放回驱动（每次用完即删，勿提交）
cp reports-evidence/20261104_root-migration-rehearsal/data/RehearsalDriver.scala \
   src/test/scala/nebflow/core/dataroot/
sbt -batch "Test/compile" "export Test/fullClasspath" > /tmp/cp.log
CP=$(grep -E "^/Users/.*test-classes:" /tmp/cp.log | tail -1)
# 2) 合成场景（秒级，验证各分岔与符号链接风险）
STAND_HOME=/tmp/x NEBFLOW_HOME=/tmp/x java -Xmx2g \
  "-Dlogback.configurationFile=$PWD/reports-evidence/20261104_root-migration-rehearsal/logback-driver.xml" \
  -cp "$CP" nebflow.core.dataroot.RehearsalDriver synth /tmp/synth-root
# 3) 用完删除临时文件
rm src/test/scala/nebflow/core/dataroot/RehearsalDriver.scala
```

> **铁律提醒**：本目录所有脚本都把真根当只读源。不要在真根上跑 `migrate` / `rollback`；不要用生产 `logback.xml` 跑驱动（会写 `~/.stand/logs`）。
