package local.media.audio.finder;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class ExecutableResolver {

    private ExecutableResolver() {
    }

    static String resolve(Context context, String name) {
        List<String> candidates = new ArrayList<>();
        candidates.add("/data/data/com.termux/files/usr/bin/" + name);
        File filesDir = context.getFilesDir();
        if (filesDir != null) {
            candidates.add(new File(filesDir, "bin/" + name).getAbsolutePath());
        }
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            candidates.add(new File(external, "bin/" + name).getAbsolutePath());
        }
        candidates.add("/system/bin/" + name);
        candidates.add(name);

        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.exists() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return name;
    }
}
