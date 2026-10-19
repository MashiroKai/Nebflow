# pipelines — 插件使用指导（Plugin.md）

> 本文件是 pipelines 插件的**包根使用指导件**（Plugin.md）：**插件分配时注入给 agent 的提示词 = 本文件内容**（作者令 2026-10-21「plugin.md = 插件必要文件机制」批；SKILL.md 回归按需查阅件——面板胶囊点击打开，不再随分配整文注入）。选配判定仍看 plugin.json 的 description；逐 skill 作业指令见各 `skills/<skill>/SKILL.md`（按需查阅）。**本文件是导览不是手册：与 SKILL.md 不一致时以 SKILL.md 为准并回改本文。** 文内路径均为**自包根的文件相对形式**（不使用 `${SKILL_DIR}`）。

## 1. 速览

pipelines 是工程闸门流水线纪律包：节点获得合并、发版与三维评分代码审查的执行与把关能力——三道闸门各自成 skill。内含 3 个 skill：

- `skills/merge-pipeline/`——Git 合并纪律：分支扫描（commit/文件/冲突/测试四事实）→ 就绪评估（go/no-go 三判据）→ 合并执行（`--no-ff` 无条件强制、冲突分级处理）→ 报告；全程不 push 除非明确指示。
- `skills/release-pipeline/`——发版纪律：审→做→包→复审四角色闭环、VERSION 文件单一版本号来源（semver）、tag 必打在 release 分支的 merge commit 上、三平台打包验证、上传 gated（永不自动 push/publish）。
- `skills/review/`——质量审查：工程哲学三条（先理解再行动/能删则删/保持简单）＋ Phase 0 自动化门禁 ＋ 三维评分（架构/质量/安全运维）＋ check-fix 循环（最多 3 轮）；后端 Scala 与前端 web 双域各有工具链适配段。

**包纪律一句话**：闸门语义大于结果语义——no-go/打回不是失败，是闸门起作用；push/发布永远显式授权；复杂改动合并前应已过审查（review 关卡前置，不在合并时补审）。

## 2. 安装与启用

- **安装**：本包不属 seed 官方分发，走**手工安装**：自插件仓获取整个包目录，拷入实例的插件目录即落位生效（拷包时连同本文件一起带走）。
- **启用/选配**：装载为「在位即生效」；分发器按 description 触发词选配（合并分支、发版与 release 操作、改 VERSION、打 tag、代码审查、review 评分、失败回滚处置等），派发时对应 SKILL.md 全文注入节点。
- **停用**：面板「更多 → 封禁该插件」/ `POST /api/plugins/pipelines/revoke` / `nebflow plugin revoke pipelines`。
- **内容变更**：包内任何字节改动都会漂移 digest，面板把该包标为「内容已变更」（可见性提示，不拦截装载；新内容对新派发即刻生效）。

## 3. 目录结构

```
pipelines/                        （包根）
  plugin.json
  Plugin.md                       ← 本文件（包根使用指导件：速览 / 安装启用 / 闸门导览 / 总目录 / 决策树）
  skills/
    merge-pipeline/SKILL.md       ← Git 合并纪律（注入面）
    release-pipeline/SKILL.md     ← 发版纪律（注入面）
    review/SKILL.md               ← 质量审查方法论（注入面）
```

纯知识包：无 references/、assets/、scripts/，无 mcp.json——每个 skill 的全部口径都在其 SKILL.md 单文件内。

## 4. 包级核心用法（三道闸门怎么配合）

> 全量口径以注入的三份 SKILL.md 为准；本节是闸门速查。

| 闸门 | 用哪个 skill | 关键判据（速查） |
|---|---|---|
| 合并 | merge-pipeline | 三判据全过才可合并（测试/无冲突/已审查）；简单冲突自行融合并核对两侧语义、复杂冲突中止上报；有问题的合并不硬修，blocked 如实上报 |
| 发版 | release-pipeline | VERSION 文件是唯一版本号来源；`--no-ff` 强制；tag 必在 release 分支的 merge commit 上；复审验证清单全绿才 PASS；上传 gated，绝不自动 publish |
| 审查 | review | Phase 0 自动化检查（编译/测试/格式/lint 四步）未过不进人工审查；三维各 1-5 分评分锚定；Critical+Warning 全修后重跑检查，最多 3 轮迭代 |

