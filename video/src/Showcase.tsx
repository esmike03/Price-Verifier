import React from 'react';
import {AbsoluteFill, Audio, Sequence, staticFile} from 'remotion';
import {Install} from './scenes/Install';
import {Intro} from './scenes/Intro';
import {Outro} from './scenes/Outro';
import {PORTAL_TAB_COUNT, Portal, portalTabStart} from './scenes/Portal';
import {SCAN_COUNT, ScanDemo, scanHit} from './scenes/ScanDemo';
import {Stats} from './scenes/Stats';
import {Sync} from './scenes/Sync';
import {SCENES, SceneId, XFADE} from './timing';
import {Background, Scene} from './ui';
import {VOICE_LINES, VOICE_VOLUME} from './voiceover';

const ORDER: SceneId[] = ['intro', 'scan', 'stats', 'portal', 'sync', 'install', 'outro'];

const content: Record<SceneId, React.ReactNode> = {
  intro: <Intro />,
  scan: <ScanDemo />,
  stats: <Stats />,
  portal: <Portal />,
  sync: <Sync />,
  install: <Install />,
  outro: <Outro duration={SCENES.outro.duration} />,
};

// The music sits lower under narration.
const MUSIC_VOLUME = 0.62;
const MUSIC_VOLUME_UNDER_VOICE = 0.18;
// Sound effects are pulled back too so they do not compete with speech.
const SFX_UNDER_VOICE = 0.6;

export const Showcase: React.FC<{music: boolean; voice: boolean}> = ({music, voice}) => (
  <AbsoluteFill>
    <Background />

    {ORDER.map((id, i) => {
      const {from, duration} = SCENES[id];
      const last = i === ORDER.length - 1;
      return (
        <Sequence key={id} name={id} from={from} durationInFrames={last ? duration : duration + XFADE}>
          <Scene duration={duration} first={i === 0} last={last}>
            {content[id]}
          </Scene>
        </Sequence>
      );
    })}

    {music ? (
      <Audio src={staticFile('audio/music.wav')} volume={voice ? MUSIC_VOLUME_UNDER_VOICE : MUSIC_VOLUME} />
    ) : null}

    {voice
      ? VOICE_LINES.map(({scene, lead}) => (
          <Sequence key={scene} name={`voice-${scene}`} from={SCENES[scene].from + lead}>
            <Audio src={staticFile(`audio/voice/${scene}.mp3`)} volume={VOICE_VOLUME} />
          </Sequence>
        ))
      : null}

    {ORDER.slice(1).map((id) => (
      <Sequence key={id} name={`whoosh-${id}`} from={SCENES[id].from - 8} durationInFrames={30}>
        <Audio src={staticFile('audio/whoosh.wav')} volume={0.32 * (voice ? SFX_UNDER_VOICE : 1)} />
      </Sequence>
    ))}

    {Array.from({length: SCAN_COUNT}, (_, k) => (
      <Sequence key={k} name={`beep-${k}`} from={SCENES.scan.from + scanHit(k)} durationInFrames={12}>
        <Audio src={staticFile('audio/beep.wav')} volume={0.5 * (voice ? SFX_UNDER_VOICE : 1)} />
      </Sequence>
    ))}

    {Array.from({length: PORTAL_TAB_COUNT - 1}, (_, i) => (
      <Sequence key={i} name={`pop-${i}`} from={SCENES.portal.from + portalTabStart(i + 1)} durationInFrames={10}>
        <Audio src={staticFile('audio/pop.wav')} volume={0.4 * (voice ? SFX_UNDER_VOICE : 1)} />
      </Sequence>
    ))}
  </AbsoluteFill>
);
