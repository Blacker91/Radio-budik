package cz.budik.radio;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Malinky webovy server pro ovladani radia z mobilu nebo pocitace
 * v domaci siti. Zadna knihovna - jen ServerSocket a rucne skladane
 * HTTP odpovedi.
 *
 * Bezi ve vlastnim vlakne, obsluhuje jeden pozadavek po druhem.
 * Na par kliknuti za minutu to boogate staci.
 */
public class WebServer implements Runnable {

    private static final String TAG = "RadioBudik";
    public static final int PORT = 8080;

    /** Co server umi - implementuje PlayerService/MainActivity. */
    public interface Handler {
        /** JSON se stavem: co hraje, seznam stanic. */
        String stateJson();
        /** Spustit stanici podle indexu v seznamu. */
        void play(int index);
        void stop();
        /** Pridat vlastni stanici. */
        void addStation(String name, String url);
        /** Prepnout oblibenou. */
        void toggleFav(int index);
        /** Nastavit adresu pro online aktualizace. */
        void setUpdateUrl(String url);
        /** Rucne spustit kontrolu aktualizaci. */
        void checkUpdateFromWeb();
        /** Soucasna adresa pro aktualizace - pro predvyplneni pole. */
        String updateUrlForWeb();
    }

    private final Handler handler;
    private ServerSocket server;
    private volatile boolean running = false;
    private Thread thread;

    public WebServer(Handler handler) {
        this.handler = handler;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "RadioWebServer");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {}
        if (thread != null) thread.interrupt();
    }

    /** Adresa, na ktere server posloucha - k zobrazeni uzivateli. */
    public static String address(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip == 0) return null;
            return String.format("http://%d.%d.%d.%d:%d",
                    ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff, PORT);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void run() {
        try {
            server = new ServerSocket(PORT);
            server.setReuseAddress(true);
            Log.i(TAG, "Web: server bezi na portu " + PORT);
        } catch (IOException e) {
            Log.w(TAG, "Web: port " + PORT + " se nepodarilo otevrit: " + e);
            running = false;
            return;
        }

        while (running) {
            Socket client = null;
            try {
                client = server.accept();
                client.setSoTimeout(8000);
                serve(client);
            } catch (Exception e) {
                if (running) Log.w(TAG, "Web: chyba pri obsluze: " + e);
            } finally {
                try { if (client != null) client.close(); } catch (Exception ignored) {}
            }
        }

        try { if (server != null) server.close(); } catch (Exception ignored) {}
        Log.i(TAG, "Web: server ukoncen");
    }

    // ---- zpracovani pozadavku ----

    private void serve(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));

        String line = r.readLine();
        if (line == null) return;

        String[] parts = line.split(" ");
        if (parts.length < 2) return;
        String path = parts[1];

        // hlavicky preskocit, jen si zapamatovat delku tela
        int contentLength = 0;
        String h;
        while ((h = r.readLine()) != null && h.length() > 0) {
            String lower = h.toLowerCase();
            if (lower.startsWith("content-length:")) {
                try {
                    contentLength = Integer.parseInt(h.substring(15).trim());
                } catch (NumberFormatException ignored) {}
            }
        }

        String body = "";
        if (contentLength > 0 && contentLength < 64 * 1024) {
            char[] buf = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = r.read(buf, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            body = new String(buf, 0, read);
        }

        OutputStream out = client.getOutputStream();

        try {
            if (path.equals("/") || path.startsWith("/index")) {
                send(out, 200, "text/html; charset=utf-8", WebPage.HTML);

            } else if (path.startsWith("/api/state")) {
                send(out, 200, "application/json; charset=utf-8", handler.stateJson());

            } else if (path.startsWith("/api/play")) {
                handler.play(intParam(path, "i", -1));
                send(out, 200, "application/json", "{\"ok\":true}");

            } else if (path.startsWith("/api/stop")) {
                handler.stop();
                send(out, 200, "application/json", "{\"ok\":true}");

            } else if (path.startsWith("/api/fav")) {
                handler.toggleFav(intParam(path, "i", -1));
                send(out, 200, "application/json", "{\"ok\":true}");

            } else if (path.startsWith("/api/add")) {
                Map<String, String> f = parseForm(body);
                String name = f.get("name");
                String url = f.get("url");
                if (url == null || url.trim().length() == 0) {
                    send(out, 400, "application/json",
                            "{\"ok\":false,\"error\":\"chybí adresa\"}");
                } else {
                    handler.addStation(name == null ? "" : name, url);
                    send(out, 200, "application/json", "{\"ok\":true}");
                }

            } else if (path.startsWith("/api/updateurl")) {
                // GET vrati soucasnou adresu, POST ji nastavi
                if (body.length() > 0) {
                    Map<String, String> f = parseForm(body);
                    String u = f.get("url");
                    handler.setUpdateUrl(u == null ? "" : u);
                    send(out, 200, "application/json", "{\"ok\":true}");
                } else {
                    send(out, 200, "application/json; charset=utf-8",
                            "{\"url\":\"" + jsonEscape(handler.updateUrlForWeb())
                                    + "\"}");
                }

            } else if (path.startsWith("/api/checkupdate")) {
                handler.checkUpdateFromWeb();
                send(out, 200, "application/json", "{\"ok\":true}");

            } else {
                send(out, 404, "text/plain; charset=utf-8", "Nenalezeno");
            }
        } catch (Exception e) {
            Log.w(TAG, "Web: chyba: " + e);
            try {
                send(out, 500, "text/plain; charset=utf-8", "Chyba serveru");
            } catch (Exception ignored) {}
        }
    }

    private static void send(OutputStream out, int code, String type, String body)
            throws IOException {
        byte[] data = body.getBytes("UTF-8");
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(code == 200 ? " OK" : " ERR").append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(data.length).append("\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("UTF-8"));
        out.write(data);
        out.flush();
    }

    /** Vytahne cislo z dotazu, napr. "/api/play?i=3". */
    /** Minimalni osetreni znaku, ktere by rozbily JSON. */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n' || c == '\r' || c == '\t') sb.append(' ');
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    private static int intParam(String path, String key, int def) {
        int q = path.indexOf('?');
        if (q < 0) return def;
        for (String kv : path.substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key)) {
                try {
                    return Integer.parseInt(kv.substring(eq + 1));
                } catch (NumberFormatException e) {
                    return def;
                }
            }
        }
        return def;
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> m = new HashMap<String, String>();
        for (String kv : body.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            try {
                m.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                      URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) {}
        }
        return m;
    }
}
