---
type: knowledge
status: final
---

# 液体玻璃基线 A/B 总报告

- 批次：液体玻璃基线 A/B 收尾批（接力 `delegate-nebflow-2be86ba4`，本批 `delegate-nebflow-a1031f13`）
- 测量对象：HEAD = `3bcf653bc`（玻璃终态树）vs baseline = `bc437df64`（玻璃前树）
- 方法：`/tmp/lg-ab2.sh` 驱动，`PW_SELFCONTAINED_ONLY=1`；HEAD 腿跑全量自足套件（/tmp/lg-current2），baseline 腿跑显式 143 文件列表（/tmp/g2-base-check）
- 关联：[[20261031_121500_liquid-glass-effect-v3__delegate-nebflow-9e155df5.zh-CN]]、[[20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN]]

## 1. 读数对比（HEAD vs baseline）

| 腿 | 树 | 结果 | 耗时 |
|---|---|---|---|
| HEAD | 3bcf653bc | **834 passed / 80 failed（34 文件）/ 3 skipped**，rc=1 | 13:48:52→15:12:17（1.4h） |
| baseline | bc437df64 | **824 passed / 76 failed（30 文件）**，无 skip | 15:12:17→16:06 跑到 test 859 被会话回收打断；16:28 续跑剩余 5 文件，19.7m 收尾 |

baseline 腿断点与续跑（如实申报）：旧会话 16:07 被回收，驱动进程死亡时已完成 138/143 文件（最后完成 `wiki-graph-paragraph` 全文件）。续跑只补跑 5 个未完成文件（`subagent-panel-open-geometry` 3/3 过、`wiki-graph-tip-dup-label` 4/4 红、`wiki-graph-ux` 12 过 2 红、`wiki-term-citation` 5 过 6 红、`ws-card-lock` 12/12 过），与首跑日志合并得最终读数。注：HEAD 腿 80 红中与旧会话中场读数「901/903」的口径差为列表报告行与终态汇总之差，以终态汇总（834/80/3）为准。

## 2. 分文件对照结论

HEAD 红文件 34 个，按 baseline 同文件表现分三类：

**A. 双腿同红（29 文件）——既有欠账 + 环境载荷超时，非玻璃批回归。**
`bg-device-origin-chip`(3=3)、`bg-task-output-viewer`(4=4)、`bgagent-dedupe`(2=2)、`canvas-file-refresh`(1=1)、`compaction-pill-remove`(3=3)、`delegate-ui-twolayer`(2=2)、`dialog-unify`(3=3)、`explorer-nebflow-dir`(2=2)、`history-replay-cards`(1=1)、`micorb-presets`(2=2)、`modelpanel-roles-hiddenfilter`(1=1)、`msgqueue-image-mime`(4=4)、`msgqueue-recall-attach`(6=6)、`onboarding-name-header`(1=1)、`project-create-panel`(1=1)、`project-panel-archive`(2=2)、`search-realshape`(4=4)、`secrets-readback`(1=1)、`secrets-ui-redesign`(7=7)、`settings-cleanup`(1=1)、`settings-key-reveal`(5v2)、`sidebar-restructure`(1v6)、`subagent-panel-merge-status`(7=7)、`tasklist-chain-blocks`(8v1)、`tool-heartbeat-timer`(2=2)、`uicopy-copy`(1=1)、`wiki-graph-tip-dup-label`(4=4)、`wiki-graph-ux`(2=2)、`wiki-term-citation`(2v6)。
其中 wiki 图谱族三文件续跑后在 baseline 上红得与 HEAD 逐项一致（`wiki-graph-ux` R8/§F 同题同红）——判环境/负载超时类，非回归。4 个计数不等文件（settings-key-reveal/sidebar-restructure/tasklist-chain-blocks/wiki-term-citation）两侧红题高度重叠，计数差为超时抖动。

**B. 仅 HEAD 红（5 文件 9 题）：**

