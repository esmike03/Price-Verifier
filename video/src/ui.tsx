import React from 'react';
import {AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {color, font, shadow} from './theme';
import {XFADE} from './timing';

export const clamp = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;

export const Background: React.FC = () => {
  const frame = useCurrentFrame();
  const t = frame / 30;
  const blob = (x: number, y: number, r: number, c: string) =>
    `radial-gradient(${r}px circle at ${x}px ${y}px, ${c}, transparent 70%)`;
  const blobs = [
    blob(340 + Math.sin(t * 0.5) * 180, 200 + Math.cos(t * 0.45) * 120, 900, 'rgba(124,58,237,0.15)'),
    blob(1600 + Math.cos(t * 0.4) * 200, 860 + Math.sin(t * 0.5) * 140, 950, 'rgba(8,145,178,0.17)'),
    blob(1120 + Math.sin(t * 0.35 + 2) * 260, 100 + Math.cos(t * 0.4) * 100, 700, 'rgba(37,99,235,0.13)'),
  ];
  return (
    <AbsoluteFill style={{backgroundColor: color.bg}}>
      <AbsoluteFill style={{backgroundImage: blobs.join(', ')}} />
    </AbsoluteFill>
  );
};

// Slides a scene in from the right and out to the left. The parent <Sequence>
// must run for duration + XFADE frames so the outgoing scene stays mounted.
const SLIDE = 150;

export const Scene: React.FC<{
  duration: number;
  first?: boolean;
  last?: boolean;
  children: React.ReactNode;
}> = ({duration, first, last, children}) => {
  const frame = useCurrentFrame();
  const enter = first ? 1 : interpolate(frame, [0, XFADE], [0, 1], {...clamp, easing: Easing.out(Easing.cubic)});
  const exit = last
    ? 0
    : interpolate(frame, [duration, duration + XFADE], [0, 1], {...clamp, easing: Easing.in(Easing.cubic)});
  return (
    <AbsoluteFill
      style={{
        opacity: enter * (1 - exit),
        transform: `translateX(${(1 - enter) * SLIDE - exit * SLIDE}px)`,
        fontFamily: font.body,
        color: color.text,
      }}
    >
      {children}
    </AbsoluteFill>
  );
};

export const useRise = (delay = 0) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  return spring({frame: frame - delay, fps, config: {damping: 200, mass: 0.45}});
};

export const Rise: React.FC<{
  delay?: number;
  y?: number;
  x?: number;
  style?: React.CSSProperties;
  children: React.ReactNode;
}> = ({delay = 0, y = 30, x = 0, style, children}) => {
  const p = useRise(delay);
  return (
    <div style={{opacity: p, transform: `translate(${(1 - p) * x}px, ${(1 - p) * y}px)`, ...style}}>
      {children}
    </div>
  );
};

export const Kicker: React.FC<{children: React.ReactNode; tone?: string}> = ({children, tone = color.blue}) => (
  <div
    style={{
      fontFamily: font.body,
      fontWeight: 700,
      fontSize: 24,
      letterSpacing: 5,
      textTransform: 'uppercase',
      color: tone,
    }}
  >
    {children}
  </div>
);

export const Chip: React.FC<{children: React.ReactNode; style?: React.CSSProperties}> = ({children, style}) => (
  <div
    style={{
      padding: '14px 26px',
      borderRadius: 999,
      background: color.panel,
      border: `1.5px solid ${color.panelBorder}`,
      boxShadow: shadow.card,
      fontSize: 26,
      fontWeight: 600,
      color: color.text,
      whiteSpace: 'nowrap',
      ...style,
    }}
  >
    {children}
  </div>
);

export const gradientText = (from: string, to: string): React.CSSProperties => ({
  backgroundImage: `linear-gradient(100deg, ${from}, ${to})`,
  WebkitBackgroundClip: 'text',
  backgroundClip: 'text',
  color: 'transparent',
});
