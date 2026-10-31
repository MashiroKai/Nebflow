---
type: audit
status: done
related: "[[20261029_131610_plugin-protocol-audit__delegate-nebflow-14e75897.zh-CN]]"
---

# stand-dev 开发插件统一合并批（2026-10-31）

> 批令（作者 10-31 22:2x）：现在装了很多用于开发 stand 的插件——qa、前端、后端、nebflow 等——统一成一个「stand 开发插件包」。本稿 = 全批留痕：盘点、方案、实施、验证、卸除记录与开放裁定点。

## 1. 盘点结论

盘点方法与装载判据同插件协议盘点 [[20261029_131610_plugin-protocol-audit__delegate-nebflow-14e75897.zh-CN]]（§1 协议面）。

本实例 23 包中 **6 包属 stand 开发向**，合并为 `stand-dev`；其余 17 包（academic-research、browser-use、computer-use、dbm-memory、design-cards、design-spec、document-production、engineering-methods、explorer-toolkit、imagegen、learn-anything、phd-note、slideblocks、thesis-review、visual-report、web-search-toolkit、website）不动。

| 旧包 | skills | 去向 |
|---|---|---|
| qa | nebflow-qa-backend / nebflow-qa-frontend / frontend-verification / isolated-smoke-verification / verification-rigor | stand-dev **qa 章**（references/qa/ 5 份） |
| frontend-dev | nebflow-frontend / visual-style / design-system | stand-dev **前端章**（3 份） |
| backend-dev | nebflow-backend / nebflow-cache / nebflow-tool-dev | stand-dev **后端章**（3 份） |
| docs-prompt | nebflow-docs / nebflow-prompt-engineering | stand-dev **引擎章**（7 份之二） |
| pipelines | merge-pipeline / release-pipeline / review | stand-dev **引擎章**（之三） |
| nebflow-plugin-creator | plugin-packaging / description-quality + validate_plugin.py + assets | stand-dev **引擎章**（之二）+ scripts/ + assets/ |

## 2. 新包结构

```
stand-dev/
  plugin.json            五段式 description（380 字符，M07 过闸）
  Plugin.md              包根使用指导件（分配注入面）
  skills/stand-dev/
    SKILL.md             四章总纲（章导览 + 核心指令摘要 + 决策树）
    references/{qa,frontend,backend,engine}/*.md   18 份 = 原 skill 全文迁入
    scripts/validate_plugin.py                     自 creator 迁入（引用链最短取舍）
    assets/{manifest.template.json, pluginmd.template.md, skill-template/}
```

- **能力零丢失口径**：18 份 references 为原六包 SKILL.md 逐字迁入（diff 可证），仅两类字节级改动——①跨包引用改写（原 `nebflow-qa`/`frontend-dev` 插件名等 → stand-dev 章指向）；②每件头部加迁址说明段。`validate_plugin.py` 的 OFFICIAL_BASELINE / OFFICIAL_FACE 名单同步收敛（`nebflow-plugin-creator` → `stand-dev`）。
- **脚本取舍说明**：validate_plugin.py 与三份模板迁入包内（`${SKILL_DIR}/scripts|assets/` 引用链最短；原包已卸除，无留原位引用的必要）。脚本内「自脚本位置回溯 4 级」逻辑在新布局同深（scripts → stand-dev → skills → 包 → plugins），零改动可用。

## 3. 卸除记录（留痕）

- **运行时**：`~/.stand/plugins/{qa,frontend-dev,backend-dev,docs-prompt,pipelines,nebflow-plugin-creator}` 已删；卸除前逐字节备份在 `~/.stand/plugins-backups/<ts>_pre-stand-dev-merge/`（含 `UNINSTALL-RECORD.md` 与 6 份 plugin-sources 台账归档件）。`nebflow.json plugins.trust` 中 creator 的审批记录按历史留痕原则未动。
- **种子面**：仓内 `src/main/resources/seed/plugins/` 六目录同步删除；`seed/manifest.json` 默认预装集 `nebflow-plugin-creator` → `stand-dev`（集合大小与语义不变）。
- **引用面**：`AGENTS.md` 第 289 行执行面副本路径改为 stand-dev references；design-spec / document-production / engineering-methods / explorer-toolkit / learn-anything / website 六包内 description 与 Plugin.md/SKILL.md 的「另配 nebflow-* 插件」全部改写为 stand-dev 章指向（runtime 与 seed 双副本同改）；`~/.stand/memory/`（history/queue jsonl）与 `chains/` 台账中的旧名属历史记录，按不回首改史原则未动（live memory 面 User.md/Soul.md/agents/prompts/flow-map 零命中）。

## 4. 测试面改动（4 个 seed spec）

- `SeedManifestCoverageSpec`：ExpectedDefaultPlugins / K9-c 标题同名替换。
- `SeedServiceSpec`：DefaultPlugins 名单替换；非默认集样本名单换为现种子树实有包（design-language / design-cards 替代 qa / frontend-dev）。
- `SeedPluginReconcileSpec`：⑥ 自愈用例 creator → stand-dev；⑦ 面积名单同步；**②b 改写**——原「官方保留名包 + 陈旧信任记录 ⇒ 出厂字节赢」死锁回归钉的 fixture 是种子树里的 creator，seed 树内再无 `nebflow-` 前缀包后该场景在 classpath 夹具上结构性不可复现，改写为替代锁：allowlist 零保留前缀包 + admits 对任意保留前缀名恒 false（fail-closed，死锁路径结构性关闭）。
- `SeedPluginDeletionMarkerSpec`：Target creator → stand-dev；③ 的「官方包镜像回出厂树」断言随 namespace 退役改写为非保留名包「用户版本保留」断言。
- 机制代码（OfficialPackages / SeedService）**零改动**。

## 5. 验证

- `validate_plugin.py --profile creator stand-dev`：**PASS**（32/32；description 380/400 字符过 M07；C17 软顶 200 超线为统一包固有，记为可接受提示）。
- `tools/stand-plugins list`：23 → **18 包**，stand-dev dispatchable 在列，六旧包消失。
- seed 四 spec sbt 测试：见本节交付报告（随批提交回归）。
- 真实小任务走新包：派子代理按 SKILL.md 引擎章 plugin-packaging 流程用新包内脚本校验一个真实包目录（自检吃自己的狗食）。
- 双副本：runtime `~/.stand/plugins/stand-dev/` 与仓内 seed `src/main/resources/seed/plugins/stand-dev/` 逐字节一致（diff 零输出）。

## 6. 开放裁定点（请作者过目）

1. **官方 namespace 空窗**：seed 树现无任何 `nebflow-` 前缀包 ⇒ OfficialPackages 出厂字节赢分支无生产载体，②b 死锁回归钉暂搁（替代锁已钉 fail-closed 结构）。恢复条件 = 下一枚官方包进种子树时重建该用例。
2. **website 包未并入**：它服务 mystand.space 官域而非 stand 本体，按批令「qa/前端/后端/nebflow/文档/测试类」范围保留独立；若作者认为官网开发也属 stand 开发面，可下批并入（加「官网章」）。
3. **memory/chains 历史记录**中的旧插件名未回改（历史层不回写原则）；若 stand-recall 检索会把这些旧名当作活口径推荐给 future agent，建议由 memory-consolidator 在后续压缩轮中以新名重述。
