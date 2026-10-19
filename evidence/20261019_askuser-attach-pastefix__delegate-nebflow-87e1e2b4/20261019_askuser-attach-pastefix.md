# 附件粘贴腿 ReferenceError 修复 · 小修批交付报告

分支 `delegate/delegate-nebflow-87e1e2b4`（基线 = main bf77d123a）· 2026-10-19 CST
来源：W1-W3（mobile-quickwins）批实施期间的发现登记——`src/main/resources/web/js/askAttachments.js:45`
paste 腿调用 `enqueueFiles`，而该函数嵌在 `mountAnswerAttachments` 闭包内 ⇒ document 级
paste 监听作用域取不到 ⇒ 运行时 ReferenceError；同位置 checkJs TS2304（Cannot find name
'enqueueFiles'）＝ checkJs 门红根因（gate 报「new file with 1 errors」）。

## 修法

整链提升为模块级函数（任务书第二选项）：`enqueueFiles` / `addChip` / `removeChip` /
`refreshHead` / `markDoneSub` / `upload` 六函数原实现即全参数化（ctx 显式传参、零闭包
捕获），整块从 `mountAnswerAttachments` 内移至模块顶层、缩进去 2 格，行为零改动。
paste（模块级监听）/ 拖拽（drop）/ 点选（filePick change）三腿现调用同一模块级定义。

## 验证

| 项 | 结果 | 证据 |
|---|---|---|
| 验红（修前树跑新 paste 腿） | ✅ 红（0 chip，ReferenceError 路径） | paste 腿先于修复编写并在未修树跑：chip count Expected 2 / Received 0 |
| 验绿（修后 paste 腿） | ✅ | paste 2 files → 2 chips → 双件 Attached（done 钉全链） |
| askuser-attach spec 家族 | ✅ **17/17**（原 16 腿 + 新 paste 腿 1） | 隔离实例 `--home /tmp/nb-askuser-smoke --port 8094`，`--workers=1`，32.4s 全绿 |
| checkJs 门 | ✅ PASS | `askAttachments.js` 零错（TS2304 消失），总 250 < 基线 273 |
| served-resource | ✅ | `curl /js/askAttachments.js` 字节 cmp == 工作树修后文件（重启实例后复验） |
| 语法 | ✅ | `node --check` 通过 |

三腿不回归：click-pick（腿 10）/ drag-drop（腿 11）/ paste（腿 12，新）同批全绿。

## 独立发现（不在本批修，已归因）

姊妹件 `tests/askuser-history-attach.spec.mjs` N1（无附件历史行双树逐字相等负对照）**修前
修后同样红**——归因实验：stash 本批 js 改动后 N1 仍失败 ⇒ 与本批无关的既有红（其钉死的
基线 ref 66f7ec46a4 与后续并 main 批次漂移所致），留待该 spec 基线重锚任务处理。
姊妹件其余 24 腿全绿（attachkey-idem / msgqueue-recall-attach / user-attach-canvas-open 全过）。

## 环境注

- 隔离实例 token 读取：`/tmp/nb-askuser-smoke/auth.json` 为 JSON 引号包裹串，须 unwrap
  后作 Bearer（裸 `cat` 带引号 ⇒ console-ticket mint 403）。
- 实例静态面为 dev web/ sources，但 boot 时快照 ⇒ 改资源后须重启实例再验（served 字节
  cmp 复核过）。
- 本批零 push；进程清理：隔离实例 kill + 端口复查释放。
