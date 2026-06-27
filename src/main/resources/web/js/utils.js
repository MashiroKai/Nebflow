import state from './state.js';
import { activeView } from './chatView.js';
import { t } from './i18n.js';

// === Lottie spinner JSON (rotating ring) ===
export const spinnerJson = {
  "v":"5.7.4","fr":60,"ip":0,"op":60,"w":48,"h":48,"nm":"spinner","ddd":0,"assets":[],
  "layers":[{
    "ddd":0,"ind":1,"ty":4,"nm":"ring","sr":1,
    "ks":{
      "o":{"a":0,"k":100},
      "r":{"a":1,"k":[{"i":{"x":[0.833],"y":[0.833]},"o":{"x":[0.167],"y":[0.167]},"t":0,"s":[0]},{"t":60,"s":[360]}]},
      "p":{"a":0,"k":[24,24,0]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}
    },
    "ao":0,
    "shapes":[{
      "ty":"gr",
      "it":[
        {"ty":"el","d":1,"s":{"a":0,"k":[36,36]},"p":{"a":0,"k":[0,0]}},
        {"ty":"st","c":{"a":0,"k":[1,1,1,1]},"o":{"a":0,"k":100},"w":{"a":0,"k":3},"lc":2,"lj":2,"d":[{"n":"d","v":{"a":0,"k":90}},{"n":"g","v":{"a":0,"k":50}}]},
        {"ty":"tr","p":{"a":0,"k":[0,0]},"a":{"a":0,"k":[0,0]},"s":{"a":0,"k":[100,100]},"r":{"a":0,"k":0},"o":{"a":0,"k":100}}
      ]
    }],
    "ip":0,"op":60,"st":0,"bm":0
  }]
};

// === Lottie spinner initialization ===
const _spinners = {};

export function initSpinner() {
  for (const id of ['lottie-spinner', 'secondary-spinner']) {
    const el = document.getElementById(id);
    if (el) {
      _spinners[id] = lottie.loadAnimation({
        container: el, renderer: 'svg', loop: true, autoplay: false,
        animationData: spinnerJson
      });
    }
  }
}

export function playSpinner() {
  const id = activeView?.dom?.lottieSpinnerEl?.id;
  if (id && _spinners[id]) _spinners[id].play();
}

export function stopSpinner() {
  const id = activeView?.dom?.lottieSpinnerEl?.id;
  if (id && _spinners[id]) _spinners[id].stop();
}

// === Markdown initialization ===
export function initMarkdown() {
  marked.setOptions({ breaks: true, gfm: true, headerIds: false });
}

// === KaTeX math rendering — protect math blocks from Markdown processing ===
export function renderMarkdownWithMath(text) {
  if (!text) return '';
  if (typeof marked === 'undefined') return escapeHtml(text);
  const mathBlocks = [];
  // Protect display math ($$...$$)
  let protected_ = text.replace(/\$\$([\s\S]+?)\$\$/g, (m, math) => {
    mathBlocks.push({ display: true, math: math.trim() });
    return `MATHBLOCK${mathBlocks.length - 1}END`;
  });
  // Protect inline math ($...$), skip escaped \$
  // Note: uses capturing group instead of lookbehind for Safari < 16.4 compatibility
  protected_ = protected_.replace(/(^|[^\\])\$([^\$\n]+?)\$/g, (fullMatch, prefix, math) => {
    mathBlocks.push({ display: false, math: math.trim() });
    return prefix + `MATHBLOCK${mathBlocks.length - 1}END`;
  });
  let html = marked.parse(protected_, { headerIds: false });
  // Wrap <pre> blocks with a copy button
  html = html.replace(/(<pre[^>]*>)/g, '<div class="code-block-wrap"><button class="code-copy-btn" onclick="window.copyCode(this)" title="' + t('chat.copy') + '"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg><span>' + t('chat.copy') + '</span></button>$1');
  html = html.replace(/<\/pre>/g, '</pre></div>');
  // Restore math blocks as KaTeX
  mathBlocks.forEach((block, i) => {
    const token = `MATHBLOCK${i}END`;
    if (typeof katex !== 'undefined') {
      try {
        const rendered = katex.renderToString(block.math, {
          displayMode: block.display,
          throwOnError: false,
          strict: 'ignore',
          trust: true
        });
        html = html.replace(token, rendered);
      } catch (e) {
        html = html.replace(token, block.display ? `$$${block.math}$$` : `$${block.math}$`);
      }
    } else {
      html = html.replace(token, block.display ? `$$${block.math}$$` : `$${block.math}$`);
    }
  });
  return html;
}

