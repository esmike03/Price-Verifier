import React from 'react';
import {interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import {Device, Shot, deviceAnchors} from '../Device';
import {color, font, shadow} from '../theme';
import {Kicker, Rise, clamp} from '../ui';

type Point = {x: number; y: number};

const SERVER = {left: 110, top: 350, width: 540, height: 250};
const ORIGIN: Point = {x: SERVER.left + SERVER.width, y: SERVER.top + SERVER.height / 2};

const DEVICE_SIZE = 380;
const ANCHORS = deviceAnchors(DEVICE_SIZE);
const TERMINALS = [
  {left: 1300, top: 14, shot: 'app-idle.png'},
  {left: 1470, top: 346, shot: 'app-price.png'},
  {left: 1300, top: 678, shot: 'app-promo.png'},
];
// Where a sync line meets each terminal: the left edge of its housing.
const target = (t: {left: number; top: number}): Point => ({
  x: t.left + ANCHORS.left.x - 10,
  y: t.top + ANCHORS.left.y,
});

const FILES = ['catalog.csv', 'promos.csv', 'design.json', 'media', 'app.apk'];
const FIRST_PACKET = 22;
const PACKET_GAP = 12;
const PACKET_TRAVEL = 22;
const TERMINAL_LAG = 3;

const bezier = (a: Point, b: Point, t: number): Point => {
  const c1 = {x: a.x + 300, y: a.y};
  const c2 = {x: b.x - 300, y: b.y};
  const u = 1 - t;
  return {
    x: u ** 3 * a.x + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t ** 3 * b.x,
    y: u ** 3 * a.y + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t ** 3 * b.y,
  };
};

const pathD = (a: Point, b: Point) => `M ${a.x} ${a.y} C ${a.x + 300} ${a.y}, ${b.x - 300} ${b.y}, ${b.x} ${b.y}`;

const FACTS = ['Check-in every 5 seconds', 'Push a sync from the browser', 'Daily auto-sync'];

export const Sync: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();

  return (
    <>
      <div style={{position: 'absolute', left: 110, top: 96}}>
        <Rise delay={3} x={-40} y={0}>
          <Kicker tone={color.green}>Sync</Kicker>
        </Rise>
        <Rise delay={6} x={-50} y={0} style={{fontFamily: font.display, fontWeight: 700, fontSize: 78, letterSpacing: -2.5, marginTop: 20, whiteSpace: 'nowrap'}}>
          One portal. Every terminal.
        </Rise>
      </div>

      <svg width={1920} height={1080} style={{position: 'absolute', left: 0, top: 0}}>
        {TERMINALS.map((t, i) => {
          const draw = interpolate(frame, [10 + i * TERMINAL_LAG, 26 + i * TERMINAL_LAG], [0, 1], clamp);
          return (
            <path
              key={i}
              d={pathD(ORIGIN, target(t))}
              fill="none"
              stroke={color.blue}
              strokeOpacity={0.4}
              strokeWidth={3}
              strokeLinecap="round"
              pathLength={1}
              strokeDasharray={`${draw} 1`}
            />
          );
        })}
      </svg>

      {TERMINALS.map((t, i) =>
        FILES.map((file, j) => {
          const local = frame - (FIRST_PACKET + j * PACKET_GAP + i * TERMINAL_LAG);
          if (local < 0 || local > PACKET_TRAVEL) return null;
          const p = bezier(ORIGIN, target(t), local / PACKET_TRAVEL);
          const fade = interpolate(local, [0, 3, PACKET_TRAVEL - 4, PACKET_TRAVEL], [0, 1, 1, 0], clamp);
          return (
            <div
              key={`${i}-${j}`}
              style={{
                position: 'absolute',
                left: p.x,
                top: p.y,
                transform: 'translate(-50%, -50%)',
                opacity: fade,
                padding: '8px 18px',
                borderRadius: 999,
                background: color.blue,
                color: '#fff',
                fontFamily: font.mono,
                fontWeight: 700,
                fontSize: 19,
                whiteSpace: 'nowrap',
                boxShadow: '0 8px 22px rgba(37,99,235,0.4)',
              }}
            >
              {file}
            </div>
          );
        }),
      )}

      <Rise
        delay={6}
        x={-60}
        y={0}
        style={{
          position: 'absolute',
          left: SERVER.left,
          top: SERVER.top,
          width: SERVER.width,
          height: SERVER.height,
          boxSizing: 'border-box',
          padding: '34px 38px',
          borderRadius: 28,
          background: color.panel,
          border: `1.5px solid ${color.panelBorder}`,
          boxShadow: shadow.lifted,
        }}
      >
        <div style={{display: 'flex', alignItems: 'center', gap: 20}}>
          <div style={{width: 68, height: 68, borderRadius: 18, background: color.portalBlue, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
            <svg width={36} height={36} viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
              <rect x={3} y={4} width={18} height={7} rx={2} />
              <rect x={3} y={13} width={18} height={7} rx={2} />
            </svg>
          </div>
          <div>
            <div style={{fontFamily: font.display, fontWeight: 700, fontSize: 36, letterSpacing: -0.5}}>Price Verifier Server</div>
            <div style={{marginTop: 4, fontSize: 24, color: color.muted}}>Web portal on your own network</div>
          </div>
        </div>
        <div style={{marginTop: 30, padding: '16px 22px', borderRadius: 14, background: '#eef3ff', fontFamily: font.mono, fontWeight: 500, fontSize: 25, color: color.blue}}>
          http://192.168.0.130:8000
        </div>
      </Rise>

      {TERMINALS.map((t, i) => {
        const enter = spring({frame: frame - (6 + i * TERMINAL_LAG), fps, config: {damping: 200, mass: 0.5}});
        const firstHit = frame - (FIRST_PACKET + i * TERMINAL_LAG + PACKET_TRAVEL);
        const synced = firstHit >= (FILES.length - 1) * PACKET_GAP;
        const receiving = firstHit >= 0 && !synced;
        return (
          <React.Fragment key={i}>
            <Device
              size={DEVICE_SIZE}
              style={{left: t.left + (1 - enter) * 140, top: t.top, opacity: enter}}
              flash={receiving ? interpolate(firstHit % PACKET_GAP, [0, 2, 10], [0, 0.8, 0], clamp) : 0}
            >
              <Shot name={t.shot} />
            </Device>
            <div
              style={{
                position: 'absolute',
                left: t.left + ANCHORS.bottom.x - 60,
                top: t.top + ANCHORS.bottom.y + 14,
                opacity: enter,
                padding: '8px 18px',
                borderRadius: 999,
                background: synced ? color.green : color.panel,
                color: synced ? '#fff' : color.muted,
                border: `1.5px solid ${synced ? color.green : color.panelBorder}`,
                boxShadow: shadow.card,
                fontSize: 21,
                fontWeight: 600,
                whiteSpace: 'nowrap',
              }}
            >
              {synced ? 'Up to date' : 'Online'}
            </div>
          </React.Fragment>
        );
      })}

      <div style={{position: 'absolute', left: 110, top: 680, width: 760, display: 'flex', flexDirection: 'column', gap: 26}}>
        {FACTS.map((fact, i) => (
          <Rise key={i} delay={18 + i * 12} x={-40} y={0} style={{display: 'flex', gap: 20, alignItems: 'center'}}>
            <svg width={34} height={34} viewBox="0 0 24 24" fill="none" stroke={color.green} strokeWidth={3} strokeLinecap="round" strokeLinejoin="round">
              <path d="M5 12.5l4.5 4.5L19 7.5" />
            </svg>
            <div style={{fontSize: 38, fontWeight: 700}}>{fact}</div>
          </Rise>
        ))}
      </div>
    </>
  );
};
