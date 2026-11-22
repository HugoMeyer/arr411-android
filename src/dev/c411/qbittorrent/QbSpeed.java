package dev.c411.qbittorrent;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * What the Quick Settings tile decides about qBittorrent's alternative speed limits: the address, the login, the answers, the look of the tile,
 * what a tap does, and the exchange with the server, whose network is handed in. Nothing here touches Android: every rule is checked without a phone.
 */
final class QbSpeed {
    /** What the tile knows: the limits are on or off, or the reason it can't tell. */
    enum Outcome { ON, OFF, NOT_CONFIGURED, OFFLINE, REFUSED }

    /** What a tap on the tile does. */
    enum Tap { SETTINGS, IGNORE, TOGGLE, REFRESH }

    /** The state of the tile: lit or dimmed (never unavailable, which swallows the taps), and the line under its name. */
    static final class Look {
        final boolean active;
        final String subtitle;

        private Look(boolean active, String subtitle) {
            this.active = active;
            this.subtitle = subtitle;
        }
    }

    /** The network of {@link #exchange}: one request to the qBittorrent at hand. It never throws: a failure is a reply with an error. */
    interface Server {
        /** {@code form}: the body of a POST ("" for none), or null for a GET. */
        Reply send(String method, String path, String form);
    }

    /** One answer. {@code error} is set only when the server could not be reached. */
    static final class Reply {
        final int status;
        final String text;
        final String error;

        Reply(int status, String text, String error) {
            this.status = status;
            this.text = text == null ? "" : text;
            this.error = error;
        }
    }

    /** What an exchange found out. {@code credentialsRefused}: the server turned the login down, which it counts as a wrong login. */
    static final class Result {
        final Outcome outcome;
        final boolean credentialsRefused;

        private Result(Outcome outcome, boolean credentialsRefused) {
            this.outcome = outcome;
            this.credentialsRefused = credentialsRefused;
        }
    }

    /** Remembers the last time the server turned the credentials down, so that the same ones aren't sent again at once. {@code key}: a hash of the address and the credentials. */
    static final class LoginGate {
        private int refusedKey;
        private long refusedAt = -1;

        /** False while the credentials of {@code key} are being waited on. */
        synchronized boolean allowed(int key, long now) {
            return refusedKey != key || mayLogin(now, refusedAt);
        }

        /** A refusal starts the wait; a state that was read with the same credentials ends it; anything else leaves it as it is. */
        synchronized void done(int key, long now, Result result) {
            if (result.credentialsRefused) {
                refusedKey = key;
                refusedAt = now;
            } else if (refusedKey == key && (result.outcome == Outcome.ON || result.outcome == Outcome.OFF)) {
                refusedAt = -1;
            }
        }
    }

    static final String PATH_LOGIN = "/api/v2/auth/login";
    static final String PATH_MODE = "/api/v2/transfer/speedLimitsMode";
    static final String PATH_TOGGLE = "/api/v2/transfer/toggleSpeedLimitsMode";
    /** The last answer is shown as it is for this long; once it is older, the tile asks again when it is opened. */
    static final long FRESH_MS = 15L * 1000;
    /** The same credentials are sent at most once in this time: qBittorrent bans an address for an hour after a few wrong logins. */
    static final long LOGIN_GAP_MS = 10L * 60 * 1000;
    /** Under the name of the tile while a request is on its way. */
    static final String BUSY = "…";
    /** What a screen reader hears meanwhile. */
    static final String BUSY_STATE = "En cours";

    private static final String HEX = "0123456789ABCDEF";

    private QbSpeed() {}

    // ---------- The server ----------

    /** The address of the web interface without its trailing slashes (a reverse proxy may add a path), or null unless it is an http(s) address. */
    static String baseUrl(String qbUrl) {
        if (qbUrl == null) return null;
        String s = qbUrl.trim();
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        s = s.substring(0, end);
        int rest = s.regionMatches(true, 0, "https://", 0, 8) ? 8 : s.regionMatches(true, 0, "http://", 0, 7) ? 7 : 0;
        return rest > 0 && s.length() > rest ? s : null;
    }

    /** The body of the login, as a form: UTF-8, with everything but letters, digits and "-._~" as %XX, so that "&", "=", "+", spaces and accents arrive as typed. */
    static String loginForm(String user, String pass) {
        return "username=" + encode(user) + "&password=" + encode(pass);
    }

