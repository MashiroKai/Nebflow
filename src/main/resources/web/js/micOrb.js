// v8.2.10 (2026-08-30): #20 unified edge — every shading/rim sphere now uses
// the shared wobble contour r0 (not a contracted 0.985r0/0.90r0) and the alpha
// feather is tightened to a narrow band right at the silhouette (0.96-1.00r0).
// This removes the internal bright rim ring + the translucent halo band beyond
// it that together read as "layer edges" and leaked as the body wobbled.
//
// v8.2.8 (2026-08-30): OUTER GLOW/BLOOM RING REMOVED — the halo bloom pass and
// the widened dark skirt are dropped so only the lit bubble body remains (user
// report: 麦克风气泡外围那一圈光晕/荧光整个不要，只保留中间亮的气泡本体). Body
// material (<=0.92r0) and rim/spec/core are untouched; see draw() in FS.
//
// micOrb.js — Mic bubble (liquid orb) v8.2.5: edge-resolution fix (dark-mode
// "blocky jagged ring" user report 2026-08-29). Root cause: the backing store
// was 48*dpr px; in environments where devicePixelRatio reports 1 (desktop
// wrapper WebViews without HiDPI scale, or page zoom races) the 48x48 buffer
// is stretched to ~96 physical px and the bright rim turns into blocky
// staircase jaggies. Fix: supersampling floor of 2 (always render >=96px,
// downscaling is smooth) capped at 3, plus live dpr tracking so browser zoom
// changes resize the backing store instead of leaving a stale low-res buffer.
// Shading/material palettes are untouched - v8.2.4 edge-clean optics remain.
// v8.2.4: edge-clean fix ("unclean
//  1) rim/fresnel light is evaluated on a CONTRACTED sphere (Rf=0.90r0) and
//     windowed to zero by 1.00r0 - the bright rim now lives fully inside the
//     opaque silhouette instead of straddling the alpha skirt (no bright
//     semi-transparent fringe at the boundary);
//  2) alpha is derived from the PRE-RIM body color (colNoRim) - the rim/halo
//     brightening no longer inflates edge alpha via the alpha-from-rgb
//     extractAlpha coupling (spec §10.8 root-cause family);
//  3) the light-theme inner alpha lift is gated by the shape skirt - the old
//     smoothstep stays at 1 past 0.96r0, painting a constant +0.12 alpha
//     square veil across the whole canvas outside the orb (hard square
//     boundary = cutout look on light panels). Now it dies with the skirt.
// v8.2.3: white-base fix — alpha skirt
// tightened (0.96r0-1.05r0, fully transparent past 1.05r0) so the orb sits
// directly on the frosted glass panel without the bright outer ring the old
// 0.98-1.14r0 skirt produced ("white circular base" user report 08-27);
// material optics / palettes / motion are untouched from v8.2.2.
// v8.2.2: pure Orb, state expressed via
// color + motion layering, zero overlay layers (user ruling 2026-08-26 01:11:
// "还是用纯Orb吧" — v1-v7's 9-state overlay animations are all dropped).
// v8.2.2 (spec §10.7): error states (mic-error red / frozen-error amber) go
// through the SAME WebGL crystalline structure via palette uniforms
// (palA/palB/palC) — the css:true bypass that produced flat matte balloons is
// removed; CSS fallback only when WebGL is unavailable.
// v8.2 (spec §10.5): jarvis motion paradigm — listening shakes with mic
// volume (hover='volume'), processing/nebula-busy rotate (rotSpeed tiers),
// stateScales, transitionPulse on state switch.
// v8.2.1 (spec §10.6): no-flicker — phaseTime accumulates dt·timeScale
// continuously (no iTime·ts phase jump), pulse soft-start, pulseAmp lerp,
// hoverIntensity lerp, theme switch never re-triggers the pulse.
// v8.1: text label removed (user ruling 08-26 07:31 "orb 下方不要文字了") —
// state = pure color + motion; a11y via aria-label (runtime t() text).
// Spec: mic-bubble-spec.md §10. Reference render: mic-bubble-visual-v8.html.
//
// Imports state.js + i18n.js only (voiceEngine is reached via dynamic import
// to keep this module cycle-free and bare-page importable for harnesses).

import state from './state.js';
import { t } from './i18n.js';

/* ========================================================================
   9-state definition (spec §10.1 + §10.5). hue/sat/lum feed the shader's
   adjustHue + sat + lum mechanism; rot = rotSpeed (rad/s); ts = timeScale
   (internal fluid flow rate); scale = stateScale (canvas transform);
   vol:true = hover driven by mic RMS (listening); pal = palette uniform
   override for the two error states (hue rotation cannot reach red/amber).
   ======================================================================== */
const STATES = [
  { k: 'idle',         cls: 's-idle',      i18n: 'chat.micOrb.idle',       hue: 0,   sat: 1.00, lum: 1.00, rot: 0.05, ts: 0.5, scale: 1.00 },
  { k: 'listening',    cls: 's-listening', i18n: 'chat.micOrb.listening',  hue: -18, sat: 1.08, lum: 1.05, rot: 0.3,  ts: 1.6, scale: 1.08, vol: true },
  { k: 'processing',   cls: 's-processing',i18n: 'chat.micOrb.processing', hue: 22,  sat: 1.06, lum: 0.94, rot: 1.0,  ts: 1.3, scale: 1.05 },
  { k: 'nebula-busy',  cls: 's-nebula',    i18n: 'chat.micOrb.nebulaBusy', hue: 34,  sat: 1.16, lum: 1.06, rot: 0.6,  ts: 1.2, scale: 1.03 },
  { k: 'bg-agents',    cls: 's-bg',        i18n: 'chat.micOrb.bgAgents',   hue: 8,   sat: 0.80, lum: 0.92, rot: 0.15, ts: 0.8, scale: 1.00 },
  { k: 'frozen',       cls: 's-frozen',    i18n: 'chat.micOrb.frozen',     hue: 0,   sat: 0.40, lum: 0.74, rot: 0,    ts: 0,   scale: 1.00 },
  { k: 'frozen-error', cls: 's-frozenerr', i18n: 'chat.micOrb.frozenError', hue: 0,  sat: 0.92, lum: 0.95, rot: 0,    ts: 0,   scale: 1.00, pal: { a: [0.878, 0.663, 0.482], b: [0.812, 0.580, 0.396], c: [0.427, 0.286, 0.184] } },
  { k: 'mic-error',    cls: 's-micerr',    i18n: 'chat.micOrb.micError',   hue: 0,   sat: 1.02, lum: 1.00, rot: 0,    ts: 0,   scale: 1.00, pal: { a: [0.929, 0.451, 0.427], b: [0.910, 0.353, 0.388], c: [0.541, 0.180, 0.200] } },
  { k: 'offline',      cls: 's-offline',   i18n: 'chat.micOrb.offline',    hue: 0,   sat: 0.00, lum: 0.85, rot: 0,    ts: 0,   scale: 1.00 },
];

