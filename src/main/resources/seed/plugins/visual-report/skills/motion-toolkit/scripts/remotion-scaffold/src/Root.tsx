import type { FC } from "react";
import {
  AbsoluteFill,
  Composition,
  interpolate,
  spring,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import { RC_DURATION, RcChargeSteps } from "./RcChargeSteps";

// 最小可复用组件：场景只改本文件（复制 MotionSmoke 换几何/动画逻辑），不动 index.ts。
// 配色取 core/SKILL.md §3 暗色变量表（无纯黑纯白数据色）。
export type MotionProps = {
  title: string;
  subtitle: string;
  accent: string;
};

export const MotionSmoke: FC<MotionProps> = ({ title, subtitle, accent }) => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();

  const enter = spring({ frame, fps, config: { damping: 200 } });
  const opacity = interpolate(enter, [0, 1], [0, 1]);
  const translateY = interpolate(enter, [0, 1], [40, 0]);
  const barWidth = interpolate(frame, [0, durationInFrames], [4, 100]);

  return (
    <AbsoluteFill
      style={{
        backgroundColor: "#14161c",
        justifyContent: "center",
        alignItems: "center",
        fontFamily: "'Segoe UI', 'Microsoft YaHei', sans-serif",
      }}
    >
      <h1
        style={{
          color: "#e8eaed",
          fontSize: 76,
          margin: 0,
          opacity,
          transform: `translateY(${translateY}px)`,
        }}
      >
        {title}
      </h1>
      <p style={{ color: "#8a8f98", fontSize: 30, marginTop: 18 }}>{subtitle}</p>
      <div
        style={{
          position: "absolute",
          bottom: 64,
          left: 0,
          width: `${barWidth}%`,
          height: 8,
          backgroundColor: accent,
        }}
      />
    </AbsoluteFill>
  );
};

export const RemotionRoot: FC = () => {
  return (
    <>
      <Composition
        id="MotionSmoke"
        component={MotionSmoke}
        durationInFrames={60}
        fps={30}
        width={1280}
        height={720}
        defaultProps={{
          title: "motion-toolkit",
          subtitle: "Remotion 4.0.532 · 本机 Chrome 渲染",
          accent: "#5e6ad2",
        }}
      />
      {/* education-motion demo · RC 充电分步讲解（400 帧 @30fps） */}
      <Composition
        id="RcChargeSteps"
        component={RcChargeSteps}
        durationInFrames={RC_DURATION}
        fps={30}
        width={1280}
        height={720}
      />
    </>
  );
};
