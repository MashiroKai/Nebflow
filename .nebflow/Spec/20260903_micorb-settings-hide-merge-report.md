# micOrb 光球配置区隐藏 — 合并收编报告（micorb-config-hide → main）

- 日期：2026-09-03
- 执行节点：合并-光球配置区隐藏（barrier，QA 判定为闸门）
- 主仓：`/Users/dev/Claude code/Nebflow`（main）
- 分支：`micorb-config-hide`（基线 `1f10246a` + 实施 `398ee67b` + 验证 spec `e34cabc7`）

## 1. QA 判定复述（闸门输入）

上游节点「验证-光球配置区隐藏」（n-918c1067）最终判定 **PASS（可合并）**，六项验收全过：

1. 隐藏断言（独立重放 V1 四象限 zh/en × dark/light，DOM 零 orb-* 命中，无空壳卡片）— PASS
2. micOrb 渲染回归（micorb-presets T1-T6 + orbit-anim 6/6，九态默认映射不变、模块保留）— PASS
3. 自定义配置读取保留（V2a/V2b：自定义板解析 + saveSaved CHANGE_EVENT 驱动 live orb）— PASS
4. 改动面审查（仅 sidebar.js 两处门控 + 新 spec；零删除、无越界、一行可逆）— PASS
5. 回归独立复跑（本批域 21/21 + i18n 11/11 parity 921=921）— PASS
6. 既有失败归因（smoke×5 缺真后端、bgagent-dedupe×1 文案过期——均与本批零交集）— PASS

证据 commit：`e34cabc7`（仅新增独立 spec）。上游报告：`20260903_micorb-settings-hide-verify-report.md`（~/.nebflow commit `ca82ca3`）。

## 2. 开工盘点

| 检查项 | 结果 |
|---|---|
| 主仓当前分支 | `main` ✓ |
| 分支提交链 | `1f10246a`(基线) → `398ee67b`(实施：ORB_SETTINGS_VISIBLE 门控) → `e34cabc7`([verify] QA spec)，tip `e34cabc74222742cc5a023ca3126c7f21282d4b7` |
| worktree 状态 | 干净（无文件要提交）✓ |
| 主仓 `git status --porcelain` | 全干净 ✓ |
| merge-base | `1f10246a`（= 基线） |

## 3. 冲突预判 vs 实际

**预判**：三点求交集——分支自基线改动 = `sidebar.js` + `tests/micorb-settings-hidden.spec.mjs` + `tests/verify-micorb-hide-qa.spec.mjs`；main 自基线改动 = **空**（main 恰好停在基线 `1f10246a`，在飞「补充-AskUser刷新存活」尚未落 main，其 chat/persistence/main.js 域与分支预期零交集成立）。预判：**零冲突**，且分支严格领先 main、无需先 merge main。

**实际**：`git merge --no-ff` ort 策略一次成功，**零冲突**，与预判一致。

## 4. 冲突逐文件融合决策

无冲突，无融合决策需要。合并引入 3 文件 +404/-3：

- `src/main/resources/web/js/sidebar.js`（+9/-3：`:28` 常量 ORB_SETTINGS_VISIBLE、`:762` renderSettings 外观卡片插值门控、`:1296` bindAppearanceEvents 门控）
- `tests/micorb-settings-hidden.spec.mjs`（新增 202 行，H1/H2/H3）
- `tests/verify-micorb-hide-qa.spec.mjs`（新增 193 行，V1×4 + V2a/V2b）

## 5. Merge commit

```
c0e67306baaad9d2fe1163fd76d3c80531ec40be
web(settings): 隐藏 micOrb 光球配置区（按预设驱动，门控保留代码与读取逻辑）
Parents: 1f10246a (main@基线) + e34cabc7 (branch tip)
```

合并后主仓工作区干净。合并过程触发仓库 Nebflow QC 自动审查钩子（无本地 diff 可审、无阻塞动作，详见 `.nebflow-qc.log`），不影响合并结果。

## 6. 合并后子集复跑（main @ c0e67306，QA 同口径逐文件 + --workers=1）

| spec | 结果 | 备注 |
|---|---|---|
| `tests/micorb-settings-hidden.spec.mjs` | **3/3 绿**（30.3s） | H1 四象限 DOM 断言 / H2 orbSettingsUI 模块保留 / H3 saveSaved 驱动 live orb |
| `tests/micorb-presets.spec.mjs` | **6/6 绿**（40.5s） | T1-T6，--workers=1（T2 多 worker flaky 规避） |
| `tests/orbit-anim.spec.mjs` | **6/6 绿**（35.9s） | R1/R2/R3×2 + pending + visual |
| `scripts/verify-i18n-sweep.cjs` | **11/11 PASS** | parity 921=921；appearance key zh/en 保留；零 page error |

**i18n sweep 首跑说明**：首跑直接 `node scripts/verify-i18n-sweep.cjs` 失败（`Failed to fetch /js/locales/zh-CN.js`）——根因为脚本不自起静态服务、需外部服务指向 `src/main/resources/web`（与 QA 上游「静态服务 8188」口径一致），属前置条件缺失非浏览器故障。补起 127.0.0.1:8387 静态服务（python http.server，非 8080）后一次通过 11/11，跑完即关停并确认端口释放。未做任何浏览器盲目重试。

未跑第二次全量（分支上已全绿，本节点按任务纪律只复跑子集）。

## 7. 清理记录（子集全绿后执行）

| 步骤 | 结果 |
|---|---|
| `git worktree remove .nebflow/worktrees/micorb-config-hide` | ✓ 已移除（worktree list 无该项） |
| `rm .nebflow/micorb-config-hide`（软链 → worktrees/micorb-config-hide） | ✓ 已删除 |
| `git branch -d micorb-config-hide` | ✓ `-d` 直接成功（曾为 `e34cabc7`，git 确认完全合并，无需 `-D`） |

临时静态服务（python http.server :8387，PID 10310）已确认身份后关停，8387 端口释放。

## 8. 生效说明

合并只落 main 源码（`src/main/resources/web/js/sidebar.js` 门控）。**运行时生效需前端产物重建 + 宿主应用重启**，本批禁令（不重启宿主）范围内**均未做**——当前运行实例仍显示旧 UI。门控可逆：`ORB_SETTINGS_VISIBLE` 翻回 `true` 一行恢复外观卡片。

## 9. 遗留问题（均不阻塞，待另行安排）

1. pr41 spec 顶层 `process.exit` 截断整目录跑（EXIT=0 假绿）——建议改名/包装该 spec。
2. micorb-presets T2 多 worker 时序 flaky——CI 固定 `--workers=1`。
3. smoke×5（需真实后端）与 bgagent-dedupe×1（文案断言未随 i18n 中文化更新）为历史既有失败，与本批零交集，待另修。
4. 前端产物重建 + 宿主重启生效动作待后续批执行。

## 10. 合规确认

全程未 push、未动 origin；未触碰宿主进程（PID 87216 / 端口 8080）；未 NodeCancel/abandon 任何节点；git add 仅具体文件；本报告先落盘、commit 后再收尾。
