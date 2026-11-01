// termLexicon.js — 正文裸词识别（B5-1，2026-11-01 文档增强腿）。
//
// 已有五件（wikilink 解析 / 三腿消解 / hover 气泡 / 反链 / 概念索引）都建立在
// 「作者显式写了 [[词条]]」之上；本件补的是**缺的那一半**：正文里裸写的已注册
// 词条，首次出现处自动变成可点引用，作者不必逐处加双方括号。
//
// 数据源（口径，别搞错）：词条注册表 = `/api/wiki/graph` 载荷的 `nodes[].terms[]`
// —— 每项来自**正文块锚段落**（`WikiIndex.termsOf`：glossary 文档的每个块段落，
// 或 `^term-<slug>` 块），不是 frontmatter。2026-10-29 已显式否决 frontmatter
// `terms:`（`WikiIndex.scala:451-452`）；`.base` 是视图定义（查询层）不是存储层。
//
// 本模块只做**匹配规则**（纯函数、零依赖、可离线/在页内断言）；DOM 面（跳过
// 代码块 / 行内代码 / 既有链接 / KaTeX / 标题）由 `viewers/markdown.js` 的
// `renderAutoTerms` 用既有 TreeWalker 纪律承担 —— 与 renderWikilinks 同一套
// acceptNode 判据，不另造一份排除逻辑。
//
// 匹配规则（四条，缺一条就会出错）：
//   ① 键 = 词条名 + 括号别名（`名（全称）` / `Name (expansion)` 拆成两键），口径
//      与引擎 `WikiIndex.termKeys` 一致；trim 后长度 < 2 的键丢弃。
//   ② **最长优先**：同一落点能匹配多个键时取最长的那个（`GCR` 不被 `GC` 截断），
//      实现 = 按首字符分桶、桶内按长度降序逐个试。
//   ③ **词边界**：键以 ASCII 字母/数字开头或结尾时，该侧必须是串首/串尾或非
//      `[A-Za-z0-9_]`；CJK 侧不设边界（中文没有词间空格，设了等于永不命中）。
//   ④ **逐文首现**：同一键在一篇文档里只链第一次出现（`check_wiki_docs.py` 同款
//      口径；也避免一页满屏链接，克制面与 hover 泡一致）。

/** ASCII 词字符（与 JS `\w` 一致；CJK 不算 —— 见规则③）。 */
function isAsciiWordChar(ch) {
  return !!ch && /[A-Za-z0-9_]/.test(ch);
}

/** 纯标点/空白的键不是词条（`（`、`-` 之类）。 */
function isMeaningfulKey(key) {
  return /[\p{L}\p{N}]/u.test(key);
}

/**
 * 从 graph 载荷取一个作用域内的词条表。
 * @param {any} data `/api/wiki/graph` 载荷
 * @param {string|null} projectName 作用域工程（null = 合并视图，全部工程）
 * @returns {{name: string, aliases: string[], source: string, rel: string, project: string}[]}
 */
export function termsForScope(data, projectName) {
  const nodes = (data && Array.isArray(data.nodes)) ? data.nodes : [];
  const out = [];
  for (const n of nodes) {
    if (!n || n.stub || !Array.isArray(n.terms)) continue;
    if (projectName && n.project !== projectName) continue;
    for (const term of n.terms) {
      const name = String((term && term.name) || '').trim();
      if (!name) continue;
      out.push({
        name,
        aliases: Array.isArray(term.aliases) ? term.aliases.map((a) => String(a || '').trim()).filter(Boolean) : [],
        source: String(term.source || ''),
        rel: String(term.rel || n.rel || ''),
        project: String(n.project || ''),
      });
    }
  }
  return out;
}

/**
 * 建一个「一篇文档一次」的匹配器（同一实例内的键只命中一次 = 规则④）。
 * @param {{name: string, aliases?: string[]}[]} terms
 * @param {{maxLinks?: number, minKeyLen?: number}} [opts]
 */
export function buildTermMatcher(terms, opts = {}) {
  const maxLinks = Number.isFinite(opts.maxLinks) ? opts.maxLinks : 300;
  const minKeyLen = Number.isFinite(opts.minKeyLen) ? opts.minKeyLen : 2;
  // 前缀树（trie）：桶式实现（按首字符分桶 + 桶内线性试）在万级词条且键前缀
  // 高度相似时退化成 O(文本长度 × 桶大小) —— 实测 10k 个 TERM0X..TERM9999X
  // 的桶里有 10k 个键，4240 字正文要 159ms。trie 把每个落点的代价压到
  // 「共同前缀 + 1」，同一份压测降到 1ms 量级，且最长优先成为自然结果。
  /** @type {{next: Map<string, any>, info: any}} */
  const root = { next: new Map(), info: null };
  const seenKeys = new Set();
  let keyCount = 0;
  for (const term of Array.isArray(terms) ? terms : []) {
    if (!term) continue;
    const keys = [term.name, ...(term.aliases || [])];
    for (const raw of keys) {
      const key = String(raw || '').trim();
      if (key.length < minKeyLen || !isMeaningfulKey(key)) continue;
      const lower = key.toLowerCase();
      if (seenKeys.has(lower)) continue;
      seenKeys.add(lower);
      keyCount++;
      let node = root;
      for (const ch of lower) {
        let nxt = node.next.get(ch);
        if (!nxt) { nxt = { next: new Map(), info: null }; node.next.set(ch, nxt); }
        node = nxt;
      }
      node.info = { key, lower, term, len: lower.length };
    }
  }
  const usedKeys = new Set();

  /**
   * 在一段纯文本里找可链落点（不写 DOM、不改字符串）。
   * @param {string} text
   * @returns {{start: number, end: number, text: string, term: any, key: string}[]}
   */
  function find(text) {
    const out = [];
    const s = String(text || '');
    if (!s || !keyCount) return out;
    const low = s.toLowerCase();
    const n = low.length;
    let i = 0;
    while (i < n && out.length < maxLinks) {
      let node = root;
      let best = null;
      let j = i;
      while (j < n) {
        const nxt = node.next.get(low[j]);
        if (!nxt) break;
        node = nxt;
        j++;
        if (node.info && !usedKeys.has(node.info.lower)) best = { info: node.info, end: j };
      }
      if (best) {
        const info = best.info;
        // 规则③ 词边界（用原文判断，CJK 侧不设边界）
        const tailChar = s[best.end];
        const headChar = s[i - 1];
        const okTail = !isAsciiWordChar(info.key[info.key.length - 1]) || !isAsciiWordChar(tailChar);
        const okHead = !isAsciiWordChar(info.key[0]) || i === 0 || !isAsciiWordChar(headChar);
        if (okTail && okHead) {
          out.push({ start: i, end: best.end, text: s.slice(i, best.end), term: info.term, key: info.lower });
          usedKeys.add(info.lower);
          i = best.end;
          continue;
        }
      }
      i++;
    }
    return out;
  }

  return {
    /** 参与匹配的键数（0 ⇒ 该文档无词条，调用方应直接返回）。 */
    get size() { return keyCount; },
    /** 已用掉的键数（一篇文档渲染结束后的统计面，供断言用）。 */
    get used() { return usedKeys.size; },
    find,
  };
}
