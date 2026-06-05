package local.media.audio.finder;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class MediaTools {

    private static final String TAG = "MediaTools";

    static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    static final String TERMUX_BIN = TERMUX_PREFIX + "/bin";
    static final String TERMUX_LIB = TERMUX_PREFIX + "/lib";

    private MediaTools() {
    }

    static String resolveFfmpeg(Context context) {
        String bundled = NativeBinaryResolver.resolveBundledFfmpeg(context);
        if (bundled != null && canRun(bundled, context, false)) {
            return bundled;
        }
        String termux = TERMUX_BIN + "/ffmpeg";
        if (new File(termux).exists() && canRun(termux, context, true)) {
            return termux;
        }
        if (bundled != null) {
            return bundled;
        }
        return termux;
    }

    static String resolveYtdlpPath() {
        File termux = new File(TERMUX_BIN + "/yt-dlp");
        if (termux.isFile() && termux.length() > 0) {
            return termux.getAbsolutePath();
        }
        return null;
    }

    /** 他アプリから直接 exec できる場合のみ（多くの端末では null 相当）。 */
    static String resolveYtdlp(Context context) {
        String termux = resolveYtdlpPath();
        if (termux != null && canRun(termux, context, true)) {
            return termux;
        }
        return null;
    }

    static boolean isYtdlpInstalledInTermux() {
        return resolveYtdlpPath() != null;
    }

    static boolean hasWorkingFfmpeg(Context context) {
        String ffmpeg = resolveFfmpeg(context);
        return ffmpeg != null && canRun(ffmpeg, context, ffmpeg.startsWith(TERMUX_PREFIX));
    }

    static boolean hasWorkingYtdlp(Context context) {
        return resolveYtdlp(context) != null;
    }

    static boolean canRun(String path, Context context, boolean termux) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        File file = new File(path);
        if (!file.isFile() || file.length() == 0) {
            return false;
        }
        File libDir = NativeBinaryResolver.getNativeLibDir(context);
        if (!termux && libDir != null && path.startsWith(libDir.getAbsolutePath())) {
            return probeVersion(path, context, false);
        }
        if (!file.canExecute()) {
            return false;
        }
        return probeVersion(path, context, termux);
    }

    static void applyProcessEnvironment(ProcessBuilder builder, Context context, String executablePath) {
        boolean termux = executablePath != null && executablePath.startsWith(TERMUX_PREFIX);
        if (termux) {
            Map<String, String> env = builder.environment();
            env.put("HOME", TERMUX_PREFIX + "/home");
            env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
            env.put("PREFIX", TERMUX_PREFIX);
            env.put("PATH", TERMUX_BIN);
            env.put("LD_LIBRARY_PATH", TERMUX_LIB);
            return;
        }
        NativeBinaryResolver.applyNativeLibPath(builder, context);
    }

    private static boolean probeVersion(String path, Context context, boolean termux) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(path, "-version");
            builder.redirectErrorStream(true);
            applyProcessEnvironment(builder, context, path);
            process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    if (output.length() > 4096) {
                        break;
                    }
                }
            }
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                Log.w(TAG, "probe timeout: " + path);
                return false;
            }
            int exit = process.exitValue();
            String text = output.toString().trim();
            if (exit != 0 || text.isEmpty()) {
                Log.w(TAG, "probe failed exit=" + exit + " path=" + path + " out=" + text);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "probe failed for " + path + ": " + e.getMessage());
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