    private static String encode(String text) {
        StringBuilder out = new StringBuilder();
        for (byte b : (text == null ? "" : text).getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 15));
            }
        }
        return out.toString();
    }

    /** False when there is nothing to log in with: a server that wants a login then can't be helped, and a try would only count as a wrong login. */
    static boolean canLogin(String user, String pass) {
        return !((user == null || user.isEmpty()) && (pass == null || pass.isEmpty()));
    }

    /** The server asks for a login. */
    static boolean needsLogin(int status) {
        return status == 401 || status == 403;
    }

    /** The login worked: 204, or a 2xx that says "Ok." (older versions). */
    static boolean loginAccepted(int status, String body) {
        if (status == 204) return true;
        return status >= 200 && status < 300 && body != null && body.trim().toLowerCase(Locale.ROOT).startsWith("ok");
    }

    /** The server turned the credentials down: 401 or 403 (also what an address that is banned gets), or a 2xx that says "Fails.". Any other answer is not a verdict (a proxy page, a server error). */
    static boolean loginRefused(int status, String body) {
        if (needsLogin(status)) return true;
        return status >= 200 && status < 300 && body != null && body.trim().toLowerCase(Locale.ROOT).startsWith("fails");
    }

    /** True or false for the limits, null when the answer is not one: an error status, or anything but "1" and "0". */
    static Boolean parseMode(int status, String body) {
        if (status < 200 || status > 299 || body == null) return null;
        String text = body.trim();
        if (text.equals("1")) return Boolean.TRUE;
        if (text.equals("0")) return Boolean.FALSE;
        return null;
    }

    // ---------- The clocks ----------

    /** True when no answer was ever kept ({@code checkedAt} negative), when the clock went back, or when the answer is old. Monotonic times. */
    static boolean stale(long now, long checkedAt) {
        return checkedAt < 0 || now < checkedAt || now - checkedAt >= FRESH_MS;
    }

    /** True when the credentials may be sent: no refusal is on record ({@code refusedAt} negative), the clock went back, or the refusal is old. Monotonic times. */
    static boolean mayLogin(long now, long refusedAt) {
        return refusedAt < 0 || now < refusedAt || now - refusedAt >= LOGIN_GAP_MS;
    }

    // ---------- The tile ----------

    static Look look(Outcome outcome) {
        switch (outcome) {
            case ON:
                return new Look(true, "Activée");
            case OFF:
                return new Look(false, "Désactivée");
            case NOT_CONFIGURED:
                return new Look(false, "À configurer");
            case REFUSED:
                return new Look(false, "Identifiants refusés");
            case OFFLINE:
            default:
                return new Look(false, "Hors ligne");
        }
    }

    /**
     * What a tap does. The limits are only flipped from a state that is known (it was read for these credentials): flipping blind could do the
     * opposite of what the user sees. {@code known} is null when nothing was read for them.
     */
    static Tap tap(boolean configured, boolean busy, Outcome known) {
        if (!configured) return Tap.SETTINGS;
        if (busy) return Tap.IGNORE;
        return known == Outcome.ON || known == Outcome.OFF ? Tap.TOGGLE : Tap.REFRESH;
    }

    // ---------- The exchange ----------

    /**
     * Reads whether the limits are on, after flipping them when {@code toggle}. When the server asks for a login, it is sent once at most, and only
     * when {@code loginAllowed} and there are credentials; the request is then sent again. A request that was turned down for want of a login
     * never took effect, so sending it again can't flip the limits twice. A flip is never sent a second time for any other reason.
     */
    static Result exchange(Server server, boolean toggle, String user, String pass, boolean loginAllowed) {
        Exchange x = new Exchange(server, user, pass, loginAllowed);
        Outcome outcome = x.run(toggle);
        return new Result(outcome, x.credentialsRefused);
    }

    private static final class Exchange {
        private final Server server;
        private final String user;
        private final String pass;
        private final boolean loginAllowed;
        private boolean loginSent;
        private Outcome failure = Outcome.OFFLINE;
        boolean credentialsRefused;

        Exchange(Server server, String user, String pass, boolean loginAllowed) {
            this.server = server;
            this.user = user;
            this.pass = pass;
            this.loginAllowed = loginAllowed;
        }

        Outcome run(boolean toggle) {
            if (toggle) {
                Reply flipped = ask("POST", PATH_TOGGLE, "");
                if (flipped == null) return failure;
                if (flipped.status < 200 || flipped.status > 299) return Outcome.OFFLINE;
            }
            Reply mode = ask("GET", PATH_MODE, null);
            if (mode == null) return failure;
            Boolean on = parseMode(mode.status, mode.text);
            return on == null ? Outcome.OFFLINE : on ? Outcome.ON : Outcome.OFF;
        }

        /** The answer to a request, after the login the server asked for; null when there is none, and {@code failure} says why. */
        private Reply ask(String method, String path, String form) {
            Reply reply = server.send(method, path, form);
            if (reply.error == null && needsLogin(reply.status)) {
                if (loginSent || !loginAllowed || !canLogin(user, pass)) return fail(Outcome.REFUSED);
                loginSent = true;
                Reply login = server.send("POST", PATH_LOGIN, loginForm(user, pass));
                if (login.error != null) return fail(Outcome.OFFLINE);
                if (!loginAccepted(login.status, login.text)) {
                    credentialsRefused = loginRefused(login.status, login.text);
                    return fail(credentialsRefused ? Outcome.REFUSED : Outcome.OFFLINE);
                }
                reply = server.send(method, path, form);
                if (reply.error == null && needsLogin(reply.status)) {
                    credentialsRefused = true;
                    return fail(Outcome.REFUSED);
                }
            }
            return reply.error == null ? reply : fail(Outcome.OFFLINE);
        }

        private Reply fail(Outcome outcome) {
            failure = outcome;
            return null;
        }
    }
}
