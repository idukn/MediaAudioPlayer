const { app, BrowserWindow, ipcMain, dialog, shell, clipboard } = require('electron');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');
const ElectronStore = require('electron-store');
const syncthingManager = require('./syncthing');
const Store = ElectronStore.default || ElectronStore;
const axios = require('axios');
const cheerio = require('cheerio');
const express = require('express');
const fsPromises = require('fs').promises;
const paths = require('./paths');
const platform = require('./platform');

function getLibraryDir() {
  return paths.getLibraryDir();
}
const AUDIO_EXTS = ['.mp3', '.m4a', '.aac', '.webm', '.wav', '.ogg', '.flac', '.opus', '.wma'];
const ALLOWED_DOWNLOAD_AUDIO_FORMATS = new Set(['auto', 'mp3', 'm4a', 'wav', 'flac', 'opus']);
// Bump this when ranking/relevance logic changes to force query cache recomputation.
const SEARCH_RANKING_CACHE_VERSION = '2026-03-18-rank-v8';
// Cap expensive per-item yt-dlp metadata lookups to improve first-paint speed.
const SEARCH_ENRICH_MAX_PER_BATCH = 3;
const SEARCH_ENRICH_MAX_PER_REQUEST = 6;
const SEARCH_BACKGROUND_ENRICH_MAX = 50;

let CACHE_DIR = '';
let METADATA_CACHE_FILE = '';
let QUERY_CACHE_FILE = '';
let STREAM_DEBUG_LOG_FILE = '';

let metadataCache = new Map();
let queryCache = new Map();
const metadataEnrichInFlight = new Set();

function emitEnrichmentStatus() {
  for (const win of BrowserWindow.getAllWindows()) {
    if (!win?.webContents || win.isDestroyed()) continue;
    win.webContents.send('search:enrichmentStatus', {
      active: metadataEnrichInFlight.size > 0,
      count: metadataEnrichInFlight.size,
    });
  }
}

let syncDirWatcher = null;
let syncDirWatcherTimer = null;
let currentSaveDir = '';
let syncthingBootstrapped = false;
let syncthingBackgroundChain = Promise.resolve();

function getCurrentSaveDir() {
  return currentSaveDir || getLibraryDir();
}

function assertPathInLibrary(targetPath) {
  const libraryDir = path.resolve(getCurrentSaveDir());
  const resolved = path.resolve(targetPath);
  if (!isPathWithin(libraryDir, resolved)) {
    throw new Error('ライブラリ外のパスは操作できません。再生する場合はライブラリにコピーしてください。');
  }
  return resolved;
}

function migrateLibraryData(libraryDir) {
  const libraryPlaylist = path.join(libraryDir, 'playlists.json');
  if (fs.existsSync(libraryPlaylist)) {
    return;
  }

  const legacyFromStore = store.get('saveDir');
  const candidates = [
    legacyFromStore,
    ...paths.getLegacyLibraryDirs(),
  ].filter((dir) => dir && path.resolve(dir) !== path.resolve(libraryDir));

  for (const legacyDir of candidates) {
    const legacyPlaylist = path.join(legacyDir, 'playlists.json');
    if (!fs.existsSync(legacyPlaylist)) {
      continue;
    }
    try {
      fs.copyFileSync(legacyPlaylist, libraryPlaylist);
      console.log(`[Library] Migrated playlists.json from ${legacyDir}`);
      break;
    } catch (err) {
      console.error('[Library] Failed to migrate playlists.json:', err.message);
    }
  }

  if (legacyFromStore) {
    store.delete('saveDir');
  }
}

function initializeLibrary() {
  const libraryDir = getLibraryDir();
  const { normalized } = applySaveDirSync(libraryDir);
  migrateLibraryData(normalized);
  if (!syncthingBootstrapped) {
    syncthingBootstrapped = true;
    scheduleSyncthingInBackground(normalized, { isFirstBoot: true });
  }
  return normalized;
}

function emitSyncUpdated(payload = {}) {
  for (const win of BrowserWindow.getAllWindows()) {
    if (!win?.webContents || win.isDestroyed()) continue;
    win.webContents.send('sync:updated', payload);
  }
}

function setupSyncDirWatcher() {
  if (syncDirWatcher) {
    syncDirWatcher.close();
    syncDirWatcher = null;
  }

  const saveDir = getCurrentSaveDir();
  if (!fs.existsSync(saveDir)) {
    return;
  }

  try {
    syncDirWatcher = fs.watch(saveDir, { recursive: true }, (_event, filename) => {
      if (!filename) {
        scheduleSyncDirChange({ type: 'dir' });
        return;
      }
      const name = String(filename);
      const lower = name.toLowerCase();
      if (name === 'playlists.json' || AUDIO_EXTS.some((ext) => lower.endsWith(ext))) {
        scheduleSyncDirChange({
          type: name === 'playlists.json' ? 'playlists' : 'audio',
        });
      }
    });
  } catch (err) {
    console.error('[Sync] Failed to watch save directory:', err.message);
  }
}

function scheduleSyncDirChange(payload) {
  if (syncDirWatcherTimer) {
    clearTimeout(syncDirWatcherTimer);
  }
  syncDirWatcherTimer = setTimeout(() => {
    syncDirWatcherTimer = null;
    emitSyncUpdated(payload);
  }, 800);
}

async function updateSyncthingSaveDir(saveDir) {
  try {
    await syncthingManager.ensureFolder(saveDir);
    await syncthingManager.waitForApi();
    const result = await syncthingManager.runStartupSync({ timeoutMs: 60000 });
    emitSyncUpdated({ type: 'dir', startup: true, ...result });
  } catch (err) {
    console.error('[Syncthing] Failed to update folder path:', err.message);
  }
}

async function bootstrapSyncthing(saveDir) {
  emitSyncUpdated({ type: 'dir', phase: 'syncing', startup: true });
  try {
    const result = await syncthingManager.bootstrap(saveDir);
    emitSyncUpdated({ type: 'dir', phase: 'idle', startup: true, ...result });
    console.log('[Syncthing] Startup sync finished:', result);
  } catch (err) {
    console.error('[Syncthing] Startup sync failed:', err.message);
    emitSyncUpdated({ type: 'dir', phase: 'error', startup: true, error: err.message });
  }
}

function emitSearchMetadataUpdate(item) {
  if (!item || !item.webpageUrl) {
    return;
  }

  const payload = {
    webpageUrl: item.webpageUrl,
    title: item.title,
    site: item.site,
    uploader: item.uploader,
    duration: item.duration,
    durationSec: item.durationSec,
    viewCount: item.viewCount,
    likeCount: item.likeCount,
    score: item.score,
  };

  for (const win of BrowserWindow.getAllWindows()) {
    if (!win?.webContents || win.isDestroyed()) {
      continue;
    }
    win.webContents.send('search:metadataUpdated', payload);
  }
}

function getStreamDebugLogPath() {
  if (!STREAM_DEBUG_LOG_FILE) {
    STREAM_DEBUG_LOG_FILE = path.join(app.getPath('userData'), 'stream_debug.log');
  }
  return STREAM_DEBUG_LOG_FILE;
}

function logStreamDebug(message, meta = null) {
  const stamp = new Date().toISOString();
  const payload = meta ? ` ${JSON.stringify(meta)}` : '';
  const line = `[${stamp}] ${message}${payload}\n`;

  try {
    const logPath = getStreamDebugLogPath();
    fs.appendFileSync(logPath, line, 'utf-8');
  } catch (_e) {
    // Ignore logging failures.
  }
}

async function loadCaches() {
  CACHE_DIR = path.join(app.getPath('userData'), 'yt_audio_app_cache');
  METADATA_CACHE_FILE = path.join(CACHE_DIR, 'metadata.json');
  QUERY_CACHE_FILE = path.join(CACHE_DIR, 'queries.json');
  try {
    await fsPromises.mkdir(CACHE_DIR, { recursive: true });
    try {
      const metadataRaw = await fsPromises.readFile(METADATA_CACHE_FILE, 'utf-8');
      metadataCache = new Map(JSON.parse(metadataRaw));
    } catch (e) { /* ignore */ }
    try {
      const queryRaw = await fsPromises.readFile(QUERY_CACHE_FILE, 'utf-8');
      queryCache = new Map(JSON.parse(queryRaw));
    } catch (e) { /* ignore */ }
  } catch (err) {
    console.error('[Cache] Failed to load caches:', err.message);
  }
}

app.on('before-quit', () => {
  syncthingManager.stop();
  try {
    if (CACHE_DIR) {
      fs.mkdirSync(CACHE_DIR, { recursive: true });
      fs.writeFileSync(METADATA_CACHE_FILE, JSON.stringify([...metadataCache]));
      fs.writeFileSync(QUERY_CACHE_FILE, JSON.stringify([...queryCache]));
    }
  } catch (e) {
    console.error('[Cache] Failed to save caches synchronously on quit:', e.message);
  }
});

const store = new Store({
  name: 'settings',
  defaults: {
    playlists: [],
  },
});

function applySaveDirSync(dir) {
  const normalized = ensureWritableDir(dir);
  const previous = currentSaveDir;
  currentSaveDir = normalized;
  setupSyncDirWatcher();
  return { normalized, previous };
}

function scheduleSyncthingInBackground(saveDir, { isFirstBoot = false, pathChanged = false } = {}) {
  if (!isFirstBoot && !pathChanged) {
    return;
  }

  const task = async () => {
    if (isFirstBoot) {
      await bootstrapSyncthing(saveDir);
      return;
    }
    if (pathChanged) {
      await updateSyncthingSaveDir(saveDir);
    }
  };

  syncthingBackgroundChain = syncthingBackgroundChain
    .then(task)
    .catch((err) => {
      console.error('[Syncthing] Background task failed:', err.message);
    });
}

