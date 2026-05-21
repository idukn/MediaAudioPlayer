# インストール手順（macOS / Windows / Android）

## ライブラリ（保存先）について

すべてのプラットフォームで **アプリ専用のライブラリフォルダ** に音源と `playlists.json` を保存します。外部ディスク上のフォルダを直接再生対象にはしません。

| プラットフォーム | ライブラリの場所 |
|------------------|------------------|
| macOS | `~/Library/Application Support/media-audio-finder/library` |
| Windows | `%APPDATA%\media-audio-finder\library` |
| Android | アプリ専用外部ストレージ `/Android/data/local.media.audio.finder/files/library` |

他の PC やフォルダのファイルを使う場合は、このライブラリへコピーしてください。

---

## 共通の依存関係

- **yt-dlp**（検索・ダウンロード）
- **ffmpeg**（音声変換・試聴）

### Windows

[yt-dlp](https://github.com/yt-dlp/yt-dlp/releases) と [ffmpeg](https://www.gyan.dev/ffmpeg/builds/) をインストールし、PATH に追加してください。

### Android

APK 単体では yt-dlp / ffmpeg が同梱されません。次のいずれかが必要です。

- [Termux](https://termux.dev/) 等で `pkg install yt-dlp ffmpeg` を実行し、PATH が通る状態にする
- 将来の APK 同梱版（予定）

**試聴ストリーム** と **Syncthing 内蔵** は Android プラグインで有効です（初回起動時に Syncthing バイナリをダウンロードします）。試聴・検索・ダウンロードには引き続き yt-dlp / ffmpeg が必要です。

---

## macOS（開発 / ローカル実行）

```bash
cd electron
npm install
npm start
```

ビルド:

```bash
cd electron
npm install
npm run build:mac
# 出力: electron/dist/mac*/Media Audio Finder.app
```

---

## Windows

```bash
cd electron
npm install
npm run build:win
```

- インストーラ: `electron/dist/Media Audio Finder-*-win-x64.exe`
- ポータブル: `electron/dist/Media Audio Finder-*-win-x64.exe`（portable 設定）

---

## Android（APK）

### 1. 依存関係

- Node.js 20+
- Android Studio（SDK / JDK 17）
- `yt-dlp`（端末または Termux、上記参照）

### 2. セットアップ

```bash
cd mobile
npm install
npx cap add android   # 初回のみ
npm run sync:www
npm run cap:sync
```

### 3. ビルドと端末へのインストール（推奨）

USB デバッグ有効な端末、またはエミュレータを接続して:

```bash
./build_and_install_android_app.sh
```

初回は `npx cap add android` まで自動実行します。複数端末がある場合は `ANDROID_SERIAL=... ./build_and_install_android_app.sh` で指定できます。

### 4. Android Studio でビルド（任意）

```bash
cd mobile
npm run android:open
```

Android Studio で **Build > Build Bundle(s) / APK(s) > Build APK(s)** を実行します。

または:

```bash
cd mobile/android
./gradlew assembleDebug
# APK: mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

### 5. UI の更新

`electron/src/renderer` を編集したあと:

```bash
cd mobile
npm run sync:www
npm run cap:sync
```

---

## トラブルシューティング

### Windows で yt-dlp / ffmpeg が見つからない

PATH に追加後、アプリを再起動してください。

### Android で検索・ダウンロードが失敗する

端末で `yt-dlp --version` が動くか確認してください。動かない場合は Termux 等でインストールしてください。

### プレイリストが表示されない

ライブラリ内の `playlists.json` を確認してください。旧 macOS 版の `/Volumes/...` からは mac 起動時のみ自動移行します。

### Android で試聴が失敗する

Termux 等で `yt-dlp` と `ffmpeg` をインストールし、アプリを再起動してください。試聴は `127.0.0.1` 上のローカル HTTP サーバー経由でストリームします。

### Android で Syncthing が「準備中」のまま

初回は GitHub からバイナリ取得のため Wi‑Fi 環境を推奨します。数分待って同期パネルを更新してください。一部端末では Linux バイナリの実行が制限される場合があります。
