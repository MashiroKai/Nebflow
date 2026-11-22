#!/usr/bin/env node
// check-canvas-seat-draft.mjs — Canvas「静默接管 + 失权端草稿」的**静态门禁**
// （静默接管批 2026-11-19；作者裁定 2026-11-19 08:05「直接静默吧 交给主会话实现」，
//  动机原话 2026-11-18 21:27「我主要是觉得现在通知很烦」）。
//
// 为什么必须有这道闸：本批的失效面全是**静默**的 ——
//   · 把「申请即授予」悄悄改回非抢占（= 退回「第二个窗口只读」），界面看着正常，
//     只有第二个窗口的人写不进去才会发现；
//   · 把「定向通知被顶下的写者」那条腿删掉：失权端再也收不到落草稿的信号，
//     「静默 + 不丢」当场退化成「静默 + 丢」，而且**完全无感**（没有报错、没有提示）；
//   · 把落草稿那条路改成弹一个 toast / 卡片：作者嫌烦的通知立刻回来；
//   · 把落草稿那条路改成写服务端：失权端会去覆盖别人正在写的内容（越界）。
// 这些都不是编译器能拦的。真渲染腿（capture 脚本）只量它跑到的那几态；本闸量**全部声明**。
//
// 判据（每条都有命中数读数）：
//   D1 落草稿被定义且**接线**：canvas.js 有 captureSeatDraft，且「丢席位」那一刻
//      （writerChanged(role, lost=true)）真的调它一次（丢席位是唯一触发点）。
//   D2 落在本机**本页签自己的槽**：草稿键前缀 = key('canvas_seat_draft')，槽键 = 前缀:tabId；
//      写入走 writeSeatDraftSlot（内部 localStorage.setItem）。
//   D3 取回通知已撤（作者令 2026-11-22「去掉这个通知」）：不得再有 renderDraftEntry /
//      取回条类名 / 页头重渲调用；restoreSeatDraft 机制保留。
//   D4 零用户可见通知：落草稿那条路**不得**出现 writerNotice( / __showToast（作者嫌烦）。
//   D5 不越界：落草稿**只写本机**（captureSeatDraft 体内无 fetch(，不写服务端）。
//   D6 取回是并集：restoreSeatDraft 走 reconcileFromServer（只补开缺失），且不清本地存档。
//   D7 服务端席位是**静默抢占**：claimCanvas 无「非抢占守卫」；notifyDisplacedWriter 在场
//      且被 claimCanvas 调用；活性惰性腿（seatExpiryReason / expireCanvasSeatIfHolderVanished /
//      Instance.goneSince）已整体移除（单源断言：删干净，不留半条腿）。
//   D8 i18n：取回通知文案键在 zh-CN 与 en 两份都已删净（残键 = 另一语种漏删）。
//   （原 D9 取回条落点判据随取回条撤除而退役。）
//   D9 按页签隔离（2026-11-23）：槽键含页签 id（canvasDraftTabId，既有 canvasTabInstanceId 为种子）；
//      无裸写前缀键（否则两页签共用全局单槽 = 缺陷复现）。
//   D10 容量与 LRU：SEAT_DRAFT_SLOT_MAX = 8；落草稿前经 evictSeatDraftSlots 淘汰他页槽。
//   D11 旧单槽迁移：migrateLegacySeatDraft 存在，且在取回路径里先于读取被调用。
//   D12 恢复接线：main.js boot 在 restoreTabs 之后调 restoreOwnSeatDraft；canvas.js 切回
//      （visibilitychange）调 restoreSeatDraft —— 两条腿都必须真接上，不留死代码。
//   D13 不覆盖在编辑：flushSeatDraftBuffers 对脏页签判 local-dirty，不写回。
//
// 用法：node scripts/check-canvas-seat-draft.mjs [--json]
// 退出码：0 = 全绿；1 = 有 FAIL；2 = 装配错误（源文件读不到 ⇒ 不判，绝不假装通过）。
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('..', import.meta.url));
const JSON_OUT = process.argv.includes('--json');
const WEB = join(HERE, 'src/main/resources/web');
const SCALA = join(HERE, 'src/main/scala/nebflow/gateway');

const readIf = (p) => { try { return readFileSync(p, 'utf8'); } catch { return null; } };
const canvasJs = readIf(join(WEB, 'js/canvas.js'));
const takeover = readIf(join(SCALA, 'ConsoleTakeover.scala'));
const zh = readIf(join(WEB, 'js/locales/zh-CN.js'));
const en = readIf(join(WEB, 'js/locales/en.js'));

const missing = [
  ['js/canvas.js', canvasJs], ['ConsoleTakeover.scala', takeover],
  ['js/locales/zh-CN.js', zh], ['js/locales/en.js', en],
].filter(([, v]) => v === null).map(([k]) => k);
if (missing.length) {
  console.error(`check-canvas-seat-draft: SKIP — 读不到源文件：${missing.join(', ')}（不判定，退出码 2）`);
  process.exit(2);
}

