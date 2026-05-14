// cardRegistry.js — Universal HTML card renderer for Nebflow
// Detects ___<AGENT>_HTML___ markers and renders HTML in sandboxed iframes.
// Theme CSS variables are injected into iframes for dark mode support.

let _iframeId = 0;

/** Inject theme CSS variables from the parent document into the iframe via srcdoc. */
function buildThemeVarsCSS() {
  const root = getComputedStyle(document.documentElement);
  const vars = [
    '--color-primary', '--color-primary-hover', '--color-text', '--color-text-muted',
    '--color-bg', '--color-bg-secondary', '--color-border', '--color-bubble-ai',
    '--color-error', '--color-success'
  ];
  const pairs = vars.map(v => {
    const val = root.getPropertyValue(v).trim();
    return val ? `${v}:${val}` : null;
  }).filter(Boolean);
  return pairs.length > 0 ? `:root{${pairs.join(';')}}` : '';
}

/** Build the height-reporting script to inject into srcdoc. */
function buildHeightScript(id) {
  return `<script>
(function(){
  var id=${id};
  function send(){try{var h=Math.max(document.body.scrollHeight,document.documentElement.scrollHeight);parent.postMessage({_nfCardH:h,id:id},"*")}catch(e){}}
  new ResizeObserver(send).observe(document.body);
  send();
  setTimeout(send,100);
  setTimeout(send,500);
})();
</script>`;
}

/** Listen for height messages from card iframes. */
window.addEventListener('message', (e) => {
  if (!e.data || !e.data._nfCardH) return;
  const iframe = document.querySelector(`iframe[data-nf-card-id="${e.data.id}"]`);
  if (iframe) iframe.style.height = e.data._nfCardH + 'px';
});

/** Render HTML content inside a sandboxed iframe. */
function renderHtmlCard(container, html, title) {
  container.innerHTML = '';

  const wrap = document.createElement('div');
  wrap.className = 'html-card-wrap';

  const id = ++_iframeId;
  const themeCSS = buildThemeVarsCSS();
  const heightScript = buildHeightScript(id);

  // Wrap user HTML: inject theme vars and base styles
  // Note: do NOT put overflow:hidden on body — it breaks flexbox min-width:auto
  // and causes horizontal content to be clipped. scrolling="no" on iframe is enough.
  const srcdoc = `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>${themeCSS}html,body{margin:0;padding:0;width:100%;font-size:13px;line-height:1.45;box-sizing:border-box;word-wrap:break-word;overflow-wrap:break-word;}*,*:before,*:after{box-sizing:inherit;}</style></head><body>${html}${heightScript}</body></html>`;

  const iframe = document.createElement('iframe');
  iframe.className = 'html-card-iframe';
  iframe.setAttribute('sandbox', 'allow-scripts');
  iframe.setAttribute('scrolling', 'no');
  iframe.setAttribute('srcdoc', srcdoc);
  iframe.dataset.nfCardId = id;

  wrap.appendChild(iframe);
  container.appendChild(wrap);
}

/**
 * Try to render a tool card using the universal HTML renderer.
 * Detects ___<AGENT>_HTML___ markers in the tool output text.
 */
export function renderWithRegistry(container, text, toolName) {
  if (text && typeof text === 'object' && !Array.isArray(text)) {
    text = text.content || text.summary || '';
  }
  if (typeof text !== 'string') text = '';

  const htmlMatch = text && text.match(/^___\w+_HTML___/);
  if (htmlMatch) {
    try {
      const json = text.substring(htmlMatch[0].length);
      const data = JSON.parse(json);
      if (data.html) {
        renderHtmlCard(container, data.html, data.title || '');
        return true;
      }
    } catch (e) {}
  }

  // Legacy ___<AGENT>_JSON___ marker (backward compat)
  const jsonMatch = text && text.match(/^___\w+_JSON___/);
  if (jsonMatch) {
    try {
      const json = text.substring(jsonMatch[0].length);
      const data = JSON.parse(json);
      const inner = data.data || data;
      if (inner.html) {
        renderHtmlCard(container, inner.html, inner.title || '');
        return true;
      }
    } catch (e) {}
  }

  return false;
}

/** No-op for backward compatibility. */
export function registerCardRenderer() {}

/** No-op for backward compatibility. */
export function clearRenderers() {}