const STATE_BY_KEY = {};
for (const s of STATES) STATE_BY_KEY[s.k] = s;

/* v8.2.2 palette table (spec §10.7 E1): the default blue/violet palette
   matches the former C_BLUE/C_VIOLET/C_DEEP constants component-wise (the 7
   normal states keep the same base look; the shader's clarity params were
   retuned slightly in v8.2.2 — alpha pow/shade/brightness — so the output is
   close to, not literally pixel-identical with, pre-v8.2.2). Error states
   pass red/amber boards. */
const PAL_DEFAULT = { a: [0.471, 0.627, 0.863], b: [0.549, 0.490, 0.839], c: [0.200, 0.251, 0.502] };

/** @param {{a:number[], b:number[], c:number[]}} p */
function clonePal(p) { return { a: p.a.slice(), b: p.b.slice(), c: p.c.slice() }; }

/* v8.2.1: lum pulse table (amplitude / period s) — render lerps the amplitude
   in/out and accumulates the phase continuously (no hard brightness jumps). */
/** @type {Object<string, number[]>} */
const PULSE_TABLE = { 'listening': [0.06, 1.6], 'nebula-busy': [0.05, 2.2], 'bg-agents': [0.03, 3.0] };

/* ========================================================================
   WebGL OrbRenderer — v7 transparent liquid material shader, v8.2.2 full
   port from mic-bubble-visual-v8.html (palette uniforms + phaseTime + pulse
   soft-start + volume-driven hover + rotSpeed tiers + stateScales).
   ======================================================================== */
const VS = [
  'precision highp float;',
  'attribute vec2 position; attribute vec2 uv;',
  'varying vec2 vUv;',
  'void main(){ vUv=uv; gl_Position=vec4(position,0.0,1.0); }'
].join('\n');