function generatePlaylistId() {
  return `pl_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}

function isAbsoluteLibraryPath(value) {
  const normalized = String(value || '').replace(/\\/g, '/').trim();
  return normalized.startsWith('/') || /^[a-zA-Z]:[/\\]/.test(normalized);
}

/** playlists.json にはライブラリ相対パスのみ保存（Mac/Android 共通）。 */
function toStorageLibraryPath(fullPath) {
  const raw = String(fullPath || '').trim();
  if (!raw) {
    return '';
  }

  const libraryDir = path.resolve(getCurrentSaveDir());
  try {
    const resolved = path.resolve(raw);
    if (fs.existsSync(resolved) && isPathWithin(libraryDir, resolved)) {
      return path.relative(libraryDir, resolved).split(path.sep).join('/');
    }
  } catch (_e) {
    // ignore
  }

  if (!isAbsoluteLibraryPath(raw)) {
    return raw.replace(/\\/g, '/').replace(/^\/+/, '');
  }

  const normalized = raw.replace(/\\/g, '/');
  const markers = [
    '/library/',
    '.media-audio-finder/library/',
    'media-audio-finder/library/',
    '/application support/media-audio-finder/library/',
    '/reference/',
    '/yt_audio_app/',
  ];
  const lower = normalized.toLowerCase();
  let best = '';
  for (const marker of markers) {
    const idx = lower.lastIndexOf(marker);
    if (idx >= 0) {
      const rel = normalized.slice(idx + marker.length);
      if (rel.length > best.length) {
        best = rel;
      }
    }
  }
  if (best) {
    return best.replace(/^\/+/, '');
  }

  return path.basename(raw);
}

function expandLibraryPath(storedPath) {
  const storage = String(storedPath || '').trim();
  if (!storage) {
    return null;
  }

  const libraryDir = getCurrentSaveDir();
  if (!isAbsoluteLibraryPath(storage)) {
    const candidate = path.join(libraryDir, storage);
    if (fs.existsSync(candidate)) {
      return path.resolve(candidate);
    }
  }

  try {
    const resolved = path.resolve(storage);
    if (fs.existsSync(resolved) && isPathWithin(libraryDir, resolved)) {
      return resolved;
    }
  } catch (_e) {
    // ignore
  }

  const rel = toStorageLibraryPath(storage);
  if (rel && rel !== storage) {
    const candidate = path.join(libraryDir, rel);
    if (fs.existsSync(candidate)) {
      return path.resolve(candidate);
    }
  }

  return null;
}

function expandPlaylistItemForClient(item) {
  if (!item || item.type !== 'local') {
    return item;
  }
  const storagePath = String(item.fullPath || '').trim();
  if (!storagePath) {
    return item;
  }
  const resolved = expandLibraryPath(storagePath);
  if (!resolved) {
    return item;
  }
  return { ...item, fullPath: resolved };
}

function normalizePlaylistItem(rawItem) {
  if (!rawItem || typeof rawItem !== 'object') {
    return null;
  }

  const type = rawItem.type === 'local' ? 'local' : 'url';
  const title = String(rawItem.title || '').trim() || '(No title)';
  const uploader = String(rawItem.uploader || '-').trim() || '-';
  const duration = String(rawItem.duration || '-').trim() || '-';
  const site = String(rawItem.site || (type === 'local' ? 'Local' : 'unknown')).trim() || 'unknown';
  const durationSec = Number.isFinite(rawItem.durationSec) ? Number(rawItem.durationSec) : null;

  if (type === 'local') {
    const storagePath = toStorageLibraryPath(rawItem.fullPath || rawItem.path || '');
    if (!storagePath) {
      return null;
    }
    return {
      id: String(rawItem.id || `item_local_${Math.random().toString(36).slice(2, 8)}`),
      type,
      title,
      uploader,
      duration,
      durationSec,
      site,
      fullPath: storagePath,
    };
  }

  const webpageUrl = String(rawItem.webpageUrl || '').trim();
  if (!webpageUrl) {
    return null;
  }
  return {
    id: String(rawItem.id || `item_url_${Math.random().toString(36).slice(2, 8)}`),
    type,
    title,
    uploader,
    duration,
    durationSec,
    site,
    webpageUrl,
  };
}

function playlistItemKey(item) {
  if (!item) return '';
  if (item.type === 'local') {
    return `local:${item.fullPath || ''}`;
  }
  return `url:${item.webpageUrl || ''}`;
}

function normalizePlaylist(rawPlaylist) {
  if (!rawPlaylist || typeof rawPlaylist !== 'object') {
    return null;
  }

  const id = String(rawPlaylist.id || generatePlaylistId());
  const name = String(rawPlaylist.name || '').trim();
  if (!name) {
    return null;
  }

  const items = Array.isArray(rawPlaylist.items)
    ? rawPlaylist.items.map(normalizePlaylistItem).filter((item) => !!item)
    : [];

  return {
    id,
    name,
    createdAt: Number(rawPlaylist.createdAt) || Date.now(),
    items,
  };
}

function getPlaylistsFilePath() {
  const saveDir = getCurrentSaveDir();
  if (!fs.existsSync(saveDir)) {
    fs.mkdirSync(saveDir, { recursive: true });
  }
  return path.join(saveDir, 'playlists.json');
}

function migratePlaylistsToStorageFormat(playlists) {
  let dirty = false;
  const migrated = playlists.map((playlist) => {
    const items = Array.isArray(playlist.items) ? playlist.items : [];
    const storageItems = items.map(normalizePlaylistItem).filter((item) => !!item);
    const changed = items.some((item, index) => {
      const before = String(item?.fullPath || item?.path || '').trim();
      const after = String(storageItems[index]?.fullPath || '').trim();
      return before !== after;
    }) || items.length !== storageItems.length;
    if (changed) {
      dirty = true;
    }
    return { ...playlist, items: storageItems };
  });
  return { playlists: migrated, dirty };
}

function playlistsForClient(playlists) {
  return playlists.map((playlist) => ({
    ...playlist,
    items: Array.isArray(playlist.items)
      ? playlist.items.map(expandPlaylistItemForClient).filter((item) => !!item)
      : [],
  }));
}

function getStoredPlaylists() {
  const filePath = getPlaylistsFilePath();
  const legacyRaw = store.get('playlists');

  if (Array.isArray(legacyRaw) && legacyRaw.length > 0 && !fs.existsSync(filePath)) {
    const normalized = legacyRaw.map(normalizePlaylist).filter((playlist) => !!playlist);
    try {
      fs.writeFileSync(filePath, JSON.stringify(normalized, null, 2), 'utf-8');
      store.delete('playlists');
      return playlistsForClient(normalized);
    } catch (e) {
      console.error('Failed to migrate playlists:', e);
    }
  }

  try {
    if (fs.existsSync(filePath)) {
      const raw = JSON.parse(fs.readFileSync(filePath, 'utf-8'));
      if (Array.isArray(raw)) {
        const normalized = raw.map(normalizePlaylist).filter((playlist) => !!playlist);
        const { playlists: storagePlaylists, dirty } = migratePlaylistsToStorageFormat(normalized);
        if (dirty) {
          fs.writeFileSync(filePath, JSON.stringify(storagePlaylists, null, 2), 'utf-8');
          console.log('[Library] Migrated playlist paths to library-relative format');
        }
        return playlistsForClient(storagePlaylists);
      }
    }
  } catch (err) {
    console.error('Failed to read playlists.json:', err);
  }
  return [];
}

function setStoredPlaylists(playlists) {
  const normalized = Array.isArray(playlists)
    ? playlists.map(normalizePlaylist).filter((playlist) => !!playlist)
    : [];
  const { playlists: storagePlaylists } = migratePlaylistsToStorageFormat(normalized);
  try {
    const filePath = getPlaylistsFilePath();
    fs.writeFileSync(filePath, JSON.stringify(storagePlaylists, null, 2), 'utf-8');
  } catch (err) {
    console.error('Failed to write playlists.json:', err);
  }
  return playlistsForClient(storagePlaylists);
}

function isPathWithin(parent, target) {
  const parentResolved = path.resolve(parent);
  const targetResolved = path.resolve(target);
  return targetResolved === parentResolved || targetResolved.startsWith(`${parentResolved}${path.sep}`);
}

function getPreviewDir() {
  const dir = path.join(app.getPath('userData'), 'preview-cache');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function getStreamWorkDir() {
  const dir = path.join(app.getPath('userData'), 'stream-work');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function cleanupPreviewCache(dir, keep = 20) {
  try {
    const files = fs
      .readdirSync(dir)
      .map((name) => path.join(dir, name))
      .filter((fullPath) => fs.statSync(fullPath).isFile())
      .sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs);

    files.slice(keep).forEach((oldFile) => {
      try {
        fs.unlinkSync(oldFile);
      } catch (_e) {
        // ignore cleanup errors
      }
    });
  } catch (_e) {
    // ignore cleanup errors
  }
}

// HTTP server for audio streaming
let audioServer = null;
let audioServerPort = null;
const expressApp = express();

function startAudioServer() {
  return new Promise((resolve, reject) => {
    // Serve files from the save directory using absolute path in query.
    expressApp.get('/audio', (req, res) => {
      const rawPath = req.query.path;
      const saveDir = getCurrentSaveDir();

      if (!rawPath || typeof rawPath !== 'string') {
        return res.status(400).send('Missing path');
      }

      const filePath = rawPath;

      console.log('[Audio Server] Request:', {
        filePath,
      });

      // Security: prevent path traversal
      const realPath = path.resolve(filePath);
      const previewDir = getPreviewDir();
      const isUnderSaveDir = isPathWithin(saveDir, realPath);
      const isUnderPreviewDir = isPathWithin(previewDir, realPath);
      if (!isUnderSaveDir && !isUnderPreviewDir) {
        console.warn('[Audio Server] Security violation, path traversal attempt');
        return res.status(403).send('Forbidden');
      }

      if (!fs.existsSync(realPath)) {
        console.warn('[Audio Server] File not found:', realPath);
        return res.status(404).send('Not Found');
      }

      const stat = fs.statSync(realPath);
      const ext = path.extname(realPath).toLowerCase();
      
      // Set correct MIME type
      const mimeTypes = {
        '.mp3': 'audio/mpeg',
        '.m4a': 'audio/mp4',
        '.aac': 'audio/aac',
        '.wav': 'audio/wav',
        '.ogg': 'audio/ogg',
        '.opus': 'audio/opus',
        '.webm': 'audio/webm',
        '.wma': 'audio/x-ms-wma',
        '.flac': 'audio/flac',
      };

      const contentType = mimeTypes[ext] || 'audio/mpeg';
      res.setHeader('Content-Type', contentType);
      res.setHeader('Content-Length', stat.size);
      res.setHeader('Accept-Ranges', 'bytes');
      // Disable caching to prevent playback issues
      res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
      res.setHeader('Pragma', 'no-cache');
      res.setHeader('Expires', '0');

      console.log('[Audio Server] Serving:', {
        file: path.basename(realPath),
        size: stat.size,
        mimeType: contentType,
        ext: ext
      });

      const stream = fs.createReadStream(realPath);
      stream.pipe(res);

      stream.on('error', (err) => {
        console.error('[Audio Server] Stream error:', err.message);
        res.status(500).send('Server Error');
      });

      res.on('finish', () => {
        console.log('[Audio Server] Stream finished:', path.basename(realPath));
      });
    });

    // Proxy streaming endpoint for pure streaming previews (no local caching)
    expressApp.get('/stream', async (req, res) => {
      const url = req.query.url;
      if (!url || typeof url !== 'string') {
        return res.status(400).send('Missing url');
      }

      console.log('[Audio Server] Proxy streaming info requested for:', url);
      logStreamDebug('[ProxyStream] request', { url });

      let yt = null;
      let ff = null;
      let isEnded = false;

      const cleanup = (reason) => {
        if (isEnded) return;
        isEnded = true;
        console.log(`[ProxyStream] Cleanup triggered: ${reason}`);
        logStreamDebug('[ProxyStream] cleanup', { reason });
        
        if (yt && !yt.killed) {
          yt.kill('SIGKILL');
        }
        if (ff && !ff.killed) {
          ff.kill('SIGKILL');
        }
      };

      try {
        const ytdlp = await findExecutable('yt-dlp');
        const ffmpeg = await findExecutable('ffmpeg');
        
        if (!fs.existsSync(ytdlp) || !fs.existsSync(ffmpeg)) {
          console.error('[Audio Server] yt-dlp or ffmpeg missing');
          logStreamDebug('[ProxyStream] dependency missing', { ytdlp, ffmpeg });
          return res.status(500).send('Dependencies missing');
        }

        const transcodeProfile = await resolvePreviewTranscodeProfile(ffmpeg);
        res.setHeader('Content-Type', transcodeProfile.contentType);
        res.setHeader('Transfer-Encoding', 'chunked');
        res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
        res.setHeader('Pragma', 'no-cache');
        res.setHeader('Expires', '0');

        console.log('[Audio Server] Spawning yt-dlp to ffmpeg pipe for URL:', url);
        logStreamDebug('[ProxyStream] spawn', { ytdlp, ffmpeg, profileId: transcodeProfile.id });
        const streamWorkDir = getStreamWorkDir();
        logStreamDebug('[ProxyStream] workdir', { streamWorkDir });
        
        yt = spawn(ytdlp, [
          '-o', '-',
          '-f', 'bestaudio/best',
          '--no-playlist',
          '--no-warnings',
          '-q',
          '--user-agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
          url
        ], {
          cwd: streamWorkDir,
        });

        ff = spawn(ffmpeg, [
          '-hide_banner',
          '-loglevel', 'error',
          '-fflags', '+nobuffer',
          '-i', 'pipe:0',
          '-vn',
          '-map_metadata', '-1',
          ...transcodeProfile.ffmpegArgs,
          '-y',
          'pipe:1'
        ], {
          cwd: streamWorkDir,
        });

        // Track data flow for debugging
        let ytStdoutDataReceived = false;
        let ffStdinDataReceived = false;
        let ffStdoutDataReceived = false;
        let resDataSent = false;

        // Capture stderr for debugging
        if (yt.stderr) {
          yt.stderr.on('data', (chunk) => {
            const msg = chunk.toString().trim();
            if (msg) {
              console.log('[ProxyStream] yt-dlp stderr:', msg);
              logStreamDebug('[ProxyStream] yt-dlp stderr', { msg });
            }
          });
        }

        if (ff.stderr) {
          ff.stderr.on('data', (chunk) => {
            const msg = chunk.toString().trim();
            if (msg && !msg.includes('Opening') && !msg.includes('Last message')) {
              console.log('[ProxyStream] ffmpeg stderr:', msg);
              logStreamDebug('[ProxyStream] ffmpeg stderr', { msg });
            }
          });
        }

        // Monitor data flow through pipes
        yt.stdout.on('data', (chunk) => {
          ytStdoutDataReceived = true;
          if (!ffStdoutDataReceived) {
            console.log('[ProxyStream] yt-dlp stdout started flowing, chunk size:', chunk.length);
          }
        });

        ff.stdin.on('data', (chunk) => {
          ffStdinDataReceived = true;
          console.log('[ProxyStream] ffmpeg stdin received data, chunk size:', chunk.length);
        });

        ff.stdout.on('data', (chunk) => {
          ffStdoutDataReceived = true;
          if (!resDataSent) {
            console.log('[ProxyStream] ffmpeg stdout started flowing to response, chunk size:', chunk.length);
            resDataSent = true;
          }
        });

        // Error handlers - EPIPE is expected and normal, silently ignore it
        yt.stdout.on('error', (e) => {
          if (e.code !== 'EPIPE' && !isEnded) {
            console.error('[ProxyStream] yt.stdout error:', e.message);
          }
        });

        ff.stdin.on('error', (e) => {
          if (e.code !== 'EPIPE' && !isEnded) {
            console.error('[ProxyStream] ff.stdin error:', e.message);
          }
        });

        ff.stdout.on('error', (e) => {
          if (e.code !== 'EPIPE' && !isEnded) {
            console.error('[ProxyStream] ff.stdout error:', e.message);
          }
        });

        res.on('error', (e) => {
          if (e.code !== 'EPIPE' && !isEnded) {
            console.error('[ProxyStream] res error:', e.message);
          }
          cleanup('response error');
        });
        
        // Setup pipes
        yt.stdout.pipe(ff.stdin);
        ff.stdout.pipe(res);

        // Process error handlers
        yt.on('error', (err) => {
          if (!isEnded) {
            console.error('[ProxyStream] yt-dlp error:', err.message);
            cleanup('yt-dlp process error');
          }
        });

        ff.on('error', (err) => {
          if (!isEnded) {
            console.error('[ProxyStream] ffmpeg error:', err.message);
            cleanup('ffmpeg process error');
          }
        });

        // Process exit handlers
        yt.on('exit', (code, signal) => {
          console.log(`[ProxyStream] yt-dlp exited: code=${code}, signal=${signal}, ytStdoutDataReceived=${ytStdoutDataReceived}`);
          logStreamDebug('[ProxyStream] yt-dlp exit', { code, signal, ytStdoutDataReceived });
          if (code !== 0 && code !== null && !isEnded) {
            console.warn(`[ProxyStream] yt-dlp error: code ${code}, signal ${signal}`);
            cleanup('yt-dlp process exit');
          }
        });

        ff.on('exit', (code, signal) => {
          console.log(`[ProxyStream] ffmpeg exited: code=${code}, signal=${signal}, ffStdinDataReceived=${ffStdinDataReceived}, ffStdoutDataReceived=${ffStdoutDataReceived}`);
          logStreamDebug('[ProxyStream] ffmpeg exit', { code, signal, ffStdinDataReceived, ffStdoutDataReceived, profileId: transcodeProfile.id });
          if (!resDataSent && !isEnded) {
            console.warn('[ProxyStream] ffmpeg exited before producing audio output');
            if (!res.headersSent) {
              res.status(500).send('Preview transcode failed');
            } else {
              res.end();
            }
            cleanup('ffmpeg produced no output');
            return;
          }
          if (code !== 0 && code !== null && !isEnded) {
            console.warn(`[ProxyStream] ffmpeg error: code ${code}, signal ${signal}`);
            cleanup('ffmpeg process exit');
          }
        });

        // Handle client disconnect
        res.on('close', () => {
          console.log('[ProxyStream] Client closed connection. Cleaning up processes.');
          cleanup('client disconnect');
        });

      } catch (err) {
        console.error('[ProxyStream] Fatal error setup:', err);
        logStreamDebug('[ProxyStream] fatal', { message: err.message });
        return res.status(500).send('Streaming setup failed');
      }
    });

    audioServer = expressApp.listen(0, '127.0.0.1', () => {
      audioServerPort = audioServer.address().port;
      console.log(`[Audio Server] Started on http://127.0.0.1:${audioServerPort}`);
      resolve(audioServerPort);
    });

    audioServer.on('error', (err) => {
      console.error('[Audio Server] Start error:', err);
      reject(err);
    });
  });
}

