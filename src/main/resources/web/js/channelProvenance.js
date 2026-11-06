// channelProvenance.js — 渠道/平台**来源标记**的显示面剥离单点（msgchip-provenance 批，
// 2026-11-01 作者令逐字：「以下消息来自网页控制台（渠道 console）· 设备：Mashiros-
// MacBook-Pro（已登记） 这种信息 在使用 msg 身份牌的时候应该被剥离 只保留最原生的输入」）。
//
// 为什么需要它：渠道注入（网页控制台 / 飞书 / 微信 / 节点 Message）在**模型面**的文本
// 头部会带一段传输层注记（`<system-reminder>` 来源块、`[NODE-MESSAGE] 来源：…` 头、
// `[mail chainId: …]` 头、附件指针行）。这些注记随消息全文一起铸牌（
// MsgTokenRegistry.mint 存的是模型面 text）⇒ msg 身份牌小窗点开显示的是「含注记的
// 原文」，引用面板里全是渠道元信息。本模块把那层注记剥掉，只留作者原生输入。
//
// 🔴 判据 = **位置 + 结构**，不是关键词（作者令硬约束：正文里自己写的「以下消息来自…」
//    必须原样保留）：
//     ① 只有**消息开头**的 `<system-reminder>` 块，且块体**恰好一行**、整行匹配渠道
//        来源模板（`以下消息来自<显示名>（渠道 <id>）…` / `（社交接口 · 渠道 <id>）…`）
//        ⇒ 剥块 + 紧随的一个空行。块体多行（真 reminder，如 Merge turn / 冻结提醒）
//        ⇒ 一字不动。
//     ② 开头叠加的注入头行（`[NODE-MESSAGE] 来源：…` / `[mail chainId: …]`）逐层剥，
//        `[NODE-REPORT-REMINDER]` 只剥标签词、正文留给读者。
//     ③ 附件的**整行**指针（`[用户附加文件: /abs/path]` / `[图片] 已存至 …` /
//        `[attachment lost: …]`）⇒ 剥行并计数，牌窗另起「N 个附件」指示（不塞路径噪声）。
//     ④ 裸的「以下消息来自…」行（没有 `<system-reminder>` 包裹）**永不剥**——那可能是
//        作者自己抄进正文的原话（历史实证：d7c3cb54 第 780 行正是此类）。
//
// 单点纪律：牌窗（chat/msgTokenChip.js renderPopBody）与主会话气泡（chat.js
// renderUserBubble + persistence.js 两条恢复腿）都只调本函数——不许第二套正则。
// 引擎端点 `GET /api/sessions/msg-token/{core}` 的 text 保持原样（后端不镜像清洗）：
//   · 牌窗两条取数腿（本地 DOM 对位 / 引擎权威）在 renderPopBody 处**汇合**，清洗
//     落在那一个渲染点 ⇒ 一份实现覆盖两条腿，不存在 JS/Scala 双实现漂移面；
//   · 投递面（`{{msg:…}}` 消解）必须拿到全文原貌，清洗绝不能进引擎（那会篡改模型
//     上下文与映射本体）。
//
// 幂等：清洗过的文本不再含上述头部结构 ⇒ 二次调用零变化（本地腿/缓存腿重复经过同一
// 渲染点时不会双剥）。

/**
 * 剥离结果的读数面。
 * @typedef {Object} ProvenanceStrip
 * @property {string} text        剥离后的原生正文（可能为空：纯注入包装消息）
 * @property {number} attachments 被剥掉的整行附件指针数（> 0 ⇒ 渲染「N 个附件」指示）
 * @property {string[]} channels  渠道 id 读数（console / feishu / …；仅在来源块/头行命中时填）
 * @property {string[]} senders   发送方读数（社交渠道 `发送方：` 值）
 * @property {string[]} devices   设备/来源读数（控制台 `设备：` 值）
 * @property {boolean} stripped   是否真的动了文本（false = 逐字节原样，调用方可据此跳过重排）
 */

/** 渠道来源块模板行（BridgeOrigin.marker / DeviceProvenance.marker 的**逐字**两形，
 *  注意两形在右括号后**没有**空格，`·` 后有一个空格）：
 *  · 社交：`以下消息来自<显示名>（社交接口 · 渠道 <id>）· 发送方：<s> · 会话：<c>`
 *  · 控制台：`以下消息来自<显示名>（渠道 <id>）· 设备：<d>（<状态>）`
 *  非贪婪显示名 + 固定括号骨架 ⇒ 作者正文里的句子（无括号/括号不闭合）天然不命中。 */
const PROVENANCE_LINE = /^以下消息来自(.+?)（(?:社交接口 · )?渠道 ([A-Za-z0-9_.-]+)）(?:· (发送方|设备)：(.*))?$/;

/** `<system-reminder>` 来源块：开标签 + **恰一行** + 闭标签（+行尾空格），其后至多一个
 *  空行。块体多行 ⇒ 本正则不命中（真 `system-reminder` 一律不动）。 */
