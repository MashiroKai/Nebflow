/* sw.js — 控制台温访问秒开 Service Worker（console-load-perf 批 2026-10-21）。
 *
 * 目标（10-19 瀑布审计根因#2 的 dev 树止损半边）：dev 树全部静态资源 no-cache，
 * 温访问=每资产一次 304 校验往返（实测 171 次；隧道腿 264ms/次 ⇒ 温开 8.4s）。
 * 本 SW 把静态面改成 SWR（缓存即答 + 后台带验证器对账），温访问零阻塞请求。
 *
 * 陈旧防护主链（「版本指纹」）：
 *   · 导航请求一律网络优先——index.html 即新鲜裁判，永不回陈旧索引；
 *   · 每次成功索引与上次哈希比对（sha-256），不一致 ⇒ 应用已更新：清空静态
 *     缓存 + 对发起导航的客户端补一次 navigate。代价=更新后首次刷新多一跳，
 *     之后恢复秒开；循环不可能（重载后哈希必已一致，仍留 reload 守卫）；
 *   · 深处单文件改动（不动 index 的）：SWR 后台对账在本次导航内完成，下一次
 *     刷新即新——陈旧窗 = 一次导航；
 *   · 逃生门：硬刷新（Cmd/Ctrl+Shift+R）浏览器原生绕过 SW；任意 URL 加
 *     ?nfsw=0 绕过；页面侧 localStorage `<lower>_sw`='0' 时注册器反向注销。
 *
 * 零接触面（fetch 处理器直接 return ⇒ 浏览器原生路径）：非 GET、/api、
 * /uploads、/plugin-assets、/ws、Range 请求、跨源。导航正文永不进静态缓存
 * （索引只在独立的 index 兜底缓存里存一份离线副本，永不作为首选应答）。
 * WebSocket 连接不经 SW fetch 管道（规范如此），/ws 天然零影响。
 *
 * 已裁决的取舍（2026-10-21）：每次温导航对全部命中项做后台 reval（~190 个
 * 304/次，隧道腿经单 WS 分帧≈几十 KB/分钟级完成）。不做 TTL 新鲜度松弛——
 * 那会把「深层改动下一航必新」的实测保证松弛成 TTL+一航；304 体量小且全程
 * 在首绘之后，与 no-cache 时代温航的阻塞式校验同量，只是不再挡路。
 *
 * 缓存键纪律：dev 树 index 引用自 2026-10-25 起带内容指纹（引擎侧
 * AssetVersions 写 `?v=<sha-256 前16hex>` + 带版本请求 immutable 头）——本 SW
 * 的键 = 完整 URL（含版本参数），内容变 ⇒ 引用变 ⇒ 新键自然穿透；索引哈希
 * 对账链照旧兜底（index 变 ⇒ 清静态缓存 + 补导航）。dist 激活后资产名即
 * 哈希（immutable），同一 SW 语义下陈旧风险进一步归零（index 变 ⇒ 名变）。
 * 版本前缀 nfsw-：activate 时清掉一切旧前缀缓存，弃用旧结构即升级。
 */

const CACHE_PREFIX = 'nfsw';
const CACHE_VER = 'v1';
const APP_CACHE = CACHE_PREFIX + '-' + CACHE_VER; // 静态面 SWR 缓存
const INDEX_CACHE = CACHE_PREFIX + '-index-' + CACHE_VER; // 索引离线兜底 + 哈希记账
const INDEX_URL = '/';
const INDEX_HASH_KEY = '/__nfsw-index-hash'; // 合成条目：索引体 sha-256

// 静态面 allowlist（唯一定义的缓存面；新增静态子目录无需改本文件，前缀已覆盖）。
const ALLOW_PREFIXES = ['/js/', '/css/', '/vendor/', '/brand/', '/assets/'];
const ALLOW_ROOT_RE = /^\/favicon(\.ico|-16\.png|-32\.png|-180\.png|-192\.png|-512\.png|\.svg)$/;

