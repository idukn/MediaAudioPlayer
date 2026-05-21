const queryEl = document.getElementById('query');
const resultSourceFilterEl = document.getElementById('resultSourceFilter');
const durationMinEl = document.getElementById('durationMin');
const durationMaxEl = document.getElementById('durationMax');
const durationPrefEnabledEl = document.getElementById('durationPrefEnabled');
const saveAudioFormatEl = document.getElementById('saveAudioFormat');
const toggleOptionsBtn = document.getElementById('toggleOptionsBtn');
const searchOptionsPanelEl = document.getElementById('searchOptionsPanel');
const libraryPathEl = document.getElementById('libraryPath');
const openLibraryBtn = document.getElementById('openLibraryBtn');
const statusEl = document.getElementById('status');
const searchStatusEl = document.getElementById('searchStatus');
const searchLoadingInlineEl = document.getElementById('searchLoadingInline');
const searchResultsOverlayEl = document.getElementById('searchResultsOverlay');

const searchBtn = document.getElementById('searchBtn');
const prevPageBtn = document.getElementById('prevPageBtn');
const nextPageBtn = document.getElementById('nextPageBtn');
const pageInfoEl = document.getElementById('pageInfo');
const backBtn = document.getElementById('backBtn');
const refreshBtn = document.getElementById('refreshBtn');
const fileSortEl = document.getElementById('fileSort');
const fileContextMenuEl = document.getElementById('fileContextMenu');
const ctxMoveSelectedBtn = document.getElementById('ctxMoveSelected');
const ctxAddSelectedToPlaylistBtn = document.getElementById('ctxAddSelectedToPlaylist');
const ctxOpenInFinderBtn = document.getElementById('ctxOpenInFinder');
const searchContextMenuEl = document.getElementById('searchContextMenu');
const ctxOpenSourceUrlBtn = document.getElementById('ctxOpenSourceUrl');
const ctxCopySourceUrlBtn = document.getElementById('ctxCopySourceUrl');
const ctxSetDurationFromItemBtn = document.getElementById('ctxSetDurationFromItem');
const ctxAddSearchToPlaylistBtn = document.getElementById('ctxAddSearchToPlaylist');
const ctxSaveToLibraryBtn = document.getElementById('ctxSaveToLibrary');
const ctxSaveToLibraryEachBtn = document.getElementById('ctxSaveToLibraryEach');
const ctxSaveToLibraryNewFolderBtn = document.getElementById('ctxSaveToLibraryNewFolder');
const ctxSaveToCustomBtn = document.getElementById('ctxSaveToCustom');
const ctxSaveToCustomEachBtn = document.getElementById('ctxSaveToCustomEach');
const ctxSaveToCustomNewFolderBtn = document.getElementById('ctxSaveToCustomNewFolder');
const textInputModalEl = document.getElementById('textInputModal');
const textInputModalTitleEl = document.getElementById('textInputModalTitle');
const textInputModalFieldEl = document.getElementById('textInputModalField');
const textInputModalCancelEl = document.getElementById('textInputModalCancel');
const textInputModalOkEl = document.getElementById('textInputModalOk');
const playlistCreateBtn = document.getElementById('playlistCreateBtn');
const playlistListEl = document.getElementById('playlistList');
const playlistItemsEl = document.getElementById('playlistItems');
const playlistEmptyEl = document.getElementById('playlistEmpty');
const playlistPickerModalEl = document.getElementById('playlistPickerModal');
const playlistPickerTitleEl = document.getElementById('playlistPickerTitle');
const playlistPickerListEl = document.getElementById('playlistPickerList');
const playlistPickerCancelEl = document.getElementById('playlistPickerCancel');
const playlistItemContextMenuEl = document.getElementById('playlistItemContextMenu');
const ctxRemoveFromPlaylistBtn = document.getElementById('ctxRemoveFromPlaylist');
const syncMyDeviceIdEl = document.getElementById('syncMyDeviceId');
const syncCopyDeviceIdBtn = document.getElementById('syncCopyDeviceIdBtn');
const syncRemoteDeviceIdEl = document.getElementById('syncRemoteDeviceId');
const syncAddDeviceBtn = document.getElementById('syncAddDeviceBtn');
const syncFolderStatusEl = document.getElementById('syncFolderStatus');
const syncConnectStatusEl = document.getElementById('syncConnectStatus');
const syncDeviceListEl = document.getElementById('syncDeviceList');

const tableBody = document.querySelector('#resultsTable tbody');
const audioListEl = document.getElementById('audioList');
const audioPlayer = document.getElementById('audioPlayer');
const nowPlayingTextEl = document.getElementById('nowPlayingText');

const playerPlayPauseBtn = document.getElementById('playerPlayPauseBtn');
const playerProgressContainer = document.getElementById('playerProgressContainer');
const playerProgressBar = document.getElementById('playerProgressBar');
const playerProgressBarBuffered = document.getElementById('playerProgressBarBuffered');
const playerTime = document.getElementById('playerTime');
const playerRepeatBtn = document.getElementById('playerRepeatBtn');
const playerVolumeBtn = document.getElementById('playerVolumeBtn');
const playerVolumeBar = document.getElementById('playerVolumeBar');
const playerVolumeValue = document.getElementById('playerVolumeValue');

let currentAudioDurationSec = 0;
let lastNonZeroVolume = 1;
let playerLoopMode = 'off';
let currentPlaybackQueue = [];
let currentPlaybackIndex = -1;
let currentPlaybackSourceType = '';

let results = [];
let currentPageResults = [];
let selectedResultIndex = -1;
let localFiles = [];
let selectedLocalIndex = -1;
let currentAudioDir = '';
let baseAudioDir = '';
let audioServerPort = null;
let currentTabActive = 'search';
let previewLoadingIndex = -1;
let currentSearchQuery = '';
let currentSearchPage = 1;
let hasNextSearchPage = false;
const SEARCH_PAGE_SIZE = 10;
const SEARCH_FILTERS = ['all', 'youtube', 'niconico', 'bilibili', 'other'];
let searchCacheGeneration = 0;
const searchPageCache = new Map();
const selectedMoveFiles = new Set();
let selectionAnchorIndex = -1;
let pendingTextInputResolver = null;
let contextMenuTargetPath = '';
const selectedSearchIndexes = new Set();
let searchSelectionAnchorIndex = -1;
let searchContextMenuIndex = -1;
let optionsExpanded = false;
let optionsApplyTimer = null;
let searchUiLockDepth = 0;
let searchMetadataRerenderTimer = null;
let detachSearchMetadataListener = null;
let detachSearchEnrichmentListener = null;
let detachSyncUpdatedListener = null;
let syncInfoRefreshTimer = null;
let bootCompleted = false;
let isBackgroundEnriching = false;
let suppressStatePersist = false;
let playlists = [];
let currentPlaylistId = '';
let pendingPlaylistPickerResolver = null;
let playlistDragFromIndex = -1;
let playlistContextMenuIndex = -1;
const searchTabContentEl = document.querySelector('.tab-content[data-tab="search"]');
const ALLOWED_SAVE_AUDIO_FORMATS = new Set(['auto', 'mp3', 'm4a', 'wav', 'flac', 'opus']);
const LOOP_MODES = ['off', 'single', 'playlist'];

function updateSearchLoadingSpinner() {
  const showSpinner = searchUiLockDepth > 0 || isBackgroundEnriching;
  if (searchLoadingInlineEl) {
    searchLoadingInlineEl.style.display = showSpinner ? 'inline-flex' : 'none';
  }
}

function matchesResultFilter(item) {
  const filter = resultSourceFilterEl?.value || 'all';
  if (filter === 'all') {
    return true;
  }

  const site = (item?.site || '').toLowerCase();
  if (filter === 'youtube') {
    return site.includes('youtube');
  }
  if (filter === 'niconico') {
    return site.includes('niconico');
  }
  if (filter === 'bilibili') {
    return site.includes('bilibili');
  }
  if (filter === 'other') {
    return !site.includes('youtube') && !site.includes('niconico') && !site.includes('bilibili');
  }

  return true;
}

// frontend filtering removed; backend filtering is used.

