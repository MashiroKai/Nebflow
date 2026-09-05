# #37 移除「最小清理字符数」配置项 · 前端 — QA 验收报告（PASS 放行）

- **日期**：2026-09-01
- **验收人**：qa-frontend
- **产出者**：Frontend
- **交付**：worktree `/tmp/nb-rm-minchars-frontend`，分支 `feat/rm-minchars-frontend`，commit **8d405164**（基于 main @7aa183a6）
- **改动**：4 文件，全在 web/：sidebar.js / state.js / locales/en.js / locales/zh-CN.js
- **验收基座**：隔离真实 http4s 实例 `http://localhost:8097`（worktree /tmp/nb-rm-minchars-frontend，java PID **82280**，health 200）
- **宿主保护**：8080 = PID 69175 我宿主全程未触碰；8097 已 PID 验身为独立 java 实例（cwd=`/private/tmp/nb-rm-minchars-frontend`）
- **结论**：**PASS —— 放行。**

---

## 验收条件逐项核对

### ① 界面两项：无「最小清理字符数」 ✅
真实实例 DOM 断言（Playwright）：
```json
LIGHT {"labels":["清理旧工具结果"],"numInputs":2,"rows":1,"hasMinCharsEl":false,"textHasMinChars":false}
DARK  {"labels":["清理旧工具结果"],"numInputs":2,"rows":1,"hasMinCharsEl":false,"textHasMinChars":false}
```
- **`ttl-min-chars` 元素不存在**（present.ttl-min-chars=false）
- 恰好 **2 个 number 输入**（ttl-minutes / ttl-keep-recent，值 60/5）
- **`$('#ttl-min-chars')` 为 null**，无「最小清理」文案
- 设置区 rows=1（原文 diff 删除了第 3 行 `rowHtml('settings.minCharsLabel'...)`），**无布局残留空行**
- 截图 `/tmp/qa-rm-minchars/settings-light.png` / `settings-dark.png` 目检：仅两项输入 + 开关 + 保存按钮

### ② 保存/加载正常 + payload 只含 3 字段 ✅
- **payload 源码权威确认**（sidebar.js 保存 handler，diff 明确）：
  ```js
  config: { enabled, ttlMinutes: values.ttlMinutes, keepRecent: values.keepRecent }
  ```
  **无 minChars 字段**。DOM 亦无 ttl-min-chars 输入可取值。
- **POST-SAVE echo**：改值 120/9 后保存，回显输入框 `mVal=120, kVal=9`，`minCharsEl=false`（正常）
- WS 端到端至此受未认证实例限制（见下方说明），payload 构造已由源码+DOM 双证据闭环。

### ③ 旧配置兼容：含 minChars 照常加载，前端忽略未知字段 ✅
renderTtlSection 仅读取：
```js
${rowHtml('settings.ttlMinutesLabel', ..., cfg.ttlMinutes ?? TTL_DEFAULTS.ttlMinutes)}
${rowHtml('settings.keepRecentLabel', ..., cfg.keepRecent ?? TTL_DEFAULTS.keepRecent)}
```
- **根本不读 `cfg.minChars`** —— 旧配置携带 minChars 字段时被自然忽略（无代码路径引用）
- state.js 注释明确：「An old backend echo may still carry minChars (removed ...); the panel simply ignores unknown fields」
- 与后端「旧配置里的值静默忽略」契约一致。

### ④ i18n 无残留 + 键数对齐 ✅
```bash
node i18n 键数校验
en keys: 879  zh keys: 879
only in en: NONE  only in zh: NONE
KEYS ALIGNED
```
- en.js / zh-CN.js 各删 `settings.minCharsLabel` / `settings.minCharsHint` 两条 key
- **无单边键**，键数 879=879 对齐
- 界面无 `settings.` 裸串（real DOM `rawKeys:[]`），无未翻译 key 泄漏

### ⑤ 既有行为不变：关闭置灰 + 越界/非整数校验 toast ✅
真实实例实测（WS 配置序列）：
```json
INIT (enabled default false)      → {enabledOn:false, mDisabled:true, kDisabled:true}
AFTER toggle->ON                  → {enabledOn:true,  mDisabled:false, kDisabled:false}
AFTER toggle->OFF (greyed)        → {enabledOn:false, mDisabled:true, kDisabled:true}
OUT-OF-RANGE ttlMinutes=0 → sent:0  toast:「结果保留时限（分钟）需在 1 – 43200 之间」
NON-INT keepRecent=2.5   → sent:0  toast:「保留最近 N 条必须是整数」
```
- 开关关闭 → **两个输入均 disabled**（灰）（enabled 禁用列表已从 3 项缩为 2 项：`['ttl-minutes','ttl-keep-recent']`）
- 越界（ttlMinutes 1–43200）/非整数（keepRecent）→ **前端校验 toast 弹出 + 不发送 WS**（sent=0）
- `parseTtlInt` 拒绝非整数，`fieldKey` 只剩 2 项

---

## 附加独立核验
- **checkJs 门禁**：`326 = baseline`，`checkJs PASS: no new errors above baseline` ✓
- **minChars 残留**：业务代码仅剩 2 处**注释**（sidebar.js:521「minChars was removed by author ruling」、state.js:115「old echo may still carry minChars」），**零标识符/元素 id/i18n key 残留** ✓
- **改动范围**：`git diff 7aa183a6..HEAD --stat` = 4 文件 / 15+/19-，全在 web/（sidebar/state/locales），**零 Scala** ✓
- **本分支 HEAD**：8d405164（feat/rm-minchars-frontend），main @7aa183a6 祖先 ✓

---

## 关于「未验点」（WS 端到端）的说明
隔离实例 `:8097` **未认证**（WS 握手 403，无 token）→ sendWs 因 `state.ws.readyState!==OPEN` 静默丢弃，故 Playwright 捕获不到 setToolResultTtl 的真实发送。这是**环境限制（未登录实例），非本次改动缺陷**。

payload 形状已由双证据确定：
1. **源码**（sidebar.js diff）：`config: { enabled, ttlMinutes, keepRecent }` —— 权威构造点，无 minChars
2. **DOM**：无 `ttl-min-chars` 输入元素可取值，保存 handler 无 minChars 解析/校验/payload 分支

WS 落盘→回显的**后端**环节（fast-micro-compaction 落盘）属后端职责，交付声明「后端已同步移除该字段」。

## 结论
**PASS —— 放行。** 五项验收条件全绿：界面恰好两项无最小清理字符数；payload 只含 3 字段；旧配置含 minChars 兼容忽略；i18n 键数 879=879 对齐无残留；关闭置灰 + 越界/非整数校验 toast 不变。

安全：8097 = java PID 82280（cwd=/private/tmp/nb-rm-minchars-frontend），8080 = 69175 宿主全程未触碰。隔离实例确认在跑新代码（served sidebar.js 无 minChars rowHtml，非快照陈旧）。另注：8096 端口有另一 agent 的实例（/tmp/nb-a2a-frontend），我未触碰。

报告：~/.nebflow/docs/Nebflow/20260901_rm-minchars-frontend-qa.md