// === HTML escaping ===
export function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// Shorthand alias for escapeHtml
export function esc(s) {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}

// === Diff formatting (unified diff) ===
export function formatDiff(content) {
  if (!content) return null;
  const lines = content.split('\n');
  // Detect unified diff: must contain at least one @@ ... @@ hunk header
  const isUnified = lines.some(l => /^@@\s+-\d+(?:,\d+)?\s+\+\d+(?:,\d+)?\s+@@/.test(l));
  if (!isUnified) return null;

  let oldLine = 0, newLine = 0;
  const html = lines.map(line => {
    // Parse hunk header to reset line counters (don't render it)
    const hm = line.match(/^@@\s+-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s+@@/);
    if (hm) { oldLine = +hm[1]; newLine = +hm[2]; return ''; }
    // Added line — uses new file line number
    if (line.startsWith('+')) {
      const n = newLine++;
      return '<div class="diff-line"><span class="diff-lineno">' + n + '</span><span class="diff-content diff-add">' + esc(line) + '</span></div>';
    }
    // Removed line — uses old file line number
    if (line.startsWith('-')) {
      const n = oldLine++;
      return '<div class="diff-line"><span class="diff-lineno">' + n + '</span><span class="diff-content diff-del">' + esc(line) + '</span></div>';
    }
    // Context line (space prefix) — both counters advance
    oldLine++; newLine++;
    return '<div class="diff-line"><span class="diff-lineno">' + (oldLine - 1) + '</span><span class="diff-content">' + esc(line) + '</span></div>';
  }).filter(Boolean).join('');
  return '<pre>' + html + '</pre>';
}

// === Tool label/summary localization ===

/**
 * Localize a tool label (the first line of tool cards).
 * Backend sends English like "Read(file.scala)" or "Bash\n  (npm run build)".
 * This replaces the tool name portion with the localized equivalent.
 */
export function localizeToolLabel(label) {
  if (!label) return label;
  const firstNewline = label.indexOf('\n');
  const firstLine = firstNewline >= 0 ? label.slice(0, firstNewline) : label;
  const rest = firstNewline >= 0 ? label.slice(firstNewline) : '';

  // "[MCP] toolName" pattern
  if (firstLine.startsWith('[MCP] ')) {
    const mcpName = firstLine.slice(6);
    const localPrefix = t('tool.MCP');
    if (localPrefix !== 'tool.MCP') {
      return '[' + localPrefix + '] ' + mcpName + rest;
    }
    return label;
  }

  // Match "ToolName(args)" — captures tool name and everything inside parens
  const m = firstLine.match(/^(\w+)\((.*)\)$/s);
  if (m) {
    const toolName = m[1];
    const args = m[2];
    const localName = t('tool.' + toolName);
    if (localName !== 'tool.' + toolName) {
      return localName + '(' + args + ')' + rest;
    }
    return label;
  }

  // Bare tool name without parens, e.g. "Bash" (when label is "Bash\n  (cmd)")
  // or "Card" (when label is "Card\n  (title)")
  const bareMatch = firstLine.match(/^(\w+)$/);
  if (bareMatch) {
    const localName = t('tool.' + bareMatch[1]);
    if (localName !== 'tool.' + bareMatch[1]) {
      return localName + rest;
    }
  }

  return label;
}

/**
 * Localize a tool result summary string.
 * Backend sends English like "3 lines", "File created", "2 files matched".
 */
