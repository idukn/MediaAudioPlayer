const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  getSaveDir: () => ipcRenderer.invoke('settings:getSaveDir'),
  setSaveDir: (dir) => ipcRenderer.invoke('settings:setSaveDir', dir),
  chooseSaveDir: () => ipcRenderer.invoke('settings:chooseSaveDir'),
  searchVideos: (payload) => ipcRenderer.invoke('search:videos', payload),
  getPreviewStreamUrl: (payload) => ipcRenderer.invoke('preview:getStreamUrl', payload),

  downloadAudio: (payload) => ipcRenderer.invoke('download:audio', payload),
  listAudio: (payload) => ipcRenderer.invoke('files:listAudio', payload),
  createFolder: (payload) => ipcRenderer.invoke('files:createFolder', payload),
  moveToNewFolder: (payload) => ipcRenderer.invoke('files:moveToNewFolder', payload),
  openInFinder: (payload) => ipcRenderer.invoke('files:openInFinder', payload),
  openExternal: (payload) => ipcRenderer.invoke('links:openExternal', payload),
  writeClipboardText: (payload) => ipcRenderer.invoke('clipboard:writeText', payload),
  getPlaylists: () => ipcRenderer.invoke('playlists:get'),
  createPlaylist: (payload) => ipcRenderer.invoke('playlists:create', payload),
  addItemsToPlaylist: (payload) => ipcRenderer.invoke('playlists:addItems', payload),
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
});
