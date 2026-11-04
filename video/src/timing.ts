// The soundtrack (scripts/make-audio.mjs) is 120 BPM, so one bar is exactly
// 60 frames at 30 fps. Scene lengths are whole bars so cuts land on the beat.
export const FPS = 30;
export const BAR = 60;
export const WIDTH = 1920;
export const HEIGHT = 1080;

// Frames two neighbouring scenes overlap while one slides out and the next in.
export const XFADE = 12;

const bars = {
  intro: 3,
  scan: 5,
  stats: 2,
  portal: 5,
  sync: 2,
  install: 2,
  outro: 2,
} as const;

export type SceneId = keyof typeof bars;

const order = Object.keys(bars) as SceneId[];

export const SCENES = order.reduce(
  (acc, id) => {
    const from = order
      .slice(0, order.indexOf(id))
      .reduce((sum, prev) => sum + bars[prev] * BAR, 0);
    acc[id] = {from, duration: bars[id] * BAR};
    return acc;
  },
  {} as Record<SceneId, {from: number; duration: number}>,
);

export const TOTAL_FRAMES = order.reduce((sum, id) => sum + bars[id] * BAR, 0);

// Scan scene: an idle beat, then four scans.
export const SCAN_IDLE = 30;
export const SCAN_STEP = 64;
// Frame within a scan step at which the beep fires and the screen changes.
export const SCAN_HIT = 20;

// Portal scene: five tabs, one bar each.
export const PORTAL_TAB = 60;
