# 桌面好友/群聊扁平化 · 气泡浅蓝 · 胶囊滚动端区分（2026-11-07）

作者令（逐字）：
1.「canvas要把胶囊的滚动逻辑，电脑端和手机端区分开，电脑端保持之前的上下滚动不要变。」
2.「然后把电脑端好友/群聊的前端 改回类似微信一样的扁平化的框，不要现在的跟主会话一样的布局了，」
3.「然后用户输入的气泡，改回之前的浅蓝色。」

本目录 = 同一把尺子下的改前/改后实拍（1440×900 桌面 / 375×812 手机 × 亮/暗），
供 root 侧 Pop 面板直接引用。复制即可打开。

## 怎么拍的（可复现，零后端零端口）

```
# 改前（baseline = 本批三个提交的父提交）
git worktree add /tmp/nf-baseline <父提交> --detach
ln -s "<repo>/node_modules" /tmp/nf-baseline/node_modules
cd /tmp/nf-baseline && node tests/canvas-capsule-ring.spec.mjs     # → evidence/20261023_capsule-six/shots/1-ring-open-dark.png
SHOTS_STATE=before SHOTS_OUT=<本目录上级>/thread-ui-unify__before node tests/thread-ui-unify-shots.mjs

# 改后（本 worktree）
cd <本 worktree> && node tests/canvas-capsule-ring.spec.mjs
SHOTS_STATE=after  node tests/thread-ui-unify-shots.mjs            # → reports-evidence/thread-ui-unify__after/
```

`tests/thread-ui-unify-shots.mjs` 用 `page.route` 从磁盘注入本仓 web 树 + `routeWebSocket`
桩帧（configData / sessionList / historyPage / dropbox-history）+ 好友/群假 API ⇒
**不开服务、不占端口、不碰宿主实例 :6138**。胶囊两份由 `canvas-capsule-ring.spec.mjs`
自带的隔离静态服务器（端口 8167）产出。

## 1. 胶囊滚动：桌面 = 上下竖环（改前改后对照）

| 文件 | 形态 |
|---|---|
| `capsule-hover__before-desktop-dark.png` | 改前（转盘批 afcd09575）：邻签**左右**排开（横排转盘） |
| `capsule-hover__after-desktop-dark.png` | 改后：邻签**上下**排开（竖弧 + rotateX 反号等幅）＝ 2026-11-07 12:15 之前的原形态 |
| `capsule-hover__after-desktop-vertical-light.png` | 改后桌面 hover 环（亮色腿，1440×900）—— 上下两枚邻签与中心签同列 |
| `capsule-touch__after-phone-landscape-horizontal.png` | 改后**触屏腿**（844×390 横屏，`pointer: coarse`）：仍是常驻横排 + 图标档邻签 ⇒ 手机/平板腿未受本批影响 |

机械读数（`canvas-capsule-touch-row.spec.mjs` G2 项，改后）：邻签位姿 `y=±38.0px`、
`x=0px`、`rx=∓34deg` —— 纵向分离、横轴恒 0。手机/触屏腿（`@media (pointer: coarse)
and (hover: none)` 横排档）在改前改后逐字相同，本批未触碰该档（`canvas-capsule-touch-row.spec.mjs`
T 组 26 项全 PASS，含「手机层走自己的 .mf-strip，桌面环不开」零串扰）。

## 2. 桌面好友/群聊消息面：扁平直框（改前/改后对照）

| 族 | 改前 | 改后 |
|---|---|---|
| 好友 · 桌面亮 | `thread-friend__before-desktop-light.png` | `thread-friend__after-desktop-light.png` |
| 好友 · 桌面暗 | `thread-friend__before-desktop-dark.png` | `thread-friend__after-desktop-dark.png` |
| 群 · 桌面亮 | `thread-group__before-desktop-light.png` | `thread-group__after-desktop-light.png` |
| 群 · 桌面暗 | `thread-group__before-desktop-dark.png` | `thread-group__after-desktop-dark.png` |

计算样式读数（`thread-ui-unify-shots.mjs` 的 `measure()`，1440×900，真 Chromium）：

