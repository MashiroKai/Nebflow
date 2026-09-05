# 重启后前端侧补验报告（③历史 Pop 记录 + ⑤console）— 真实实例

- **日期**：2026-09-01
- **验证人**：qa-frontend
- **上位指令**：Manager [PARALLEL]「重启后补验前端侧先行一项」
- **环境**：**真实实例 8080（宿主，只读验证）**，main @9bb38304（作者 09-01 09:18 重启）
- **结论**：**③ PASS；⑤ PASS（含 2 条非阻塞说明 + 1 条已验证清理的自测事件）**

---

## ③ 历史 Pop 记录运行时验证（#25 修复：pop.readFile 展开 `~`）

**结论：PASS。** 真实实例上 `~` 展开生效，旧 Pop 记录可打开，且「路径非法 vs 不存在」错误提示正确区分。

### 证据 1 — 打开真实历史 Pop 记录
会话历史（scrollTop 至顶）渲染出 3 条 `.pop-tool-card`（`Pop — Opened Nebflow 新架构方案 v3/v4/v5 in Canvas`）。点开第一条 → Canvas 打开 `20260831_project-node-architecture.md` 标签页，内容加载（active pane 29912 字符，无 `file-unreadable` 骨架）。**能正常打开显示内容**。

> 发现的这 3 条记录存储的是**绝对路径** `/Users/dev/.nebflow/docs/Nebflow/20260831_project-node-architecture.md`（未走 `~` 分支）。

### 证据 2 — 构造 `~` 路径补验（参考 7251b2e5 修复语义）
因上述记录用的是绝对路径，为实测 `~` 展开，依指令在真实实例上构造 `pop.readFile` 用例（dispatch `workspace-open-item`，走同一 canvas→pop.readFile 代码路径）：

| 用例 | 发送 path | 结果 | 判定 |
|---|---|---|---|
| a `~` 现有文件 | `~/.nebflow/docs/Nebflow/20260831_project-node-architecture.md` | 请求以 `~` 发送 → 后端展开 `/Users/dev/...` → 内容加载（no error） | ✅ `~` 展开生效 |
| b `~` 不存在 | `~/qa-nonexistent-nope-xyz.md` | 错误文案 `file not found: /Users/dev/qa-nonexistent-nope-xyz.md`（路径已展开）→ 前端显示**「文件不存在或被清理，无法读取」**（`canvas.unreadableHint`） | ✅ 不存在 区分 |
| c 相对路径 | `relative/no-abs-path.md` | 错误文案 `path must be absolute` → 前端显示**「路径非法，无法读取」**（`canvas.unreadablePathHint`） | ✅ 路径非法 区分 |

- **展开证明**：捕获真实出站 `pop.readFile` 请求 path 为 `~/.nebflow/...`（前端未预展开），后端返回 fileContent 后才出现绝对展开路径；用例 b 的占位符路径显示为展开后的 `/Users/dev/qa-nonexistent-nope-xyz.md`——证明 `~` 在**服务端**展开。
- **错误区分证明**：用例 b/c 前端 `readErrorHint` 分别命中「不存在/files 不存在」与「路径非法/path must be absolute」，与 7251b2e5 的 i18n（`canvas.unreadableHint` / `unreadablePathHint`）一致。
- 对应实现复核（main @9bb38304）：`WebSocketRoutes.pop.readFile` L2107-2111 展开 `~`/`~/`→`user.home`；L2114/2121/2125 `startsWith("/")`/`isFile`/`size` 校验 → 非法路径；L2118-2119 `os.exists` → 不存在。`canvas.js` L802-809 `readErrorHint` 映射。

---

## ⑤ 前端 console 日志无异常确认

**结论：PASS**（无 JS 异常 / 无 WS 错误 / 无资源 404；2 条非阻塞说明）。

真实实例 8080 上独立加载（已认证），收集 pageerror / console / 网络：

| 项 | 结果 |
|---|---|
| JS 异常（pageerror） | **无** ✅ |
| WS 连接错误（403/connect failed/persist fail） | **无** ✅（token 认证后 WS 正常连接） |
| 资源 404 | **无** ✅ |
| 信息：[ws] connect failed 页面缺少 token | 仅出现在**未认证**加载；认证后消失（属预期，非异常） |

### 非阻塞说明
1. **`GET /api/nf-file` → 400（每次加载出现）**：该端点需 `?path=` 参数（`WebSocketRoutes.scala` L551，Pop/file 二进制经它取内容），裸 GET 无参数返回 400——属已知良性探测，非回归、不影响功能。
2. **WebGL `GPU stall due to ReadPixels`（headless 环境）**：Playwright 无头 Chromium 截图触发，仅存在于验证环境，与前端应用无关。
3. **self-verify 期间两条 `readFile error: path must be absolute` warning**：**非作者基线异常**——是我构造用例 b/c 时打开的相对路径标签被持久化到全局 `canvas_tabs.json`，下次加载 tab-restore 重拉该相对路径所致。已清理（见下）。

---

## 附：验证过程副作用已清理（透明披露）

- 真实实例交互验证（打开 Pop 记录标签 / 构造 `~` 用例）会把标签态持久化到**全局** `~/.nebflow/canvas_tabs.json`（`CanvasTabStore` 落盘，非 per-session）。
- 我的自测标签（`no-abs-path.md`、`qa-nonexistent-nope-xyz.md`）曾写入该文件并暂时挤掉了作者已开的 `20260831_project-node-architecture.md` 标签。
- 已清理：关闭自测标签（`no-abs-path.md`），并重开作者 `20260831` 文档标签还原。
- **最终 `~/.nebflow/canvas_tabs.json` = 基线**：`[项目, 20260831_project-node-architecture.md]`（id/type/absPath 正确），与验证前首次加载状态一致。清理后干净加载 console 无 `path must be absolute` 残留。

---

## 复现命令

```bash
# ③ 构造 ~ 用例（真实实例 8080）
node /tmp/nb-real/tilde-test.mjs      # a/b/c 三用例：展开+内容 / ~不存在→不存在提示 / 相对→非法提示
# ⑤ console 审计
node /tmp/nb-real/console-check.mjs   # pageerror / console / 4xx-5xx
# 真实历史 Pop 记录点开（内容加载）
node /tmp/nb-real/click-pop.mjs
```

> 备注：`~` 展开在服务端；前端不可预展开（本地无 user.home）。端到端真实 Pop 记录（绝对路径）本就可开；`~` 历史记录为构造补验（代码路径一致）。
