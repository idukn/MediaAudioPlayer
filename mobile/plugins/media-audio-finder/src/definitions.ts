export interface MediaAudioFinderPlugin {
  getLibraryDir(): Promise<{ path: string }>;
  getPlatformInfo(): Promise<{
    platform: string;
    platformLabel: string;
    fileManagerLabel: string;
    libraryDir: string;
  }>;
  listAudio(options: { saveDir: string }): Promise<{ files: Array<Record<string, unknown>> }>;
  getPlaylists(): Promise<{ playlists: unknown[] }>;
  createPlaylist(options: { name: string }): Promise<Record<string, unknown>>;
  addItemsToPlaylist(options: { playlistId: string; items: unknown[] }): Promise<Record<string, unknown>>;
  reorderPlaylistItems(options: { playlistId: string; fromIndex: number; toIndex: number }): Promise<Record<string, unknown>>;
  removePlaylistItems(options: { playlistId: string; itemIndexes: number[] }): Promise<Record<string, unknown>>;
  createFolder(options: { parentDir: string; name: string }): Promise<Record<string, unknown>>;
  createFolderAt(options: { parentDir: string; name: string }): Promise<Record<string, unknown>>;
  downloadAudio(options: { url: string; saveDir: string; audioFormat?: string }): Promise<Record<string, unknown>>;
  searchVideos(options: Record<string, unknown>): Promise<Record<string, unknown>>;
  chooseDownloadDir(): Promise<{ path: string | null }>;
  openInFinder(options: { filePath: string }): Promise<void>;
  openExternal(options: { url: string }): Promise<void>;
  writeClipboardText(options: { text: string }): Promise<void>;
  getAudioServerPort(): Promise<{ port: number }>;
  getMediaServerConfig(): Promise<{
    port: number;
    androidLibraryRoot: string;
    vmLibraryRoot: string;
  }>;
  getMediaToolsDiagnostics(): Promise<Record<string, unknown>>;
  syncthingGetInfo(): Promise<Record<string, unknown>>;
  syncthingAddDevice(options: { deviceID: string }): Promise<Record<string, unknown>>;
}
