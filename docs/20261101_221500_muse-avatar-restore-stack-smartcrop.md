---
type: batch-report
status: current
---

# Muse 头像基座回归 + 叠卡封面智能取景批（作者令 2026-11-01 21:40 三件）

- **令面**：①叠卡缩略图几何中心裁剪改内容感知取景；②agent 头像+名字回归顶层居中（Muse 式，IMG_1663 参考、49e2e7477 已落形态）；③git 考古适配——把「顶层居中」移植回当前 UI 语言，agent 名字弃胶囊立体边框改扁平毛玻璃（玻璃样式包 token）。
- **落地面**：`mobileLayer.css`（.g2-chat-head 基座回归块）、`js/smartCrop.js`（新）、`js/popArtifacts.js`（叠卡收拢层接线）。
- **验收**：`tests/muse-restore-smartcrop-shots.mjs` 双引擎（Chromium/WebKit）×双主题（light/dark）×双端（375 手机 / 1280 桌面）ALL PASS 零 console error；实拍 40 张 `evidence/20261101_muse-restore-smartcrop/`（恶意样本修前/修后对照 + 5 类样本逐行 + Muse 形态 rest/pinned）。

## 一、考古结论（③）

- **找到的版本**：Muse 式悬浮头像批 `b4b6d8745`（作者 11-01 13:33 令），经 `49e2e7477` 合入 main。形态 = 会话顶 sticky 居中大圆头像（78px）+ 名字下置；其**基座块**（列式居中布局 + 52px 圆头像 + 名字玻璃胶囊）原居 `css/liquid-glass.css:330-402`。更早谱系：`07e20bffc`（10-31 11:38，作者令②「主对话头像+agent 名恢复」）自 `d4cc8d189` 删除面逐值恢复改造前形态；`587db0035`（10-31 22:50 dock 收形批）曾加消息流行内身份头 `.g2-msg-meta`，Muse 批整批退役（作者令①「去掉每条消息头部的 agent 头像+名字行」）。
- **被谁改掉**：统一按钮+玻璃设计实施批 `dd78d5d05`（同日 17:22）以 glass.css 单源包**整文件退役 liquid-glass.css**，基座块随之湮灭；Muse 批留在 mobileLayer.css 的覆盖块（sticky/78px/字号）失去基座后，头像+名字塌成**左对齐行内形态**随流滚动（作者 21:37 实拍 IMG_1673 所见即本病）。回归只显于手机层（glass 批验证矩阵是桌面 1280 实拍，`mobile-muse-avatar-shots.mjs` 不在其回归清单）；修前复跑该 spec **30 FAIL**（居中/名字下置/压流全破）为实锤。
- **移植了什么**：按当前 UI 语言重建基座（不复活 liquid-glass.css）——列式 flex 居中 + flex:none（原基座逐值）；名字按作者令③改**扁平毛玻璃**：玻璃单源包 token（sapphire.css `--glass-bg`/`--glass-blur`/`--cv-saturate`），零 border、零立体 box-shadow（原 glass-control 双 inset 高光/阴影档不移植）；头像本体零触碰（28px 圆 + 主题化芯片 = chat.css 全局现役档，78px 放大由 Muse 覆盖块承担，自定义头像/stand logo 回落货源不动）。

## 二、叠卡封面内容感知取景（①）

- **病灶**：叠卡收拢卡脸（`.pop-stack-view` 固定 220×280）`object-fit: cover` 缺省 `object-position: 50% 50%` = 几何中心；截图类图片（内容集中上部、下半大面积空白）裁出一片空（作者实拍 IMG_1673 叠卡全空）。
- **方案**（`smartCrop.js`，smartcrop 思路自实现零依赖）：解码后降采样位图（≤240px）上跑**显著区域滑窗打分**——候选窗 = cover 同比窗（只在溢出维平移，不越权缩放）；打分 = 边缘密度 0.45 + 亮度方差 0.35 + 熵 0.20（积分图 O(1)/窗 + 32-bin 直方图熵）；**截图类偏置**：下半近均匀行占比 ≥0.55（或超纵 ≥1.9 且上 1/3 边缘密度 ≥ 下 1/3 × 2.2）判截图类，score × (1 + 0.35·(1−y/100)) 越顶分越高，普通图片零偏置；命中窗中心写 `object-position`。**缓存**按 src 记忆（FIFO 240；账本 blob/data URI 天然稳定）；**回落**：跨域位图污染/解码失败/任何异常不写内联样式 = 原几何中心。探测面 `img.dataset.smartCrop = computed|cache-hit|failed`。
- **接线**：`popArtifacts.js buildMediaStack` 收拢层图片脸 load 报告点即取景点（卡脸实测同比喂滑窗，首报未布局兜底 220/280）；平铺单卡/展开行（contain 真实比）与视频脸零感知。消费点唯一，零第二套算术。
- **验收实证**：恶意样本（上 35% 内容 + 下 65% 空白的 UI 截图）修前中心裁出空白、修后 y=0% 裁到内容区（pre/post 实拍对照在案）；5 类样本（UI 截图/人像/风景/文档/暗图）取景位置全在合理域，全域纹理人像 x,y ∈ [10,90] 不 slam 到边，逐行实拍目检不劣化；缓存腿同 src 三面结果逐字一致且 computed/cache-hit 并存。

## 三、回归与余项

- **邻域回归全绿**：`mobile-muse-avatar-shots` 30 FAIL → **ALL PASS**；`attcard-unify-shots` / `msg-token-chip.spec`（171/0）/ `mobile-dock-shape-shots` ALL PASS；checkJs 门本批文件零新增（存量 7 错属 msgTokenChip×2 / messages×1 / todoFace×1 / todoStore×3，非本批触碰文件，归属前批）。
- **已知边界**：视频脸不进取景（逐帧解码无便宜路，回落中心）；桌面叠卡同享取景（同生产路径，实拍在案）；webkit 对「detached 期设 src 的 lazy img」插入视口后不重评估（harness 对照实验在案）——spec 内以 eager 触发既有 load 链，生产 lazy 语义零触碰。
- **互链**：玻璃材质族见 [[glossary]]；Muse 形态验收判据沿用 `tests/mobile-muse-avatar-shots.mjs`（本批批注亦见该文件头）。
