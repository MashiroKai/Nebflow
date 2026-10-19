# 标准 logo 全范围应用 · 主仓控制台面（brand-standard-console 批，2026-10-19）

作者令：`~/Downloads/STAND_Illustrator/`（10 资产 × ai/eps/pdf/svg）为默认标准 logo 源；
旧黑猫头像退役，主仓控制台全范围替换。本批只管主仓控制台（stand-website / link-server
各有平行批，未触碰；Logto 配置未动；零 push）。

## 一、触点位摸底（替换前 → 替换后）

集中机制（沿用，未新造）：`brand.conf` `activeLogo` + `src/main/resources/web/brand/logos.json`
目录 + `scripts/switch-logo.py` 一条同步命令 + `brand/current.json` 清单 +
`StaticRoutes.brandScriptTag` 注入 `window.__BRAND__`。

| # | 消费 slot | 替换前 | 替换后 |
|---|---|---|---|
| 1 | 活动栏 avatar 静态兜底 `index.html:88-97`（`#activity-avatar` picture） | `/brand/black-cat/source.png` 亮暗同图（黑猫） | 亮=`/brand/stand/illustrator/symbol-light.png`（黑 S）、暗=`symbol-dark.png`（白 S）；注入网关经 `__BRAND__.logoUrl/logoDarkUrl` 覆盖为 release `symbol-{light,dark}-128.png` |
| 2 | 设置头像 slot `js/sidebar.js:793-798`（`brand.logoUrl/logoDarkUrl`） | release `light-128/dark-128.png`（方块 app icon） | release `symbol-light-128/symbol-dark-128.png`（裸 S 标，亮暗对） |
| 3 | favicon 位图族 `index.html:19-24` + web 根 `favicon-{16,32,180,192,512}.png`/`favicon.ico` | 旧 df9c 版 | 新 release `f9e37ba994f63d8f`：亮/暗 tile 从 `stand-app-icon-light/dark.svg` 自转（`?v=` 摘要已随生成器刷新） |
| 4 | favicon SVG（新增 slot）`index.html:22` + web 根 `favicon.svg` + 路由白名单 | —（无） | release `favicon.svg`：light tile 为默认，内嵌 `@media (prefers-color-scheme: dark)` 反色（黑 tile 白 S）；`WebSocketRoutes.scala` Root 白名单 + `verify-web-assets.mjs` ROOT_WHITELIST 各加 `favicon.svg` |
| 5 | `current.json` 清单 | revision df9c…，logoBright4/Dark4=128 tile | revision `f9e37ba994f63d8f`，logoBright4/Dark4=`symbol-*-128.png`，faviconSvg=release favicon.svg；`logo`/`emailLogo`/`appleTouch` 仍 app-icon 512/180（邮件与 PWA 面） |
| 6 | `brand.conf` logoUrl/logoDarkUrl（注入源） | df9c 128 tile | f9e3 symbol-128 对 |
| 7 | `js/brand.js` 静态兜底 fallback | df9c 128 tile | f9e3 symbol-128 对 |
| 8 | 桌面安装图标 `packaging/icons/nebflow.{png,ico,icns}` | 旧 light 源渲染 | 同一命令重渲（stand-app-icon-light 源），仍由 switch-logo.py 单一机制产出 |
| 9 | 黑猫源资产 `brand/black-cat/source.png` | 在库且被 slot #1 引用 | **退役**：文件删除、logos.json 目录项移除、`switch-logo.py` 兜底 logo id 改 `stand-illustrator` |
| 10 | 矢量源（新增，收入既有集中位置） | — | `brand/stand/illustrator/svg/` 收入包内全部 10 张 SVG（唯一真源）；位图 catalog 源由 SVG 自转（rsvg-convert）：logo-512-*、symbol-*、wordmark-*、horizontal-*、horizontal-compact-* |

wordmark / horizontal lockup：控制台面当前无 wordmark/组合 lockup 消费 slot（全仓 grep
仅 catalog 引用）；矢量源与位图源已按对入库（black→light、white→dark），供后续 slot
直接取用。安装器 banner 像素矩阵（install.sh/ps1 + check-logo-matrix.py）是独立真值链
（pixil 工件钉死），非黑猫面，本批未动。

## 二、生成器扩展（scripts/switch-logo.py，同机制内）

- 目录项可声明 `avatarLight/avatarDark`（裸标源）：渲染为 release `symbol-{light,dark}-128.png`，
  清单 logoUrl/logoBright4/logoDark4 指向之；favicon/app-icon 族仍出自 light/dark tile 源。
- 目录项可声明 `svg`（light app-icon SVG）：发布 release `favicon.svg`（含暗色媒体查询反色）
  并复制到 web 根，清单 faviconSvg 指向之；web 根副本纳入 favicon `?v=` 摘要刷新正则。
- digest 材料纳入 avatar+svg 源；`sourceSha256` 维持 light/dark 两键（清单 schema 不变）。
- 旧 `black-cat` 兜底 id 退役（黑猫已不在目录）。

## 三、验收

| 门 | 结果 |
|---|---|
| `node scripts/build-web.mjs` | **OK，orphans: 0**（164 js 全在 bundle 图；exemptions 5 为既登记项） |
| Scala 品牌链 spec（sbt testOnly BrandingSpec / BrandInjectionSpec / BrandSubtreeRoutesSpec） | **18/18 PASS**（含「live constants match brand.conf」与 /brand 子树服务面）；白名单改动后 `sbt compile` 复验通过 |
| `verify-web-assets.mjs`（source 模式，隔离静态服 8195） | **checked=469 skipped=0 failures=0 PASS**（新 svg 源/release 件/favicon.svg 全 200） |
| 亮/暗整页截图 | `shots/console-light.png`（黑 S 于活动栏顶）/ `shots/console-dark.png`（白 S）；特写 `shots/avatar-{light,dark}.png` |
| favicon 渲染抽查 | `favicon-32.png`（S 方块 tile）、`favicon.ico`（16/24/32/48/64/128/256 全尺寸）、`favicon.svg`（暗色反色媒体查询就位）、app-icon 512 亮暗对 —— 均存本目录 |
| HTTP 探针（两主题同读数） | 新 slots 全 200；`/brand/black-cat/source.png` = **404（退役生效）**，见 `shots/http-probes-*.json` |
| e2e-avatar-flicker.cjs | 22/26 PASS；RED B0/C1/F1/F2 —— **与 pristine 主仓检出逐字相同（基线预存，非本批引入）** |
| tests/sidebar-restructure.spec.mjs | 4 PASS / 2 FAIL（T1 侧栏序、T3 头像缓存）—— **pristine 主仓同样 2 FAIL（基线预存）** |
| tests/activity-bar-smoke.mjs | TimeoutError —— **pristine 主仓同样 TimeoutError（基线预存）** |

复现：`NODE_PATH=/opt/homebrew/lib/node_modules node shoot-console.mjs`（隔离 8189 端口，
永不触碰宿主 8080）。

## 四、边界与移交

- 零 push；仅本地 pathspec 提交于工作分支。
- stand-website / neblink-server（link-server）两仓未触碰 —— 官网 OG（`lib/brand-og.ts`）、
  PWA manifest、独立登录页与 Logto 品牌同步由平行批以同一 `switch-logo.py --website … --auth-repo …`
  流程消费本批入库的源与清单。
- `brand.conf` `activeLogo = stand-illustrator` 未变（选择未变，源与生成面已换标准包）。
- 旧 release 目录（df9c…/c746…）按「原图和旧版本保留」惯例原样留存。