const FS = [
  'precision highp float;',
  'uniform float iTime; uniform vec3 iResolution;',
  'uniform float hue; uniform float hover; uniform float rot;',
  'uniform float hoverIntensity; uniform float isLight;',
  'uniform float sat; uniform float lum; uniform float timeScale;',
  '/* v8.2.2: palette as uniforms — error states (red/amber) share the same',
  '   crystalline structure (fluid/refraction/highlight/rim all preserved) */',
  'uniform vec3 palA; uniform vec3 palB; uniform vec3 palC;',
  'varying vec2 vUv;',
  'const vec3 LUMA=vec3(0.299,0.587,0.114);',
  'vec3 rgb2yiq(vec3 c){',
  '  float y=dot(c,vec3(0.299,0.587,0.114));',
  '  float i=dot(c,vec3(0.596,-0.274,-0.322));',
  '  float q=dot(c,vec3(0.211,-0.523,0.312));',
  '  return vec3(y,i,q);',
  '}',
  'vec3 yiq2rgb(vec3 c){',
  '  return vec3(c.x+0.956*c.y+0.621*c.z, c.x-0.272*c.y-0.647*c.z, c.x-1.106*c.y+1.703*c.z);',
  '}',
  'vec3 adjustHue(vec3 color,float hueDeg){',
  '  float hueRad=hueDeg*3.14159265/180.0;',
  '  vec3 yiq=rgb2yiq(color);',
  '  float cosA=cos(hueRad), sinA=sin(hueRad);',
  '  /* v8.1 fix: rotation direction aligned with CSS hue-rotate (YIQ positive',
  '     = blue->cyan; negated, +34 = warm violet, semantics per §10.1) */',
  '  float i=yiq.y*cosA+yiq.z*sinA;',
  '  float q=-yiq.y*sinA+yiq.z*cosA;',
  '  yiq.y=i; yiq.z=q;',
  '  return yiq2rgb(yiq);',
  '}',
  'vec3 hash33(vec3 p3){',
  '  p3=fract(p3*vec3(0.1031,0.11369,0.13787));',
  '  p3+=dot(p3,p3.yxz+19.19);',
  '  return -1.0+2.0*fract(vec3(p3.x+p3.y,p3.x+p3.z,p3.y+p3.z)*p3.zyx);',
  '}',
  'float snoise3(vec3 p){',
  '  const float K1=0.333333333; const float K2=0.166666667;',
  '  vec3 i=floor(p+(p.x+p.y+p.z)*K1);',
  '  vec3 d0=p-(i-(i.x+i.y+i.z)*K2);',
  '  vec3 e=step(vec3(0.0),d0-d0.yzx);',
  '  vec3 i1=e*(1.0-e.zxy);',
  '  vec3 i2=1.0-e.zxy*(1.0-e);',
  '  vec3 d1=d0-(i1-K2);',
  '  vec3 d2=d0-(i2-K1);',
  '  vec3 d3=d0-0.5;',
  '  vec4 h=max(0.6-vec4(dot(d0,d0),dot(d1,d1),dot(d2,d2),dot(d3,d3)),0.0);',
  '  vec4 n=h*h*h*h*vec4(dot(d0,hash33(i)),dot(d1,hash33(i+i1)),dot(d2,hash33(i+i2)),dot(d3,hash33(i+1.0)));',
  '  return dot(vec4(31.316),n);',
  '}',
  'const vec3 C_ICE   =vec3(0.845,0.905,0.985);',
  'vec4 draw(vec2 uv){',
  '  float t=iTime*timeScale;',
  '  /* v8.2.2: c1/c2/c3 from palA/palB/palC uniforms (default blue-violet',
  '     board; error states = red/amber boards), adjustHue unchanged */',
  '  vec3 c1=adjustHue(palA,hue);',
  '  vec3 c2=adjustHue(palB,hue);',
  '  vec3 c3=adjustHue(palC,hue);',
  '  float len=length(uv);',
  '  float nEdge=snoise3(vec3(uv*0.9,t*0.4))*0.5+0.5;',
  '  /* v8.2.6: orb body scaled down within a LARGER canvas (64px) so the glow',
  '     bloom has room to fade as a gradient past the body edge without being',
  '     clipped at the canvas boundary (v8.2.5 canvas was 48px and the body',
  '     filled it to ~0.94uv, leaving ~4px for the bloom = hard edge). Body',
  '     stays visually ~same px; the extra canvas is the glow field. */',
  '  /* SHARED distortion contour rEdge: every layer that sizes against the body',
  '     (S5 core / S6 rim / S7 spec / S8 caustic / S10 skirt) uses r0, so all',
  '     follow the same wobble field — nothing drifts past the body silhouette. */',
  '  float r0=mix(0.56,0.66,nEdge);',
  '  /* v8.2.11 (#24 white-edge): the near-white C_ICE sheens (ice field + broad',
  '     specular sheen) wash the LIT surface pale; at the alpha-feather silhouette',
  '     that pale bands against a non-white background as a WHITE EDGE ring',
  '     (strongest in high-saturation states via contrast). Window the near-white',
  '     to the interior so the silhouette carries the saturated body hue — the',
  '     glassy highlight is preserved in the body, the edge is not pale. */',
  '  float nwWin=1.0-smoothstep(r0*0.68,r0*0.94,len);',
  '  vec2 w=vec2(snoise3(vec3(uv*1.3+vec2(0.0,t*0.10),t*0.30)),',
  '              snoise3(vec3(uv*1.3+vec2(5.2,-t*0.08),t*0.30)));',
  '  vec2 p=uv+0.30*w;',
  '  float f1=snoise3(vec3(p*1.5,t*0.40))*0.5+0.5;',
  '  float f2=snoise3(vec3(p*3.2+vec2(2.7),t*0.55))*0.5+0.5;',
  '  vec3 col=mix(c3*1.22,c1*1.04,smoothstep(0.20,0.80,f1));',
  '  col=mix(col,c2,smoothstep(0.35,0.85,f1*0.5+f2*0.5)*0.55);',
  '  col+=C_ICE*pow(f2,5.0)*mix(0.38,0.30,isLight)*nwWin;',
  '  /* v8.2.10 #20: unify EVERY shading/rim sphere to the shared wobble',
  '     contour r0 (not a contracted fraction). A contracted sphere (0.985r0 /',
  '     0.90r0) collapses the surface normal to horizontal inside the body,',
  '     producing a ring-band brightness discontinuity that reads as a',
  '     "separate layer" and (being a fixed fraction of the wobble radius)',
  '     shifts as the body wiggles = the layer-edge leak. Using R=r0 for both',
  '     the shading normal and the fresnel rim keeps every brightness feature',
  '     on the SAME contour as the silhouette, so layers stay unified. */',
  '  float R=r0;',
  '  float z=sqrt(max(R*R-len*len,0.0));',
  '  vec3 N=normalize(vec3(uv,z));',
  '  vec3 Ld=normalize(vec3(-0.42,0.50,0.66));',
  '  float diff=clamp(dot(N,Ld),0.0,1.0);',
  '  float shade=mix(mix(0.65,0.65,isLight),1.12,pow(diff,0.9));',
  '  col*=shade;',
  '  float core=1.0-smoothstep(0.0,r0*0.58,len);',
  '  float pulse=0.88+0.12*snoise3(vec3(uv*2.2,t*0.8));',
  '  col+=mix(c1,C_ICE,0.4)*core*pulse*mix(0.28,0.20,isLight);',
  '  /* v8.2.10 #20: rim sphere unified to r0 (was r0*0.90) and its window',
  '     tightened to the SAME band as the alpha feather (0.96r0-1.00r0), so the',
  '     bright edge and the silhouette terminate together — no internal bright',
  '     ring, no dim halo band beyond it. */',
  '  float Rf=r0;',
  '  float zf=sqrt(max(Rf*Rf-len*len,0.0));',
  '  vec3 Nf=normalize(vec3(uv,zf));',
  '  float fres=pow(1.0-clamp(Nf.z,0.0,1.0),2.4)*(1.0-smoothstep(r0*0.96,r0*1.00,len));',
  '  /* alpha source: pre-rim body color (rim/halo brightening must not',
  '     inflate edge alpha through the alpha-from-rgb coupling) */',
  '  vec3 colNoRim=col;',
  '  vec3 rimBright=mix(c1,C_ICE,0.5);',
  '  col+=rimBright*fres*(1.0-isLight)*0.50;',
  '  col=mix(col,c3*1.10,fres*isLight*0.50);',
  '  col+=rimBright*fres*isLight*0.10;',
  '  vec3 H=normalize(Ld+vec3(0.0,0.0,1.0));',
  '  float ndh=clamp(dot(N,H),0.0,1.0);',
  '  vec3 specHi=(vec3(1.0,0.99,0.97)*pow(ndh,mix(90.0,140.0,isLight))*mix(0.75,0.85,isLight)+C_ICE*pow(ndh,8.0)*0.10)*nwWin;',
  '  col+=specHi; colNoRim+=specHi;',
  '  float ca=1.0-smoothstep(0.0,r0*0.30,distance(uv,vec2(0.10,-0.42)*r0));',
  '  vec3 caHi=mix(c1,C_ICE,0.3)*ca*ca*mix(0.20,0.14,isLight);',
  '  col+=caHi; colNoRim+=caHi;',
  '  col*=mix(1.03,1.12,isLight); colNoRim*=mix(1.03,1.12,isLight);',
  '  float g0=dot(col,LUMA);',
  '  col=mix(col,mix(vec3(g0),col,1.42),isLight);',
  '  col=clamp(col,0.0,1.0);',
  '  float gb=dot(colNoRim,LUMA);',
  '  colNoRim=mix(colNoRim,mix(vec3(gb),colNoRim,1.42),isLight);',
  '  colNoRim=clamp(colNoRim,0.0,1.0);',
  '  /* v8.2.8: outer glow/bloom REMOVED (user report 2026-08-30: the lavender',
  '     fluorescent ring around the body is unwanted in BOTH themes — keep only the',
  '     lit bubble body). The old halo bloom (exp falloff * haloA * haloCol mixed',
  '     into outCol) plus the widened dark skirt painted that ring. The body',
  '     material (<=0.92r0, fluid/refraction/rim/spec/core) and the alpha-from-',
  '     colNoRim coupling (v8.2.4) are untouched; outCol is now just the body color,',
  '     so nothing bright spreads outside the silhouette. */',
  '  vec3 outCol=col;',
  '  float gg=dot(outCol,LUMA);',
  '  outCol=mix(vec3(gg),outCol,sat)*lum;',
  '  outCol=clamp(outCol,0.0,1.0);',
  '  float gb2=dot(colNoRim,LUMA);',
  '  colNoRim=mix(vec3(gb2),colNoRim,sat)*lum;',
  '  colNoRim=clamp(colNoRim,0.0,1.0);',
  '  float glassA=pow(clamp(max(colNoRim.r,max(colNoRim.g,colNoRim.b))*1.06,0.0,1.0),1.58);',
  '  /* v8.2.9 (user 2026-08-30): clip EVERY layer to the body visual edge — no',
  '     layer may exceed the S2 body silhouette (rEdge, the shared per-pixel wobble',
  '     radius). v8.2.6/7 widened the skirt (1.44/1.15r0) to carry a soft glow;',
  '     v8.2.8 removed that glow but kept skirtEnd=1.06r0, which still painted a faint',
  '     translucent band BEYOND the body edge. As the body wobbles, that band peeks',
  '     out asymmetrically = background leak. Now the feather ends exactly AT the',
  '     body edge (r0) and follows the same wobble field, so alpha dies at the',
  '     silhouette — nothing extends past the body\'s visual edge at any phase. */',
  '  float skirtEnd=r0;',
  '  /* v8.2.10 #20: tighten the alpha feather to a narrow band at the edge',
  '     (0.96r0-1.00r0) instead of the wide 0.90-1.00r0 skirt. The wide band was',
  '     a translucent halo BEYOND the bright rim that peeked out as the body',
  '     wobbled = the "layer leak". Now the body stays opaque to ~0.96r0 and',
  '     dies right at the silhouette (r0), same contour as the rim. */',
  '  float shape=1.0-smoothstep(r0*0.96,skirtEnd,len);',
  '  /* #23 edge-root fix (user 2026-08-30, 4th report): every layer must track',
  '     the BODY visual edge = the S1 wobble contour r0, and the visible alpha',
  '     edge must be that silhouette — not the S2 luminance field. Old',
  '     a=glassA*shape let glassA (lum->alpha, driven by colNoRim which is a',
  '     FIXED uv-space pattern) collapse near the edge (colNoRim dims via the',
  '     S4 shade dark side + S2 dark c3), dragging the visible edge INWARD',
  '     BEFORE the shape feather. Empirical: alpha0.5 edge varied by angle/theme',
  '     (dark 0.22-0.59, light 0.36-0.62uv) because the luminance field does NOT',
  '     follow the wobble = the "layer separation" / shifted edge the user keeps',
  '     flagging. Replacing glassA with a constant edge made alpha0.5 UNIFORM',
  '     (spread 0.375->0.047, dark/light both 0.59uv=r0) — proven root.',
  '     Fix: read alpha as a SOLID glass body whose silhouette = shape (r0).',
  '     glassA modulates only a narrow interior 0.86-1.0 band (liquid texture',
  '     shows through COLOR, never through alpha collapse), so the edge stays a',
  '     coherent boundary at the same contour as the rim in BOTH themes. */',
  '  float glassBody=mix(0.86,1.0,glassA);',
  '  float a=clamp(glassBody*shape,0.0,1.0);',
  '  a=mix(a,a*0.94,isLight);',
  '  /* v8.2.4: the light-theme inner lift is gated by shape - the bare',
  '     smoothstep stays at 1 past 0.96r0 and painted a constant +0.12 alpha',
  '     veil across the whole canvas outside the orb (square cutout edge). It',
  '     now fades with the widened skirt instead of a sharp 1.05r0 cutoff. */',
  '  a=clamp(a+smoothstep(r0*0.80,r0*0.96,len)*shape*isLight*0.12,0.0,1.0);',
  '  return vec4(outCol,clamp(a,0.0,1.0));',
  '}',
  'vec4 mainImage(vec2 fragCoord){',
  '  vec2 center=iResolution.xy*0.5;',
  '  float size=min(iResolution.x,iResolution.y);',
  '  vec2 uv=(fragCoord-center)/size*2.0;',
  '  float angle=rot;',
  '  float s=sin(angle), c=cos(angle);',
  '  uv=vec2(c*uv.x-s*uv.y,s*uv.x+c*uv.y);',
  '  float tt=iTime*timeScale;',
  '  uv.x+=hover*hoverIntensity*0.1*sin(uv.y*9.0+tt);',
  '  uv.y+=hover*hoverIntensity*0.1*sin(uv.x*9.0+tt);',
  '  return draw(uv);',
  '}',
  'void main(){',
  '  vec2 fragCoord=vUv*iResolution.xy;',
  '  vec4 col=mainImage(fragCoord);',
  '  gl_FragColor=vec4(col.rgb,col.a);',
  '}'
].join('\n');