const PREVIEW_TRANSCODE_PROFILES = [
  {
    id: 'mp3',
    contentType: 'audio/mpeg',
    ffmpegArgs: ['-c:a', 'libmp3lame', '-q:a', '4', '-id3v2_version', '0', '-write_xing', '0', '-f', 'mp3'],
    isAvailable: (encoderText) => encoderText.includes('libmp3lame'),
  },
  {
    id: 'aac-mp4',
    contentType: 'audio/mp4',
    ffmpegArgs: ['-c:a', 'aac', '-b:a', '128k', '-movflags', 'frag_keyframe+empty_moov+default_base_moof', '-f', 'mp4'],
    isAvailable: (encoderText) => /\s+aac(\s|$)/m.test(encoderText) || encoderText.includes('aac_at'),
  },
  {
    id: 'opus-webm',
    contentType: 'audio/webm',
    ffmpegArgs: ['-c:a', 'libopus', '-b:a', '96k', '-f', 'webm'],
    isAvailable: (encoderText) => encoderText.includes('libopus'),
  },
  {
    id: 'wav',
    contentType: 'audio/wav',
    ffmpegArgs: ['-c:a', 'pcm_s16le', '-f', 'wav'],
    isAvailable: () => true,
  },
];

const previewTranscodeCache = new Map();

async function resolvePreviewTranscodeProfile(ffmpegPath) {
  if (previewTranscodeCache.has(ffmpegPath)) {
    return previewTranscodeCache.get(ffmpegPath);
  }

  let encoderText = '';
  try {
    const result = await runCommand(ffmpegPath, ['-hide_banner', '-encoders']);
    encoderText = `${result.stdout}\n${result.stderr}`;
  } catch (err) {
    encoderText = String(err.message || '');
  }

  const profile = PREVIEW_TRANSCODE_PROFILES.find((candidate) => candidate.isAvailable(encoderText))
    || PREVIEW_TRANSCODE_PROFILES[PREVIEW_TRANSCODE_PROFILES.length - 1];

  previewTranscodeCache.set(ffmpegPath, profile);
  logStreamDebug('[ProxyStream] transcode profile', { ffmpegPath, profileId: profile.id, contentType: profile.contentType });
  return profile;
}

