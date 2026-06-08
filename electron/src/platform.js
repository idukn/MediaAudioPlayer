const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');
const { shell } = require('electron');

function getPlatformLabel() {
  if (process.platform === 'darwin') return 'macOS';
  if (process.platform === 'win32') return 'Windows';
  if (process.platform === 'linux') return 'Linux';
  return process.platform;
}

function getFileManagerLabel() {
  if (process.platform === 'darwin') return 'Finder';
  if (process.platform === 'win32') return 'エクスプローラー';
  return 'ファイルマネージャ';
}

async function openPathInFileManager(filePath) {
  if (!filePath) return;
  if (process.platform === 'darwin') {
    shell.showItemInFolder(filePath);
    return;
  }
  const stat = fs.existsSync(filePath) ? fs.statSync(filePath) : null;
  const target = stat?.isDirectory() ? filePath : path.dirname(filePath);
  await shell.openPath(target);
}

function resolveFromShell(name) {
  if (process.platform === 'win32') {
    return new Promise((resolve) => {
      const child = spawn('where', [name], {
        stdio: ['ignore', 'pipe', 'ignore'],
        windowsHide: true,
      });
      let out = '';
      child.stdout.on('data', (chunk) => {
        out += chunk.toString();
      });
      child.on('close', (code) => {
        if (code !== 0) {
          resolve(null);
          return;
        }
        const resolved = out
          .split(/\r?\n/)
          .map((line) => line.trim())
          .find((line) => line && fs.existsSync(line));
        resolve(resolved || null);
      });
      child.on('error', () => resolve(null));
    });
  }

  const shellPath = process.env.ComSpec
    || process.env.SHELL
    || (process.platform === 'win32' ? 'cmd.exe' : '/bin/sh');
  const shellArgs = process.platform === 'win32'
    ? ['/c', 'where', name]
    : ['-lc', `command -v ${name}`];

  return new Promise((resolve) => {
    const child = spawn(shellPath, shellArgs, {
      stdio: ['ignore', 'pipe', 'ignore'],
      windowsHide: true,
    });
    let out = '';
    child.stdout.on('data', (chunk) => {
      out += chunk.toString();
    });
    child.on('close', (code) => {
      if (code !== 0) {
        resolve(null);
        return;
      }
      const resolved = out.trim().split(/\r?\n/)[0];
      resolve(resolved && fs.existsSync(resolved) ? resolved : null);
    });
    child.on('error', () => resolve(null));
  });
}

function getExecutableCandidates(name) {
  const home = os.homedir();
  const winLocal = process.env.LOCALAPPDATA || path.join(home, 'AppData', 'Local');
  const winProgramFiles = process.env.ProgramFiles || 'C:\\Program Files';
  const winProgramFilesX86 = process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)';

  const ext = process.platform === 'win32' ? '.exe' : '';
  const binaryName = `${name}${ext}`;

  return [
    path.join(path.dirname(process.execPath), binaryName),
    path.join(winLocal, 'Programs', name, binaryName),
    path.join(winProgramFiles, name, binaryName),
    path.join(winProgramFilesX86, name, binaryName),
    path.join(home, 'scoop', 'shims', binaryName),
    path.join(home, 'AppData', 'Local', 'Microsoft', 'WinGet', 'Links', binaryName),
    path.join(home, 'anaconda3', 'bin', binaryName),
    path.join(home, 'miniconda3', 'bin', binaryName),
    path.join(home, 'micromamba', 'bin', binaryName),
    `/opt/homebrew/anaconda3/bin/${binaryName}`,
    `/opt/homebrew/Caskroom/miniconda/base/bin/${binaryName}`,
    path.join(home, '.local', 'bin', binaryName),
    path.join(home, 'Library', 'Python', '3.12', 'bin', binaryName),
    path.join(home, 'Library', 'Python', '3.11', 'bin', binaryName),
    `/opt/homebrew/bin/${binaryName}`,
    `/usr/local/bin/${binaryName}`,
    `/usr/bin/${binaryName}`,
    binaryName,
  ];
}

module.exports = {
  getPlatformLabel,
  getFileManagerLabel,
  openPathInFileManager,
  resolveFromShell,
  getExecutableCandidates,
};
