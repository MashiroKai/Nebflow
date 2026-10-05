import * as THREE from "three";

// 最小可交互场景：扭矩结 + 指针拖拽旋转（带惯性）。场景在此基础上替换几何/材质/动画。
// 配色取 core/SKILL.md §3 变量表：强调 #5e6ad2（dark 模式 #a0a8e8 走 CSS，不在场景内切换）。
const stage = document.getElementById("stage");

const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
stage.appendChild(renderer.domElement);

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 100);
camera.position.set(0, 0, 6);

scene.add(new THREE.AmbientLight(0xffffff, 0.55));
const key = new THREE.DirectionalLight(0xffffff, 2.2);
key.position.set(3, 4, 5);
scene.add(key);

const knot = new THREE.Mesh(
  new THREE.TorusKnotGeometry(1.4, 0.42, 220, 32),
  new THREE.MeshStandardMaterial({ color: 0x5e6ad2, roughness: 0.35, metalness: 0.15 }),
);
scene.add(knot);

// 指针拖拽自实现（不引 OrbitControls，保持 three 单依赖）
let dragging = false, px = 0, py = 0, vx = 0.004, vy = 0, rx = 0.4, ry = 0;
addEventListener("pointerdown", (e) => { dragging = true; px = e.clientX; py = e.clientY; });
addEventListener("pointerup", () => { dragging = false; });
addEventListener("pointermove", (e) => {
  if (!dragging) return;
  vy = (e.clientX - px) * 0.005;
  vx = (e.clientY - py) * 0.005;
  px = e.clientX; py = e.clientY;
});

function resize() {
  const w = innerWidth, h = innerHeight;
  renderer.setSize(w, h);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}
addEventListener("resize", resize);
resize();

renderer.setAnimationLoop(() => {
  if (!dragging) { vx *= 0.95; vy *= 0.95; }
  rx += vx; ry += vy;
  knot.rotation.set(rx, ry, 0);
  renderer.render(scene, camera);
});
