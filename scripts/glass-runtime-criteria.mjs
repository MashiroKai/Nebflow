#!/usr/bin/env node
// glass-runtime-criteria.mjs — 运行时毛玻璃判据的**唯一实现**（单源）。
//
// 为什么单独一份：严格腿（tests/mobile-overlay-glass.spec.mjs 的 NEB_OVERLAY_STRICT_GLASS）
// 与运行时闸（scripts/check-glass-runtime.mjs 的 R1）此前各写一份判据与阈值拷贝 ——
// 2026-11-18 复测批（mobile-overlay-truth）实测：两份拷贝在同一棵树上给出互斥的结论
// （严格腿要求静止态玻璃带 sd>1.0，而该静止态背后为空是甲支净空设计的既定属性）。
// 本模块把判据按**不变式**收成一条，两个消费者都从这里取：
//
//   不变式：**玻璃贡献只在背后样本可判时才成立** ——
//     · 背后有可辨内容            ⇒ 该面自己的 backdrop-filter 必须改像素（Δpx ≥ effectMin）
//     · 背后是平色背板            ⇒ 模糊常量色仍得常量色，该样本**不可判**（豁免）
//     · 该面被不透明层完全覆盖    ⇒ 它的贡献根本到不了最终画面，该样本**不可判**（豁免）
//
// 阈值**不在本文件写死**，由设计规范 §9 的机器可读块解析而来（design spec 即单源，
// 见 references/frontend/glass-runtime-spec.md 的 ```glass-runtime-criteria 块）；本文件只是解析 + 判定逻辑。
// **禁止**在本文件或任一消费者里再抄一份数字。
//
// 用法：
//   import { CRITERIA, alphaOf, glassSampleVerdict } from './glass-runtime-criteria.mjs';

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const REPO = fileURLToPath(new URL('..', import.meta.url));
// 仓内规范单源：references/frontend/glass-runtime-spec.md（§9.2 阈值块）。不再引用 seed/插件包路径。
export const SPEC_PATH = fileURLToPath(new URL(
  '../references/frontend/glass-runtime-spec.md', import.meta.url));

const FENCE = /```glass-runtime-criteria\r?\n([\s\S]*?)```/;

/** 解析设计规范 §9 的阈值块（唯一数字来源）。 */
export function parseCriteria(specPath = SPEC_PATH) {
  const md = readFileSync(specPath, 'utf8');
  const m = md.match(FENCE);
  if (!m) throw new Error(`设计规范缺少 \`\`\`glass-runtime-criteria 阈值块：${specPath}`);
  const out = {};
  for (const line of m[1].split(/\r?\n/)) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const [k, v] = t.split('=').map((s) => s.trim());
    if (!k || v === undefined) throw new Error(`阈值块行无法解析：${line}`);
    const n = Number(v);
    if (!Number.isFinite(n)) throw new Error(`阈值非数字：${line}`);
    out[k] = n;
  }
  for (const k of ['effectMin', 'inertMax', 'flatEdgeMax', 'occluderMinAlpha', 'degradeMinAlpha']) {
    if (!Number.isFinite(out[k])) throw new Error(`阈值块缺 ${k}`);
  }
  return out;
}

export const CRITERIA = parseCriteria();

/** rgba()/rgb()/#rrggbbaa → α（透明度）。 */
export function alphaOf(v) {
  const m = String(v).match(/^rgba?\(([^)]*)\)$/i);
  if (!m) { const h = String(v).match(/^#([0-9a-f]{8})$/i); return h ? parseInt(h[1].slice(6, 8), 16) / 255 : 1; }
  const parts = m[1].split(/[,/]/).map((s) => s.trim()).filter(Boolean);
  return parts.length >= 4 ? parseFloat(parts[3]) : 1;
}

/**
 * 单样本判定（严格腿与 R1 共用）。
 * @param {{ deltaPx: number|null, backdropEdge: number|null, cover?: {visibleDelta: number, occluders: Array}, crit?: object }} s
 *   deltaPx       该面玻璃贡献的逐像素差（本相 vs 摘掉 backdrop-filter 相，面内）
 *   backdropEdge  藏起该面后、其盒内背板的边缘能量（平色背板 ⇒ 近 0）
 *   cover         遮挡实测：藏起该面后其盒内的逐像素差（< 噪声底 ⇒ 该面被不透明层
 *                 盖住），occluders 为**身份证据**（压在该面之上、非其祖先/后代的不透明元素）
 * @returns {{ judgeable: boolean, ok: boolean, status: string, reason: string }}
 */
export function glassSampleVerdict({ deltaPx, backdropEdge, cover, crit = CRITERIA }) {
  if (deltaPx === null || deltaPx === undefined) {
    return { judgeable: false, ok: true, status: 'n/a', reason: '该面在本次装配里没有可测盒（越界/零尺寸）——不判' };
  }
  if (cover && Number.isFinite(cover.visibleDelta) && cover.visibleDelta < crit.inertMax) {
    const who = (cover.occluders || []).map((o) => `${o.sel} (α=${o.alpha}, box ${o.rect.join(',')})`).join(' · ') || '（未定位到单一不透明元素：该面自身可能在此状态下不绘制）';
    return {
      judgeable: false, ok: true, status: 'covered',
      reason: `被不透明层覆盖：藏起该面后它自己盒内的逐像素差只有 ${cover.visibleDelta}（< 噪声底 ${crit.inertMax}）⇒ 该面的玻璃贡献到不了最终画面；遮挡候选 ${who} ⇒ 样本不可判`,
    };
  }
  if (backdropEdge === null || backdropEdge === undefined || backdropEdge < crit.flatEdgeMax) {
    return {
      judgeable: false, ok: true, status: 'flat-backdrop',
      reason: `背后是平色背板：背板边缘能量 ${backdropEdge} < ${crit.flatEdgeMax} ⇒ 模糊常量色仍得常量色，样本不可判`,
    };
  }
  const ok = deltaPx >= crit.effectMin;
  return {
    judgeable: true, ok,
    status: ok ? 'effective' : (deltaPx < crit.inertMax ? 'inert' : 'weak'),
    reason: ok
      ? `该面的 backdrop-filter 改了 ${deltaPx} 像素/通道（≥ ${crit.effectMin}）——真采样`
      : `声明了玻璃但摘掉它只改了 ${deltaPx} 像素/通道（< ${crit.effectMin}），且背后背板有内容（边缘 ${backdropEdge} ≥ ${crit.flatEdgeMax}）、该面也未被不透明层盖住 ⇒ 玻璃纸`,
  };
}
