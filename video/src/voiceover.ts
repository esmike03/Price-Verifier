import {SceneId} from './timing';

// Narration, one clip per scene, generated with ElevenLabs (voice: Sarah,
// model: Eleven Multilingual v2). Each clip is public/audio/voice/<scene>.mp3.
// `lead` is how many frames into its scene a clip starts; the scan line is
// longer than its scene, so it starts a little before the cut.
export const VOICE_VOLUME = 1;

export const VOICE_LINES: {scene: SceneId; lead: number; text: string}[] = [
  {scene: 'intro', lead: 10, text: 'Meet Price Verifier: a price-check terminal and web portal for your store.'},
  {
    scene: 'scan',
    lead: -12,
    text: 'Shoppers scan a barcode and see the price instantly, even offline. Promos, buy one take one deals and special prices appear automatically.',
  },
  {scene: 'stats', lead: 10, text: 'Hundreds of thousands of products, on every terminal.'},
  {
    scene: 'portal',
    lead: 10,
    text: 'From one web portal, manage display media, your catalog, promos, screen design and app updates.',
  },
  {scene: 'sync', lead: 10, text: 'Every terminal syncs over your own network.'},
  {scene: 'install', lead: 10, text: 'The server installs in minutes, on Windows or Linux.'},
  {scene: 'outro', lead: 10, text: 'Price Verifier. The right price, on every scan.'},
];