function parseDurationInputToSec(text, fallbackSec) {
  const value = String(text || '').trim();
  if (!value) {
    return fallbackSec;
  }
  if (/^\d+$/.test(value)) {
    return Number(value) * 60;
  }
  const parts = value.split(':').map((x) => Number(x));
  if (parts.some((x) => Number.isNaN(x))) {
    return fallbackSec;
  }
  if (parts.length === 2) {
    return parts[0] * 60 + parts[1];
  }
  if (parts.length === 3) {
    return parts[0] * 3600 + parts[1] * 60 + parts[2];
  }
  return fallbackSec;
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

function formatSecToDurationInput(sec) {
  const safe = Math.max(0, Math.floor(sec));
  const m = Math.floor(safe / 60);
  const s = safe % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function getDurationPreferencePayload() {
  const minSec = parseDurationInputToSec(durationMinEl.value, 120);
  const maxSecRaw = parseDurationInputToSec(durationMaxEl.value, 300);
  const maxSec = Math.max(minSec, maxSecRaw);

  return {
    enabled: !!durationPrefEnabledEl.checked,
    minSec,
    maxSec,
  };
}

function isSearchUiLocked() {
  return searchUiLockDepth > 0;
}

function setSearchUiLocked(locked) {
  const controls = [
    queryEl,
    searchBtn,
    resultSourceFilterEl,
    toggleOptionsBtn,
    durationMinEl,
    durationMaxEl,
    durationPrefEnabledEl,
    prevPageBtn,
    nextPageBtn,
  ];

  controls.forEach((el) => {
    if (!el) return;
    el.disabled = locked;
  });

  if (searchTabContentEl) {
    searchTabContentEl.classList.toggle('search-ui-locked', locked);
  }

  updateSearchLoadingSpinner();

  if (searchResultsOverlayEl) {
    searchResultsOverlayEl.style.display = locked ? 'flex' : 'none';
  }

  updateSearchPager();
}

function beginSearchUiLock() {
  searchUiLockDepth += 1;
  if (searchUiLockDepth === 1) {
    setSearchUiLocked(true);
  }
}

function endSearchUiLock() {
  if (searchUiLockDepth <= 0) {
    searchUiLockDepth = 0;
    setSearchUiLocked(false);
    return;
  }
  searchUiLockDepth -= 1;
  if (searchUiLockDepth === 0) {
    setSearchUiLocked(false);
  }
}

async function withSearchUiLock(task) {
  beginSearchUiLock();
  try {
    return await task();
  } finally {
    endSearchUiLock();
  }
}

function updateSearchPager() {
  prevPageBtn.disabled = isSearchUiLocked() || currentSearchPage <= 1;
  nextPageBtn.disabled = isSearchUiLocked() || !hasNextSearchPage;
  pageInfoEl.textContent = `Page ${currentSearchPage}`;
}

function updateOptionsVisibility() {
  searchOptionsPanelEl.style.display = optionsExpanded ? 'block' : 'none';
  toggleOptionsBtn.textContent = optionsExpanded ? 'Options ▴' : 'Options ▾';
}

async function fetchSearchPage(page, overrideFilter = null, isPreload = false) {
  if (!currentSearchQuery) {
    return false;
  }

  const targetFilter = overrideFilter || resultSourceFilterEl.value || 'all';
  const cacheKey = `${targetFilter}_${page}`;
  const skipFrontCache = isLikelyHttpUrl(currentSearchQuery);
  let fetched;

  if (!skipFrontCache && searchPageCache.has(cacheKey)) {
    fetched = searchPageCache.get(cacheKey);
  } else {
    if (!isPreload) setStatus(`Fetching Page ${page} for ${targetFilter}...`);
    try {
      const offset = (page - 1) * SEARCH_PAGE_SIZE;
      fetched = await window.api.searchVideos({
        query: currentSearchQuery,
        offset,
        limit: SEARCH_PAGE_SIZE,
        durationPreference: getDurationPreferencePayload(),
        sourceFilter: targetFilter,
      });
      if (!skipFrontCache) {
        searchPageCache.set(cacheKey, fetched);
      }
    } catch (e) {
      if (!isPreload) {
        safeAlert(`検索エラー: ${e.message}`);
        setStatus('Search failed');
      }
      return false;
    }
  }

  if (!isPreload) {
    if (page > 1 && fetched.length === 0) {
      hasNextSearchPage = false;
      updateSearchPager();
      setStatus('これ以上の検索結果は見つかりませんでした');
      return false;
    }

    currentSearchPage = page;
    currentPageResults = fetched;
    hasNextSearchPage = fetched.length >= SEARCH_PAGE_SIZE;
    selectedResultIndex = -1;
    selectedSearchIndexes.clear();
    searchSelectionAnchorIndex = -1;
    
    results = currentPageResults;
    if (selectedResultIndex >= results.length) {
      selectedResultIndex = -1;
    }
    renderResults();
    updateSearchPager();

    const durationPref = getDurationPreferencePayload();
    const prefText = durationPref.enabled ? ` (duration ${durationMinEl.value}~${durationMaxEl.value} preferred)` : '';
    setStatus(`Page ${currentSearchPage}: ${fetched.length} results loaded${prefText}`);
  }
  return true;
}

function triggerPreloads() {
  if (!currentSearchQuery || isLikelyHttpUrl(currentSearchQuery)) return;
  const currentFilter = resultSourceFilterEl.value || 'all';
  const page = currentSearchPage || 1;
  
  // Preload next page of the current filter
  fetchSearchPage(page + 1, currentFilter, true).catch(() => {});
  
  // Preload page 1 of all other filters
  const allFilters = ['all', 'youtube', 'niconico', 'bilibili', 'other'];
  for (const filter of allFilters) {
    if (filter !== currentFilter) {
      fetchSearchPage(1, filter, true).catch(() => {});
    }
  }
}

function scheduleApplyOptions(delayMs = 300) {
  if (!currentSearchQuery) {
    return;
  }
  if (optionsApplyTimer) {
    clearTimeout(optionsApplyTimer);
  }
  optionsApplyTimer = setTimeout(async () => {
    optionsApplyTimer = null;
    try {
      await withSearchUiLock(async () => {
        searchPageCache.clear();
        currentSearchPage = 1;
        await fetchSearchPage(1);
      });
      triggerPreloads();
    } catch (error) {
      safeAlert(`オプション反映に失敗しました。\n${error.message}`);
    }
  }, delayMs);
}

function setStatus(text) {
  if (!statusEl) {
    return;
  }
  statusEl.textContent = text;
}

function setSearchStatus(text) {
  searchStatusEl.textContent = text || '';
}

function mergeMetadataIntoItem(item, update) {
  if (!item || !update || item.webpageUrl !== update.webpageUrl) {
    return false;
  }

  let changed = false;
  const fields = ['uploader', 'duration', 'durationSec', 'viewCount', 'likeCount', 'score'];
  for (const key of fields) {
    const nextValue = update[key];
    if (nextValue === undefined || nextValue === null) {
      continue;
    }
    if (item[key] !== nextValue) {
      item[key] = nextValue;
      changed = true;
    }
  }
  return changed;
}

function scheduleSearchMetadataRerender() {
  if (searchMetadataRerenderTimer) {
    return;
  }

  searchMetadataRerenderTimer = setTimeout(() => {
    searchMetadataRerenderTimer = null;
    renderResults();
  }, 90);
}

function applySearchMetadataUpdate(update) {
  if (!update?.webpageUrl) {
    return;
  }

  let changed = false;
  let foundInCurrentPage = false;

  for (const item of results) {
    changed = mergeMetadataIntoItem(item, update) || changed;
  }

  for (const item of currentPageResults) {
    if (item.webpageUrl === update.webpageUrl) foundInCurrentPage = true;
    changed = mergeMetadataIntoItem(item, update) || changed;
  }

  for (const cachedPage of searchPageCache.values()) {
    if (!Array.isArray(cachedPage)) {
      continue;
    }
    for (const item of cachedPage) {
      changed = mergeMetadataIntoItem(item, update) || changed;
    }
  }

  if (!foundInCurrentPage && update.title && currentPageResults.length > 0) {
    if (update.score !== undefined) {
      // Break reference to avoid mutating other pages directly
      currentPageResults = [...currentPageResults, update];
      changed = true;
    }
  }

  if (changed) {
    if (update.score !== undefined) {
      currentPageResults.sort((a, b) => (b.score || 0) - (a.score || 0));
    }
    // Always restrict to 10 items to prevent UI from breaking
    currentPageResults = currentPageResults.slice(0, SEARCH_PAGE_SIZE);
    
    const targetFilter = resultSourceFilterEl.value || 'all';
    const cacheKey = `${targetFilter}_${currentSearchPage}`;
    searchPageCache.set(cacheKey, currentPageResults);
    
    results = currentPageResults;
    scheduleSearchMetadataRerender();
        persistUiState();
  }
}

function safeAlert(message) {
  window.alert(message);
}

function setNowPlayingText(text) {
  nowPlayingTextEl.textContent = text && text.trim().length > 0 ? text : '-';
}

function buildAudioUrl(fullPath) {
  if (typeof window.api.buildLocalAudioUrl === 'function') {
    return window.api.buildLocalAudioUrl(fullPath);
  }
  return `http://127.0.0.1:${audioServerPort}/audio?path=${encodeURIComponent(fullPath)}&t=${Date.now()}`;
}

function cloneSearchItemForState(item) {
  if (!item || !item.webpageUrl) {
    return null;
  }
  return {
    title: item.title || '(No title)',
    uploader: item.uploader || '-',
    duration: item.duration || '-',
    durationSec: Number.isFinite(item.durationSec) ? Number(item.durationSec) : null,
    webpageUrl: item.webpageUrl,
    site: item.site || 'unknown',
    viewCount: Number.isFinite(item.viewCount) ? Number(item.viewCount) : null,
    likeCount: Number.isFinite(item.likeCount) ? Number(item.likeCount) : null,
    score: Number.isFinite(item.score) ? Number(item.score) : null,
  };
}

function getSearchSnapshotForState() {
  return {
    query: currentSearchQuery || queryEl.value.trim(),
    page: Number.isFinite(currentSearchPage) ? currentSearchPage : 1,
    hasNextSearchPage: Boolean(hasNextSearchPage),
    sourceFilter: resultSourceFilterEl?.value || 'all',
    durationMin: durationMinEl?.value || '2:00',
    durationMax: durationMaxEl?.value || '5:00',
    durationPrefEnabled: !!durationPrefEnabledEl?.checked,
    optionsExpanded: !!optionsExpanded,
    searchStatus: searchStatusEl?.textContent || '',
    results: currentPageResults
      .map((item) => cloneSearchItemForState(item))
      .filter((item) => !!item),
  };
}

function restoreSearchSnapshotFromState(snapshot) {
  if (!snapshot || typeof snapshot !== 'object') {
    return;
  }

  const restoredQuery = String(snapshot.query || '').trim();
  currentSearchQuery = restoredQuery;
  queryEl.value = restoredQuery;

  const restoredFilter = String(snapshot.sourceFilter || 'all');
  if (SEARCH_FILTERS.includes(restoredFilter)) {
    resultSourceFilterEl.value = restoredFilter;
  }

  if (typeof snapshot.durationMin === 'string' && snapshot.durationMin.trim()) {
    durationMinEl.value = snapshot.durationMin.trim();
  }
  if (typeof snapshot.durationMax === 'string' && snapshot.durationMax.trim()) {
    durationMaxEl.value = snapshot.durationMax.trim();
  }
  durationPrefEnabledEl.checked = Boolean(snapshot.durationPrefEnabled);

  optionsExpanded = Boolean(snapshot.optionsExpanded);
  updateOptionsVisibility();

  const restoredPage = Number(snapshot.page);
  currentSearchPage = Number.isFinite(restoredPage) && restoredPage > 0 ? Math.floor(restoredPage) : 1;
  hasNextSearchPage = Boolean(snapshot.hasNextSearchPage);

  const restoredResults = Array.isArray(snapshot.results)
    ? snapshot.results.map((item) => cloneSearchItemForState(item)).filter((item) => !!item)
    : [];

  currentPageResults = restoredResults;
  results = restoredResults;
  selectedResultIndex = -1;
  selectedSearchIndexes.clear();
  searchSelectionAnchorIndex = -1;

  const cacheKey = `${resultSourceFilterEl.value || 'all'}_${currentSearchPage}`;
  if (!isLikelyHttpUrl(currentSearchQuery)) {
    searchPageCache.set(cacheKey, restoredResults);
  }

  renderResults();
  updateSearchPager();

  if (restoredResults.length > 0) {
    setSearchStatus(snapshot.searchStatus || '前回の検索結果を復元しました');
    setStatus(`復元: ${restoredResults.length} 件`);
  } else {
    setSearchStatus('');
  }
}

function toPlaylistItemFromSearchItem(item) {
  if (!item?.webpageUrl) {
    return null;
  }
  return {
    type: 'url',
    title: item.title || '(No title)',
    uploader: item.uploader || '-',
    duration: item.duration || '-',
    durationSec: Number.isFinite(item.durationSec) ? Number(item.durationSec) : null,
    site: item.site || 'unknown',
    webpageUrl: item.webpageUrl,
  };
}

function toPlaylistItemFromLocalPath(fullPath) {
  const entry = localFiles.find((file) => file.fullPath === fullPath);
  return {
    type: 'local',
    title: entry?.name || fullPath.split('/').pop() || '(No title)',
    uploader: 'Local File',
    duration: '-',
    durationSec: null,
    site: 'Local',
    fullPath,
  };
}

function getPlaybackQueueItemKey(item) {
  if (!item) {
    return '';
  }
  if (item.type === 'local') {
    return `local:${item.fullPath || ''}`;
  }
  return `url:${item.webpageUrl || ''}`;
}

function setPlaybackQueue(items, index = 0, sourceType = '') {
  currentPlaybackQueue = Array.isArray(items)
    ? items.filter((item) => !!item)
    : [];
  if (currentPlaybackQueue.length === 0) {
    currentPlaybackIndex = -1;
    currentPlaybackSourceType = '';
    return;
  }
  currentPlaybackIndex = Math.max(0, Math.min(index, currentPlaybackQueue.length - 1));
  currentPlaybackSourceType = String(sourceType || '');
}

function getCurrentPlaybackQueueItem() {
  if (currentPlaybackIndex < 0 || currentPlaybackIndex >= currentPlaybackQueue.length) {
    return null;
  }
  return currentPlaybackQueue[currentPlaybackIndex];
}

function buildLocalPlaybackQueue() {
  return localFiles
    .filter((file) => file?.isAudio && file?.fullPath)
    .map((file) => toPlaylistItemFromLocalPath(file.fullPath));
}

async function playPlaybackQueueItem(item, { statusPrefix = '▶️ 再生中' } = {}) {
  if (!item) {
    throw new Error('再生対象が見つかりませんでした。');
  }

  previewLoadingIndex = -1;
  currentAudioDurationSec = Number.isFinite(item.durationSec)
    ? Number(item.durationSec)
    : parseDurationInputToSec(item.duration, 0);
  playerProgressBar.style.width = '0%';
  playerProgressBarBuffered.style.width = '0%';
  playerTime.textContent = `0:00 / ${formatSecToDurationInput(currentAudioDurationSec)}`;

  if (item.type === 'local' && item.fullPath) {
    const localIndex = localFiles.findIndex((file) => file?.fullPath === item.fullPath);
    selectedLocalIndex = localIndex;
    if (localIndex >= 0) {
      renderLocalFiles();
    }

    audioPlayer.src = buildAudioUrl(item.fullPath);
    await audioPlayer.play();
    setNowPlayingText(item.title || 'Local Audio');
    setStatus(`${statusPrefix}: ${item.title || 'Local Audio'}`);
    return;
  }

  selectedLocalIndex = -1;
  if (item.webpageUrl) {
    const previewUrl = `http://127.0.0.1:${audioServerPort}/stream?url=${encodeURIComponent(item.webpageUrl)}`;
    audioPlayer.src = previewUrl;
    await audioPlayer.play();
    setNowPlayingText(item.title || 'Preview');
    setStatus(`${statusPrefix}: ${item.title || 'Preview'}`);
    return;
  }

  throw new Error('再生元URLが見つかりませんでした。');
}

async function playCurrentPlaybackQueueItem(options = {}) {
  const item = getCurrentPlaybackQueueItem();
  if (!item) {
    throw new Error('再生対象がありません。');
  }
  await playPlaybackQueueItem(item, options);
}

async function handleEndedPlayback() {
  const mode = normalizeLoopMode(playerLoopMode);
  const queueLength = currentPlaybackQueue.length;
  if (mode === 'off' || queueLength === 0 || currentPlaybackIndex < 0) {
    return;
  }

  try {
    if (mode === 'single') {
      await playCurrentPlaybackQueueItem({ statusPrefix: '▶️ ループ再生中' });
      return;
    }

    if (mode === 'playlist') {
      currentPlaybackIndex += 1;
      if (currentPlaybackIndex >= queueLength) {
        currentPlaybackIndex = 0;
      }
      await playCurrentPlaybackQueueItem({ statusPrefix: '▶️ ループ再生中' });
    }
  } catch (error) {
    console.error('[Audio] ended loop playback failed:', error);
    setStatus(`ループ再生エラー: ${error.message}`);
  }
}

function getSelectedLocalItemsForPlaylist() {
  const selected = Array.from(selectedMoveFiles);
  if (selected.length > 0) {
    return selected.map((fullPath) => toPlaylistItemFromLocalPath(fullPath));
  }

  if (!contextMenuTargetPath) {
    return [];
  }

  const target = localFiles.find((file) => file.fullPath === contextMenuTargetPath && file.isAudio);
  if (!target) {
    return [];
  }
  return [toPlaylistItemFromLocalPath(target.fullPath)];
}

function renderPlaylists() {
  if (!playlistListEl || !playlistItemsEl || !playlistEmptyEl) {
    return;
  }

  playlistListEl.innerHTML = '';
  playlistItemsEl.innerHTML = '';

  if (!Array.isArray(playlists) || playlists.length === 0) {
    currentPlaylistId = '';
    playlistEmptyEl.style.display = 'block';
    playlistEmptyEl.textContent = 'プレイリストがありません。右クリックメニューから追加できます。';
    return;
  }

  if (!currentPlaylistId || !playlists.some((playlist) => playlist.id === currentPlaylistId)) {
    currentPlaylistId = playlists[0].id;
  }

  for (const playlist of playlists) {
    const li = document.createElement('li');
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'playlist-list-btn';
    if (playlist.id === currentPlaylistId) {
      btn.classList.add('active');
    }
    btn.textContent = playlist.name;
    btn.addEventListener('click', () => {
      currentPlaylistId = playlist.id;
      renderPlaylists();
      persistUiState();
    });
    li.appendChild(btn);
    playlistListEl.appendChild(li);
  }

  const selectedPlaylist = playlists.find((playlist) => playlist.id === currentPlaylistId);
  const items = Array.isArray(selectedPlaylist?.items) ? selectedPlaylist.items : [];
  if (items.length === 0) {
    playlistEmptyEl.style.display = 'block';
    playlistEmptyEl.textContent = 'このプレイリストには曲がありません。';
    return;
  }

  playlistEmptyEl.style.display = 'none';

  items.forEach((item, itemIndex) => {
    const li = document.createElement('li');
    li.className = 'playlist-item';
    li.dataset.itemIndex = String(itemIndex);

    const dragHandle = createPlaylistDragHandle(itemIndex, li);
    bindPlaylistItemDropTarget(li, itemIndex);

    const playBtn = document.createElement('button');
    playBtn.type = 'button';
    playBtn.className = 'playlist-play-btn';
    playBtn.textContent = '▶';
    playBtn.addEventListener('click', (event) => {
      event.stopPropagation();
      playPlaylistItem(itemIndex);
    });

    const meta = document.createElement('div');
    meta.className = 'playlist-item-meta';

    const title = document.createElement('div');
    title.className = 'playlist-item-title';
    title.textContent = item.title || '(No title)';

    const sub = document.createElement('div');
    sub.className = 'playlist-item-sub';
    const sourceLabel = item.type === 'local' ? 'Local File' : item.site || 'URL';
    const durationLabel = item.duration || '-';
    sub.textContent = `${sourceLabel} / ${item.uploader || '-'} / ${durationLabel}`;

    meta.appendChild(title);
    meta.appendChild(sub);
    li.appendChild(dragHandle);
    li.appendChild(playBtn);
    li.appendChild(meta);

    li.addEventListener('dblclick', () => {
      playPlaylistItem(itemIndex);
    });

    li.addEventListener('contextmenu', (event) => {
      event.preventDefault();
      playlistContextMenuIndex = itemIndex;
      openPlaylistItemContextMenu(event.clientX, event.clientY);
    });

    playlistItemsEl.appendChild(li);
  });
}

async function loadPlaylists({ keepCurrentSelection = true } = {}) {
  const loaded = await window.api.getPlaylists();
  playlists = Array.isArray(loaded) ? loaded : [];

  if (!keepCurrentSelection || !playlists.some((playlist) => playlist.id === currentPlaylistId)) {
    currentPlaylistId = playlists[0]?.id || '';
  }

  renderPlaylists();
}

async function reorderPlaylistItems(fromIndex, toIndex) {
  if (!currentPlaylistId || fromIndex === toIndex) {
    return;
  }
  await window.api.reorderPlaylistItems({
    playlistId: currentPlaylistId,
    fromIndex,
    toIndex,
  });
  await loadPlaylists({ keepCurrentSelection: true });
}

async function removePlaylistItemAt(itemIndex) {
  if (!currentPlaylistId || itemIndex < 0) {
    return;
  }
  await window.api.removePlaylistItems({
    playlistId: currentPlaylistId,
    itemIndexes: [itemIndex],
  });
  await loadPlaylists({ keepCurrentSelection: true });
}

function createPlaylistDragHandle(itemIndex, rowEl) {
  const dragHandle = document.createElement('button');
  dragHandle.type = 'button';
  dragHandle.className = 'playlist-drag-handle';
  dragHandle.setAttribute('aria-label', '曲順を並べ替え');
  dragHandle.title = 'ドラッグして並べ替え';
  dragHandle.draggable = true;

  const icon = document.createElement('span');
  icon.className = 'playlist-drag-handle-icon';
  icon.setAttribute('aria-hidden', 'true');
  for (let i = 0; i < 3; i += 1) {
    icon.appendChild(document.createElement('span'));
  }
  dragHandle.appendChild(icon);

  dragHandle.addEventListener('dragstart', (event) => {
    event.stopPropagation();
    playlistDragFromIndex = itemIndex;
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', String(itemIndex));
    rowEl.classList.add('playlist-item-dragging');
  });

  dragHandle.addEventListener('dragend', () => {
    playlistDragFromIndex = -1;
    rowEl.classList.remove('playlist-item-dragging');
    playlistItemsEl?.querySelectorAll('.playlist-item-dragover').forEach((el) => {
      el.classList.remove('playlist-item-dragover');
    });
  });

  return dragHandle;
}

function bindPlaylistItemDropTarget(rowEl, itemIndex) {
  rowEl.addEventListener('dragover', (event) => {
    if (playlistDragFromIndex < 0) {
      return;
    }
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
    rowEl.classList.add('playlist-item-dragover');
  });

  rowEl.addEventListener('dragleave', () => {
    rowEl.classList.remove('playlist-item-dragover');
  });

  rowEl.addEventListener('drop', async (event) => {
    event.preventDefault();
    rowEl.classList.remove('playlist-item-dragover');
    const fromIndex = playlistDragFromIndex;
    playlistDragFromIndex = -1;
    if (fromIndex < 0 || fromIndex === itemIndex) {
      return;
    }
    try {
      await reorderPlaylistItems(fromIndex, itemIndex);
    } catch (error) {
      safeAlert(`曲順の変更に失敗しました。\n${error.message}`);
    }
  });
}

function closePlaylistItemContextMenu({ resetIndex = false } = {}) {
  if (playlistItemContextMenuEl) {
    playlistItemContextMenuEl.style.display = 'none';
  }
  if (resetIndex) {
    playlistContextMenuIndex = -1;
  }
}

function openPlaylistItemContextMenu(x, y) {
  if (!playlistItemContextMenuEl) {
    return;
  }
  playlistItemContextMenuEl.style.left = `${x}px`;
  playlistItemContextMenuEl.style.top = `${y}px`;
  playlistItemContextMenuEl.style.display = 'block';
}

async function createPlaylistFlow(options = {}) {
  const rawName = await askForTextInput(
    '新しいプレイリスト名を入力してください',
    '',
    {
      cancelLabel: options.cancelLabel || 'Cancel',
      okLabel: options.okLabel || 'OK',
    }
  );
  if (rawName === null) {
    return null;
  }

  const name = rawName.trim();
  if (!name) {
    safeAlert('プレイリスト名を入力してください。');
    return null;
  }

  const created = await window.api.createPlaylist({ name });
  await loadPlaylists({ keepCurrentSelection: false });
  currentPlaylistId = created.id;
  renderPlaylists();
  persistUiState();
  return created;
}

function closePlaylistPickerModal(result = null) {
  if (playlistPickerModalEl) {
    playlistPickerModalEl.style.display = 'none';
  }
  if (pendingPlaylistPickerResolver) {
    pendingPlaylistPickerResolver(result);
    pendingPlaylistPickerResolver = null;
  }
}

function renderPlaylistPickerItems() {
  if (!playlistPickerListEl) {
    return;
  }

  playlistPickerListEl.innerHTML = '';

  const createBtn = document.createElement('button');
  createBtn.type = 'button';
  createBtn.className = 'playlist-picker-item create';
  createBtn.textContent = '+ 新しいプレイリストを作成';
  createBtn.addEventListener('click', async () => {
    try {
      const created = await createPlaylistFlow({ cancelLabel: 'Back', okLabel: 'Create' });
      if (created) {
        closePlaylistPickerModal(created.id);
      }
    } catch (error) {
      safeAlert(`プレイリスト作成に失敗しました。\n${error.message}`);
    }
  });
  playlistPickerListEl.appendChild(createBtn);

  for (const playlist of playlists) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'playlist-picker-item';
    btn.textContent = playlist.name;
    btn.addEventListener('click', () => {
      closePlaylistPickerModal(playlist.id);
    });
    playlistPickerListEl.appendChild(btn);
  }
}

function openPlaylistPickerModal(title = '追加先プレイリストを選択') {
  if (!playlistPickerModalEl || !playlistPickerListEl) {
    return Promise.resolve(null);
  }

  return new Promise((resolve) => {
    pendingPlaylistPickerResolver = resolve;
    if (playlistPickerTitleEl) {
      playlistPickerTitleEl.textContent = title;
    }
    renderPlaylistPickerItems();
    playlistPickerModalEl.style.display = 'flex';
  });
}

async function addItemsToPlaylistFlow(items) {
  const normalizedItems = Array.isArray(items) ? items.filter((item) => !!item) : [];
  if (normalizedItems.length === 0) {
    safeAlert('プレイリストに追加する曲を選択してください。');
    return;
  }

  await loadPlaylists();
  const selectedPlaylistId = await openPlaylistPickerModal('追加先プレイリストを選択');
  if (!selectedPlaylistId) {
    return;
  }

  const result = await window.api.addItemsToPlaylist({
    playlistId: selectedPlaylistId,
    items: normalizedItems,
  });

  await loadPlaylists();
  currentPlaylistId = selectedPlaylistId;
  renderPlaylists();
  persistUiState();

  setStatus(`プレイリスト追加: ${result.addedCount} 件`);
}

async function playPlaylistItem(itemIndex) {
  const selectedPlaylist = playlists.find((playlist) => playlist.id === currentPlaylistId);
  const queue = Array.isArray(selectedPlaylist?.items)
    ? selectedPlaylist.items.filter((item) => !!item)
    : [];
  if (queue.length === 0) {
    return;
  }

  const safeIndex = Number.isFinite(itemIndex)
    ? Math.max(0, Math.min(Math.floor(itemIndex), queue.length - 1))
    : 0;

  setPlaybackQueue(queue, safeIndex, 'playlist');
  try {
    await playCurrentPlaybackQueueItem({ statusPrefix: '▶️ 再生中' });
  } catch (error) {
    safeAlert(`再生に失敗しました。\n${error.message}`);
    setStatus('再生に失敗しました');
  }
}

function closeContextMenu() {
  fileContextMenuEl.style.display = 'none';
}

function openContextMenu(x, y) {
  fileContextMenuEl.style.left = `${x}px`;
  fileContextMenuEl.style.top = `${y}px`;
  const hasSelectedAudio = selectedMoveFiles.size > 0;
  ctxMoveSelectedBtn.style.display = hasSelectedAudio ? 'block' : 'none';
  if (ctxAddSelectedToPlaylistBtn) {
    ctxAddSelectedToPlaylistBtn.style.display = hasSelectedAudio ? 'block' : 'none';
  }
  fileContextMenuEl.style.display = 'block';
}

function closeSearchContextMenu() {
  searchContextMenuEl.style.display = 'none';
}

function closeAllContextMenus() {
  closeContextMenu();
  closeSearchContextMenu();
  closePlaylistItemContextMenu({ resetIndex: true });
}

function openSearchContextMenu(x, y) {
  const selectedCount = selectedSearchIndexes.size;
  searchContextMenuEl.style.left = `${x}px`;
  searchContextMenuEl.style.top = `${y}px`;
  if (ctxCopySourceUrlBtn) {
    ctxCopySourceUrlBtn.style.display = selectedCount > 0 ? 'block' : 'none';
  }
  if (ctxAddSearchToPlaylistBtn) {
    ctxAddSearchToPlaylistBtn.style.display = selectedCount > 0 ? 'block' : 'none';
  }
  const isMulti = selectedCount > 1;
  const show = (btn, visible) => {
    if (btn) btn.style.display = visible ? 'block' : 'none';
  };

  show(ctxSaveToLibraryBtn, !isMulti);
  show(ctxSaveToLibraryEachBtn, isMulti);
  show(ctxSaveToLibraryNewFolderBtn, isMulti);
  show(ctxSaveToCustomBtn, !isMulti);
  show(ctxSaveToCustomEachBtn, isMulti);
  show(ctxSaveToCustomNewFolderBtn, isMulti);
  searchContextMenuEl.style.display = 'block';
}

function getSelectedSearchItems() {
  if (selectedSearchIndexes.size > 0) {
    return Array.from(selectedSearchIndexes)
      .sort((a, b) => a - b)
      .map((index) => results[index])
      .filter((item) => !!item?.webpageUrl);
  }

  if (selectedResultIndex >= 0 && results[selectedResultIndex]?.webpageUrl) {
    return [results[selectedResultIndex]];
  }

  return [];
}

async function downloadSearchItems(items, targetDir) {
  const selectedFormat = saveAudioFormatEl?.value || 'auto';
  const audioFormat = ALLOWED_SAVE_AUDIO_FORMATS.has(selectedFormat) ? selectedFormat : 'auto';
  for (let i = 0; i < items.length; i += 1) {
    const item = items[i];
    const formatLabel = audioFormat === 'auto' ? 'auto' : audioFormat;
    setStatus(`Downloading (${i + 1}/${items.length}) [${formatLabel}]: ${item.title}`);
    await window.api.downloadAudio({ url: item.webpageUrl, saveDir: targetDir, audioFormat });
  }
}

async function saveSelectedSearchAudio({
  destination = 'library',
  toNewFolderForMultiple = false,
} = {}) {
  const items = getSelectedSearchItems();
  if (items.length === 0) {
    safeAlert('保存する検索結果を選択してください。');
    return;
  }

  let targetDir = baseAudioDir;
  if (destination === 'custom') {
    const chosen = await window.api.chooseDownloadDir();
    if (!chosen) {
      return;
    }
    targetDir = chosen;
  }

  if (!targetDir) {
    safeAlert('ライブラリのパスを取得できませんでした。');
    return;
  }

  if (toNewFolderForMultiple && items.length > 1) {
    const folderNameInput = await askForTextInput('複数保存用の新規フォルダ名を入力してください');
    if (folderNameInput === null) {
      return;
    }

    const folderName = folderNameInput.trim();
    if (!folderName) {
      safeAlert('フォルダ名を入力してください。');
      return;
    }

    const createFolder = destination === 'library'
      ? window.api.createFolder
      : window.api.createFolderAt;
    const created = await createFolder({ parentDir: targetDir, name: folderName });
    targetDir = created.fullPath;
  }

  await downloadSearchItems(items, targetDir);

  if (destination === 'library') {
    await refreshLocal();
    switchTab('files');
    setStatus(`ライブラリに保存完了: ${items.length}件`);
    safeAlert(`ライブラリに保存しました。\n件数: ${items.length}件`);
    return;
  }

  setStatus(`指定フォルダに保存完了: ${items.length}件`);
  safeAlert(
    `指定フォルダに保存しました。\n件数: ${items.length}件\n保存先:\n${targetDir}\n\n再生する場合はライブラリにコピーしてください。`,
  );
}

function sortLocalFilesInPlace() {
  const mode = fileSortEl?.value || 'mtime_desc';
  localFiles.sort((a, b) => {
    if (a.isDir !== b.isDir) {
      return a.isDir ? -1 : 1;
    }

    if (mode === 'name_asc') {
      return a.name.localeCompare(b.name, 'ja');
    }
    if (mode === 'name_desc') {
      return b.name.localeCompare(a.name, 'ja');
    }
    if (mode === 'mtime_asc') {
      return a.mtimeMs - b.mtimeMs;
    }
    if (mode === 'size_desc') {
      return b.size - a.size;
    }
    if (mode === 'size_asc') {
      return a.size - b.size;
    }

    return b.mtimeMs - a.mtimeMs;
  });
}

function closeTextInputModal(result = null) {
  textInputModalEl.style.display = 'none';
  if (textInputModalCancelEl) {
    textInputModalCancelEl.textContent = 'Cancel';
  }
  if (textInputModalOkEl) {
    textInputModalOkEl.textContent = 'OK';
  }
  if (pendingTextInputResolver) {
    pendingTextInputResolver(result);
    pendingTextInputResolver = null;
  }
}

function askForTextInput(title, initialValue = '', options = {}) {
  const cancelLabel = String(options?.cancelLabel || 'Cancel');
  const okLabel = String(options?.okLabel || 'OK');

  return new Promise((resolve) => {
    pendingTextInputResolver = resolve;
    textInputModalTitleEl.textContent = title;
    textInputModalFieldEl.value = initialValue;
    if (textInputModalCancelEl) {
      textInputModalCancelEl.textContent = cancelLabel;
    }
    if (textInputModalOkEl) {
      textInputModalOkEl.textContent = okLabel;
    }
    textInputModalEl.style.display = 'flex';
    textInputModalFieldEl.focus();
    textInputModalFieldEl.select();
  });
}

async function moveSelectedToNewFolder() {
  const targets = Array.from(selectedMoveFiles);
  if (targets.length === 0) {
    safeAlert('移動する曲を1つ以上選択してください。');
    return;
  }

  const parentDir = currentAudioDir || baseAudioDir;
  if (!parentDir) {
    safeAlert('移動先フォルダが見つかりません。');
    return;
  }

  const folderNameInput = await askForTextInput('移動先の新規フォルダ名を入力してください');
  if (folderNameInput === null) {
    return;
  }

  const folderName = folderNameInput.trim();
  if (!folderName) {
    safeAlert('フォルダ名を入力してください。');
    return;
  }

  const result = await window.api.moveToNewFolder({
    parentDir,
    folderName,
    filePaths: targets,
  });
  selectedMoveFiles.clear();
  selectionAnchorIndex = -1;
  await refreshLocal();
  setStatus(`${result.movedCount} 曲を ${result.folderName} に移動しました`);
}

function switchTab(tabName) {
  currentTabActive = tabName;
  document.querySelectorAll('.tab-btn').forEach((btn) => btn.classList.remove('active'));
  document.querySelectorAll('.tab-content').forEach((content) => content.classList.remove('active'));
  document.querySelector(`[data-tab="${tabName}"].tab-btn`).classList.add('active');
  document.querySelector(`[data-tab="${tabName}"].tab-content`).classList.add('active');
  persistUiState();
}

function clampVolume(value) {
  if (!Number.isFinite(value)) {
    return 1;
  }
  return Math.max(0, Math.min(1, value));
}

function updateVolumeUi() {
  const volume = audioPlayer.muted ? 0 : clampVolume(audioPlayer.volume);
  const percent = Math.round(volume * 100);
  if (playerVolumeBar) {
    playerVolumeBar.value = String(percent);
    playerVolumeBar.style.setProperty('--volume-progress', `${percent}%`);
  }
  if (playerVolumeValue) {
    playerVolumeValue.textContent = `${percent}%`;
  }
  if (playerVolumeBtn) {
    playerVolumeBtn.textContent = percent === 0 ? '🔇' : percent < 40 ? '🔉' : '🔊';
  }
}

function normalizeLoopMode(mode) {
  const normalized = String(mode || '').toLowerCase();
  if (LOOP_MODES.includes(normalized)) {
    return normalized;
  }
  return 'off';
}

function getLoopModeDisplayText(mode) {
  if (mode === 'single') {
    return '単曲ループ';
  }
  if (mode === 'playlist') {
    return 'プレイリスト全体ループ';
  }
  return 'OFF';
}

function getNextLoopMode(mode) {
  if (mode === 'off') {
    return 'single';
  }
  if (mode === 'single') {
    return 'playlist';
  }
  return 'off';
}

function updateRepeatUi() {
  if (!playerRepeatBtn) {
    return;
  }

  const mode = normalizeLoopMode(playerLoopMode);
  const isActive = mode !== 'off';
  playerRepeatBtn.classList.toggle('active', isActive);
  playerRepeatBtn.setAttribute('aria-pressed', isActive ? 'true' : 'false');
  playerRepeatBtn.textContent = mode === 'single' ? '🔂' : '🔁';
  playerRepeatBtn.title = `ループ: ${getLoopModeDisplayText(mode)}`;
}

function setPlayerLoopMode(mode, { persist = true } = {}) {
  playerLoopMode = normalizeLoopMode(mode);
  // Looping behavior is handled by the ended event to support playlist loops.
  audioPlayer.loop = false;
  updateRepeatUi();
  if (persist) {
    persistUiState();
  }
}

function setPlayerVolume(nextVolume, { persist = true } = {}) {
  const clamped = clampVolume(nextVolume);
  audioPlayer.volume = clamped;
  audioPlayer.muted = clamped === 0;
  if (clamped > 0) {
    lastNonZeroVolume = clamped;
  }
  updateVolumeUi();
  if (persist) {
    persistUiState();
  }
}

function persistUiState() {
  if (suppressStatePersist) {
    return;
  }

  window.api.saveUIState({
    tab: currentTabActive,
    audioDir: currentAudioDir,
    playerVolume: clampVolume(audioPlayer.volume),
    playerMuted: audioPlayer.muted,
    playerLoopMode,
    playerRepeat: playerLoopMode === 'single',
    saveAudioFormat: saveAudioFormatEl?.value || 'auto',
    playlistId: currentPlaylistId,
    searchSnapshot: getSearchSnapshotForState(),
  });
}

function updateBackButtonVisibility() {
  const isInSubfolder = currentAudioDir && currentAudioDir !== baseAudioDir;
  backBtn.style.display = isInSubfolder ? 'block' : 'none';
}

function renderResults() {
  const oldRects = new Map();
  Array.from(tableBody.children).forEach((tr) => {
    if (tr.dataset.url) {
      oldRects.set(tr.dataset.url, tr.getBoundingClientRect());
    }
  });

  tableBody.innerHTML = '';
  results.forEach((item, index) => {
    const tr = document.createElement('tr');
    tr.dataset.url = item.webpageUrl;
    
    if (index === selectedResultIndex || selectedSearchIndexes.has(index)) {
      tr.classList.add('selected');
    }

    const previewLabel = previewLoadingIndex === index ? '...' : '▶';
    tr.innerHTML = `
      <td><button class="preview-btn" data-preview-index="${index}">${previewLabel}</button></td>
      <td>${item.title}</td>
      <td>${item.uploader}</td>
      <td>${item.duration}</td>
      <td>${item.site}</td>
    `;

    tr.addEventListener('click', (event) => {
      selectedResultIndex = index;
      const isToggleModifier = event.metaKey || event.ctrlKey;

      if (isToggleModifier) {
        if (selectedSearchIndexes.has(index)) {
          selectedSearchIndexes.delete(index);
        } else {
          selectedSearchIndexes.add(index);
        }
        searchSelectionAnchorIndex = index;
      } else if (event.shiftKey && searchSelectionAnchorIndex >= 0) {
        selectedSearchIndexes.clear();
        const start = Math.min(searchSelectionAnchorIndex, index);
        const end = Math.max(searchSelectionAnchorIndex, index);
        for (let i = start; i <= end; i += 1) {
          selectedSearchIndexes.add(i);
        }
      } else {
        selectedSearchIndexes.clear();
        selectedSearchIndexes.add(index);
        searchSelectionAnchorIndex = index;
      }

      setStatus(`検索結果選択: ${selectedSearchIndexes.size} 件`);
      renderResults();
    });

    tr.addEventListener('contextmenu', (event) => {
      event.preventDefault();
      searchContextMenuIndex = index;
      if (!selectedSearchIndexes.has(index)) {
        selectedSearchIndexes.clear();
        selectedSearchIndexes.add(index);
        selectedResultIndex = index;
        searchSelectionAnchorIndex = index;
        renderResults();
      }
      openSearchContextMenu(event.clientX, event.clientY);
    });

    const previewBtn = tr.querySelector('.preview-btn');
    previewBtn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const result = results[index];
      if (!result?.webpageUrl) {
        safeAlert('試聴URLが見つかりませんでした。');
        return;
      }

      const queueItem = toPlaylistItemFromSearchItem(result);
      if (!queueItem) {
        safeAlert('試聴URLが見つかりませんでした。');
        return;
      }

      previewLoadingIndex = index;
      renderResults();
      setStatus('試聴音声を準備中...');

      try {
        setPlaybackQueue([queueItem], 0, 'search');
        await playCurrentPlaybackQueueItem({ statusPrefix: '▶️ 試聴中' });
        setNowPlayingText(result.title || 'Preview');
        setStatus(`▶️ 試聴中: ${result.title}`);
      } catch (error) {
        safeAlert(`試聴に失敗しました。\n${error.message}`);
        setStatus('試聴に失敗しました');
      } finally {
        previewLoadingIndex = -1;
        renderResults();
      }
    });

    tableBody.appendChild(tr);
  });

  // Apply FLIP animation
  Array.from(tableBody.children).forEach((tr) => {
    const url = tr.dataset.url;
    const oldRect = oldRects.get(url);
    if (!oldRect) {
      tr.style.opacity = '0';
      requestAnimationFrame(() => {
        tr.style.transition = 'opacity 0.4s ease-out';
        tr.style.opacity = '1';
      });
      return;
    }
    const newRect = tr.getBoundingClientRect();
    const deltaY = oldRect.top - newRect.top;
    if (deltaY !== 0) {
      tr.style.transform = `translateY(${deltaY}px)`;
      tr.style.transition = 'none';
      
      // Force repaint
      tr.getBoundingClientRect();

      tr.style.transition = 'transform 0.4s cubic-bezier(0.25, 1, 0.5, 1), background-color 0.4s';
      tr.style.transform = '';
      
      // Highlight updated rows briefly
      const originalBg = tr.style.backgroundColor;
      tr.style.backgroundColor = 'rgba(255, 255, 255, 0.1)';
      setTimeout(() => {
        tr.style.backgroundColor = originalBg;
      }, 400);
    }
  });
}

