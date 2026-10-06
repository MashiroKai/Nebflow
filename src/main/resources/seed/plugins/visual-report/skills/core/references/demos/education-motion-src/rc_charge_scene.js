import * as THREE from "three";

// education-motion demo · 形态② RC 充电 3D 引导场景（three 0.186.1，build_three_html.py 内联自包含）
// 交互：拖拽旋转 / 滚轮缩放 / 点画面或按钮分步推进 / ←→ 步进 / URL ?step=N 直达第 N 步
// 每步：重点元件高亮（其余减淡）+ 底部旁白字幕 + 电容液位/电流珠实时联动。
// 教学示例值（显著声明）：R = 1 kΩ，C = 1 µF，U = 1 V ⇒ τ = RC = 1 ms；
// 一切数值由 u_C(t) = U(1 − e^(−t/τ)) 实时复算（core §1.2 数值逐字）。

// ---------- 教学示例值与公式 ----------
const R_OHM = 1e3;
const C_F = 1e-6;
const U_V = 1.0;
const TAU_MS = R_OHM * C_F * 1e3; // = 1 ms

const ucAt = (tMs) => U_V * (1 - Math.exp(-tMs / TAU_MS));
const iMA = (tMs) => ((U_V - ucAt(tMs)) / R_OHM) * 1e3;
const pctAt = (tMs) => (ucAt(tMs) / U_V) * 100;

// ---------- 分步脚本（旁白字幕 = 引导式动效文案） ----------
const STEPS = [
  {
    n: "①", t: 0, title: "t = 0 · 开关闭合", focus: ["switch", "battery"],
    narr: `开关闭合，输入从 0 V 跳到 1 V。电容还空着（u_C = ${ucAt(0).toFixed(3)} V），此刻电流最大 i = U/R = ${iMA(0).toFixed(3)} mA——充电最快的一刻。`,
  },
  {
    n: "②", t: 1, title: "t = 1τ = 1 ms · 充电 1τ", focus: ["capacitor"],
    narr: `电容充到 u_C = ${ucAt(1).toFixed(3)} V（${pctAt(1).toFixed(1)}%），电流降到 i = ${iMA(1).toFixed(3)} mA。每过一个 τ，就走完「剩余缺口」的 63.2%。`,
  },
  {
    n: "③", t: 2, title: "t = 2τ = 2 ms · 充电 2τ", focus: ["capacitor"],
    narr: `u_C = ${ucAt(2).toFixed(3)} V（${pctAt(2).toFixed(1)}%），i = ${iMA(2).toFixed(3)} mA。缺口按指数收缩，充电越来越慢。`,
  },
  {
    n: "④", t: 3, title: "t = 3τ = 3 ms · 充电 3τ", focus: ["resistor"],
    narr: `u_C = ${ucAt(3).toFixed(3)} V（${pctAt(3).toFixed(1)}%），电流只剩 i = ${iMA(3).toFixed(3)} mA。限流的 R 决定了每一步的快慢；工程上从 3τ 起视为基本充满。`,
  },
  {
    n: "⑤", t: 4, title: "t = 4τ = 4 ms · 充电 4τ", focus: ["capacitor"],
    narr: `u_C = ${ucAt(4).toFixed(3)} V（${pctAt(4).toFixed(1)}%），i = ${iMA(4).toFixed(3)} mA。`,
  },
  {
    n: "⑥", t: 5, title: "t = 5τ = 5 ms · 充满", focus: ["capacitor", "battery"],
    narr: `u_C = ${ucAt(5).toFixed(3)} V（${pctAt(5).toFixed(1)}%）≈ u_in = 1 V，剩余缺口不足 1%，充电结束。`,
  },
];

