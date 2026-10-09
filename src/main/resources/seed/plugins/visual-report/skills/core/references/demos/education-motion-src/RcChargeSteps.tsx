import type { FC } from "react";
import {
  AbsoluteFill,
  Img,
  interpolate,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";

// education-motion demo · 形态③ RC 充电分步讲解视频（Remotion 4.0.532 + 本机 Chrome 渲染 → MP4）
// 分步推进（①–⑥）+ 底部旁白字幕；曲线每帧由 u_C(t) = U(1 − e^(−t/τ)) 实时采样（数据驱动，无手画坐标）。
// 教学示例值（显著声明）：R = 1 kΩ，C = 1 µF，U = 1 V ⇒ τ = RC = 1 ms。
// 原理图为 schemdraw 0.23 真实渲染（public/rc_schematic_dark.png）。

const R_OHM = 1e3;
const C_F = 1e-6;
const U_V = 1.0;
const TAU_MS = R_OHM * C_F * 1e3; // = 1 ms

const ucAt = (tMs: number) => U_V * (1 - Math.exp(-tMs / TAU_MS));
const iMA = (tMs: number) => ((U_V - ucAt(tMs)) / R_OHM) * 1e3;
const pctAt = (tMs: number) => (ucAt(tMs) / U_V) * 100;

type Step = {
  n: string;
  t: number; // ms
  title: string;
  narr: string;
};

const STEPS: Step[] = [
  {
    n: "①", t: 0, title: "t = 0 · 开关闭合",
    narr: `开关闭合，输入从 0 V 跳到 1 V。电容还空着（u_C = ${ucAt(0).toFixed(3)} V），此刻电流最大 i = U/R = ${iMA(0).toFixed(3)} mA——充电最快的一刻。`,
  },
  {
    n: "②", t: 1, title: "t = 1τ = 1 ms · 充电 1τ",
    narr: `电容充到 u_C = ${ucAt(1).toFixed(3)} V（${pctAt(1).toFixed(1)}%），电流降到 i = ${iMA(1).toFixed(3)} mA。每过一个 τ，就走完「剩余缺口」的 63.2%。`,
  },
  {
    n: "③", t: 2, title: "t = 2τ = 2 ms · 充电 2τ",
    narr: `u_C = ${ucAt(2).toFixed(3)} V（${pctAt(2).toFixed(1)}%），i = ${iMA(2).toFixed(3)} mA。缺口按指数收缩，充电越来越慢。`,
  },
  {
    n: "④", t: 3, title: "t = 3τ = 3 ms · 充电 3τ",
    narr: `u_C = ${ucAt(3).toFixed(3)} V（${pctAt(3).toFixed(1)}%），电流只剩 i = ${iMA(3).toFixed(3)} mA。工程上从 3τ 起视为基本充满。`,
  },
  {
    n: "⑤", t: 4, title: "t = 4τ = 4 ms · 充电 4τ",
    narr: `u_C = ${ucAt(4).toFixed(3)} V（${pctAt(4).toFixed(1)}%），i = ${iMA(4).toFixed(3)} mA。`,
  },
  {
    n: "⑥", t: 5, title: "t = 5τ = 5 ms · 充满",
    narr: `u_C = ${ucAt(5).toFixed(3)} V（${pctAt(5).toFixed(1)}%）≈ u_in = 1 V，剩余缺口不足 1%，充电结束。`,
  },
];

const INTRO_F = 40;
const STEP_F = 55;
const OUTRO_F = 30;
export const RC_DURATION = INTRO_F + STEP_F * STEPS.length + OUTRO_F; // 400 帧 @30fps ≈ 13.3 s

// 配色（core §3 暗色变量表，视频固定暗底）
const BG = "#14161c";
const CARD = "#1b1e26";
const TEXT = "#e8eaed";
const MUTED = "#8a8f98";
const ACCENT = "#5e6ad2";
const ACCENT_L = "#a0a8e8";
const BORDER = "#2a2e38";
const GRID = "#2a2e38";
const CURVE_FULL = "#4a5164";

// 曲线图几何（容器 800×500 内）
const CH_W = 800;
const CH_H = 500;
const X0 = 64;
const Y0 = 26;
const PLOT_W = 706;
const PLOT_H = 424;
const T_MAX = 6;
const U_MAX = 1.12;
const xPix = (t: number) => X0 + (t / T_MAX) * PLOT_W;
const yPix = (u: number) => Y0 + PLOT_H - (u / U_MAX) * PLOT_H;

const easeOut = (x: number) => 1 - Math.pow(1 - x, 3);

const FONT = "'Segoe UI', 'Microsoft YaHei', sans-serif";

export const RcChargeSteps: FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const introOpacity = interpolate(frame, [2, 26], [0, 1], { extrapolateRight: "clamp" });

  // 当前步与曲线推进
  const stepIdx = Math.min(
    STEPS.length - 1,
    Math.max(0, Math.floor((frame - INTRO_F) / STEP_F)),
  );
  const inSteps = frame >= INTRO_F && frame < INTRO_F + STEP_F * STEPS.length;
  const step = STEPS[stepIdx];
  const lf = Math.max(0, frame - (INTRO_F + stepIdx * STEP_F)); // 步内局部帧
  // 曲线终点 t：第①步停在 0；第 k 步从 (k−2)τ 推进到 (k−1)τ
  let tCur = 0;
  if (stepIdx > 0) {
    const p = inSteps ? easeOut(Math.min(1, lf / (STEP_F - 14))) : 1;
    tCur = (stepIdx - 1) + p; // 目标 (stepIdx)τ
  }
  if (frame >= INTRO_F + STEP_F * STEPS.length) tCur = STEPS.length - 1;

  // 采样曲线折线
  const pts: string[] = [];
  for (let t = 0; t <= tCur + 1e-9; t += 0.02) {
    pts.push(`${xPix(t).toFixed(1)},${yPix(ucAt(Math.min(t, tCur))).toFixed(1)}`);
  }
  if (pts.length === 0) pts.push(`${xPix(0).toFixed(1)},${yPix(0).toFixed(1)}`);

  // 步末弹出值牌
  const popF = lf - (STEP_F - 16);
  const pop =
    stepIdx >= 0 && popF > 0
      ? spring({ frame: popF, fps, config: { damping: 12, mass: 0.6 } })
      : 0;
  const calloutText =
    step.t === 0
      ? `u_C = 0.000 V · i = U/R = 1.000 mA（最大）`
      : `u_C = ${ucAt(step.t).toFixed(3)} V（${pctAt(step.t).toFixed(1)}%）`;

  // u_in 跳升（第①步内弹簧入场）
  const uinSpring = spring({
    frame: Math.max(0, frame - INTRO_F),
    fps,
    config: { damping: 200 },
  });

  const outro = frame >= INTRO_F + STEP_F * STEPS.length;
  const progress = frame / RC_DURATION;
  // 值牌位置：第⑤⑥步放曲线下方左侧，避开 u_in 标签
  const calloutLeft = step.t >= 4 ? xPix(step.t) - 292 : xPix(Math.max(step.t, 0.15)) + 18;
  const calloutTop = step.t >= 4 ? yPix(ucAt(step.t)) + 36 : yPix(ucAt(step.t)) - 64;

  return (
    <AbsoluteFill style={{ backgroundColor: BG, fontFamily: FONT, opacity: introOpacity }}>
      {/* 标题 */}
      <div style={{ textAlign: "center", marginTop: 22 }}>
        <div style={{ color: TEXT, fontSize: 32, fontWeight: 700 }}>
          RC 电路充电 · 分步讲解
        </div>
        <div style={{ color: MUTED, fontSize: 17, marginTop: 8 }}>
          R = 1 kΩ，C = 1 µF，τ = RC = 1 ms，U = 1 V（教学示例值）
        </div>
      </div>

      {/* 曲线图卡（数据驱动，逐帧实时采样） */}
      <div
        style={{
          position: "absolute", left: 64, top: 112, width: CH_W, height: CH_H,
          backgroundColor: CARD, border: `1px solid ${BORDER}`, borderRadius: 14,
        }}
      >
        <svg width={CH_W} height={CH_H}>
          {/* 网格 */}
          {Array.from({ length: 7 }, (_, i) => (
            <line key={`gx${i}`} x1={xPix(i)} y1={yPix(0)} x2={xPix(i)} y2={yPix(U_MAX > 1 ? 1.0 : U_MAX)} stroke={GRID} strokeWidth={1} opacity={0.55} />
          ))}
          {[0.2, 0.4, 0.6, 0.8, 1.0].map((u) => (
            <line key={`gy${u}`} x1={xPix(0)} y1={yPix(u)} x2={xPix(T_MAX)} y2={yPix(u)} stroke={GRID} strokeWidth={1} opacity={0.55} />
          ))}
          {/* 目标值虚线 u_in = 1 V */}
          <line
            x1={xPix(0)} y1={yPix(1)} x2={xPix(T_MAX)} y2={yPix(1)}
            stroke={MUTED} strokeWidth={1.6} strokeDasharray="7 6" opacity={0.35 + 0.6 * uinSpring}
          />
          <text x={xPix(T_MAX) - 8} y={yPix(1) - 9} textAnchor="end" fill={MUTED} fontSize={14} opacity={0.35 + 0.6 * uinSpring}>
            u_in = 1 V
          </text>
          {/* 完整响应底稿 */}
          <polyline
            points={Array.from({ length: 301 }, (_, i) => {
              const t = (i / 300) * T_MAX;
              return `${xPix(t).toFixed(1)},${yPix(ucAt(t)).toFixed(1)}`;
            }).join(" ")}
            fill="none" stroke={CURVE_FULL} strokeWidth={1.6}
          />
          {/* 已充段（随时间推进） */}
          <polyline points={pts.join(" ")} fill="none" stroke={ACCENT_L} strokeWidth={4} strokeLinecap="round" />
          {/* τ 里程碑点 */}
          {[1, 2, 3, 4, 5].map((k) =>
            tCur >= k - 1e-6 ? (
              <circle key={k} cx={xPix(k)} cy={yPix(ucAt(k))} r={4.5} fill="none" stroke={MUTED} strokeWidth={1.4} />
            ) : null,
          )}
          {/* 当前点 */}
          <circle cx={xPix(tCur)} cy={yPix(ucAt(tCur))} r={7.5} fill={ACCENT_L} />
          {/* 坐标轴 */}
          <line x1={xPix(0)} y1={yPix(0)} x2={xPix(0)} y2={yPix(U_MAX)} stroke={MUTED} strokeWidth={1.4} />
          <line x1={xPix(0)} y1={yPix(0)} x2={xPix(T_MAX)} y2={yPix(0)} stroke={MUTED} strokeWidth={1.4} />
          {[0, 1, 2, 3, 4, 5, 6].map((t) => (
            <text key={`tx${t}`} x={xPix(t)} y={yPix(0) + 22} textAnchor="middle" fill={MUTED} fontSize={13}>{t}</text>
          ))}
          {[0, 0.2, 0.4, 0.6, 0.8, 1.0].map((u) => (
            <text key={`ty${u}`} x={xPix(0) - 12} y={yPix(u) + 4} textAnchor="end" fill={MUTED} fontSize={13}>
              {u.toFixed(1)}
            </text>
          ))}
          <text x={xPix(T_MAX)} y={yPix(0) + 44} textAnchor="end" fill={MUTED} fontSize={14}>t (ms)</text>
          <text x={xPix(0) + 6} y={yPix(U_MAX) - 6} fill={MUTED} fontSize={14}>u_C (V)</text>
          {/* 步末值牌 */}
          {pop > 0 && (
            <g transform={`translate(${calloutLeft},${calloutTop}) scale(${pop})`}>
              <rect x={0} y={0} width={272} height={46} rx={10} fill={CARD} stroke={ACCENT} strokeWidth={1.5} />
              <text x={14} y={29} fill={TEXT} fontSize={17} fontWeight={600}>{calloutText}</text>
            </g>
          )}
        </svg>
      </div>

      {/* 原理图卡（schemdraw 真实渲染） */}
      <div
        style={{
          position: "absolute", left: 64 + CH_W + 20, top: 112, width: 332, height: 258,
          backgroundColor: CARD, border: `1px solid ${BORDER}`, borderRadius: 14,
          display: "flex", alignItems: "center", justifyContent: "center",
        }}
      >
        <Img src={staticFile("rc_schematic_dark.png")} width={308} />
      </div>

      {/* 当前步卡 */}
      <div
        style={{
          position: "absolute", left: 64 + CH_W + 20, top: 390, width: 332, height: 222,
          backgroundColor: CARD, border: `1px solid ${BORDER}`, borderRadius: 14, padding: "18px 20px",
        }}
      >
        <div style={{ display: "flex", alignItems: "baseline", gap: 12 }}>
          <span style={{ color: ACCENT_L, fontSize: 44, fontWeight: 700 }}>{step.n}</span>
          <span style={{ color: TEXT, fontSize: 19, fontWeight: 600 }}>{step.title}</span>
        </div>
        <div style={{ color: MUTED, fontSize: 14, marginTop: 12, lineHeight: 1.5 }}>
          步 {stepIdx + 1} / 6 · 曲线终点 t = {tCur.toFixed(2)} ms
        </div>
        <div style={{ color: MUTED, fontSize: 14, marginTop: 6, lineHeight: 1.5 }}>
          {outro ? "全部 6 步完成" : `曲线按 u_C(t) = U(1 − e^(−t/τ)) 逐帧采样`}
        </div>
      </div>

      {/* 字幕条 */}
      <div
        style={{
          position: "absolute", left: 64, bottom: 34, width: 1152,
          backgroundColor: CARD, border: `1px solid ${BORDER}`, borderRadius: 12, padding: "13px 18px",
        }}
      >
        <div style={{ color: TEXT, fontSize: 16, fontWeight: 600 }}>
          <span style={{ color: ACCENT_L, marginRight: 10 }}>{outro ? "复盘" : step.n}</span>
          {outro ? "每过一个 τ，走完剩余缺口的 63.2%" : step.title}
        </div>
        <div style={{ color: TEXT, opacity: 0.92, fontSize: 15, marginTop: 6, lineHeight: 1.55 }}>
          {outro
            ? "里程碑复算：1τ 63.2% → 2τ 86.5% → 3τ 95.0% → 4τ 98.2% → 5τ 99.3%（工程上 3τ～5τ 视为基本充满）。"
            : step.narr}
        </div>
        <div style={{ color: MUTED, fontSize: 13, marginTop: 6, fontVariantNumeric: "tabular-nums" }}>
          {outro
            ? "全部数值由 u_C(t) = U(1 − e^(−t/τ)) 实时计算 · R = 1 kΩ，C = 1 µF，U = 1 V（教学示例值）"
            : `u_C(${step.t} ms) = ${ucAt(step.t).toFixed(3)} V（${pctAt(step.t).toFixed(1)}%） · i = ${iMA(step.t).toFixed(3)} mA · τ = RC = ${TAU_MS.toFixed(0)} ms`}
        </div>
      </div>

      {/* 进度条 */}
      <div style={{ position: "absolute", left: 0, bottom: 0, width: `${progress * 100}%`, height: 6, backgroundColor: ACCENT }} />
    </AbsoluteFill>
  );
};
