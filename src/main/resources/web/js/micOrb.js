// micOrb.js — Mic bubble (liquid orb) v8.0: pure Orb, state expressed via
// color + text label, zero overlay layers (user ruling 2026-08-26 01:11:
// "还是用纯Orb吧，然后Orb用颜色和文字来显示状态" — v1-v7's 9-state complex
// overlay animations, listening shake / bg-agent orbit dots / frost ring /
// busy glow are all dropped). The orb body keeps the v7 transparent liquid
// material (extractAlpha / background blending / isLight branch); state is
// expressed via the 9-state color map (spec §10.1) + a text label (§10.2).
// Spec: mic-bubble-spec.md §10. Reference render: mic-bubble-visual-v8.html.
//
// Self-contained: imports state.js + i18n.js only (no cycles). One orb lives
// on the primary input bar (popup views have voiceBtn:null — no orb there).

import state from './state.js';
import { t } from './i18n.js';

/* ========================================================================
   9-state definition (spec §10.1). hue/sat/lum feed the v7 shader's
   adjustHue + sat + lum mechanism; css:true states (frozen-error amber /
   mic-error red) are unreachable by hue rotation and use an independent
   palette that overrides the CSS orb background.
   ======================================================================== */
const STATES = [
  { k: 'idle',         cls: 's-idle',      i18n: 'chat.micOrb.idle',       hue: 0,   sat: 1.00, lum: 1.00, rot: 0.05, ts: 1 },
  { k: 'listening',    cls: 's-listening', i18n: 'chat.micOrb.listening',  hue: -18, sat: 1.08, lum: 1.05, rot: 0.18, ts: 1 },
  { k: 'processing',   cls: 's-processing',i18n: 'chat.micOrb.processing', hue: 22,  sat: 1.06, lum: 0.94, rot: 0.12, ts: 1 },
  { k: 'nebula-busy',  cls: 's-nebula',    i18n: 'chat.micOrb.nebulaBusy', hue: 34,  sat: 1.16, lum: 1.06, rot: 0.10, ts: 1 },
  { k: 'bg-agents',    cls: 's-bg',        i18n: 'chat.micOrb.bgAgents',   hue: 8,   sat: 0.80, lum: 0.92, rot: 0.06, ts: 1 },
  { k: 'frozen',       cls: 's-frozen',    i18n: 'chat.micOrb.frozen',     hue: 0,   sat: 0.40, lum: 0.74, rot: 0,    ts: 0 },
  { k: 'frozen-error', cls: 's-frozenerr', i18n: 'chat.micOrb.frozenError', css: true },
  { k: 'mic-error',    cls: 's-micerr',    i18n: 'chat.micOrb.micError',   css: true },
  { k: 'offline',      cls: 's-offline',   i18n: 'chat.micOrb.offline',    hue: 0,   sat: 0.00, lum: 0.85, rot: 0,    ts: 0.2 },
];

const STATE_BY_KEY = {};
for (const s of STATES) STATE_BY_KEY[s.k] = s;

