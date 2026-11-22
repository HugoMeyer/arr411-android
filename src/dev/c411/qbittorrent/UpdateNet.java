package dev.c411.qbittorrent;

import android.os.SystemClock;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Locale;

/**
 * The requests of the update. They only go to GitHub: every address, the first one and each redirect, has to pass {@link UpdateHosts}, and the redirects
 * are followed here one by one, never by the connection. Nothing the user typed or stored goes with them, only the name of the app and its version.
 */
final class UpdateNet {
    enum Kind {
        /** The answer of the API of GitHub: a small JSON text. */
        API,
        /** A file of the release, which GitHub sends from its CDN after one or two redirects. */
        DOWNLOAD
    }

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int API_READ_TIMEOUT_MS = 15_000;
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 30_000;

    private UpdateNet() {}

    /** An answer whose body has not been read yet. Closing it ends the connection. */
    static final class Reply implements Closeable {
        final int status;
        private final HttpURLConnection connection;

        private Reply(int status, HttpURLConnection connection) {
            this.status = status;
            this.connection = connection;
        }

        String header(String name) {
            return connection.getHeaderField(name);
        }

        /** The length that the server announces, or -1. */
        long length() {
            return connection.getContentLengthLong();
        }

        /** The body of a successful answer. For an error status there is nothing to read: its message would only repeat the address. */
        InputStream body() throws IOException {
            if (status >= 400) throw new UpdateFailure(UpdateFlow.httpMessage(status), false);
            return connection.getInputStream();
        }

        @Override
        public void close() {
            try {
                connection.disconnect();
            } catch (RuntimeException ignored) {
            }
        }
    }

    static Reply get(String address, String userAgent, Kind kind, String etag) throws IOException {
        URL url;
        try {
            url = new URL(address);
        } catch (MalformedURLException e) {
            throw new UpdateFailure("Adresse de mise à jour invalide", false);
        }
        return get(url, userAgent, kind, etag);
    }

    /**
     * Asks for {@code url} and follows the redirects that stay on the hosts of GitHub; the {@link Reply} is the last answer, which can be an error
     * status. {@code etag} is only sent to the API, with the first request.
     */
    static Reply get(URL url, String userAgent, Kind kind, String etag) throws IOException {
        URL current = url;
        long started = SystemClock.elapsedRealtime();
        int followed = 0;
        while (true) {
            if (!UpdateHosts.isAllowed(current)) throw new UpdateFailure("Adresse de mise à jour non autorisée", false);
            HttpURLConnection connection = open(current, userAgent, kind, followed == 0 ? etag : null);
            boolean handedOver = false;
            try {
                int status = connection.getResponseCode();
                if (RedirectPolicy.isRedirect(status)) {
                    UpdateHosts.Hop hop = UpdateHosts.next(current, status, connection.getHeaderField("Location"), followed);
                    if (hop.url == null) throw new UpdateFailure(hop.refusal, false);
                    current = hop.url;
                    followed++;
                    continue;
                }
                Diag.http(what(current), status, null, 0, SystemClock.elapsedRealtime() - started);
                handedOver = true;
                return new Reply(status, connection);
            } catch (UpdateFailure e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                // Only the class: the message of a network error can carry the address, and the address of the CDN is signed.
                Diag.http(what(current), 0, e.getClass().getSimpleName(), 0, SystemClock.elapsedRealtime() - started);
                throw e;
            } finally {
                if (!handedOver) connection.disconnect();
            }
        }
    }

    private static HttpURLConnection open(URL url, String userAgent, Kind kind, String etag) throws IOException {
        URLConnection raw = url.openConnection();
        if (!(raw instanceof HttpURLConnection)) throw new UpdateFailure("Adresse de mise à jour non autorisée", false);
        HttpURLConnection connection = (HttpURLConnection) raw;
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(kind == Kind.API ? API_READ_TIMEOUT_MS : DOWNLOAD_READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", userAgent);
            if (kind == Kind.API) {
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                String tag = UpdateFlow.etagOrNull(etag);
                if (tag != null && "api.github.com".equalsIgnoreCase(url.getHost())) connection.setRequestProperty("If-None-Match", tag);
            } else {
                connection.setRequestProperty("Accept", "application/octet-stream");
                // The size announced has to be the size of the file: no compression that hides it.
                connection.setRequestProperty("Accept-Encoding", "identity");
            }
            return connection;
        } catch (IOException | RuntimeException e) {
            connection.disconnect();
            throw e;
        }
    }

    /** "GET host/path" for the two hosts whose paths are public; the CDN gets its host only, its paths and queries are signed. */
    private static String what(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        boolean showPath = "api.github.com".equals(host) || "github.com".equals(host);
        return "GET " + host + (showPath ? url.getPath() : "");
    }
}
