// staleTabHint.js — 陈旧 SPA 标签页提示（门户身份透传批·方案 B 小刀③，2026-10-28 作者令④）。
//
// 治断点 B1（设计 §1/§2）：Safari 长命标签页跨引擎重启靠 ws.js 自动重连续命——
// 页面跑的还是旧版前端半边：device stamp 不发（消息恒「未登记」默认名）、近路
// 自升级不跑（localhost 加速走不成）、新版功能全部缺席。引擎在 index.html 注入
// window.__WEB_FINGERPRINT__（本页被 serve 时的前端资源指纹），并在 serverConfig
// 帧下行**当前**指纹；两者皆在场且不等 ⇒ 本标签页是旧版 ⇒ 顶栏提示刷新。
// 任一缺席（旧引擎帧 / 无注入脸如测试 harness）⇒ 静默——绝不假阳。
//
// 形态：顶部居中固定条（内联样式，零 css 依赖；z-index 压过一切面板），一条
// 提示 + 【刷新页面】主钮 + 关闭叉。每页至多一条（shown 单闸；serverConfig 帧
// 每连/每次广播都会来，重复调用幂等）。刷新即整页重载（index no-cache ⇒ 新版
// 前端 + 新指纹，B1 收口）。

let shown = false;

export function showStaleTabHint() {
  if (shown) return;
  if (document.getElementById('stale-tab-hint')) { shown = true; return; }
  shown = true;

  const bar = document.createElement('div');
  bar.id = 'stale-tab-hint';
  bar.setAttribute('style', [
    'position:fixed', 'top:10px', 'left:50%', 'transform:translateX(-50%)',
    'z-index:2147483000', 'display:flex', 'align-items:center', 'gap:var(--ds-space-inline-gap)',
    'padding:var(--ds-space-2) var(--ds-space-3) var(--ds-space-2) var(--ds-space-4)', 'border-radius:var(--ds-radius-xl)',
    'background:rgba(28,30,38,0.92)', 'color:#f4f5f7',
    'font:var(--ds-fs-body)/1.4 -apple-system,"Segoe UI",sans-serif',
    'box-shadow:0 6px 24px rgba(0,0,0,0.28)',
    // glass-unify 批 2026-11-05：原 blur(8px) 摘除（α.92 近实心深色提示条，
    // 8% 透光下模糊不可见；且这是第二处 backdrop-filter，违反「全站只有一处
    // 毛玻璃」）。深色提示条不是玻璃面，故不入 .nf-glass。
    'border:1px solid rgba(255,255,255,0.12)',
    'max-width:min(92vw,560px)',
  ].join(';'));

  const text = document.createElement('span');
  text.textContent = '检测到控制台有新版本——本页是旧版标签页，部分功能未启用。';
  text.setAttribute('style', 'flex:1 1 auto;');

  const btn = document.createElement('button');
  btn.textContent = '刷新页面';
  btn.setAttribute('style', [
    'flex:0 0 auto', 'border:none', 'border-radius:var(--ds-radius-input)', 'padding:var(--ds-space-1h) 14px',
    'background:#4f7cff', 'color:#fff', 'font:600 var(--ds-fs-body)/1 -apple-system,"Segoe UI",sans-serif',
    'cursor:pointer',
  ].join(';'));
  btn.addEventListener('click', () => {
    // A normal reload can be fulfilled by an already-controlling service worker.
    // Bypass that worker for this navigation so the fresh index/fingerprint is
    // fetched from the server; the SW itself documents `nfsw` as its escape hatch.
    const freshUrl = new URL(location.href);
    freshUrl.searchParams.set('nfsw', '0');
    location.assign(freshUrl.href);
  });

  const close = document.createElement('button');
  close.textContent = '×';
  close.setAttribute('aria-label', '关闭提示');
  close.setAttribute('style', [
    'flex:0 0 auto', 'border:none', 'background:transparent', 'color:#9aa0ab',
    'font:var(--ds-fs-callout)/1 sans-serif', 'cursor:pointer', 'padding:var(--ds-space-half) var(--ds-space-1)',
  ].join(';'));
  close.addEventListener('click', () => bar.remove());

  bar.append(text, btn, close);
  document.body.appendChild(bar);
}

/** 测试面：本页提示是否已挂（幂等闸的只读读数）。 */
export function staleTabHintShown() {
  return !!document.getElementById('stale-tab-hint');
}