/* ========================================================================
   WebGL OrbRenderer — v7 transparent liquid material shader, ported verbatim
   from mic-bubble-visual-v8.html (stateCfg already extended to 9 states).
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
  'varying vec2 vUv;',
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
  '  float i=yiq.y*cosA-yiq.z*sinA;',
  '  float q=yiq.y*sinA+yiq.z*cosA;',
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
  'const vec3 C_BLUE  =vec3(0.471,0.627,0.863);',
  'const vec3 C_VIOLET=vec3(0.549,0.490,0.839);',
  'const vec3 C_DEEP  =vec3(0.200,0.251,0.502);',
  'const vec3 C_ICE   =vec3(0.845,0.905,0.985);',
  'vec4 draw(vec2 uv){',
  '  float t=iTime*timeScale;',
  '  vec3 c1=adjustHue(C_BLUE,hue);',
  '  vec3 c2=adjustHue(C_VIOLET,hue);',
  '  vec3 c3=adjustHue(C_DEEP,hue);',
  '  float len=length(uv);',
  '  float nEdge=snoise3(vec3(uv*0.9,t*0.4))*0.5+0.5;',
  '  float r0=mix(0.80,0.94,nEdge);',
  '  float bodyA=1.0-smoothstep(r0,r0*1.10,len);',
  '  vec2 w=vec2(snoise3(vec3(uv*1.3+vec2(0.0,t*0.10),t*0.30)),',
  '              snoise3(vec3(uv*1.3+vec2(5.2,-t*0.08),t*0.30)));',
  '  vec2 p=uv+0.30*w;',
  '  float f1=snoise3(vec3(p*1.5,t*0.40))*0.5+0.5;',
  '  float f2=snoise3(vec3(p*3.2+vec2(2.7),t*0.55))*0.5+0.5;',
  '  vec3 col=mix(c3*1.22,c1*1.04,smoothstep(0.20,0.80,f1));',
  '  col=mix(col,c2,smoothstep(0.35,0.85,f1*0.5+f2*0.5)*0.55);',
  '  col+=C_ICE*pow(f2,5.0)*mix(0.38,0.30,isLight);',
  '  float R=r0*0.985;',
  '  float z=sqrt(max(R*R-len*len,0.0));',
  '  vec3 N=normalize(vec3(uv,z));',
  '  vec3 Ld=normalize(vec3(-0.42,0.50,0.66));',
  '  float diff=clamp(dot(N,Ld),0.0,1.0);',
  '  float shade=mix(mix(0.62,0.62,isLight),1.12,pow(diff,0.9));',
  '  col*=shade;',
  '  float core=1.0-smoothstep(0.0,r0*0.58,len);',
  '  float pulse=0.88+0.12*snoise3(vec3(uv*2.2,t*0.8));',
  '  col+=mix(c1,C_ICE,0.4)*core*pulse*mix(0.28,0.20,isLight);',
  '  float fres=pow(1.0-clamp(N.z,0.0,1.0),2.4);',
  '  vec3 rimBright=mix(c1,C_ICE,0.5);',
  '  col+=rimBright*fres*(1.0-isLight)*0.50;',
  '  col=mix(col,c3*1.10,fres*isLight*0.50);',
  '  col+=rimBright*fres*isLight*0.10;',
  '  vec3 H=normalize(Ld+vec3(0.0,0.0,1.0));',
  '  float ndh=clamp(dot(N,H),0.0,1.0);',
  '  col+=vec3(1.0,0.99,0.97)*pow(ndh,mix(90.0,140.0,isLight))*mix(0.75,0.85,isLight);',
  '  col+=C_ICE*pow(ndh,8.0)*0.10;',
  '  float ca=1.0-smoothstep(0.0,r0*0.30,distance(uv,vec2(0.10,-0.42)*r0));',
  '  col+=mix(c1,C_ICE,0.3)*ca*ca*mix(0.20,0.14,isLight);',
  '  col*=mix(1.0,1.10,isLight);',
  '  float g0=dot(col,vec3(0.299,0.587,0.114));',
  '  col=mix(col,mix(vec3(g0),col,1.42),isLight);',
  '  col=clamp(col,0.0,1.0);',
  '  float halo=exp(-max(len-r0*1.02,0.0)*9.0)*(1.0-bodyA);',
  '  float haloA=halo*0.16*(1.0-isLight);',
  '  vec3 haloCol=mix(c1,c2,0.5)*1.15;',
  '  vec3 outCol=mix(col,haloCol,clamp(haloA*1.5,0.0,1.0)*(1.0-bodyA));',
  '  float gg=dot(outCol,vec3(0.299,0.587,0.114));',
  '  outCol=mix(vec3(gg),outCol,sat)*lum;',
  '  outCol=clamp(outCol,0.0,1.0);',
  '  float glassA=pow(clamp(max(outCol.r,max(outCol.g,outCol.b))*1.10,0.0,1.0),1.45);',
  '  float shape=1.0-smoothstep(r0*0.98,r0*1.14,len);',
  '  float a=clamp(glassA*shape,0.0,1.0);',
  '  a=mix(a,a*0.96,isLight);',
  '  a=clamp(a+smoothstep(r0*0.80,r0*1.00,len)*isLight*0.12,0.0,1.0);',
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

/** WebGL liquid orb renderer (v7 transparent material). */
class OrbRenderer {
  /**
   * @param {HTMLCanvasElement} canvas
   * @param {{ size?: number, reducedMotion?: boolean }} [opts]
   */
  constructor(canvas, opts) {
    this.canvas = canvas;
    this.opts = opts || {};
    this.reducedMotion = !!this.opts.reducedMotion;
    this.dpr = Math.min(window.devicePixelRatio || 1, 2);
    this.size = this.opts.size || 48;
    canvas.width = Math.round(this.size * this.dpr);
    canvas.height = Math.round(this.size * this.dpr);
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
    this.target = { hue: 0, hover: 0.08, rotSpeed: 0.05, sat: 1, lum: 1, timeScale: 1 };
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
    ['iTime', 'iResolution', 'hue', 'hover', 'rot', 'hoverIntensity', 'isLight', 'sat', 'lum', 'timeScale']
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

  /** @param {{k:string,hue?:number,rot?:number,sat?:number,lum?:number,ts?:number}} s */
  setStateCfg(s) {
    this.state = s.k;
    this.target.hue = s.hue || 0;
    this.target.rotSpeed = s.rot || 0;
    this.target.sat = (s.sat === undefined ? 1 : s.sat);
    this.target.lum = (s.lum === undefined ? 1 : s.lum);
    this.target.timeScale = (s.ts === undefined ? 1 : s.ts);
    this.target.hover = 0.08;
    if (this.reducedMotion) {
      // Reduced motion (spec §10.3): snap to the target color and freeze time
      // (no lerp, no breathing, no flow) — draw a single still frame.
      this.params.hue = this.target.hue;
      this.params.sat = this.target.sat;
      this.params.lum = this.target.lum;
      this.params.hover = this.target.hover;
      this.params.timeScale = 0;
      this.target.timeScale = 0;
      this.drawFrame();
      return;
    }
    this.resume();
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
    const dt = this.lastTime ? (time - this.lastTime) : 0.016;
    this.lastTime = time;
    if (!this.reducedMotion) {
      this.params.hue += (this.target.hue - this.params.hue) * 0.06;
      this.params.hover += (this.target.hover - this.params.hover) * 0.1;
      this.params.rot += dt * this.target.rotSpeed;
      this.params.sat += (this.target.sat - this.params.sat) * 0.08;
      this.params.lum += (this.target.lum - this.params.lum) * 0.08;
      this.params.timeScale += (this.target.timeScale - this.params.timeScale) * 0.08;
    }
    gl.viewport(0, 0, this.canvas.width, this.canvas.height);
    gl.useProgram(this.program);
    gl.uniform1f(this.u.iTime, time);
    gl.uniform3f(this.u.iResolution, this.canvas.width, this.canvas.height, 1);
    gl.uniform1f(this.u.hue, this.params.hue);
    gl.uniform1f(this.u.hover, this.params.hover);
    gl.uniform1f(this.u.rot, this.params.rot);
    gl.uniform1f(this.u.hoverIntensity, 1.0);
    gl.uniform1f(this.u.sat, this.params.sat);
    let lum = this.params.lum;
    // Listening: restrained brightness pulse (replaces the ruled-out shake).
    if (!this.reducedMotion && this.state === 'listening') lum *= (1 + 0.06 * Math.sin((time * Math.PI * 2) / 1.6));
    gl.uniform1f(this.u.lum, lum);
    gl.uniform1f(this.u.timeScale, this.params.timeScale);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    if (this.params.timeScale > 0.01) {
      const breath = 1 + Math.sin(time * 0.8) * 0.02;
      this.canvas.style.transform = 'scale(' + breath.toFixed(4) + ')';
    }
  }

  render() {
    this.drawFrame();
    if (this.running) requestAnimationFrame(this.render);
  }
}

/* ========================================================================
   MicOrb controller — binds the DOM (button canvas + css-orb + label) and a
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
    /** @type {HTMLElement|null} */ this.labelEl = document.getElementById('mic-orb-label');
    this.state = 'idle';
    this.renderer = null;
    this.webglOk = false;
    this.micErrorTimer = null;
    this.reduced = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

