// ============================================================
// 智谱 GLM Coding Plan Max 抢购脚本 — 持续刷新版
// ============================================================
// 逻辑: 加载页面 → 找按钮 → 没找到就刷新 → 循环
// 每秒刷新一次，找到"连续包月"立即点击
//
// 使用:
//   1. 打开 https://bigmodel.cn/glm-coding 并登录
//   2. Cmd+Option+I > Console
//   3. 粘贴全部代码，回车运行
// ============================================================

(function() {
  'use strict';

  const PLAN = 'max';         // 目标套餐
  const REFRESH_INTERVAL = 2; // 刷新间隔（秒）
  const KEY = '_glm_snap';

  // ─── 日志 ──────────────────────────────────────────────
  function log(msg, style) {
    console.log('%c[Snap] ' + msg, style || 'color:#2196F3');
  }

  // ─── 蜂鸣 ──────────────────────────────────────────────
  function beep() {
    try {
      const a = new (window.AudioContext || window.webkitAudioContext)();
      [880, 1100, 880].forEach((f, i) => {
        const o = a.createOscillator(), g = a.createGain();
        o.connect(g); g.connect(a.destination);
        o.frequency.value = f; o.type = 'sine';
        g.gain.setValueAtTime(0.3, a.currentTime + i * 0.15);
        g.gain.exponentialRampToValueAtTime(0.01, a.currentTime + i * 0.15 + 0.2);
        o.start(a.currentTime + i * 0.15);
        o.stop(a.currentTime + i * 0.15 + 0.2);
      });
    } catch (e) {}
  }

  // ─── 查找按钮 ──────────────────────────────────────────
  function findMaxMonthlyButton() {
    // 找页面上的每个按钮
    const allBtns = document.querySelectorAll('button, [role="button"], a, [class*="btn"], [class*="button"]');
    
    for (const btn of allBtns) {
      if (btn.disabled || btn.closest('[disabled]')) continue;
      const style = window.getComputedStyle(btn);
      if (style.display === 'none' || style.visibility === 'hidden' || btn.offsetParent === null) continue;
      
      const text = (btn.textContent || '').trim().toLowerCase().replace(/\s+/g, ' ');
      
      // 必须是 Max 相关的 + 订阅/包月 关键词
      const isMax = text.includes('max');
      const isSubscribe = ['连续包月', '包月', '订阅', '购买', '开通', '立即订购', '立即购买', '立即订阅']
        .some(k => text.includes(k));

      if (isMax && isSubscribe) {
        return { element: btn, text: (btn.textContent || '').trim().replace(/\s+/g, ' ') };
      }
    }

    // 备选：不限定 Max，只要是订阅按钮就点（点错了也比错过好）
    for (const btn of allBtns) {
      if (btn.disabled || btn.closest('[disabled]')) continue;
      const style = window.getComputedStyle(btn);
      if (style.display === 'none' || style.visibility === 'hidden' || btn.offsetParent === null) continue;
      
      const text = (btn.textContent || '').trim().toLowerCase().replace(/\s+/g, ' ');
      if (['连续包月', '包月'].some(k => text.includes(k))) {
        return { element: btn, text: (btn.textContent || '').trim().replace(/\s+/g, ' ') };
      }
    }

    return null;
  }

  // ─── 主循环 ────────────────────────────────────────────
  let refreshTimer = null;
  let attempt = parseInt(sessionStorage.getItem(KEY + '_count') || '0') + 1;
  sessionStorage.setItem(KEY + '_count', String(attempt));

  // 页面标题显示抢购状态
  document.title = `[抢购中#${attempt}] ` + document.title.replace(/^\[抢购中#\d+\] /, '');

  log(`第 ${attempt} 次加载，检查按钮...`, 'color:#999');

  const btn = findMaxMonthlyButton();

  if (btn) {
    // 找到了！点击
    log(`找到了! "${btn.text}"`, 'color:#4CAF50;font-size:16px;font-weight:bold');
    beep();
    beep();

    btn.element.scrollIntoView({ behavior: 'smooth', block: 'center' });
    
    // 多次点击确保生效
    setTimeout(() => {
      btn.element.click();
      log('已点击第 1 次', 'color:#4CAF50');
      
      setTimeout(() => {
        btn.element.click();
        log('已点击第 2 次', 'color:#4CAF50');
        
        setTimeout(() => {
          // 检查是否跳转
          if (window.location.href.includes('pay') || window.location.href.includes('order')
              || window.location.href.includes('alipay') || window.location.href.includes('wechat')) {
            log('已进入支付流程！请用手机扫码', 'color:#4CAF50;font-size:16px;font-weight:bold');
          } else {
            log('已点击，请查看页面变化', 'color:#FF9800');
          }
          // 清除刷新计数器
          sessionStorage.removeItem(KEY + '_count');
        }, 2000);
      }, 500);
    }, 500);
    
    return; // 停止刷新循环
  }

  // 没找到，定时刷新
  log(`未找到按钮，${REFRESH_INTERVAL} 秒后刷新`);

  refreshTimer = setTimeout(() => {
    // 清理 sessionStorage 标记（保留计数）
    window.location.reload();
  }, REFRESH_INTERVAL * 1000);

  // ─── 清理 ──────────────────────────────────────────────
  window.addEventListener('beforeunload', () => {
    if (refreshTimer) clearTimeout(refreshTimer);
  });
})();
