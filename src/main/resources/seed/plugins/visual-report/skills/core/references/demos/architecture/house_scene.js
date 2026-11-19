import * as THREE from "three";

// 两层坡顶小宅「拾光居」· 体块模型（示例方案，合成数据）
// 1 单位 = 1 m；与 demos/architecture 平面图/轴测图同一份米制数据：
// 轴线 9.6×8.4，外墙 240/内墙 120，层高 3.0×2，檐口 +6.0，屋脊 +8.4，挑檐 0.4
// 交互：拖拽轨道旋转 + 滚轮推拉缩放（经 motion-toolkit three-template 构建）
const stage = document.getElementById("stage");

const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
stage.appendChild(renderer.domElement);

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(40, 1, 0.1, 500);

scene.add(new THREE.AmbientLight(0xffffff, 0.65));
const key = new THREE.DirectionalLight(0xffffff, 2.0);
key.position.set(14, 20, 10);
scene.add(key);
const fill = new THREE.DirectionalLight(0xdde4ff, 0.7);
fill.position.set(-12, 9, -16);
scene.add(fill);

const house = new THREE.Group();
scene.add(house);

// 材质（core §3 调色板：强调 #5e6ad2，无纯黑纯白数据色）
const wallMat = new THREE.MeshStandardMaterial({ color: 0xe3e0d5, roughness: 0.92 });
const gableMat = new THREE.MeshStandardMaterial({ color: 0xd6d3c8, roughness: 0.92 });
const slabMat = new THREE.MeshStandardMaterial({ color: 0xb9b6ad, roughness: 0.9 });
const roofS = new THREE.MeshStandardMaterial({ color: 0x5e6ad2, roughness: 0.55 });
const roofN = new THREE.MeshStandardMaterial({ color: 0x454e8f, roughness: 0.55 });
const glassMat = new THREE.MeshStandardMaterial({ color: 0xa8bcd8, roughness: 0.2, metalness: 0.15 });
const doorMat = new THREE.MeshStandardMaterial({ color: 0x8a4a32, roughness: 0.7 });
const groundMat = new THREE.MeshStandardMaterial({ color: 0xc6c2b2, roughness: 1.0 });
const chimMat = new THREE.MeshStandardMaterial({ color: 0x9a6a55, roughness: 0.85 });

function box(w, h, d, x, y, z, mat) {
  const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat);
  m.position.set(x, y, z);
  house.add(m);
  return m;
}

// 场地垫层（每侧出 1.0 m）
box(11.6, 0.18, 10.4, 4.8, -0.09, 4.2, groundMat);

// 一层墙体（y 0→3.0；平面 y 轴映射到 three z 轴）
box(9.6, 3.0, 0.24, 4.8, 1.5, 0.12, wallMat);        // 南（前）
box(9.6, 3.0, 0.24, 4.8, 1.5, 8.28, wallMat);        // 北（后）
box(0.24, 3.0, 7.92, 0.12, 1.5, 4.2, wallMat);       // 西
box(0.24, 3.0, 7.92, 9.48, 1.5, 4.2, wallMat);       // 东
box(0.12, 3.0, 4.2, 5.4, 1.5, 6.3, wallMat);         // 内 x=5.4（z 4.2→8.4）
box(9.6, 3.0, 0.12, 4.8, 1.5, 4.2, wallMat);         // 内 y=4.2
box(0.12, 3.0, 4.2, 3.3, 1.5, 2.1, wallMat);         // 内 x=3.3
box(0.12, 3.0, 4.2, 6.3, 1.5, 2.1, wallMat);         // 内 x=6.3
// 楼板（9.6×8.4×0.18，顶面 +3.09）
box(9.6, 0.18, 8.4, 4.8, 3.09, 4.2, slabMat);
// 二层墙体（y 3.0→6.0，保留 x=5.4 一道内墙）
box(9.6, 3.0, 0.24, 4.8, 4.5, 0.12, wallMat);
box(9.6, 3.0, 0.24, 4.8, 4.5, 8.28, wallMat);
box(0.24, 3.0, 7.92, 0.12, 4.5, 4.2, wallMat);
box(0.24, 3.0, 7.92, 9.48, 4.5, 4.2, wallMat);
box(0.12, 3.0, 4.2, 5.4, 4.5, 6.3, wallMat);

// 坡屋面：坡度 = 2.4 / 4.2（檐口 6.0 → 屋脊 8.4），挑檐 0.4
const RUN = 4.6, RISE = 2.629;                        // 水平 4.6 = 4.2 + 挑檐 0.4
const LEN = Math.hypot(RUN, RISE);                    // 坡长 5.296
const ANG = Math.atan2(RISE, RUN);                    // 29.74°
const south = box(10.4, 0.12, LEN, 4.8, 7.086, 1.9, roofS);
south.rotation.x = -ANG;
const north = box(10.4, 0.12, LEN, 4.8, 7.086, 6.5, roofN);
north.rotation.x = ANG;
box(10.4, 0.12, 0.34, 4.8, 8.44, 4.2, roofN);         // 屋脊盖板

