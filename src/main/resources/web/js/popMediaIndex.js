// popMediaIndex.js — the unified media index (#491 主题二, 2026-10-05 author
// ruling 「聊天记录搜索时…把Pop的内容按图片/视频/链接/文件等，微信的这样的设计
// 来进行分类和检索。用户输入的文件和LLM Pop的文件，按这个方式一起管理」).
//
// ONE index over the SAME history array the message stream renders from
// (GET /api/sessions/{id}/history — UiMessage list). Both entry sources:
//   - agent: a tool row's `___POP_JSON___` payload (parsePopPayload — the one
//     parser; no second copy of the payload contract), items[] + the URL leg
//     (itemType:'url'); legacy payload-less Pop/Card rows degrade through
//     popArtifactFromInput (tool input → file entry, no thumbnail).
//   - user: UiMessage.User.attachments (type/name/preview/size/path — ref /
//     taskRef attachments are reference cards, not media, and stay out).
//
// Consistency criterion (PLAN §3.1, mechanically checkable): given one history
// input, the artifact set the message stream renders inline == the entries the
// index yields with source filtered to 'agent'. tests/pop-media-index.spec.mjs
// pins it by double-rendering.
//
// Entries carry REFERENCES, never byte copies: `thumbRef` is the payload's own
// data URI string (shared, not duplicated) when the payload inlined one, else
// '' — the panel mints the URL at render time through the single builders
// (nfTicket.ticketUrl for agent refs, the uploads route for user paths).
//
// Timestamps: tool rows carry none (UiMessage tool ts=0), so an entry takes
// its reply's time anchor — the nearest EARLIER row that has a ts (PLAN §3.3);
// a leading run with no anchor stays 0 and sorts into the undated tail.

import { isPopPayload, parsePopPayload, popArtifactFromInput } from './popArtifacts.js';

/** File-name → extension (no dot → ''). */
export function extOf(name) {
  const n = String(name || '');
  const i = n.lastIndexOf('.');
  return i > 0 ? n.slice(i + 1).toLowerCase() : '';
}

/** UiMessage tool labels are rich display strings ("Pop(a.png)\n …"); the
 *  clean leading identifier decides the legacy Pop/Card degrade. Same shape
 *  as chatSearch.js's cleanToolName — kept local so this module stays leaf
 *  (chatSearch → popMediaIndex → popArtifacts only; no cycle). */
function cleanToolName(label) {
  const m = /^([A-Za-z_][A-Za-z0-9_-]*)/.exec(label || '');
  return m ? m[1] : (label || '');
}

/** Basename of a path-ish string (links keep the whole URL). */
function baseName(p) {
  const s = String(p || '');
  const i = s.lastIndexOf('/');
  return i >= 0 ? s.slice(i + 1) : s;
}

/**
 * Build the media index over one session's UiMessage array.
 * @param {any[]} messages raw UiMessage list (document order)
 * @param {{sessionId?:string, sessionName?:string}} [meta]
 * @returns {Array<{id:string, sessionId:string, sessionName:string, msgOrd:number,
 *   ts:number, type:'image'|'video'|'file'|'link', source:'user'|'agent',
 *   name:string, ext:string, size:number|undefined, ref:string, thumbRef:string}>}
 */
