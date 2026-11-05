# 设计规格 525 处改值收敛批 · 证据与可复算入口

作者令 2026-11-04 20:49（「按你的推荐走，重启后实施」）。本目录是该批的**机械证据层**：
每条结论都配一条能重跑的判据与读数，不含"看着没变"这类判词。

| 层 | 面 | 判据 | 读 |
|---|---|---|---|
| ① | 命名债（三处） | computed 逐值 + 像素 A/B | 见下 §1/§2 |
| ② | 字号十三档 → 6–7 档 | 改前/改后值表 + 双档截图 | 见 `TYPE-TIERS.md` |
| ③ | 圆角/间距例外值 | 值表 + 具名例外槽 | 见 `EXCEPTIONS.md` |

## 0. 复算命令（全部零端口零进程，结构上碰不到宿主实例 6138）

```bash
R=reports-evidence/20261105_design-spec-value-convergence

# 第一层：命名债迁移（dry-run / apply / 零残余断言）
node $R/layer1-naming-debt.mjs            # 打印逐角色计数，拒猜则 exit 1
node $R/layer1-naming-debt.mjs --apply
node $R/layer1-naming-debt.mjs --verify   # 旧名残余 0 / 拒猜 0

# computed 逐值快照（改前树 vs 改后树）
git archive <pre-commit> src/main/resources/web | tar -x -C /tmp/pre
node $R/computed-snapshot.mjs --web-root /tmp/pre/src/main/resources/web --out $R/snap-pre.json
node $R/computed-snapshot.mjs --out $R/snap-post.json
node $R/computed-snapshot.mjs --diff $R/snap-pre.json $R/snap-post.json    # 必须 0

# 像素 A/B（28 张：14 场景 × 双主题），用两段式采集把两棵树分目录
node scripts/visual-baseline.mjs --update --platform pretree --web-root /tmp/pre/src/main/resources/web
node scripts/visual-baseline.mjs --update --platform posttree
node $R/compare-dirs.mjs .nebflow/visual-baseline/pretree/baseline-draft \
                        .nebflow/visual-baseline/posttree/baseline-draft
```

## 1. 第一层 · computed 逐值对照（任务书验收判据：差异必须为空）

装置：`computed-snapshot.mjs`。对 **body 下每个元素** 取 52 个计算属性（字号族 / 内距 /
外距 / 圆角四角 / 色 / 阴影 / 行高字距 / 显示与几何）+ 盒几何（四舍五入到整 px），
按 DOM 路径（`tag#id[兄弟序]`）逐值比对。场景 × 视口 × 主题 = 3 × 2 × 2 = 12 轮，
**10,790 个元素**，即 10,790 × 52 ≈ 56 万个值逐字比对。

| 对 | 逐值差异 | 元素集差异 | 判 |
|---|---|---|---|
| 改前树 ↔ 改后树 | **0** | 0 | ✅ 计算值逐字未变 |
| 改前树 ↔ 改前树（同树双拍，确定性控制） | **0** | 0 | ✅ 装置稳定，不产生假差异 |
| 改后树 ↔ 变异树（**负控制**：把 `--ds-space-inline-x` 的定义改名成 `-xx`，其余不动） | **1,210** | 0 | ✅ 装置有检出能力（且定位到「哪个元素哪个属性」：`padding-* 10px → 0px`） |

负控制是这套装置的价值证明：一次"改名改错"的滑手（在本批里是最可能的失败形态）
会被它按元素×属性逐条点名；反过来，全 0 才是「逐值相等」而非「没人看」。

## 2. 第一层 · 像素 A/B（四腿方阵：把「改前/改后」与「同树抖动」分开）

`visual-baseline.mjs --update` 的产物是 28 张（14 场景 × 双主题）。四腿 = 改前树 A1/A2、
改后树 B1/B2；同树腿给出**噪声带**，跨树腿的差异只有在落在带内才可判 NOISE-BOUND。

| 腿对 | SAME | DIFF | 差异场景（px） |
|---|---|---|---|
| **A1 ↔ B1**（改前 ↔ 改后，判据腿） | 22 | 6 | canvas-html-tab-dark 29 · canvas-markdown{,-code}-tab-{dark,light} 5–6 · settings-llm-empty-light 97 |
| A1 ↔ A2（改前树双拍，零改动） | 23 | 5 | canvas-html-tab-dark 29 · canvas-markdown-code-tab-{dark 6, light 12} · canvas-markdown-tab-dark 6 · canvas-txt-tab-dark 9 |
| B1 ↔ B2（改后树双拍，零改动） | 25 | 3 | canvas-markdown-code-tab-dark 6 · canvas-markdown-tab-light 5 · canvas-txt-tab-dark 9 |

