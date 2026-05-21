# yt_audio_app

YouTube / ニコニコ動画の検索、音源保存、ローカル再生を1つで行う Node.js + Electron デスクトップアプリです。

## Features
- YouTube / ニコニコ動画 / 両方からキーワード検索
- 気に入った動画の音源を `yt-dlp` で保存
- 音源・プレイリストはアプリのローカルライブラリに保存（`userData/library`）
- 検索結果の右クリックから「ライブラリに保存」または「保存先を指定して保存」を選択可能
- 保存済み音源をアプリ内で再生（Play / Pause / Stop）
- 外部フォルダのファイルを再生する場合はライブラリへコピー

## Platforms

| Platform | Status |
|----------|--------|
| macOS | Electron（フル機能） |
| Windows | Electron（フル機能） |
| Android | Capacitor（同一 UI、ネイティブ API） |

詳細は [docs/INSTALL.md](docs/INSTALL.md) を参照してください。

## Requirements
- Node.js 20+
- `ffmpeg` (yt-dlpの音声変換に必要)
- `yt-dlp`

## Library path

音源とプレイリストは各 OS の **アプリ専用ライブラリ** に保存されます（外部ディスクの任意パスは再生対象にしません）。

- macOS: `~/Library/Application Support/media-audio-finder/library`
- Windows: `%APPDATA%\media-audio-finder\library`
- Android: アプリ外部ストレージ `.../files/library`

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

## Build desktop (macOS / Windows)

```bash
cd electron
npm install
npm run build:mac    # macOS
npm run build:win      # Windows installer + portable
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
