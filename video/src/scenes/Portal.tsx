import React from 'react';
import {Easing, Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {color, font, shadow} from '../theme';
import {PORTAL_TAB} from '../timing';
import {Kicker, Rise, clamp} from '../ui';

// Screenshots are 1300px wide; `height` is each file's pixel height.
const SHOT_W = 1300;
const TABS = [
  {
    shot: 'portal-media.png',
    height: 710,
    label: 'Media',
    title: 'Run every screen like signage',
    points: ['Banners, video and a scrolling message', 'Scheduled media and playback order'],
  },
  {
    shot: 'portal-catalog.png',
    height: 900,
    label: 'Catalog',
    title: 'Your whole catalog, in a browser',
    points: ['Upload CSV, TXT or Excel', 'Search, add and edit items'],
  },
  {
    shot: 'portal-promos.png',
    height: 820,
    label: 'Promos',
    title: 'Promos and special prices',
    points: ['Percent, amount, fixed or buy-and-take', 'Start and end dates'],
  },
  {
    shot: 'portal-design.png',
    height: 1460,
    label: 'Design',
    title: 'One look on every terminal',
    points: ['Colours, logo size and timing', 'Live preview while you edit'],
  },
  {
    shot: 'portal-app-update.png',
    height: 720,
    label: 'App update',
    title: 'Ship app updates from here',
    points: ['Upload the APK with release notes', 'Terminals prompt to install'],
  },
];

const FRAME = {left: 660, top: 150, width: 1180, chrome: 56, viewport: 700};
const SHOT_SCALE = FRAME.width / SHOT_W;

export const portalTabStart = (i: number) => i * PORTAL_TAB;
export const PORTAL_TAB_COUNT = TABS.length;

export const Portal: React.FC = () => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const active = Math.min(TABS.length - 1, Math.floor(frame / PORTAL_TAB));
  const enter = spring({frame: frame - 2, fps, config: {damping: 200, mass: 0.5}});

  return (
    <>
      <div style={{position: 'absolute', left: 100, top: 150, width: 500}}>
        <Rise delay={3} x={-40} y={0}>
          <Kicker>Web portal</Kicker>
        </Rise>

        <div style={{position: 'relative', height: 640, marginTop: 30}}>
          {TABS.map((tab, i) => {
            const start = portalTabStart(i);
            const end = start + PORTAL_TAB;
            if (frame < start - 1 || frame > end + 1) return null;
            const fadeIn = interpolate(frame, [start + (i === 0 ? 5 : 1), start + (i === 0 ? 14 : 9)], [0, 1], {
              ...clamp,
              easing: Easing.out(Easing.cubic),
            });
            const fadeOut = i === TABS.length - 1 ? 1 : interpolate(frame, [end - 6, end - 1], [1, 0], clamp);
            return (
              <div
                key={i}
                style={{
                  position: 'absolute',
                  inset: 0,
                  display: 'flex',
                  flexDirection: 'column',
                  justifyContent: 'center',
                  opacity: fadeIn * fadeOut,
                  transform: `translateX(${(1 - fadeIn) * -50}px)`,
                }}
              >
                <div style={{fontFamily: font.display, fontWeight: 700, fontSize: 64, lineHeight: 1.1, letterSpacing: -2}}>
                  {tab.title}
                </div>
                <div style={{marginTop: 34, display: 'flex', flexDirection: 'column', gap: 18}}>
                  {tab.points.map((point, j) => {
                    const p = interpolate(frame, [start + 7 + j * 4, start + 15 + j * 4], [0, 1], clamp);
                    return (
                      <div key={j} style={{fontSize: 34, lineHeight: 1.3, color: color.muted, opacity: p, transform: `translateX(${(1 - p) * -24}px)`}}>
                        {point}
                      </div>
                    );
                  })}
                </div>
              </div>
            );
          })}
        </div>

        <Rise delay={8} style={{display: 'flex', gap: 10}}>
          {TABS.map((tab, i) => {
            const fill = interpolate(frame, [portalTabStart(i), portalTabStart(i) + PORTAL_TAB], [0, 1], clamp);
            return (
              <div key={i} style={{flex: 1}}>
                <div style={{height: 6, borderRadius: 3, background: 'rgba(15,27,70,0.12)', overflow: 'hidden'}}>
                  <div style={{width: `${fill * 100}%`, height: '100%', background: color.blue}} />
                </div>
                <div style={{marginTop: 12, fontSize: 19, fontWeight: 600, whiteSpace: 'nowrap', color: i === active ? color.text : color.faint}}>
                  {tab.label}
                </div>
              </div>
            );
          })}
        </Rise>
      </div>

      <div
        style={{
          position: 'absolute',
          left: FRAME.left,
          top: FRAME.top,
          width: FRAME.width,
          opacity: enter,
          transform: `perspective(2600px) rotateY(${-5 - (1 - enter) * 9}deg) translateX(${(1 - enter) * 120}px)`,
          transformOrigin: '100% 50%',
          borderRadius: 18,
          overflow: 'hidden',
          boxShadow: `${shadow.lifted}, 0 0 0 1.5px ${color.panelBorder}`,
        }}
      >
        <div style={{height: FRAME.chrome, background: '#e4e9f4', display: 'flex', alignItems: 'center', padding: '0 14px'}}>
          <div
            style={{
              flex: 1,
              height: 34,
              borderRadius: 17,
              background: '#fff',
              display: 'flex',
              alignItems: 'center',
              padding: '0 18px',
              fontFamily: font.mono,
              fontWeight: 500,
              fontSize: 17,
              color: color.muted,
            }}
          >
            http://192.168.0.130:8000
          </div>
        </div>
        <div style={{position: 'relative', height: FRAME.viewport, background: '#eef1f6', overflow: 'hidden'}}>
          {TABS.map((tab, i) => {
            const start = portalTabStart(i);
            const local = frame - start;
            if (local < 0 || (i < TABS.length - 1 && local > PORTAL_TAB + 8)) return null;
            const shown = tab.height * SHOT_SCALE;
            const overflow = Math.max(0, shown - FRAME.viewport);
            const ease = {...clamp, easing: Easing.inOut(Easing.cubic)};
            const scroll = interpolate(local, [12, 52], [0, overflow], ease);
            return (
              <Img
                key={i}
                src={staticFile(`shots/${tab.shot}`)}
                style={{
                  position: 'absolute',
                  left: 0,
                  top: -scroll,
                  width: FRAME.width,
                  height: shown,
                  opacity: i === 0 ? 1 : interpolate(local, [0, 7], [0, 1], clamp),
                }}
              />
            );
          })}
        </div>
      </div>
    </>
  );
};
