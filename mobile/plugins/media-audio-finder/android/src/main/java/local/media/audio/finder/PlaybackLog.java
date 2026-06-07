package local.media.audio.finder;

import android.util.Log;

import org.json.JSONObject;

final class PlaybackLog {
    static final String TAG = "MediaAudioFinder";

    private PlaybackLog() {
    }

    static void info(String event, JSONObject fields) {
        Log.i(TAG, format(event, fields));
    }

    static void warn(String event, JSONObject fields) {
        Log.w(TAG, format(event, fields));
    }

    static void error(String event, JSONObject fields, Throwable error) {
        Log.e(TAG, format(event, fields), error);
    }

    static void error(String event, JSONObject fields) {
        Log.e(TAG, format(event, fields));
    }

    private static String format(String event, JSONObject fields) {
        if (fields == null || fields.length() == 0) {
            return event;
        }
        return event + " " + fields;
    }

    static JSONObject fields() {
        return new JSONObject();
    }

    static JSONObject with(String key, Object value) {
        JSONObject json = new JSONObject();
        try {
            json.put(key, value);
        } catch (Exception ignored) {
            // ignore
        }
        return json;
    }

    static JSONObject put(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (Exception ignored) {
            // ignore
        }
        return json;
    }
}