async function findExecutable(name, extraCandidates = []) {
  const candidates = [
    ...extraCandidates,
    ...platform.getExecutableCandidates(name),
  ];

  for (const candidate of candidates) {
    if (candidate === name || candidate === `${name}.exe`) {
      continue;
    }
    if (fs.existsSync(candidate)) {
      try {
        fs.accessSync(candidate, fs.constants.X_OK);
        console.log(`[Deps] Resolved ${name}: ${candidate}`);
        logStreamDebug('[Deps] resolved candidate', { name, candidate });
        return candidate;
      } catch (_e) {
        // Keep searching if the file exists but is not executable.
      }
    }
  }

  const shellResolved = await platform.resolveFromShell(name);
  if (shellResolved) {
    console.log(`[Deps] Resolved ${name} via shell: ${shellResolved}`);
    logStreamDebug('[Deps] resolved via shell', { name, shellResolved });
    return shellResolved;
  }

  return name;
}

function normalizeSaveDir(dir) {
  const normalized = String(dir || '').trim();
  if (!normalized) {
    throw new Error('保存先パスが設定されていません');
  }
  return normalized;
}

function ensureWritableDir(dir) {
  const normalized = normalizeSaveDir(dir);
  fs.mkdirSync(normalized, { recursive: true });
  fs.accessSync(normalized, fs.constants.W_OK);
  return normalized;
}

function runCommand(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    let stdout = '';
    let stderr = '';

    child.stdout.on('data', (d) => {
      stdout += d.toString();
    });
    child.stderr.on('data', (d) => {
      stderr += d.toString();
    });

    child.on('error', (err) => {
      reject(err);
    });

    child.on('close', (code) => {
      if (code === 0) {
        resolve({ stdout, stderr });
      } else {
        reject(new Error(stderr || stdout || `Command failed: ${command} ${args.join(' ')}`));
      }
    });
  });
}

function parseSearchResult(jsonText, siteLabel) {
  const parsed = JSON.parse(jsonText);
  const entries = parsed.entries || [];
  return entries
    .filter((e) => !!e)
    .map((e) => ({
      title: e.title || '(No title)',
      uploader: e.uploader || '-',
      duration: formatDuration(e.duration),
      webpageUrl: e.webpage_url || e.url || '',
      site: siteLabel,
    }));
}

async function searchYtdlp(query, sourceSite, offset = 0, limit = 20) {
  const site = sourceSite === 'youtube' ? 'ytsearch' : 'nicosearch';
  const fetchCount = Math.min(80, Math.max(20, offset + limit));
  const searchQuery = `${site}${fetchCount}:${query}`;

  console.log(`[Search] Using yt-dlp to search ${sourceSite} for:`, query);

  const ytdlp = await findExecutable('yt-dlp');
  if (!fs.existsSync(ytdlp)) {
    throw new Error('yt-dlp not found. Install it with: brew install yt-dlp');
  }

  const args = [
    searchQuery,
    '--dump-json',
    '--flat-playlist',
    '--no-warnings',
    '-q',
  ];

  const { stdout } = await runCommand(ytdlp, args);
  if (!stdout.trim()) {
    throw new Error('検索結果が見つかりませんでした。別のキーワードを試してください。');
  }

  const entries = stdout
    .trim()
    .split('\n')
    .filter((line) => line.trim())
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch (_e) {
        return null;
      }
    })
    .filter((entry) => entry !== null);

  const results = entries
    .slice(offset, offset + limit)
    .map((entry) => ({
      title: entry.title || 'Unknown',
      uploader: entry.uploader || 'Unknown',
      duration: formatDuration(entry.duration),
      webpageUrl: entry.webpage_url || entry.url || '',
      site: sourceSite === 'youtube' ? 'YouTube' : 'Niconico',
      viewCount: entry.view_count || 0,
      likeCount: entry.like_count || 0,
    }))
    .filter((r) => r.webpageUrl.length > 0);

  console.log(`[Search] Found ${results.length} results for ${sourceSite}`);
  return results;
}

function decodeHtmlEntities(text) {
  if (!text || typeof text !== 'string') {
    return '';
  }
  return text
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>');
}

async function searchNiconicoDirect(query, offset = 0, limit = 20) {
  const pageSize = 32;
  const page = Math.floor(offset / pageSize) + 1;
  const offsetInPage = offset % pageSize;
  // Keep NicoNico's default ranking to favor highly relevant/official uploads.
  const searchUrl = `https://www.nicovideo.jp/search/${encodeURIComponent(query)}?page=${page}`;

  const response = await axios.get(searchUrl, {
    headers: {
      'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36',
    },
    timeout: 15000,
  });

  const $ = cheerio.load(response.data);
  const serverResponseRaw = $('meta[name="server-response"]').attr('content') || '';
  if (!serverResponseRaw) {
    return [];
  }

  const serverResponseJson = decodeHtmlEntities(serverResponseRaw);
  const parsed = JSON.parse(serverResponseJson);
  const items = parsed?.data?.response?.$getSearchVideoV2?.data?.items || [];

  return items
    .filter((item) => item && typeof item.id === 'string' && item.id.length > 0)
    .slice(offsetInPage, offsetInPage + limit)
    .map((item) => ({
      title: item.title || 'Unknown',
      uploader: item?.owner?.name || '-',
      duration: formatDuration(item.duration),
      webpageUrl: `https://www.nicovideo.jp/watch/${item.id}`,
      site: 'Niconico',
      viewCount: item?.count?.view || 0,
      likeCount: item?.count?.like || 0,
      mylistCount: item?.count?.mylist || 0,
    }));
}

function normalizeForRank(text) {
  return String(text || '').toLowerCase().replace(/[\s\u3000]+/g, ' ').trim();
}

function parseDurationStringToSeconds(text) {
  if (!text || typeof text !== 'string' || text === '-') {
    return null;
  }
  const parts = text.split(':').map((p) => Number(p));
  if (parts.some((p) => Number.isNaN(p))) {
    return null;
  }
  if (parts.length === 2) {
    return parts[0] * 60 + parts[1];
  }
  if (parts.length === 3) {
    return parts[0] * 3600 + parts[1] * 60 + parts[2];
  }
  return null;
}

function tokenizeQuery(query) {
  return normalizeForRank(query)
    .split(' ')
    .map((t) => t.trim())
    .filter((t) => t.length > 0);
}

function scoreResult(query, item, durationPreference) {
  const title = normalizeForRank(item?.title);
  const uploader = normalizeForRank(item?.uploader);
  const site = normalizeForRank(item?.site);
  const normalizedQuery = normalizeForRank(query);
  const tokens = tokenizeQuery(normalizedQuery);

  let score = 0;
  if (title.includes(normalizedQuery)) {
    score += 180;
  }
  if (title.startsWith(normalizedQuery)) {
    score += 90;
  }

  let allTokensMatch = tokens.length > 0;
  for (const token of tokens) {
    if (title.includes(token)) {
      score += 40;
    } else {
      allTokensMatch = false;
    }
    if (uploader.includes(token)) {
      score += 60;
    }
  }
  if (allTokensMatch && tokens.length > 1) {
    score += 500;
  }
  if (title === normalizedQuery) {
    score += 300; // Exact title match bonus
  }
  if (uploader === normalizedQuery || uploader.includes(normalizedQuery)) {
    score += 400; // Exact uploader match bonus
  }

  if (title.includes('official') || title.includes('公式') || title.includes('karma')) {
    score += 200;
  }
  if (title.includes('mv') || title.includes('music video')) {
    score += 120;
  }

  // Platform-aware engagement boost.
  if (site.includes('youtube')) {
    if (uploader.includes('official') || uploader.includes('topic')) {
      score += 150;
    }
    if (title.includes('official audio')) {
      score += 150;
    }
    if (title.includes('原口沙輔')) {
      score += 120;
    }
    if (/歌ってみた|cover|弾いてみた|踊ってみた|叩いてみた|remix|nightcore|sped up|inst|off vocal/.test(title)) {
      score -= 200;
    }
    const view = Number(item?.viewCount || 0);
    const like = Number(item?.likeCount || 0);
    score += Math.log10(view + 1) * 65;
    score += Math.log10(like + 1) * 85;
  }

  if ((item?.site || '') === 'Niconico') {
    if (title.includes('公式')) {
      score += 90;
    }
    if (title.includes('mv') || title.includes('music video')) {
      score += 45;
    }

    const view = Number(item?.viewCount || 0);
    const like = Number(item?.likeCount || 0);
    const mylist = Number(item?.mylistCount || 0);
    score += Math.log10(view + 1) * 35;
    score += Math.log10(like + 1) * 40;
    score += Math.log10(mylist + 1) * 50;
  }

  if (durationPreference?.enabled) {
    const itemSec = Number.isFinite(item?.durationSec)
      ? item.durationSec
      : parseDurationStringToSeconds(item?.duration);
    if (Number.isFinite(itemSec)) {
      const minSec = Number(durationPreference.minSec);
      const maxSec = Number(durationPreference.maxSec);
      if (itemSec >= minSec && itemSec <= maxSec) {
        const center = (minSec + maxSec) / 2;
        const spread = Math.max(1, (maxSec - minSec) / 2);
        const closeness = Math.max(0, 1 - Math.abs(itemSec - center) / spread);
        score += 120 + closeness * 50;
      }
    }
  }

  return score;
}

function rankResults(query, results, durationPreference) {
  // Give items a native rank bonus based on their original position from the search engine
  const scored = results.map((item, index) => {
    // Save original index on first rank to preserve it across background re-ranks
    if (item.originalEngineRank === undefined) {
      item.originalEngineRank = index;
    }
    const score = scoreResult(query, item, durationPreference);
    // Add bonus: +400 for 1st, +380 for 2nd... helps match things where title doesn't structurally match query
    const engineBonus = Math.max(0, 400 - item.originalEngineRank * 20);
    return { item, score: score + engineBonus };
  });

  scored.sort((a, b) => b.score - a.score);
  return scored.map(s => {
    s.item.score = s.score;
    return s.item;
  });
}

