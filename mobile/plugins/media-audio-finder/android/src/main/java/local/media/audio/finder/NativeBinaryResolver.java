package local.media.audio.finder;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
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
}
