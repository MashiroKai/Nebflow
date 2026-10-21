# delegate 全景视图 · 真机证据（delegate 视图重设计实施批 · 2026-10-22）

真机 = 本机现役引擎 `:8080`（旧构建，禁杀禁重启）的真实 delegate 行集；
前端 = 本 worktree 新面（`serve-proxy.mjs` 静态座 + `/api`、`/ws` 反代到 ：8080——
新前端 × 真数据联调，零假数据）。会话链 = console-ticket（session-rail 同款）；
主题 = 生产 token 自带 prefers-color-scheme，用 Playwright `colorScheme` 切。

## 截图清单

| 文件 | 内容 | 要点 |
|---|---|---|
| `pano-full-dark.png` | 全景全量 · 暗（1720×1124 @2x） | 3 项目带 28 委托：stand / stand-website / DOSTEL；六泳道（派发｜运行｜候合并｜合并中｜完成｜汇点）；派发边（根幽卡→运行卡）；stand 完成列拥挤折叠「完成 ×23」计数块（折叠即回收竖向空间）；汇点幽卡（main/staging · base）；#656 失败卡红染环；底部三段图例 |
| `pano-full-light.png` | 全景全量 · 亮 | 同上，亮主题（生产 token 双主题零分叉）；#678 完成卡报告条 + 已投递实线入汇点 |
| `pano-star-dark.png` | 子过程星扇 · 暗（stand 过滤） | 「子过程 · 真实拓扑 2 ‹」展开：真数据 3 张 subagent 子卡（#694 活子 + #648 星扇），星扇虚线边自 #694 卡下锚；`subagent · 1` 行内徽标 |
| `pano-star-light.png` | 子过程星扇 · 亮 | 同上，亮主题 |
| `pano-merge-dark.png` / `pano-merge-light.png` | 合并管线视图 | 本 delegate `delegate_report(merge)` 后进队/持窗时截——候合并琥珀 FIFO + 合并窗蓝晶行军蚁（真实引擎状态，非夹具） |

## 再现

```bash
# 1) 联调座（127.0.0.1:8123；静态=worktree web，/api+/ws → :8080）
node serve-proxy.mjs 8123
# 2) 截图（playwright 在主树）
node shoot.mjs full              # 暗+亮：全景全量 + 星扇
node shoot.mjs merge [both]      # 合并管线（等真实候合并/合并中状态在场）
```

## 本批随证据修复的两处产品缺陷（截图发现 → 修复 → 复测）

1. **拥挤折叠不回收竖向空间**：终态泳道折叠为计数块后 `laneBottom` 仍按幻影全栈
   计算，画布保持 ~2800px 高（大量留白）。修：`buildCanvas` 折叠臂 laneBottom 按
   计数块（56px）收（`delegatePanoramaView.js` stackBottom 重构）；spec 27/27 复绿。
2. **失败卡白描边（违反代审裁定③）**：`rgb(var(--status-error-icon) / 0.5)` 中该
   token 是 hex 档（`#f26b6b`）——`rgb(hex / α)` 形参非法，整条声明被丢，边框回落
   `currentColor` = 正文色（暗档白环/亮档黑环）。修：hex 档调 α 改 `color-mix()`
   （sapphire.css 同款；`delegatePanorama.css` .st-fail 块）；截图像素复测红环 ✓。
