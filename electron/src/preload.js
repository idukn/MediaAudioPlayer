const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  getLibraryDir: () => ipcRenderer.invoke('settings:getLibraryDir'),
  getPlatformInfo: () => ipcRenderer.invoke('platform:getInfo'),
  buildLocalAudioUrl: null,
  chooseDownloadDir: () => ipcRenderer.invoke('settings:chooseDownloadDir'),
  searchVideos: (payload) => ipcRenderer.invoke('search:videos', payload),
  getPreviewStreamUrl: (payload) => ipcRenderer.invoke('preview:getStreamUrl', payload),

  downloadAudio: (payload) => ipcRenderer.invoke('download:audio', payload),
  listAudio: (payload) => ipcRenderer.invoke('files:listAudio', payload),
  createFolder: (payload) => ipcRenderer.invoke('files:createFolder', payload),
  createFolderAt: (payload) => ipcRenderer.invoke('files:createFolderAt', payload),
  moveToNewFolder: (payload) => ipcRenderer.invoke('files:moveToNewFolder', payload),
  openInFinder: (payload) => ipcRenderer.invoke('files:openInFinder', payload),
  openExternal: (payload) => ipcRenderer.invoke('links:openExternal', payload),
  writeClipboardText: (payload) => ipcRenderer.invoke('clipboard:writeText', payload),
  getPlaylists: () => ipcRenderer.invoke('playlists:get'),
  createPlaylist: (payload) => ipcRenderer.invoke('playlists:create', payload),
  addItemsToPlaylist: (payload) => ipcRenderer.invoke('playlists:addItems', payload),
  reorderPlaylistItems: (payload) => ipcRenderer.invoke('playlists:reorderItems', payload),
  removePlaylistItems: (payload) => ipcRenderer.invoke('playlists:removeItems', payload),
  getAudioServerPort: () => ipcRenderer.invoke('audio:getServerPort'),
  getStreamLogPath: () => ipcRenderer.invoke('debug:getStreamLogPath'),
  onSearchMetadataUpdated: (listener) => {
    const handler = (_event, payload) => listener(payload);
    ipcRenderer.on('search:metadataUpdated', handler);
    return () => ipcRenderer.removeListener('search:metadataUpdated', handler);
  },
  onSearchEnrichmentStatus: (listener) => {
    const handler = (_event, payload) => listener(payload);
    ipcRenderer.on('search:enrichmentStatus', handler);
    return () => ipcRenderer.removeListener('search:enrichmentStatus', handler);
  },
  saveUIState: (state) => ipcRenderer.invoke('ui:saveState', state),
  loadUIState: () => ipcRenderer.invoke('ui:loadState'),
  clearCache: () => ipcRenderer.invoke('clear:cache'),
  syncthingGetInfo: () => ipcRenderer.invoke('syncthing:getInfo'),
  syncthingAddDevice: (payload) => ipcRenderer.invoke('syncthing:addDevice', payload),
  onSyncUpdated: (listener) => {
    const handler = (_event, payload) => listener(payload);
    ipcRenderer.on('sync:updated', handler);
    return () => ipcRenderer.removeListener('sync:updated', handler);
  },
});
