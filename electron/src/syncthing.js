const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn, execSync } = require('child_process');
const axios = require('axios');
const fsPromises = fs.promises;
const { app } = require('electron');

const VERSION = 'v1.27.6';
const FOLDER_ID = 'yt-audio-app-sync';
const DEVICE_ID_RE = /^[A-Z0-9]{7}(-[A-Z0-9]{7}){6}$/;

function normalizeDeviceID(raw) {
  const trimmed = String(raw || '').trim().toUpperCase();
  if (!trimmed) {
    return null;
  }
  if (DEVICE_ID_RE.test(trimmed)) {
    return trimmed;
  }
  const compact = trimmed.replace(/-/g, '');
  if (compact.length !== 49 || !/^[A-Z2-7]+$/.test(compact)) {
    return null;
  }
  const parts = compact.match(/.{1,7}/g);
  return parts.join('-');
}

function getReleaseDetails() {
  const platform = os.platform();
  const arch = os.arch();
  
  let osName = '';
  let archName = '';
  let ext = '.tar.gz';

  if (platform === 'darwin') {
    osName = 'macos';
    ext = '.zip';
  } else if (platform === 'win32') {
    osName = 'windows';
    ext = '.zip';
  } else if (platform === 'linux') {
    osName = 'linux';
  } else if (platform === 'android') {
    // Official releases ship linux-arm64; runs on most Android devices.
    osName = 'linux';
    archName = 'arm64';
    ext = '.tar.gz';
  } else {
    throw new Error(`Unsupported platform: ${platform}`);
  }

  if (arch === 'arm64') {
    archName = 'arm64';
  } else if (arch === 'x64') {
    archName = 'amd64';
  } else if (arch === 'ia32') {
    archName = '386';
  } else if (arch === 'arm') {
    archName = 'arm';
  } else {
    throw new Error(`Unsupported architecture: ${arch}`);
  }

  const folderName = `syncthing-${osName}-${archName}-${VERSION}`;
  const fileName = `${folderName}${ext}`;
  const url = `https://github.com/syncthing/syncthing/releases/download/${VERSION}/${fileName}`;
  
  return { folderName, fileName, url, ext, osName };
}

async function downloadSyncthing(binDir) {
  const { folderName, fileName, url, ext, osName } = getReleaseDetails();
  const archivePath = path.join(binDir, fileName);
  
  if (!fs.existsSync(binDir)) {
    fs.mkdirSync(binDir, { recursive: true });
  }

  console.log(`[Syncthing] Downloading ${url}...`);
  const response = await axios({
    url,
    method: 'GET',
    responseType: 'stream'
  });

  const writer = fs.createWriteStream(archivePath);
  response.data.pipe(writer);

  await new Promise((resolve, reject) => {
    writer.on('finish', resolve);
    writer.on('error', reject);
  });

  console.log(`[Syncthing] Extracting ${archivePath}...`);
  if (ext === '.zip') {
    if (osName === 'windows') {
      execSync(`powershell -command "Expand-Archive -Path '${archivePath}' -DestinationPath '${binDir}' -Force"`);
    } else {
      execSync(`unzip -o "${archivePath}" -d "${binDir}"`);
    }
  } else {
    execSync(`tar -xzf "${archivePath}" -C "${binDir}"`);
  }

  const extractedFolder = path.join(binDir, folderName);
  const binaryName = osName === 'windows' ? 'syncthing.exe' : 'syncthing';
  const extractedBinary = path.join(extractedFolder, binaryName);
  const finalBinary = path.join(binDir, binaryName);

  fs.copyFileSync(extractedBinary, finalBinary);
  if (osName !== 'windows') {
    fs.chmodSync(finalBinary, 0o755);
  }

  // Cleanup
  fs.rmSync(extractedFolder, { recursive: true, force: true });
  fs.unlinkSync(archivePath);

  return finalBinary;
}

class SyncthingManager {
  constructor() {
    this.process = null;
    this.apiKey = null;
    this.apiPort = 8384;
    this.binDir = path.join(app.getPath('userData'), 'syncthing_bin');
    this.configDir = path.join(app.getPath('userData'), 'syncthing_config');
    this.baseUrl = '';
    this._shouldRun = false;
    this._starting = false;
  }

  get binaryPath() {
    const ext = os.platform() === 'win32' ? '.exe' : '';
    return path.join(this.binDir, `syncthing${ext}`);
  }

