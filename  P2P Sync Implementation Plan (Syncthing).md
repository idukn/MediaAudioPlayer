# P2P Sync Implementation Plan (Syncthing)

## Objective
Automatically synchronize local audio files and playlists across multiple devices using Syncthing in the background, without requiring the user to install external software manually.

## Key Files & Context
- `electron/src/main.js`: Main process where we will spawn Syncthing and manage its lifecycle.
- `electron/src/syncthing.js` (NEW): Module to handle downloading, spawning, and interacting with the Syncthing REST API.
- `electron/src/preload.js`: Expose Syncthing IPC events.
- `electron/src/renderer/index.html` & `renderer.js`: UI for displaying the local Device ID and adding remote Device IDs.
- `package.json`: Need to ensure `axios` is available (already is).

## Implementation Steps

### Phase 1: Playlist Storage Migration
To ensure playlists are synced alongside audio files, they must be saved in the target sync folder (`saveDir`) rather than the default `electron-store` location.
1. Modify `getStoredPlaylists` and `setStoredPlaylists` in `main.js` to read/write `playlists.json` located inside `store.get('saveDir')`.
2. Implement a one-time migration: If `playlists.json` does not exist, copy existing playlists from `electron-store` to the new JSON file, then clear the store.

### Phase 2: Syncthing Binary Management
1. Create `electron/src/syncthing.js`.
2. Implement an auto-downloader: On startup, check if the Syncthing executable exists in `app.getPath('userData') + '/bin/'`. If not, download the latest release from GitHub based on `os.platform()` and `os.arch()`, extract it (using `tar` or `unzip` via native shell commands or `adm-zip`), and place the binary.
3. Spawn the Syncthing process:
   - Command: `syncthing --no-browser --home="<userData>/syncthing_config"`
   - Handle app exit to cleanly terminate the Syncthing child process.

### Phase 3: Syncthing API & Configuration
1. After spawning, parse `<userData>/syncthing_config/config.xml` to extract the auto-generated `apikey` and API port.
2. Implement an API client using `axios` to interact with Syncthing's REST API (`http://127.0.0.1:<port>/rest/...`).
3. Auto-configure the shared folder:
   - Fetch current config (`GET /rest/system/config`).
   - Check if a folder with ID `yt-audio-app-sync` exists.
   - If not, add it pointing to `store.get('saveDir')` and `POST` the updated config back to Syncthing.
   - Restart Syncthing via API if config changes require it.

### Phase 4: UI and IPC
1. **IPC Handlers (`main.js`)**:
   - `syncthing:get-info`: Returns local Device ID and sync status.
   - `syncthing:add-device`: Accepts a remote Device ID, adds it to the config, and shares the `yt-audio-app-sync` folder with it.
2. **Preload (`preload.js`)**: Expose the above handlers.
3. **UI (`index.html` & `renderer.js`)**:
   - Add a new section in the settings tab (or a new "Sync" tab).
   - Display "My Device ID" with a copy button.
   - Add a text input "Add Remote Device ID" and a "Connect" button.
   - Display a list of connected devices and their sync status.

## Verification & Testing
1. **Migration**: Verify existing playlists are correctly moved to `saveDir/playlists.json`.
2. **Download**: Verify Syncthing downloads correctly on macOS (and optionally other platforms).
3. **API**: Verify the `yt-audio-app-sync` folder is automatically created in Syncthing without user intervention.
4. **P2P Sync**: Run two instances of the app (e.g., with different `userData` paths locally) and pair their Device IDs. Add an audio file or modify a playlist in one instance and verify it appears in the other instance.

## Alternatives Considered
- **Custom P2P (hyperswarm)**: Rejected due to immense complexity in handling directory diffing, file chunks, and offline sync compared to Syncthing's proven robustness.
- **Requiring manual Syncthing installation**: Rejected because it degrades the user experience. Bundling or auto-downloading provides a seamless "it just works" experience.