function buildBalancedPage(results, limit = 20, sourcePriorityCount = 3) {
  if (!Array.isArray(results) || results.length === 0) {
    return [];
  }

  const deduped = [];
  const seen = new Set();
  for (const item of results) {
    const url = item?.webpageUrl;
    if (!url || seen.has(url)) {
      continue;
    }
    seen.add(url);
    deduped.push(item);
  }

  // Group by site while preserving original ranking order inside each site.
  const siteBuckets = new Map();
  for (const item of deduped) {
    const site = item.site || extractSiteFromUrl(item.webpageUrl) || 'unknown';
    if (!siteBuckets.has(site)) {
      siteBuckets.set(site, []);
    }
    siteBuckets.get(site).push(item);
  }

  // Prefer major sources first; append any remaining sites in detected order.
  const preferredSiteOrder = ['YouTube', 'Niconico', 'Bilibili'];
  const orderedSites = preferredSiteOrder.filter((site) => siteBuckets.has(site));
  for (const site of siteBuckets.keys()) {
    if (!orderedSites.includes(site)) {
      orderedSites.push(site);
    }
  }

  const picked = [];
  const pickedUrls = new Set();

  // Round-robin pick top-N per site so each source's top results surface in all-sources.
  for (let round = 0; round < sourcePriorityCount; round += 1) {
    for (const site of orderedSites) {
      if (picked.length >= limit) {
        break;
      }
      const bucket = siteBuckets.get(site);
      if (!bucket || round >= bucket.length) {
        continue;
      }
      const candidate = bucket[round];
      if (!candidate?.webpageUrl || pickedUrls.has(candidate.webpageUrl)) {
        continue;
      }
      picked.push(candidate);
      pickedUrls.add(candidate.webpageUrl);
    }
  }

  // Fill remaining slots from globally ranked list.
  for (const item of deduped) {
    if (picked.length >= limit) {
      break;
    }
    if (!item?.webpageUrl || pickedUrls.has(item.webpageUrl)) {
      continue;
    }
    picked.push(item);
    pickedUrls.add(item.webpageUrl);
  }

  return picked;
}

// Video platform domains that yt-dlp supports
const VIDEO_DOMAINS = [
  'youtube.com', 'youtu.be',
  'nicovideo.jp', 'nico.ms',
  'bilibili.com',
  'b23.tv',
  'dailymotion.com',
  'vimeo.com',
  'twitch.tv',
  'soundcloud.com',
  'spotify.com',
  'bandcamp.com',
  'instagram.com',
  'tiktok.com',
  'reddit.com',
  'facebook.com',
];

function isVideoUrl(url) {
  if (!url) return false;
  return VIDEO_DOMAINS.some((domain) => url.includes(domain));
}

function isLikelyHttpUrl(value) {
  if (!value || typeof value !== 'string') {
    return false;
  }
  try {
    const parsed = new URL(value.trim());
    return parsed.protocol === 'http:' || parsed.protocol === 'https:';
  } catch (_e) {
    return false;
  }
}

function isDirectVideoPage(url) {
  if (!url) return false;

  // Niconico: only accept watch pages (exclude search/tag pages)
  if (url.includes('nicovideo.jp')) {
    return url.includes('/watch/');
  }
  if (url.includes('nico.ms/')) {
    return true;
  }

  // YouTube: accept watch/shorts/live and youtu.be links
  if (url.includes('youtube.com')) {
    return url.includes('/watch') || url.includes('/shorts/') || url.includes('/live/');
  }
  if (url.includes('youtu.be/')) {
    return true;
  }

  // Bilibili: accept video pages
  if (url.includes('bilibili.com')) {
    return url.includes('/video/');
  }
  if (url.includes('b23.tv/')) {
    return true;
  }

  // TikTok: accept only direct video pages, reject search/list pages.
  if (url.includes('tiktok.com')) {
    return url.includes('/video/');
  }

  // For other supported domains, keep existing behavior.
  return true;
}

function matchesSource(site, source) {
  if (!source || source === 'both') return true;
  if (source === 'youtube') return site === 'YouTube';
  if (source === 'niconico') return site === 'Niconico';
  if (source === 'bilibili') return site === 'Bilibili';
  return true;
}

function matchesFilterValue(site, filter) {
  if (!filter || filter === 'all') return true;
  const lowered = String(site || '').toLowerCase();
  if (filter === 'youtube') return lowered.includes('youtube');
  if (filter === 'niconico') return lowered.includes('niconico');
  if (filter === 'bilibili') return lowered.includes('bilibili');
  if (filter === 'other') {
    return !lowered.includes('youtube') && !lowered.includes('niconico') && !lowered.includes('bilibili');
  }
  return true;
}

function extractSiteFromUrl(url) {
  if (!url) return 'unknown';
  if (url.includes('youtube.com') || url.includes('youtu.be')) return 'YouTube';
  if (url.includes('nicovideo.jp') || url.includes('nico.ms')) return 'Niconico';
  if (url.includes('bilibili.com') || url.includes('b23.tv')) return 'Bilibili';
  if (url.includes('dailymotion.com')) return 'DailyMotion';
  if (url.includes('vimeo.com')) return 'Vimeo';
  if (url.includes('twitch.tv')) return 'Twitch';
  if (url.includes('soundcloud.com')) return 'SoundCloud';
  if (url.includes('spotify.com')) return 'Spotify';
  if (url.includes('bandcamp.com')) return 'Bandcamp';
  if (url.includes('instagram.com')) return 'Instagram';
  if (url.includes('tiktok.com')) return 'TikTok';
  if (url.includes('reddit.com')) return 'Reddit';
  if (url.includes('facebook.com')) return 'Facebook';
  return 'unknown';
}

async function resolveDirectVideoResult(url) {
  const normalizedUrl = String(url || '').trim();
  if (!isLikelyHttpUrl(normalizedUrl)) {
    throw new Error('URLが不正です');
  }
  if (!isVideoUrl(normalizedUrl)) {
    throw new Error('サポート対象の動画URLを入力してください。');
  }
  if (!isDirectVideoPage(normalizedUrl)) {
    throw new Error('動画ページのURLを入力してください。');
  }

  const ytdlp = await findExecutable('yt-dlp');
  if (!fs.existsSync(ytdlp)) {
    throw new Error('yt-dlp not found. Install it with: brew install yt-dlp');
  }

  const args = [
    '--dump-single-json',
    '--skip-download',
    '--no-playlist',
    '--no-warnings',
    '-q',
    normalizedUrl,
  ];

  try {
    const { stdout } = await runCommand(ytdlp, args);
    const jsonText = stdout.trim();
    if (!jsonText) {
      throw new Error('動画情報を取得できませんでした。');
    }

    const meta = JSON.parse(jsonText);
    const webpageUrl = meta.webpage_url || meta.original_url || normalizedUrl;
    return {
      title: meta.title || '(No title)',
      uploader: meta.channel || meta.uploader || meta.creator || meta.artist || '-',
      duration: formatDuration(meta.duration),
      durationSec: Number.isFinite(meta.duration) ? meta.duration : undefined,
      webpageUrl,
      site: extractSiteFromUrl(webpageUrl),
      viewCount: meta.view_count,
      likeCount: meta.like_count,
    };
  } catch (error) {
    throw new Error(`URL解析に失敗しました: ${error.message}`);
  }
}

async function searchDuckDuckGo(query, source) {
  try {
    const offset = Number.isInteger(source?.offset) ? source.offset : 0;
    const limit = Number.isInteger(source?.limit) ? source.limit : 20;
    const siteFilter = source?.siteFilter || 'both';
    const ddgUrl = `https://html.duckduckgo.com/html/?q=${encodeURIComponent(`${query} video`)}&s=${Math.max(0, offset)}`;
    console.log(`[Search] DuckDuckGo(HTML) query:`, query);

    const response = await axios.get(ddgUrl, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36',
      },
      timeout: 15000,
    });

    const $ = cheerio.load(response.data);
    const results = [];

    $('.result').each((_, el) => {
      if (results.length >= 30) return;

      const anchor = $(el).find('.result__a').first();
      const href = anchor.attr('href') || '';
      const title = anchor.text().trim();
      if (!href || !title) return;

      let finalUrl = href;
      try {
        // DuckDuckGo uses redirect links like /l/?uddg=<encodedUrl>
        if (href.includes('duckduckgo.com/l/?') || href.startsWith('/l/?')) {
          const normalized = href.startsWith('http') ? href : `https://duckduckgo.com${href}`;
          const parsed = new URL(normalized);
          const uddg = parsed.searchParams.get('uddg');
          if (uddg) {
            finalUrl = decodeURIComponent(uddg);
          }
        }
      } catch (_e) {
        // keep original href
      }

      if (!isVideoUrl(finalUrl)) return;
      if (!isDirectVideoPage(finalUrl)) return;

      const site = extractSiteFromUrl(finalUrl);
      if (!matchesSource(site, siteFilter)) return;

      results.push({
        title: title.substring(0, 150),
        uploader: '-',
        duration: '-',
        webpageUrl: finalUrl,
        site,
      });
    });

    // Remove duplicates by URL
    const deduped = [];
    const seen = new Set();
    for (const item of results) {
      if (!seen.has(item.webpageUrl)) {
        seen.add(item.webpageUrl);
        deduped.push(item);
      }
    }

    const sliced = deduped.slice(0, limit);
    console.log(`[Search] Found ${sliced.length} video results`);
    return sliced;
  } catch (error) {
    console.error(`[Search] DuckDuckGo error:`, error.message);
    throw new Error(`検索エンジンでの検索に失敗しました: ${error.message}`);
  }
}

async function resolveVideoMetadata(url) {
  if (!url) {
    return { uploader: '-', duration: '-' };
  }

  if (metadataCache.has(url)) {
    return metadataCache.get(url);
  }

  const ytdlp = await findExecutable('yt-dlp');
  if (!fs.existsSync(ytdlp)) {
    return { uploader: '-', duration: '-' };
  }

  try {
    const args = [
      '--dump-single-json',
      '--skip-download',
      '--no-playlist',
      '--no-warnings',
      '-q',
      url,
    ];

    const { stdout } = await runCommand(ytdlp, args);
    const jsonText = stdout.trim();
    if (!jsonText) {
      return { uploader: '-', duration: '-' };
    }

    const meta = JSON.parse(jsonText);
    const resultMeta = {
      uploader: meta.channel || meta.uploader || meta.creator || meta.artist || '-',
      duration: formatDuration(meta.duration),
      durationSec: meta.duration,
      viewCount: meta.view_count,
      likeCount: meta.like_count,
    };
    return resultMeta;
  } catch (_error) {
    return { uploader: '-', duration: '-' };
  }
}

