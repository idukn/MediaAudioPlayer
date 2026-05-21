import { registerPlugin } from '@capacitor/core';
import type { MediaAudioFinderPlugin } from './definitions';

const MediaAudioFinder = registerPlugin<MediaAudioFinderPlugin>('MediaAudioFinder');

export * from './definitions';
export { MediaAudioFinder };
