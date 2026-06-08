#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const { startMediaServer } = require('../index');

function loadEnvFile(envPath) {
  if (!fs.existsSync(envPath)) {
    return;
  }
  for (const line of fs.readFileSync(envPath, 'utf8').split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) {
      continue;
    }
    const eq = trimmed.indexOf('=');
    if (eq <= 0) {
      continue;
    }
    const key = trimmed.slice(0, eq);
    const value = trimmed.slice(eq + 1);
    if (!process.env[key]) {
      process.env[key] = value;
    }
  }
}

loadEnvFile(path.join(__dirname, '..', '.env'));

const port = Number(process.env.MEDIA_SERVER_PORT || 8765);
const host = process.env.MEDIA_SERVER_HOST || '0.0.0.0';
const libraryRoot = process.env.LIBRARY_ROOT
  || path.join(process.env.HOME || '/home/droid', 'media-audio-finder-library');

startMediaServer({ port, host, libraryRoot })
  .then(({ port: boundPort }) => {
    console.log(`[Media Server] Ready on http://${host}:${boundPort}`);
    console.log(`[Media Server] Library: ${libraryRoot}`);
  })
  .catch((err) => {
    console.error('[Media Server] Failed to start:', err.message);
    process.exit(1);
  });
