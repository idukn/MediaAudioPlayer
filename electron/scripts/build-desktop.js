#!/usr/bin/env node
const { spawnSync } = require('child_process');
const os = require('os');

const platform = os.platform();
let target = 'build:mac';

if (platform === 'win32') {
  target = 'build:win';
} else if (platform === 'linux') {
  target = 'build:linux';
}

const result = spawnSync('npm', ['run', target], {
  stdio: 'inherit',
  shell: platform === 'win32',
});

process.exit(result.status ?? 1);
