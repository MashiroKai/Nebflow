# 客户端网页标签 STAND 大写统一（作者令）· 2026-10-19

作者原话：「所有的客户端网页标签的STAND都要用这个大写」。规则 = 品牌词统一大写
**STAND**，既有标题结构/文案保留；动态 title 只动品牌词。

## 一、title 源全量清点（改前）

| # | title 面 | 改前 | 判定 |
|---|---|---|---|
| 1 | `web/index.html:16` | `<title></title>`（中性空值，brand.js 回填） | 不改（设计如此） |
| 2 | `web/js/brand.js:140` | `document.title = brand.lowerName` → "stand" | **改** |
| 3 | `web/js/main.js:14` | `document.title = 'nebflow (embedded)'` | **改** |
| 4 | `AuthRoutes.scala:541` 登录回执页 | `<title>${Branding.lowerName} 登录</title>` | **改** |
| 5 | `AuthRoutes.scala:565` 已退出登录页 | 同上 | **改** |
| 6 | `AuthRoutes.scala:583` 切换账号页 | 同上 | **改** |
| 7 | `ConsoleTicketRoutes.scala:120` | `<title>Console ticket</title>` | 不改（无品牌词，见登记⑤） |

其余 `<title>`/`document.title` 命中均为非本客户端面：WebFetch/WebSearch 工具解析外站、
seed 插件资产模板、tests fixtures/harness、reports/demo/design 样张、vendor 库内部——不属于
web 客户端浏览器标签页标题。

## 二、改动点清单（文件:行）

| 文件 | 行 | 改动 |
|---|---|---|
| `src/main/resources/web/js/brand.js` | 134-142 | `document.title = brand.lowerName.toUpperCase()`；注释更新：作废 2026-08-25 小写裁定，引用本作者令；transform 仅此展示点，lowerName 其余语义（存储键/jar 名）不动 |
| `src/main/resources/web/js/main.js` | 3, 14-15 | import 增加 `brand`；embedded 守卫 title → `` `${brand.lowerName.toUpperCase()} (embedded)` `` |
| `src/main/scala/nebflow/shared/Branding.scala` | 75-82 | 新增派生 val `upperName = lowerName.toUpperCase(Locale.ROOT)`（无 brand.conf 键，改名日自动跟随；Locale.ROOT 防本地化大小写映射） |
| `src/main/scala/nebflow/gateway/AuthRoutes.scala` | 542 / 568 / 586 | 三处 `<title>` 品牌词 lowerName → `Branding.upperName`（"STAND 登录 / STAND 已退出登录 / STAND 切换账号"）；两个 scaladoc 的「字节等价、小写形态」表述同步修正 |
| `src/test/scala/nebflow/shared/BrandingSpec.scala` | 41-43 | 新增断言 `Branding.upperName == "STAND"`（title 相关既有测试不存在，故仅此最小同步） |

动态 title 面（会话名拼接等）清点结果：本客户端**不存在**其它 `document.title` 赋值点
（全仓 grep 复核，唯一两处即上表 2/3），故无拼接面需要局部处理。

## 三、验证

1. **grep 复核零漏网**：`document.title` 写点仅 brand.js/main.js 两处（均 toUpperCase）；
   `src/main` 下 `<title>` 面仅 AuthRoutes×3（upperName）+ ConsoleTicketRoutes（无品牌词）
   + index.html（中性空）；`lowerName} 登录|已退出|切换`、`nebflow (embedded)` 零命中。
2. **运行时断言**（静态直服 web/，走 brand.js 白名单 fallback 路径，headless chromium，
   `verify-title.mjs`）：
   - client tab → `document.title === "STAND"` **PASS**
   - embedded 守卫（iframe 触发 nfEmbedded）→ `"STAND (embedded)"` **PASS**
   - 读出单：`title-readout.png`
3. **真实浏览器标签形态**：Chrome 真窗打开静态直服客户端，标签条截图 `tab-client-STAND.png`
   （标签页显示大写 STAND + 站点图标；页面因无网关出现登录卡/插件 404 属静态直服预期，不影响 title 证据）。
4. **Scala**：`sbt "testOnly nebflow.shared.BrandingSpec nebflow.gateway.BrandInjectionSpec"`
   → 12/12 PASS（2026-10-19 20:50，编译全量 571+772 源无错）。
5. 服务端三页（AuthRoutes）title 由 `Branding.upperName` 模板渲染，BrandingSpec 值断言 +
   模板 grep 覆盖；未起真网关渲染（重依赖，超出小批射程）。

## 四、登记（不改，归后续品牌清扫批）· nebflow→STAND 用户可见面残留

- ① `web/js/main.js:16` embedded 守卫页**正文**「nebflow 预览已停止——…」（title 面本批已改，正文文案属清扫批）。
- ② `web/brand/logos.json` logo 目录显示名「Nebflow pixel / classic / login」（logo 选择器可见名；历史资产命名，是否改名需作者定）。
- ③ locale 文案（`js/locales/en.js`）：`settings.jevHint` 的 `~/.nebflow/secrets/`（=homeDirName 真值，路径面合法，非残留）；`content.plugin.nebflow-plugin-creator.*` 等插件/skill 身份名（内部 id 语义，非品牌展示词）。
- ④ 执行器 id `nebflow`（modelPanel/delegateChainStub 等 role/executor 比对面）——内部标识符（D2 稳定 id 语义），非用户品牌文案。
- ⑤ `ConsoleTicketRoutes.scala:120` 浏览器标签面 "Console ticket" 完全无品牌词——若需品牌化（如 "STAND Console ticket"）归清扫批裁。
- ⑥ 存储键兼容层 `nebflow_sidebar_*`、`__b||'nebflow'` fallback（index.html:76）——非用户可见，改名禁区的兼容双读，保持不动。

## 五、移交项

- **官网仓 stand-website** 的 `<title>` 面不在本仓射程——大写 STAND 统一需在该仓另开一批。

## 复现

```bash
python3 -m http.server 8791 -d src/main/resources/web &
node evidence/20261019_204721_stand-title-case/verify-title.mjs   # 两个 PASS
sbt "testOnly nebflow.shared.BrandingSpec nebflow.gateway.BrandInjectionSpec"
```
