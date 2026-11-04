import React from 'react';
import {Easing, interpolate, useCurrentFrame} from 'remotion';
import {color, font, shadow} from '../theme';
import {Kicker, Rise, clamp, gradientText} from '../ui';

// Product count shown in the portal's Catalog tab (docs/guide/img/portal-catalog.png).
const CATALOG_SIZE = 333934;

const Tile: React.FC<{delay: number; value: React.ReactNode; label: string; tones: [string, string]}> = ({
  delay,
  value,
  label,
  tones,
}) => (
  <Rise
    delay={delay}
    y={50}
    style={{
      flex: 1,
      padding: '44px 38px 46px',
      borderRadius: 28,
      background: color.panel,
      border: `1.5px solid ${color.panelBorder}`,
      boxShadow: shadow.card,
    }}
  >
    <div style={{fontFamily: font.display, fontWeight: 800, fontSize: 68, letterSpacing: -2, lineHeight: 1.1, whiteSpace: 'nowrap', ...gradientText(...tones)}}>
      {value}
    </div>
    <div style={{marginTop: 18, fontSize: 31, lineHeight: 1.35, color: color.muted}}>{label}</div>
  </Rise>
);

export const Stats: React.FC = () => {
  const frame = useCurrentFrame();
  const count = Math.round(
    interpolate(frame, [10, 46], [0, CATALOG_SIZE], {...clamp, easing: Easing.out(Easing.cubic)}),
  );

  return (
    <div style={{position: 'absolute', inset: 0, padding: '0 110px', display: 'flex', flexDirection: 'column', justifyContent: 'center'}}>
      <Rise delay={3} x={-40} y={0}>
        <Kicker tone={color.green}>Under the hood</Kicker>
      </Rise>
      <Rise delay={6} x={-50} y={0} style={{fontFamily: font.display, fontWeight: 700, fontSize: 92, letterSpacing: -3, marginTop: 24}}>
        Built for real store conditions
      </Rise>

      <div style={{display: 'flex', gap: 28, marginTop: 64}}>
        <Tile delay={10} tones={[color.blue, color.cyan]} value={count.toLocaleString('en-US')} label="products in one catalog" />
        <Tile delay={14} tones={[color.green, color.cyan]} value="Offline" label="lookups run on the device" />
        <Tile delay={18} tones={['#d97706', '#ea580c']} value="EAN + UPC" label="barcodes matched automatically" />
        <Tile delay={22} tones={[color.violet, '#db2777']} value="PIN lock" label="admin screen on the device" />
      </div>
    </div>
  );
};
