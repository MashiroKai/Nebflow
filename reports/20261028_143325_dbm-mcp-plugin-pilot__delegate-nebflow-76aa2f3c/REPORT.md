# dbm 作首个 MCP 插件挂载试点（作者拍板 2026-10-28 14:0x「文档+两个试点」之试点一）

- 执行人：delegate-nebflow-76aa2f3c（stand）。时间：2026-10-28 14:08–14:33。
- 性质：插件系统 MCP 面首次实跑。前置 = 插件审计（`reports/20261028_115822_plugin-system-audit__delegate-nebflow-67b2a760/REPORT.md`）G7：协议/装载/生命周期代码面完备、运行痕迹为零，差 3 步到首跑——本批把这三步走完。
- **验收三件齐**：① validate 退出码 0（M1-M15 全量+C 组镜像，30 checks PASS）②离线装载通过（CLI 镜像 22/22 dispatchable、0 blocked、0 rejected）③MCP 往返硬证据（stdio 拉起 + initialize + tools/list=31 + 7 只工具真实调用全通）。
- **零接触纪律**：在产引擎（pid 70481/6138）未重启、未发 REST、未发信号；dbm 仓只读零 push（npm install 曾改本地克隆 package-lock.json，已 `git checkout --` 恢复零改动）；npm install 只发生在 worktree scratch。

---

## ① 对象与基线

- dbm = 张安齐（phenol1145）`nebflow-dbm` v0.1.0：治理化文档记忆引擎，JS + better-sqlite3 单依赖，CLI 20 命令 + MCP 31 工具。
- 克隆：`git@git.ustc.edu.cn:phenol1145/dbm.git` → worktree `scratch/dbm`（单 commit `5ccd93a`，remote 仅 fetch/push URL，零 push）。
- 基线：`npm install`（32s）+ `npm test` → **47 pass / 0 fail**（evidence/npm-test.txt），与 10-19 我方实测一致。

## ② 插件包（creator 形态 B 首例生产样本）

- 名字：**dbm-memory**（`nebflow-dbm` 撞官方保留前缀 `nebflow-`，M4/C9 会拒——审计 §1.3 官方身份闸，故用任务建议的备选名）。
- 产物（staging）：`scratch/pkg/dbm-memory/`；**安装位：`/Users/kaiyu/.stand/plugins/dbm-memory/`**（同名探测无撞名后 cp -R，非 installFrom）。
- 结构（51 files / 2.2MB）：

```
dbm-memory/
  plugin.json          ← canonical $schema、name、0.1.0、五段式 description(213 字符)、author、keywords
  mcp.json             ← canonical mcp $schema，1 entry "dbm"（stdio）
  Plugin.md            ← 分配注入体（速览/31 工具分组表/首跑流程/数据落点/CLI 20 命令/红标声明/停止手段）
  skills/dbm-usage/SKILL.md   ← 按需查阅件（frontmatter name+description；作业流程手册）
  vendor/dbm/          ← 运行时快照：src/*.js + GUIDE.md + package.json + node_modules 运行时闭包
  vendor/governance/mini-package/  ← 自带治理包（dbm_package_install 首装用，来自上游 examples/）
```

- **mcp.json 全字段对照协议（审计 §1.4 逐条）**：

```json
{ "$schema": "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json",
  "mcpServers": { "dbm": {
    "type": "stdio",
    "command": "node",                       // 单 token 裸名，走 PATH；非 shell 类（无红标）
    "args": ["./vendor/dbm/src/mcp.js"],     // ./ 相对形态（运行期按 cwd=插件根解析）
    "cwd": "./",                             // 装载归一为 ${PLUGIN_ROOT}，运行期展开
    "env": { "DBM_ROOT": "${PLUGIN_DATA}" }  // env 值占位符展开；未声明保留键、零凭据键
} } }
```

