(function initCapacitorApi() {
  const noopUnsubscribe = () => {};
  const DEFAULT_MEDIA_SERVER_PORT = 8765;
  const UI_STATE_KEY = 'media-audio-finder-ui-state';
  let cachedMediaServerPort = DEFAULT_MEDIA_SERVER_PORT;
  let cachedMediaServerConfig = null;
  let pendingUiState = null;
  let saveUiStateTimer = null;

  function writeUiStateNow(state) {
    if (!state) {
      return;
    }
    localStorage.setItem(UI_STATE_KEY, JSON.stringify(state));
    pendingUiState = null;
  }

  async function saveUiState(state) {
    pendingUiState = state;
    if (saveUiStateTimer) {
      clearTimeout(saveUiStateTimer);
    }
    return new Promise((resolve) => {
      saveUiStateTimer = setTimeout(() => {
        saveUiStateTimer = null;
        try {
          writeUiStateNow(pendingUiState);
          resolve({ ok: true });
        } catch (err) {
          resolve({ ok: false, error: err.message });
        }
      }, 250);
    });
  }

  async function flushUiState() {
    if (saveUiStateTimer) {
      clearTimeout(saveUiStateTimer);
      saveUiStateTimer = null;
    }
    try {
      writeUiStateNow(pendingUiState || JSON.parse(localStorage.getItem(UI_STATE_KEY) || 'null'));
      return { ok: true };
    } catch (err) {
      return { ok: false, error: err.message };
    }
  }

  function loadUiState() {
    try {
      const raw = localStorage.getItem(UI_STATE_KEY);
      if (!raw) {
        return null;
      }
      return JSON.parse(raw);
    } catch {
      return null;
    }
  }

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

  async function getMediaServerConfig() {
    if (cachedMediaServerConfig) {
      return cachedMediaServerConfig;
    }
    let nativeConfig = {
      port: DEFAULT_MEDIA_SERVER_PORT,
      androidLibraryRoot: '',
      vmLibraryRoot: '',
    };
    try {
      const res = await nativeCall('getMediaServerConfig');
      nativeConfig = {
        port: Number(res.port) || DEFAULT_MEDIA_SERVER_PORT,
        androidLibraryRoot: String(res.androidLibraryRoot || ''),
        vmLibraryRoot: String(res.vmLibraryRoot || ''),
      };
    } catch {
      // use defaults
    }
    cachedMediaServerPort = nativeConfig.port;
    try {
      const response = await fetch(`${mediaServerBaseUrl(nativeConfig.port)}/health`);
      const health = await response.json();
      if (health?.libraryRoot) {
        nativeConfig.vmLibraryRoot = String(health.libraryRoot);
      }
    } catch {
      // health optional during early boot
    }
    cachedMediaServerConfig = nativeConfig;
    return cachedMediaServerConfig;
  }

  function mediaServerBaseUrl(port) {
    return `http://127.0.0.1:${port || cachedMediaServerPort}`;
  }

  async function toVmPath(androidPath) {
    const path = String(androidPath || '');
    if (!path) {
      return path;
    }
    const cfg = await getMediaServerConfig();
    const androidRoot = cfg.androidLibraryRoot.replace(/\/+$/, '');
    const vmRoot = cfg.vmLibraryRoot.replace(/\/+$/, '');
    if (androidRoot && vmRoot && path.startsWith(androidRoot)) {
      return vmRoot + path.slice(androidRoot.length);
    }
    return path;
  }

  async function fetchMediaServer(path, options = {}) {
    const cfg = await getMediaServerConfig();
    const url = `${mediaServerBaseUrl(cfg.port)}${path}`;
    const response = await fetch(url, options);
    return response;
  }

  async function ensureMediaServerReady() {
    const cfg = await getMediaServerConfig();
    const response = await fetch(`${mediaServerBaseUrl(cfg.port)}/health`);
    let data = null;
    try {
      data = await response.json();
    } catch {
      throw new Error('Debian メディアサーバーに接続できません。Terminal で setup-debian-media-server.sh を実行し、ポート 8765 を転送してください。');
    }
    if (!data?.ok) {
      throw new Error('Debian VM に yt-dlp または ffmpeg がありません。Terminal 内で: sudo apt install yt-dlp ffmpeg');
    }
    return cfg;
  }

  function buildLocalAudioUrl(fullPath) {
    return fullPath;
  }

  const TRANSCODE_EXTS = new Set(['wav', 'flac', 'wma', 'opus']);

  async function buildLocalAudioUrlAsync(fullPath) {
    if (!fullPath) {
      return fullPath;
    }
    const cfg = await getMediaServerConfig();
    const path = String(fullPath);
    const androidRoot = cfg.androidLibraryRoot.replace(/\/+$/, '');

    if (window.Capacitor?.getPlatform?.() === 'android') {
      try {
        const res = await nativeCall('getLocalAudioStreamUrl', { path });
        if (res?.url) {
          return res.url;
        }
      } catch (err) {
        console.warn('[LocalAudio] native HTTP server failed, trying fallback:', err);
      }
    }

    if (androidRoot && path.startsWith(androidRoot) && window.Capacitor?.convertFileSrc) {
      return window.Capacitor.convertFileSrc(path);
    }

    const vmPath = await toVmPath(fullPath);
    const ext = path.split('.').pop()?.toLowerCase() || '';
    const transcode = TRANSCODE_EXTS.has(ext) ? '&transcode=1' : '';
    return `${mediaServerBaseUrl(cfg.port)}/audio?path=${encodeURIComponent(vmPath)}${transcode}&t=${Date.now()}`;
  }

  window.api = {
    getLibraryDir: async () => {
      const res = await nativeCall('getLibraryDir');
      return res.path;
    },
    getPlatformInfo: () => nativeCall('getPlatformInfo'),
    getMediaServerConfig: () => getMediaServerConfig(),
    getMediaToolsDiagnostics: async () => {
      try {
        const cfg = await getMediaServerConfig();
        const response = await fetch(`${mediaServerBaseUrl(cfg.port)}/health`);
        const data = await response.json();
        return {
          mediaServerPort: cfg.port,
          mediaServerOk: Boolean(data?.ok),
          ytdlp: data?.ytdlp || null,
          ffmpeg: data?.ffmpeg || null,
          libraryRoot: data?.libraryRoot || cfg.vmLibraryRoot,
          setupHint: 'Terminal 内: ./scripts/setup-debian-media-server.sh → ポート 8765 転送を許可',
        };
      } catch (err) {
        return {
          mediaServerPort: cachedMediaServerPort,
          mediaServerOk: false,
          setupHint: 'Linux 開発環境 (Debian Terminal) でメディアサーバーを起動してください',
          error: err.message,
        };
      }
    },
    chooseDownloadDir: async () => {
      const res = await nativeCall('chooseDownloadDir');
      return res.path || null;
    },
    searchVideos: async (payload) => {
      await ensureMediaServerReady();
      const response = await fetchMediaServer('/api/search', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query: payload?.query || '' }),
      });
      const data = await response.json();
      if (!data?.ok) {
        throw new Error(data?.error || '検索に失敗しました');
      }
      return Array.isArray(data.results) ? data.results : [];
    },
    getPreviewStreamUrl: async (payload) => {
      await ensureMediaServerReady();
      const cfg = await getMediaServerConfig();
      const url = payload?.url || payload?.webpageUrl;
      if (!url) {
        throw new Error('試聴を開始できませんでした');
      }
      return `${mediaServerBaseUrl(cfg.port)}/stream?url=${encodeURIComponent(url)}`;
    },
    downloadAudio: async (payload) => {
      await ensureMediaServerReady();
      const saveDir = await toVmPath(payload?.saveDir || (await window.api.getLibraryDir()));
      const response = await fetchMediaServer('/api/download', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          url: payload?.url,
          saveDir,
          audioFormat: payload?.audioFormat || 'auto',
        }),
      });
      const data = await response.json();
      if (!data?.ok) {
        throw new Error(data?.error || 'ダウンロードに失敗しました');
      }
      return data;
    },
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
      const cfg = await getMediaServerConfig();
      return cfg.port;
    },
    resolveLibraryPath: async (payload) => {
      const res = await nativeCall('resolveLibraryPath', payload || {});
      return {
        found: Boolean(res.found),
        path: res.path || '',
      };
    },
    getStreamLogPath: async () => '(debian-vm)',
    buildLocalAudioUrl: (fullPath) => fullPath,
    buildLocalAudioUrlAsync,
    onSearchMetadataUpdated: () => noopUnsubscribe,
    onSearchEnrichmentStatus: () => noopUnsubscribe,
    saveUIState: (state) => saveUiState(state),
    flushUIState: () => flushUiState(),
    loadUIState: async () => loadUiState(),
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
