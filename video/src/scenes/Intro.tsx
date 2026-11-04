import React from 'react';
import {Easing, interpolate, useCurrentFrame} from 'remotion';
import {Device, Shot} from '../Device';
import {color, font} from '../theme';
import {Chip, Kicker, Rise, clamp, gradientText} from '../ui';

export const Intro: React.FC = () => {
  const frame = useCurrentFrame();
  const slide = interpolate(frame, [0, 40], [1, 0], {...clamp, easing: Easing.out(Easing.cubic)});
  const float = Math.sin(frame / 30) * 6;

  return (
    <>
      <div style={{position: 'absolute', left: 120, top: 0, bottom: 0, width: 860, display: 'flex', flexDirection: 'column', justifyContent: 'center'}}>
        <Rise delay={4} x={-40} y={0}>
          <Kicker>Price-check terminal + web portal</Kicker>
        </Rise>
        <div style={{fontFamily: font.display, fontWeight: 800, fontSize: 156, lineHeight: 1.02, letterSpacing: -5, marginTop: 30}}>
          <Rise delay={8} x={-60} y={0}>
            Price
          </Rise>
          <Rise delay={12} x={-60} y={0}>
            <span style={gradientText(color.blue, color.violet)}>Verifier</span>
          </Rise>
        </div>
        <Rise delay={20} x={-40} y={0} style={{marginTop: 34, fontSize: 44, fontWeight: 500, color: color.muted, lineHeight: 1.3}}>
          Scan it. See the price. Keep shopping.
        </Rise>
        <div style={{display: 'flex', gap: 16, marginTop: 52}}>
          <Rise delay={30}>
            <Chip>Android terminal app</Chip>
          </Rise>
          <Rise delay={34}>
            <Chip>Web portal &amp; server</Chip>
          </Rise>
        </div>
      </div>

      <Device
        size={1150}
        style={{
          left: 767 + slide * 420,
          top: -20 + float,
          opacity: interpolate(frame, [0, 10], [0, 1], clamp),
        }}
      >
        <Shot name="app-idle.png" />
      </Device>
    </>
  );
};
