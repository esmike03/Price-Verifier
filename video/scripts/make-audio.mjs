// Synthesizes the showcase soundtrack and sound effects into public/audio/.
// Everything is generated from scratch, so there is no third-party licence.
// The arrangement follows the scene layout in src/timing.ts (21 bars, 120 BPM).
import {mkdirSync, writeFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const SR = 44100;
const BPM = 120;
const BEAT = 60 / BPM;
const BAR = BEAT * 4;
const BARS = 21;
const outDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'public', 'audio');
mkdirSync(outDir, {recursive: true});

const hz = (midi) => 440 * 2 ** ((midi - 69) / 12);

// Deterministic noise so re-running the script reproduces the same files.
let seed = 0x2545f491;
const noise = () => {
  seed ^= seed << 13;
  seed ^= seed >>> 17;
  seed ^= seed << 5;
  return ((seed >>> 0) / 0xffffffff) * 2 - 1;
};

const makeBus = (seconds) => {
  const n = Math.ceil(seconds * SR);
  return {l: new Float32Array(n), r: new Float32Array(n), n};
};

// Adds a harmonic tone to a bus. env(t, dur) returns the gain at time t.
const tone = (bus, start, dur, freq, amp, pan, env, harmonics = [1]) => {
  const s0 = Math.floor(start * SR);
  const len = Math.floor(dur * SR);
  const gl = amp * Math.cos(((pan + 1) * Math.PI) / 4);
  const gr = amp * Math.sin(((pan + 1) * Math.PI) / 4);
  for (let i = 0; i < len; i++) {
    const idx = s0 + i;
    if (idx < 0 || idx >= bus.n) continue;
    const t = i / SR;
    let v = 0;
    for (let h = 0; h < harmonics.length; h++) {
      v += harmonics[h] * Math.sin(2 * Math.PI * freq * (h + 1) * t);
    }
    v *= env(t, dur);
    bus.l[idx] += v * gl;
    bus.r[idx] += v * gr;
  }
};

const padEnv = (t, dur) => Math.min(1, t / 0.45) * Math.min(1, Math.max(0, (dur - t) / 0.9));
const pluckEnv = (t) => Math.min(1, t / 0.004) * Math.exp(-t / 0.17);
const bassEnv = (t, dur) => Math.min(1, t / 0.01) * Math.exp(-t / 0.45) * Math.min(1, (dur - t) / 0.03);

const kick = (bus, start, amp) => {
  const len = Math.floor(0.4 * SR);
  const s0 = Math.floor(start * SR);
  let phase = 0;
  for (let i = 0; i < len; i++) {
    const idx = s0 + i;
    if (idx >= bus.n) break;
    const t = i / SR;
    phase += (2 * Math.PI * (46 + 95 * Math.exp(-t / 0.028))) / SR;
    const v = Math.sin(phase) * Math.exp(-t / 0.13) * Math.min(1, t / 0.002) * amp;
    bus.l[idx] += v;
    bus.r[idx] += v;
  }
};

// Short filtered-noise hit: bright for hats, darker and longer for the clap.
const noiseHit = (bus, start, amp, decay, bright, pan) => {
  const len = Math.floor(decay * 6 * SR);
  const s0 = Math.floor(start * SR);
  let prev = 0;
  let lp = 0;
  for (let i = 0; i < len; i++) {
    const idx = s0 + i;
    if (idx >= bus.n) break;
    const x = noise();
    const hp = x - prev;
    prev = x;
    lp += 0.35 * (x - lp);
    const v = (bright ? hp * 0.6 : lp - 0.5 * hp * 0.2) * Math.exp(-i / SR / decay) * amp;
    bus.l[idx] += v * (1 - Math.max(0, pan));
    bus.r[idx] += v * (1 + Math.min(0, pan));
  }
};

const writeWav = (name, bus, peak = 0.85) => {
  let max = 0;
  for (let i = 0; i < bus.n; i++) max = Math.max(max, Math.abs(bus.l[i]), Math.abs(bus.r[i]));
  const gain = max > 0 ? peak / max : 1;
  const data = Buffer.alloc(44 + bus.n * 4);
  data.write('RIFF', 0);
  data.writeUInt32LE(36 + bus.n * 4, 4);
  data.write('WAVEfmt ', 8);
  data.writeUInt32LE(16, 16);
  data.writeUInt16LE(1, 20);
  data.writeUInt16LE(2, 22);
  data.writeUInt32LE(SR, 24);
  data.writeUInt32LE(SR * 4, 28);
  data.writeUInt16LE(4, 32);
  data.writeUInt16LE(16, 34);
  data.write('data', 36);
  data.writeUInt32LE(bus.n * 4, 40);
  for (let i = 0; i < bus.n; i++) {
    data.writeInt16LE(Math.round(Math.max(-1, Math.min(1, bus.l[i] * gain)) * 32767), 44 + i * 4);
    data.writeInt16LE(Math.round(Math.max(-1, Math.min(1, bus.r[i] * gain)) * 32767), 46 + i * 4);
  }
  writeFileSync(join(outDir, name), data);
  console.log(`${name}: ${(bus.n / SR).toFixed(2)}s, raw peak ${max.toFixed(3)}`);
};

// ---------------------------------------------------------------- music ----
// Am7 - Fmaj7 - Cadd9 - G, one chord per bar.
const CHORDS = [
  {bass: 45, pad: [57, 60, 64, 67]},
  {bass: 41, pad: [53, 57, 60, 64]},
  {bass: 48, pad: [55, 60, 62, 64]},
  {bass: 43, pad: [55, 59, 62, 67]},
];
const FINAL = {bass: 48, pad: [55, 60, 62, 64, 67]};
const ARP = [0, 2, 1, 3, 2, 3, 1, 2];

