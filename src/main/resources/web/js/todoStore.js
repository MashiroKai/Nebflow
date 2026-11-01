// todoStore.js — 待办内存仓（ReminderIsland 复刻批 · 作者令 2026-11-02 20:02）
//
// 数据面边界（作者令逐字）：「数据面不接 Apple Reminder——本批先做纯前端复刻
// （内存态/mock 数据即可，接口留到下一步）」。本文件 = 那条"接口缝"：
//   · 读口形状复刻 ReminderIsland ReminderManager.fetchVisibleReminders（过滤
//     !completed + activeTag + 重要度筛选；排序 = 重要度降序 →（All 视图再加）
//     标签 chips 行序 → 截止时间升序、无截止殿后）；
//   · 写口形状复刻 createReminder / updateReminder / completeReminder 与
//     calendar CRUD（createCalendar / updateCalendar / deleteCalendar）+
//     chips 拖拽重排（calendarOrder 语义，本批内存态不落 localStorage）；
//   · completeReminder 的「完成记录写入日历」（ReminderConfig.
//     completionLogCalendarName）= EventKit 侧效应，本批无等价物（对照表申报）。
// 下一步接自有待办工具时：换本文件实现，渲染层（todoFace.js）零改动。
//
// 色板 = ReminderConfig.calendarColorPresets / priorityColors 逐值迁入（复刻
// 常量，非主题 token——数据驱动色随 JS 下发，与 EKCalendar cgColor 同位）。

/** 标签色板（ReminderIsland ReminderConfig.calendarColorPresets 十色逐值）。 */
export const TAG_COLOR_PRESETS = [
  'rgb(255, 61, 61)',    // red    (1.0, 0.24, 0.24)
  'rgb(255, 148, 0)',    // orange (1.0, 0.58, 0.0)
  'rgb(255, 204, 0)',    // yellow (1.0, 0.8, 0.0)
  'rgb(77, 217, 102)',   // green  (0.3, 0.85, 0.4)
  'rgb(0, 204, 204)',    // cyan   (0.0, 0.8, 0.8)
  'rgb(0, 122, 255)',    // blue   (0.0, 0.48, 1.0)
  'rgb(89, 89, 217)',    // indigo (0.35, 0.35, 0.85)
  'rgb(153, 77, 217)',   // purple (0.6, 0.3, 0.85)
  'rgb(217, 77, 153)',   // pink   (0.85, 0.3, 0.6)
  'rgb(143, 143, 148)',  // gray   (0.56, 0.56, 0.58)
];

/** 重要度四色（ReminderIsland priorityColors：None 白 60% / Low cyan /
 *  Med orange / High red；系统色等价值，亮暗同值——数据色不随主题翻转，
 *  与对照表「重要度色」行申报一致）。 */
export const PRIORITY_COLORS = {
  0: 'rgba(255, 255, 255, 0.6)',
  1: 'rgb(50, 173, 230)',   // system cyan
  2: 'rgb(255, 149, 0)',    // system orange
  3: 'rgb(255, 59, 48)',    // system red
};

export const PRIORITY_LABEL_KEYS = ['todoface.prioNone', 'todoface.prioLow', 'todoface.prioMed', 'todoface.prioHigh'];

/** 新建标签的默认色（ReminderConfig.calendarColorPresets[5] = blue）。 */
const DEFAULT_TAG_COLOR = TAG_COLOR_PRESETS[5];

// ── 内存态 ────────────────────────────────────────────────────────────────

/** @type {Array<{id:string,name:string,color:string}>} */
let tags = [];
/** @type {Array<{id:string,tagId:string,title:string,notes:string,due:number|null,priority:number,completed:boolean,createdAt:number}>} */
let items = [];
/** chips 行序（calendarOrder 等价；缺省 = tags 数组序）。 @type {string[]} */
let tagOrder = [];
/** 当前浏览标签（null = 总条目 All）。 @type {string|null} */
let activeTagId = null;
/** 重要度筛选（selectedPriorityLevels 等价；空集 = 不过滤）。 @type {Set<number>} */
const selectedPriorities = new Set();

let uidSeed = 0;
function uid(prefix) { return `${prefix}${++uidSeed}_${Date.now().toString(36)}`; }

const listeners = new Set();
function emit() { for (const fn of listeners) { try { fn(); } catch { /* listener 断链不传染 */ } } }

// ── 读口（复刻 fetchVisibleReminders + All 视图排序）─────────────────────