// ---------- 主题跟随 ----------
const mq = matchMedia("(prefers-color-scheme: dark)");
let darkMode = mq.matches;
const tText = () => (darkMode ? "#e8eaed" : "#1b1e26");
const tCard = () => (darkMode ? "rgba(27,30,38,0.82)" : "rgba(255,255,255,0.82)");
const tGrid = () => (darkMode ? 0x33384a : 0xd9dce3);
const tGridCenter = () => (darkMode ? 0x4a5164 : 0xb6bcc9);

// ---------- 渲染器 / 场景 / 相机 ----------
const stage = document.getElementById("stage");
const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
stage.appendChild(renderer.domElement);

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 100);
const TARGET = new THREE.Vector3(0, 0.35, 0);
let az = 0.72, pol = 1.02, radius = 7.2;
function applyCamera() {
  pol = Math.max(0.18, Math.min(1.38, pol));
  radius = Math.max(3.2, Math.min(11, radius));
  camera.position.set(
    TARGET.x + radius * Math.sin(pol) * Math.sin(az),
    TARGET.y + radius * Math.cos(pol),
    TARGET.z + radius * Math.sin(pol) * Math.cos(az),
  );
  camera.lookAt(TARGET);
}
applyCamera();

scene.add(new THREE.AmbientLight(0xffffff, 0.6));
const key = new THREE.DirectionalLight(0xffffff, 2.0);
key.position.set(4, 6, 3);
scene.add(key);
const fill = new THREE.DirectionalLight(0xffffff, 0.7);
fill.position.set(-4, 3, -4);
scene.add(fill);

// ---------- 网格地面（主题跟随重建） ----------
let grid = null;
function rebuildGrid() {
  if (grid) { scene.remove(grid); grid.geometry.dispose(); grid.material.dispose(); }
  grid = new THREE.GridHelper(12, 24, tGridCenter(), tGrid());
  grid.material.transparent = true;
  grid.material.opacity = 0.55;
  scene.add(grid);
}
rebuildGrid();

// ---------- 电路回路（x–z 平面矩形，y = 0.25） ----------
const WIRE_Y = 0.25, HX = 2.6, HZ = 1.4;
const LOOP_LEN = 2 * (2 * HX + 2 * HZ); // 周长
// 参数 u∈[0,1) 沿回路，起点 (HX, y, -HZ)（右上角）：
// 顶边 (HX,-HZ)→(-HX,-HZ)：5.2；左边 →(-HX,HZ)：2.8；底边 →(HX,HZ)：5.2；右边 →回到起点：2.8
function loopPoint(u, out) {
  let s = u * LOOP_LEN;
  if (s < 2 * HX) { out.set(HX - s, WIRE_Y, -HZ); }
  else if ((s -= 2 * HX) < 2 * HZ) { out.set(-HX, WIRE_Y, -HZ + s); }
  else if ((s -= 2 * HZ) < 2 * HX) { out.set(-HX + s, WIRE_Y, HZ); }
  else { s -= 2 * HX; out.set(HX, WIRE_Y, HZ - s); }
  return out;
}

const compMats = { battery: [], switch: [], resistor: [], capacitor: [], wire: [] };
function reg(key, mesh) { compMats[key].push(mesh.material); return mesh; }

const wireMat = new THREE.MeshStandardMaterial({ color: 0x8a8f98, roughness: 0.45, metalness: 0.35, transparent: true });

// 四段导线（整圈），元件叠放在线体之上
{
  const segs = [
    [[HX, -HZ], [-HX, -HZ]], [[-HX, -HZ], [-HX, HZ]], [[-HX, HZ], [HX, HZ]], [[HX, HZ], [HX, -HZ]],
  ];
  const geo = new THREE.CylinderGeometry(0.045, 0.045, 1, 12);
  for (const [[x1, z1], [x2, z2]] of segs) {
    const len = Math.hypot(x2 - x1, z2 - z1);
    const m = new THREE.Mesh(geo, wireMat);
    m.scale.set(1, len, 1);
    m.position.set((x1 + x2) / 2, WIRE_Y, (z1 + z2) / 2);
    m.rotation.z = Math.PI / 2;
    m.rotation.y = -Math.atan2(z2 - z1, x2 - x1);
    scene.add(m);
  }
  for (const [x, z] of [[HX, -HZ], [-HX, -HZ], [-HX, HZ], [HX, HZ]]) {
    const j = new THREE.Mesh(new THREE.SphereGeometry(0.075, 16, 12), wireMat);
    j.position.set(x, WIRE_Y, z);
    scene.add(j);
  }
}

