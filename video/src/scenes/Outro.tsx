import React from 'react';
import {AbsoluteFill, Easing, interpolate, useCurrentFrame} from 'remotion';
import {Device, Shot} from '../Device';
import {color, font} from '../theme';
import {Chip, Rise, clamp, gradientText} from '../ui';

const SHOTS = ['app-price.png', 'app-promo.png', 'app-special.png'];
const HOLD = 34;
// Slightly tighter than the default chip so all three fit on one row.
const CHIP = {fontSize: 23, padding: '13px 22px'};

export const Outro: React.FC<{duration: number}> = ({duration}) => {
  const frame = useCurrentFrame();
  const slide = interpolate(frame, [0, 34], [1, 0], {...clamp, easing: Easing.out(Easing.cubic)});
  const float = Math.sin(frame / 30) * 6;

  return (
    <>
      <Device
        size={1100}
        style={{left: -60 - slide * 360, top: 5 + float, opacity: interpolate(frame, [0, 10], [0, 1], clamp)}}
      >
        {SHOTS.map((name, i) => (
          <Shot key={name} name={name} style={{opacity: i === 0 ? 1 : interpolate(frame, [i * HOLD + 10, i * HOLD + 18], [0, 1], clamp)}} />
        ))}
      </Device>

      <div style={{position: 'absolute', left: 930, top: 0, bottom: 0, width: 900, display: 'flex', flexDirection: 'column', justifyContent: 'center'}}>
        <div style={{fontFamily: font.display, fontWeight: 800, fontSize: 132, lineHeight: 1.03, letterSpacing: -4.5}}>
          <Rise delay={6} x={60} y={0}>
            Price
          </Rise>
          <Rise delay={10} x={60} y={0}>
            <span style={gradientText(color.blue, color.violet)}>Verifier</span>
          </Rise>
        </div>
        <Rise delay={18} x={40} y={0} style={{marginTop: 28, fontSize: 44, fontWeight: 500, color: color.muted}}>
          The right price, on every scan.
        </Rise>
        <div style={{display: 'flex', gap: 12, marginTop: 50}}>
          <Rise delay={26}>
            <Chip style={CHIP}>Android terminal app</Chip>
          </Rise>
          <Rise delay={30}>
            <Chip style={CHIP}>Web portal</Chip>
          </Rise>
          <Rise delay={34}>
            <Chip style={CHIP}>Windows &amp; Linux server</Chip>
          </Rise>
        </div>
      </div>

      <AbsoluteFill style={{background: '#fff', opacity: interpolate(frame, [duration - 16, duration - 2], [0, 1], clamp)}} />
    </>
  );
};