async function enrichMetadata(results, options = {}) {
  if (!Array.isArray(results) || results.length === 0) {
    return results;
  }

  const maxItems = Number.isFinite(options.maxItems)
    ? Math.max(0, Math.floor(options.maxItems))
    : Number.POSITIVE_INFINITY;

  const enrichPromises = [];
  let scheduled = 0;

  for (let i = 0; i < results.length; i += 1) {
    const item = results[i];
    if (!item || !item.webpageUrl) {
      continue;
    }

    enrichPromises.push((async () => {
      // 1. If previously completely resolved, just merge in cache
      if (metadataCache.has(item.webpageUrl)) {
        Object.assign(item, metadataCache.get(item.webpageUrl));
        if (options.queryContext) {
          item.score = scoreResult(options.queryContext.query, item, options.queryContext.durationPreference);
          if (typeof options.queryContext.onItemEnriched === 'function') {
            options.queryContext.onItemEnriched();
          }
        }
        emitSearchMetadataUpdate(item);
        return;
      }

      if (metadataEnrichInFlight.has(item.webpageUrl)) {
        return;
      }

      if (scheduled >= maxItems) {
        return;
      }

      // 2. Otherwise fetch and fill gaps
      const needsUploader = !item.uploader || item.uploader === '-' || item.uploader === 'Unknown';
      const needsDuration = !item.duration || item.duration === '-';
      if (!needsUploader && !needsDuration) {
        metadataCache.set(item.webpageUrl, { ...item });
        if (options.queryContext) {
          item.score = scoreResult(options.queryContext.query, item, options.queryContext.durationPreference);
        }
        emitSearchMetadataUpdate(item);
        return;
      }

      scheduled += 1;
      metadataEnrichInFlight.add(item.webpageUrl);
      emitEnrichmentStatus();

      try {
        const metadata = await resolveVideoMetadata(item.webpageUrl);
        if (needsUploader && metadata.uploader && metadata.uploader !== '-') {
          item.uploader = metadata.uploader;
        }
        if (needsDuration && metadata.duration && metadata.duration !== '-') {
          item.duration = metadata.duration;
        }
        if (metadata.durationSec && !Number.isFinite(item.durationSec)) {
          item.durationSec = metadata.durationSec;
        }
        if (metadata.viewCount && !item.viewCount) item.viewCount = metadata.viewCount;
        if (metadata.likeCount && !item.likeCount) item.likeCount = metadata.likeCount;

        // 3. Re-calculate score if context given
        if (options.queryContext) {
          item.score = scoreResult(options.queryContext.query, item, options.queryContext.durationPreference);
          if (typeof options.queryContext.onItemEnriched === 'function') {
            options.queryContext.onItemEnriched();
          }
        }

        // 4. Save enriched item to cache
        metadataCache.set(item.webpageUrl, { ...item });
        emitSearchMetadataUpdate(item);
      } finally {
        metadataEnrichInFlight.delete(item.webpageUrl);
        emitEnrichmentStatus();
      }
    })());
  }

  await Promise.all(enrichPromises);
  return results;
}

function startBackgroundEnrichment(results, queryContext = null) {
  if (!Array.isArray(results) || results.length === 0) {
    return;
  }

  const target = results.slice(0, SEARCH_BACKGROUND_ENRICH_MAX).map((item) => ({ ...item }));
  enrichMetadata(target, { 
    maxItems: SEARCH_BACKGROUND_ENRICH_MAX,
    queryContext
  })
    .then(() => {
      console.log(`[Search] Background metadata enrichment finished (${target.length} items)`);
    })
    .catch((error) => {
      console.warn('[Search] Background metadata enrichment failed:', error.message);
    });
}

function formatDuration(raw) {
  if (!Number.isInteger(raw)) {
    return '-';
  }
  const h = Math.floor(raw / 3600);
  const m = Math.floor((raw % 3600) / 60);
  const s = raw % 60;
  if (h > 0) {
    return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }
  return `${m}:${String(s).padStart(2, '0')}`;
}

function listAudioFiles(saveDir) {
  if (!fs.existsSync(saveDir)) {
    return [];
  }
  return fs
    .readdirSync(saveDir)
    .filter((name) => !name.startsWith('._') && name !== '.DS_Store') // Exclude macOS metadata
    .map((name) => path.join(saveDir, name))
    .map((fullPath) => {
      try {
        const stat = fs.statSync(fullPath);
        const ext = path.extname(fullPath).toLowerCase();
        const isDir = stat.isDirectory();
        
        // For audio files, check if they exist and have valid extension
        let isAudio = false;
        if (AUDIO_EXTS.includes(ext) && !isDir && stat.size > 0) {
          isAudio = true;
          // Log audio file info for debugging
          console.log(`[FileList] Found audio file:`, {
            name: path.basename(fullPath),
            size: stat.size,
            ext,
          });
        }
        
        return {
          name: path.basename(fullPath),
          fullPath,
          mtimeMs: stat.mtimeMs,
          isDir,
          isAudio,
          size: stat.size,
        };
      } catch (e) {
        console.error(`Error reading file metadata: ${fullPath}`, e.message);
        return null;
      }
    })
    .filter((item) => item !== null && (item.isDir || item.isAudio))
    .sort((a, b) => {
      if (a.isDir !== b.isDir) return a.isDir ? -1 : 1;
      return b.mtimeMs - a.mtimeMs;
    });
}

function createWindow() {
  const win = new BrowserWindow({
    width: 1200,
    height: 800,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      webSecurity: false,
    },
  });

  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
}

ipcMain.handle('settings:getLibraryDir', () => {
  return getCurrentSaveDir();
});

ipcMain.handle('settings:chooseDownloadDir', async () => {
  const result = await dialog.showOpenDialog({
    title: 'ダウンロード先フォルダを選択',
    properties: ['openDirectory', 'createDirectory'],
    defaultPath: paths.getDefaultDialogPath('downloads'),
  });

  if (result.canceled || !result.filePaths[0]) {
    return null;
  }

  const chosen = result.filePaths[0];
  ensureWritableDir(chosen);
  return chosen;
});

ipcMain.handle('search:videos', async (_event, payload) => {
  const {
    query,
    offset = 0,
    limit = 20,
    durationPreference = null,
    sourceFilter = 'all',
  } = payload || {};
  if (!query || query.trim().length === 0) {
    throw new Error('検索クエリが空です');
  }

  const normalizedQuery = query.trim();
  if (isLikelyHttpUrl(normalizedQuery)) {
    if (offset > 0) {
      return [];
    }
    const directItem = await resolveDirectVideoResult(normalizedQuery);
    if (!matchesFilterValue(directItem.site, sourceFilter)) {
      return [];
    }
    return [directItem];
  }

  const allResults = [];
  
  const queryLimit = limit === 20 ? 10 : limit; 
  const queryCacheKey = `${SEARCH_RANKING_CACHE_VERSION}_${query}_${sourceFilter}_${offset}_${queryLimit}_${durationPreference?.enabled ? durationPreference.minSec + '-' + durationPreference.maxSec : 'none'}`;
  
  if (queryCache.has(queryCacheKey)) {
    const cachedEntry = queryCache.get(queryCacheKey);
    const cachedResults = [];

    // Backward compatibility for old cache shape (array of URLs).
    if (Array.isArray(cachedEntry) && cachedEntry.length > 0 && typeof cachedEntry[0] === 'string') {
      for (const url of cachedEntry) {
        if (metadataCache.has(url)) {
          cachedResults.push({ ...metadataCache.get(url) });
        }
      }
    } else if (Array.isArray(cachedEntry)) {
      for (const item of cachedEntry) {
        if (!item || !item.webpageUrl) continue;
        const merged = metadataCache.has(item.webpageUrl)
          ? { ...item, ...metadataCache.get(item.webpageUrl) }
          : { ...item };
        cachedResults.push(merged);
      }
    }

    if (cachedResults.length > 0) {
      console.log(`[Search] Returning ${cachedResults.length} from queryCache!`);
      startBackgroundEnrichment(cachedResults, { query, durationPreference });
      return cachedResults;
    }
  }

  try {
    let engineOffset = offset;
    let loopCount = 0;
    const MAX_LOOPS = 3;
    let enrichBudgetRemaining = SEARCH_ENRICH_MAX_PER_REQUEST;
    const backgroundEnrichPool = new Map();

    while (allResults.length < queryLimit && loopCount < MAX_LOOPS) {
      loopCount++;
      let results = [];
      
      try {
        const ddgSiteFilter = sourceFilter === 'all' || sourceFilter === 'other' ? 'both' : sourceFilter;
        results = await searchDuckDuckGo(query, { offset: engineOffset, limit, siteFilter: ddgSiteFilter });
      } catch (ddgError) {
        console.warn(`[Search] DuckDuckGo fallback to yt-dlp:`, ddgError.message);
        const fallbackTasks = [];
        if (sourceFilter === 'all' || sourceFilter === 'youtube' || sourceFilter === 'other') {
          fallbackTasks.push(searchYtdlp(query, 'youtube', engineOffset, limit));
        }
        const fallbackResults = await Promise.allSettled(fallbackTasks);
        results = fallbackResults
          .filter((r) => r.status === 'fulfilled')
          .flatMap((r) => r.value);
      }

      const supplementPromises = [];

      // Supplement with YouTube search data for better recall of official uploads.
      if (sourceFilter === 'all' || sourceFilter === 'youtube' || sourceFilter === 'other') {
        supplementPromises.push(
          searchYtdlp(query, 'youtube', engineOffset, Math.max(limit, 20))
            .then((youtubeSupplement) => {
              console.log(`[Search] Added ${youtubeSupplement.length} youtube supplemental results from yt-dlp`);
              return youtubeSupplement;
            })
            .catch((youtubeError) => {
              console.warn('[Search] YouTube supplement failed:', youtubeError.message);
              return [];
            })
        );
      }

      // Supplement with Niconico first-party search data
      if (sourceFilter === 'all' || sourceFilter === 'niconico' || sourceFilter === 'other') {
        supplementPromises.push(
          searchNiconicoDirect(query, engineOffset, Math.max(limit, 20))
            .then((niconicoDirect) => {
              console.log(`[Search] Added ${niconicoDirect.length} niconico direct results from site search`);
              return niconicoDirect;
            })
            .catch((directError) => {
              console.warn('[Search] Niconico direct search failed:', directError.message);
              return [];
            })
        );
      }

      const resolvedSupplements = await Promise.all(supplementPromises);
      const seenUrlsMap = new Map(results.map((r) => [r.webpageUrl, r]));
      for (const supplementList of resolvedSupplements) {
        for (const item of supplementList) {
          const existing = seenUrlsMap.get(item.webpageUrl);
          if (!existing) {
            results.push(item);
            seenUrlsMap.set(item.webpageUrl, item);
          } else {
            // Merge rich metadata from supplement into the DDG result
            if (!existing.viewCount && item.viewCount) existing.viewCount = item.viewCount;
            if (!existing.likeCount && item.likeCount) existing.likeCount = item.likeCount;
            if (!existing.duration && item.duration) existing.duration = item.duration;
            if (!existing.durationSec && item.durationSec) existing.durationSec = item.durationSec;
            if ((!existing.uploader || existing.uploader === '-' || existing.uploader === 'Unknown') && item.uploader) {
              existing.uploader = item.uploader;
            }
          }
        }
      }

      results = results.filter((item) => matchesFilterValue(item.site, sourceFilter));
      results = rankResults(query, results, durationPreference);

      let candidatePool = sourceFilter === 'all' ? buildBalancedPage(results, 50, 3) : results;
      candidatePool.forEach(item => backgroundEnrichPool.set(item.webpageUrl, item));

      const BATCH_SIZE = 10;
      
      for (let i = 0; i < candidatePool.length; i += BATCH_SIZE) {
        let batch = candidatePool.slice(i, i + BATCH_SIZE);
        const enrichCap = Math.min(SEARCH_ENRICH_MAX_PER_BATCH, enrichBudgetRemaining);
        if (enrichCap > 0) {
          // Fire-and-forget lightweight enrichment to avoid blocking first render.
          enrichMetadata(batch.map((item) => ({ ...item })), { 
            maxItems: enrichCap,
            queryContext: { query, durationPreference }
          }).catch(() => {});
          enrichBudgetRemaining -= enrichCap;
        }
        
        // Do not hard-filter by duration here. Duration preference is handled in ranking score.
        
        for (const validItem of batch) {
          if (allResults.length < queryLimit && !allResults.some(r => r.webpageUrl === validItem.webpageUrl)) {
            allResults.push(validItem);
          }
        }
        
        if (allResults.length >= queryLimit) {
          break;
        }
      }
      
      if (allResults.length < queryLimit) {
        engineOffset += 20; // Try next page on engines
      }
    }
    
    if (allResults.length > 0) {
      queryCache.set(queryCacheKey, allResults.map((item) => ({ ...item })));
      // Enrich up to 50 items so accurate candidates bubble up
      const toEnrich = Array.from(backgroundEnrichPool.values()).slice(0, 50);
      startBackgroundEnrichment(toEnrich, { query, durationPreference });
    }
  } catch (error) {
    console.error(`[Search] All search methods failed:`, error.message);
    throw new Error(`検索に失敗しました。\n${error.message}\n\n別のキーワードまたは動画URLを直接貼り付けてください。`);
  }

  if (allResults.length === 0) {
    throw new Error('検索結果が見つかりませんでした。別のキーワードを試してください。');
  }

  return allResults;
});

