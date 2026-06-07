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

**Linux 開発環境（Debian Terminal）** 内のメディアサーバー（`yt-dlp` + `ffmpeg` ライブ pipe）が必要です。APK 単体では YouTube 試聴・検索・ダウンロードは動きません。

1. 開発者向けオプション → **Linux 開発環境** を有効化
2. Terminal アプリで Debian をインストール
3. リポジトリを VM にコピーし `./scripts/setup-debian-media-server.sh` を実行
4. Terminal 設定で **ポート 8765** の転送を許可

**Syncthing 同期** は APK 内蔵（ビルド時に `libsyncthing.so` 同梱）。

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

### 1. 依存関係（PC 側）

- Node.js 20+
- Android Studio（SDK）/ JDK 21（`brew install openjdk@21` でも可）
- arm64 実機（USB デバッグ ON）

### 2. 依存関係（端末側・Debian VM）

- Android 15+ / 16 の **Linux 開発環境**（Terminal アプリ）
- VM 内: `nodejs`, `yt-dlp`, `ffmpeg`（`setup-debian-media-server.sh` が自動インストール）
- **ポート 8765** を Android ホストへ転送（Terminal アプリ設定）

### 3. Debian メディアサーバーのセットアップ（端末・1 回）

**注意:** `adb push` の先は **Android 本体**（`/sdcard` 等）です。`/home/droid` は Debian VM 内のパスなので `Read-only file system` になります。

#### PC からリポジトリを送る

**リポジトリ全体**の `adb push` は `dist/` 内のシンボリックリンク等で失敗します。必要ファイルだけ送るスクリプトを使ってください:

```bash
# Mac（USB デバッグ ON）
./scripts/push-debian-setup-to-android.sh
```

内部で `/storage/emulated/0/Download/yt_audio_app` に展開します（`/sdcard` は adb 上では `/storage/emulated/0` へのリンクです。Debian Terminal 内からは `/sdcard` が見えないことがあります）。

#### Debian Terminal 内で実行

Terminal アプリを開き、共有ストレージのマウントを確認してからセットアップします:

```bash
ls /mnt/shared
# 例: /mnt/shared/0/Download/yt_audio_app が見える

cd /mnt/shared/0/Download/yt_audio_app
chmod +x scripts/setup-debian-media-server.sh
./scripts/setup-debian-media-server.sh
```

`/mnt/shared/0/...` のパスが端末で異なる場合は `ls /mnt/shared` で Download フォルダを探してください。git が使えるなら VM 内で `git clone` でも構いません（`shared/media-server/` と `scripts/` が必要）。

確認:

```bash
curl http://127.0.0.1:8765/health
# Android Chrome からも http://127.0.0.1:8765/health が {"ok":true} なら OK
```

ライブラリパス（virtiofs）が端末と異なる場合は `LIBRARY_ROOT=... ./scripts/setup-debian-media-server.sh` で指定してください。デフォルト:

`/mnt/shared/0/Android/data/local.media.audio.finder/files/library`

#### メディアサーバーの更新（YouTube 試聴など）

`update-vm-media-server.sh` は **Mac のリポジトリにのみ** あります。VM 内に無い場合は、Mac から再プッシュしてください:

```bash
# Mac
./scripts/push-debian-setup-to-android.sh
```

Debian Terminal 内:

```bash
cd /mnt/shared/0/Download/yt_audio_app   # ls /mnt/shared でパスを確認
./scripts/update-vm-media-server.sh
```

スクリプトがまだ無い場合は、次の 3 行だけでも更新できます:

```bash
REPO=/mnt/shared/0/Download/yt_audio_app   # パスは環境に合わせる
cp "$REPO/shared/media-server/index.js" "$HOME/media-audio-finder-server/index.js"
systemctl --user restart media-audio-finder && curl -sf http://127.0.0.1:8765/health
```

### 4. APK ビルド（PC）

```bash
cd mobile
npm install
npx cap add android   # 初回のみ
npm run sync:www
npm run cap:sync
```

ネイティブ（Syncthing のみ）:

```bash
./scripts/build-syncthing-android.sh
```

`./build_and_install_android_app.sh` は初回に Syncthing 同梱を自動実行します（ffmpeg 同梱は不要）。

### 5. ビルドと端末へのインストール（推奨）

```bash
./build_and_install_android_app.sh
```

### 6. Android Studio でビルド（任意）

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

### 6. UI の更新

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

### Android で検索・ダウンロード・試聴が失敗する

Debian VM 内のメディアサーバー（ポート **8765**）に接続できていません。