// ---------- 文字标签（CanvasTexture，主题跟随重绘） ----------
const labels = [];
function makeLabel(text, opt = {}) {
  const fs = opt.fs || 40, pad = 16;
  const cv = document.createElement("canvas");
  const draw = () => {
    const ctx = cv.getContext("2d");
    const font = `500 ${fs}px 'Microsoft YaHei', system-ui, sans-serif`;
    ctx.font = font;
    const w = Math.ceil(ctx.measureText(text).width) + pad * 2;
    const h = fs + pad * 2;
    cv.width = w; cv.height = h;
    const c2 = cv.getContext("2d");
    c2.font = font;
    const r = 14;
    c2.fillStyle = tCard();
    c2.beginPath();
    c2.roundRect(1, 1, w - 2, h - 2, r);
    c2.fill();
    c2.strokeStyle = darkMode ? "#2a2e38" : "#e8eaed";
    c2.lineWidth = 2;
    c2.stroke();
    c2.fillStyle = tText();
    c2.textBaseline = "middle";
    c2.fillText(text, pad, h / 2 + 2);
    return { w, h };
  };
  const { w, h } = draw();
  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, transparent: true, depthWrite: false }));
  const k = opt.k || 0.0031;
  sp.scale.set(w * k, h * k, 1);
  labels.push({ cv, tex, draw, k });
  return sp;
}

// ---------- 电池（左边中点） ----------
const battery = new THREE.Group();
{
  const body = reg("battery", new THREE.Mesh(
    new THREE.CylinderGeometry(0.19, 0.19, 0.72, 24),
    new THREE.MeshStandardMaterial({ color: 0x8a8f98, roughness: 0.3, metalness: 0.7, transparent: true }),
  ));
  body.position.y = 0.36;
  battery.add(body);
  const cap = reg("battery", new THREE.Mesh(
    new THREE.CylinderGeometry(0.07, 0.07, 0.14, 16),
    new THREE.MeshStandardMaterial({ color: 0xc7a15a, roughness: 0.35, metalness: 0.8, transparent: true }),
  ));
  cap.position.y = 0.79;
  battery.add(cap);
  const ring = reg("battery", new THREE.Mesh(
    new THREE.CylinderGeometry(0.196, 0.196, 0.1, 24),
    new THREE.MeshStandardMaterial({ color: 0x5e6ad2, roughness: 0.4, metalness: 0.3, transparent: true }),
  ));
  ring.position.y = 0.55;
  battery.add(ring);
  const lab = makeLabel("电源 U = 1 V");
  lab.position.set(0, 1.35, 0);
  battery.add(lab);
  battery.position.set(-HX, WIRE_Y, 0);
  scene.add(battery);
}