/** WebGL liquid orb renderer (v8.2.2: palette uniforms + jarvis motion). */
class OrbRenderer {
  /**
   * @param {HTMLCanvasElement} canvas
   * @param {{ size?: number, reducedMotion?: boolean }} [opts]
   */
  constructor(canvas, opts) {
    this.canvas = canvas;
    this.opts = opts || {};
    this.reducedMotion = !!this.opts.reducedMotion;
    /* v8.2.5: supersampling floor of 2 - in dpr=1 environments the old
       48*dpr backing (48x48) was stretched to ~96 physical px and the rim
       turned blocky. Render at >=96px and let the CSS downscale smooth it.
       Cap at 3 to bound GPU cost on 3x mobile screens. */
    this.dpr = Math.min(Math.max(window.devicePixelRatio || 1, 2), 3);
    this.size = this.opts.size || 64;
    canvas.width = Math.round(this.size * this.dpr);
    canvas.height = Math.round(this.size * this.dpr);
    /* v8.2.5: track dpr changes (browser zoom) and resize the backing store
       live so a stale low-res buffer never persists. */
    this.bindDprTracking();
    /** @type {WebGLRenderingContext} */
    this.gl = /** @type {WebGLRenderingContext} */ (
      canvas.getContext('webgl', { alpha: true, premultipliedAlpha: false, preserveDrawingBuffer: true })
      || canvas.getContext('experimental-webgl', { alpha: true, premultipliedAlpha: false, preserveDrawingBuffer: true })
    );
    if (!this.gl) { this.failed = true; return; }
    this.failed = false;
    this.startTime = performance.now();
    this.lastTime = 0;
    this.params = { hue: 0, hover: 0.08, rot: 0, sat: 1, lum: 1, timeScale: 1 };
    this.target = { hue: 0, hover: 0.08, rotSpeed: 0.05, sat: 1, lum: 1, timeScale: 0.5, scale: 1.0, pal: PAL_DEFAULT };
    /* v8.2 jarvis port: volume-driven hover / state scale / switch pulse */
    this.volume = 0;           // mic RMS normalized to [0,1]
    this.volDriven = false;    // whether the current state's hover follows volume
    this.scale = 1.0;
    this.transitionPulse = 0;
    /* v8.2.1 no-flicker: */
    this.pulseTarget = 0;      // pulse soft-start target (rise then decay)
    this.phaseTime = 0;        // shading phase time = integral of timeScale*dt
    this.pulsePhase = 0;       // lum pulse phase (accumulates continuously)
    this.pulseAmp = 0;         // lum pulse amplitude (lerped in/out)
    this.pulsePeriod = 1.6;
    this.hoverIntensity = 1.0; // lerped 1.0<->1.4
    this.pal = clonePal(PAL_DEFAULT); // current palette (0.04/frame lerp)
    this.state = 'idle';
    this.initShaders();
    this.initBuffers();
    this.render = this.render.bind(this);
    this.running = !this.reducedMotion;
    this.drawFrame();
    if (this.running) requestAnimationFrame(this.render);
  }

