'use strict';

const express = require('express');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { promisify } = require('util');

const execFile = promisify(require('child_process').execFile);

const AUDIO_EXTS = ['.mp3', '.m4a', '.aac', '.webm', '.wav', '.ogg', '.flac', '.opus', '.wma'];
const ALLOWED_DOWNLOAD_AUDIO_FORMATS = new Set(['auto', 'mp3', 'm4a', 'wav', 'flac', 'opus']);

const MIME_BY_EXT = {
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

function isPathWithin(parent, target) {
  const parentResolved = path.resolve(parent);
  const targetResolved = path.resolve(target);
  return targetResolved === parentResolved || targetResolved.startsWith(`${parentResolved}${path.sep}`);
}

function aliasLibraryPaths(inputPath) {
  const resolved = path.resolve(inputPath);
  const aliases = new Set([resolved]);
  const pairs = [
    ['/mnt/shared/Android/', '/mnt/shared/0/Android/'],
    ['/mnt/shared/0/Android/', '/mnt/shared/Android/'],
  ];
  for (const [from, to] of pairs) {
    if (resolved.includes(from)) {
      aliases.add(resolved.replace(from, to));
    }
  }
  return [...aliases];
}

function resolvePathWithinRoots(rawPath, roots) {
  if (!rawPath || typeof rawPath !== 'string') {
    return null;
  }
  let foundMissing = null;
  for (const alias of aliasLibraryPaths(rawPath)) {
    for (const root of roots) {
      if (!root || !isPathWithin(root, alias)) {
        continue;
      }
      if (fs.existsSync(alias)) {
        return alias;
      }
      if (!foundMissing) {
        foundMissing = alias;
      }
    }
  }
  return foundMissing;
}

const YTDLP_EJS_ARGS = [
  '--js-runtimes', 'node',
  '--remote-components', 'ejs:github',
];

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

function runCommand(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      stdio: ['ignore', 'pipe', 'pipe'],
      ...options,
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

async function findExecutable(name) {
  const envPath = process.env.PATH || '';
  const candidates = [
    process.env[`${name.toUpperCase().replace(/-/g, '_')}_PATH`],
    `/usr/bin/${name}`,
    `/usr/local/bin/${name}`,
    ...envPath.split(':').map((dir) => path.join(dir, name)),
  ].filter(Boolean);

  for (const candidate of candidates) {
    if (candidate === name) {
      continue;
    }
    if (fs.existsSync(candidate)) {
      try {
        fs.accessSync(candidate, fs.constants.X_OK);
        return candidate;
      } catch (_e) {
        // keep searching
      }
    }
  }

  try {
    const { stdout } = await execFile('which', [name], { encoding: 'utf8' });
    const resolved = stdout.trim();
    if (resolved) {
      return resolved;
    }
  } catch (_e) {
    // ignore
  }

  return name;
}

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
  return profile;
}

function corsMiddleware(_req, res, next) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  next();
}

const YTDLP_STREAM_ARGS = [
  '--no-playlist',
  '--no-warnings',
  '-q',
  '--retries', '3',
  '--socket-timeout', '30',
  ...YTDLP_EJS_ARGS,
  '--extractor-args', 'youtube:player_client=android,web',
  '--user-agent', 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
];

async function resolveDirectStreamUrl(ytdlp, pageUrl, workDir) {
  const formats = ['ba/b', 'bestaudio/best'];
  for (const format of formats) {
    try {
      const { stdout } = await runCommand(ytdlp, [
        '-g',
        '-f', format,
        ...YTDLP_STREAM_ARGS,
        pageUrl,
      ], { cwd: workDir });
      const line = stdout
        .split('\n')
        .map((entry) => entry.trim())
        .find((entry) => entry.startsWith('http'));
      if (line) {
        console.log(`[ProxyStream] Direct URL resolved via format=${format}`);
        return line;
      }
    } catch (err) {
      console.error(`[ProxyStream] yt-dlp -g format=${format} failed:`, err.message);
    }
  }
  return null;
}