- **运行时闭包裁剪（关键决策）**：dbm 全量 node_modules 29MB 里 better-sqlite3 运行时只依赖
  `better-sqlite3/{lib,build/Release/better_sqlite3.node,package.json}` + `bindings` + `file-uri-to-path`；
  prebuild-install 及其传递依赖（semver/tar-fs/readable-stream 等）仅安装期需要且含 12 行 TODO/FIXME——
  M15 全包扫描必 FAIL。裁剪后 2.2MB / 47 文件，M15 零命中，剪后冒烟+全量往返功能无损。
  build/ 目录只留 `better_sqlite3.node`（obj/obj.target/sqlite3.a/test_extension.node 为编译中间产物，剔除）。

## ③ 机械闸自证（creator 交付闸）

```
python3 …/nebflow-plugin-creator/skills/plugin-packaging/scripts/validate_plugin.py --profile creator <pkg>
== RESULT(profile=creator): PASS — 30 checks, 30 PASS, 0 FAIL, 0 human-review hint(s) ==
EXIT=0
```

- M01-M15 全 PASS（description 五段式 213/400 字符；M11 声明==实际={dbm-usage}；M14 1 entry 全过镜像规则；M15 全包零占位残留——覆盖 vendor 全部 51 文件）。
- digest：`sha256:264bf1ce75ae0485d4787f6b4d197d7cc32cb372f83c979bb7fc1e0097c882db  files=51`。**安装后对在盘字节重跑校验：digest 逐字相等 + 再 PASS**（evidence/validate-installed.txt）——闸过的就是装上的那份字节。
- 3 条 WARN 逐条处置（无 FAIL）：
  | WARN | 内容 | 处置 |
  |---|---|---|
  | C04 ×8 | `vendor/dbm/GUIDE.md`×5（上游 Windows 路径示例）、`bindings/bindings.js`×2、`file-uri-to-path/index.js`×1（上游注释） | 保留上游原字节不改（快照保真）；均为文档/注释示例，非操作指令；已在 Plugin.md §5 红标段声明 |
  | C05 ×1 | `better_sqlite3.node` 非 strict UTF-8（原生 SQLite addon） | dbm 的存在本身；用户装包模式=警告非硬闸（硬闸只对 seed 分发树内包）；Plugin.md 已声明 |
  | C11 ×1 | 包根未知条目 `vendor` | 协议「宽容忽略+告警」面（§B.8-5）；Plugin.md §5 已声明；装载不受影响 |

## ④ 安装 + 离线装载验证

- 安装：cp -R → `/Users/kaiyu/.stand/plugins/dbm-memory/`（落盘即生效语义；引擎 boot/重扫时装载——**未重启引擎**，已在跑的分发器会话目录快照不刷新，下一次派发才会分配到）。
- 离线装载验证（PluginRegistry 装载逻辑的镜像面，未触在产引擎 REST）：
  - `tools/stand-plugins list`：**22 plugins（22 dispatchable, 0 blocked, 0 dispatch-off）**，0 rejected（前值 21）——manifest 解析+canonical schema+skills/mcp 硬门槛镜像全过（evidence/stand-plugins.txt）。
  - `tools/stand-plugins search dbm` / `search 记忆`：各 1 hit 即 dbm-memory；目录行渲染 `mcp: dbm`（**CLI 首次渲染出 mcp 组件面**——21 存量包该列全是 `-`）与 `keyword:记忆` 命中（keywords 元数据通道 G5 首次有消费者）。
  - 装载期预期告警 2 条/包（不拒载）：包根 `vendor`、`Plugin.md`（装载器 known-set 未扩，审计 G1 已立项）。
- **plugin-data 规范验证**：运行前 `/Users/kaiyu/.stand/plugin-data/` **不存在**（审计 G7 零实跑硬证据）；往返后按 §9.1 规范落位：`plugin-data/dbm-memory/{registry.db, identities/{user-*.db, agent-*.db}}`（evidence/package-tree.txt）——引擎 acquire 时 `os.makeDir.all`（PluginMcpManager.scala:107-119）+ 本试点等价复刻均实证该路径可用。

## ⑤ MCP 首例实跑（硬证据）