function renderLocalFiles() {
  audioListEl.innerHTML = '';
  localFiles.forEach((file, index) => {
    const li = document.createElement('li');
    li.style.display = 'flex';
    li.style.alignItems = 'center';
    li.style.gap = '12px';
    li.style.cursor = 'pointer';
    
    if (index === selectedLocalIndex) {
      li.classList.add('active');
    }

    if (file.isDir) {
      // Folder item
      const icon = document.createElement('span');
      icon.textContent = '📁';
      icon.style.flexShrink = '0';
      icon.style.fontSize = '20px';
      li.appendChild(icon);
      
      const name = document.createElement('span');
      name.textContent = file.name;
      name.style.flex = '1';
      li.appendChild(name);
      
      li.addEventListener('click', async () => {
        currentAudioDir = file.fullPath;
        selectedLocalIndex = -1;
        await refreshLocal();
        updateBackButtonVisibility();
        persistUiState();
      });

      li.addEventListener('contextmenu', (event) => {
        event.preventDefault();
        contextMenuTargetPath = file.fullPath;
        openContextMenu(event.clientX, event.clientY);
      });
    } else if (file.isAudio) {
      // Audio file with play button
      if (selectedMoveFiles.has(file.fullPath)) {
        li.classList.add('multi-selected');
      }

      const playBtn = document.createElement('button');
      playBtn.textContent = '▶';
      playBtn.className = 'audio-play-btn';
      playBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        selectedLocalIndex = index;
        const queue = buildLocalPlaybackQueue();
        const queueIndex = queue.findIndex((item) => item.type === 'local' && item.fullPath === file.fullPath);
        setPlaybackQueue(queue, queueIndex >= 0 ? queueIndex : 0, 'files');
        console.log('[Audio] Play button clicked:', {
          file: file.name,
          fullPath: file.fullPath,
          baseDir: baseAudioDir,
          queueLength: queue.length,
          serverPort: audioServerPort,
          fileSize: file.size,
        });
        playCurrentPlaybackQueueItem({ statusPrefix: '▶️ 再生中' }).catch((err) => {
          console.error('[Audio] play() rejected:', err);
        });
        renderLocalFiles();
      });
      li.appendChild(playBtn);
      
      const name = document.createElement('span');
      name.textContent = file.name;
      name.style.flex = '1';
      li.appendChild(name);
      
      li.addEventListener('click', (event) => {
        selectedLocalIndex = index;
        const isToggleModifier = event.metaKey || event.ctrlKey;

        if (isToggleModifier) {
          if (selectedMoveFiles.has(file.fullPath)) {
            selectedMoveFiles.delete(file.fullPath);
          } else {
            selectedMoveFiles.add(file.fullPath);
          }
          selectionAnchorIndex = index;
        } else if (event.shiftKey && selectionAnchorIndex >= 0) {
          selectedMoveFiles.clear();
          const start = Math.min(selectionAnchorIndex, index);
          const end = Math.max(selectionAnchorIndex, index);
          for (let i = start; i <= end; i += 1) {
            const item = localFiles[i];
            if (item && item.isAudio) {
              selectedMoveFiles.add(item.fullPath);
            }
          }
        } else {
          selectedMoveFiles.clear();
          selectedMoveFiles.add(file.fullPath);
          selectionAnchorIndex = index;
        }

        setStatus(`選択中: ${selectedMoveFiles.size} 曲`);
        renderLocalFiles();
      });

      li.addEventListener('contextmenu', (event) => {
        event.preventDefault();
        if (!selectedMoveFiles.has(file.fullPath)) {
          selectedMoveFiles.clear();
          selectedMoveFiles.add(file.fullPath);
          selectedLocalIndex = index;
          selectionAnchorIndex = index;
          renderLocalFiles();
        }
        contextMenuTargetPath = file.fullPath;
        openContextMenu(event.clientX, event.clientY);
      });
      
      li.addEventListener('dblclick', () => {
        selectedLocalIndex = index;
        const queue = buildLocalPlaybackQueue();
        const queueIndex = queue.findIndex((item) => item.type === 'local' && item.fullPath === file.fullPath);
        setPlaybackQueue(queue, queueIndex >= 0 ? queueIndex : 0, 'files');
        playCurrentPlaybackQueueItem({ statusPrefix: '▶️ 再生中' }).catch((err) => {
          console.error('[Audio] play() rejected:', err);
        });
        renderLocalFiles();
      });
    } else {
      // Other files
      li.textContent = file.name;
    }

    audioListEl.appendChild(li);
  });
}