  async ensureBinary() {
    if (!fs.existsSync(this.binaryPath)) {
      await downloadSyncthing(this.binDir);
    }
    return this.binaryPath;
  }

  async start() {
    if (this.process || this._starting) return;
    this._shouldRun = true;
    this._starting = true;

    try {
      const bin = await this.ensureBinary();
      if (!fs.existsSync(this.configDir)) {
        fs.mkdirSync(this.configDir, { recursive: true });
      }

      console.log('[Syncthing] Starting daemon...');
      this.process = spawn(bin, [
        '--no-browser',
        '--no-restart',
        `--home=${this.configDir}`,
      ], {
        stdio: 'ignore',
        windowsHide: true,
      });

      this.process.on('close', (code) => {
        console.log(`[Syncthing] Daemon exited with code ${code}`);
        this.process = null;
        if (this._shouldRun) {
          setTimeout(() => {
            this.start().catch((err) => {
              console.error('[Syncthing] Failed to restart daemon:', err.message);
            });
          }, 2000);
        }
      });

      await this.waitForApi();
    } finally {
      this._starting = false;
    }
  }

  async waitForApi(maxAttempts = 40) {
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      await this.readConfig();
      if (this.apiKey) {
        try {
          await this.getStatus();
          return;
        } catch (_) {
          /* API not ready yet */
        }
      }
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    throw new Error('Syncthing API did not become ready in time');
  }

  async readConfig() {
    const configPath = path.join(this.configDir, 'config.xml');
    if (!fs.existsSync(configPath)) {
      console.error('[Syncthing] config.xml not found yet.');
      return;
    }

    const xml = fs.readFileSync(configPath, 'utf-8');
    const apiKeyMatch = xml.match(/<apikey>([^<]+)<\/apikey>/);
    const addressMatch = xml.match(/<address>([^<]+)<\/address>/); // Usually 127.0.0.1:8384

    if (apiKeyMatch) {
      this.apiKey = apiKeyMatch[1];
    }
    
    if (addressMatch) {
      const addr = addressMatch[1];
      this.baseUrl = `http://${addr.replace('127.0.0.1', 'localhost')}`;
    } else {
      this.baseUrl = 'http://localhost:8384';
    }

    console.log(`[Syncthing] API Ready at ${this.baseUrl}`);
  }

  async apiRequest(method, endpoint, data = null) {
    if (!this.apiKey) throw new Error('API Key not loaded');
    
    const url = `${this.baseUrl}${endpoint}`;
    try {
      const response = await axios({
        method,
        url,
        headers: { 'X-API-Key': this.apiKey },
        data
      });
      return response.data;
    } catch (error) {
      console.error(`[Syncthing] API Error ${method} ${endpoint}:`, error.message);
      throw error;
    }
  }

  async getStatus() {
    return this.apiRequest('GET', '/rest/system/status');
  }

  async getConfig() {
    return this.apiRequest('GET', '/rest/system/config');
  }

  async setConfig(config) {
    return this.apiRequest('POST', '/rest/system/config', config);
  }

  async getConnections() {
    return this.apiRequest('GET', '/rest/system/connections');
  }

  async getFolderStatus(folderId = FOLDER_ID) {
    return this.apiRequest('GET', `/rest/db/status?folder=${encodeURIComponent(folderId)}`);
  }

  async scanFolder(folderId = FOLDER_ID, sub = '') {
    const params = new URLSearchParams({ folder: folderId });
    if (sub) {
      params.set('sub', sub);
    }
    await this.apiRequest('POST', `/rest/db/scan?${params.toString()}`);
  }

  async runStartupSync({ timeoutMs = 120000, pollIntervalMs = 1500 } = {}) {
    await this.scanFolder();
    const startedAt = Date.now();

    while (Date.now() - startedAt < timeoutMs) {
      let folderStatus;
      try {
        folderStatus = await this.getFolderStatus();
      } catch (err) {
        await new Promise((resolve) => setTimeout(resolve, pollIntervalMs));
        continue;
      }

      const state = folderStatus.state || 'unknown';
      const needBytes = Number(folderStatus.needBytes) || 0;

      if (state === 'error') {
        throw new Error('Syncthing reported a folder sync error');
      }

      if (state === 'idle' && needBytes === 0) {
        return { completed: true, state, needBytes };
      }

      await new Promise((resolve) => setTimeout(resolve, pollIntervalMs));
    }

    const folderStatus = await this.getFolderStatus().catch(() => null);
    return {
      completed: false,
      state: folderStatus?.state || 'timeout',
      needBytes: Number(folderStatus?.needBytes) || 0,
    };
  }