// ---------- 开关（底边中点，t = 0 闭合） ----------
const switchG = new THREE.Group();
let lever = null;
{
  const contactGeo = new THREE.SphereGeometry(0.07, 16, 12);
  const contactMat = new THREE.MeshStandardMaterial({ color: 0xc7a15a, roughness: 0.3, metalness: 0.85, transparent: true });
  const c1 = reg("switch", new THREE.Mesh(contactGeo, contactMat));
  c1.position.set(-0.26, 0, 0);
  const c2 = reg("switch", new THREE.Mesh(contactGeo, contactMat.clone()));
  c2.position.set(0.26, 0, 0);
  switchG.add(c1, c2);
  lever = new THREE.Group();
  const arm = reg("switch", new THREE.Mesh(
    new THREE.CylinderGeometry(0.035, 0.035, 0.52, 12),
    new THREE.MeshStandardMaterial({ color: 0xc7a15a, roughness: 0.3, metalness: 0.85, transparent: true }),
  ));
  arm.rotation.z = Math.PI / 2;
  arm.position.x = 0.26;
  lever.add(arm);
  const knob = reg("switch", new THREE.Mesh(
    new THREE.SphereGeometry(0.06, 12, 10),
    new THREE.MeshStandardMaterial({ color: 0x5e6ad2, roughness: 0.4, transparent: true }),
  ));
  knob.position.set(0.5, 0, 0);
  lever.add(knob);
  lever.position.set(-0.26, 0.02, 0);
  switchG.add(lever);
  const lab = makeLabel("开关 t = 0 闭合");
  lab.position.set(0, 0.95, 0);
  switchG.add(lab);
  switchG.position.set(0, WIRE_Y + 0.02, HZ);
  scene.add(switchG);
}

// ---------- 电阻（顶边右段） ----------
const resistor = new THREE.Group();
{
  const body = reg("resistor", new THREE.Mesh(
    new THREE.CylinderGeometry(0.15, 0.15, 1.15, 20),
    new THREE.MeshStandardMaterial({ color: 0xd8c49a, roughness: 0.55, metalness: 0.1, transparent: true }),
  ));
  body.rotation.z = Math.PI / 2;
  resistor.add(body);
  const bandGeo = new THREE.CylinderGeometry(0.156, 0.156, 0.07, 20);
  const bandMat = new THREE.MeshStandardMaterial({ color: 0x4a4e58, roughness: 0.5, transparent: true });
  for (const dx of [-0.3, 0, 0.3]) {
    const b = reg("resistor", new THREE.Mesh(bandGeo, bandMat));
    b.rotation.z = Math.PI / 2;
    b.position.x = dx;
    resistor.add(b);
  }
  const lab = makeLabel("R = 1 kΩ");
  lab.position.set(0, 0.85, 0);
  resistor.add(lab);
  resistor.position.set(1.35, WIRE_Y, -HZ);
  scene.add(resistor);
}

// ---------- 电容（右边中点）：双平行板 + 液位式电荷填充 ----------
const capacitor = new THREE.Group();
let fillMesh = null, pctLabel = null;
{
  const plateGeo = new THREE.BoxGeometry(0.62, 0.78, 0.055);
  const plateMat = new THREE.MeshStandardMaterial({ color: 0xa0a8e8, roughness: 0.35, metalness: 0.6, transparent: true });
  const p1 = reg("capacitor", new THREE.Mesh(plateGeo, plateMat));
  p1.position.z = 0.22;
  const p2 = reg("capacitor", new THREE.Mesh(plateGeo, plateMat.clone()));
  p2.position.z = -0.22;
  capacitor.add(p1, p2);
  // 电荷填充（液位）：几何原点移到底部，scale.y = 电荷比例
  const fillGeo = new THREE.BoxGeometry(0.5, 0.62, 0.34);
  fillGeo.translate(0, 0.31, 0);
  fillMesh = new THREE.Mesh(fillGeo, new THREE.MeshStandardMaterial({
    color: 0x5e6ad2, emissive: 0x5e6ad2, emissiveIntensity: 0.35, roughness: 0.3, transparent: true, opacity: 0.92,
  }));
  fillMesh.position.y = 0.08;
  capacitor.add(fillMesh);
  pctLabel = makeLabel("0.0%", { fs: 46 });
  pctLabel.position.set(-1.15, 0.62, 0); // 板左侧，避开 R 标签的投影区
  capacitor.add(pctLabel);
  const lab = makeLabel("C = 1 µF");
  lab.position.set(0.95, 0.62, 0);
  capacitor.add(lab);
  capacitor.position.set(HX, WIRE_Y - 0.02, 0);
  scene.add(capacitor);
}