1. Terminal 内: `systemctl --user status media-audio-finder`
2. Terminal 内: `curl http://127.0.0.1:8765/health` → `ok: true`（**VM 内**の成功だけでは不十分）
3. Terminal アプリ **歯車** → リスニングポート **8765** を追加
4. **サーバーを再起動**（転送を再検知させる）:
   ```bash
   systemctl --user restart media-audio-finder
   ```
5. バインド時の **ポート転送ポップアップ** を許可
6. Mac から Android 側を確認:
   ```bash
   adb shell ss -ltn | grep 8765
   ```
   `127.0.0.1:8765` が `LISTEN` なら転送 OK
7. Android Chrome で `http://127.0.0.1:8765/health`（**https 不可**）を開く

**VM 内 curl は成功するのに Chrome だけ connection refused** になる典型原因は、Android ホスト側に 8765 の転送リスナーがまだ立っていないことです。Terminal（Debian VM）を起動したまま、上記 3〜6 を実施してください。

YouTube **試聴**は Mac 版と同様 **yt-dlp → ffmpeg のライブ pipe**（`/stream?url=`）です。キャッシュ待ちはありません。

アプリ内: `await window.api.getMediaToolsDiagnostics()` で接続状態を確認できます。

### プレイリストが表示されない

ライブラリ内の `playlists.json` を確認してください。旧 macOS 版の `/Volumes/...` からは mac 起動時のみ自動移行します。

### Android で Syncthing の localhost (127.0.0.1:8384) に接続できない

**よくある誤解:** Mac のブラウザで `http://localhost:8384` を開いても、表示されるのは **Mac 上の Syncthing** です。Android 端末内の Syncthing には届きません。

**アプリ内の同期パネル**（デバイス ID の表示・相手の登録）は、アプリが端末内の `127.0.0.1:8384` に直接 API 接続します。ここが失敗する場合:

1. **arm64 実機**で `./build_and_install_android_app.sh` 済みか確認（x86 エミュレータは未対応）
2. 同期パネルで「起動中」が消えるまで 30〜60 秒待つ
3. `adb logcat | grep Syncthing` で `API base URL: http://127.0.0.1:8384` とデーモン起動ログを確認

**端末の Syncthing Web UI を Mac から見る場合**（上級者向け）:

```bash
adb forward tcp:8384 tcp:8384
```

その後 Mac のブラウザで `http://127.0.0.1:8384` を開く（`adb forward` 実行中のみ）。

### Android で Syncthing が `unsupported verneed` / デーモンが起動しない

logcat に `CANNOT LINK EXECUTABLE ... libsyncthing.so: unsupported verneed` と出る場合、Termux 版の ELF バージョン情報が Android と合いません。**必ず再ビルド**してください（`llvm-objcopy` 要: `brew install llvm`）。

```bash
./scripts/build-syncthing-android.sh
./build_and_install_android_app.sh
```

成功時はビルドログに `Android linker finalize OK: libsyncthing.so` と表示されます。

### Android で Syncthing が permission denied (error=13) / デバイス ID が空

Android 10 以降は `files/` からバイナリを実行できません。Syncthing は **`jniLibs/arm64-v8a/libsyncthing.so`** として同梱し、`nativeLibraryDir` から起動します。必ず次を実行してから **再インストール** してください。

```bash
./scripts/build-syncthing-android.sh
./build_and_install_android_app.sh
```

（x86 エミュレータは未対応。arm64 実機または arm64 AVD を使用してください。）

### Android / macOS でデバイス ID が空のまま

Syncthing の起動完了まで数十秒かかることがあります。同期パネルに「起動中」と出ている間は待ち、コピーは自動で再試行されます。macOS で初回 DL 後に起動しない場合はアプリを再起動してください。

### Gradle: Unable to locate a Java Runtime / languageVersion=21

Capacitor 7 は **JDK 21** が必要です。

```bash
brew install openjdk@21
./build_and_install_android_app.sh
```

`JAVA_HOME` はスクリプトが `openjdk@21` を優先して自動検出します。

### Gradle: SDK location not found

`adb` だけではビルドできません。次の **どちらか** で SDK を入れてください。

**CLI のみ（Android Studio 不要）:**

```bash
./scripts/setup-android-sdk.sh
./build_and_install_android_app.sh
```

`setup-android-sdk.sh` は Homebrew の `android-commandlinetools` と SDK 35 / build-tools を `~/Library/Android/sdk` に入れます。

**Android Studio を使う場合:**

Studio の SDK Manager で SDK を入れたあと:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
./build_and_install_android_app.sh
```
