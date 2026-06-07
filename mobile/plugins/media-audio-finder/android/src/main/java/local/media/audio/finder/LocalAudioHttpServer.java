package local.media.audio.finder;

import android.content.Context;
import android.util.Log;

import fi.iki.elonen.NanoHTTPD;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Serves library audio files over HTTP on 127.0.0.1 with Range support.
 * WebView {@code <audio>} handles this more reliably than {@code convertFileSrc} for many formats.
 */
final class LocalAudioHttpServer extends NanoHTTPD {
    private static final String TAG = "LocalAudioHttpServer";
    static final int DEFAULT_PORT = 8767;

    private static LocalAudioHttpServer instance;

    private final File libraryRoot;
    private final int boundPort;

    private LocalAudioHttpServer(File libraryRoot, int port) throws IOException {
        super("127.0.0.1", port);
        this.libraryRoot = libraryRoot.getCanonicalFile();
        start(SOCKET_READ_TIMEOUT, false);
        this.boundPort = getListeningPort();
        Log.i(TAG, "Listening on http://127.0.0.1:" + boundPort + " library=" + this.libraryRoot);
    }

    static synchronized LocalAudioHttpServer ensureStarted(Context context, File libraryRoot) throws IOException {
        if (instance != null) {
            return instance;
        }
        IOException lastError = null;
        for (int port = DEFAULT_PORT; port < DEFAULT_PORT + 8; port++) {
            try {
                instance = new LocalAudioHttpServer(libraryRoot, port);
                return instance;
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new IOException("Failed to bind local audio server");
    }

    String buildAudioUrl(String absolutePath) throws IOException {
        File file = new File(absolutePath).getCanonicalFile();
        if (!file.isFile() || !isWithinLibrary(file)) {
            throw new IOException("File not in library: " + absolutePath);
        }
        return "http://127.0.0.1:" + boundPort
            + "/audio?path="
            + java.net.URLEncoder.encode(file.getAbsolutePath(), StandardCharsets.UTF_8.name());
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (!Method.GET.equals(session.getMethod())) {
                return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method Not Allowed");
            }
            if (!"/audio".equals(session.getUri())) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
            }
            Map<String, String> params = session.getParms();
            return serveAudioFile(params.get("path"), session.getHeaders());
        } catch (IOException e) {
            Log.w(TAG, "serve failed: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server Error");
        }
    }

    private Response serveAudioFile(String rawPath, Map<String, String> headers) throws IOException {
        File file = resolveRequestedFile(rawPath);
        if (file == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
        }

        long fileLength = file.length();
        String mime = mimeTypeForFile(file);
        long start = 0;
        long end = fileLength - 1;
        boolean partial = false;

        String rangeHeader = headers != null ? headers.get("range") : null;
        if (rangeHeader == null && headers != null) {
            rangeHeader = headers.get("Range");
        }
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String spec = rangeHeader.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                String startText = spec.substring(0, dash).trim();
                String endText = spec.substring(dash + 1).trim();
                if (!startText.isEmpty()) {
                    start = Long.parseLong(startText);
                }
                if (!endText.isEmpty()) {
                    end = Long.parseLong(endText);
                }
                if (end >= fileLength) {
                    end = fileLength - 1;
                }
                if (start <= end && start < fileLength) {
                    partial = true;
                }
            }
        }

        long contentLength = end - start + 1;
        FileInputStream input = new FileInputStream(file);
        if (start > 0) {
            long skipped = input.skip(start);
            if (skipped < start) {
                input.close();
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Seek failed");
            }
        }

        Response response = newFixedLengthResponse(
            partial ? Response.Status.PARTIAL_CONTENT : Response.Status.OK,
            mime,
            input,
            contentLength
        );
        response.addHeader("Accept-Ranges", "bytes");
        response.addHeader("Cache-Control", "no-cache");
        if (partial) {
            response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
        }
        return response;
    }

    private boolean isWithinLibrary(File file) throws IOException {
        String root = libraryRoot.getCanonicalPath();
        String target = file.getCanonicalPath();
        return target.equals(root) || target.startsWith(root + File.separator);
    }

    private File resolveRequestedFile(String rawPath) throws IOException {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return null;
        }
        String decoded = URLDecoder.decode(rawPath.trim(), StandardCharsets.UTF_8.name());
        File file = new File(decoded).getCanonicalFile();
        if (!file.isFile() || !isWithinLibrary(file)) {
            return null;
        }
        return file;
    }

    private static String mimeTypeForFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".aac")) return "audio/aac";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".opus")) return "audio/opus";
        if (name.endsWith(".webm")) return "audio/webm";
        if (name.endsWith(".flac")) return "audio/flac";
        if (name.endsWith(".wma")) return "audio/x-ms-wma";
        return "application/octet-stream";
    }
}