ipcMain.handle('preview:getStreamUrl', async (_event, { url }) => {
  if (!url || typeof url !== 'string') {
    throw new Error('試聴URLが不正です');
  }

  const ytdlp = await findExecutable('yt-dlp');
  if (!fs.existsSync(ytdlp)) {
    throw new Error('yt-dlp not found. Install it with: brew install yt-dlp');
  }

  // Resolve direct audio stream URL for preview playback.
  const args = [
    '-f',
    'bestaudio/best',
    '--no-playlist',
    '-g',
    url,
  ];

  try {
    const { stdout } = await runCommand(ytdlp, args);
    const candidates = stdout
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.startsWith('http://') || line.startsWith('https://'));

    if (candidates.length === 0) {
      throw new Error('ストリームURLを取得できませんでした');
    }

    const streamUrl = candidates[0];
    console.log('[Preview] Resolved stream URL for preview:', streamUrl.slice(0, 120));
    return { streamUrl };
  } catch (error) {
    console.error('[Preview] Failed to resolve stream URL:', error.message);
    throw new Error(`試聴に失敗しました: ${error.message}`);
  }
});

// Removed preparePreviewAudio since we moved to direct proxy streaming.
ipcMain.handle('download:audio', async (_event, { url, saveDir, audioFormat = 'auto' }) => {
  ensureWritableDir(saveDir);

  const ytdlp = await findExecutable('yt-dlp');
  const ffmpeg = await findExecutable('ffmpeg');
  if (!fs.existsSync(ytdlp)) {
    throw new Error('yt-dlp not found. Install it with: brew install yt-dlp');
  }
  if (!fs.existsSync(ffmpeg)) {
    throw new Error('ffmpeg not found. Install it with: brew install ffmpeg');
  }

  // Get a unique temp identifier for this download
  const tempPrefix = `temp_${Date.now()}_`;
  const tempOutputPattern = path.join(saveDir, `${tempPrefix}%(title).100s.%(ext)s`);

  const normalizedFormat = String(audioFormat || 'auto').toLowerCase();
  const safeFormat = ALLOWED_DOWNLOAD_AUDIO_FORMATS.has(normalizedFormat) ? normalizedFormat : 'auto';

  // Try selected format, or fallback chain in auto mode.
  const formats = safeFormat === 'auto' ? ['mp3', 'm4a'] : [safeFormat];
  let lastError = null;
  let downloadedFile = null;

  for (const format of formats) {
    try {
      const args = [
        '-f',
        'bestaudio/best',
        '--extract-audio',
        '--audio-format',
        format,
        '--ffmpeg-location',
        ffmpeg,
        '-o',
        tempOutputPattern,
        '--no-playlist',
        '-v',  // Verbose for debugging
        url,
      ];

      console.log(`[Download] Attempting ${format.toUpperCase()} format for URL:`, url);
      const { stderr } = await runCommand(ytdlp, args);
      console.log(`[Download] yt-dlp output:`, stderr);

      // Find the downloaded file
      const files = fs.readdirSync(saveDir);
      const tempCandidates = files.filter((f) => {
        if (!f.startsWith(tempPrefix)) return false;
        const ext = path.extname(f).toLowerCase();
        return AUDIO_EXTS.includes(ext);
      });

      const preferredExt = `.${format}`;
      downloadedFile = tempCandidates.find((f) => path.extname(f).toLowerCase() === preferredExt);
      if (!downloadedFile) {
        // Fallback: pick newest temp audio file even if extension differs.
        tempCandidates.sort((a, b) => {
          const aPath = path.join(saveDir, a);
          const bPath = path.join(saveDir, b);
          return fs.statSync(bPath).mtimeMs - fs.statSync(aPath).mtimeMs;
        });
        downloadedFile = tempCandidates[0] || null;
      }

      if (downloadedFile) {
        console.log(`[Download] Successfully downloaded as ${format.toUpperCase()}:`, downloadedFile);
        break; // Success, exit loop
      }
    } catch (error) {
      lastError = error;
      console.error(`[Download] Error with ${format.toUpperCase()}:`, error.message);
      // Continue to next format
    }
  }

  if (!downloadedFile) {
    // Clean up any temp files
    const files = fs.readdirSync(saveDir);
    files.forEach((f) => {
      if (f.startsWith(tempPrefix)) {
        try {
          fs.unlinkSync(path.join(saveDir, f));
        } catch (e) {}
      }
    });
    throw new Error(`ダウンロード失敗: ${lastError ? lastError.message : 'ファイルが作成されませんでした'}`);
  }

  const oldPath = path.join(saveDir, downloadedFile);
  const stats = fs.statSync(oldPath);

  console.log(`[Download] File info:`, {
    filename: downloadedFile,
    size: stats.size,
    readable: fs.constants.R_OK ? 'yes' : 'no',
  });

  // Check if file is not empty (at least 100KB)
  if (stats.size < 102400) {
    console.warn(`[Download] File size too small (${Math.round(stats.size / 1024)}KB), deleting`);
    fs.unlinkSync(oldPath);
    throw new Error(`ファイルサイズが小さすぎます (${Math.round(stats.size / 1024)}KB)。ダウンロード失敗の可能性があります。`);
  }

  // Remove temp prefix from final filename
  const finalName = downloadedFile.substring(tempPrefix.length);
  const newPath = path.join(saveDir, finalName);

  // If file with final name already exists, append number
  let finalPath = newPath;
  let counter = 1;
  while (fs.existsSync(finalPath)) {
    const ext = path.extname(newPath);
    const nameWithoutExt = path.basename(newPath, ext);
    finalPath = path.join(saveDir, `${nameWithoutExt}_${counter}${ext}`);
    counter++;
  }

  fs.renameSync(oldPath, finalPath);
  const fileExt = path.extname(finalPath).toLowerCase();

  console.log(`[Download] Complete:`, {
    finalPath,
    format: fileExt,
    size: stats.size,
  });

  return { ok: true, filename: path.basename(finalPath), format: fileExt };
});

ipcMain.handle('files:listAudio', async (_event, { saveDir }) => {
  const normalized = assertPathInLibrary(ensureWritableDir(saveDir));
  return listAudioFiles(normalized);
});

ipcMain.handle('files:createFolderAt', async (_event, { parentDir, name }) => {
  if (!parentDir || typeof parentDir !== 'string') {
    throw new Error('作成先フォルダが不正です');
  }
  if (!name || typeof name !== 'string') {
    throw new Error('フォルダ名を入力してください');
  }

  const trimmed = name.trim();
  if (!trimmed) {
    throw new Error('フォルダ名を入力してください');
  }
  if (trimmed === '.' || trimmed === '..') {
    throw new Error('そのフォルダ名は使えません');
  }
  if (/[\\/]/.test(trimmed)) {
    throw new Error('フォルダ名に / や \\ は使えません');
  }

  const resolvedParent = ensureWritableDir(parentDir);
  const resolvedTarget = path.resolve(path.join(resolvedParent, trimmed));
  if (!isPathWithin(resolvedParent, resolvedTarget)) {
    throw new Error('不正なフォルダパスです');
  }
  if (fs.existsSync(resolvedTarget)) {
    throw new Error('同名のファイルまたはフォルダが既に存在します');
  }

  fs.mkdirSync(resolvedTarget, { recursive: false });
  return { ok: true, fullPath: resolvedTarget, name: trimmed };
});

