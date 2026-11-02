// descSearch.js — 「描述进检索」匹配谓词的**唯一实现**（件一，2026-11-02）。
//
// 作者原话：「…这样你查询的时候，可以根据描述来做判断。」
//
// 为什么单开一个叶子模块（零 import）：
//  · 谓词有三个消费面——桌面联系人页本地过滤（`contacts.js`）、手机常驻搜索条的
//    「会话档案命中」段（`mobile/socialSearch.js`）、以及将来的三点入口过滤面。
//    三处各写一份 `includes` 必然漂移（本仓「两份判据 = 缺陷族」口径）。
//  · 放进 `profileFace.js` 会把整块 Profile 面（连带 `messages.js` 的重依赖）拖进
//    每个过滤面，并造出 `friendGroups → profileFace → messages → friendGroups` 的静态环
//    ⇒ 谓词下沉为**零依赖叶子**，谁都能 import。
//
// 匹配面（定案）：好友 `remark`（我给对方写的）+ 好友 `bio`（对方自我介绍）+
// 群 `remark`（我给群起的）+ 群 `description`（群介绍）。渲染名（displayName / 群名 /
// NL 号）作为「同一条目上的常规检索面」一并参与——否则用户在搜索框里输好友名字
// 会「过滤掉全部条目」，那是比不做过滤更坏的形态。

/**
 * 查询串是否命中任一候选字段（大小写不敏感子串；空查询恒命中 = 不过滤）。
 * @param {string} q
 * @param {Array<string|null|undefined>} fields
 * @returns {boolean}
 */
export function descMatches(q, fields) {
  const needle = String(q || '').trim().toLowerCase();
  if (!needle) return true;
  return (fields || []).some(
    (v) => typeof v === 'string' && v.length > 0 && v.toLowerCase().includes(needle)
  );
}

/**
 * 一条会话/条目的**描述字段集**（好友行、群行、联系人列表行三种入参都吃）。
 * 单点构造：字段顺序无关判定，只决定「哪些字段参与检索」。
 * @param {any} sub 好友行（{name,neblinkId,remark,bio}）或群行（{title,remark,description}）
 * @returns {Array<string|null|undefined>}
 */
export function describeFieldsOf(sub) {
  if (!sub || typeof sub !== 'object') return [];
  return [sub.remark, sub.bio, sub.description, sub.title, sub.name, sub.neblinkId];
}

/**
 * 会话行 → 描述字段集（好友会话取 `conv.friend`，群会话取行自身）。
 * @param {any} conv
 * @returns {Array<string|null|undefined>}
 */
export function convDescribeFields(conv) {
  if (!conv || typeof conv !== 'object') return [];
  if (conv.kind === 'group') return describeFieldsOf(conv);
  return describeFieldsOf(conv.friend);
}
