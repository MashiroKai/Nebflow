# 好友 / 群 / 设备会话身份头常显 —— 实拍与读数（2026-11-04 批）

作者 2026-11-04 20:49 裁定「按你的推荐走」：三族会话身份头一并改成常显，与主对话同口径。
知识文档（归因/契约变更/验收）在 vault：
`conversation-heads-always-visible__20261105_084500__delegate-nebflow-fec6393b.md`。
本目录只放**过程产物**：实拍 PNG + 两次门禁/实跑日志。

## 目录内容

| 文件 | 是什么 |
|---|---|
| `375-{friend,group}-{before,after}.png` | 手机 375×812 · 好友/群会话 · **已滚动到底** |
| `1440-{friend,group}-{before,after}.png` | 桌面 1440×900 · 好友/群会话 · 已滚到底（桌面回归腿） |
| `spec-leg4-reverse-red.log` | 腿 ④ 反向验红：把渐隐绑定装回去的树 ⇒ **红** |
| `spec-leg4-green.log` | 本树（修复后）⇒ **绿** |
| `shots-after.log` / `shots-before.log` | 两组实拍的逐张读数（身份头 computed style + 滚动位） |

## 读数对照（375×812，已滚动到底）

| 态 | `--idhead-p` | `opacity` | `visibility` | 名字胶囊可见 | 截图 |
|---|---|---|---|---|---|
| 改前（好友，scrollTop 2917/2917） | `1` | `0` | `hidden` | false | `375-friend-before.png` |
| 改后（好友，scrollTop 2917/2917） | `` （空） | `1` | `visible` | true | `375-friend-after.png` |
| 改前（群，scrollTop 3277/3277） | `1` | `0` | `hidden` | false | `375-group-before.png` |
| 改后（群，scrollTop 3277/3277） | `` （空） | `1` | `visible` | true | `375-group-after.png` |

桌面 1440：两族 before/after **完全相同**，身份头 `present=false`（`messages.js` 的
`isMobileLayer` 分支不在桌面执行）⇒ 改动零触及桌面。

## 腿 ④ 两次读数

- **反向验红**（`CONVHEADS_WEB_ROOT` 指向前版本四文件拼出的 web 树）：
  `--idhead-p="1"` / `opacity 0` / `visibility hidden` / 头·名·头像 = false/false/false；
  `Error: ④/打开即落底：--idhead-p 不许被写入（读数 "1" = 渐隐驱动器复活）`
  `Expected: ""  Received: "1"` ⇒ **1 failed**。
- **本树**：四档（打开即落底 / 滚到底 / 中段 500 / 回顶 0）逐档
  `--idhead-p=""` / `opacity 1` / `visibility visible` / true/true/true ⇒ **1 passed**。

## 复现

```sh
# 改后（本树）
node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/conversation-heads-unify.spec.mjs --workers=1 --reporter=line

# 反向验红：把四个文件还原成改前版本拼一棵 web 树
# BASE = 修复前提交（本批修复提交的父 = 当时的 main tip aa300cbc9）
BASE=aa300cbc9
rm -rf /tmp/idhead-before && mkdir -p /tmp/idhead-before && cp -R src/main/resources/web /tmp/idhead-before/web
for f in css/mobileLayer.css js/messages.js js/mobile/chatChrome.js js/mobile/sessionHead.js; do
  git show "$BASE:src/main/resources/web/$f" > "/tmp/idhead-before/web/$f"; done
CONVHEADS_WEB_ROOT=/tmp/idhead-before/web \
  node /opt/homebrew/lib/node_modules/@playwright/test/cli.js test tests/conversation-heads-unify.spec.mjs --workers=1 --reporter=line

# 实拍（改后 / 改前）
node tests/conversation-heads-always-visible-shots.mjs
SHOTS_WEB_ROOT=/tmp/idhead-before/web SHOTS_STATE=before node tests/conversation-heads-always-visible-shots.mjs
```

零后端零端口：spec 与 harness 都用 `page.route` 从磁盘注入静态资源 + `routeWebSocket` 假 WS
⇒ 不启任何服务、不碰宿主实例 6138。
