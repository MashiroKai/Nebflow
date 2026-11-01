---
type: batch
status: published
date: 2026-11-01
scope: 手机层消息输入卡垂直收窄（composer mobile slim）
---

# 手机输入卡垂直收窄批（作者令 2026-11-01 22:1x）——条高 78 → 58px，桌面零触碰

关联批：[[20261101_212200_mobile-dock-capsule__delegate-nebflow-7fa8a6bb]]（本批输入卡悬浮其上的 dock 胶囊批）；上批 2026-10-31 composer-slim（a9d9a3812，桌面/手机共用件收到 6px 档，evidence/20261031_composer-slim）。

## 令文与口径

作者令：「收窄输入框的上下高度，给内容腾显示空间」——只收**上下** padding/高度（横向布局、头像/附件/发送钮尺寸不动），单行态明显变薄；多行自动长高保留（封顶不变）；按既有裁定手机优先（手机端消息输入框含 dock 上方那颗收窄，桌面零触碰）；收窄后头像钮/发送钮在更矮条里垂直居中不显挤，触控热区不小于可用下限。

## 改动（生产代码单文件两刀，全部 `body.mobile-layer` 作用域）

`web/css/mobileLayer.css` 输入卡垂直收窄批块（紧跟发送钮纯圆块之后）：

1. `#input-bar` 垂直内 padding `6px` → `4px`（4px 栅格档；横向 12px 不动）。
2. `#mic-orb-wrap` 上下对称负边距 `-8px` —— 光球 64px 画布的**布局足印**收到 48px。
   球体本身零触碰：canvas 尺寸与 WebGL 渲染分辨率不变 ⇒ 球面可见径（~40px）零缩放零模糊，只是布局盒与条解耦；负边距对称 ⇒ 球心与条心仍逐像素同轴（flex align-items:center 契约不破）。溢出条缘的上下 8px 是画布透明角（光晕最大半径不出原 content 盒），下方落 dock 呼吸缝、上方落消息列底空气带。

**几何账**（实测，tests/composer-mobile-slim-shots.mjs 探针）：条高 = 光球足印 + bar padding + 2px border ⇒ 修前 64+12+2 = **78px**，修后 48+8+2 = **58px**，**省 20px（−25.6%）**；`#chat` padding-bottom 让位 152 → 132px（main.js 读 `#input-area.offsetHeight`，JS 零改动自动跟收）＝消息列可视高 +20px。

## 保全面（令文红线逐条）

| 红线 | 处置 | 实证 |
|---|---|---|
| 横向布局不动 | 两刀全在纵轴（bar padding 纵档、wrap 纵边距） | 探针 padding 横值 12px 原值 |
| 头像（光球）/附件/发送钮尺寸不动 | 球渲染盒 64、发送/附件 36 全原值 | 探针 `orbBtnH=64 sendH=36 attachH=36` 前后同值 |
| 单行明显变薄 | 78 → 58（−20px） | 双主题 GEOM 前后对照 |
| 多行自动长高 + 封顶不变 | textarea scrollHeight 增长逻辑与 `max-height:200px` 零触碰；长高后 #input-wrap 接管条高（光球足印 48 恒小于行高不参与驱动） | 5 行后 textarea 34→200、条 58→210 |
| 垂直居中不显挤 | flex align-items:center 原样；球 40px/钮 36px 在 58px 条内各留 8-10px 呼吸 | 探针 Δsend/Δattach/Δorb = 0 |
| 触控热区不小于可用下限 | 热区全数原值（36/36/64，未缩） | 探针 M3 腿 |
| 桌面零触碰 | 全部 body.mobile-layer 作用域；桌面（含 ≤768px 窄窗非手机层档）padding/条高/光球边距钉 6px·6px/78/0px 原值 | D 腿 before/after 同值 |
| dock 呼吸缝不动 | `#input-area` padding-bottom 公式（含 --dock-gap 10px）原样 | 探针 gapBelowBar=72 前后同值 |

## 范围申报（按令「分开实现的先不动」）

- **canvas ask 输入条**：独立组件（`ask.css` `.ask-input` 自带几何：7px padding + 32px 发送钮，与主会话不共输入组件、不共几何 token——只共材质/色彩族 token）⇒ 本批未动，与主会话收窄后不同款，待作者另令。
- 好友会话输入框（窗口面）、`.fa-input-bar` 弹窗输入条：非「手机端消息输入框」面，未动。

## 验收证据

- 实拍：`evidence/20261101_composer-slim2/{before,after}/`——手机 **375×812** 亮/暗双主题 rest/multiline/attach 整视口截图各 3 帧 + 桌面 1280 rest 对照帧；before 跑修前树、after 跑修后树。
- 主会话输入回归三 spec（隔离实例：静态树 + routeWebSocket 桩，不碰宿主 8080）：
  - IME：`tests/ime-guard.spec.mjs`
  - 发送：`tests/f1-send-queue.spec.mjs`
  - 中断/busy 停止链：`tests/busy-lifecycle.spec.mjs`
- 本批几何契约 + 前后对照：`tests/composer-mobile-slim-shots.mjs`（G 腿钉 before 78 / after 58；M1-M5 居中/热区/长高/遮挡不变量；D 腿桌面零触碰钉；C 腿 console 零 error）——before/after 两跑 ALL PASS、console 零新增。