function createMediaServer(options = {}) {
  const libraryRoot = path.resolve(options.libraryRoot || path.join(process.env.HOME || '/tmp', 'library'));
  const previewDir = path.resolve(options.previewDir || path.join(libraryRoot, '.preview-cache'));
  const streamWorkDir = path.resolve(options.streamWorkDir || path.join(libraryRoot, '.stream-work'));

  fs.mkdirSync(libraryRoot, { recursive: true });
  fs.mkdirSync(previewDir, { recursive: true });
  fs.mkdirSync(streamWorkDir, { recursive: true });

  const app = express();
  app.use(corsMiddleware);
  app.use(express.json({ limit: '1mb' }));

  app.options('*', (_req, res) => {
    res.sendStatus(204);
  });

  app.get('/health', async (_req, res) => {
    try {
      const ytdlp = await findExecutable('yt-dlp');
      const ffmpeg = await findExecutable('ffmpeg');
      const ytdlpOk = fs.existsSync(ytdlp);
      const ffmpegOk = fs.existsSync(ffmpeg);
      res.json({
        ok: ytdlpOk && ffmpegOk,
        ytdlp: ytdlpOk ? ytdlp : null,
        ffmpeg: ffmpegOk ? ffmpeg : null,
        libraryRoot,
        port: options.port,
      });
    } catch (err) {
      res.status(500).json({ ok: false, error: err.message });
    }
  });

  app.get('/audio', async (req, res) => {
    const rawPath = req.query.path;
    if (!rawPath || typeof rawPath !== 'string') {
      return res.status(400).send('Missing path');
    }

    const realPath = resolvePathWithinRoots(rawPath, [libraryRoot, previewDir]);
    if (!realPath) {
      return res.status(403).send('Forbidden');
    }

    if (!fs.existsSync(realPath)) {
      return res.status(404).send('Not Found');
    }

    const stat = fs.statSync(realPath);
    const ext = path.extname(realPath).toLowerCase();
    const forceTranscode = req.query.transcode === '1' || req.query.transcode === 'true';
    const shouldTranscode = forceTranscode || WEB_TRANSCODE_EXTS.has(ext);

    res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
    res.setHeader('Pragma', 'no-cache');
    res.setHeader('Expires', '0');

    if (shouldTranscode) {
      try {
        const ffmpeg = await findExecutable('ffmpeg');
        if (!fs.existsSync(ffmpeg)) {
          return res.status(500).send('ffmpeg missing');
        }
        const transcodeProfile = await resolvePreviewTranscodeProfile(ffmpeg);
        res.setHeader('Content-Type', transcodeProfile.contentType);
        res.setHeader('Transfer-Encoding', 'chunked');

        const ff = spawn(ffmpeg, [
          '-hide_banner',
          '-loglevel', 'error',
          '-i', realPath,
          '-vn',
          '-map_metadata', '-1',
          ...transcodeProfile.ffmpegArgs,
          '-y',
          'pipe:1',
        ], { cwd: streamWorkDir });

        ff.stdout.pipe(res);
        ff.on('error', () => {
          if (!res.headersSent) {
            res.status(500).send('Transcode failed');
          }
        });
        ff.on('close', (code) => {
          if (code !== 0 && !res.headersSent) {
            res.status(500).send('Transcode failed');
          }
        });
        return;
      } catch (err) {
        return res.status(500).send(err.message || 'Transcode failed');
      }
    }

    const contentType = MIME_BY_EXT[ext] || 'audio/mpeg';
    res.setHeader('Content-Type', contentType);
    res.setHeader('Content-Length', stat.size);
    res.setHeader('Accept-Ranges', 'bytes');

    const stream = fs.createReadStream(realPath);
    stream.pipe(res);
    stream.on('error', () => {
      if (!res.headersSent) {
        res.status(500).send('Server Error');
      }
    });
  });

  app.get('/stream', async (req, res) => {
    const url = req.query.url;
    if (!url || typeof url !== 'string') {
      return res.status(400).send('Missing url');
    }

    let yt = null;
    let ff = null;
    let isEnded = false;
    let responseStarted = false;
    let startupTimer = null;

    const cleanup = (reason) => {
      if (isEnded) {
        return;
      }
      isEnded = true;
      if (startupTimer) {
        clearTimeout(startupTimer);
        startupTimer = null;
      }
      console.log(`[ProxyStream] Cleanup: ${reason}`);
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
        return res.status(500).send('Dependencies missing');
      }

      const transcodeProfile = await resolvePreviewTranscodeProfile(ffmpeg);

      const directUrl = await resolveDirectStreamUrl(ytdlp, url, streamWorkDir);
      const useDirectUrl = Boolean(directUrl);

      const beginResponse = () => {
        if (responseStarted || isEnded) {
          return;
        }
        responseStarted = true;
        if (startupTimer) {
          clearTimeout(startupTimer);
          startupTimer = null;
        }
        res.setHeader('Content-Type', transcodeProfile.contentType);
        res.setHeader('Transfer-Encoding', 'chunked');
        res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
        res.setHeader('Pragma', 'no-cache');
        res.setHeader('Expires', '0');
      };

      startupTimer = setTimeout(() => {
        if (!responseStarted && !isEnded) {
          cleanup('startup timeout');
          if (!res.headersSent) {
            res.status(504).send('Stream startup timeout (yt-dlp/ffmpeg produced no audio)');
          }
        }
      }, useDirectUrl ? 45000 : 25000);

      if (useDirectUrl) {
        ff = spawn(ffmpeg, [
          '-hide_banner',
          '-loglevel', 'error',
          '-reconnect', '1',
          '-reconnect_streamed', '1',
          '-reconnect_delay_max', '5',
          '-fflags', '+nobuffer',
          '-i', directUrl,
          '-vn',
          '-map_metadata', '-1',
          ...transcodeProfile.ffmpegArgs,
          '-y',
          'pipe:1',
        ], { cwd: streamWorkDir });
      } else {
        console.log('[ProxyStream] Falling back to yt-dlp stdout pipe');
        yt = spawn(ytdlp, [
          '-o', '-',
          '-f', 'ba/b',
          ...YTDLP_STREAM_ARGS,
          url,
        ], { cwd: streamWorkDir });

        ff = spawn(ffmpeg, [
          '-hide_banner',
          '-loglevel', 'error',
          '-fflags', '+nobuffer',
          '-i', 'pipe:0',
          '-vn',
          '-map_metadata', '-1',
          ...transcodeProfile.ffmpegArgs,
          '-y',
          'pipe:1',
        ], { cwd: streamWorkDir });
      }

      if (yt?.stderr) {
        yt.stderr.on('data', (chunk) => {
          const msg = chunk.toString().trim();
          if (msg) {
            console.error('[ProxyStream] yt-dlp stderr:', msg);
          }
        });
      }
      if (ff.stderr) {
        ff.stderr.on('data', (chunk) => {
          const msg = chunk.toString().trim();
          if (msg) {
            console.error('[ProxyStream] ffmpeg stderr:', msg);
          }
        });
      }

      res.on('error', () => cleanup('response error'));
      res.on('close', () => cleanup('client disconnect'));

      if (yt) {
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
        yt.stdout.pipe(ff.stdin);
      }

      ff.stdout.on('error', (e) => {
        if (e.code !== 'EPIPE' && !isEnded) {
          console.error('[ProxyStream] ff.stdout error:', e.message);
        }
      });

      ff.stdout.on('data', (chunk) => {
        beginResponse();
        res.write(chunk);
      });

      ff.on('close', (code) => {
        if (!responseStarted && !isEnded) {
          cleanup('ffmpeg produced no output');
          if (!res.headersSent) {
            res.status(500).send('Preview transcode failed');
          }
          return;
        }
        if (!isEnded) {
          res.end();
          cleanup(`ffmpeg close code=${code}`);
        }
      });

      if (yt) {
        yt.on('error', (err) => {
          if (!isEnded) {
            console.error('[ProxyStream] yt-dlp error:', err.message);
            cleanup('yt-dlp process error');
            if (!res.headersSent) {
              res.status(500).send('yt-dlp failed');
            }
          }
        });
        yt.on('close', (code) => {
          if (code !== 0 && !responseStarted && !isEnded) {
            cleanup(`yt-dlp exit code=${code}`);
            if (!res.headersSent) {
              res.status(500).send('yt-dlp failed to fetch audio');
            }
          }
        });
      }

      ff.on('error', (err) => {
        if (!isEnded) {
          console.error('[ProxyStream] ffmpeg error:', err.message);
          cleanup('ffmpeg process error');
          if (!res.headersSent) {
            res.status(500).send('ffmpeg failed');
          }
        }
      });
    } catch (err) {
      console.error('[ProxyStream] Fatal:', err.message);
      cleanup('fatal');
      if (!res.headersSent) {
        res.status(500).send('Streaming setup failed');
      }
    }
  });

  app.post('/api/search', async (req, res) => {
    try {
      const query = String(req.body?.query || '').trim();
      if (!query) {
        return res.status(400).json({ ok: false, error: '検索キーワードを入力してください' });
      }

      const ytdlp = await findExecutable('yt-dlp');
      if (!fs.existsSync(ytdlp)) {
        return res.status(500).json({ ok: false, error: 'yt-dlp が見つかりません' });
      }

      const args = [
        `ytsearch10:${query}`,
        '--flat-playlist',
        '-J',
        '--no-warnings',
        '-q',
      ];
      const { stdout } = await runCommand(ytdlp, args);
      const parsed = JSON.parse(stdout);
      const entries = parsed.entries || [];
      const results = entries
        .filter(Boolean)
        .map((entry) => ({
          title: entry.title || '(No title)',
          uploader: entry.uploader || '-',
          duration: formatDuration(entry.duration),
          webpageUrl: entry.webpage_url || entry.url || '',
          site: 'YouTube',
        }))
        .filter((item) => item.webpageUrl);

      res.json({ ok: true, results });
    } catch (err) {
      res.status(500).json({ ok: false, error: err.message });
    }
  });

  app.post('/api/download', async (req, res) => {
    try {
      const url = String(req.body?.url || '').trim();
      let saveDir = String(req.body?.saveDir || libraryRoot).trim();
      const audioFormat = String(req.body?.audioFormat || 'auto').toLowerCase();

      if (!url) {
        return res.status(400).json({ ok: false, error: 'URLが不正です' });
      }

      saveDir = resolvePathWithinRoots(saveDir, [libraryRoot]);
      if (!saveDir) {
        return res.status(403).json({ ok: false, error: 'ライブラリ外への保存はできません' });
      }
      fs.mkdirSync(saveDir, { recursive: true });

      const ytdlp = await findExecutable('yt-dlp');
      const ffmpeg = await findExecutable('ffmpeg');
      if (!fs.existsSync(ytdlp) || !fs.existsSync(ffmpeg)) {
        return res.status(500).json({ ok: false, error: 'yt-dlp または ffmpeg が見つかりません' });
      }

      const tempPrefix = `temp_${Date.now()}_`;
      const tempOutputPattern = path.join(saveDir, `${tempPrefix}%(title).100s.%(ext)s`);
      const safeFormat = ALLOWED_DOWNLOAD_AUDIO_FORMATS.has(audioFormat) ? audioFormat : 'auto';
      const formats = safeFormat === 'auto' ? ['mp3', 'm4a'] : [safeFormat];

      let lastError = null;
      let downloadedFile = null;

      for (const format of formats) {
        try {
          const args = [
            '-f', 'bestaudio/best',
            '--extract-audio',
            '--audio-format', format,
            '--ffmpeg-location', ffmpeg,
            '-o', tempOutputPattern,
            '--no-playlist',
            url,
          ];
          await runCommand(ytdlp, args);

          const files = fs.readdirSync(saveDir);
          const tempCandidates = files.filter((f) => {
            if (!f.startsWith(tempPrefix)) {
              return false;
            }
            return AUDIO_EXTS.includes(path.extname(f).toLowerCase());
          });

          downloadedFile = tempCandidates.find((f) => path.extname(f).toLowerCase() === `.${format}`);
          if (!downloadedFile && tempCandidates.length > 0) {
            tempCandidates.sort((a, b) => {
              return fs.statSync(path.join(saveDir, b)).mtimeMs - fs.statSync(path.join(saveDir, a)).mtimeMs;
            });
            downloadedFile = tempCandidates[0];
          }
          if (downloadedFile) {
            break;
          }
        } catch (err) {
          lastError = err;
        }
      }

      if (!downloadedFile) {
        return res.status(500).json({
          ok: false,
          error: lastError ? lastError.message : 'ダウンロードに失敗しました',
        });
      }

      const oldPath = path.join(saveDir, downloadedFile);
      const stats = fs.statSync(oldPath);
      if (stats.size < 102400) {
        fs.unlinkSync(oldPath);
        return res.status(500).json({ ok: false, error: 'ファイルサイズが小さすぎます' });
      }

      const finalName = downloadedFile.substring(tempPrefix.length);
      let finalPath = path.join(saveDir, finalName);
      let counter = 1;
      while (fs.existsSync(finalPath)) {
        const ext = path.extname(finalPath);
        const nameWithoutExt = path.basename(finalPath, ext);
        finalPath = path.join(saveDir, `${nameWithoutExt}_${counter}${ext}`);
        counter += 1;
      }

      fs.renameSync(oldPath, finalPath);
      res.json({ ok: true, filename: path.basename(finalPath) });
    } catch (err) {
      res.status(500).json({ ok: false, error: err.message });
    }
  });

  return { app, libraryRoot, previewDir, streamWorkDir };
}

function startMediaServer(options = {}) {
  const host = options.host || '127.0.0.1';
  const port = Number(options.port || 8765);
  const { app } = createMediaServer(options);

  return new Promise((resolve, reject) => {
    const server = app.listen(port, host, () => {
      const boundPort = server.address().port;
      resolve({ server, port: boundPort, app });
    });
    server.on('error', reject);
  });
}

module.exports = {
  createMediaServer,
  startMediaServer,
  findExecutable,
  formatDuration,
};