| 项 | 改前 | 改后 |
|---|---|---|
| 出向泡 `border-radius` | `16px 16px 4px` | **`6px`** |
| 出向泡 `box-shadow` | `none` | `none`（本来就无影；本批连入向柔影一起去掉） |
| 入向泡 `border-radius` | `16px 16px 16px 4px` | **`6px`** |
| 入向泡 `border` | `1px solid rgba(0,0,0,0.04)`（whisper 柔边） | **`1px solid rgba(91,127,191,0.08/0.1)`**（etched 实描边＝「框」） |
| 出向泡 `background-color` 亮 | `color(srgb 0.584314 0.92549 0.411765 / 0.97)` = #95ec69（微信绿） | **`color(srgb 0.803922 0.85098 0.92549 / 0.97)` = #cdd9ec（浅蓝）** |
| 出向泡 `background-color` 暗 | 同上（绿，亮暗同值） | **`color(srgb 0.160784 0.207843 0.301961 / 0.97)` = #29354d（暗档浅蓝）** |

## 3. 未受非预期影响（对照组）

| 项 | 证据 | 读数 |
|---|---|---|
| 主会话（`.row > .bubble`）布局 | `main-session__before-desktop-light.png` / `main-session__after-desktop-light.png` | `radius 16px 16px 4px`、`maxW 80%`、`pad 10px 14px` 改前改后**逐字相同**（扁平化选择器带 `.fm-msg` 前缀，主会话零命中） |
| 手机层好友/群（375×812） | `thread-friend__before-mobile-light.png` / `thread-friend__after-mobile-light.png`、同族 group 两张 | 泡 `radius 16px 16px 16px 4px` 改前改后**逐字相同**；仅底色随「气泡浅蓝」那条令变化（全局单点令牌，主会话/好友/群同源，属该令的预期范围） |

## 4. 气泡浅蓝的出处（不是猜的色值）

`git log -S"color-bubble-user"` 全历史只有 4 个取值：微信绿 `#95ec69`、浅蓝
`#cdd9ec`/`#29354d`、近白 `#ecebe7`/`#33312d`。其中唯一带蓝相的是浅蓝档，首次落地 =
commit `9158a5fab`（UI 巡检批 2026-11-07 11:10，作者令「禁止出现微信式高饱和绿…该
颜色应是会话气泡组件的单点 token，好友页/群页/主会话三处同源」）。本批逐字复用该值，
零新色值；`--nf-bub-ours-ink` 回 `--color-text`（亮 11.7:1 / 暗 10.0:1，均过 AA）。

## 5. 门禁与既有红（如实登记）

| 门禁 | 结果 |
|---|---|
| check-bubble-side / check-msgstyle-single-source / check-flat-buttons / check-self-explaining | PASS |
| check-design-tokens | FAIL 1 项 —— `css/lightbox.css` 颜色字面量 5→12（**基线既有红**，本批未触碰该文件） |
| check-glass-unify | FAIL 2 项 —— `css/lightbox.css:135-136` backdrop-filter（**基线既有红**） |
| check-component-contract | FAIL 73 项 —— chatSearch.js / sidebar / bgAgentMobileChrome.js（**基线既有红**，均为 JS 侧，本批零 JS 结构改动） |
| canvas-capsule-ring.spec | 42 PASS / 1 FAIL —— 唯一 FAIL = CR2-5（胶囊材质与 #input-bar 逐项相等），**父提交同一脚本同读数复现**（`ref.blur=blur(24px) saturate(1.15)` vs `capsule.blur=blur(40px) saturate(1.4)`），属 USTC main 合流后的令牌漂移，非本批引入 |
| canvas-capsule-touch-row.spec / capsule-trackpad-feel.spec | 26 PASS / 0 FAIL、16 PASS / 0 FAIL |
| bubble-side-color.spec | 22 checks / **0 failures**（父提交基线 6 failures）。E1a 我方档正文实测 **亮 11.79:1 / 暗 10.34:1**（#1b1e26 on #cdd9ec·97% / #e8eaed on #29354d·97%）—— 浅蓝档两主题各自过 AA 的机械证据 |
| contact-shell.spec | 60 项 · FAIL 0 项（窗壳/窗头/输入条零回归） |

`bubble-side-color.spec` 的期望色已从硬编码 `#95ec69` 改为**运行时读
`--color-bubble-user` 令牌计算值**（见 `test(web): 气泡归属方 spec…` 提交）：该断言
验的是「哪一方吃哪枚令牌」，钉死色相会让每次作者改色都假红。