const results = [];
const check = (id, ok, note) => results.push({ id, ok, note });

// 取三个切片：丢席位钩子 / 落草稿核 / 取回核。
// 注意：切片的判据一律打在**代码**上（先剥注释）—— 否则一句「旧的 captureSeatDraft() 已删」
// 的注释或一句「这里调 captureSeatDraft()」的口头描述就能把闸骗绿（本次自证就抓到了这个假绿）。
const stripJsComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, ' ')
  .split('\n').map((l) => l.replace(/(^|[^:"'\\])\/\/.*$/, '$1')).join('\n');
const jsCode = stripJsComments(canvasJs);
const slice = (from, to) => {
  const a = jsCode.indexOf(from);
  const b = a >= 0 ? jsCode.indexOf(to, a) : -1;
  return a >= 0 && b > a ? jsCode.slice(a, b) : '';
};
const writerChangedBlock = slice('function writerChanged(', 'const canvasWriter = createCanvasWriterClient(');
const captureBlock = slice('function captureSeatDraft(', 'function clearSeatDraft(');
const restoreBlock = slice('function restoreSeatDraft(', 'function allowCanvasMutation(');
// 落草稿核的 restoreSeatDraftOnce 与 flush 同在 restoreSeatDraft~allowCanvasMutation 之间，上面切片覆盖。

// ── D1 落草稿被定义且接线 ──
const hasCapture = /function captureSeatDraft\s*\(/.test(canvasJs);
const wiredOnLoss = /if\s*\(\s*lost\s*\)[\s\S]{0,400}?captureSeatDraft\(\)/.test(writerChangedBlock);
check('D1', hasCapture && wiredOnLoss,
  `captureSeatDraft=${hasCapture} fired-on-seat-loss=${wiredOnLoss}`);

// ── D2 落在本机单槽 ──
const hasKey = /const\s+LS_DRAFT_PREFIX\s*=\s*key\(\s*'canvas_seat_draft'\s*\)/.test(jsCode)
  && /const\s+seatDraftSlotKey\s*=\s*\(\)\s*=>\s*`\$\{LS_DRAFT_PREFIX\}:\$\{canvasDraftTabId\}`/.test(jsCode);
const writesSlot = /writeSeatDraftSlot\(/.test(captureBlock)
  && /function writeSeatDraftSlot\([\s\S]{0,200}?localStorage\.setItem\(\s*slotKey/.test(jsCode);
check('D2', hasKey && writesSlot, `draft-prefix=canvas_seat_draft slot=prefix:tabId present=${hasKey} writes-own-slot=${writesSlot}`);

// ── D3 取回通知已撤（作者令 2026-11-22「去掉这个通知」），取回机制保留 ──
// 负向判据：取回条渲染函数 / chip 类名 / 页头重渲调用全部不在代码里；restoreSeatDraft 仍在。
const noChip = !/function renderDraftEntry\s*\(/.test(jsCode)
  && !/DRAFT_CHIP_CLASS/.test(jsCode) && !/renderDraftEntry\(/.test(jsCode);
const hasRestore = /function restoreSeatDraft\s*\(/.test(canvasJs);
check('D3', noChip && hasRestore,
  `notice-chip-absent=${noChip} restore-mechanism-kept=${hasRestore}`);

// ── D4 零用户可见通知（作者嫌烦的就是它）──
const silent = !/writerNotice\s*\(/.test(writerChangedBlock) && !/__showToast/.test(writerChangedBlock);
check('D4', silent, `seat-loss hook carries no writerNotice/__showToast=${silent}`);

// ── D5 不越界（落草稿只写本机）──
const localOnly = captureBlock.length > 0 && !/fetch\s*\(/.test(captureBlock);
check('D5', localOnly, `captureSeatDraft does no fetch (local-only)=${localOnly}`);

// ── D6 取回是并集，不清本地存档 ──
const mergeRestore = /reconcileFromServer\s*\(/.test(restoreBlock);
const noWipe = !/tabs\.clear\s*\(/.test(restoreBlock) && !/removeItem\(\s*LS_TABS_KEY/.test(restoreBlock);
check('D6', mergeRestore && noWipe, `restore merges (reconcileFromServer)=${mergeRestore} never wipes local=${noWipe}`);

// ── D7 服务端席位是静默抢占，活性腿已整体移除 ──
// 「移除」类判据量的是**代码**，不是注释：先剥掉 Scala 注释再判 —— 否则一句解释
// 「旧的 expireCanvasSeatIfHolderVanished 已删」的历史注释就会把闸弄红（假红）。
const stripScalaComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, ' ')
  .split('\n').map((l) => l.replace(/(^|[^:"'\\])\/\/.*$/, '$1')).join('\n');
const code = stripScalaComments(takeover);
const claimIdx = code.indexOf('def claimCanvas(');
const claimWindow = claimIdx >= 0 ? code.slice(claimIdx, claimIdx + 2400) : '';
const preemptive = claimIdx >= 0 && !/state\.writer\.isDefined\s*&&\s*!mine/.test(claimWindow)
  && /val displaced/.test(claimWindow) && /notifyDisplacedWriter\(/.test(claimWindow);
const notifier = /def\s+notifyDisplacedWriter\s*\(/.test(code);
const legRemoved = !/seatExpiryReason/.test(code)
  && !/expireCanvasSeatIfHolderVanished/.test(code)
  && !/\bgoneSince\b/.test(code);
check('D7', preemptive && notifier && legRemoved,
  `claim-preemptive=${preemptive} notifyDisplacedWriter=${notifier} liveness-leg-removed=${legRemoved}`);

// ── D8 取回通知文案已撤：两语种都不得残留 canvas.draft* 通知键（残键 = 另一语种漏删）──
const KEYS = ['canvas.draftAvailable', 'canvas.draftRestore', 'canvas.draftDismiss', 'canvas.draftConflict', 'canvas.draftConflictKeep'];
const leftZh = KEYS.filter((k) => zh.includes(`'${k}'`));
const leftEn = KEYS.filter((k) => en.includes(`'${k}'`));
check('D8', leftZh.length === 0 && leftEn.length === 0,
  `residual-keys zh=${leftZh.length} en=${leftEn.length}`);

// ── D9 按页签隔离：无裸前缀写入（全局单槽形态已不可复现） ──
const bareWrite = /localStorage\.setItem\(\s*LS_DRAFT_PREFIX\s*[,)]/.test(jsCode)
  || /localStorage\.setItem\(\s*key\(\s*'canvas_seat_draft'\s*\)/.test(jsCode);
const perTab = /const\s+canvasDraftTabId\s*=/.test(jsCode) && !bareWrite;
check('D9', perTab, `per-tab-slot=${perTab} bare-global-write=${bareWrite}`);

// ── D10 容量上限 8 + LRU 淘汰在落草稿路径上 ──
const capMatch = /const\s+SEAT_DRAFT_SLOT_MAX\s*=\s*8\s*;/.test(jsCode);
const evictFn = /function\s+evictSeatDraftSlots\s*\(/.test(jsCode);
const evictOnWrite = /evictSeatDraftSlots\(\s*slot\s*,\s*SEAT_DRAFT_SLOT_MAX\s*-\s*1\s*\)/.test(captureBlock);
check('D10', capMatch && evictFn && evictOnWrite,
  `cap-8=${capMatch} LRU-evict-fn=${evictFn} evict-on-capture=${evictOnWrite}`);

// ── D11 旧单槽迁移 ──
const migFn = /function\s+migrateLegacySeatDraft\s*\(/.test(jsCode);
const migCalled = /function\s+restoreSeatDraftOnce\s*\([\s\S]{0,120}?migrateLegacySeatDraft\(\)/.test(jsCode);
check('D11', migFn && migCalled, `migrate-fn=${migFn} called-before-restore=${migCalled}`);

// ── D12 恢复接线（开页 + 切回）──
const mainJs = readIf(join(WEB, 'js/main.js'));
const openWired = mainJs !== null && /restoreTabs\(\)\.then\([\s\S]{0,240}?restoreOwnSeatDraft\(\)/.test(mainJs)
  && /import\s*\{[^}]*restoreOwnSeatDraft[^}]*\}\s*from\s*'\.\/canvas\.js'/.test(mainJs);
const switchWired = /visibilitychange[\s\S]{0,160}?restoreSeatDraft\(\)/.test(jsCode);
check('D12', openWired && switchWired, `open-restore(main.js after restoreTabs)=${openWired} switch-back-restore=${switchWired}`);

// ── D13 不覆盖在编辑中的页签 ──
const dirtyGuard = /function flushSeatDraftBuffers[\s\S]{0,1200}?isTabDirty\(entry\)[\s\S]{0,80}?local-dirty/.test(jsCode);
check('D13', dirtyGuard, `flush-skips-dirty-tab=${dirtyGuard}`);

const fails = results.filter((r) => !r.ok);
if (JSON_OUT) {
  console.log(JSON.stringify({ results, ok: fails.length === 0 }, null, 2));
} else {
  for (const r of results) console.log(`${r.ok ? 'PASS' : 'FAIL'} ${r.id} — ${r.note}`);
  console.log(fails.length === 0
    ? `canvas-seat-draft: ${results.length}/${results.length} rules pass`
    : `canvas-seat-draft: ${results.length - fails.length}/${results.length} rules pass — ${fails.map((f) => f.id).join(', ')} FAIL`);
}
process.exit(fails.length === 0 ? 0 : 1);
