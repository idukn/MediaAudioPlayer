package local.media.audio.finder;

import android.content.Context;

import java.util.Map;

final class MediaTools {

    private MediaTools() {
    }

    static void applyProcessEnvironment(ProcessBuilder builder, Context context, String executablePath) {
        NativeBinaryResolver.applyNativeLibPath(builder, context);
    }
}
