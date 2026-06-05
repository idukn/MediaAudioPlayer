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

  function extensionOf(filePath) {
    const match = String(filePath || '').toLowerCase().match(/\.([a-z0-9]+)$/);
    return match ? match[1] : '';
  }

  /**
   * ローカルファイル再生は常にプラグインの /audio サーバを優先する。
   * convertFileSrc (_capacitor_file_) はファイル名に特殊文字・絵文字を含むと
   * mediaError=4 になる端末があるため、サーバ経由 (Content-Type + Range) のほうが安定。
   */
  function buildLocalAudioUrl(fullPath) {
    if (!fullPath) {
      return fullPath;
    }
    if (cachedAudioServerPort > 0) {
      return `http://127.0.0.1:${cachedAudioServerPort}/audio?path=${encodeURIComponent(fullPath)}&t=${Date.now()}`;
    }
    const capacitor = window.Capacitor;
    if (capacitor?.convertFileSrc) {
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
    getMediaToolsDiagnostics: () => nativeCall('getMediaToolsDiagnostics'),
    requestTermuxRunPermission: () => nativeCall('requestTermuxRunPermission'),
    openAppPermissionSettings: () => nativeCall('openAppPermissionSettings'),
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
        throw new Error('試聴を開始できませんでした');
      }
      return `http://127.0.0.1:${port}/stream?url=${encodeURIComponent(url)}`;
    },
    preparePreviewStream: async (payload) => {
      const port = await window.api.getAudioServerPort();
      const pageUrl = String(payload?.url || payload?.webpageUrl || '').trim();
      if (!port || !pageUrl) {
        throw new Error('試聴を開始できませんでした');
      }
      if (window.Capacitor?.getPlatform?.() === 'android' && typeof window.api.requestTermuxRunPermission === 'function') {
        try {
          await window.api.requestTermuxRunPermission();
        } catch (permError) {
          console.warn('[Preview] Termux permission request:', permError);
        }
      }
      console.log('[Preview] prepare stream:', pageUrl);
      const prepareUrl = `http://127.0.0.1:${port}/stream/prepare?url=${encodeURIComponent(pageUrl)}`;
      const response = await fetch(prepareUrl);
      let data = null;
      try {
        data = await response.json();
      } catch {
        const text = await response.text().catch(() => '');
        throw new Error(text || `サーバーエラー (HTTP ${response.status})`);
      }
      if (!data?.ok) {
        throw new Error(data?.error || `サーバーエラー (HTTP ${response.status})`);
      }
      if (data.cachePath && window.Capacitor?.convertFileSrc) {
        return window.Capacitor.convertFileSrc(data.cachePath);
      }
      if (data.playUrl) {
        return data.playUrl;
      }
      throw new Error('ストリーム準備の応答が不正です');
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
    resolveLibraryPath: async (payload) => {
      const res = await nativeCall('resolveLibraryPath', payload || {});
      return {
        found: Boolean(res.found),
        path: res.path || '',
      };
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
