// 「溪山流霭」· p5.js 生成艺术（示例作品，合成生成）
// 可复现性：randomSeed(20261004) + noiseSeed(20261004)，noLoop 单帧绘制——
// 同一 p5 版本（1.11.10，本件内联）逐位可复现；random()/noise() 序列完全确定。
function setup() {
  createCanvas(1080, 1440);
  pixelDensity(1);
  randomSeed(20261004);
  noiseSeed(20261004);
  noLoop(); // 单帧；draw() 由 p5 恰好调用一次
}

function draw() {
  background(246, 241, 231); // 宣纸暖白

  // 一、噪声流场长丝：240 条 × 300 步——长丝让场的涡结构显形
  const palette = [
    [47, 58, 92],    // 墨青
    [94, 106, 210],  // 强调 #5e6ad2
    [210, 96, 58],   // 朱
    [217, 164, 65],  // 赭金
    [63, 127, 116],  // 苍绿
    [142, 79, 158],  // 紫
  ];
  noFill();
  for (let i = 0; i < 240; i++) {
    let x = random(-60, width + 60);
    let y = random(-60, height + 60);
    const c = palette[floor(random(palette.length))];
    stroke(c[0], c[1], c[2], 40 + random(55));
    strokeWeight(0.7 + random() * random() * 3.4); // 偏细分布，偶出粗笔
    beginShape();
    for (let s = 0; s < 300; s++) {
      const a = noise(x * 0.0016, y * 0.0016, i * 0.011) * TWO_PI * 1.7;
      x += 3.1 * cos(a);
      y += 3.1 * sin(a);
      vertex(x, y);
    }
    endShape();
  }

  // 二、重墨少数：18 条粗笔扫过，压住画面骨架
  for (let i = 0; i < 18; i++) {
    let x = random(-40, width + 40);
    let y = random(-40, height + 40);
    const c = palette[floor(random(2))] || palette[0]; // 墨青/强调 二选一
    stroke(c[0], c[1], c[2], 110 + random(40));
    strokeWeight(3.2 + random(2.6));
    beginShape();
    for (let s = 0; s < 220; s++) {
      const a = noise(x * 0.0014, y * 0.0014, 500 + i * 0.05) * TWO_PI * 1.7;
      x += 3.4 * cos(a);
      y += 3.4 * sin(a);
      vertex(x, y);
    }
    endShape();
  }

  // 三、五枚「石」：四空一心，位置半径由同一随机序列确定
  noFill();
  stroke(47, 58, 92, 150);
  strokeWeight(2);
  for (let k = 0; k < 4; k++) {
    const cx = random(width * 0.14, width * 0.86);
    const cy = random(height * 0.12, height * 0.88);
    const r = 26 + random(58);
    circle(cx, cy, r * 2);
    circle(cx + r * 0.32, cy - r * 0.21, r * 0.46);
  }
  noStroke();
  fill(210, 96, 58, 200); // 一枚朱色实心石作视觉锚点
  const ax0 = random(width * 0.3, width * 0.7);
  const ay0 = random(height * 0.3, height * 0.7);
  const ar = 20 + random(30);
  circle(ax0, ay0, ar * 2);
  noFill();
  stroke(246, 241, 231);
  strokeWeight(1.6);
  circle(ax0 + ar * 0.26, ay0 - ar * 0.18, ar * 0.42);

  // 三、边框与种子戳记（可复现性声明内嵌于作品）
  noFill();
  stroke(47, 58, 92, 90);
  strokeWeight(1.5);
  rect(24.5, 24.5, width - 49, height - 49);
  noStroke();
  fill(47, 58, 92, 150);
  textAlign(RIGHT, BOTTOM);
  textSize(15);
  text("seed 20261004 · p5 1.11.10 · 溪山流霭（示例生成作品）", width - 38, height - 34);
}