function tagIndexOf(tagId) {
  const i = tagOrder.indexOf(tagId);
  return i >= 0 ? i : Number.MAX_SAFE_INTEGER;
}

function baseSorted(list) {
  // 单标签视图排序：重要度降序 → 截止时间升序（无截止殿后）。
  return [...list].sort((a, b) => {
    if (a.priority !== b.priority) return b.priority - a.priority;
    const ad = a.due, bd = b.due;
    if (ad != null && bd != null) return ad - bd;
    if (ad == null && bd == null) return a.createdAt - b.createdAt;
    return ad == null ? 1 : -1;
  });
}

/** 可见条目（过滤 + 排序；ReminderManager.fetchVisibleReminders 同语义）。 */
function visibleItems() {
  const base = items.filter((it) => !it.completed)
    .filter((it) => activeTagId == null || it.tagId === activeTagId)
    .filter((it) => selectedPriorities.size === 0 || selectedPriorities.has(it.priority));
  if (activeTagId != null) return baseSorted(base);
  // All 视图：重要度降序 → 标签 chips 行序 → 截止时间。
  return [...base].sort((a, b) => {
    if (a.priority !== b.priority) return b.priority - a.priority;
    const ta = tagIndexOf(a.tagId);
    const tb = tagIndexOf(b.tagId);
    if (ta !== tb) return ta - tb;
    if (a.due != null && b.due != null) return a.due - b.due;
    if (a.due == null && b.due == null) return a.createdAt - b.createdAt;
    return a.due == null ? 1 : -1;
  });
}

function orderedTags() {
  return [...tags].sort((a, b) => tagIndexOf(a.id) - tagIndexOf(b.id));
}

function openCount() { return items.filter((it) => !it.completed).length; }
function overdueCount() {
  const now = Date.now();
  return items.filter((it) => !it.completed && it.due != null && it.due < now).length;
}

// ── 写口（复刻 ReminderManager CRUD）────────────────────────────────────

function createItem({ tagId = null, title = '', notes = '', due = null, priority = 0 } = {}) {
  const id = uid('i');
  items.push({
    id, tagId: tagId || (tags[0] ? tags[0].id : null),
    title: String(title || '').trim(), notes: String(notes || ''),
    due: Number.isFinite(due) ? due : null,
    priority: Math.min(3, Math.max(0, priority | 0)),
    completed: false, createdAt: Date.now(),
  });
  emit();
  return id;
}

function updateItem(id, patch = {}) {
  const it = items.find((x) => x.id === id);
  if (!it) return;
  if (typeof patch.title === 'string') it.title = patch.title.trim();
  if (typeof patch.notes === 'string') it.notes = patch.notes;
  if (typeof patch.due === 'number') it.due = patch.due;
  if ('hasDue' in patch && !patch.hasDue) it.due = null;
  if (typeof patch.priority === 'number') it.priority = Math.min(3, Math.max(0, patch.priority | 0));
  if (typeof patch.tagId === 'string' && tags.some((tg) => tg.id === patch.tagId)) it.tagId = patch.tagId;
  emit();
}

/** 完成（ReminderManager.completeReminder 等价：isCompleted=true → 可见面退场）。 */
function completeItem(id) {
  const it = items.find((x) => x.id === id);
  if (!it) return;
  it.completed = true;
  emit();
}

function createTag(name, color) {
  const trimmed = String(name || '').trim();
  if (!trimmed) return null;
  const tag = { id: uid('t'), name: trimmed, color: color || DEFAULT_TAG_COLOR };
  tags.push(tag);
  tagOrder = [...tagOrder.filter((id) => tags.some((tg) => tg.id === id)), tag.id];
  emit();
  return tag.id;
}

function updateTag(id, patch = {}) {
  const tag = tags.find((x) => x.id === id);
  if (!tag) return;
  if (typeof patch.name === 'string' && patch.name.trim()) tag.name = patch.name.trim();
  if (typeof patch.color === 'string') tag.color = patch.color;
  emit();
}

/** 删除标签（deleteCalendar 等价：标签连带其条目级联退场）。 */
function deleteTag(id) {
  tags = tags.filter((x) => x.id !== id);
  tagOrder = tagOrder.filter((x) => x !== id);
  items = items.filter((it) => it.tagId !== id);
  if (activeTagId === id) activeTagId = null;
  emit();
}