function formatSyncFolderState(state) {
  const labels = {
    idle: '同期完了',
    syncing: '同期中',
    scanning: 'スキャン中',
    missing: '未設定',
    error: 'エラー',
    unknown: '不明',
  };
  return labels[state] || state || '不明';
}

function formatSyncBytes(bytes) {
  const value = Number(bytes) || 0;
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  if (value < 1024 * 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)} MB`;
  return `${(value / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

function renderSyncDeviceList(devices) {
  if (!syncDeviceListEl) return;
  syncDeviceListEl.innerHTML = '';
  const list = Array.isArray(devices) ? devices : [];
  if (list.length === 0) {
    const li = document.createElement('li');
    li.textContent = '接続済みのデバイスはありません';
    syncDeviceListEl.appendChild(li);
    return;
  }
  for (const device of list) {
    const li = document.createElement('li');
    const name = document.createElement('span');
    name.className = 'sync-device-name';
    name.textContent = device.name || device.deviceID;
    const status = document.createElement('span');
    status.className = device.connected ? 'sync-device-connected' : 'sync-device-disconnected';
    status.textContent = device.connected ? '接続中' : '未接続';
    li.appendChild(name);
    li.appendChild(status);
    syncDeviceListEl.appendChild(li);
  }
}

function applySyncInfo(info) {
  if (!info?.ok) {
    if (syncFolderStatusEl) {
      syncFolderStatusEl.textContent = `同期: ${info?.error || '状態を取得できません'}`;
    }
    renderSyncDeviceList([]);
    return;
  }
  if (syncMyDeviceIdEl) {
    syncMyDeviceIdEl.value = info.myID || '';
  }
  if (syncFolderStatusEl) {
    const stateLabel = formatSyncFolderState(info.folderState);
    const pathLabel = info.folderPath ? ` (${info.folderPath})` : '';
    const pending = Number(info.needBytes) > 0
      ? ` / 残り ${formatSyncBytes(info.needBytes)}`
      : '';
    syncFolderStatusEl.textContent = `同期フォルダ: ${stateLabel}${pathLabel}${pending}`;
  }
  renderSyncDeviceList(info.devices);
}

async function refreshSyncInfo() {
  if (!window.api?.syncthingGetInfo) return;
  try {
    const info = await window.api.syncthingGetInfo();
    applySyncInfo(info);
  } catch (error) {
    applySyncInfo({ ok: false, error: error.message });
  }
}

async function handleSyncUpdated(payload = {}) {
  if (!bootCompleted) {
    return;
  }

  if (payload.phase === 'syncing' && payload.startup) {
    if (syncFolderStatusEl) {
      syncFolderStatusEl.textContent = 'バックグラウンド同期中...';
    }
    await refreshSyncInfo();
    return;
  }

  const type = payload?.type;
  if (!type || type === 'playlists' || type === 'dir') {
    await loadPlaylists({ keepCurrentSelection: true });
  }
  if (!type || type === 'audio' || type === 'dir') {
    await refreshLocal();
  }
  await refreshSyncInfo();
}

function isPathWithinLibrary(targetPath) {
  const libraryDir = String(baseAudioDir || '').trim();
  const target = String(targetPath || '').trim();
  if (!libraryDir || !target) {
    return false;
  }
  return target === libraryDir || target.startsWith(`${libraryDir}/`);
}

function getActiveSaveDir() {
  return String(baseAudioDir || '').trim();
}

function getActiveListDir() {
  const baseDir = getActiveSaveDir();
  const nestedDir = String(currentAudioDir || '').trim();
  return nestedDir || baseDir;
}

async function refreshLocal() {
  try {
    const listDir = getActiveListDir();
    if (!listDir) {
      return;
    }
    localFiles = await window.api.listAudio({ saveDir: listDir });
    if (selectedLocalIndex >= localFiles.length) {
      selectedLocalIndex = -1;
    }
    const visibleSet = new Set(localFiles.filter((f) => f.isAudio).map((f) => f.fullPath));
    for (const fullPath of Array.from(selectedMoveFiles)) {
      if (!visibleSet.has(fullPath)) {
        selectedMoveFiles.delete(fullPath);
      }
    }
    sortLocalFilesInPlace();
    renderLocalFiles();
  } catch (error) {
    safeAlert(`保存先にアクセスできません。\n${error.message}`);
  }
}

async function boot() {
  if (!detachSearchMetadataListener && window.api.onSearchMetadataUpdated) {
    detachSearchMetadataListener = window.api.onSearchMetadataUpdated((update) => {
      applySearchMetadataUpdate(update);
    });
  }
  if (!detachSearchEnrichmentListener && window.api.onSearchEnrichmentStatus) {
    detachSearchEnrichmentListener = window.api.onSearchEnrichmentStatus((status) => {
      isBackgroundEnriching = status.active;
      updateSearchLoadingSpinner();
      if (status.active && searchLoadingInlineEl) {
        searchLoadingInlineEl.innerHTML = `<span class="spinner"></span> データの取得・計算中... (${status.count}件)`;
      } else if (searchLoadingInlineEl) {
        searchLoadingInlineEl.innerHTML = `<span class="spinner"></span> Loading...`;
      }
    });
  }
  if (!detachSyncUpdatedListener && window.api.onSyncUpdated) {
    detachSyncUpdatedListener = window.api.onSyncUpdated((payload) => {
      handleSyncUpdated(payload).catch((err) => {
        console.error('[Sync] Failed to apply update:', err);
      });
    });
  }

  suppressStatePersist = true;

  const [libraryDir, savedState, serverPort, platformInfo] = await Promise.all([
    window.api.getLibraryDir(),
    window.api.loadUIState(),
    window.api.getAudioServerPort(),
    window.api.getPlatformInfo?.() ?? null,
  ]);
  if (platformInfo && openLibraryBtn) {
    openLibraryBtn.textContent = `${platformInfo.fileManagerLabel || 'フォルダ'}で開く`;
  }
  baseAudioDir = libraryDir;
  if (libraryPathEl) {
    libraryPathEl.value = libraryDir;
  }
  audioServerPort = serverPort;
  console.log('[Boot] Audio server port:', audioServerPort);

  updateSearchPager();
  updateOptionsVisibility();

  if (savedState) {
    currentTabActive = savedState.tab || 'search';
    const restoredAudioDir = String(savedState.audioDir || '').trim();
    currentAudioDir = isPathWithinLibrary(restoredAudioDir) ? restoredAudioDir : '';
    console.log('[Boot] Restored state:', savedState);
  } else {
    currentAudioDir = '';
    currentTabActive = 'search';
  }

  if (savedState?.searchSnapshot) {
    restoreSearchSnapshotFromState(savedState.searchSnapshot);
  }

  if (!['search', 'files', 'playlist'].includes(currentTabActive)) {
    currentTabActive = 'search';
  }
  switchTab(currentTabActive);

  const savedVolume = Number(savedState?.playerVolume);
  const initialVolume = Number.isFinite(savedVolume) ? savedVolume : 1;
  const shouldMute = Boolean(savedState?.playerMuted) || initialVolume <= 0;
  audioPlayer.volume = clampVolume(initialVolume);
  audioPlayer.muted = shouldMute;
  if (audioPlayer.volume > 0) {
    lastNonZeroVolume = audioPlayer.volume;
  }
  updateVolumeUi();

  const savedLoopMode = savedState?.playerLoopMode || (savedState?.playerRepeat ? 'single' : 'off');
  setPlayerLoopMode(savedLoopMode, { persist: false });

  const savedAudioFormat = String(savedState?.saveAudioFormat || 'auto').toLowerCase();
  if (saveAudioFormatEl && ALLOWED_SAVE_AUDIO_FORMATS.has(savedAudioFormat)) {
    saveAudioFormatEl.value = savedAudioFormat;
  }

  selectedLocalIndex = -1;
  setNowPlayingText('');

  await Promise.all([
    loadPlaylists({ keepCurrentSelection: false }),
    refreshLocal(),
  ]);

  const savedPlaylistId = String(savedState?.playlistId || '').trim();
  if (savedPlaylistId && playlists.some((playlist) => playlist.id === savedPlaylistId)) {
    currentPlaylistId = savedPlaylistId;
    renderPlaylists();
  }

  updateBackButtonVisibility();

  suppressStatePersist = false;
  persistUiState();

  bootCompleted = true;

  if (syncInfoRefreshTimer) {
    clearInterval(syncInfoRefreshTimer);
  }
  syncInfoRefreshTimer = setInterval(() => {
    refreshSyncInfo().catch(() => {});
  }, 15000);
  void refreshSyncInfo();

  // Add audio player error handling
  audioPlayer.addEventListener('error', () => {
    const errorCode = audioPlayer.error?.code;
    const errorMessage = {
      1: 'Aborted',
      2: 'Network error',
      3: 'Decoding failed',
      4: 'Format not supported',
    }[errorCode] || `Unknown error (${errorCode})`;
    
    console.error(`[Audio Error] ${errorMessage}`, {
      code: errorCode,
      src: audioPlayer.src,
      currentTime: audioPlayer.currentTime,
      networkState: audioPlayer.networkState,
      readyState: audioPlayer.readyState,
    });

    const isPreviewStream = typeof audioPlayer.src === 'string' && audioPlayer.src.includes('/stream?url=');
    if (isPreviewStream) {
      setStatus(`❌ 試聴エラー: ${errorMessage}`);
      window.api.getStreamLogPath()
        .then((logPath) => {
          safeAlert(`試聴に失敗しました。\n\n理由: ${errorMessage}\n\nログを確認してください:\n${logPath}`);
        })
        .catch(() => {
          safeAlert(`試聴に失敗しました。\n\n理由: ${errorMessage}`);
        });
      return;
    }
    
    const file = localFiles[selectedLocalIndex];
    if (file) {
      setStatus(`❌ 再生エラー: ${file.name} - ${errorMessage}`);
      safeAlert(`再生できません: ${file.name}\n\n理由: ${errorMessage}\n\nファイルが破損している可能性があります。別のファイルを試してください。`);
    }
  });

  audioPlayer.addEventListener('loadstart', () => {
    console.log('[Audio] loadstart event');
  });

  audioPlayer.addEventListener('timeupdate', () => {
    const duration = (isFinite(audioPlayer.duration) && audioPlayer.duration > 0)
                       ? audioPlayer.duration
                       : currentAudioDurationSec;
    const current = audioPlayer.currentTime || 0;
    
    if (duration > 0) {
      const percent = Math.min(100, (current / duration) * 100);
      playerProgressBar.style.width = `${percent}%`;
      playerTime.textContent = `${formatSecToDurationInput(current)} / ${formatSecToDurationInput(duration)}`;
    } else {
      playerProgressBar.style.width = '0%';
      playerTime.textContent = `${formatSecToDurationInput(current)} / 0:00`;
    }
  });

  audioPlayer.addEventListener('progress', () => {
    const duration = (isFinite(audioPlayer.duration) && audioPlayer.duration > 0)
                       ? audioPlayer.duration
                       : currentAudioDurationSec;
    if (duration > 0 && audioPlayer.buffered.length > 0) {
      const bufferedEnd = audioPlayer.buffered.end(audioPlayer.buffered.length - 1);
      const percent = Math.min(100, (bufferedEnd / duration) * 100);
      playerProgressBarBuffered.style.width = `${percent}%`;
    } else {
      playerProgressBarBuffered.style.width = '0%';
    }
  });

  playerPlayPauseBtn.addEventListener('click', () => {
    if (audioPlayer.paused) {
      audioPlayer.play();
    } else {
      audioPlayer.pause();
    }
  });

  playerProgressContainer.addEventListener('click', (e) => {
    const duration = (isFinite(audioPlayer.duration) && audioPlayer.duration > 0)
                       ? audioPlayer.duration
                       : currentAudioDurationSec;
    if (duration > 0) {
      const rect = playerProgressContainer.getBoundingClientRect();
      const clickX = e.clientX - rect.left;
      const percent = Math.max(0, Math.min(1, clickX / rect.width));
      audioPlayer.currentTime = percent * duration;
    }
  });

  if (playerVolumeBar) {
    playerVolumeBar.addEventListener('input', (e) => {
      const sliderValue = Number(e.target?.value);
      setPlayerVolume((Number.isFinite(sliderValue) ? sliderValue : 100) / 100);
    });
  }

  if (playerVolumeBtn) {
    playerVolumeBtn.addEventListener('click', () => {
      const current = audioPlayer.muted ? 0 : clampVolume(audioPlayer.volume);
      if (current <= 0) {
        const restoreVolume = lastNonZeroVolume > 0 ? lastNonZeroVolume : 1;
        setPlayerVolume(restoreVolume);
      } else {
        setPlayerVolume(0);
      }
    });
  }

  if (playerRepeatBtn) {
    playerRepeatBtn.addEventListener('click', () => {
      const nextMode = getNextLoopMode(playerLoopMode);
      setPlayerLoopMode(nextMode);
      setStatus(`ループ: ${getLoopModeDisplayText(nextMode)}`);
    });
  }

  audioPlayer.addEventListener('play', () => {
    playerPlayPauseBtn.textContent = '⏸';
  });

  audioPlayer.addEventListener('pause', () => {
    playerPlayPauseBtn.textContent = '▶';
  });

  audioPlayer.addEventListener('canplay', () => {
    console.log('[Audio] canplay - ready to play');
  });

  audioPlayer.addEventListener('playing', () => {
    const file = localFiles[selectedLocalIndex];
    if (file) {
      console.log('[Audio] playing:', file.name);
      setStatus(`▶️ 再生中: ${file.name}`);
    }
  });

  audioPlayer.addEventListener('pause', () => {
    console.log('[Audio] paused');
  });

  audioPlayer.addEventListener('loadedmetadata', () => {
    console.log('[Audio] Metadata loaded, duration:', audioPlayer.duration);
  });

  audioPlayer.addEventListener('loadeddata', () => {
    console.log('[Audio] Data loaded');
  });

  audioPlayer.addEventListener('ended', () => {
    handleEndedPlayback();
  });

  audioPlayer.addEventListener('volumechange', () => {
    if (!audioPlayer.muted && audioPlayer.volume > 0) {
      lastNonZeroVolume = audioPlayer.volume;
    }
    updateVolumeUi();
  });
}

searchBtn.addEventListener('click', async () => {
  if (isSearchUiLocked()) {
    return;
  }

  const input = queryEl.value.trim();
  if (!input) {
    safeAlert('検索キーワードまたはURLを入力してください。');
    return;
  }

  await withSearchUiLock(async () => {
    setSearchStatus('Searching...');
    setStatus('Searching...');
    try {
      currentSearchQuery = input;
      currentSearchPage = 1;
      searchPageCache.clear();
      hasNextSearchPage = false;
      updateSearchPager();
      await fetchSearchPage(1);
      triggerPreloads();
      setStatus(`Found ${results.length} results`);
    } catch (error) {
      setSearchStatus('Search failed');
      safeAlert(`検索に失敗しました。\n${error.message}`);
      setStatus('Search failed');
    }
  });
});

resultSourceFilterEl.addEventListener('change', async () => {
  if (isSearchUiLocked()) {
    return;
  }
  await withSearchUiLock(async () => {
    currentSearchPage = 1;
    await fetchSearchPage(1);
  });
  triggerPreloads();
});

prevPageBtn.addEventListener('click', async () => {
  if (isSearchUiLocked() || !currentSearchQuery || currentSearchPage <= 1) {
    return;
  }
  try {
    await withSearchUiLock(async () => {
      await fetchSearchPage(currentSearchPage - 1);
    });
  } catch (error) {
    safeAlert(`前のページ取得に失敗しました。\n${error.message}`);
  }
});

nextPageBtn.addEventListener('click', async () => {
  if (isSearchUiLocked() || !currentSearchQuery || !hasNextSearchPage) {
    return;
  }
  try {
    const ok = await withSearchUiLock(async () => fetchSearchPage(currentSearchPage + 1));
    if (!ok) {
      hasNextSearchPage = false;
      updateSearchPager();
    }
    triggerPreloads();
  } catch (error) {
    safeAlert(`次のページ取得に失敗しました。\n${error.message}`);
  }
});

toggleOptionsBtn.addEventListener('click', () => {
  optionsExpanded = !optionsExpanded;
  updateOptionsVisibility();
});

durationPrefEnabledEl.addEventListener('change', () => {
  scheduleApplyOptions(0);
});

durationMinEl.addEventListener('input', () => {
  scheduleApplyOptions(300);
});

durationMaxEl.addEventListener('input', () => {
  scheduleApplyOptions(300);
});

queryEl.addEventListener('keydown', (event) => {
  if (isSearchUiLocked() || event.isComposing) {
    return;
  }
  if (event.key === 'Enter') {
    event.preventDefault();
    searchBtn.click();
  }
});

refreshBtn.addEventListener('click', refreshLocal);

if (openLibraryBtn) {
  openLibraryBtn.addEventListener('click', async () => {
    if (!baseAudioDir) {
      safeAlert('ライブラリのパスを取得できませんでした。');
      return;
    }
    try {
      await window.api.openInFinder({ filePath: baseAudioDir });
    } catch (error) {
      safeAlert(`Finderで開けませんでした。\n${error.message}`);
    }
  });
}

ctxMoveSelectedBtn.addEventListener('click', async () => {
  closeContextMenu();
  try {
    await moveSelectedToNewFolder();
  } catch (error) {
    safeAlert(`曲の移動に失敗しました。\n${error.message}`);
  }
});

if (ctxAddSelectedToPlaylistBtn) {
  ctxAddSelectedToPlaylistBtn.addEventListener('click', async () => {
    closeContextMenu();
    try {
      const items = getSelectedLocalItemsForPlaylist();
      await addItemsToPlaylistFlow(items);
    } catch (error) {
      safeAlert(`プレイリスト追加に失敗しました。\n${error.message}`);
    }
  });
}

ctxOpenInFinderBtn.addEventListener('click', async () => {
  closeContextMenu();
  if (contextMenuTargetPath) {
    await window.api.openInFinder({ filePath: contextMenuTargetPath });
    return;
  }
  const targets = Array.from(selectedMoveFiles);
  if (targets.length === 0) {
    safeAlert('ファイルを選択してください。');
    return;
  }
  await window.api.openInFinder({ filePath: targets[0] });
});

fileSortEl.addEventListener('change', () => {
  selectedLocalIndex = -1;
  selectionAnchorIndex = -1;
  sortLocalFilesInPlace();
  renderLocalFiles();
});

ctxOpenSourceUrlBtn.addEventListener('click', async () => {
  closeSearchContextMenu();
  const target = results[searchContextMenuIndex] || getSelectedSearchItems()[0];
  if (!target?.webpageUrl) {
    safeAlert('元URLが見つかりません。');
    return;
  }
  try {
    await window.api.openExternal({ url: target.webpageUrl });
  } catch (error) {
    safeAlert(`ブラウザで開けませんでした。\n${error.message}`);
  }
});

if (ctxCopySourceUrlBtn) {
  ctxCopySourceUrlBtn.addEventListener('click', async () => {
    closeSearchContextMenu();
    const selected = getSelectedSearchItems();
    if (selected.length === 0) {
      safeAlert('コピーするURLがありません。');
      return;
    }

    const urls = selected
      .map((item) => item?.webpageUrl)
      .filter((url) => typeof url === 'string' && url.length > 0);
    if (urls.length === 0) {
      safeAlert('コピーするURLがありません。');
      return;
    }

    try {
      await window.api.writeClipboardText({ text: urls.join('\n') });
      setStatus(`URLをコピーしました (${urls.length}件)`);
    } catch (error) {
      safeAlert(`URLコピーに失敗しました。\n${error.message}`);
    }
  });
}

if (ctxAddSearchToPlaylistBtn) {
  ctxAddSearchToPlaylistBtn.addEventListener('click', async () => {
    closeSearchContextMenu();
    try {
      const items = getSelectedSearchItems()
        .map((item) => toPlaylistItemFromSearchItem(item))
        .filter((item) => !!item);
      await addItemsToPlaylistFlow(items);
    } catch (error) {
      safeAlert(`プレイリスト追加に失敗しました。\n${error.message}`);
    }
  });
}

ctxSetDurationFromItemBtn.addEventListener('click', async () => {
  closeSearchContextMenu();
  const target = results[searchContextMenuIndex] || getSelectedSearchItems()[0];
  if (!target?.duration || target.duration === '-') {
    safeAlert('この結果にはduration情報がありません。');
    return;
  }

  const centerSec = parseDurationInputToSec(target.duration, -1);
  if (!Number.isFinite(centerSec) || centerSec < 0) {
    safeAlert('durationの解析に失敗しました。');
    return;
  }

  const minSec = Math.max(0, centerSec - 10);
  const maxSec = centerSec + 10;
  durationMinEl.value = formatSecToDurationInput(minSec);
  durationMaxEl.value = formatSecToDurationInput(maxSec);
  durationPrefEnabledEl.checked = true;
  setStatus(`duration設定: ${durationMinEl.value} ~ ${durationMaxEl.value}`);

  scheduleApplyOptions(0);
});

function bindSaveContextAction(button, handler) {
  if (!button) return;
  button.addEventListener('click', async () => {
    closeSearchContextMenu();
    try {
      await handler();
    } catch (error) {
      safeAlert(`保存に失敗しました。\n${error.message}`);
    }
  });
}

bindSaveContextAction(ctxSaveToLibraryBtn, () => (
  saveSelectedSearchAudio({ destination: 'library', toNewFolderForMultiple: false })
));
bindSaveContextAction(ctxSaveToLibraryEachBtn, () => (
  saveSelectedSearchAudio({ destination: 'library', toNewFolderForMultiple: false })
));
bindSaveContextAction(ctxSaveToLibraryNewFolderBtn, () => (
  saveSelectedSearchAudio({ destination: 'library', toNewFolderForMultiple: true })
));
bindSaveContextAction(ctxSaveToCustomBtn, () => (
  saveSelectedSearchAudio({ destination: 'custom', toNewFolderForMultiple: false })
));
bindSaveContextAction(ctxSaveToCustomEachBtn, () => (
  saveSelectedSearchAudio({ destination: 'custom', toNewFolderForMultiple: false })
));
bindSaveContextAction(ctxSaveToCustomNewFolderBtn, () => (
  saveSelectedSearchAudio({ destination: 'custom', toNewFolderForMultiple: true })
));

if (ctxRemoveFromPlaylistBtn) {
  ctxRemoveFromPlaylistBtn.addEventListener('click', async (event) => {
    event.stopPropagation();
    const itemIndex = playlistContextMenuIndex;
    closePlaylistItemContextMenu({ resetIndex: true });
    if (itemIndex < 0) {
      return;
    }
    try {
      await removePlaylistItemAt(itemIndex);
      setStatus('プレイリストから曲を削除しました');
    } catch (error) {
      safeAlert(`プレイリストからの削除に失敗しました。\n${error.message}`);
    }
  });
}

if (playlistItemContextMenuEl) {
  playlistItemContextMenuEl.addEventListener('click', (event) => {
    event.stopPropagation();
  });
}

document.addEventListener('click', (event) => {
  if (event.target.closest('.context-menu')) {
    return;
  }
  closeAllContextMenus();
});

document.addEventListener('keydown', (event) => {
  const target = event.target;
  const tagName = String(target?.tagName || '').toUpperCase();
  const isTypingContext =
    tagName === 'INPUT' ||
    tagName === 'TEXTAREA' ||
    tagName === 'SELECT' ||
    target?.isContentEditable;

  if (!isTypingContext && (event.code === 'Space' || event.key === ' ') && !event.repeat) {
    if (audioPlayer.src) {
      event.preventDefault();
      if (audioPlayer.paused) {
        audioPlayer.play().catch(() => {});
      } else {
        audioPlayer.pause();
      }
    }
    return;
  }

  if (event.key === 'Escape') {
    closeAllContextMenus();
    if (textInputModalEl.style.display !== 'none') {
      closeTextInputModal(null);
      return;
    }
    if (playlistPickerModalEl && playlistPickerModalEl.style.display !== 'none') {
      closePlaylistPickerModal(null);
    }
  }
});

textInputModalCancelEl.addEventListener('click', () => {
  closeTextInputModal(null);
});

textInputModalOkEl.addEventListener('click', () => {
  closeTextInputModal(textInputModalFieldEl.value);
});

textInputModalFieldEl.addEventListener('keydown', (event) => {
  if (event.isComposing) {
    return;
  }
  if (event.key === 'Enter') {
    event.preventDefault();
    closeTextInputModal(textInputModalFieldEl.value);
  } else if (event.key === 'Escape') {
    event.preventDefault();
    closeTextInputModal(null);
  }
});

textInputModalEl.addEventListener('click', (event) => {
  if (event.target === textInputModalEl) {
    closeTextInputModal(null);
  }
});

if (playlistCreateBtn) {
  playlistCreateBtn.addEventListener('click', async () => {
    try {
      const created = await createPlaylistFlow();
      if (created) {
        setStatus(`プレイリスト作成: ${created.name}`);
      }
    } catch (error) {
      safeAlert(`プレイリスト作成に失敗しました。\n${error.message}`);
    }
  });
}

if (playlistPickerCancelEl) {
  playlistPickerCancelEl.addEventListener('click', () => {
    closePlaylistPickerModal(null);
  });
}

if (playlistPickerModalEl) {
  playlistPickerModalEl.addEventListener('click', (event) => {
    if (event.target === playlistPickerModalEl) {
      closePlaylistPickerModal(null);
    }
  });
}

backBtn.addEventListener('click', async () => {
  if (!currentAudioDir) return;
  const parent = currentAudioDir.substring(0, currentAudioDir.lastIndexOf('/'));
  if (parent && parent !== baseAudioDir) {
    currentAudioDir = parent;
  } else {
    currentAudioDir = '';
  }
  selectedLocalIndex = -1;
  await refreshLocal();
  updateBackButtonVisibility();
  persistUiState();
});

document.querySelectorAll('.tab-btn').forEach((btn) => {
  btn.addEventListener('click', () => {
    switchTab(btn.dataset.tab);
  });
});

const clearCacheBtn = document.getElementById('clearCacheBtn');
const cacheStatus = document.getElementById('cacheStatus');

if (clearCacheBtn) {
  clearCacheBtn.addEventListener('click', async () => {
    cacheStatus.textContent = 'Clearing...';
    try {
      searchPageCache.clear();
      const res = await window.api.clearCache();
      if (res.ok) {
        cacheStatus.textContent = '初期化完了';
        setTimeout(() => { cacheStatus.textContent = ''; }, 3000);
      } else {
        cacheStatus.textContent = 'エラー: ' + res.error;
      }
    } catch (e) {
      cacheStatus.textContent = 'エラー: ' + e.message;
    }
  });
}

if (saveAudioFormatEl) {
  saveAudioFormatEl.addEventListener('change', () => {
    persistUiState();
  });
}

if (syncCopyDeviceIdBtn && syncMyDeviceIdEl) {
  syncCopyDeviceIdBtn.addEventListener('click', async () => {
    const deviceId = syncMyDeviceIdEl.value.trim();
    if (!deviceId) {
      safeAlert('デバイス ID を取得できていません。しばらく待ってから再試行してください。');
      return;
    }
    try {
      await window.api.writeClipboardText({ text: deviceId });
      if (syncConnectStatusEl) {
        syncConnectStatusEl.textContent = 'デバイス ID をコピーしました';
        setTimeout(() => {
          if (syncConnectStatusEl.textContent === 'デバイス ID をコピーしました') {
            syncConnectStatusEl.textContent = '';
          }
        }, 2500);
      }
    } catch (error) {
      safeAlert(`コピーに失敗しました。\n${error.message}`);
    }
  });
}

if (syncAddDeviceBtn && syncRemoteDeviceIdEl) {
  syncAddDeviceBtn.addEventListener('click', async () => {
    const deviceID = syncRemoteDeviceIdEl.value.trim();
    if (!deviceID) {
      safeAlert('相手のデバイス ID を入力してください。');
      return;
    }
    if (syncConnectStatusEl) {
      syncConnectStatusEl.textContent = '接続を設定しています...';
    }
    try {
      const res = await window.api.syncthingAddDevice({ deviceID });
      if (!res.success) {
        throw new Error(res.error || '接続に失敗しました');
      }
      if (res.info) {
        applySyncInfo({ ok: true, ...res.info });
      } else {
        await refreshSyncInfo();
      }
      syncRemoteDeviceIdEl.value = '';
      if (syncConnectStatusEl) {
        syncConnectStatusEl.textContent = 'デバイスを登録しました。相手の端末でもこの端末の ID を登録してください。';
      }
    } catch (error) {
      if (syncConnectStatusEl) {
        syncConnectStatusEl.textContent = '';
      }
      safeAlert(`デバイス接続に失敗しました。\n${error.message}`);
    }
  });
}

boot();