ipcMain.handle('files:createFolder', async (_event, { parentDir, name }) => {
  if (!parentDir || typeof parentDir !== 'string') {
    throw new Error('作成先フォルダが不正です');
  }
  if (!name || typeof name !== 'string') {
    throw new Error('フォルダ名を入力してください');
  }

  const trimmed = name.trim();
  if (!trimmed) {
    throw new Error('フォルダ名を入力してください');
  }
  if (trimmed === '.' || trimmed === '..') {
    throw new Error('そのフォルダ名は使えません');
  }
  if (/[\\/]/.test(trimmed)) {
    throw new Error('フォルダ名に / や \\ は使えません');
  }

  const resolvedParent = assertPathInLibrary(ensureWritableDir(parentDir));
  const targetPath = path.join(resolvedParent, trimmed);
  const resolvedTarget = path.resolve(targetPath);

  if (!isPathWithin(resolvedParent, resolvedTarget)) {
    throw new Error('不正なフォルダパスです');
  }
  if (fs.existsSync(resolvedTarget)) {
    throw new Error('同名のファイルまたはフォルダが既に存在します');
  }

  fs.mkdirSync(resolvedTarget, { recursive: false });
  return { ok: true, fullPath: resolvedTarget, name: trimmed };
});

ipcMain.handle('files:moveToNewFolder', async (_event, { parentDir, folderName, filePaths }) => {
  if (!parentDir || typeof parentDir !== 'string') {
    throw new Error('移動先の親フォルダが不正です');
  }
  if (!folderName || typeof folderName !== 'string') {
    throw new Error('新規フォルダ名を入力してください');
  }
  if (!Array.isArray(filePaths) || filePaths.length === 0) {
    throw new Error('移動する曲を選択してください');
  }

  const trimmed = folderName.trim();
  if (!trimmed) {
    throw new Error('新規フォルダ名を入力してください');
  }
  if (trimmed === '.' || trimmed === '..') {
    throw new Error('そのフォルダ名は使えません');
  }
  if (/[\\/]/.test(trimmed)) {
    throw new Error('フォルダ名に / や \\ は使えません');
  }

  const resolvedParent = assertPathInLibrary(ensureWritableDir(parentDir));
  const destDir = path.resolve(path.join(resolvedParent, trimmed));

  if (!isPathWithin(resolvedParent, destDir)) {
    throw new Error('不正な移動先です');
  }
  if (fs.existsSync(destDir)) {
    throw new Error('同名のフォルダが既に存在します');
  }

  fs.mkdirSync(destDir, { recursive: false });

  let movedCount = 0;
  for (const src of filePaths) {
    if (!src || typeof src !== 'string') continue;
    const resolvedSrc = path.resolve(src);

    if (!isPathWithin(resolvedParent, resolvedSrc)) {
      throw new Error('選択ファイルに不正なパスが含まれています');
    }
    if (!fs.existsSync(resolvedSrc)) {
      continue;
    }
    const stat = fs.statSync(resolvedSrc);
    if (!stat.isFile()) {
      continue;
    }

    const originalName = path.basename(resolvedSrc);
    const ext = path.extname(originalName);
    const base = path.basename(originalName, ext);

    let destPath = path.join(destDir, originalName);
    let counter = 1;
    while (fs.existsSync(destPath)) {
      destPath = path.join(destDir, `${base}_${counter}${ext}`);
      counter += 1;
    }

    fs.renameSync(resolvedSrc, destPath);
    movedCount += 1;
  }

  return { ok: true, movedCount, folderPath: destDir, folderName: trimmed };
});

ipcMain.handle('files:openInFinder', async (_event, { filePath }) => {
  await platform.openPathInFileManager(filePath);
});

ipcMain.handle('platform:getInfo', () => ({
  platform: process.platform,
  platformLabel: platform.getPlatformLabel(),
  fileManagerLabel: platform.getFileManagerLabel(),
  libraryDir: getCurrentSaveDir(),
}));

ipcMain.handle('links:openExternal', async (_event, { url }) => {
  if (!url || typeof url !== 'string') {
    throw new Error('URLが不正です');
  }
  await shell.openExternal(url);
  return { ok: true };
});

ipcMain.handle('clipboard:writeText', async (_event, { text }) => {
  const safeText = String(text || '');
  clipboard.writeText(safeText);
  return { ok: true };
});

ipcMain.handle('playlists:get', () => {
  return getStoredPlaylists();
});

ipcMain.handle('playlists:create', (_event, { name }) => {
  const trimmed = String(name || '').trim();
  if (!trimmed) {
    throw new Error('プレイリスト名を入力してください');
  }

  const playlists = getStoredPlaylists();
  const created = {
    id: generatePlaylistId(),
    name: trimmed,
    createdAt: Date.now(),
    items: [],
  };
  playlists.unshift(created);
  setStoredPlaylists(playlists);
  return created;
});

ipcMain.handle('playlists:addItems', (_event, { playlistId, items }) => {
  const targetId = String(playlistId || '').trim();
  if (!targetId) {
    throw new Error('追加先プレイリストが不正です');
  }
  if (!Array.isArray(items) || items.length === 0) {
    throw new Error('追加する曲がありません');
  }

  const playlists = getStoredPlaylists();
  const index = playlists.findIndex((playlist) => playlist.id === targetId);
  if (index < 0) {
    throw new Error('指定されたプレイリストが見つかりません');
  }

  const playlist = playlists[index];
  const existingKeys = new Set(playlist.items.map((item) => playlistItemKey(item)));

  let addedCount = 0;
  for (const rawItem of items) {
    const normalized = normalizePlaylistItem(rawItem);
    if (!normalized) {
      continue;
    }
    const key = playlistItemKey(normalized);
    if (!key || existingKeys.has(key)) {
      continue;
    }
    existingKeys.add(key);
    playlist.items.push(normalized);
    addedCount += 1;
  }

  playlists[index] = playlist;
  setStoredPlaylists(playlists);
  return { ok: true, addedCount, playlist: playlists[index] };
});

ipcMain.handle('playlists:reorderItems', (_event, { playlistId, fromIndex, toIndex }) => {
  const targetId = String(playlistId || '').trim();
  if (!targetId) {
    throw new Error('プレイリストが不正です');
  }

  const from = Number(fromIndex);
  const to = Number(toIndex);
  if (!Number.isInteger(from) || !Number.isInteger(to)) {
    throw new Error('並べ替え位置が不正です');
  }

  const playlists = getStoredPlaylists();
  const index = playlists.findIndex((playlist) => playlist.id === targetId);
  if (index < 0) {
    throw new Error('指定されたプレイリストが見つかりません');
  }

  const items = playlists[index].items;
  if (from < 0 || from >= items.length || to < 0 || to >= items.length) {
    throw new Error('並べ替え位置が不正です');
  }
  if (from === to) {
    return { ok: true, playlist: playlists[index] };
  }

  const [moved] = items.splice(from, 1);
  items.splice(to, 0, moved);
  playlists[index].items = items;
  setStoredPlaylists(playlists);
  return { ok: true, playlist: playlists[index] };
});

ipcMain.handle('playlists:removeItems', (_event, { playlistId, itemIndexes }) => {
  const targetId = String(playlistId || '').trim();
  if (!targetId) {
    throw new Error('プレイリストが不正です');
  }
  if (!Array.isArray(itemIndexes) || itemIndexes.length === 0) {
    throw new Error('削除する曲がありません');
  }

  const playlists = getStoredPlaylists();
  const index = playlists.findIndex((playlist) => playlist.id === targetId);
  if (index < 0) {
    throw new Error('指定されたプレイリストが見つかりません');
  }

  const removeSet = new Set(
    itemIndexes
      .map((value) => Number(value))
      .filter((value) => Number.isInteger(value) && value >= 0),
  );
  if (removeSet.size === 0) {
    throw new Error('削除する曲がありません');
  }

  const items = playlists[index].items.filter((_item, itemIndex) => !removeSet.has(itemIndex));
  playlists[index].items = items;
  setStoredPlaylists(playlists);
  return { ok: true, removedCount: removeSet.size, playlist: playlists[index] };
});

ipcMain.handle('audio:getServerPort', () => {
  return audioServerPort;
});

ipcMain.handle('debug:getStreamLogPath', () => {
  return getStreamDebugLogPath();
});

ipcMain.handle('ui:saveState', (_event, state) => {
  store.set('uiState', state);
  console.log('[UI State] Saved:', state);
});

ipcMain.handle('ui:loadState', () => {
  const state = store.get('uiState');
  console.log('[UI State] Loaded:', state);
  return state;
});

ipcMain.handle('clear:cache', async () => {
  metadataCache.clear();
  queryCache.clear();
  try {
    if (CACHE_DIR) {
      await fsPromises.rm(CACHE_DIR, { recursive: true, force: true });
      await fsPromises.mkdir(CACHE_DIR, { recursive: true });
    }
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e.message };
  }
});

ipcMain.handle('syncthing:getInfo', async () => {
  try {
    return await syncthingManager.getInfo();
  } catch (e) {
    return { ok: false, error: e.message };
  }
});

ipcMain.handle('syncthing:addDevice', async (_event, { deviceID }) => {
  try {
    const res = await syncthingManager.addDevice(deviceID);
    const info = await syncthingManager.getInfo();
    return { ...res, info };
  } catch (e) {
    return { success: false, error: e.message };
  }
});

app.whenReady().then(async () => {
  paths.setUserDataResolver(() => app.getPath('userData'));

  // Start audio streaming server first
  try {
    await startAudioServer();
  } catch (error) {
    console.error('[App] Failed to start audio server:', error);
  }

  try {
    initializeLibrary();
  } catch (err) {
    console.error('[Library] Failed to initialize library directory:', err.message);
  }

  loadCaches().catch((err) => {
    console.error('[Cache] Failed to load caches on startup:', err.message);
  });

  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  // Stop audio server
  if (audioServer) {
    audioServer.close();
    audioServer = null;
    audioServerPort = null;
  }

  if (process.platform !== 'darwin') {
    app.quit();
  }
});
