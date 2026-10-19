// ===== #538 远程腿探针 v2（在 Safari 控制台粘贴运行）=====
// 跑完会把结果【自动复制到剪贴板】——直接 ⌘V 粘贴回来即可，不用抄控制台。
(async () => {
  const IMG = '/Users/kaiyu/.nebflow/plots/20261019_b538_timing-probe.png';
  const N = 5, rows = [];
  const med = (a) => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
  const r0 = (p) => Math.round(p);
  for (let i = 0; i < N; i++) {
    const t0 = performance.now();
    const r = { i, mint: -1, ttfb: -1, bytes: -1, decode: -1, imgload: -1, st: 0, size: 0, cc: '', etag: '' };
    try {
      const m0 = performance.now();
      const mr = await fetch('/api/nf-ticket', { method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: window.__nfSessionId || '', paths: [IMG] }) });
      r.mint = performance.now() - m0; r.st = mr.status;
      if (mr.ok) {
        const t = (await mr.json()).tickets?.[IMG]?.t;
        const url = '/api/nf-file?path=' + encodeURIComponent(IMG) + '&ticket=' + encodeURIComponent(t);
        const f0 = performance.now();
        const fr = await fetch(url);
        r.ttfb = performance.now() - f0;
        const blob = await fr.blob();
        r.bytes = performance.now() - f0 - r.ttfb; r.size = blob.size;
        r.cc = fr.headers.get('cache-control') || ''; r.etag = (fr.headers.get('etag') || '').slice(0, 12);
        const d0 = performance.now();
        const bmp = await createImageBitmap(blob); r.decode = performance.now() - d0; bmp.close && bmp.close();
        // 真开图腿：<img> 直连 ticketUrl（viewers/image.js 同构），此刻 URL 已在 HTTP 缓存
        const l0 = performance.now();
        const img = new Image(); img.src = url;
        await new Promise((ok, err) => { img.onload = ok; img.onerror = err; });
        r.imgload = performance.now() - l0;
      }
    } catch (e) { r.st = 'ERR'; }
    rows.push(r);
    console.log(`#${i}: mint=${r0(r.mint)}ms 兑换ttfb=${r0(r.ttfb)}ms 字节=${r0(r.bytes)}ms 解码=${r0(r.decode)}ms img直开=${r0(r.imgload)}ms st=${r.st} size=${r.size} cc="${r.cc}"`);
    await new Promise((x) => setTimeout(x, 300));
  }
  const ok = rows.filter((r) => r.st === 200);
  let out;
  if (ok.length) {
    out = `#538远程腿 n=${ok.length} | mint med=${r0(med(ok.map(r=>r.mint)))}ms | 兑换往返 med=${r0(med(ok.map(r=>r.ttfb)))}ms p95=${r0(Math.max(...ok.map(r=>r.ttfb)))}ms | 字节传输 med=${r0(med(ok.map(r=>r.bytes)))}ms | 解码 med=${r0(med(ok.map(r=>r.decode)))}ms | img直开 med=${r0(med(ok.map(r=>r.imgload)))}ms | cache-control="${ok[0].cc}" size=${ok[0].size}B`;
  } else {
    out = `#538远程腿 全部失败 st=${rows.map(r=>r.st).join(',')} —— 会话门 403（15min 死窗）或隧道断`;
  }
  console.log(out);
  try { copy(out); out += '  ✅已复制到剪贴板，直接粘贴回来'; } catch (e) { out += ' （copy 不可用，请手抄上行）'; }
  return out;
})();