async function sha256Hex(buf) {
  const digest = await crypto.subtle.digest('SHA-256', buf);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

self.addEventListener('install', (event) => {
  event.waitUntil(
    (async () => {
      // 索引离线兜底副本（仅断网时应答；在线导航永远网络优先）。
      try {
        const res = await fetch(INDEX_URL, { cache: 'no-cache', credentials: 'same-origin' });
        if (res && res.ok) {
          const cache = await caches.open(INDEX_CACHE);
          await cache.put(INDEX_URL, res.clone());
          const hash = await sha256Hex(await res.arrayBuffer());
          await cache.put(INDEX_HASH_KEY, new Response(hash));
        }
      } catch (_) {
        /* 断网安装：兜底副本缺席可接受，activate 后首次在线导航会补齐 */
      }
      await self.skipWaiting();
    })()
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const names = await caches.keys();
      await Promise.all(
        names
          .filter((n) => n.startsWith(CACHE_PREFIX) && n !== APP_CACHE && n !== INDEX_CACHE)
          .map((n) => caches.delete(n))
      );
      await self.clients.claim();
    })()
  );
});

/** 静态面判定：同源 + allowlist 前缀/根图标 + 未挂绕过参数。 */
function isAllowlisted(url) {
  if (url.origin !== self.location.origin) return false;
  if (url.searchParams.has('nfsw')) return false;
  const p = url.pathname;
  if (ALLOW_ROOT_RE.test(p)) return true;
  return ALLOW_PREFIXES.some((pre) => p.startsWith(pre));
}

/**
 * SWR 腿：缓存即答（温访问零阻塞），后台带 no-cache 对账（ETag/Last-Modified
 * 304 便宜，200 则换新）。未命中 ⇒ 等网络；网络非 200 原样透传（不缓存）。
 */
async function swrResponse(event) {
  const req = event.request;
  const cache = await caches.open(APP_CACHE);
  const cached = await cache.match(req);
  const reval = fetch(req, { cache: 'no-cache', credentials: 'same-origin' })
    .then((res) => (res && res.ok ? cache.put(req, res.clone()).then(() => res) : res))
    .catch(() => null);
  event.waitUntil(reval.then(() => undefined));
  if (cached) return cached;
  const res = await reval;
  return res || Response.error();
}

/**
 * 导航腿：网络优先 + 索引哈希版本指纹（陈旧防护主链，见文件头）。
 * 返回 null = 不接管（如 ?nfsw=0），fetch 处理器放行为原生路径。
 */
async function navigateResponse(event) {
  const req = event.request;
  let fresh = null;
  try {
    fresh = await fetch(req, { cache: 'no-cache' });
  } catch (_) {
    /* 断网：走兜底副本 */
  }
  if (!fresh || !fresh.ok) {
    const idxCache = await caches.open(INDEX_CACHE);
    const fallback = await idxCache.match(INDEX_URL);
    if (fallback) return fallback;
    return fresh || Response.error();
  }

  const idxCache = await caches.open(INDEX_CACHE);
  const prevHashRes = await idxCache.match(INDEX_HASH_KEY);
  const prevHash = prevHashRes ? await prevHashRes.text() : null;
  const hash = await sha256Hex(await fresh.clone().arrayBuffer());
  await idxCache.put(INDEX_URL, fresh.clone());
  await idxCache.put(INDEX_HASH_KEY, new Response(hash));

  if (prevHash && prevHash !== hash) {
    // 应用更新：静态缓存整体作废 + 发起导航的客户端补一跳（重载后哈希一致，
    // 循环不可能；这里仍按导航型客户端过滤，防误伤其它标签）。
    await caches.delete(APP_CACHE);
    try {
      const clients = await self.clients.matchAll({ type: 'window' });
      const target = clients.find((c) => c.url === fresh.url || c.url === req.url) || clients[0];
      if (target) await target.navigate(req.url);
    } catch (_) {
      /* navigate 失败不致命：本次已渲染新索引，下次刷新自然一致 */
    }
  }
  return fresh;
}

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  let url;
  try {
    url = new URL(req.url);
  } catch (_) {
    return;
  }
  if (req.mode === 'navigate') {
    if (url.searchParams.has('nfsw')) return; // 显式绕过
    event.respondWith(navigateResponse(event));
    return;
  }
  if (!isAllowlisted(url)) return; // /api /uploads /plugin-assets /ws …零接触
  if (req.headers.has('range')) return; // 媒体 Range 走原生
  event.respondWith(swrResponse(event));
});
