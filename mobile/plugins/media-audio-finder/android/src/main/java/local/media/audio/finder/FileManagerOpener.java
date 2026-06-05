package local.media.audio.finder;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Android 向けにアプリ内ライブラリフォルダを外部ファイルマネージャで開く。
 */
final class FileManagerOpener {

    private static final String MIME_DIR = "vnd.android.document/directory";
    private static final String MIME_RESOURCE_FOLDER = "resource/folder";

    private FileManagerOpener() {
    }

    static boolean openFolder(Context context, File folder) {
        if (folder == null || !folder.isDirectory()) {
            return false;
        }
        String authority = context.getPackageName() + ".fileprovider";
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(context, authority, folder);
        } catch (IllegalArgumentException e) {
            return false;
        }

        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_ACTIVITY_NEW_TASK;

        List<Intent> candidates = Arrays.asList(
            viewIntent(uri, MIME_DIR, flags),
            viewIntent(uri, MIME_RESOURCE_FOLDER, flags),
            viewIntent(uri, null, flags)
        );

        for (Intent intent : candidates) {
            if (intent.resolveActivity(context.getPackageManager()) == null) {
                continue;
            }
            try {
                Intent chooser = Intent.createChooser(intent, "フォルダを開く");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(chooser);
                return true;
            } catch (ActivityNotFoundException ignored) {
                // try next
            }
        }
        return false;
    }

    static void copyPathAndNotify(Context context, String path) {
        ClipboardManager clipboard =
            (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("libraryPath", path));
        }
        String message = "ファイルアプリで開けないため、パスをコピーしました。\n"
            + "Files 等で Android/data/…/files/library を開いてください。\n"
            + path;
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    private static Intent viewIntent(Uri uri, String mimeType, int flags) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(flags);
        if (mimeType != null && !mimeType.isEmpty()) {
            intent.setDataAndType(uri, mimeType);
        } else {
            intent.setData(uri);
        }
        return intent;
    }
}