  initShaders() {
    const gl = this.gl;
    const compile = (type, src) => {
      const sh = gl.createShader(type);
      gl.shaderSource(sh, src);
      gl.compileShader(sh);
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
        console.error('[micOrb] shader compile error:', gl.getShaderInfoLog(sh));
      }
      return sh;
    };
    const prog = gl.createProgram();
    gl.attachShader(prog, compile(gl.VERTEX_SHADER, VS));
    gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, FS));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      console.error('[micOrb] program link error:', gl.getProgramInfoLog(prog));
    }
    this.program = prog;
    gl.useProgram(prog);
    this.u = {};
    ['iTime', 'iResolution', 'hue', 'hover', 'rot', 'hoverIntensity', 'isLight', 'sat', 'lum', 'timeScale', 'palA', 'palB', 'palC']
      .forEach((n) => { this.u[n] = gl.getUniformLocation(prog, n); });
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
  }

  initBuffers() {
    const gl = this.gl;
    const verts = new Float32Array([-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1]);
    this.buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, this.buf);
    gl.bufferData(gl.ARRAY_BUFFER, verts, gl.STATIC_DRAW);
    const posLoc = gl.getAttribLocation(this.program, 'position');
    gl.enableVertexAttribArray(posLoc);
    gl.vertexAttribPointer(posLoc, 2, gl.FLOAT, false, 16, 0);
    const uvLoc = gl.getAttribLocation(this.program, 'uv');
    gl.enableVertexAttribArray(uvLoc);
    gl.vertexAttribPointer(uvLoc, 2, gl.FLOAT, false, 16, 8);
  }

  /* v8.2.5: rebuild the backing store when devicePixelRatio changes (browser
     zoom in/out). Each listener is one-shot - the query embeds the current
     dpr, so a change fires it and we re-arm with the new value. */
  bindDprTracking() {
    if (!window.matchMedia) return;
    const arm = () => {
      const mq = window.matchMedia(`(resolution: ${window.devicePixelRatio || 1}dppx)`);
      const onChange = () => {
        if (mq.removeEventListener) mq.removeEventListener('change', onChange);
        else if (mq.removeListener) mq.removeListener(onChange);
        this.resizeBacking();
        arm();
      };
      if (mq.addEventListener) mq.addEventListener('change', onChange);
      else if (mq.addListener) mq.addListener(onChange);
    };
    arm();
  }

  /* v8.2.5: recompute the supersampled dpr and resize the canvas backing
     store; resizing clears the GL buffer so redraw one frame immediately. */
  resizeBacking() {
    if (!this.gl || this.failed) return;
    const next = Math.min(Math.max(window.devicePixelRatio || 1, 2), 3);
    const w = Math.round(this.size * next);
    if (w === this.canvas.width) return;
    this.dpr = next;
    this.canvas.width = w;
    this.canvas.height = w;
    this.drawFrame();
  }

  /**
   * @param {{k:string,hue?:number,rot?:number,sat?:number,lum?:number,ts?:number,scale?:number,vol?:boolean,pal?:{a:number[],b:number[],c:number[]}}} s
   */
  setStateCfg(s) {
    this.state = s.k;
    this.target.hue = s.hue || 0;
    this.target.rotSpeed = s.rot || 0;
    this.target.sat = (s.sat === undefined ? 1 : s.sat);
    this.target.lum = (s.lum === undefined ? 1 : s.lum);
    this.target.timeScale = (s.ts === undefined ? 1 : s.ts);
    this.target.scale = (s.scale === undefined ? 1 : s.scale);
    /* v8.2.2: error-state palettes (red/amber) lerp in over ~0.7s — no hard cut */
    this.target.pal = s.pal || PAL_DEFAULT;
    /* v8.2: hover='volume' states (listening) keep hover under setVolume
       control; other states fall back to the 0.08 base perturbation */
    this.volDriven = !!s.vol;
    if (!this.volDriven) this.target.hover = 0.08;
    if (this.reducedMotion) {
      // Reduced motion (spec §10.3): snap to the target (color, palette,
      // scale) and freeze time — no lerp, no pulse, no flow; one still frame.
      this.params.hue = this.target.hue;
      this.params.sat = this.target.sat;
      this.params.lum = this.target.lum;
      this.params.hover = this.target.hover;
      this.params.timeScale = 0;
      this.target.timeScale = 0;
      this.scale = this.target.scale;
      this.pal = clonePal(this.target.pal);
      this.pulseTarget = 0;
      this.transitionPulse = 0;
      this.pulseAmp = 0;
      this.drawFrame();
      return;
    }
    /* v8.2.1: transitionPulse soft start (render rises then decays) */
    this.pulseTarget = 1.0;
    this.resume();
  }

  /** v8.2: volume-driven listening shake (production feeds voiceEngine RMS). */
  setVolume(v) {
    this.volume = Math.max(0, Math.min(1, v));
  }

  /** @param {boolean} isLight */
  setTheme(isLight) {
    if (!this.gl) return;
    this.gl.useProgram(this.program);
    this.gl.uniform1f(this.u.isLight, isLight ? 1 : 0);
    if (this.reducedMotion) this.drawFrame();
  }

  pause() { this.running = false; }

  resume() {
    if (!this.running) { this.running = true; this.lastTime = 0; this.render(); }
  }

  /* One GL frame with no scheduling (the body of the animation loop). */
  drawFrame() {
    if (!this.gl || this.failed) return;
    const gl = this.gl;
    const now = performance.now();
    const time = (now - this.startTime) / 1000;
    let dt = this.lastTime ? (time - this.lastTime) : 0.016;
    dt = Math.min(dt, 0.05); // clamp: tab-switch/dropped frames must not jump phase
    this.lastTime = time;
    /* v8.2.1 main fix: shading phase time accumulates (phaseTime += dt·ts).
       Absolute iTime·ts made state switches jump the whole noise field. */
    this.phaseTime += dt * this.params.timeScale;
    if (!this.reducedMotion) {
      this.params.hue += (this.target.hue - this.params.hue) * 0.035;
      /* v8.2: hover='volume' — listening hover follows mic volume
         (target = 0.10 + volume·0.90), lerp 0.1 */
      if (this.volDriven) this.target.hover = 0.10 + this.volume * 0.90;
      this.params.hover += (this.target.hover - this.params.hover) * 0.1;
      this.params.rot += dt * this.target.rotSpeed;
      this.params.sat += (this.target.sat - this.params.sat) * 0.04;
      this.params.lum += (this.target.lum - this.params.lum) * 0.04;
      this.params.timeScale += (this.target.timeScale - this.params.timeScale) * 0.04;
      /* v8.2.1: transitionPulse soft start — rise (lerp 0.3, ~0.25s to peak)
         then decay ×0.92; no first-frame effHover step */
      if (this.pulseTarget > 0) {
        this.transitionPulse += (this.pulseTarget - this.transitionPulse) * 0.3;
        if (this.pulseTarget - this.transitionPulse < 0.03) this.pulseTarget = 0;
      } else if (this.transitionPulse > 0) {
        this.transitionPulse *= 0.92;
        if (this.transitionPulse < 0.01) this.transitionPulse = 0;
      }
      /* v8.2: stateScale lerp 0.08 */
      this.scale += (this.target.scale - this.scale) * 0.08;
      /* v8.2.1: hoverIntensity lerped 1.0<->1.4 (no 40% distortion step) */
      this.hoverIntensity += ((this.volDriven ? 1.4 : 1.0) - this.hoverIntensity) * 0.08;
      /* v8.2.2: palette lerp 0.04/frame — error-state switches are smooth */
      const tpal = this.target.pal, cpal = this.pal;
      for (const k of ['a', 'b', 'c']) {
        for (let i = 0; i < 3; i++) cpal[k][i] += (tpal[k][i] - cpal[k][i]) * 0.04;
      }
      /* v8.2.1: lum pulse — amplitude lerps in/out, phase accumulates */
      const pc = PULSE_TABLE[this.state];
      if (pc) this.pulsePeriod = pc[1];
      this.pulseAmp += ((pc ? pc[0] : 0) - this.pulseAmp) * 0.06;
      this.pulsePhase += dt * Math.PI * 2 / this.pulsePeriod;
    }
    const effHover = Math.min(1, this.params.hover + this.transitionPulse * 0.3);
    gl.viewport(0, 0, this.canvas.width, this.canvas.height);
    gl.useProgram(this.program);
    gl.uniform1f(this.u.iTime, this.phaseTime); // phase time, not wall time
    gl.uniform3f(this.u.iResolution, this.canvas.width, this.canvas.height, 1);
    gl.uniform1f(this.u.hue, this.params.hue);
    gl.uniform1f(this.u.hover, effHover);
    gl.uniform1f(this.u.rot, this.params.rot);
    gl.uniform1f(this.u.hoverIntensity, this.hoverIntensity);
    gl.uniform1f(this.u.sat, this.params.sat);
    gl.uniform3fv(this.u.palA, this.pal.a);
    gl.uniform3fv(this.u.palB, this.pal.b);
    gl.uniform3fv(this.u.palC, this.pal.c);
    const lum = this.params.lum * (1 + this.pulseAmp * Math.sin(this.pulsePhase));
    gl.uniform1f(this.u.lum, lum);
    /* v8.2.1: timeScale uniform stays 1 — flow rate lives in phaseTime */
    gl.uniform1f(this.u.timeScale, 1.0);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    /* v8.2: stateScales apply in every state; the L1 breath still only
       stacks on idle */
    let sc = this.scale;
    if (!this.reducedMotion && this.state === 'idle' && this.params.timeScale > 0.01) {
      sc *= 1 + Math.sin(time * 0.8) * 0.02;
    }
    if (Math.abs(sc - 1) > 0.001) {
      this.canvas.style.transform = 'scale(' + sc.toFixed(4) + ')';
    } else if (this.canvas.style.transform) {
      this.canvas.style.transform = '';
    }
  }

  render() {
    this.drawFrame();
    if (this.running) requestAnimationFrame(this.render);
  }
}