- 拉起方式 = **等价复刻引擎 `PluginMcpManager.runtimeConfig` 输出**：`spawn("node", ["./vendor/dbm/src/mcp.js"], cwd=插件根, env={…原 env, PLUGIN_ROOT=插件根, PLUGIN_DATA=<dataRoot>/plugin-data/dbm-memory, DBM_ROOT=${PLUGIN_DATA} 展开值})`；harness：evidence/mcp-roundtrip.mjs（逐行 wire log：evidence/mcp-roundtrip-full.jsonl）。
- 往返序列与结果（全部真实 wire 往返，非 mock）：

| # | 方法/工具 | 结果 |
|---|---|---|
| 1 | `initialize` | serverInfo `nebflow-dbm@0.1.0`，protocolVersion 2024-11-05，instructions 带 agent 工作流 |
| 2 | `notifications/initialized` | ack（stderr 横幅 `root=…/plugin-data/dbm-memory defaultIdentity=user:local`） |
| 3 | `tools/list` | **31 只工具**（名单见 evidence/mcp-roundtrip-full.jsonl，与 mcp.js TOOLS 一致） |
| 4 | `tools/call dbm_identity_list` | 默认身份 `user:local` 已由 server 启动时 ensure，db 落在 plugin-data 规范路径 |
| 5 | `tools/call dbm_identity_create {agent:pilot}` | 独立 SQLite 建成（identities/agent-64fdd070….db） |
| 6 | `tools/call dbm_package_install {path:"vendor/governance/mini-package"}` | **cwd 相对路径解析成功**，mini-governance@0.1.0 安装（治理根 root_keeper，4 skills） |
| 7 | `tools/call dbm_doc_add` | 文档落库并被 `note_maker` 治理（治理规则继承生效，version 1） |
| 8 | `tools/call dbm_search {query:"MCP 往返", mode:"inverted"}` | **倒排检索命中** score 9.99，matchedTerms ["mcp","往返"] |
| 9 | `tools/call dbm_sql {SELECT … FROM v_docs}` | 只读 SQL 返回治理包 4 skills 行（readonly+LIMIT 生效） |
| 10 | `tools/call dbm_stats` | docs 5 / blocks 21 / terms 463 / vectors 26 / 边 4 / 根 1（hash 降级向量在场） |

- 一次往返即覆盖「初始化→装包→落库→检索→直查→盘点」全闭环；embedding 未配 API 时 hash 降级可用（`DBM_EMBEDDING_*` 由 mcp.json env 按需加，本包零凭据声明）。

## ⑥ 评估回报

### 6.1 挂载起步成本实测

- **8 步 / 全程约 25 分钟**（含协议面精读与包撰写）；纯机械管线（clone→npm i+test→staging→validate→install→往返）约 **10 分钟**。有本报告在手，下一个 MCP 型包可压到 **5 步 / <10 分钟**（裁剪清单、mcp.json 模板、坑清单都是现成的）。
- 步骤：①只读克隆+依赖+基线测试 ②运行时闭包裁剪+staging ③authoring 四件（plugin.json/mcp.json/Plugin.md/SKILL.md）④validate 过闸 ⑤同名探测+cp 安装 ⑥对在盘字节重校验 ⑦CLI 离线装载镜像 ⑧stdio 拉起+全链路往返。
- **踩坑实录**（前 3 条是协议/工具面改进项）：
  1. **M15 全包扫描 vs node_modules 必撞**：自带 node_modules 的 MCP 型包几乎不可能过 M15（第三方库 TODO/FIXME 不可修）→ 只能运行时闭包裁剪。建议 validator 增加 vendor/node_modules 豁免或目录白名单（归 G7 批次）。
  2. **M11 实质要求 ≥1 skill**：协议硬门槛是「skills/ 与 mcp.json 至少其一」，但 `m11_ok = bool(actual) and …` 使**纯 mcp 包（无 skills）必 FAIL**——纯 MCP 包也必须带至少一个 skill 目录。creator 手册未写明。
  3. **MCP identity 不隐式建**：server 启动只 ensure 默认身份，其余工具传未知 identity 报 NOT_FOUND；首跑必须 `dbm_identity_create` 先行（dbm MCP instructions 有写 workflow，但「传参即建」是自然误解）。
  4. C4/C5/C11 三类 WARN 是 MCP 型包的常态伴生（上游文档路径、原生二进制、vendor 根条目）——都可处置说明，不拦闸；建议官方验收口径直接认领这三类为「MCP 型包标准 WARN 组合」。
  5. 裸名 `command:"node"` 依赖引擎进程 PATH；若引擎将来跑在最小 PATH 的 launchd 下会 `PLUGIN_MCP_START_FAILED`（failNode，不静默）。缓解：绝对路径 token（吃 C4 WARN）或包内 wrapper。
  6. （harness 自身，非产品）`new URL().pathname` 对含空格路径返回 `%20` → spawn ENOENT 误导；用 `fileURLToPath`。

