import React from 'react';
import {interpolate, useCurrentFrame} from 'remotion';
import {color, font, shadow} from '../theme';
import {Chip, Kicker, Rise, clamp} from '../ui';

// A stylised installer run. The steps mirror server-installer/README-install.txt.
const LINES: {at: number; text: string; tone?: string; ok?: boolean; typed?: boolean}[] = [
  {at: 10, text: 'install.cmd', typed: true},
  {at: 26, text: 'Port [8000]:', tone: color.muted},
  {at: 33, text: 'Installing to C:\\PriceVerifier', tone: color.muted},
  {at: 41, text: 'Starts automatically at boot', ok: true},
  {at: 48, text: 'Firewall: local network only', ok: true},
  {at: 55, text: 'Existing data kept on upgrade', ok: true},
  {at: 64, text: 'Enter this address on the scanners:', tone: color.muted},
  {at: 69, text: 'http://192.168.0.130:8000', tone: color.blue},
];
const TYPE_FRAMES = 12;

const TARGETS = [
  {name: 'Windows 10 / 11 / Server', body: 'Double-click install.cmd.'},
  {name: 'Linux', body: 'One install.sh, runs as a service.'},
  {name: 'cPanel / Apache hosting', body: 'Or upload the portal folder.'},
];

export const Install: React.FC = () => {
  const frame = useCurrentFrame();

  return (
    <>
      <div style={{position: 'absolute', left: 110, top: 110, width: 900}}>
        <Rise delay={3} x={-40} y={0}>
          <Kicker tone={color.violet}>Server setup</Kicker>
        </Rise>
        <Rise delay={6} x={-50} y={0} style={{fontFamily: font.display, fontWeight: 700, fontSize: 88, letterSpacing: -3, marginTop: 20, whiteSpace: 'nowrap'}}>
          Up in minutes.
        </Rise>
        <Rise delay={10} x={-40} y={0} style={{fontSize: 36, color: color.muted, marginTop: 16}}>
          Offline installer. Nothing else to download.
        </Rise>
      </div>

      <Rise
        delay={8}
        y={50}
        style={{
          position: 'absolute',
          left: 110,
          top: 430,
          width: 880,
          height: 500,
          borderRadius: 20,
          overflow: 'hidden',
          background: color.panel,
          border: `1.5px solid ${color.panelBorder}`,
          boxShadow: shadow.lifted,
        }}
      >
        <div style={{height: 52, background: '#e4e9f4', display: 'flex', alignItems: 'center', padding: '0 24px', fontSize: 19, fontWeight: 600, color: color.muted}}>
          Price Verifier Server installer
        </div>
        <div style={{padding: '26px 32px', fontFamily: font.mono, fontWeight: 500, fontSize: 27, lineHeight: 1.72}}>
          {LINES.map((line, i) => {
            if (frame < line.at) return null;
            const chars = line.typed ? Math.floor(interpolate(frame, [line.at, line.at + TYPE_FRAMES], [0, line.text.length], clamp)) : line.text.length;
            const caret = line.typed && frame < line.at + TYPE_FRAMES + 4;
            return (
              <div key={i} style={{display: 'flex', alignItems: 'center', gap: 16, color: line.tone ?? color.text, opacity: interpolate(frame, [line.at, line.at + 4], [0, 1], clamp)}}>
                {line.typed ? <span style={{color: color.green, fontWeight: 700}}>&gt;</span> : null}
                {line.ok ? <span style={{color: color.green, fontWeight: 700}}>OK</span> : null}
                <span style={{fontWeight: line.tone === color.blue ? 700 : 500}}>
                  {line.text.slice(0, chars)}
                  {caret ? <span style={{background: color.text, marginLeft: 2}}>&nbsp;</span> : null}
                </span>
              </div>
            );
          })}
        </div>
      </Rise>

      <div style={{position: 'absolute', left: 1080, top: 262, width: 730, display: 'flex', flexDirection: 'column', gap: 24}}>
        {TARGETS.map((target, i) => (
          <Rise
            key={i}
            delay={12 + i * 6}
            x={70}
            y={0}
            style={{
              padding: '32px 36px',
              borderRadius: 24,
              background: color.panel,
              border: `1.5px solid ${color.panelBorder}`,
              boxShadow: shadow.card,
            }}
          >
            <div style={{fontFamily: font.display, fontWeight: 700, fontSize: 38, letterSpacing: -0.5}}>{target.name}</div>
            <div style={{marginTop: 10, fontSize: 29, lineHeight: 1.38, color: color.muted}}>{target.body}</div>
          </Rise>
        ))}
        <div style={{display: 'flex', flexWrap: 'wrap', gap: 14, marginTop: 18}}>
          <Rise delay={36}>
            <Chip>No internet needed</Chip>
          </Rise>
          <Rise delay={40}>
            <Chip>LAN-only by default</Chip>
          </Rise>
        </div>
      </div>
    </>
  );
};
