#!/usr/bin/env node
// check-attachment-persistence.mjs — 「用户附件必须是**持久**引用」源码闸
// （2026-11-18 P0「我发送出去的文件过一会还是会出现这种失效的问题」修复批）。
//
// 现场症状（作者 2026-11-18 17:00 截图）：一条己方消息的图片附件渲染成虚线空卡 +
// 蓝色问号裂图，footer 显示「无文件来源，无法下载」。关键词「过一会」= 刚发出时
// 是好的（live 行字节 = 内联 `data:` 预览），刷新/重进（历史恢复腿）才失效。
//
// 根因（本次实测复现 + 生产数据实证 + 隔离实例双重实证，两处**互相独立**）：
//   df7bb5334（2026-11-09「Integrate owned attachment commits in WebSocket uploads」）
//   把「无条件落盘」换成带主人闸的 `AttachmentCommitService`，其中
//     ① `access.ownsSession(msgSessionId)` 拿**聊天会话 id**去查 owner 台账的
//        `session:<id>` 行 —— 那些行按**网关会话 id**（GatewaySessionStore.newSessionId
//        = 32B base64url）注册，两个 id 空间从不相交 ⇒ 恒 false ⇒ 附件永不落盘；
//     ② 落盘登记走 2 参 `commit`（resourceId = 裸文件名），而读取面
//        `StaticRoutes.uploadsRoutes` 授权的键是 `<sid>/<file>` ⇒ 即使落了盘也 403。
//   ③ 守门的那一行 `attachmentWrites *> { … val attJson = … }`：`*>` 的右参数是
//      **by-value** 的 IO（cats-effect `def *>[B](that: IO[B])`）⇒ 整块在**构造期**
//      eager 求值，读 `savedPaths` 时它还是空 Map ⇒ `path` 恒 null。修好 ①② 之后
//      症状**照旧**（本次实测：落盘成功、owner 行正确、attJson 仍 `map=0 v=`）。
//   三者叠加 ⇒ ui.json 落 `path:null` ⇒ 历史恢复腿的 item 无 path ⇒
//   `hasDownloadSource`（popArtifacts.js:1375）为假 ⇒ 「无文件来源，无法下载」；
//   媒体面 `attachmentImageUrl`（persistence.js:472）对无 `/uploads/` 的串返 null
//   ⇒ 回落到 nf-file 票腿 / 空 path ⇒ 400/401 ⇒ 裂图。
//
// 断言（每条都钉住链条的一个环节，任一断裂即回归）：
//   ── static 面（Scala 源码）──────────────────────────────────────────────
//   A1 落盘准入**不得**再用聊天会话 id 去查 owner 台账（`ownsSession(<chatSid>)`）。
//   A2 **两处**落盘（图片腿 / 通用附件腿）登记的 resourceId 必须是 **URL 形状**
//      `<sid>/<file>` —— 与 A5 的读取面同一把钥匙。
//   A3 会话 id 作 URL 段前必须过 `AttachmentScope` 单段闸（拒空/拒 `..`/拒分隔符/
//      拒 NUL/拒控制字符/限长）—— 否则落一个读取面永远取不回来的名字。
//   A4 ui.json 的 `path` 必须来自落盘结果（`savedPaths`），空档才写 null
//      （「症状可能复现」的**判据位**：path 恒 null ⇒ 必然全量失效）。
//   A5 读取面（`uploadsRoutes`）授权的键形状 = `<sid>/<file>`（去掉 `uploads` 段后
//      的整串）—— A2 必须与它逐字对齐。
//   A8 attJson 组装块必须**延迟**到落盘之后（`attachmentWrites *> IO.defer {`），
//      不得留 eager 形态 ⇒ 根因 ③。
//   A9 两处落盘分支体必须**产出** IO（`.attempt.flatMap {…}`）——用 `.map` 会把
//      内层 IO 当值丢掉，「Saved image / not persisted」日志静默消失
//      （本 P0 因此 9 天不可见）。
//   ── frontend 面（web/js + spec + 配置）──────────────────────────────────
//   A6 前端「按 path 取字节」两腿在场：历史恢复腿的 resolver
//      （`persistence.attachmentImageUrl` → `/uploads/<sid>/<file>`）与
//      下载键的可下载判据（`popArtifacts.hasDownloadSource` 认 `item.path`）。
//   A7 端到端回归 spec 在场且断言刷新后仍可读（`tests/attachment-persistence.spec.mjs`
//      里必须有「reload → /uploads/ 源 + 非 disabled 下载键」这组断言），
//      且该 spec 已登记进 playwright 的 instance-backed 名单（否则 CI 自足面会漏跑它）。
//
// 用法（退出码 0 = 合规；默认两面都跑）：
//   node scripts/check-attachment-persistence.mjs
//   node scripts/check-attachment-persistence.mjs --profile static
//   node scripts/check-attachment-persistence.mjs --profile frontend
//
// 自证检测力（实测退出码；破坏 → 红 / 还原 → 绿）：
//   ① 两处 `s"$sid/${key.value}"` → `key`（裸文件名，复原根因②）⇒ static 面 1
//   ② `attachmentWrites *> IO.defer {` → `attachmentWrites *> {`（复原根因③）⇒ static 面 1
//   ③ `AttachmentScope` 单段闸拆掉 ⇒ static 面 1；还原 ⇒ 面 0
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const argv = process.argv.slice(2);
const pi = argv.indexOf('--profile');
const profile = pi >= 0 ? argv[pi + 1] : 'all';
if (!['all', 'static', 'frontend'].includes(profile)) {
  console.error(`unknown --profile ${profile}（期望 static | frontend）`);
  process.exit(2);
}
const wantStatic = profile === 'all' || profile === 'static';
const wantFrontend = profile === 'all' || profile === 'frontend';