// ---------- 电流珠（速度 ∝ i，物理联动） ----------
const DOTS = 16;
const dotGeo = new THREE.SphereGeometry(0.058, 12, 10);
const dotMat = new THREE.MeshStandardMaterial({ color: 0x5e6ad2, emissive: 0x5e6ad2, emissiveIntensity: 0.55, roughness: 0.3 });
const dots = [];
for (let i = 0; i < DOTS; i++) {
  const d = new THREE.Mesh(dotGeo, dotMat);
  scene.add(d);
  dots.push({ mesh: d, u: i / DOTS });
}
const _p = new THREE.Vector3();
let loopOffset = 0; // 0..1 回路累计参数

// ---------- UI（字幕 / 步进点 / 按钮 / 提示） ----------
const uiStyle = document.createElement("style");
uiStyle.textContent = `
.edu-sub{position:fixed;left:16px;right:16px;bottom:14px;background:var(--color-card);
  border:1px solid var(--color-border);border-radius:12px;padding:12px 16px;pointer-events:none;
  box-shadow:0 4px 18px rgba(0,0,0,.18)}
.edu-sub-head{font-size:15px;font-weight:600;color:var(--color-text)}
.edu-sub-head .edu-badge{color:var(--color-accent);margin-right:8px}
.edu-sub-narr{font-size:13px;color:var(--color-text);opacity:.92;margin-top:5px;line-height:1.55}
.edu-sub-vals{font-size:12px;color:var(--color-muted);margin-top:6px;font-variant-numeric:tabular-nums}
.edu-dots{position:fixed;top:12px;right:14px;display:flex;gap:6px}
.edu-dot{width:30px;height:30px;border-radius:50%;border:1px solid var(--color-border);
  background:var(--color-card);color:var(--color-muted);font-size:14px;cursor:pointer;padding:0}
.edu-dot.on{background:var(--color-accent);border-color:var(--color-accent);color:#fff}
.edu-btns{position:fixed;right:14px;bottom:110px;display:flex;flex-direction:column;gap:8px}
.edu-btn{border:1px solid var(--color-border);background:var(--color-card);color:var(--color-text);
  border-radius:9px;padding:7px 14px;font-size:13px;cursor:pointer}
.edu-btn:hover{border-color:var(--color-accent)}
.edu-hint{position:fixed;left:16px;top:40px;font-size:12px;color:var(--color-muted);pointer-events:none}
`;
document.head.appendChild(uiStyle);

const subHead = document.createElement("div");
subHead.className = "edu-sub-head";
const subNarr = document.createElement("div");
subNarr.className = "edu-sub-narr";
const subVals = document.createElement("div");
subVals.className = "edu-sub-vals";
const sub = document.createElement("div");
sub.className = "edu-sub";
sub.append(subHead, subNarr, subVals);
document.body.appendChild(sub);

const dotsBox = document.createElement("div");
dotsBox.className = "edu-dots";
const dotBtns = STEPS.map((s, i) => {
  const b = document.createElement("button");
  b.className = "edu-dot";
  b.textContent = s.n;
  b.title = s.title;
  b.addEventListener("click", () => setStep(i, true));
  dotsBox.appendChild(b);
  return b;
});
document.body.appendChild(dotsBox);

const btns = document.createElement("div");
btns.className = "edu-btns";
const bPrev = document.createElement("button");
bPrev.className = "edu-btn"; bPrev.textContent = "← 上一步";
bPrev.addEventListener("click", () => setStep(stepIdx - 1, true));
const bNext = document.createElement("button");
bNext.className = "edu-btn"; bNext.textContent = "下一步 ▶";
bNext.addEventListener("click", () => setStep(stepIdx + 1, true));
const bReset = document.createElement("button");
bReset.className = "edu-btn"; bReset.textContent = "↺ 重置";
bReset.addEventListener("click", () => { setStep(0, true); replayClosure = true; });
btns.append(bPrev, bNext, bReset);
document.body.appendChild(btns);