/* ========================================================================
   MicOrb controller — binds the DOM (button canvas + css-orb fallback) and a
   dimensional signal hub. State derivation priority (single orb, single
   state): mic-error > frozen-error > offline > frozen > listening >
   processing > nebula-busy > bg-agents > idle.
   ======================================================================== */

// Signal dimensions (fed by voice events, freeze class observer, polls).
const dims = {
  micError: false,
  frozenError: false,
  offline: false,
  frozen: false,
  listening: false,
  processing: false,
  nebulaBusy: false,
  bgAgents: false,
};

/** Derive the single effective orb state from the dimensional signals. */
function deriveState() {
  if (dims.micError) return 'mic-error';
  if (dims.frozenError) return 'frozen-error';
  if (dims.offline) return 'offline';
  if (dims.frozen) return 'frozen';
  if (dims.listening) return 'listening';
  if (dims.processing) return 'processing';
  if (dims.nebulaBusy) return 'nebula-busy';
  if (dims.bgAgents) return 'bg-agents';
  return 'idle';
}

class MicOrb {
  constructor() {
    /** @type {HTMLElement|null} */ this.btn = document.getElementById('voice-btn');
    /** @type {HTMLCanvasElement|null} */
    this.canvas = this.btn ? /** @type {HTMLCanvasElement|null} */ (this.btn.querySelector('.orb-canvas')) : null;
    /** @type {HTMLElement|null} */
    this.cssOrb = this.btn ? /** @type {HTMLElement|null} */ (this.btn.querySelector('.css-orb')) : null;
    this.state = 'idle';
    this.renderer = null;
    this.webglOk = false;
    this.micErrorTimer = null;
    this.reduced = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

    if (this.canvas) {
      this.renderer = new OrbRenderer(this.canvas, { size: 64, reducedMotion: this.reduced });
      this.webglOk = !this.renderer.failed;
      // WebGL context lost -> permanent CSS fallback (restored is rare; the CSS
      // orb is visually equivalent for the state colors).
      this.canvas.addEventListener('webglcontextlost', (e) => {
        e.preventDefault();
        this.webglOk = false;
        if (this.renderer) this.renderer.pause();
        this.apply(this.state);
      });
      // v8.2: feed mic volume into the renderer (listening shake). Dynamic
      // import keeps micOrb cycle-free (voiceEngine pulls in ws.js).
      import('./voiceEngine.js').then((m) => {
        m.setMicVolumeListener((v) => {
          if (this.renderer && !this.renderer.failed) this.renderer.setVolume(v);
        });
      }).catch(() => {});
    }
    // v8.1: click ripple (jarvis orb-ripple paradigm) — decorative ring that
    // expands from the orb edge on every click; disabled under reduced motion.
    if (this.btn) this.btn.addEventListener('click', () => this.spawnRipple());
    this.applyTheme();
    this.apply('idle');
    this.bindObservers();
  }

