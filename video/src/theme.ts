import {cancelRender, continueRender, delayRender, staticFile} from 'remotion';

// Fonts ship in public/fonts (variable woff2, SIL Open Font License) so that
// rendering never depends on the network.
const loadLocalFont = (family: string, file: string) => {
  if (typeof document === 'undefined') return family;
  const handle = delayRender(`Loading font ${family}`);
  const face = new FontFace(family, `url('${staticFile(`fonts/${file}`)}') format('woff2')`, {
    weight: '100 900',
  });
  face
    .load()
    .then(() => {
      document.fonts.add(face);
      continueRender(handle);
    })
    .catch((err) => cancelRender(err));
  return family;
};

export const font = {
  display: loadLocalFont('Sora', 'Sora.woff2'),
  body: loadLocalFont('Inter', 'Inter-latin.woff2'),
  mono: loadLocalFont('JetBrains Mono', 'JetBrainsMono.woff2'),
};

export const color = {
  bg: '#eef2fb',
  panel: '#ffffff',
  panelBorder: 'rgba(15,27,70,0.09)',
  text: '#0b1533',
  muted: '#4a587a',
  faint: '#9aa5bf',
  cyan: '#0891b2',
  violet: '#7c3aed',
  blue: '#2563eb',
  portalBlue: '#1766a6',
  yellow: '#fde047',
  red: '#ef4444',
  green: '#059669',
  // Bright green of the scanner's "good read" light.
  led: '#00e676',
};

export const shadow = {
  card: '0 18px 44px rgba(28,45,100,0.12)',
  lifted: '0 36px 80px rgba(28,45,100,0.2)',
};
