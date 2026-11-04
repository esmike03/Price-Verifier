import React from 'react';
import {Composition} from 'remotion';
import {Showcase} from './Showcase';
import {FPS, HEIGHT, TOTAL_FRAMES, WIDTH} from './timing';

export const Root: React.FC = () => (
  <Composition
    id="Showcase"
    component={Showcase}
    durationInFrames={TOTAL_FRAMES}
    fps={FPS}
    width={WIDTH}
    height={HEIGHT}
    defaultProps={{music: true, voice: true}}
  />
);