const REMINDER_BLOCK = /^<system-reminder>[ \t]*\r?\n([^\n]*?)\r?\n<\/system-reminder>[ \t]*(?:\r?\n)?/;

/** 开头叠加的注入头（逐层剥；顺序无意义，每轮重试整组）。 */
const HEAD_PREFIXES = [
  // 分发器 NodeMessage 头行（NodeEngineContract.nodeMessageHeader 逐字模板）。
  /^\[NODE-MESSAGE\][^\n]*\r?\n(?:[ \t]*\r?\n)?/,
  // Mail 链号头行（MailTool.withChainAnnotation 逐字模板）。
  /^\[mail chainId: [^\]\r\n]*\][ \t]*\r?\n(?:[ \t]*\r?\n)?/,
  // 未申报提醒头：只剥**标签词**（其正文是给 agent 的说明，不属于传输注记骨架）。
  /^\[NODE-REPORT-REMINDER\][ \t]+/,
];

/** 附件**整行**指针（引擎各构块点的逐字形态；只认整行 ⇒ 正文中间的同名句不动）：
 *  · `[用户附加图片|文件: <path>]`（WebSocketRoutes:2069/2084/2098、UnifiedMedia.labelLineOf）
 *  · `[attachment lost: <path>]`（ImageInject 读盘失败降级）
 *  · `[图片|文件|视频] 已存至 <path>…，可用 Read 查看`（社交通道媒体线 mediaLine 的**完整**
 *    形状——尾句是模板的一部分：残行（缺尾句）由 social-media-unify B4 钉为「保持文本」，
 *    故此处必须带尾句才认，避免把普通文本吃掉）
 *  · `[image|file: <name> (<降级词>)]`（控制台无数据/路径不安全/落盘失败的降级行） */
const ATTACH_LINE =
  /^(?:\[用户附加(?:图片|文件): [^\n]*\]|\[attachment lost: [^\n]*\]|\[(?:图片|文件|视频)\] 已存至 [^\n]*，可用 Read 查看|\[(?:image|file): [^\n]*\((?:path unsafe|保存失败|无数据)\)\])[ \t]*$/;

/** 剥掉文首连续空行（剥完来源块后常留一层空行）。 */
function trimLeadingBlankLines(text) {
  let out = text;
  for (;;) {
    if (out.startsWith('\r\n')) out = out.slice(2);
    else if (out.startsWith('\n')) out = out.slice(1);
    else return out;
  }
}

/**
 * 剥离渠道/平台来源注记，只留作者原生输入。纯函数、绝不抛（渲染器永不抛纪律）。
 * @param {string} raw
 * @returns {ProvenanceStrip}
 */
export function stripChannelProvenance(raw) {
  /** @type {ProvenanceStrip} */
  const out = { text: typeof raw === 'string' ? raw : '', attachments: 0, channels: [], senders: [], devices: [], stripped: false };
  if (!out.text) return out;
  const original = out.text;
  let text = original;

  // ① 来源块（开头、单行、模板行 —— 三条同时成立才剥）。
  const block = REMINDER_BLOCK.exec(text);
  if (block) {
    const line = PROVENANCE_LINE.exec(block[1]);
    if (line) {
      const kind = line[3] || '';
      const value = line[4] || '';
      if (line[2]) out.channels.push(line[2]);
      if (kind === '发送方' && value) out.senders.push(value);
      if (kind === '设备' && value) out.devices.push(value);
      text = text.slice(block[0].length);
    }
  }

  // ② 注入头行（可叠加：NODE-MESSAGE 头里常再跟一条 mail 头）。
  for (let guard = 0; guard < 8; guard++) {
    let hit = false;
    for (const re of HEAD_PREFIXES) {
      const m = re.exec(text);
      if (m && m[0].length > 0) {
        text = text.slice(m[0].length);
        hit = true;
        break;
      }
    }
    if (!hit) break;
  }
  text = trimLeadingBlankLines(text);

  // ③ 附件整行指针（行级过滤 + 计数）。
  if (text.indexOf('[') >= 0) {
    const lines = text.split('\n');
    const kept = [];
    for (const line of lines) {
      const bare = line.endsWith('\r') ? line.slice(0, -1) : line;
      if (ATTACH_LINE.test(bare)) {
        out.attachments += 1;
        continue;
      }
      kept.push(line);
    }
    if (out.attachments > 0) {
      text = kept.join('\n');
      // 尾部残留空行收敛（附件行常是消息末尾的独占块；正文标记不变）。
      text = text.replace(/(?:\r?\n)+$/, '');
      text = trimLeadingBlankLines(text);
    }
  }

  // ④ 「绝不掏空」兜底：纯注入包装（剥完什么都没有、也没有附件）⇒ 回退原文。
  //    两个显示面（牌窗 / 气泡）共用这一条政策，避免「剥出空泡」这种反向事故。
  if (text.trim() === '' && out.attachments === 0) {
    out.text = original;
    out.stripped = false;
    return out;
  }

  out.text = text;
  out.stripped = text !== original;
  return out;
}

export default { stripChannelProvenance };
