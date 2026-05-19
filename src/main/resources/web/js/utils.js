import state from './state.js';
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
let lottieSpinner = null;

export function initSpinner() {
  lottieSpinner = lottie.loadAnimation({
    container: document.getElementById('lottie-spinner'),
    renderer: 'svg', loop: true, autoplay: false,
    animationData: spinnerJson
  });
}

export function playSpinner() {
  if (lottieSpinner) lottieSpinner.play();
}

export function stopSpinner() {
  if (lottieSpinner) lottieSpinner.stop();
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
  html = html.replace(/(<pre[^>]*>)/g, '<div class="code-block-wrap"><button class="code-copy-btn" onclick="window.copyCode(this)" title="Copy code"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg><span>Copy</span></button>$1');
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
  const chat = state.dom.chat;
  const threshold = 60;
  return chat.scrollHeight - chat.scrollTop - chat.clientHeight < threshold;
}

export function smartScroll() {
  const chat = state.dom.chat;
  requestAnimationFrame(() => {
    if (state.scrollSnapped || shouldAutoScroll()) {
      chat.scrollTop = chat.scrollHeight;
      state.scrollSnapped = true;
    }
  });
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