  applyTheme() {
    const isLight = !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches);
    if (this.webglOk && this.renderer) this.renderer.setTheme(isLight);
  }

  /**
   * Apply a state: v8.2.2 — every state goes through the WebGL path (error
   * states included, via palette uniforms); CSS orb only when WebGL failed.
   * @param {string} key
   */
  apply(key) {
    const s = STATE_BY_KEY[key] || STATE_BY_KEY.idle;
    this.state = s.k;
    const useCss = !this.webglOk;
    if (this.cssOrb) {
      this.cssOrb.className = 'css-orb orb ' + s.cls;
      this.cssOrb.style.display = useCss ? 'block' : 'none';
    }
    if (this.canvas) this.canvas.style.display = useCss ? 'none' : 'block';
    if (!useCss && this.renderer) {
      this.renderer.setStateCfg(s);
      this.applyTheme();
    }
    // Wrap carries the state class so CSS vars (--orb-glow/--orb-dot/...)
    // follow for the fallback orb + ripple color.
    if (this.btn) {
      const wrap = this.btn.closest('.mic-orb-wrap');
      if (wrap) {
        for (const st of STATES) wrap.classList.remove(st.cls);
        wrap.classList.add(s.cls);
      }
    }
    this.updateA11y();
  }

  /* v8.1: no visual text label — state reaches screen readers via aria-label
     (runtime t() text; the canvas stays aria-hidden, decorative). The button
     label keeps the control action ('voice input') alongside the state so the
     a11y name still describes what the control does. */
  updateA11y() {
    const s = STATE_BY_KEY[this.state] || STATE_BY_KEY.idle;
    const label = t(s.i18n);
    const btnLabel = `${label} · ${t('input.voiceBtn')}`;
    if (this.btn) this.btn.setAttribute('aria-label', btnLabel);
    if (this.cssOrb) {
      this.cssOrb.setAttribute('role', 'img');
      this.cssOrb.setAttribute('aria-label', label);
    }
  }

  /* v8.1: click ripple — 2px ring in the current state's semantic color,
     scale 0.8->1.5 / opacity 0.6->0 over 0.8s, removed on animationend. */
  spawnRipple() {
    if (this.reduced || !this.btn) return;
    const old = this.btn.querySelector('.orb-ripple');
    if (old) old.remove();
    const r = document.createElement('span');
    r.className = 'orb-ripple go';
    const wrap = this.btn.closest('.mic-orb-wrap');
    const color = wrap ? getComputedStyle(wrap).getPropertyValue('--orb-dot').trim() : '';
    r.style.setProperty('--ripple', color || '#78A0DC');
    this.btn.appendChild(r);
    r.addEventListener('animationend', () => r.remove());
  }

  /* Feed a voice signal from input.js (voiceEngine onState). */
  notifyVoiceState(voiceState) {
    switch (voiceState) {
      case 'listening':
      case 'speaking':
        dims.listening = true;
        dims.processing = false;
        dims.micError = false;
        break;
      case 'processing':
        dims.processing = true;
        dims.listening = false;
        break;
      case 'error':
        dims.listening = false;
        dims.processing = false;
        dims.micError = true;
        // Mic errors surface red, then auto-settle back after ~8s.
        if (this.micErrorTimer) clearTimeout(this.micErrorTimer);
        this.micErrorTimer = setTimeout(() => { dims.micError = false; this.refresh(); }, 8000);
        break;
      case 'idle':
      default:
        dims.listening = false;
        dims.processing = false;
        break;
    }
    this.refresh();
  }

  /* Recompute the effective state from the dimensional signals and apply. */
  refresh() {
    const next = deriveState();
    if (next !== this.state) this.apply(next);
    else this.updateA11y();
  }

  /* Test harness helper: clear every signal dimension (and the mic-error
     auto-settle timer) so a fresh state can be driven deterministically. */
  clearDims() {
    dims.micError = dims.frozenError = dims.offline = dims.frozen = false;
    dims.listening = dims.processing = dims.nebulaBusy = dims.bgAgents = false;
    if (this.micErrorTimer) { clearTimeout(this.micErrorTimer); this.micErrorTimer = null; }
  }

  bindObservers() {
    // Frozen / frozen-error: the input bar carries the class (no pub/sub on
    // state.js). Observe the class attribute on the primary input bar.
    const bar = document.getElementById('input-bar');
    if (bar && typeof MutationObserver !== 'undefined') {
      const sync = () => {
        dims.frozenError = bar.classList.contains('frozen-error');
        dims.frozen = !dims.frozenError && bar.classList.contains('frozen');
        this.refresh();
      };
      new MutationObserver(sync).observe(bar, { attributes: true, attributeFilter: ['class'] });
      sync();
    }
    // Theme: shader isLight uniform follows prefers-color-scheme.
    // v8.2.1 F5: theme switch only updates the uniform — it must NOT re-run
    // setStateCfg (that would re-trigger the transition pulse = flicker).
    if (window.matchMedia) {
      const mq = window.matchMedia('(prefers-color-scheme: light)');
      const onChange = () => this.applyTheme();
      if (mq.addEventListener) mq.addEventListener('change', onChange);
      else if (mq.addListener) mq.addListener(onChange);
    }
    // Locale: re-render the aria-label text when the language switches.
    window.addEventListener('locale-changed', () => this.updateA11y());
    // Busy / bg-agents / offline have no push event on state.js — poll 1s.
    setInterval(() => this.pollDerived(), 1000);
  }

  /* Poll the push-less dimensions from state.js (busy / bg-agents / offline). */
  pollDerived() {
    const sid = state.activeSessionId;
    const nebulaBusy = !!(sid && state.busySessionIds && state.busySessionIds.has(sid));
    let bgAgents = false;
    const agents = sid && state.sessionBgAgents ? state.sessionBgAgents[sid] : null;
    if (agents) {
      for (const aid of Object.keys(agents)) {
        const a = agents[aid];
        if (a && (a.status === 'Processing' || a.status === 'processing')) { bgAgents = true; break; }
      }
    }
    const offline = state.connected === false;
    if (nebulaBusy !== dims.nebulaBusy || bgAgents !== dims.bgAgents || offline !== dims.offline) {
      dims.nebulaBusy = nebulaBusy;
      dims.bgAgents = bgAgents;
      dims.offline = offline;
      this.refresh();
    }
  }
}

let instance = null;

/** Lazily create + return the MicOrb singleton (binds #voice-btn). */
export function getMicOrb() {
  if (!instance && document.getElementById('voice-btn')) instance = new MicOrb();
  return instance;
}

/** Initialize the orb after the DOM is ready (called once from main.js). */
export function initMicOrb() {
  getMicOrb();
}

/** Feed a voice state (listening/processing/error/idle) to the orb. */
export function notifyVoiceState(voiceState) {
  const o = getMicOrb();
  if (o) o.notifyVoiceState(voiceState);
}

/** Reset the singleton (test harness only). */
export function __resetMicOrbForTest() {
  if (instance && instance.renderer) instance.renderer.pause();
  instance = null;
  dims.micError = dims.frozenError = dims.offline = dims.frozen = false;
  dims.listening = dims.processing = dims.nebulaBusy = dims.bgAgents = false;
}