export function indexMedia(messages, meta = {}) {
  const sessionId = meta.sessionId || '';
  const sessionName = meta.sessionName || meta.sessionId || '';
  const entries = [];
  let anchor = 0;   // nearest earlier row with a ts (向前最近一条带 ts 的行)
  const push = (e) => { if (e) entries.push(e); };

  (messages || []).forEach((m, ord) => {
    const rowTs = Number(m && m.timestamp) || 0;
    if (rowTs > 0) anchor = rowTs;
    const ts = rowTs > 0 ? rowTs : anchor;
    if (!m || typeof m !== 'object') return;

    if (m.type === 'user') {
      const source = m.injected === true ? 'agent' : 'user';
      (Array.isArray(m.attachments) ? m.attachments : []).forEach((att, i) => {
        if (!att || typeof att !== 'object') return;
        if (att.type === 'ref' || att.type === 'taskRef') return;   // reference card, not media
        const name = String(att.name || '');
        const path = typeof att.path === 'string' ? att.path : '';
        const preview = (typeof att.preview === 'string' && att.preview.startsWith('data:')) ? att.preview : '';
        const size = Number(att.size) || undefined;
        if (att.type === 'image' || att.type === 'video') {
          push({
            id: `${sessionId}:${ord}:${i}`, sessionId, sessionName, msgOrd: ord, ts,
            type: att.type, source, name,
            ext: extOf(name), size, ref: path, thumbRef: preview,
          });
        } else if (name || path) {
          push({
            id: `${sessionId}:${ord}:${i}`, sessionId, sessionName, msgOrd: ord, ts,
            type: 'file', source, name: name || baseName(path),
            ext: extOf(name), size, ref: path, thumbRef: '',
          });
        }
      });
      return;
    }

    if (m.type === 'tool') {
      if (isPopPayload(m.content)) {
        const payload = parsePopPayload(m.content);
        (payload ? payload.items : []).forEach((item, i) => {
          if (!item || typeof item !== 'object') return;
          // The URL leg (PopTool itemType:'url'): a link entry — the 「链接」
          // tab's agent half (PLAN §7 default scope: Pop URL leg + user link
          // attachments; message-body URL extraction stays unregistered).
          if (item.itemType === 'url' || (typeof item.url === 'string' && item.url)) {
            const url = String(item.url);
            push({
              id: `${sessionId}:${ord}:${i}`, sessionId, sessionName, msgOrd: ord, ts,
              type: 'link', source: 'agent',
              name: String(item.title || item.name || baseName(url)),
              ext: '', size: undefined, ref: url, thumbRef: '',
            });
            return;
          }
          if (typeof item.path !== 'string' || !item.path) return;
          const isMedia = item.kind === 'image' || item.kind === 'video';
          push({
            id: `${sessionId}:${ord}:${i}`, sessionId, sessionName, msgOrd: ord, ts,
            type: item.kind === 'image' ? 'image' : item.kind === 'video' ? 'video' : 'file',
            source: 'agent',
            name: String(item.name || baseName(item.path)),
            ext: String(item.ext || extOf(item.name || item.path)),
            size: Number(item.size) || undefined,
            ref: item.path,
            thumbRef: (typeof item.src === 'string' && item.src.startsWith('data:')) ? item.src : '',
          });
        });
        return;
      }
      // Legacy history: payload-less Pop / retired-Card rows degrade to a file
      // entry from the tool input (no thumbnail — PLAN §3.7).
      const tool = cleanToolName(m.label);
      if (tool === 'Pop' || tool === 'Card') {
        const art = popArtifactFromInput(m.label, m.input);
        if (art && art.filePath) {
          push({
            id: `${sessionId}:${ord}:0`, sessionId, sessionName, msgOrd: ord, ts,
            type: 'file', source: 'agent',
            name: baseName(art.filePath),
            ext: extOf(art.filePath),
            size: undefined, ref: art.filePath, thumbRef: '',
          });
        }
      }
    }
  });

  return entries;
}

/** Category-tab membership for one entry. */
export function entryMatchesTab(entry, tab) {
  if (tab === 'media') return entry.type === 'image' || entry.type === 'video';
  if (tab === 'files') return entry.type === 'file';
  if (tab === 'links') return entry.type === 'link';
  return true;
}

/** In-category keyword hit: name / extension / path fields (PLAN §3.5 — the
 *  filter object moves from normalized message text to index entry fields). */
export function entryMatchesKeyword(entry, kw) {
  const k = String(kw || '').trim().toLowerCase();
  if (!k) return true;
  return [entry.name, entry.ext, entry.ref]
    .some(f => typeof f === 'string' && f.toLowerCase().includes(k));
}

/** Monday 00:00 local of the week `d` belongs to (zh convention, same as the
 *  calendar popover's zh week start). */
function weekStart(d) {
  const day = (d.getDay() + 6) % 7;   // 0 = Monday
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() - day).getTime();
}

/**
 * Time groups for the media grid: 本周, then one bucket per month (newest
 * first) — PLAN §3.3. Entries with ts=0 (history with no earlier anchor) land
 * in a trailing unlabelled group so they stay reachable without inventing
 * copy outside the §6 candidate list.
 * @param {any[]} entries
 * @param {Date} now
 */
export function groupEntriesByTime(entries, now) {
  const start = weekStart(now);
  const week = [];
  const months = new Map();   // 'y-m' → {y, m0, entries}
  const undated = [];
  for (const e of entries) {
    if (!e.ts || e.ts <= 0) { undated.push(e); continue; }
    if (e.ts >= start) { week.push(e); continue; }
    const d = new Date(e.ts);
    const key = d.getFullYear() + '-' + d.getMonth();
    if (!months.has(key)) months.set(key, { y: d.getFullYear(), m0: d.getMonth(), entries: [] });
    months.get(key).entries.push(e);
  }
  const groups = [];
  if (week.length) groups.push({ key: 'week', entries: week });
  const monthList = [...months.values()].sort((a, b) =>
    (b.y - a.y) || (b.m0 - a.m0));
  for (const g of monthList) groups.push({ key: `m:${g.y}-${g.m0}`, y: g.y, m0: g.m0, entries: g.entries });
  if (undated.length) groups.push({ key: 'undated', entries: undated });
  // Within a group: newest first (ts desc, then document order desc).
  for (const g of groups) {
    g.entries.sort((a, b) => (b.ts - a.ts) || (b.msgOrd - a.msgOrd));
  }
  return groups;
}
