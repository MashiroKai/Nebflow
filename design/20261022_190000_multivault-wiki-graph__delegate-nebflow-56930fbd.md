# 多项目图谱设计（#744-B · stand 内置 wiki-graph）

> 台账 #744-B「图谱演进 B 批：stand 内置 wiki-graph 多项目图谱」。
> 现状：`GET /api/wiki/graph` 把**所有**已注册项目的 `<workspace>/docs` vault
> 扫描后合并成一张大图；面板（wikiGraph.js）只有目录 chips / 搜索过滤，
> 没有项目维度。本设计先定 vault 注册/发现机制（与项目挂载的关系），
> 再定层级图谱与多图谱数据管线，最后定面板项目切换器。

## 0. 裁定摘要

| # | 决策点 | 裁定 |
|---|--------|------|
| 1 | vault 注册 | **发现式，零配置**：vault = `<workspace>/docs`（既有约定，wiki-docs SKILL 同款），项目挂载即注册；不新增 project.json 字段 |
| 2 | 子项目层级 | **约定式子 vault**：父 vault 内任何直接子目录 `*/docs/` 即子 vault（可递归）；图谱内子 vault = **聚合节点**，点击**下钻**进子图谱（面包屑返回） |
| 3 | 数据隔离 | 每 vault 独立 `VaultScan` → 独立缓存条目、独立统计、独立断链清单；`?project=` 缺席 = 合并视图（向后兼容） |
| 4 | 跨 vault 引用 | 不合并、不算断链：解析到占位节点 `stub=true, external=true`，计入统计「跨库引用」，永不画成节点 |
| 5 | C 批接口 | `?project=__user__` 预留（用户历史图谱）；本次返回空图占位 |
| 6 | A 批兼容 | 段落锚（edge.anchor/headings/blocks）维度**零触碰**；本批只加 project/vault 维度 |

## 1. Vault 发现与注册（同项目挂载机制的关系）

**关系**：项目（ProjectStore 注册，挂载/卸载/归档过滤后 `ProjectStore.list()`）
是图谱的**一级命名空间**；vault 不独立注册，跟随项目定义——

- 本地项目：vault = `<workspace>/docs`（`os.Path(pd.workspace, dataRoot)` 解析）。
- 远端项目（`origin.device` 非 self）：vault = `<origin.path>/docs`，
  经 `RemoteExecutor` scoped browse 通道在 peer 上扫描（既有机制不动）。

**好处**：卸载/归档的项目自动从图谱消失（`ProjectStore.list` 源头过滤，
面板 API 与图谱同源）；`POST /api/wiki/vault`（ensureVault）语义不变；
check_wiki_docs 的 `--root <workspace>/docs` 命令行与图谱口径永远一致。

**子 vault 发现**（本地）：扫描根 vault 时，直接子目录里存在 `docs/` 目录者
即为子 vault（`<vault>/<sub>/docs`），递归至多 3 层、每 vault ≤32 个子 vault
（扫描预算，与 `MaxFilesPerVault` 同纪律）。子 vault 内容**不参与**父 vault
的文档扫描（隔离）；父 vault 扫描时把子 vault 目录跳过。
远端：best-effort 一层——列 vault 根目录，对每个子目录试列 `<sub>/docs`，
失败即视为无子 vault（降级不报错）。

## 2. API 面

```
GET /api/wiki/graph                → 合并视图（现状逐字节兼容：projects/nodes/edges）
GET /api/wiki/graph?project=X      → 项目 X 的单图谱（vault 隔离数据）
GET /api/wiki/graph?project=X&vault=<rel>  → 下钻进子 vault（rel = 相对项目 vault 的路径，如 sub/docs）
GET /api/wiki/vaults               → 轻量清单（项目名/设备/workspace/vault 路径/有无 docs），
                                     驱动切换器，不做内容扫描
```

单图谱响应（新面，合并视图保持不变）：