- **闸门顺序**：审查前置（review）→ 合并（merge-pipeline）→ 发版（release-pipeline）；合并的「已审查」判据由 review 承载。
- **verdict 门禁语义**：评审结论（PASS/FAIL＋问题清单）写进输出文本，由触发方读结果决定是否触发 fix 轮——门禁由「读结果＋重触发」承载，流程保持无环。
- **修发版失败**：复审未过 → 执行方按报告逐项修复重做 → 再复审；发版铁律不因赶进度放宽（tag 位置/merge commit 双亲/不 amend 不强推）。
- **前端审查适配**：review 的三维框架与评分锚定不变，工具链与检查项按前端域替换（Playwright 冒烟断言实际内容、语法检查、视觉铁律核对），细则见其 SKILL「前端适配」段。

## 5. 各技能总目录

| skill 路径（自 skills/） | 一句话括注 |
|---|---|
| merge-pipeline/SKILL.md | Git 合并纪律：四事实扫描、go/no-go 三判据、`--no-ff` 执行与冲突分级、报告与红线 |
| release-pipeline/SKILL.md | 发版纪律：分支与版本模型、四角色闭环、tag 程序、三平台打包验证、复审清单 |
| review/SKILL.md | 质量审查：工程哲学、Phase 0 门禁、三维评分、check-fix 循环、前端适配段、verdict 门禁 |

## 6. 选 skill 决策树

- 要**合并 feature 分支**（就绪评估、冲突处置、合并后清理）→ `merge-pipeline`。
- 要**发版**（bump VERSION、合并进 beta/release、打 tag、构建安装包、CI 矩阵确认）→ `release-pipeline`（低频高危，执行前通读其验证清单）。
- 要**代码质量审查**（交付后把关、`/review` 显式触发、合并后 diff 审查）→ `review`（后端 Scala 四步门禁；前端按适配段替换）。
- **功能开发本身** → 不在本包；**交付验证执行**（冒烟/契约/五维审核）→ 另配 `nebflow-qa` 插件——review 的三维质量审查与 qa 的交付验证是两道不同关卡。
- 三个 skill 常连用：一次收口＝review 过关 → merge-pipeline 落并 → 需要发版时 release-pipeline 全链。

## 7. 共享件专节

本包无 assets/、scripts/、references/ 共享件——全部内容承载于三个 SKILL.md；包级资产面为空是本包的合法形态（skills 非空即满足协议）。分支与版本模型（主线/beta/release 分工、semver 口径）以主仓 AGENTS.md「版本与发布管理」为权威，本包 skill 随其演进。

## 8. 扩展位（未来候选）

| 候选项 | 前置条件 | 备注 |
|---|---|---|
| 其他闸门流水线 skill（如依赖升级闸、发布后回滚预案） | 该闸门在生产中稳定跑通并沉淀出可复现清单 | 先入本表，落地后迁出并入 §5 |

> 只登记候选与前置条件；候选落地后迁出本表、入 §5 总目录。

## 9. 维护约定

- **闸门口径演进**：分支模型、semver 口径、打包矩阵以主仓 AGENTS.md 为权威；权威面变化时同步 release-pipeline 与本文件 §4/§6。
- **审查清单演化**：review 的自动化门禁与三维检查项随工具链演进增删；改动后核对其 SKILL 内双域适配段不脱节。
- **红线不放宽**：不 push、不强推、不改已发布合并历史、上传 gated——四条是本包安全基线，任何 skill 改动不得稀释。
- **本文件定位**：Plugin.md 是按需 Read 面——装载器不注入；SKILL.md 是注入面权威，两者不一致时以 SKILL.md 为准并回改本文。
