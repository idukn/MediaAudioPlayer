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

**試聴ストリーム** は Android プラグインで有効です（APK 同梱 ffmpeg + Invidious API。**ネット接続必須**）。**Syncthing 内蔵** は APK ビルド時にバイナリ同梱が必要です（下記スクリプト）。試聴・検索・ダウンロードの検索/ダウンロードには Android では別途 yt-dlp が必要な場合があります。

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
- Android Studio（SDK）/ JDK 21（`brew install openjdk@21` でも可）
- `yt-dlp`（端末または Termux、上記参照）

### 2. セットアップ

```bash
cd mobile
npm install
npx cap add android   # 初回のみ
npm run sync:www
npm run cap:sync
```

### 3. ネイティブバイナリ（Android 内蔵機能を使う場合）

```bash
./scripts/build-syncthing-android.sh
./scripts/build-media-tools-android.sh
```

- Syncthing: `jniLibs/arm64-v8a/libsyncthing.so`（約 27MB）
- ffmpeg: `libffmpeg.so` + Termux 依存ライブラリ一式（`build-media-tools-android.sh` で patchelf 改名して同梱、APK は約 60MB+）。試聴・WAV 再生に使用。Termux の `ffmpeg` は別アプリから実行できないため APK 同梱が必須

`./build_and_install_android_app.sh` は初回に上記を自動実行します。

### 4. ビルドと端末へのインストール（推奨）

USB デバッグ有効な端末、またはエミュレータを接続して:

```bash
./build_and_install_android_app.sh
```

初回は `npx cap add android` まで自動実行します。複数端末がある場合は `ANDROID_SERIAL=... ./build_and_install_android_app.sh` で指定できます。

### 5. Android Studio でビルド（任意）

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

### Android で検索・ダウンロードが失敗する

端末で `yt-dlp --version` が動くか確認してください。動かない場合は Termux 等でインストールしてください。

### プレイリストが表示されない

ライブラリ内の `playlists.json` を確認してください。旧 macOS 版の `/Volumes/...` からは mac 起動時のみ自動移行します。

### Android で試聴が失敗する

**YouTube 試聴** は Mac 版と同様に **yt-dlp → ffmpeg のリアルタイムパイプ** で配信します（チャンクストリーム）。Termux に `yt-dlp` と `ffmpeg` があると Mac に最も近い動作になります。Termux がない場合は URL 取得 + ffmpeg パイプにフォールバックします。

```bash
# Termux（検索・ダウンロード・試聴すべて）
pkg update && pkg install yt-dlp ffmpeg python

# APK 再ビルド（同梱 ffmpeg）
./scripts/build-media-tools-android.sh
./build_and_install_android_app.sh
```

**注意**: Syncthing 用に Termux を入れているだけでは yt-dlp は使えません。Termux 内で上記 `pkg install` が必要です。

**Termux に yt-dlp を入れているのに試聴だけ失敗する場合**（よくある）:

Android では **他アプリから Termux 内の `/data/data/com.termux/.../yt-dlp` を直接実行できません**（インストール済みでもアプリは見えない/実行不可）。次を設定してください。

1. **Termux** で `pkg install yt-dlp ffmpeg`（済みなら不要）
2. **Termux** で外部アプリ連携を有効化:
   ```bash
   mkdir -p ~/.termux
   echo 'allow-external-apps=true' >> ~/.termux/termux.properties
   ```
   Termux アプリを完全終了して再起動
3. **Termux** にストレージ権限: `termux-setup-storage` を実行
4. **権限の付与**（公式 Termux の場合のみ）:
   - 使うのは **F-Droid の公式 Termux** (`com.termux`) であること。Play 版・別名アプリでは `RUN_COMMAND` 権限が存在せず、**設定にも adb にも出ません**。
   - YouTube 試聴を開き「Termux でコマンドを実行」ダイアログが出たら許可
   - `adb shell pm grant` が **`Unknown permission`** になる場合 → **Termux が非公式か未インストール**です。grant は使えません。

**YouTube 試聴の本体**は Termux が無くても **Piped / Invidious の公開 API** で動作します（アプリがインスタンス一覧を自動取得）。Termux の yt-dlp は API が全部落ちているときの予備です。

### Google Play 版 Termux（`versionName=googleplay.*`）について

`adb shell dumpsys package com.termux | grep versionName` が **`googleplay.2026.xx.xx`** の場合、それは **Google Play 版 Termux** です。

このビルドは **RUN_COMMAND をシステムに登録しない** ため、

- 設定に「Termux でコマンドを実行」が**出ない**
- `adb shell pm grant ... RUN_COMMAND` が **Unknown permission**
- Termux 内の `yt-dlp` は動いても **本アプリからは使えない**

**対処（Termux 連携を使いたい場合のみ）:**

1. Google Play 版 Termux を**アンインストール**
2. [F-Droid の Termux](https://f-droid.org/packages/com.termux/) をインストール（`versionName` が `0.118.x` など **googleplay でない**こと）
3. Termux 内で `pkg install yt-dlp ffmpeg`、上記 `allow-external-apps` 設定

**YouTube 試聴**は F-Droid 版が無くても **Invidious API** で動作します（Termux 不要）。

### RUN_COMMAND が Unknown permission のとき（com.termux はある）

**アプリ側から権限を「新規登録」することはできません。** Termux APK がインストールされたときに初めてシステムに登録されます。

端末で確認:

```bash
adb shell pm list packages | grep termux
adb shell dumpsys package com.termux | grep versionName
adb shell pm list permissions | grep -i run_command
```

| 結果 | 対処 |
|------|------|
| `grep run_command` が何も出ない | Termux が古い／壊れている → **F-Droid で公式 Termux を再インストール**（0.118 以降） |
| 権限は出るが grant できない | Media Audio Finder で YouTube 試聴を1回開き、ダイアログで許可 |
| それでも不可 | **Termux 連携は諦めて OK**（YouTube は Invidious API のみで動作） |

Termux 再インストール後:

```bash
# Termux 内
pkg update && pkg upgrade
pkg install yt-dlp ffmpeg
mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties
# Termux を完全終了→再起動、termux-setup-storage

# PC（権限が登録された後だけ成功する）
adb shell pm grant local.media.audio.finder com.termux.permission.RUN_COMMAND
```

**恒久対策（開発側）**: APK に yt-dlp を同梱すれば Termux 不要になります（ffmpeg 同梱と同様）。現状は Invidious + 同梱 ffmpeg が主経路です。

アプリ内の開発者向け: `await window.api.getMediaToolsDiagnostics()` で `termuxRunCommandPermission` と `adbGrantCommand` を確認できます。

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
