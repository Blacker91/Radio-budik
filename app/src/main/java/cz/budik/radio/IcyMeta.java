package cz.budik.radio;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Precte z audio streamu ICY metadata (pole StreamTitle),
 * tedy nazev prave hrajici skladby.
 *
 * Umi si poradit s:
 *  - presmerovanim (vcetne zmeny http -> https, ktere HttpURLConnection sam nezvladne)
 *  - playlistem (.m3u / .pls), ktery je potreba nejdriv rozbalit na skutecny stream
 */
public class IcyMeta {

    private static final String TAG = "RadioBudik";
    private static final int MAX_HOPS = 5;

    /** @return nazev skladby, nebo null kdyz ho nelze ziskat */
    public static String read(String streamUrl) {
        String url = streamUrl;

        for (int hop = 0; hop < MAX_HOPS; hop++) {
            HttpURLConnection c = null;
            InputStream in = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("Icy-MetaData", "1");
                c.setRequestProperty("User-Agent", "RadioBudik/1.0");
                c.setConnectTimeout(8000);
                c.setReadTimeout(12000);
                c.setInstanceFollowRedirects(false);   // resime rucne
                c.connect();

                int code = c.getResponseCode();

                // --- presmerovani ---
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) {
                        Log.w(TAG, "ICY: presmerovani bez Location");
                        return null;
                    }
                    url = new URL(new URL(url), loc).toString();
                    Log.i(TAG, "ICY: presmerovano na " + url);
                    continue;
                }

                if (code != 200) {
                    Log.w(TAG, "ICY: server vratil " + code + " pro " + url);
                    return null;
                }

                String ctype = c.getContentType();
                if (ctype != null) ctype = ctype.toLowerCase();

                // --- playlist misto streamu? ---
                if (isPlaylist(ctype, url)) {
                    String inner = firstUrlFromPlaylist(c.getInputStream());
                    if (inner == null) {
                        Log.w(TAG, "ICY: playlist neobsahuje zadnou adresu");
                        return null;
                    }
                    url = new URL(new URL(url), inner).toString();
                    Log.i(TAG, "ICY: playlist rozbalen na " + url);
                    continue;
                }

                // --- ICY metadata ---
                String miHeader = c.getHeaderField("icy-metaint");
                if (miHeader == null) {
                    Log.i(TAG, "ICY: stanice neposila metadata (chybi icy-metaint), ctype="
                            + ctype + ", url=" + url);
                    return null;
                }

                int metaint;
                try {
                    metaint = Integer.parseInt(miHeader.trim());
                } catch (NumberFormatException e) {
                    Log.w(TAG, "ICY: nesrozumitelny icy-metaint: " + miHeader);
                    return null;
                }
                if (metaint <= 0 || metaint > 1024 * 1024) {
                    Log.w(TAG, "ICY: nesmyslny icy-metaint: " + metaint);
                    return null;
                }

                in = c.getInputStream();

                // preskocit metaint bajtu zvuku
                long skipped = 0;
                byte[] buf = new byte[4096];
                while (skipped < metaint) {
                    int want = (int) Math.min(buf.length, metaint - skipped);
                    int n = in.read(buf, 0, want);
                    if (n < 0) {
                        Log.w(TAG, "ICY: stream skoncil driv, nez prisla metadata");
                        return null;
                    }
                    skipped += n;
                }

                // dalsi bajt = delka metadat / 16
                int lenByte = in.read();
                if (lenByte < 0) return null;
                if (lenByte == 0) {
                    // prazdny blok - stanice zatim nic nehlasi
                    Log.i(TAG, "ICY: prazdny blok metadat");
                    return null;
                }
                int metaLen = lenByte * 16;

                byte[] meta = new byte[metaLen];
                int read = 0;
                while (read < metaLen) {
                    int n = in.read(meta, read, metaLen - read);
                    if (n < 0) break;
                    read += n;
                }

                String raw = new String(meta, 0, read, "UTF-8");
                String title = parseStreamTitle(raw);
                Log.i(TAG, "ICY: metadata=" + read + "B, title=" + title);
                return title;

            } catch (Exception e) {
                Log.w(TAG, "ICY: chyba: " + e);
                return null;
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                if (c != null) c.disconnect();
            }
        }

        Log.w(TAG, "ICY: prilis mnoho presmerovani");
        return null;
    }

    private static boolean isPlaylist(String ctype, String url) {
        String u = url.toLowerCase();
        if (u.endsWith(".m3u") || u.endsWith(".m3u8") || u.endsWith(".pls")) return true;
        if (ctype == null) return false;
        return ctype.contains("mpegurl")
                || ctype.contains("scpls")
                || ctype.contains("x-scpls")
                || ctype.contains("audio/x-mpegurl");
    }

    /** Z .m3u / .pls vytahne prvni adresu streamu. */
    private static String firstUrlFromPlaylist(InputStream is) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            int guard = 0;
            while ((line = r.readLine()) != null && guard++ < 200) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;

                // .pls ma radky typu "File1=http://..."
                int eq = line.indexOf('=');
                if (eq > 0 && line.toLowerCase().startsWith("file")) {
                    line = line.substring(eq + 1).trim();
                }
                if (line.startsWith("http://") || line.startsWith("https://")) {
                    return line;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ICY: chyba pri cteni playlistu: " + e);
        }
        return null;
    }

    /** Z retezce "StreamTitle='Interpret - Skladba';" vytahne obsah. */
    private static String parseStreamTitle(String meta) {
        int i = meta.indexOf("StreamTitle='");
        if (i < 0) return null;
        int start = i + "StreamTitle='".length();
        int end = meta.indexOf("';", start);
        if (end < 0) {
            end = meta.indexOf("'", start);
            if (end < 0) return null;
        }
        String title = meta.substring(start, end).trim();
        title = title.replace("\u0000", "").trim();
        return title.length() == 0 ? null : title;
    }
}