const hint = document.createElement("div");
hint.className = "edu-hint";
hint.textContent = "拖拽旋转 · 滚轮缩放 · 点击画面或按钮分步推进（← → 键可用）";
document.body.appendChild(hint);

// ---------- 步进状态机 ----------
let stepIdx = 0;
let level = 0;          // 电容电荷比例 0..1（= u_C / U）
let levelFrom = 0, levelTo = 0, transT = 1; // 过渡动画
let leverAngle = 0.55;  // 开关初始断开
let leverTarget = 0.55;
let replayClosure = false;

function setStep(i, fromUser) {
  stepIdx = Math.max(0, Math.min(STEPS.length - 1, i));
  const s = STEPS[stepIdx];
  levelFrom = level;
  levelTo = ucAt(s.t) / U_V;
  transT = 0;
  leverTarget = 0; // 全部步骤里开关都已闭合（① 即闭合瞬间）
  bPrev.disabled = stepIdx === 0;
  bNext.disabled = stepIdx === STEPS.length - 1;
  dotBtns.forEach((b, j) => b.classList.toggle("on", j === stepIdx));
  subHead.innerHTML = `<span class="edu-badge">${s.n}</span>${s.title}`;
  subNarr.textContent = s.narr;
  subVals.textContent =
    `u_C(${s.t} ms) = ${ucAt(s.t).toFixed(3)} V（${pctAt(s.t).toFixed(1)}%） · i = ${iMA(s.t).toFixed(3)} mA · τ = RC = ${TAU_MS.toFixed(0)} ms（R = 1 kΩ，C = 1 µF，U = 1 V，教学示例值）`;
  setFocus(s.focus);
  if (fromUser && stepIdx === 0 && i === 0) replayClosure = true;
}
function setFocus(names) {
  for (const key of Object.keys(compMats)) {
    const on = names.includes(key);
    for (const m of compMats[key]) {
      m.userData.targetOpacity = on ? 1 : 0.38;
      m.userData.focused = on;
    }
  }
  wireMat.userData.targetOpacity = 0.85;
  wireMat.userData.focused = false;
}

// URL ?step=N 直达
{
  const q = parseInt(new URLSearchParams(location.search).get("step") || "1", 10);
  const i0 = Number.isFinite(q) ? Math.max(1, Math.min(6, q)) - 1 : 0;
  level = 0;
  setStep(i0, false);
  if (i0 > 0) { level = levelTo; transT = 1; leverAngle = 0; } // 直达中间步：开关保持闭合、无过渡
  syncFill(); // 初始标签与液位对齐
}

function syncFill() {
  // 电荷液位（几何原点在底部，scale.y = 电荷比例）+ 百分比标签实时跟随动画
  fillMesh.scale.y = Math.max(level, 0.0001);
  const shown = level * U_V;
  const text = `${(level * 100).toFixed(1)}% · ${shown.toFixed(3)} V`;
  const cv = pctLabel.material.map.image;
  const ctx = cv.getContext("2d");
  const fs = 46, pad = 16;
  const font = `500 ${fs}px 'Microsoft YaHei', system-ui, sans-serif`;
  ctx.font = font;
  cv.width = Math.ceil(ctx.measureText(text).width) + pad * 2;
  cv.height = fs + pad * 2;
  const c2 = cv.getContext("2d");
  c2.font = font;
  c2.fillStyle = tCard();
  c2.beginPath(); c2.roundRect(1, 1, cv.width - 2, cv.height - 2, 14); c2.fill();
  c2.strokeStyle = darkMode ? "#2a2e38" : "#e8eaed"; c2.lineWidth = 2; c2.stroke();
  c2.fillStyle = tText(); c2.textBaseline = "middle";
  c2.fillText(text, pad, cv.height / 2 + 2);
  pctLabel.material.map.needsUpdate = true;
  const k = 0.0031;
  pctLabel.scale.set(cv.width * k, cv.height * k, 1);
}

