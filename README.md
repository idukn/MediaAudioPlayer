# yt_audio_app

YouTube / ニコニコ動画の検索、音源保存、ローカル再生を1つで行う Node.js + Electron デスクトップアプリです。

## Features
- YouTube / ニコニコ動画 / 両方からキーワード検索
- 気に入った動画の音源を `yt-dlp` で保存
- 保存先はデフォルトで `/Volumes/2TB_WINMAC/reference`
- 保存済み音源をアプリ内で再生（Play / Pause / Stop）
- 保存先の書き込み権限チェックとフォルダ選択

## Requirements
- macOS
- Node.js 20+
- `ffmpeg` (yt-dlpの音声変換に必要)
- `yt-dlp`

ffmpeg は導入済み前提です。

## Setup
```bash
cd /Users/idukn/Program/yt_audio_app
cd electron
npm install
```

## Run
```bash
cd /Users/idukn/Program/yt_audio_app
cd electron
npm start
```

## Build as .app (macOS)
ビルドして `/Applications` へ自動配置します。

```bash
cd /Users/idukn/Program/yt_audio_app
chmod +x build_and_install_app.sh
./build_and_install_app.sh
```

配置先:
- `/Applications/Media Audio Finder.app`

## Notes
- 検索結果が空の場合、入力キーワードやソース選択を変えて再試行してください。
- ニコニコ動画検索は `yt-dlp` の対応状況に依存します。
- 保存ファイル名は動画タイトル由来です。
- 依存パッケージは `electron/package.json` で管理します。
- `.app` は `electron-builder` で作成します。
