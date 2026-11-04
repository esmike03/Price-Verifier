import React from 'react';
import {Easing, interpolate, random, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {Device, PopShot, Shot, deviceAnchors} from '../Device';
import {color, font, shadow} from '../theme';
import {SCAN_HIT, SCAN_IDLE, SCAN_STEP} from '../timing';
import {Kicker, Rise, clamp} from '../ui';

const SCANS = [
  {name: 'Baking Soda', code: '480653306059', shot: 'app-price.png'},
  {name: 'Baking Soda', code: '480653306059', shot: 'app-promo.png'},
  {name: 'Clear Coffee Mug', code: '0000000069083', shot: 'app-b1t1.png'},
  {name: 'Krematop', code: '0000049680218', shot: 'app-special.png'},
];

const STEPS = [
  {pill: 'Idle ads', title: 'Your ads, between scans', body: 'Banners, videos and a scrolling message.'},
  {pill: 'Price', title: 'Scan. Price. Instantly.', body: 'Looked up on the device. Works offline.'},
  {pill: 'Promo', title: 'Promos show up on their own', body: 'Percent off, amount off or a fixed price.'},
  {pill: 'Buy 1 Take 1', title: 'Bundle deals, front and centre', body: 'Buy 1 Take 1, right on the price screen.'},
  {pill: 'Special price', title: 'Was. Now. You save.', body: 'Special prices with the savings spelled out.'},
];

export const scanStart = (k: number) => SCAN_IDLE + k * SCAN_STEP;
export const scanHit = (k: number) => scanStart(k) + SCAN_HIT;
export const SCAN_COUNT = SCANS.length;

const stepStart = (i: number) => (i === 0 ? 0 : scanHit(i - 1));

const DEVICE = {size: 1050, left: -103, top: -75};
const CARD = {width: 300, height: 176, top: 856};
// Frames into a scan step at which the laser switches on and the card leaves.
const LASER_ON = 8;
const CARD_EXIT = SCAN_STEP - 16;
const CARD_GONE = SCAN_STEP - 2;

// Decorative bars seeded from the barcode digits (not a scannable symbol).
const Barcode: React.FC<{code: string}> = ({code}) => {
  const bars: {x: number; w: number}[] = [];
  let x = 0;
  for (let i = 0; x < 232; i++) {
    const w = 2 + Math.floor(random(`${code}-w-${i}`) * 3) * 2;
    const gap = 2 + Math.floor(random(`${code}-g-${i}`) * 3) * 2;
    if (x + w > 236) break;
    bars.push({x, w});
    x += w + gap;
  }
  return (
    <svg width={236} height={66} style={{display: 'block'}}>
      {bars.map((b) => (
        <rect key={b.x} x={b.x} y={0} width={b.w} height={66} fill="#0f172a" />
      ))}
    </svg>
  );
};

const ProductCard: React.FC<{k: number; ledX: number; ledY: number}> = ({k, ledX, ledY}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const local = frame - scanStart(k);
  if (local < 0 || local > CARD_GONE) return null;

  const enter = spring({frame: local, fps, config: {damping: 17, mass: 0.5}});
  const exit = interpolate(local, [CARD_EXIT, CARD_GONE], [0, 1], {...clamp, easing: Easing.in(Easing.cubic)});
  const top = CARD.top + (1 - enter) * 280 + exit * 280;
  const left = ledX - CARD.width / 2;
  const read = local >= SCAN_HIT;
  const laser = local >= LASER_ON && local < SCAN_HIT + 2;
  const beamY = top + 100;
  const flicker = 0.75 + 0.25 * Math.sin(local * 2.3);
  const okPulse = interpolate(local, [SCAN_HIT, SCAN_HIT + 5, SCAN_HIT + 24], [0, 1, 0], clamp);

  return (
    <>
      {laser ? (
        <svg width={1920} height={1080} style={{position: 'absolute', left: 0, top: 0}}>
          <defs>
            <linearGradient id={`beam-${k}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor={color.red} stopOpacity={0.5 * flicker} />
              <stop offset="1" stopColor={color.red} stopOpacity={0.1} />
            </linearGradient>
          </defs>
          <polygon
            points={`${ledX - 26},${ledY + 14} ${ledX + 26},${ledY + 14} ${left + CARD.width - 22},${beamY} ${left + 22},${beamY}`}
            fill={`url(#beam-${k})`}
          />
        </svg>
      ) : null}
      <div
        style={{
          position: 'absolute',
          left,
          top,
          width: CARD.width,
          height: CARD.height,
          borderRadius: 18,
          background: '#fff',
          border: `1.5px solid ${color.panelBorder}`,
          boxShadow: `${shadow.lifted}, 0 0 0 ${4 * okPulse}px ${color.led}, 0 0 ${44 * okPulse}px ${color.led}`,
          transform: `rotate(${-3 + (1 - enter) * -6}deg)`,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 8,
        }}
      >
        <div style={{fontWeight: 700, fontSize: 22, color: '#0f172a'}}>{SCANS[k].name}</div>
        <div style={{position: 'relative'}}>
          <Barcode code={SCANS[k].code} />
          {laser ? (
            <div
              style={{
                position: 'absolute',
                left: -18,
                right: -18,
                top: 30,
                height: 4,
                borderRadius: 2,
                background: color.red,
                opacity: flicker,
                boxShadow: `0 0 18px 4px ${color.red}`,
              }}
            />
          ) : null}
        </div>
        <div style={{fontFamily: font.mono, fontWeight: 500, fontSize: 17, letterSpacing: 2, color: '#334155'}}>
          {SCANS[k].code}
        </div>
        {read ? (
          <div
            style={{
              position: 'absolute',
              right: -16,
              top: -16,
              width: 48,
              height: 48,
              borderRadius: 24,
              background: color.led,
              transform: `scale(${spring({frame: local - SCAN_HIT, fps, config: {damping: 12, mass: 0.4}})})`,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <svg width={26} height={26} viewBox="0 0 24 24" fill="none" stroke="#052e16" strokeWidth={3.5} strokeLinecap="round" strokeLinejoin="round">
              <path d="M5 12.5l4.5 4.5L19 7.5" />
            </svg>
          </div>
        ) : null}
      </div>
    </>
  );
};

export const ScanDemo: React.FC = () => {
  const frame = useCurrentFrame();
  const float = Math.sin(frame / 34) * 5;
  const scanner = deviceAnchors(DEVICE.size).scanner;
  const ledX = DEVICE.left + scanner.x;
  const ledY = DEVICE.top + float + scanner.y;

  let step = 0;
  for (let i = 1; i < STEPS.length; i++) {
    if (frame >= stepStart(i)) step = i;
  }

  const flash = SCANS.reduce(
    (max, _, k) => Math.max(max, interpolate(frame - scanHit(k), [0, 2, 16], [0, 1, 0], clamp)),
    0,
  );

  return (
    <>
      <Device size={DEVICE.size} flash={flash} style={{left: DEVICE.left, top: DEVICE.top + float}}>
        <Shot name="app-idle.png" />
        {SCANS.map((s, k) => (
          <PopShot key={k} name={s.shot} from={scanHit(k)} />
        ))}
      </Device>

      {SCANS.map((_, k) => (
        <ProductCard key={k} k={k} ledX={ledX} ledY={ledY} />
      ))}

      <div style={{position: 'absolute', left: 900, top: 262, width: 900}}>
        <Rise delay={4} x={40} y={0}>
          <Kicker tone={color.violet}>On the sales floor</Kicker>
        </Rise>

        <div style={{position: 'relative', height: 340, marginTop: 30}}>
          {STEPS.map((s, i) => {
            const start = stepStart(i);
            const end = i + 1 < STEPS.length ? stepStart(i + 1) : Infinity;
            if (frame < start - 1 || frame > end) return null;
            const fadeIn = interpolate(frame, [start + (i === 0 ? 6 : 1), start + (i === 0 ? 16 : 9)], [0, 1], {
              ...clamp,
              easing: Easing.out(Easing.cubic),
            });
            const fadeOut = Number.isFinite(end) ? interpolate(frame, [end - 6, end - 1], [1, 0], clamp) : 1;
            return (
              <div
                key={i}
                style={{
                  position: 'absolute',
                  inset: 0,
                  opacity: fadeIn * fadeOut,
                  transform: `translateX(${(1 - fadeIn) * 50}px)`,
                }}
              >
                <div style={{fontFamily: font.display, fontWeight: 700, fontSize: 84, lineHeight: 1.08, letterSpacing: -2.5}}>
                  {s.title}
                </div>
                <div style={{marginTop: 26, fontSize: 38, lineHeight: 1.4, color: color.muted}}>{s.body}</div>
              </div>
            );
          })}
        </div>

        <Rise delay={12} style={{display: 'flex', flexWrap: 'wrap', gap: 14, marginTop: 30, width: 900}}>
          {STEPS.map((s, i) => {
            const active = i === step;
            const done = i < step;
            return (
              <div
                key={i}
                style={{
                  padding: '14px 26px',
                  borderRadius: 999,
                  fontSize: 26,
                  fontWeight: 600,
                  whiteSpace: 'nowrap',
                  background: active ? color.yellow : color.panel,
                  color: active ? '#1c1503' : done ? color.text : color.faint,
                  border: `1.5px solid ${active ? '#eab308' : color.panelBorder}`,
                  boxShadow: active ? '0 10px 30px rgba(234,179,8,0.35)' : 'none',
                }}
              >
                {s.pill}
              </div>
            );
          })}
        </Rise>
      </div>
    </>
  );
};
