package cz.budik.radio;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Online aktualizace. Na zadane adrese ocekava maly JSON:
 *
 *   {"versionCode": 3, "versionName": "1.2", "url": "https://.../radio.apk",
 *    "novinky": "Co je nove"}
 *
 * Kdyz je versionCode vyssi nez nainstalovany, APK se stahne do cache
 * a preda systemu k instalaci. Nove APK musi byt podepsane STEJNYM
 * klicem (~/budik.keystore), jinak to Android odmitne jako cizi aplikaci.
 */
public class Updater {

    /** Vysledek kontroly. */
    public static class Info {
        public int versionCode;
        public String versionName = "";
        public String url = "";
        public String novinky = "";
    }

    /** Stazeny soubor se jmenuje vzdy stejne - starsi se prepise. */
    private static final String APK_NAME = "aktualizace.apk";

    private static final int TIMEOUT_MS = 15000;
    /** Nejvetsi rozumna velikost APK - pojistka proti nesmyslnemu souboru. */
    private static final long MAX_APK_BYTES = 30L * 1024 * 1024;

    // ---- kontrola verze ----

    /**
     * Stahne a precte JSON s informacemi o nejnovejsi verzi.
     * Vraci null, kdyz se to nepovedlo (neni sit, spatna adresa, ...).
     * Volat z vlakna na pozadi!
     */
    public static Info check(String jsonUrl) {
        if (jsonUrl == null || jsonUrl.trim().length() == 0) return null;

        HttpURLConnection c = null;
        try {
            // GitHub posila raw soubory s presmerovanim, proto par preskoku
            String url = jsonUrl.trim();
            for (int hop = 0; hop < 5; hop++) {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", "FreeRadio");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307
                        || code == 308) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null) return null;
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                if (code != 200) return null;
                break;
            }
            if (c == null) return null;

            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();

            JSONObject o = new JSONObject(sb.toString());
            Info i = new Info();
            i.versionCode = o.optInt("versionCode", 0);
            i.versionName = o.optString("versionName", "");
            i.url = o.optString("url", "");
            i.novinky = o.optString("novinky", "");
            if (i.versionCode <= 0 || i.url.length() == 0) return null;
            return i;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    /** Verze, ktera je prave nainstalovana. */
    public static int installedVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Je na serveru neco novejsiho? */
    public static boolean isNewer(Context ctx, Info i) {
        return i != null && i.versionCode > installedVersion(ctx);
    }

    // ---- stazeni ----

    /**
     * Stahne APK do cache aplikace. Vraci soubor, nebo null pri chybe.
     * Volat z vlakna na pozadi!
     */
    public static File download(Context ctx, String apkUrl) {
        File out = new File(ctx.getCacheDir(), APK_NAME);
        HttpURLConnection c = null;
        FileOutputStream fos = null;
        InputStream in = null;
        try {
            String url = apkUrl;
            for (int hop = 0; hop < 5; hop++) {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", "FreeRadio");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307
                        || code == 308) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    c = null;
                    if (loc == null) return null;
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                if (code != 200) return null;
                break;
            }
            if (c == null) return null;

            in = c.getInputStream();
            fos = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_APK_BYTES) throw new Exception("APK je moc velke");
                fos.write(buf, 0, n);
            }
            fos.flush();
            fos.close();
            fos = null;

            // APK zacina jako kazdy ZIP znakama "PK" - hruba kontrola,
            // ze jsme nestahli chybovou stranku. Aplikace sama ma jen
            // kolem 60 kB, proto je spodni hranice nizko.
            if (total < 20000 || !looksLikeZip(out)) {
                out.delete();
                return null;
            }

            // instalator systemu musi na soubor videt
            out.setReadable(true, false);
            return out;
        } catch (Exception e) {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            out.delete();
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static boolean looksLikeZip(File f) {
        java.io.FileInputStream fis = null;
        try {
            fis = new java.io.FileInputStream(f);
            return fis.read() == 'P' && fis.read() == 'K';
        } catch (Exception e) {
            return false;
        } finally {
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
        }
    }

    // ---- instalace ----

    /**
     * Zkusi nainstalovat potichu pres root. Vraci true, kdyz se to povedlo.
     * Na budiku bez rootu to jen tise selze a pokracuje se dialogem.
     * Volat z vlakna na pozadi!
     */
    public static boolean installWithRoot(File apk) {
        try {
            // soubor musi byt citelny i pro shell
            apk.setReadable(true, false);
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "pm install -r \"" + apk.getAbsolutePath() + "\""});

            StringBuilder outp = new StringBuilder();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) outp.append(l);
            r.close();
            p.waitFor();

            // "pm install" vypise Success, kdyz prosel
            return outp.toString().toLowerCase().contains("success");
        } catch (Exception e) {
            return false;
        }
    }

    /** Je na budiku root? */
    public static boolean hasRoot() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            p.waitFor();
            return sb.toString().contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ma aplikace povoleno instalovat aplikace? Na Androidu 8 a novejsim
     * je to prepinac v nastaveni, ktery staci zapnout jednou a pak plati.
     */
    public static boolean canInstall(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        try {
            return ctx.getPackageManager().canRequestPackageInstalls();
        } catch (Exception e) {
            return false;
        }
    }

    /** Otevre obrazovku, kde se to povoleni zapina. */
    public static boolean openInstallSettings(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            // nektere okrajove ROM tuhle obrazovku nemaji - zkusime obecnou
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    /**
     * Preda APK systemovemu instalatoru. Uzivatel pak jen potvrdi.
     * Od Androidu 7 nejde poslat cestu k souboru, musi to byt content://
     * adresa - proto mame vlastni ApkProvider.
     */
    public static boolean installWithDialog(Context ctx, File apk) {
        try {
            Uri uri = Uri.parse("content://" + ApkProvider.AUTHORITY
                    + "/" + apk.getName());
            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            i.putExtra(Intent.EXTRA_RETURN_RESULT, false);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Smaze stazene APK - po uspesne instalaci uz je k nicemu. */
    public static void cleanup(Context ctx) {
        try {
            new File(ctx.getCacheDir(), APK_NAME).delete();
        } catch (Exception ignored) {}
    }

    private Updater() {}
}