/** chips 拖拽重排（reorderCalendar 等价：摘出拖入目标位）。 */
function reorderTag(dragId, targetId) {
  if (dragId === targetId) return;
  const from = tagOrder.indexOf(dragId);
  const to = tagOrder.indexOf(targetId);
  if (from < 0 || to < 0) return;
  const next = [...tagOrder];
  next.splice(from, 1);
  next.splice(to, 0, dragId);
  tagOrder = next;
  emit();
}

function setActiveTag(id) {
  activeTagId = id == null ? null : (tags.some((tg) => tg.id === id) ? id : null);
  emit();
}

function togglePriority(p) {
  if (selectedPriorities.has(p)) selectedPriorities.delete(p);
  else selectedPriorities.add(p);
  emit();
}

function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

// ── mock 种子（作者令：内存态/mock；__ 前缀 = 测试缝，产品路径不调用）─────

function seed() {
  const now = Date.now();
  const H = 3600_000;
  const work = uid('t'), life = uid('t'), study = uid('t'), idea = uid('t');
  tags = [
    { id: work, name: '工作', color: TAG_COLOR_PRESETS[5] },
    { id: life, name: '生活', color: TAG_COLOR_PRESETS[3] },
    { id: study, name: '学习', color: TAG_COLOR_PRESETS[7] },
    { id: idea, name: '灵感', color: TAG_COLOR_PRESETS[1] },
  ];
  tagOrder = [work, life, study, idea];
  items = [
    { id: uid('i'), tagId: work, title: '修复登录态在手机层的过期提示', notes: '过期后应回到登录页而不是静默 401', due: now + 2 * H, priority: 3, completed: false, createdAt: now - 26 * H },
    { id: uid('i'), tagId: work, title: '整理验收对照表的动画取帧', notes: '', due: now + 26 * H, priority: 2, completed: false, createdAt: now - 20 * H },
    { id: uid('i'), tagId: work, title: '回复设计稿第二批意见', notes: '', due: null, priority: 1, completed: false, createdAt: now - 9 * H },
    { id: uid('i'), tagId: life, title: '缴房租', notes: '', due: now - 3 * H, priority: 3, completed: false, createdAt: now - 50 * H },
    { id: uid('i'), tagId: life, title: '下班顺路买菜', notes: '番茄 + 鸡蛋 + 青椒', due: now + 5 * H, priority: 2, completed: false, createdAt: now - 30 * H },
    { id: uid('i'), tagId: life, title: '预约体检', notes: '', due: now + 7 * 24 * H, priority: 0, completed: false, createdAt: now - 80 * H },
    { id: uid('i'), tagId: study, title: '读《设计中的设计》第三章', notes: '', due: now + 3 * 24 * H, priority: 1, completed: false, createdAt: now - 60 * H },
    { id: uid('i'), tagId: idea, title: '记录灵感：刘海收展节奏迁移到胶囊', notes: 'spring(0.42, 0.8) 开 / spring(0.45, 1.0) 收', due: null, priority: 1, completed: false, createdAt: now - 4 * H },
  ];
}

seed();

export const todoStore = {
  /** 只读投影（渲染层不持有可变引用）。 */
  get tags() { return orderedTags(); },
  get activeTagId() { return activeTagId; },
  get selectedPriorities() { return new Set(selectedPriorities); },
  visibleItems, openCount, overdueCount,
  tagById: (id) => tags.find((x) => x.id === id) || null,
  isAllView: () => activeTagId == null,
  setActiveTag, togglePriority,
  createItem, updateItem, completeItem,
  createTag, updateTag, deleteTag, reorderTag,
  subscribe,
  /** 测试缝：确定性种子（shots 夹具用；__ 前缀 = 非产品路径）。 */
  __seed({ tagList, itemList, order } = {}) {
    if (Array.isArray(tagList)) {
      tags = tagList.map((x) => ({ id: String(x.id), name: String(x.name), color: String(x.color) }));
      tagOrder = tags.map((x) => x.id);
    }
    if (Array.isArray(order) && order.every((id) => tags.some((x) => x.id === id))) tagOrder = [...order];
    if (Array.isArray(itemList)) {
      items = itemList.map((x) => ({
        id: String(x.id), tagId: String(x.tagId), title: String(x.title || ''),
        notes: String(x.notes || ''), due: Number.isFinite(x.due) ? x.due : null,
        priority: Math.min(3, Math.max(0, (x.priority | 0))),
        completed: !!x.completed, createdAt: Number.isFinite(x.createdAt) ? x.createdAt : Date.now(),
      }));
    }
    activeTagId = null;
    selectedPriorities.clear();
    emit();
  },
};
