package local.media.audio.finder;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Termux にインストール済みの yt-dlp を、RUN_COMMAND Intent 経由で実行する。
 * 別アプリから /data/data/com.termux/... のバイナリは直接 exec できないため。
 */
final class TermuxCommandRunner {

    private static final String TAG = "TermuxRunner";

    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";

    private static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_RESULT_DIRECTORY = "com.termux.RUN_COMMAND_RESULT_DIRECTORY";
    private static final String EXTRA_RESULT_SINGLE_FILE = "com.termux.RUN_COMMAND_RESULT_SINGLE_FILE";
    private static final String EXTRA_RESULT_FILES_SUFFIX = "com.termux.RUN_COMMAND_RESULT_FILES_SUFFIX";

    private static final String RESULT_ERR_BASENAME = "err";
    private static final String RESULT_EXIT_CODE_BASENAME = "exit_code";
    private static final String RESULT_STDERR_BASENAME = "stderr";

    private TermuxCommandRunner() {
    }

    static boolean isTermuxInstalled(Context context) {
        return TermuxDetector.findInstalledPackage(context) != null;
    }

    static boolean hasRunCommandPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return context.checkSelfPermission("com.termux.permission.RUN_COMMAND")
            == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isYtdlpInstalledInTermux() {
        File ytdlp = new File(MediaTools.TERMUX_BIN + "/yt-dlp");
        return ytdlp.isFile() && ytdlp.length() > 0;
    }

    static String getSetupHint(Context context) {
        return TermuxDetector.getTermuxStatusMessage(context);
    }

    /**
     * yt-dlp で YouTube 音声を MP3 に抽出し dest にコピーする。
     */
    static void extractYoutubeAudioToFile(Context context, String pageUrl, File dest, long timeoutSec)
        throws Exception {
        if (!isTermuxInstalled(context)) {
            throw new IOException("Termux がインストールされていません");
        }
        if (!isYtdlpInstalledInTermux()) {
            throw new IOException("Termux に yt-dlp がありません。Termux で pkg install yt-dlp ffmpeg");
        }
        if (!TermuxDetector.isRunCommandPermissionDefined(context)) {
            throw new IOException(TermuxDetector.getTermuxStatusMessage(context));
        }
        if (!hasRunCommandPermission(context)) {
            throw new IOException(TermuxDetector.getTermuxStatusMessage(context));
        }

        File workBase = context.getExternalFilesDir("termux_run");
        if (workBase == null) {
            workBase = new File(context.getCacheDir(), "termux_run");
        }
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        File workDir = new File(workBase, sessionId);
        File resultDir = new File(workDir, "result");
        File outputDir = new File(workDir, "out");
        if (!outputDir.mkdirs() || !resultDir.mkdirs()) {
            throw new IOException("作業ディレクトリを作成できませんでした");
        }

        String outputTemplate = new File(outputDir, "preview.%(ext)s").getAbsolutePath();
        String[] args = new String[] {
            "-x",
            "--audio-format", "mp3",
            "-o", outputTemplate,
            "--no-playlist",
            "--no-warnings",
            "--no-progress",
            pageUrl,
        };

        Log.i(TAG, "RUN_COMMAND yt-dlp session=" + sessionId);
        int exitCode = runBackgroundCommand(
            context,
            MediaTools.TERMUX_BIN + "/yt-dlp",
            args,
            resultDir,
            sessionId,
            timeoutSec
        );
        if (exitCode != 0) {
            String stderr = readResultText(resultDir, RESULT_STDERR_BASENAME, sessionId);
            throw new IOException(
                "Termux yt-dlp が失敗しました (exit=" + exitCode + ")"
                    + (stderr.isEmpty() ? "" : ": " + stderr)
            );
        }

        File audio = findNewestAudio(outputDir);
        if (audio == null || !audio.isFile() || audio.length() < 512) {
            throw new IOException("Termux yt-dlp の出力ファイルが見つかりませんでした");
        }
        copyFile(audio, dest);
        Log.i(TAG, "Termux yt-dlp ok: " + dest.length() + " bytes");
        deleteRecursive(workDir);
    }

    private static int runBackgroundCommand(
        Context context,
        String executable,
        String[] arguments,
        File resultDir,
        String resultSuffix,
        long timeoutSec
    ) throws Exception {
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra(EXTRA_COMMAND_PATH, executable);
        intent.putExtra(EXTRA_ARGUMENTS, arguments);
        intent.putExtra(EXTRA_WORKDIR, MediaTools.TERMUX_PREFIX + "/home");
        intent.putExtra(EXTRA_BACKGROUND, true);
        intent.putExtra(EXTRA_RESULT_DIRECTORY, resultDir.getAbsolutePath());
        intent.putExtra(EXTRA_RESULT_SINGLE_FILE, false);
        intent.putExtra(EXTRA_RESULT_FILES_SUFFIX, resultSuffix);

        try {
            context.startService(intent);
        } catch (Exception e) {
            throw new IOException("Termux RUN_COMMAND を開始できません: " + e.getMessage()
                + " （allow-external-apps=true を確認）");
        }

        File errMarker = resultFile(resultDir, RESULT_ERR_BASENAME, resultSuffix);
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (errMarker.isFile()) {
                break;
            }
            Thread.sleep(500);
        }
        if (!errMarker.isFile()) {
            throw new IOException("Termux コマンドがタイムアウトしました (" + timeoutSec + "s)");
        }

        File exitFile = resultFile(resultDir, RESULT_EXIT_CODE_BASENAME, resultSuffix);
        if (!exitFile.isFile()) {
            return -1;
        }
        String raw = readFileUtf8(exitFile).trim();
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static File resultFile(File resultDir, String basename, String suffix) {
        return new File(resultDir, basename + " " + suffix);
    }

    private static String readResultText(File resultDir, String basename, String suffix) {
        File file = resultFile(resultDir, basename, suffix);
        if (!file.isFile()) {
            return "";
        }
        return readFileUtf8(file).trim();
    }

    private static String readFileUtf8(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 65536)];
            int n = in.read(data);
            if (n <= 0) {
                return "";
            }
            return new String(data, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static File findNewestAudio(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        File best = null;
        long bestTime = 0;
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".mp3") && !lower.endsWith(".m4a") && !lower.endsWith(".opus")
                && !lower.endsWith(".webm") && !lower.endsWith(".ogg")) {
                continue;
            }
            if (file.lastModified() >= bestTime) {
                bestTime = file.lastModified();
                best = file;
            }
        }
        return best;
    }

    private static void copyFile(File source, File dest) throws IOException {
        if (dest.exists() && !dest.delete()) {
            throw new IOException("出力先を上書きできません");
        }
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
