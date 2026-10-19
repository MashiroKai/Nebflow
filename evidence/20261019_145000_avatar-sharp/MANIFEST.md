# avatar slot 模糊排查 + 修复（avatar-sharp 批，2026-10-19）

作者反馈：brand-standard-console 批（黑猫→stand-symbol 亮暗对）后「头像 slot 很模糊」。
本批只动 avatar 供图链路；stand-website / link-server 未触碰；Logto 配置未动；零 push。

## 一、根因（实测，与预查线索不同）

预查线索称「128px 供图 < slot×retina 2x 被拉糊」。**量清后该理论不成立**：

| 消费 slot | CSS 尺寸 | @2x 需供 | 128 供图（艺术区 128×105） | 结论 |
|---|---|---|---|---|
| 活动栏 `.activity-avatar-logo`（nav.css） | 24×24 | 48×48 | 128×105 ≥ 48 | 分辨率充足 |
| 设置 `.settings-avatar-logo`（sidebar.css） | 32×32 | 64×64 | 同上 ≥ 64 | 充足 |
| 执行器徽标 executorIcons（taskList 14 / chain 12 / picker 20） | ≤20 | ≤40 | 充足 | 充足 |

真根因（实证，shots/ 对比）：`.activity-avatar-logo` / `.settings-avatar-logo` 残留
**`image-rendering: pixelated`**（退役像素 logo v4 的调谐规则）。它把 128px 位图的
降采样变成最近邻（NN），S 曲线边缘全部阶梯化——同一 128px 供图、同一槽位，仅摘除
该规则即恢复平滑（`before-128` vs `before-128-nopx` 两组特写逐像素可判）。512px 对照
组（`ctrl-512`）证明非分辨率问题。

## 二、修法（沿用集中换装机制，未新造第二套）

slot 全是 `<img>`，**全部能吃 SVG ⇒ 首选直挂**（任务令②首选腿）：

- `logos.json` stand-illustrator 增 `avatarLightSvg/avatarDarkSvg` 声明
  （`stand-symbol-black.svg` 亮 / `stand-symbol-white.svg` 暗，矢量唯一真源）。
- `scripts/switch-logo.py` 增 SVG 发布腿：字节原样入 release 目录
  `symbol-{light,dark}.svg`、入 digest 材料（新 revision `71a456e2d388b27e`，缓存自动
  失效）、清单新增 `logoBrightSvg/logoDarkSvg`；**brand.conf logoUrl/logoDarkUrl 与
  brand.js 兜底改指 SVG**。`logoBright4/logoDark4` 保持 128 PNG 不变——
  sync-brand-auth.py 以 `data:image/png` 内联它们（raster-only 消费面），官网
  brand-assets.json 发布拷贝亦不受影响；`symbol-*-128.png` 继续生成。
- `index.html` 静态兜底（无注入回落）两腿改指 illustrator SVG 源。
- CSS 摘除两处退役 `image-rendering: pixelated`（矢量标下该规则只会让任何位图回落
  再次 NN 劣化），注释更新。

控制台无「吃不了 SVG」的槽，故第二腿（256/512 位图档）按实需未产出——多产即
build-web 孤儿。executorIcons 三处消费（12–20px）同一 `<img>` 机制，同源获益。

## 三、验收

| 门 | 结果 |
|---|---|
| `python3 scripts/switch-logo.py stand-illustrator --client-only --check` | **36 outputs, 0 differ**（switch-logo-check.txt） |
| `node scripts/build-web.mjs` | **orphans: 0**（build-web.txt） |
| Scala 品牌链 spec（BrandingSpec / BrandInjectionSpec / BrandSubtreeRoutesSpec） | **18/18 PASS**（spec-output.txt） |
| `verify-web-assets.mjs`（source 模式，隔离静态服 8237，未触 8080） | **checked=491 skipped=0 failures=0 PASS**（verify-web-assets.txt） |
| `e2e-avatar-flicker.cjs` | 22/26 PASS；RED B0/C1/F1/F2 —— 与 brand-standard-console 批记录的 pristine 基线**逐字相同**（预存，非本批引入） |
| `check-js-types.mjs` | 1 败：`askAttachments.js`（askuser #551 批文件，本批未触碰，预存基线）；本批触及文件零告警 |

## 四、对比图（同一 slot、修前 128 供图 vs 修后 SVG，1x/2x 实拍 + 8x 特写）

`shots/compare/` 四行对比图（行序：before-128 / before-128-nopx / ctrl-512 / after-svg-rel）：

- `compare-light-dsf2-activity-paired.png`（活动栏·配对态·亮·2x）—— 修前阶梯边 vs 修后平滑
- `compare-dark-dsf2-activity-paired.png`（暗面同读数）
- `compare-light-dsf2-settings.png` / `compare-dark-dsf2-settings.png`（设置头像 56 环 + 32 标）
- `shots/` 原始元素截图（dsf1+dsf2 全矩阵）、`shots/mag/` 8x 特写、`shots/report.json`
  逐格 src/几何读数（修前 natural 128×128；修后 SVG 按布局尺寸栅格化）

复现：`NODE_PATH=/opt/homebrew/lib/node_modules node shoot-avatar.mjs [variant …]`
（隔离 8191 端口，永不触 8080；before 组经 forcePixelated 在修后树上复现修前 CSS 态，
首组 before 实拍于码面改动前）。

## 五、边界

- 改动仅限 avatar 供图链路：logos.json 声明、switch-logo.py 生成腿、brand.conf/brand.js/
  index.html 指向、两处 CSS 残留规则、release 目录重生成（旧 f9e37 目录按惯例保留为历史
  revision，官网已发布 URL 不受影响）。
- 官网/认证仓的发布拷贝需另行显式 `switch-logo.py --website --auth-repo` 才刷新，本批未跑
  （manifest 位图字段值不变，两仓当前内容继续有效）。
- 零 push；pathspec 小步提交于 delegate/external-zcode-26afe2a0。