判词：
- **canvas 族 5–29px = 既有渲染噪声**，不是本批产生：它出现在**两条同树腿**里（零代码改动），
  且 bbox 恒定落在画布面板右下角/页签 1px 边框（`y 860–864`、`x 1–3 / 548–550`，整帧 AA 一种）。
  与 2026-11-04 迁移批记录的同机噪声带（canvas 族 5–29px）逐场景吻合。
- **settings-llm-empty-light 97px** 只出现在跨树腿，bbox = `{941,747–966,768}`，即 25×21 的
  **文本光标位**（艾条/插入符相位），与迁移批记录过的「settings 光标位 97px」同一现象；
  本层**不可能**移动它：同一场景同一视口同一主题下，computed 逐值差异为 0（§1）。
  ⇒ 结构上只剩渲染相位一种解释。**残留风险如实标注**：这 97px 未在本次同树腿复现。
- 其余 22 张跨树腿逐字节 SAME。

## 3. 第一层的八道门禁读数（改后）

```
check-design-tokens        EXIT 0   PASS: 无新增设计字面量；严格面零跑偏，令牌引用全部有定义
check-design-spec-sync     EXIT 0   PASS: spec 63 行 · 账本对位 44 条（无漂移）
check-component-contract   EXIT 0   PASS
check-self-explaining      EXIT 0   PASS（基线 27 条）
check-msgstyle-single-source EXIT 0 PASS（7 rules / 41 files）
check-circular             EXIT 0   PASS（237 files，无环）
check-process-artifacts    EXIT 0   ✓ clean（allowlist 0 条生效）
make frontend-gates        EXIT 0   Frontend static gates passed
```

`check-design-tokens` 的「令牌引用全部有定义」一句是本层的关键静态网：13 处桌面 16px
与 3 处 14px 的改指若写错名字（→ 未定义 var），该闸直接红，不必等某个面板被点开。

---

## 合并 main 后的最终读数（HEAD=5e61ec462，main=87f4e1bec）

三层 `--verify` 重跑（main 侧新增改动没有把旧名/档外值带回来）：

```
layer1-naming-debt --verify   旧名残余 0 / 拒猜 0                    EXIT 0
layer2-font-tiers  --verify   [7,26]px 档外值 0                       EXIT 0
layer3-exceptions  --verify   单值例外 0（组合/负数/组件尺寸/14px 见 EXCEPTIONS.md）  EXIT 0
```

八道静态门禁全 EXIT 0（check-design-tokens / check-design-spec-sync / check-component-contract /
check-msgstyle-single-source / check-circular / check-self-explaining / check-process-artifacts /
make frontend-gates）。

`make check-visual` = **FAIL 28 项**（12 DIFF + 16 MISS + 0 SAME），本批不许抬基准 ⇒ 这是
预期状态，不是回归判定：入库图仍是改造前渲染。
DIFF：chat-messages-light 161,189px · chat-messages-dark 160,988 · settings-llm-empty-light
112,616 · settings-llm-empty-dark 107,546 · app-empty-dark 40,559 · app-empty-light 39,716 ·
canvas-markdown-tab-dark 9,071 · canvas-markdown-tab-light 9,029 · canvas-code-tab-dark 902 ·
canvas-code-tab-light 862 · bgtask-panel-{dark,light} −1px（高度差，内容逐像素相同）。
MISS（16）：canvas-txt/html/image、explorer-tree、deck-cards、modal-confirm、settings-panel
各 2 主题 —— 这 8 个场景在 darwin 入库集里从来就没有基准图（上一批等值令牌化时同一份清单即 MISS），
本批未新增场景、未删基准。

`tests/visual/baseline/**` 零变更；刷新稿全在 `.nebflow/visual-baseline/darwin-chromium-151/`
（gitignore）。作者评审后用 `make visual-baseline-promote` 一条命令入库。

未合并到 main：`git merge-base --is-ancestor delegate/delegate-spec-value-converge main` = 非 0。
