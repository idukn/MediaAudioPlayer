const path = require('path');
const os = require('os');

const LIBRARY_DIR_NAME = 'library';
const PLAYLISTS_FILE_NAME = 'playlists.json';

/** @type {() => string} */
let userDataResolver = () => path.join(os.homedir(), '.media-audio-finder');

function setUserDataResolver(resolver) {
  userDataResolver = resolver;
}

function getUserDataDir() {
  return userDataResolver();
}

function getLibraryDir() {
  return path.join(getUserDataDir(), LIBRARY_DIR_NAME);
}

function getPlaylistsFilePath(libraryDir = getLibraryDir()) {
  return path.join(libraryDir, PLAYLISTS_FILE_NAME);
}

function getLegacyLibraryDirs() {
  if (process.platform === 'darwin') {
    return [
      '/Volumes/2TB_WINMAC/reference',
      '/Volumes/WINMAC-2TB/reference',
    ];
  }
  if (process.platform === 'win32') {
    return [];
  }
  return [];
}

function getDefaultDialogPath(kind = 'downloads') {
  const home = os.homedir();
  if (process.platform === 'win32') {
    const localAppData = process.env.LOCALAPPDATA || path.join(home, 'AppData', 'Local');
    if (kind === 'downloads') {
      return process.env.USERPROFILE
        ? path.join(process.env.USERPROFILE, 'Downloads')
        : path.join(home, 'Downloads');
    }
    return localAppData;
  }
  if (process.platform === 'darwin') {
    return kind === 'downloads'
      ? path.join(home, 'Downloads')
      : path.join(home, 'Library', 'Application Support');
  }
  return kind === 'downloads' ? path.join(home, 'Downloads') : home;
}

module.exports = {
  LIBRARY_DIR_NAME,
  PLAYLISTS_FILE_NAME,
  setUserDataResolver,
  getUserDataDir,
  getLibraryDir,
  getPlaylistsFilePath,
  getLegacyLibraryDirs,
  getDefaultDialogPath,
};