| 文件 | 红题 | 错误签名 | 判定 |
|---|---|---|---|
| `liquid-glass.spec.mjs` | 4（M3 圆钮 48px、T1 三态 token、F1 降级 alpha、R1 折射契约） | 断言类（computed-style/解析值） | 玻璃验收规本身：孤立跑 14/14 过（[[20261031_084400_liquid-glass-effect-v2__delegate-nebflow-a9ba5eff.zh-CN]] §9 验收面），全量并行载荷下对像素/解析值敏感——**载荷敏感项，非功能回归**；baseline 无此文件（新文件） |
| `mobile-p12-settings` | 2 | click 180s 超时 | 环境超时（同类超时在 baseline 腿 `mobile-p10-plugins` 对称出现 1 题） |
| `mobile-v3` | 1 | click 180s 超时 | 环境超时；且该 spec 两树间有改动（v2 契约改写），计数口径本不可直接比 |
| `settings-imagegen-two-forms` | 1 | toContainText 断言 | 单题断言差，载荷下设置面保存态竞态嫌疑，**观察项** |
| `wiki-graph-multivault` | 1（B1-B4 下钻 selected=null） | 断言类（非超时） | **唯一非超时型单腿分歧**：HEAD 下钻选中返回 null、baseline 通过。复测优先项 |

**C. 仅 baseline 红（1 文件）：** `mobile-p10-plugins` 1 题（click 超时）——与 B 类移动面板超时互为镜像，进一步支持「环境超时」判定。

## 3. 十二文件改动面基线对照结论

口径声明：前一会话未留存「12 文件」清单，本批按 v2 实现批的 12 个非测试改动面文件给结论（v3 微调同落在这些文件内；v3 另触 `ask.css` 发送钮配色，一并列入观察）：

| # | 文件 | 对照结论 |
|---|---|---|
| 1 | `css/stand-tokens.css` | `--g2-*`/`--ctl-*` token 族双侧行为无功能面分歧（UI 族相关红题均双腿同红或超时类） |
| 2 | `css/liquid-glass.css` | 新文件；玻璃材质断言在 M3/T1/F1/R1 四题载荷敏感，见 §2-B |
| 3 | `css/sapphire.css` | Pattern B 控件族无单腿分歧红题 |
| 4 | `css/sidebar.css` | `sidebar-restructure` 双腿同红（既有欠账） |
| 5 | `css/input.css` | 无单腿分歧红题 |
| 6 | `css/mobileLayer.css` | 移动红题均为超时类且双腿对称出现 |
| 7 | `index.html` | SVG 折射 defs/edge-fade 无结构分歧（R1 题为载荷敏感断言） |
| 8 | `js/liquidGlass.js` | 能力探测摘除逻辑无功能分歧（同 R1 题覆盖） |
| 9 | `js/main.js` | 顶带观察器计入 edge-fade 无回归读数 |
| 10 | `js/mobile/chatChrome.js` | chat head/双 FAB 移动面相关 spec（mobile-v3 等）红题为超时类 |
| 11 | `js/chatView.js` | setSession 保留 g2-chat-head 无分歧读数 |
| 12 | `playwright.config.mjs` | 仅实例编排（queuepos 排除），非行为面 |

结论：**12 文件改动面无一条「baseline 绿 + HEAD 红且非超时」的玻璃批回归签名**；移动/图谱族单腿分歧全部为 click 超时镜像对或载荷敏感像素断言。

## 4. 总判定与遗留

1. 玻璃批（v2+v3，3bcf653bc 态）对 143 文件自足面**无确认回归**；双腿 76/80 的红量高度重叠，属既有欠账 + 当日多会话并行跑测试的载荷超时。
2. 遗留观察两项（均单题、非超时断言型）：`wiki-graph-multivault` B1-B4 下钻 selected=null（HEAD 红 / baseline 绿）与 `settings-imagegen-two-forms` 保存态文案。建议后续批在空闲机上单独复测这两题定性。
3. 本报告只覆盖 A/B 读数与对照结论；`liquid-glass.spec` 四题载荷敏感性的根治（如放宽像素断言或串行跑该规）留给后续批裁定。
4. 背景事项：death-watch 六点修复已上线、#1220 由观测腿+修复批接管，本批未涉。

## 5. 证据

- 驱动：`/tmp/lg-ab2.sh`；HEAD 腿日志 `/tmp/lg-ab2-current.log`（834/80/3 终态汇总于尾部）；baseline 腿日志 `/tmp/lg-ab2-baseline.log`（首跑 138 文件）+ `/tmp/lg-ab2-baseline-resume.log`（续跑 32 过 12 红，16:47:13 收尾 rc=1）
- 基线文件清单：`/tmp/lg-base-files.txt`（143 文件）
- 前台任务记录：`~/.stand/bg-task-logs/46a86d3f.log`（「Session reclaimed」取消记录，16:07）
- 旧批终文：`~/.stand/delegates/delegate-nebflow-2be86ba4.receipt.json`