  async bootstrap(saveDir) {
    await this.start();
    await this.ensureFolder(saveDir);
    await this.waitForApi();
    return this.runStartupSync();
  }

  async getInfo() {
    const status = await this.getStatus();
    const config = await this.getConfig();
    const connections = await this.getConnections();
    let folderStatus = null;
    try {
      folderStatus = await this.getFolderStatus();
    } catch (_) {
      /* folder may not exist yet */
    }

    const myID = status.myID;
    const folder = config.folders.find((f) => f.id === FOLDER_ID);
    const remoteDevices = config.devices
      .filter((device) => device.deviceID !== myID)
      .map((device) => {
        const conn = connections.connections?.[device.deviceID];
        return {
          deviceID: device.deviceID,
          name: device.name || device.deviceID.substring(0, 7),
          connected: Boolean(conn?.connected),
        };
      });

    return {
      myID,
      folderId: FOLDER_ID,
      folderPath: folder?.path || '',
      folderState: folderStatus?.state || (folder ? 'idle' : 'missing'),
      globalBytes: folderStatus?.globalBytes ?? 0,
      needBytes: folderStatus?.needBytes ?? 0,
      devices: remoteDevices,
    };
  }

  async ensureFolder(saveDir) {
    const config = await this.getConfig();
    const folderId = FOLDER_ID;
    
    const folderExists = config.folders.some(f => f.id === folderId);
    if (!folderExists) {
      console.log('[Syncthing] Adding shared folder...');
      config.folders.push({
        id: folderId,
        label: 'YT Audio Sync',
        path: saveDir,
        type: 'sendreceive',
        devices: config.devices.map(d => ({ deviceID: d.deviceID })),
        rescanIntervalS: 3600,
        fsWatcherEnabled: true,
        fsWatcherDelayS: 10,
        ignorePerms: false,
        autoNormalize: true,
      });
      await this.setConfig(config);
      await this.apiRequest('POST', '/rest/system/restart');
      console.log('[Syncthing] Restarting to apply folder config...');
      // Re-read config will happen on manual start or it might stay alive
    } else {
      // Ensure path is updated if saveDir changed
      const folder = config.folders.find(f => f.id === folderId);
      if (folder.path !== saveDir) {
        console.log('[Syncthing] Updating shared folder path...');
        folder.path = saveDir;
        await this.setConfig(config);
        await this.apiRequest('POST', '/rest/system/restart');
      }
    }
  }

  async addDevice(rawDeviceID) {
    const deviceID = normalizeDeviceID(rawDeviceID);
    if (!deviceID) {
      throw new Error('Invalid device ID format');
    }

    const config = await this.getConfig();
    const deviceExists = config.devices.some(d => d.deviceID === deviceID);
    
    let needsRestart = false;
    if (!deviceExists) {
      console.log(`[Syncthing] Adding remote device: ${deviceID}`);
      config.devices.push({
        deviceID,
        name: `Remote Device (${deviceID.substring(0, 7)})`,
        addresses: ['dynamic'],
        compression: 'metadata',
        certName: '',
        introducer: false,
        skipIntroductionRemovals: false,
        introducedBy: '',
        paused: false,
        allowedNetworks: [],
        autoAcceptFolders: false,
        maxSendKbps: 0,
        maxRecvKbps: 0,
        ignoredFolders: [],
        maxRequestKiB: 0,
        untrusted: false,
        remoteGUIPort: 0
      });
      needsRestart = true;
    }

    // Share our folder with the device
    const folder = config.folders.find(f => f.id === FOLDER_ID);
    if (folder) {
      const isShared = folder.devices.some(d => d.deviceID === deviceID);
      if (!isShared) {
        folder.devices.push({ deviceID, introducedBy: '' });
        needsRestart = true;
      }
    }

    if (needsRestart) {
      await this.setConfig(config);
      await this.apiRequest('POST', '/rest/system/restart');
    }
    return { success: true };
  }

  stop() {
    this._shouldRun = false;
    if (this.process) {
      this.process.kill('SIGTERM');
      this.process = null;
    }
  }
}

const manager = new SyncthingManager();
manager.normalizeDeviceID = normalizeDeviceID;
manager.FOLDER_ID = FOLDER_ID;
module.exports = manager;