### 6.2 31 工具的注入面核心子集建议

注入面（Plugin.md/description）承担「选配+上手」，工具 schema 引擎运行时经 tools/list 全量在场，不必也不该全塞注入文本。建议核心 10 只（首跑闭环）：

> `dbm_guide`、`dbm_identity_list`、`dbm_identity_create`、`dbm_package_install`、`dbm_doc_add`、`dbm_search`、`dbm_sql`、`dbm_sql_schema`、`dbm_validate`、`dbm_stats`

覆盖 初始化(3)→落库(1)→检索(1)→直查(2)→治理(1)→盘点(1)≈1。其余 21 只（block CRUD、refs、timeline、export、rebuild、embedding_test、doctor、doc_get/list/update/remove）在 Plugin.md 分组表中一句话带过即可（本包 Plugin.md §2 即此形态，全 31 只分组表 ~15 行，成本可控）。

### 6.3 与 memory-db / stand-recall 的分工建议

- **dbm（本包）= 结构治理记忆**：结论/规范/决策沉淀为治理树上的文档，单根单亲无环+规则继承保证结构合法，倒排/模糊/向量四模式检索，只读 SQL 审计面；身份隔离（每身份独立 SQLite）、跨会话持久、可 `dbm_validate`。
- **stand-recall / memory-db = 会话回溯面**：episodic 过程记录——哪个会话发生了什么、跑过什么、时间线回放。
- 边界：dbm 明确不做会话过程回放（description 与 Plugin.md 边界句均已落字）；recall 不承担结构化知识治理。互补不重叠；作业顺序建议「recall 查发生了什么 → dbm 查该信什么/怎么定」。

## ⑦ 移交物

| 件 | 路径 |
|---|---|
| 插件包（安装位，已冻结） | `/Users/kaiyu/.stand/plugins/dbm-memory/`（51 files，digest `264bf1ce…`） |
| staging 副本（worktree scratch，未提交） | `scratch/pkg/dbm-memory/` |
| dbm 只读克隆 | `scratch/dbm`（@`5ccd93a`，零改动零 push） |
| 往返 harness + wire 逐行记录 | `evidence/mcp-roundtrip.mjs` / `mcp-roundtrip-full.jsonl` / `mcp-roundtrip-staged.jsonl` |
| 校验/测试/装载镜像/tree 证据 | `evidence/validate-installed.txt` / `npm-test.txt` / `stand-plugins.txt` / `package-tree.txt` |

## ⑧ 开放项与后续建议

1. **引擎内 acquire 实拉起未验**（在产引擎零接触约束）：本试点等价复刻了 runtimeConfig 输出；建议 G7 收尾在下次引擎重启窗口或隔离实例给一个节点分配 dbm-memory 跑真 acquire（含 15s timeout、failNode、release 停飞三语义）。
2. validator 三点增强（G7 批次顺手）：vendor/node_modules 豁免（坑 1）、纯 mcp 包 M11 放行口径（坑 2）、「MCP 型标准 WARN 组合」验收口径（坑 4）。
3. 本包红标人审演练材料已备好（Plugin.md §5 红标段 + §③ WARN 处置表），可直接作为「红标项人审流程演练」对象。
4. `dbm-memory` 数据已在 `plugin-data/dbm-memory/` 落了两身份（user:local 空库 + agent:pilot 试点库）；试点文档 `mcp-pilot-first-doc` 留在 agent:pilot 库内作为首跑标记，不需要可 `dbm_identity_remove`。
