# wiki-graph 多项目图谱批（#744-B）· 批报告

- 台账：#744-B 图谱演进 B 批（stand 内置 wiki-graph 多项目图谱）
- 分支：delegate/delegate-nebflow-56930fbd
- 提交序列：设计 3a62eb696 → 实现 675889ee3 → spec+证据 edf90e75f → 门禁微修 8eb9abfef → 报告 4adf3a7fa → 命名空间修复 2f10ffd63
- 设计正本：design/20261022_190000_multivault-wiki-graph__delegate-nebflow-56930fbd.md
- 验收报告：evidence/20261022_wiki-graph-multivault__delegate-nebflow-56930fbd/验收报告.md
- 截图证据：evidence/20261022_wiki-graph-multivault__delegate-nebflow-56930fbd/shots/（12 张，亮暗 × 5 态）

## 做了什么

**引擎侧**（WikiIndex.scala / WikiService.scala / WikiRoutes.scala）
1. vault 发现式注册：项目挂载即注册 `<workspace>/docs`；零新配置字段。
2. 子 vault 约定发现（`SUB/docs` 直接子目录，递归 ≤3 层、≤32 个/vault，
   预算常量 MaxSubVaultDepth/MaxSubVaultsPerVault）；本地扫描整枝剪除子 vault
   根（per-vault 隔离），远端 best-effort 一层探测（probeRemoteSubVaults）。
3. API：`?project=`/`?vault=` 视图参数 + `GET /api/wiki/vaults` 轻量清单；
   每 vault 独立缓存键 + 既有 TTL/构建串行旗标；`?project=__user__` C 批预留。
4. 跨 vault 引用 = `external:true` 占位（WikiIndex.externalNode /
   classifyExternals 纯函数；本地视图按兄弟 vault rel 集分类；远端 v1 不分类，
   设计 §6 备案）；不画、不进断链、计 `stats.crossVault`。
5. 单视图响应新键：view（project/vault/breadcrumb）/childVaults/stats；
   合并视图响应形状逐字节兼容（graph(None) 原.assemble 原样保留）。

**前端**（wikiGraph.js / locales / split.css）
6. 项目切换器（.wg-switch glass select，左栏头 + 移动 panebar 双挂点）；
   /api/wiki/vaults 清单驱动，失败静默降级合并-only。
7. 子 vault 聚合节点（isVault 双环石墨圆点、`名称 (N)` 常显标注、参与力导向、
   不进目录 chips）→ 详情卡子图谱卡（子图谱徽标 + N 篇文档 + 进入子图谱）
   → 下钻（drillIn）→ 面包屑（.wg-crumbs，逐级可点回）。
8. 统计行：单视图吃服务端 stats + 追加「N 条跨库引用」；合并视图走既有
   客户端计算（零变化）；Esc 逐层退出增加「离开下钻视图」一档。
9. i18n：wiki.allProjects / kindVault / vaultDocCount / vaultBlurb /
   enterVault / crossVault（zh-CN + en 双语）。
10. focusWikiNode 升级：从合并视图改为打开节点所属项目的 vault 视图。

**校验管线**（check_wiki_docs.py / wiki-docs SKILL.md）
11. `--include-subvaults`：逐 vault 独立 R1-R3（各自 docs/词表/解析器），
    跨 vault 引用记 info 级 cross-vault-link（不计违规、不影响退出码）；
    **无旗标输出逐字节不变**（--json 与人读，对 main HEAD 实测 diff 为空）。
12. SKILL.md Vault layout 增补子 vault 约定与逐 vault 校验命令。

## 验证读数

- Scala：**WikiIndexSpec + WikiServiceSpec 25/25 全绿**（WI-1..19 + WS-1..6，
  临时 dataRoot 双项目 + 子 vault 夹具：隔离/external 分类/面包屑/清单/
  __user__ 占位/非法 vault rel 拒绝）。spec 过程揪出两处实现缺陷并修复
  （2f10ffd63）：① 单视图 vaultRel 被当 workspace 相对折叠（"sub/docs" 错指
  `<workspace>/sub/docs`）——统一为根 vault 相对 + vaultRelSegments 单点折叠
  （本地 vault/sibling 与远端 joinRemote 三处）；② 根视图 children 深度公式
  对根错 1（根 "docs" 本身是 pair 尾段）——改为 below-段前缀匹配 + 差恰一对。
  另降级错误补 logger.errorSync（不再静默吞进空视图）。
- 前端契约：tests/wiki-graph-multivault.spec.mjs 3/3（切换器默认合并视图/
  切项目隔离/聚合节点→详情卡→下钻→面包屑回父级/统计跨库口径/合并兼容）。
- 邻族回归：tests/wiki-graph-ux.spec.mjs 12/13——§F frosted 同败 main HEAD
  （main 复测同败，既有，与本批无关）。
- 门禁：checkJs 257=基线；check-circular 与 main 逐字节同；check-scala-layers
  未豁免失败集与 main 逐条同；build-web 179 sources orphans 0。
- 亮暗实拍：12 张（合并/stand 真实 91 篇/视觉工作室/夹具聚合/下钻面包屑 ×
  dark/light），shots 断言 14/14，两主题 console 零错误。

## 与 A 批（段落级引用）的合并预案

本批触 wikiGraph.js 的面：模块头/typedef/openWikiGraphTab/renderInto/
ensureSidePanel（+切换器+面包屑）/buildPaneBar/loadData（URL 构造）/
rebuildSim（vault 节点 + external + stats + crumbs）/renderStats/draw（聚合
glyph）/fillDetailCard（+vault 卡）/showTip/focusWikiNode/probe。
A 批的段落锚消费面（edge.anchor 的 L42/#^block 跳转、详情卡段落列表）与上述
维度正交；同文件冲突按「A=段落锚语义、B=项目面语义」手工解。

## 备案（待作者裁定）

- 合并视图 = 根 vault 并集（子 vault 不再混入大图）：隔离语义的直接推论；
  若要含子 vault，`buildMerged` 改遍历 allLocalVaultRels 单点即可。
- 远端视图 external 分类 v1 未做（peer 往返成本），幻影按断链显示。
- 真实仓内暂无 `SUB/docs` 子 vault 实例；任一项目 vault 建目录即自动出图。
