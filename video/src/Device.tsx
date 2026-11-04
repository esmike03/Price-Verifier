import React from 'react';
import {Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {color} from './theme';
import {clamp} from './ui';

// The device photo (public/device.png) is 700x700. These are its display
// corners (TL, TR, BR, BL) as measured by scripts/find_screen.py.
const PHOTO = 700;
const QUAD: [number, number][] = [
  [165.07, 134.35],
  [500.59, 228.59],
  [489.18, 537.55],
  [171.28, 405.66],
];

// App screenshots are 1340x800; screen content is laid out at that size and
// projected onto the display.
export const SCREEN_W = 1340;
export const SCREEN_H = 800;

// Points on the device, in photo pixels.
const POINTS = {
  // Scan window / status light at the bottom of the housing.
  scanner: {x: 328, y: 522},
  // Middle of the housing's left edge.
  left: {x: 148, y: 341},
  // Underside of the housing.
  bottom: {x: 320, y: 592},
};

// Pixel positions of those points inside a Device box of the given size.
export const deviceAnchors = (size: number) => {
  const k = size / PHOTO;
  const at = (p: {x: number; y: number}) => ({x: p.x * k, y: p.y * k});
  return {scanner: at(POINTS.scanner), left: at(POINTS.left), bottom: at(POINTS.bottom)};
};

const solve = (m: number[][], v: number[]) => {
  const n = v.length;
  const a = m.map((row, i) => [...row, v[i]]);
  for (let col = 0; col < n; col++) {
    let pivot = col;
    for (let row = col + 1; row < n; row++) {
      if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) pivot = row;
    }
    [a[col], a[pivot]] = [a[pivot], a[col]];
    for (let row = 0; row < n; row++) {
      if (row === col) continue;
      const k = a[row][col] / a[col][col];
      for (let c = col; c <= n; c++) a[row][c] -= k * a[col][c];
    }
  }
  return a.map((row, i) => row[n] / row[i]);
};

// CSS matrix3d that maps a w x h box (origin top-left) onto the quad.
const projection = (w: number, h: number, quad: [number, number][]) => {
  const src: [number, number][] = [
    [0, 0],
    [w, 0],
    [w, h],
    [0, h],
  ];
  const m: number[][] = [];
  const v: number[] = [];
  src.forEach(([x, y], i) => {
    const [u, t] = quad[i];
    m.push([x, y, 1, 0, 0, 0, -x * u, -y * u]);
    v.push(u);
    m.push([0, 0, 0, x, y, 1, -x * t, -y * t]);
    v.push(t);
  });
  const [a, b, c, d, e, f, g, i] = solve(m, v);
  return `matrix3d(${a},${d},0,${g},${b},${e},0,${i},0,0,1,0,${c},${f},0,1)`;
};

const SCREEN_TRANSFORM = projection(SCREEN_W, SCREEN_H, QUAD);
const DROP_SHADOW = 'drop-shadow(0 30px 36px rgba(28,45,100,0.3)) drop-shadow(0 4px 8px rgba(28,45,100,0.14))';

export const Device: React.FC<{
  size: number;
  // 0..1 brightness of the green "good read" flash on the status light.
  flash?: number;
  style?: React.CSSProperties;
  children: React.ReactNode;
}> = ({size, flash = 0, style, children}) => (
  <div style={{position: 'absolute', width: size, height: size, ...style}}>
    <div
      style={{
        position: 'absolute',
        width: PHOTO,
        height: PHOTO,
        transform: `scale(${size / PHOTO})`,
        transformOrigin: '0 0',
      }}
    >
      <Img
        src={staticFile('device.png')}
        style={{position: 'absolute', width: PHOTO, height: PHOTO, filter: DROP_SHADOW}}
      />
      <div
        style={{
          position: 'absolute',
          left: 0,
          top: 0,
          width: SCREEN_W,
          height: SCREEN_H,
          transformOrigin: '0 0',
          transform: SCREEN_TRANSFORM,
          overflow: 'hidden',
          background: '#0b0c10',
          borderRadius: 6,
        }}
      >
        {children}
        <div
          style={{
            position: 'absolute',
            inset: 0,
            backgroundImage:
              'linear-gradient(118deg, rgba(255,255,255,0.13) 0%, rgba(255,255,255,0.03) 26%, transparent 42%)',
          }}
        />
      </div>
      {flash > 0.01 ? (
        <div
          style={{
            position: 'absolute',
            left: POINTS.scanner.x - 110,
            top: POINTS.scanner.y - 80,
            width: 220,
            height: 160,
            opacity: flash,
            backgroundImage: `radial-gradient(ellipse at center, ${color.led} 0%, rgba(0,230,118,0.45) 32%, transparent 68%)`,
          }}
        />
      ) : null}
    </div>
  </div>
);

// Full-screen app screenshot.
export const Shot: React.FC<{name: string; style?: React.CSSProperties}> = ({name, style}) => (
  <Img
    src={staticFile(`shots/${name}`)}
    style={{position: 'absolute', left: 0, top: 0, width: SCREEN_W, height: SCREEN_H, ...style}}
  />
);

// A screenshot that snaps in at `from` with a short pop and screen flash, the
// way the app jumps from the idle screen to a price.
export const PopShot: React.FC<{name: string; from: number}> = ({name, from}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const local = frame - from;
  if (local < 0) return null;
  const settle = spring({frame: local, fps, config: {damping: 18, mass: 0.6}});
  return (
    <>
      <Shot
        name={name}
        style={{
          opacity: interpolate(local, [0, 3], [0, 1], clamp),
          transform: `scale(${1.06 - 0.06 * settle})`,
        }}
      />
      <div
        style={{
          position: 'absolute',
          inset: 0,
          background: '#fff',
          opacity: interpolate(local, [0, 9], [0.6, 0], clamp),
        }}
      />
    </>
  );
};
