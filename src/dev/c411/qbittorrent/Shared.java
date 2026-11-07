package dev.c411.qbittorrent;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Converts VIEW / SEND intents (magnets, links, .torrent files) into the JSON App.handleShared expects. */
final class Shared {
    private static final int MAX = 20 * 1024 * 1024;

    private Shared() {
    }

    /** Returns null when the intent carries nothing to share. Blocking: call it off the UI thread. */
    static String fromIntent(Context ctx, Intent in) throws Exception {
        String action = in.getAction();
        List<Uri> uris = new ArrayList<>();
        String text = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri d = in.getData();
            if (d == null) return null;
            if ("content".equals(d.getScheme()) || "file".equals(d.getScheme())) uris.add(d);
            else text = d.toString();
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
            else {
                CharSequence t = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (t == null || t.toString().trim().isEmpty()) t = in.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
                if (t != null) text = t.toString();
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) for (Uri u : list) if (u != null) uris.add(u);
        } else {
            return null;
        }
        ClipData clip = in.getClipData();
        if (uris.isEmpty() && (text == null || text.trim().isEmpty()) && clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                ClipData.Item it = clip.getItemAt(i);
                if (it.getUri() != null) uris.add(it.getUri());
                else if (text == null && it.getText() != null) text = it.getText().toString();
            }
        }
        JSONObject o = new JSONObject();
        if (!uris.isEmpty()) {
            JSONArray files = new JSONArray();
            for (Uri u : uris) files.put(readTorrent(ctx, u));
            o.put("files", files);
        } else if (text != null && !text.trim().isEmpty()) {
            o.put("text", text.trim());
        } else {
            return null;
        }
        return o.toString();
    }

    static JSONObject readTorrent(Context ctx, Uri u) throws Exception {
        String name = displayName(ctx, u);
        byte[] data;
        try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception("fichier illisible (" + name + ")");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) {
                out.write(b, 0, n);
                if (out.size() > MAX) throw new Exception("fichier trop gros (" + name + ")");
            }
            data = out.toByteArray();
        }
        // A .torrent is a bencoded dictionary: it always starts with 'd'.
        if (data.length == 0 || data[0] != 'd') throw new Exception("« " + name + " » n’est pas un fichier .torrent");
        if (!name.toLowerCase().endsWith(".torrent")) name += ".torrent";
        return new JSONObject().put("name", name).put("base64", Base64.encodeToString(data, Base64.NO_WRAP));
    }

    private static String displayName(Context ctx, Uri u) {
        String name = null;
        if ("content".equals(u.getScheme())) {
            try (Cursor c = ctx.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
            } catch (Exception ignored) {
            }
        }
        if (name == null) name = u.getLastPathSegment();
        if (name == null || name.trim().isEmpty()) return "fichier.torrent";
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }
}