const root = resolve(import.meta.dirname, '..');
const read = (p) => readFileSync(resolve(root, p), 'utf8');
/** 整行 `//` 注释剔除：本批的修法都**被自己的注释逐字引述**（「修前是
  * `ownsSession(msgSessionId)`」「修前是 `attachmentWrites *> {`」），
  * 不剔注释的话闸会被自己的说明文字假红。只剔**整行**注释，不动行尾注释
  * （行尾 `//` 可能落在字符串里，如 URL）。 */
const stripLineComments = (s) => s.split('\n').filter((l) => !/^\s*\/\//.test(l)).join('\n');
const errors = [];
const check = (ok, msg) => { if (!ok) errors.push(msg); };
const count = (hay, needle) => hay.split(needle).length - 1;

// ── static 面 ────────────────────────────────────────────────────────────────
if (wantStatic) {
  const ws = stripLineComments(read('src/main/scala/nebflow/gateway/WebSocketRoutes.scala'));
  const scope = read('src/main/scala/nebflow/gateway/AttachmentScope.scala');
  const staticRoutes = read('src/main/scala/nebflow/gateway/StaticRoutes.scala');

  // A1：落盘准入不得再用聊天会话 id 查 owner 台账
  check(
    !ws.includes('ownsSession(msgSessionId)'),
    'A1: WebSocketRoutes 仍以聊天会话 id 作 owner 闸（`ownsSession(msgSessionId)`）——' +
      '该 id 空间与 owner 台账的 `session:<网关会话 id>` 从不相交 ⇒ 附件永不落盘（本批根因①）'
  );
  check(
    ws.includes('val attachmentScope'),
    'A1: 缺少 `attachmentScope` 准入身份（应经 AttachmentScope.segmentOf 取单段会话身份）'
  );

  // A2：两处落盘登记 URL 形状的 resourceId
  const urlScoped = count(ws, 's"$sid/${key.value}"');
  check(
    urlScoped === 2,
    `A2: URL 形状 resourceId 的落盘调用应有 2 处（图片腿 + 通用附件腿），实测 ${urlScoped} 处 —— ` +
      '少于 2 说明有一腿退回裸文件名（读取面 `ownsResource("attachment","<sid>/<file>")` 会 403，根因②）'
  );

  // A3：会话 id 单段闸
  check(
    scope.includes('def segmentOf') && scope.includes("c == '/'") && scope.includes('".."'),
    'A3: AttachmentScope.segmentOf 的单段闸不完整（须拒空/拒 `..`/拒 `/`、`\\`、NUL）'
  );

  // A4：ui.json 的 path 来自落盘结果
  check(
    ws.includes('savedPaths.getOrElse(idx, "")'),
    'A4: attJson 的 path 必须取自 savedPaths（落盘结果）；缺失即 path 恒 null（症状判据位）'
  );

  // A5：读取面键形状 = <sid>/<file>
  check(
    staticRoutes.includes('access.ownsResource("attachment", resourceId)') &&
      staticRoutes.includes('segments.map(_.decoded()).toList.drop(1)'),
    'A5: uploadsRoutes 的授权键不再是「去掉 uploads 段后的整串」——A2 的键形状须随之对齐'
  );

  // A8：attJson 必须延迟到落盘之后才求值
  const deferIdx = ws.indexOf('attachmentWrites *> IO.defer {');
  check(
    deferIdx > 0,
    'A8: attJson 组装块未被延迟（应有 `attachmentWrites *> IO.defer {`）—— ' +
      '`*>` 右参数 by-value ⇒ 构造期 eager 求值 ⇒ savedPaths 恒空 ⇒ path 恒 null（根因③）'
  );
  check(
    !ws.includes('attachmentWrites *> {'),
    'A8: 存在 eager 形态 `attachmentWrites *> {`（attJson 会在落盘前求值）'
  );
  check(
    deferIdx > 0 && ws.indexOf('savedPaths.getOrElse(idx, "")') > deferIdx,
    'A8: `savedPaths.getOrElse(idx, "")` 必须出现在 IO.defer 块内（落盘之后）'
  );

  // A9：落盘分支体必须产出 IO
  const flatCommit = (ws.match(/\.attempt\s*\n\s*\.flatMap \{/g) || []).length;
  check(
    flatCommit === 2,
    `A9: 两处落盘（图片腿 + 通用附件腿）都必须是 .attempt.flatMap {…}（分支体产出 IO）；实测 ${flatCommit} 处 —— ` +
      '用 .map 会把内层 IO 当值丢掉（日志静默 = 回归不可见）'
  );
}

// ── frontend 面 ──────────────────────────────────────────────────────────────
if (wantFrontend) {
  const persistence = read('src/main/resources/web/js/persistence.js');
  const popArtifacts = read('src/main/resources/web/js/popArtifacts.js');
  const pwConfig = read('playwright.config.mjs');
  const specPath = 'tests/attachment-persistence.spec.mjs';

  // A6：前端两腿
  check(
    persistence.includes("'/uploads/'") && persistence.includes('attachmentImageUrl'),
    'A6: 历史恢复腿的 uploads resolver（persistence.attachmentImageUrl）不在场'
  );
  check(
    /function hasDownloadSource\(item\)\s*\{[\s\S]{0,200}item\.path/.test(popArtifacts),
    'A6: popArtifacts.hasDownloadSource 必须认 `item.path`（否则 footer 下载键永远 disabled = 「无文件来源」）'
  );

  // A7：端到端回归 spec 在场并已登记
  let spec = '';
  try { spec = read(specPath); } catch { /* missing */ }
  check(
    spec.includes('/uploads/') && spec.includes('disabled'),
    `A7: ${specPath} 必须断言「reload 后 /uploads/ 源 + 下载键非 disabled」`
  );
  check(
    pwConfig.includes('attachment-persistence.spec.mjs'),
    'A7: 该 spec 未登记进 playwright.config.mjs 的 instance-backed 名单（自足面会漏跑）'
  );
}

if (errors.length) {
  for (const e of errors) console.error(`FAIL ${e}`);
  console.error(`check-attachment-persistence[${profile}]: ${errors.length} 条不合规`);
  process.exit(1);
}
const faces = profile === 'all' ? 'A1–A9' : profile === 'static' ? 'A1–A5,A8–A9' : 'A6–A7';
console.log(`check-attachment-persistence[${profile}]: PASS（${faces} 全部成立）`);
process.exit(0);
