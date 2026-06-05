package local.media.audio.finder;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class NativeBinaryResolver {

    private NativeBinaryResolver() {
    }

    static File getNativeLibDir(Context context) {
        ApplicationInfo info = context.getApplicationInfo();
        if (info == null || info.nativeLibraryDir == null) {
            return null;
        }
        return new File(info.nativeLibraryDir);
    }

    static String resolveBundledFfmpeg(Context context) {
        File libDir = getNativeLibDir(context);
        if (libDir == null) {
            return null;
        }
        File ffmpeg = new File(libDir, "libffmpeg.so");
        if (ffmpeg.isFile() && ffmpeg.length() > 0) {
            return ffmpeg.getAbsolutePath();
        }
        return null;
    }

    static String resolveFfmpeg(Context context) {
        return MediaTools.resolveFfmpeg(context);
    }

    static boolean isUsableExecutable(String path, Context context) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        boolean termux = path.startsWith(MediaTools.TERMUX_PREFIX);
        return MediaTools.canRun(path, context, termux);
    }

    static void applyNativeLibPath(ProcessBuilder builder, Context context) {
        File libDir = getNativeLibDir(context);
        if (libDir == null) {
            return;
        }
        Map<String, String> env = builder.environment();
        String existing = env.get("LD_LIBRARY_PATH");
        String libPath = libDir.getAbsolutePath();
        if (existing != null && !existing.isEmpty()) {
            libPath = libPath + ":" + existing;
        }
        env.put("LD_LIBRARY_PATH", libPath);
    }

    static List<String> ffmpegCommand(Context context, String ffmpegPath, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.addAll(args);
        return command;
    }
}