export function localizeToolSummary(summary, toolLabel) {
  if (!summary) return summary;

  // Exact match patterns (most specific first)
  const exactMap = {
    'File created': () => t('tool.result.fileCreated'),
    'File updated': () => t('tool.result.fileUpdated'),
    'Edited': () => t('tool.result.edited'),
    'Created': () => t('tool.result.created'),
    'No output': () => t('tool.result.noOutput'),
    'No matches': () => t('tool.result.noMatches'),
    'No files found': () => t('tool.result.noFilesFound'),
    'Timed out': () => t('tool.result.timedOut'),
    'Blocked': () => t('tool.result.blocked'),
    'Interactive blocked': () => t('tool.result.interactiveBlocked'),
    'Background': () => t('tool.result.background'),
    'Sandbox bypassed': () => t('tool.result.sandboxBypassed'),
    'Auto-background': () => t('tool.result.autoBackground'),
  };

  if (exactMap[summary]) {
    const result = exactMap[summary]();
    if (result !== summary) return result;
  }

  // Regex patterns
  // "N lines"
  let rm = summary.match(/^(\d+) lines$/);
  if (rm) return t('tool.result.lines', { n: rm[1] });

  // "N lines of output"
  rm = summary.match(/^(\d+) lines of output$/);
  if (rm) return t('tool.result.linesOfOutput', { n: rm[1] });

  // "N lines fetched"
  rm = summary.match(/^(\d+) lines fetched$/);
  if (rm) return t('tool.result.linesFetched', { n: rm[1] });

  // "N files found"
  rm = summary.match(/^(\d+) files? found$/);
  if (rm) return t('tool.result.filesFound', { n: rm[1] });

  // "N files matched"
  rm = summary.match(/^(\d+) files? matched$/);
  if (rm) return t('tool.result.filesMatched', { n: rm[1] });

  // "N files with matches"
  rm = summary.match(/^(\d+) files with matches$/);
  if (rm) return t('tool.result.filesWithMatches', { n: rm[1] });

  // "N matches"
  rm = summary.match(/^(\d+) matches$/);
  if (rm) return t('tool.result.matches', { n: rm[1] });

  // "N lines added" / "N lines removed" / combo "Na added, Nr removed"
  rm = summary.match(/^(\d+) lines? added$/);
  if (rm) return t('tool.result.lineAdded', { n: rm[1] });
  rm = summary.match(/^(\d+) lines? removed$/);
  if (rm) return t('tool.result.lineRemoved', { n: rm[1] });

  // "Na added, Nr removed"
  rm = summary.match(/^(\d+) lines? added, (\d+) lines? removed$/);
  if (rm) return t('tool.result.lineAdded', { n: rm[1] }) + ', ' + t('tool.result.lineRemoved', { n: rm[2] });

  // "N of M lines"
  rm = summary.match(/^(\d+) of (\d+) lines$/);
  if (rm) return t('tool.result.ofLines', { current: rm[1], total: rm[2] });

  // "Task #N created"
  rm = summary.match(/^Task #(\d+) created$/);
  if (rm) return t('tool.result.taskCreated', { id: rm[1] });

  return summary;
}

// === Tool detail builder ===
export function buildToolDetail(inputJson, label) {
  if (!inputJson) return '';
  try {
    const input = JSON.parse(inputJson);
    const parts = [];
    if (input.file_path) parts.push('file: ' + input.file_path);
    if (input.command) parts.push('cmd: ' + input.command);
    if (input.url) parts.push('url: ' + input.url);
    if (input.query) parts.push('query: ' + input.query);
    if (input.pattern) parts.push('pattern: ' + input.pattern);
    if (input.path) parts.push('path: ' + input.path);
    if (parts.length === 0) return '';
    return '<div class="detail-cmd">' + esc(parts.join('  ')) + '</div>';
  } catch (e) {
    return '';
  }
}

// === Tool card click handler (click to toggle body) ===
export function attachToolClick(card) {
  card.addEventListener('click', () => {
    const body = card.querySelector('.body');
    if (body) body.classList.toggle('open');
  });
}

// === Scroll helpers ===
export function shouldAutoScroll() {
  if (!activeView) return false;
  const chat = activeView.dom.chat;
  const threshold = 60;
  return chat.scrollHeight - chat.scrollTop - chat.clientHeight < threshold;
}

export function smartScroll() {
  if (!activeView) return;
  const chat = activeView.dom.chat;
  const snapped = activeView.stream.scrollSnapped;
  requestAnimationFrame(() => {
    const threshold = 60;
    if (snapped || chat.scrollHeight - chat.scrollTop - chat.clientHeight < threshold) {
      chat.scrollTop = chat.scrollHeight;
    }
  });
}

// === Syntax highlighting for code (highlight.js) ===
const EXT_TO_LANG = {
  js:'javascript',jsx:'javascript',ts:'typescript',tsx:'typescript',mjs:'javascript',cjs:'javascript',
  py:'python',rb:'ruby',rs:'rust',go:'go',java:'java',scala:'scala',kt:'kotlin',kts:'kotlin',
  swift:'swift',c:'c',h:'c',cpp:'cpp',hpp:'cpp',cc:'cpp',cxx:'cpp',
  cs:'csharp',fs:'fsharp',php:'php',pl:'perl',pm:'perl',
  html:'html',htm:'html',xhtml:'html',css:'css',scss:'scss',less:'less',
  xml:'xml',svg:'xml',json:'json',yaml:'yaml',yml:'yaml',toml:'ini',ini:'ini',
  sql:'sql',sh:'bash',bash:'bash',zsh:'bash',fish:'bash',
  md:'markdown',tex:'latex',sty:'latex',cls:'latex',
  vue:'vue',svelte:'svelte',dart:'dart',lua:'lua',r:'r',
  gradle:'groovy',groovy:'groovy',dockerfile:'dockerfile',Dockerfile:'dockerfile',
  makefile:'makefile',mk:'makefile',cmake:'cmake',
  erl:'erlang',hrl:'erlang',ex:'elixir',exs:'elixir',
  clj:'clojure',cljs:'clojure',edn:'clojure',
  proto:'protobuf',graphql:'graphql',gql:'graphql',
  diff:'diff',patch:'diff',dockerfile:'dockerfile'
};

export function detectLangFromLabel(label) {
  if (!label) return null;
  // Try to extract file extension from Read label: "Read(file.ext)" or "(/path/to/file.ext)"
  const extMatch = label.match(/\.([a-zA-Z0-9]+)(?:\s|\)|,|$)/);
  if (extMatch) {
    const ext = extMatch[1].toLowerCase();
    return EXT_TO_LANG[ext] || null;
  }
  // Try to extract from file path in second line: '  ("/path/to/file.ext")'
  const pathMatch = label.match(/\/([^/]+\.[a-zA-Z0-9]+)"/);
  if (pathMatch) {
    const ext = pathMatch[1].split('.').pop().toLowerCase();
    return EXT_TO_LANG[ext] || null;
  }
  return null;
}

export function highlightCode(code, label) {
  if (!code || typeof hljs === 'undefined') return null;
  if (code.length < 10) return null;
  // Strip Read tool line number prefixes ("1\t", " 42\t") before passing to hljs
  const clean = code.replace(/^[ \t]*\d+\t/gm, '');
  if (clean.length < 10) return null;
  const lang = detectLangFromLabel(label || '');
  try {
    let result;
    if (lang) {
      if (hljs.getLanguage(lang)) {
        result = hljs.highlight(clean, { language: lang, ignoreIllegals: true });
      } else {
        result = hljs.highlightAuto(clean);
      }
    } else {
      result = hljs.highlightAuto(clean);
    }
    if (result && result.value) {
      return '<pre class="tool-body-pre hljs"><code class="hljs language-' + (result.language || '') + '">' + result.value + '</code></pre>';
    }
  } catch(e) {
    // Fall through to plain text
  }
  return null;
}

/**
 * Render tool content with appropriate highlighting.
 * Returns HTML string for the body, or null if no special rendering applies.
 * Priority: diff > syntax highlight (Read/Grep only) > null (plain text)
 */
export function renderHighlightedContent(content, label) {
  if (!content) return null;
  const diffHtml = formatDiff(content);
  if (diffHtml) return diffHtml;
  const toolName = label ? label.replace(/\(.*$/, '').trim() : '';
  if (toolName === 'Read' || toolName === 'Grep') {
    const hlHtml = highlightCode(content, label);
    if (hlHtml) return hlHtml;
  }
  return null;
}

// === Code copy button handler ===
window.copyCode = function(btn) {
  const wrap = btn.closest('.code-block-wrap');
  const pre = wrap && wrap.querySelector('pre');
  const code = pre ? pre.textContent : '';
  if (!code) return;
  navigator.clipboard.writeText(code).then(() => {
    const span = btn.querySelector('span');
    const orig = span.textContent;
    span.textContent = t('chat.copied');
    btn.classList.add('copied');
    setTimeout(() => {
      span.textContent = orig;
      btn.classList.remove('copied');
    }, 2000);
  }).catch(() => {
    // Fallback: select the code text
    if (pre) {
      const range = document.createRange();
      range.selectNodeContents(pre);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    }
  });
};