```json
{
  "generatedAt": 0,
  "view": { "project": "X", "vault": "docs", "breadcrumb": [{ "name": "X", "vault": "docs" }] },
  "projects": [ { "name": "X", "device": "self", "workspace": "…", "vault": "…",
                  "docCount": 12, "error": null, "stub": false } ],
  "childVaults": [ { "name": "sub", "rel": "sub/docs", "docCount": 4 } ],
  "nodes": [ …, { "stub": true, "external": true, … } ],
  "edges": [ … ],
  "stats": { "docs": 12, "attachments": 3, "edges": 21, "unresolved": 2, "crossVault": 1 }
}
```

- `nodes/edges` 只含本 vault（external 占位节点带 `external:true`，前端不画）。
- 跨 vault 链接（父链子等、子链父、链别的项目）统一解析成 external 占位：
  可见（计数、详情），不算断链。
- `__user__`：C 批用户历史图谱的预留 project 值，本批返回
  `{view:{project:"__user__"}, nodes:[], edges:[], childVaults:[], stats:全 0}`。

**缓存**：`Map[cacheKey, (ts, Json)]`，cacheKey = `merged` 或 `project:X` 或
`project:X|vault:sub/docs`；同一 TTL（30s）与同一构建串行旗标（低频发请求，
全局一把锁足够，不做 per-key 锁）。

## 3. 面板：项目切换器 + 聚合节点 + 下钻

**切换器**（左栏控制面头部 + 移动层 panebar 同款）：glass-chip 材质
`<select>`，选项 = 「全部项目」+ 每个已注册项目。切换 → 拉取对应单图谱 →
重建仿真。默认「全部项目」= 现状合并大图（行为逐字节不变）。

**聚合节点**：单项目视图且 `childVaults` 非空时，每个子 vault 在画布上渲染
一个聚合节点（双环圆点 + 名称 + `N 篇`标注，常显 label，不参与力导向度数、
不出现在目录 chips）。点击聚合节点 → 详情卡显示子项目名/文档数/
「进入子图谱」按钮 → 下钻（fetch `?project=X&vault=sub/docs`）。
面包屑（头部切换器旁）`X › sub` 返回任意上级。

**统计隔离**：统计行/断链清单/目录 chips 全部只反映当前视图数据
（`view.data` 即当前图谱，天然隔离）。

**渲染性能**：聚合节点走既有力导向循环（作为固定普通节点参与布局）；
视口裁剪沿用现状（大图 = 合并视图，数百节点既有性能不变）。

## 4. 校验管线（check_wiki_docs.py）

- 新增 `--include-subvaults`：发现子 vault 后**逐 vault 独立**跑 R1-R3
  （各 vault 用自己的 docs 集合与 glossary），输出按 vault 分节；
  跨 vault 链接解析进其他已知 vault 时记 `cross-vault-link`（提示级，
  不计违规、不影响退出码）。
- 默认（不带旗标）行为逐字节不变。
- wiki-docs SKILL.md 增补：子 vault 约定 + 逐 vault 校验命令。

## 5. 与 A 批（段落级引用）的关系

A 批维度 = 段落锚（`edge.anchor` 的 `#^block`/`#Heading`/`L42` 消费面、
段落索引格式）。本批维度 = project/vault。**两维正交**：本批不动
WikiIndex 的解析/锚字段，只在其上叠加 vault 命名空间与 external 标记；
合并冲突按语义解（A 段落锚 = B 项目面）。

## 6. 验证计划

1. `WikiIndexSpec` 增补：子 vault 发现（纯函数面）+ external 节点编码。
2. `WikiService` 级 spec：临时 dataRoot 双项目 + 子 vault，验证 per-vault
   隔离、跨 vault external、清单端点、`__user__` 占位。
3. 前端契约 spec `tests/wiki-graph-multivault.spec.mjs`：切换器选项/
   单图谱拉取/聚合节点/下钻面包屑/统计隔离/合并视图默认兼容。
4. 真实双 vault 演示：stand 仓 docs（88 篇）+ 视觉工作室 docs（1 篇），
   亮暗截图（真实 vault 内容经 check_wiki_docs --graph 导出回放）。
5. 门禁：WikiIndexSpec/WikiServiceSpec 全绿 + spec + checkJs + check-circular
   + build orphans 0。