// 主题切换：重绘所有标签 + 网格
mq.addEventListener("change", (e) => {
  darkMode = e.matches;
  rebuildGrid();
  for (const l of labels) {
    l.draw();
    l.tex.needsUpdate = true;
  }
  syncFill();
});

// ---------- 交互：拖拽旋转 / 滚轮缩放 / 点击步进 ----------
let dragging = false, moved = 0, px = 0, py = 0, vAz = 0, vPol = 0, downT = 0;
renderer.domElement.addEventListener("pointerdown", (e) => {
  dragging = true; moved = 0; px = e.clientX; py = e.clientY; downT = performance.now();
});
addEventListener("pointerup", (e) => {
  if (dragging && moved < 6 && performance.now() - downT < 400) setStep(stepIdx + 1, true);
  dragging = false;
});
addEventListener("pointermove", (e) => {
  if (!dragging) return;
  const dx = e.clientX - px, dy = e.clientY - py;
  moved += Math.abs(dx) + Math.abs(dy);
  vAz = -dx * 0.005; vPol = -dy * 0.005;
  az += vAz; pol += vPol;
  px = e.clientX; py = e.clientY;
  applyCamera();
});
addEventListener("wheel", (e) => {
  radius *= e.deltaY > 0 ? 1.08 : 0.92;
  applyCamera();
}, { passive: true });
addEventListener("keydown", (e) => {
  if (e.key === "ArrowRight" || e.key === " ") setStep(stepIdx + 1, true);
  else if (e.key === "ArrowLeft") setStep(stepIdx - 1, true);
});

function resize() {
  const w = innerWidth, h = innerHeight;
  renderer.setSize(w, h);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}
addEventListener("resize", resize);
resize();

// ---------- 主循环 ----------
const easeInOut = (x) => (x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2);
const clock = new THREE.Clock();

renderer.setAnimationLoop(() => {
  const dt = Math.min(clock.getDelta(), 0.05);
  const now = clock.elapsedTime;

  // 电荷液位过渡（0.9s easeInOut）+ 电容百分比标签
  if (transT < 1) {
    transT = Math.min(1, transT + dt / 0.9);
    level = levelFrom + (levelTo - levelFrom) * easeInOut(transT);
    syncFill();
  }

  // 开关杆动画
  leverAngle += (leverTarget - leverAngle) * Math.min(1, dt * 7);
  if (replayClosure && stepIdx === 0) { leverAngle = 0.55; replayClosure = false; }
  lever.rotation.z = leverAngle;

  // 电流珠：速度 ∝ 归一化电流 (1 − level)
  const iNorm = 1 - level;
  loopOffset = (loopOffset + dt * 0.055 * Math.max(iNorm, 0.004)) % 1;
  for (const d of dots) {
    loopPoint((d.u + loopOffset) % 1, _p);
    d.mesh.position.copy(_p);
    d.mesh.visible = leverAngle < 0.1; // 开关闭合才有电流
  }

  // 高亮呼吸 + 减淡过渡（导线随元件一起淡）
  const fadeK = Math.min(1, dt * 8);
  wireMat.opacity += (0.85 - wireMat.opacity) * fadeK;
  for (const key of Object.keys(compMats)) {
    for (const m of compMats[key]) {
      const tgt = m.userData.targetOpacity === undefined ? 1 : m.userData.targetOpacity;
      m.opacity += (tgt - m.opacity) * fadeK;
      if (m.emissive) {
        const base = m.userData.focused ? 0.22 + 0.13 * Math.sin(now * 4.2) : 0.0;
        m.emissiveIntensity += (base - m.emissiveIntensity) * fadeK;
        if (!m.emissive.getHex()) m.emissive.setHex(0x5e6ad2);
      }
    }
  }

  renderer.render(scene, camera);
});