    if (this.canvas) {
      this.renderer = new OrbRenderer(this.canvas, { size: 48, reducedMotion: this.reduced });
      this.webglOk = !this.renderer.failed;
      // WebGL context lost -> permanent CSS fallback (restored is rare; the CSS
      // orb is visually equivalent for the state colors).
      this.canvas.addEventListener('webglcontextlost', (e) => {
        e.preventDefault();
        this.webglOk = false;
        if (this.renderer) this.renderer.pause();
        this.apply(this.state);
      });
    }
    this.applyTheme();
    this.apply('idle');
    this.bindObservers();
  }

  applyTheme() {
    const isLight = !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches);
    if (this.webglOk && this.renderer) this.renderer.setTheme(isLight);
  }

  /**
   * Apply a state: pick WebGL canvas or CSS orb, set the renderer stateCfg,
   * update the label text + state class (colors come from CSS vars).
   * @param {string} key
   */
  apply(key) {
    const s = STATE_BY_KEY[key] || STATE_BY_KEY.idle;
    this.state = s.k;
    const useCss = s.css || !this.webglOk;
    if (this.cssOrb) {
      this.cssOrb.className = 'css-orb orb ' + s.cls;
      this.cssOrb.style.display = useCss ? 'block' : 'none';
    }
    if (this.canvas) this.canvas.style.display = useCss ? 'none' : 'block';
    if (!useCss && this.renderer) {
      this.renderer.setStateCfg(s);
      this.applyTheme();
    }
    // Wrap carries the state class so the label dot/text colors follow via CSS.
    if (this.btn) {
      const wrap = this.btn.closest('.mic-orb-wrap');
      if (wrap) {
        for (const st of STATES) wrap.classList.remove(st.cls);
        wrap.classList.add(s.cls);
      }
    }
    this.renderLabel();
  }

  renderLabel() {
    if (!this.labelEl) return;
    const s = STATE_BY_KEY[this.state] || STATE_BY_KEY.idle;
    const span = this.labelEl.querySelector('span');
    if (span) span.textContent = t(s.i18n);
    this.labelEl.setAttribute('data-state', this.state);
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
    else this.renderLabel();
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
    if (window.matchMedia) {
      const mq = window.matchMedia('(prefers-color-scheme: light)');
      const onChange = () => this.applyTheme();
      if (mq.addEventListener) mq.addEventListener('change', onChange);
      else if (mq.addListener) mq.addListener(onChange);
    }
    // Locale: re-render the label text when the language switches.
    window.addEventListener('locale-changed', () => this.renderLabel());
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