// 山墙（x=0 与 x=9.6，三角 (z,y)：(0,6)(8.4,6)(4.2,8.4)）
const tri = new THREE.Shape();
tri.moveTo(0, 6); tri.lineTo(8.4, 6); tri.lineTo(4.2, 8.4); tri.closePath();
const gableGeo = new THREE.ExtrudeGeometry(tri, { depth: 0.24, bevelEnabled: false });
const gableW = new THREE.Mesh(gableGeo, gableMat);
gableW.rotation.y = Math.PI / 2;
gableW.position.set(-0.12, 0, 8.4);
house.add(gableW);
const gableE = gableW.clone();
gableE.position.set(9.48, 0, 8.4);
house.add(gableE);

// 烟囱（穿南坡于平面 (7.2, 2.2)，坡面该处高 7.26）
box(0.6, 1.7, 0.6, 7.2, 7.5, 2.2, chimMat);

// 门窗（贴面体块，进墙 0.3 免 z-fighting）
// 南立面：入户门 (4.9→5.7)、卧室1 窗 (1.2→2.4)、卫生间 窗 (7.5→8.3)、二层 主卧/卧室2 窗
box(0.8, 2.1, 0.3, 5.3, 1.05, 0.0, doorMat);
box(1.2, 1.2, 0.3, 1.8, 1.5, 0.0, glassMat);
box(0.8, 0.9, 0.3, 7.9, 1.65, 0.0, glassMat);
box(2.4, 1.6, 0.3, 2.7, 4.4, 0.0, glassMat);
box(1.8, 1.6, 0.3, 7.5, 4.4, 0.0, glassMat);
// 北立面：客厅/餐厨（一层）与 主卧/卧室2（二层）窗
box(2.4, 1.2, 0.3, 2.7, 1.5, 8.4, glassMat);
box(1.8, 1.2, 0.3, 7.5, 1.5, 8.4, glassMat);
box(2.4, 1.6, 0.3, 2.7, 4.5, 8.4, glassMat);
box(1.8, 1.6, 0.3, 7.5, 4.5, 8.4, glassMat);
// 东西立面窗（z 为平面 y：卧室1 1.4→2.6、客厅 5.6→7.2、卫生间 1.6→2.6、餐厨 5.8→7.4）
box(0.3, 1.2, 1.2, 0.0, 1.5, 2.0, glassMat);
box(0.3, 1.2, 1.6, 0.0, 1.5, 6.4, glassMat);
box(0.3, 1.6, 1.2, 0.0, 4.5, 6.4, glassMat);
box(0.3, 1.0, 1.0, 9.6, 1.65, 2.1, glassMat);
box(0.3, 1.2, 1.6, 9.6, 1.5, 6.6, glassMat);
box(0.3, 1.6, 1.6, 9.6, 4.5, 6.6, glassMat);

// 轨道交互：拖拽旋转 + 滚轮推拉（绕建筑几何中心 4.8, 3.2, 4.2）
const target = new THREE.Vector3(4.8, 3.2, 4.2);
let radius = 22, phi = 0.88, theta = Math.PI * 1.86;  // 初视角：东南上方 ~40°（山墙五边形正视）
function applyCam() {
  camera.position.set(
    target.x + radius * Math.sin(phi) * Math.cos(theta),
    target.y + radius * Math.cos(phi),
    target.z + radius * Math.sin(phi) * Math.sin(theta),
  );
  camera.lookAt(target);
}
let dragging = false, px = 0, py = 0;
addEventListener("pointerdown", (e) => { dragging = true; px = e.clientX; py = e.clientY; });
addEventListener("pointerup", () => { dragging = false; });
addEventListener("pointermove", (e) => {
  if (!dragging) return;
  theta -= (e.clientX - px) * 0.005;
  phi -= (e.clientY - py) * 0.005;
  phi = Math.min(1.45, Math.max(0.15, phi));
  px = e.clientX; py = e.clientY;
  applyCam();
});
addEventListener("wheel", (e) => {
  e.preventDefault();
  radius *= e.deltaY > 0 ? 1.08 : 0.925;
  radius = Math.min(60, Math.max(10, radius));
  applyCam();
}, { passive: false });
applyCam();

function resize() {
  const w = innerWidth, h = innerHeight;
  renderer.setSize(w, h);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}
addEventListener("resize", resize);
resize();

renderer.setAnimationLoop(() => renderer.render(scene, camera));