// Which layers play in each bar (scene boundaries: 3, 8, 10, 15, 17, 19).
const layers = (bar) => ({
  arp: bar < BARS - 1,
  bass: bar >= 3 && bar < 19,
  kick: bar >= 3 && bar < 19,
  fourFloor: bar >= 10 && bar < 19,
  hats: bar >= 8 && bar < 19,
  clap: bar >= 10 && bar < 19,
});

const total = BARS * BAR + 2.5;
const pad = makeBus(total);
const rest = makeBus(total);
const kickTimes = [];

for (let bar = 0; bar < BARS; bar++) {
  const t0 = bar * BAR;
  const last = bar === BARS - 1;
  const chord = last ? FINAL : CHORDS[bar % 4];
  const on = layers(bar);

  chord.pad.forEach((note, i) => {
    const dur = last ? BAR + 2.2 : BAR + 0.7;
    const pan = (i / (chord.pad.length - 1)) * 1.2 - 0.6;
    tone(pad, t0, dur, hz(note) * 1.0023, 0.05, pan, padEnv, [1, 0.32, 0.1]);
    tone(pad, t0, dur, hz(note) * 0.9977, 0.05, -pan, padEnv, [1, 0.32, 0.1]);
  });

  if (on.arp) {
    const lift = bar >= 10 && bar < 19 ? 24 : 12;
    for (let step = 0; step < 8; step++) {
      const note = chord.pad[ARP[step] % chord.pad.length] + lift;
      const t = t0 + step * (BEAT / 2);
      const pan = step % 2 ? 0.45 : -0.45;
      const amp = bar < 3 ? 0.06 : 0.085;
      tone(rest, t, 1.1, hz(note), amp, pan, pluckEnv, [1, 0.38, 0.12]);
      tone(rest, t + BEAT * 0.75, 1.1, hz(note), amp * 0.36, -pan, pluckEnv, [1, 0.3]);
      tone(rest, t + BEAT * 1.5, 1.1, hz(note), amp * 0.14, pan, pluckEnv, [1, 0.25]);
    }
  }

  if (on.bass) {
    for (const [beat, len] of [[0, 1.4], [1.5, 0.9], [2.5, 0.9], [3.5, 0.45]]) {
      tone(rest, t0 + beat * BEAT, len * BEAT, hz(chord.bass - 12), 0.2, 0, bassEnv, [1, 0.35, 0.08]);
    }
  }

  if (on.kick) {
    for (const beat of on.fourFloor ? [0, 1, 2, 3] : [0, 2]) {
      kick(rest, t0 + beat * BEAT, 0.42);
      kickTimes.push(t0 + beat * BEAT);
    }
  }

  if (on.hats) {
    for (let step = 0; step < 8; step++) {
      if (step % 2 === 1) noiseHit(rest, t0 + step * (BEAT / 2), 0.05, 0.022, true, step % 4 === 1 ? 0.3 : -0.3);
    }
  }

  if (on.clap) {
    noiseHit(rest, t0 + BEAT, 0.11, 0.05, false, 0);
    noiseHit(rest, t0 + 3 * BEAT, 0.11, 0.05, false, 0);
  }
}

// Duck the pad under each kick so the low end stays clean.
for (const t of kickTimes) {
  const s0 = Math.floor(t * SR);
  const len = Math.floor(0.3 * SR);
  for (let i = 0; i < len && s0 + i < pad.n; i++) {
    const g = 1 - 0.4 * Math.exp(-i / SR / 0.09);
    pad.l[s0 + i] *= g;
    pad.r[s0 + i] *= g;
  }
}

const music = makeBus(total);
// The video ends exactly at the last bar line, so the fade must finish there.
const end = BARS * BAR;
const fadeOutStart = end - 1.3;
for (let i = 0; i < music.n; i++) {
  const t = i / SR;
  const fade = Math.min(1, t / 0.4) * Math.min(1, Math.max(0, (end - t) / (end - fadeOutStart)));
  music.l[i] = Math.tanh((pad.l[i] + rest.l[i]) * 1.6) * fade;
  music.r[i] = Math.tanh((pad.r[i] + rest.r[i]) * 1.6) * fade;
}
writeWav('music.wav', music);

// ---------------------------------------------------------------- beep -----
// Handheld-scanner style confirmation beep.
const beep = makeBus(0.16);
tone(beep, 0, 0.11, 2350, 0.5, 0, (t, d) => Math.min(1, t / 0.004) * Math.min(1, (d - t) / 0.012), [1, 0.22, 0.08]);
writeWav('beep.wav', beep, 0.6);

// ---------------------------------------------------------------- whoosh ---
// Soft noise swell for scene changes.
const whoosh = makeBus(0.7);
{
  let lpL = 0;
  let lpR = 0;
  for (let i = 0; i < whoosh.n; i++) {
    const p = i / whoosh.n;
    const cutoff = 0.02 + 0.22 * Math.sin(Math.PI * p) ** 2;
    lpL += cutoff * (noise() - lpL);
    lpR += cutoff * (noise() - lpR);
    const env = Math.sin(Math.PI * p) ** 2;
    whoosh.l[i] = lpL * env * (1 - p * 0.5);
    whoosh.r[i] = lpR * env * (0.5 + p * 0.5);
  }
}
writeWav('whoosh.wav', whoosh, 0.5);

// ---------------------------------------------------------------- pop ------
// Tiny tick used when UI elements land.
const pop = makeBus(0.12);
tone(pop, 0, 0.1, 880, 0.5, 0, (t) => Math.min(1, t / 0.002) * Math.exp(-t / 0.02), [1, 0.5]);
writeWav('pop.wav', pop, 0.5);
