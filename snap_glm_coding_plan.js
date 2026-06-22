// ============================================================
// 智谱 GLM Coding Plan Max 抢购脚本 — 浏览器模式
// ============================================================
// 用法:
//   1. 在 Safari/Chrome 中登录 bigmodel.cn
//   2. 打开 console (Cmd+Option+I > Console)
//   3. 粘贴本脚本并回车
//   4. 它会倒计时等待开售，自动抢购
// ============================================================

(function() {
  'use strict';

  // ─── 配置 ──────────────────────────────────────────────
  const CONFIG = {
    plan: 'max',            // 目标套餐: lite / pro / max
    saleHour: 10,           // 开售时间（北京时间）
    saleMinute: 0,
    pollFast: 500,          // 开售前后的快速轮询间隔 (ms)
    pollSlow: 5000,         // 常规轮询间隔 (ms)
    timeout: 5 * 60 * 1000, // 抢购超时 (5分钟)
  };

  // 套餐名称映射（用于 UI 识别）
  const PLAN_NAMES = { lite: 'Lite', pro: 'Pro', max: 'Max' };
  const PLAN_PRICES = { lite: '18', pro: '199', max: '399' };

  // 日志颜色
  const C = {
    info: 'color:#2196F3;font-weight:bold',
    ok: 'color:#4CAF50;font-weight:bold',
    warn: 'color:#FF9800;font-weight:bold',
    err: 'color:#f44336;font-weight:bold',
    dim: 'color:#999',
  };

  function log(style, ...args) {
    console.log(`%c[GLM-Snap]`, style, ...args);
  }

  // ─── 时间工具 ──────────────────────────────────────────
  function beijingNow() {
    const now = new Date();
    // 转换为北京时间 (UTC+8)
    const tzOffset = now.getTimezoneOffset(); // 本地时区偏移（分钟）
    const beijingOffset = -480; // UTC+8 = -480分钟
    return new Date(now.getTime() + (beijingOffset - tzOffset) * 60000);
  }

  function nextSaleTime() {
    const now = beijingNow();
    const sale = new Date(now);
    sale.setHours(CONFIG.saleHour, CONFIG.saleMinute, 0, 0);
    if (now >= sale) sale.setDate(sale.getDate() + 1);
    return sale;
  }

  function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  // ─── API 调用 ──────────────────────────────────────────
  const API_BASE = 'https://www.bigmodel.cn/api/biz';

  async function apiGet(path) {
    try {
      const resp = await fetch(`${API_BASE}${path}`, {
        method: 'GET',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
      });
      return await resp.json();
    } catch (e) {
      return null;
    }
  }

  async function apiPost(path, body) {
    try {
      const resp = await fetch(`${API_BASE}${path}`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      return await resp.json();
    } catch (e) {
      return null;
    }
  }

  // ─── 获取套餐信息 ──────────────────────────────────────
  async function checkPlanAvailability(planName) {
    // 方式1: 看页面上的可用套餐列表
    // 方式2: 调用 pricing 接口
    try {
      const pricing = await apiGet('/subscription/enterprise/v2/pricing');
      if (pricing && pricing.code === 200 && pricing.data) {
        const plans = Array.isArray(pricing.data) ? pricing.data
          : pricing.data.products || pricing.data.plans || [pricing.data];
        for (const p of plans) {
          const name = (p.name || p.productName || '').toLowerCase();
          const id = (p.productId || p.planId || p.id || '').toLowerCase();
          if (name.includes(planName) || id.includes(planName)) {
            return p;
          }
        }
      }
    } catch (e) { /* ignore */ }
    return null;
  }

  // ─── 页面操作 ──────────────────────────────────────────
  function findSubscribeButton() {
    // 优先找当前页面可见的订阅按钮
    const selectors = [
      // 按钮文本匹配
      'button:not([disabled])',
      'span:not([disabled])',
      'a:not([disabled])',
      'div[role="button"]:not([disabled])',
    ];

    const keywords = ['订阅', '购买', '开通', '立即订购', '立即购买', 'subscribe', 'purchase'];

    for (const sel of selectors) {
      const elements = document.querySelectorAll(sel);
      for (const el of elements) {
        const text = (el.textContent || '').trim().toLowerCase();
        if (keywords.some(k => text.includes(k))) {
          // 确认这个按钮在 Max 套餐卡片区域内
          const parentText = (el.closest('[class*="card"], [class*="plan"], [class*="product"], [class*="item"]')
            || el.parentElement || el).textContent || '';
          if (parentText.toLowerCase().includes(CONFIG.plan) || text.includes(CONFIG.plan)) {
            return el;
          }
        }
      }
    }

    // 备选：找包含 Max 文本的卡片内的按钮
    const cards = document.querySelectorAll('[class*="card"], [class*="plan"], [class*="product"], [class*="pricing"]');
    for (const card of cards) {
      if ((card.textContent || '').toLowerCase().includes(CONFIG.plan)) {
        const btn = card.querySelector('button, a, [role="button"], [class*="btn"], [class*="button"]');
        if (btn && !btn.disabled) return btn;
      }
    }

    return null;
  }

  // ─── 音频提醒 ──────────────────────────────────────────
  function playAlert() {
    try {
      const ctx = new (window.AudioContext || window.webkitAudioContext)();
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.frequency.value = 880;
      osc.type = 'sine';
      gain.gain.setValueAtTime(0.3, ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.5);
      osc.start(ctx.currentTime);
      osc.stop(ctx.currentTime + 0.5);
    } catch (e) { /* audio not supported */ }
  }

  // ─── 抢购主逻辑 ────────────────────────────────────────
  async function snap() {
    const planName = PLAN_NAMES[CONFIG.plan] || CONFIG.plan;
    const planPrice = PLAN_PRICES[CONFIG.plan] || '?';

    console.log('');
    console.log('%c═══════════════════════════════════════════', 'color:#1664FF;font-weight:bold');
    console.log(`%c  智谱 GLM Coding Plan 抢购助手`, 'color:#1664FF;font-size:16px;font-weight:bold');
    console.log(`%c  目标: ${planName} (${planPrice}元/月)`, C.info);
    console.log(`%c  开售: 每日 ${CONFIG.saleHour.toString().padStart(2,'0')}:${CONFIG.saleMinute.toString().padStart(2,'0')} (北京时间)`, C.info);
    console.log('%c═══════════════════════════════════════════', 'color:#1664FF;font-weight:bold');
    console.log('');

    // 等待开售时间
    const saleTime = nextSaleTime();
    const now = beijingNow();
    let remaining = (saleTime - now) / 1000;

    if (remaining > 0) {
      log(C.info, `距离开售还有 ${Math.floor(remaining / 60)} 分 ${Math.floor(remaining % 60)} 秒`);
      log(C.dim, '请保持此页面打开，不要关闭...');
      console.log('');

      // 倒计时
      while (remaining > 0) {
        const m = Math.floor(remaining / 60);
        const s = Math.floor(remaining % 60);
        if (remaining > 60) {
          process.stdout?.write?.(`\r⏳ 距离开售还有 ${m} 分 ${s} 秒...`);
        } else {
          process.stdout?.write?.(`\r⏳ 距离开售还有 ${s} 秒...`);
        }
        await sleep(remaining > 60 ? 5000 : remaining > 10 ? 1000 : 100);
        remaining = (saleTime - beijingNow()) / 1000;
      }
      console.log('');
      playAlert();
      log(C.ok, '开售时间到！准备抢购...');
    } else {
      log(C.warn, '已过开售时间，立即检查库存...');
    }

    // 快速抢购阶段
    const startTime = Date.now();
    let attempt = 0;

    while (Date.now() - startTime < CONFIG.timeout) {
      attempt++;
      const elapsed = Math.floor((Date.now() - startTime) / 1000);

      // 刷新页面以确保最新状态（每 10 秒刷新一次）
      if (attempt > 1 && attempt % 20 === 0) {
        log(C.dim, `刷新页面... (已过 ${elapsed} 秒)`);
        window.location.reload();
        return; // 页面刷新后脚本停止，需要重新粘贴
      }

      process.stdout?.write?.(`\r[${attempt}] 检查中...`);
      console.log(`\r[${attempt}] 检查中...`);

      // 方式1: 直接找页面上的按钮点击
      const btn = findSubscribeButton();
      if (btn) {
        log(C.ok, `找到订阅按钮: "${(btn.textContent || '').trim()}"`);
        playAlert();
        btn.click();
        log(C.ok, '已点击订阅按钮！');

        // 等待跳转到支付页面
        await sleep(3000);

        // 检查是否跳转到支付页面
        if (window.location.href.includes('pay') || window.location.href.includes('order')) {
          log(C.ok, '已跳转到支付页面！请完成支付。');
        } else {
          log(C.warn, '可能已进入下一步，请检查页面。');
          log(C.info, '页面 URL: ' + window.location.href);
        }
        return;
      }

      // 方式2: 检查 API 中套餐状态
      const planInfo = await checkPlanAvailability(CONFIG.plan);
      if (planInfo) {
        log(C.info, '套餐信息:', planInfo);
        const status = (planInfo.status || '').toLowerCase();
        if (status === 'available' || status === 'onsale' || status === '' || planInfo.available) {
          log(C.ok, '套餐可用！尝试通过 API 订阅...');
          // 尝试通过下单 API
        }
      }

      // 动态调整轮询间隔
      const elapsedSeconds = (Date.now() - startTime) / 1000;
      const interval = elapsedSeconds < 30 ? CONFIG.pollFast : CONFIG.pollSlow;
      await sleep(interval);
    }

    log(C.err, `抢购超时 (${CONFIG.timeout / 1000 / 60} 分钟)`);
    log(C.warn, '开售时间可能已过或套餐已售罄');
  }

  // ─── 启动 ──────────────────────────────────────────────
  snap().catch(e => {
    log(C.err, '脚本出错:', e.message);
    console.error(e);
  });
})();
