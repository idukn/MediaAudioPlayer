(function initCapacitorApi() {
  const noopUnsubscribe = () => {};
  let cachedAudioServerPort = 0;

  function nativePlugin() {
    const plugins = window.Capacitor?.Plugins;
    return plugins?.MediaAudioFinder || null;
  }

  async function nativeCall(method, payload = {}) {
    const plugin = nativePlugin();
    if (!plugin || typeof plugin[method] !== 'function') {
      throw new Error(`Native API not available: ${method}`);
    }
    return plugin[method](payload);
  }

  function buildLocalAudioUrl(fullPath) {
    if (cachedAudioServerPort > 0 && fullPath) {
      return `http://127.0.0.1:${cachedAudioServerPort}/audio?path=${encodeURIComponent(fullPath)}&t=${Date.now()}`;
    }
    const capacitor = window.Capacitor;
    if (capacitor?.convertFileSrc && fullPath) {
      return capacitor.convertFileSrc(fullPath);
    }
    return fullPath;
  }

  window.api = {
    getLibraryDir: async () => {
      const res = await nativeCall('getLibraryDir');
      return res.path;
    },
    getPlatformInfo: () => nativeCall('getPlatformInfo'),
    chooseDownloadDir: async () => {
      const res = await nativeCall('chooseDownloadDir');
      return res.path || null;
    },
    searchVideos: async (payload) => {
      const res = await nativeCall('searchVideos', payload);
      return Array.isArray(res.results) ? res.results : [];
    },
    getPreviewStreamUrl: async (payload) => {
      const port = await window.api.getAudioServerPort();
      const url = payload?.url || payload?.webpageUrl;
      if (!port || !url) {
        throw new Error('試聴ストリームを開始できませんでした');
      }
      return `http://127.0.0.1:${port}/stream?url=${encodeURIComponent(url)}`;
    },
    downloadAudio: (payload) => nativeCall('downloadAudio', payload),
    listAudio: async (payload) => {
      const res = await nativeCall('listAudio', payload);
      return Array.isArray(res.files) ? res.files : [];
    },
    createFolder: (payload) => nativeCall('createFolder', payload),
    createFolderAt: (payload) => nativeCall('createFolderAt', payload),
    moveToNewFolder: async () => {
      throw new Error('Android版ではフォルダ移動は未対応です');
    },
    openInFinder: (payload) => nativeCall('openInFinder', payload),
    openExternal: (payload) => nativeCall('openExternal', payload),
    writeClipboardText: (payload) => nativeCall('writeClipboardText', payload),
    getPlaylists: async () => {
      const res = await nativeCall('getPlaylists');
      return Array.isArray(res.playlists) ? res.playlists : [];
    },
    createPlaylist: (payload) => nativeCall('createPlaylist', payload),
    addItemsToPlaylist: (payload) => nativeCall('addItemsToPlaylist', payload),
    reorderPlaylistItems: (payload) => nativeCall('reorderPlaylistItems', payload),
    removePlaylistItems: (payload) => nativeCall('removePlaylistItems', payload),
    getAudioServerPort: async () => {
      const res = await nativeCall('getAudioServerPort');
      cachedAudioServerPort = Number(res.port) || 0;
      return cachedAudioServerPort;
    },
    getStreamLogPath: async () => '(mobile)',
    buildLocalAudioUrl,
    onSearchMetadataUpdated: () => noopUnsubscribe,
    onSearchEnrichmentStatus: () => noopUnsubscribe,
    saveUIState: async () => ({ ok: true }),
    loadUIState: async () => null,
    clearCache: async () => ({ ok: true }),
    syncthingGetInfo: () => nativeCall('syncthingGetInfo'),
    syncthingAddDevice: (payload) => nativeCall('syncthingAddDevice', payload),
    onSyncUpdated: (listener) => {
      const plugin = nativePlugin();
      if (!plugin?.addListener) {
        return noopUnsubscribe;
      }
      const handle = plugin.addListener('syncUpdated', (event) => {
        listener(event);
      });
      return () => {
        if (handle?.remove) {
          handle.remove();
        }
      };
    },
  };
})();
