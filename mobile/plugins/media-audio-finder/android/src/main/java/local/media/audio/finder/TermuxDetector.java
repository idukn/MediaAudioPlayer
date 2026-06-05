package local.media.audio.finder;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;

import java.util.Locale;

final class TermuxDetector {

    private static final String RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";

    /** RUN_COMMAND が安定している目安（F-Droid 0.118.0 以降） */
    private static final long MIN_TERMUX_VERSION_CODE = 118;

    private static final String[] TERMUX_PACKAGES = {
        "com.termux",
        "com.termux.googleplay",
    };

    private TermuxDetector() {
    }

    static String getTermuxVersionName(Context context) {
        String pkg = findInstalledPackage(context);
        if (pkg == null) {
            return "";
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
            return info.versionName != null ? info.versionName : "";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    static long getTermuxVersionCode(Context context) {
        String pkg = findInstalledPackage(context);
        if (pkg == null) {
            return 0;
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
            return info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static boolean isGooglePlayBuild(Context context) {
        String ver = getTermuxVersionName(context).toLowerCase(Locale.ROOT);
        return ver.startsWith("googleplay");
    }

    static boolean isTermuxVersionLikelySupported(Context context) {
        if (isGooglePlayBuild(context)) {
            return false;
        }
        long code = getTermuxVersionCode(context);
        return code >= MIN_TERMUX_VERSION_CODE;
    }

    static String findInstalledPackage(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : TERMUX_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    static boolean isRunCommandPermissionDefined(Context context) {
        try {
            PermissionInfo info = context.getPackageManager().getPermissionInfo(
                RUN_COMMAND_PERMISSION,
                0
            );
            return info != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static String getTermuxStatusMessage(Context context) {
        String pkg = findInstalledPackage(context);
        if (pkg == null) {
            return "Termux が未インストールです。F-Droid の公式 Termux (com.termux) を入れてください。"
                + " Play 版・別フォークでは RUN_COMMAND 権限が無い場合があります。";
        }
        if (isGooglePlayBuild(context)) {
            return "インストール中の Termux は Google Play 版（"
                + getTermuxVersionName(context)
                + "）です。このビルドは RUN_COMMAND をシステムに登録しないため、"
                + " 他アプリ（本アプリ含む）から Termux の yt-dlp は使えません。\n"
                + "対処: Play 版をアンインストールし、F-Droid の公式 Termux（versionName が googleplay でないもの）を入れ直す。\n"
                + "YouTube 試聴は Termux 無しでも Invidious API + 同梱 ffmpeg で動作します。";
        }
        if (!isRunCommandPermissionDefined(context)) {
            String ver = getTermuxVersionName(context);
            long code = getTermuxVersionCode(context);
            return "com.termux はありますが RUN_COMMAND 権限がシステムにありません"
                + (ver.isEmpty() ? "" : "（Termux " + ver + " / " + code + "）") + "。\n"
                + "1. Termux 内: pkg update && pkg upgrade\n"
                + "2. 改善しない場合: F-Droid の公式 Termux を再インストール（0.118 以降推奨）\n"
                + "3. ~/.termux/termux.properties に allow-external-apps=true\n"
                + "4. 再インストール後: adb shell pm grant "
                + context.getPackageName() + " com.termux.permission.RUN_COMMAND\n"
                + "YouTube 試聴自体は Termux 無しでも Invidious API で動作します。";
        }
        if (!TermuxCommandRunner.hasRunCommandPermission(context)) {
            return "Termux は入っています。YouTube 試聴を一度開き「Termux でコマンドを実行」を許可するか、"
                + " ~/.termux/termux.properties に allow-external-apps=true を設定してください。";
        }
        return "Termux RUN_COMMAND は利用可能です。";
    }
}
