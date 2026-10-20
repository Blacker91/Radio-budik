package cz.budik.radio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Stahuje loga stanic (favicon z radio-browser) a uklada je na disk.
 * Zadna externi knihovna - jen HttpURLConnection a BitmapFactory.
 */
public class LogoCache {

    /** Pamet: max 2 MB obrazku - na 1 GB RAM bohate staci. */
    private static final LruCache<String, Bitmap> MEM =
            new LruCache<String, Bitmap>(4 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap b) {
                    return b.getRowBytes() * b.getHeight();
                }
            };

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** Nahradni loga (inicialy) - klicem je nazev stanice. */
    private static final LruCache<String, Bitmap> PLACEHOLDERS =
            new LruCache<String, Bitmap>(48);

    /** Vrati logo stanice, a kdyz zadne neni, vyrobi nahradu z nazvu. */
    public static Bitmap placeholderFor(String stationName) {
        String key = stationName == null ? "?" : stationName;
        Bitmap b = PLACEHOLDERS.get(key);
        if (b == null) {
            b = LogoPlaceholder.create(key);
            PLACEHOLDERS.put(key, b);
        }
        return b;
    }

    public interface Callback {
        /** bitmap muze byt null, kdyz se logo nepodarilo ziskat */
        void onLogo(String url, Bitmap bitmap);
    }

    /** Vrati logo z pameti, kdyz uz je nactene (jinak null). */
    public static Bitmap peek(String url) {
        if (url == null || url.length() == 0) return null;
        return MEM.get(url);
    }

    /** Asynchronne nacte logo - z pameti, z disku, nebo z internetu. */
    public static void load(final Context ctx, final String url, final Callback cb) {
        if (url == null || url.length() == 0) {
            cb.onLogo(url, null);
            return;
        }
        Bitmap cached = MEM.get(url);
        if (cached != null) {
            cb.onLogo(url, cached);
            return;
        }

        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap b = fromDisk(app, url);
                if (b == null) b = fromNet(app, url);
                final Bitmap result = b;
                if (result != null) MEM.put(url, result);
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onLogo(url, result);
                    }
                });
            }
        }).start();
    }

    // ---- disk ----

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "loga");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File fileFor(Context ctx, String url) {
        return new File(dir(ctx), Integer.toHexString(url.hashCode()) + ".img");
    }

    private static Bitmap fromDisk(Context ctx, String url) {
        File f = fileFor(ctx, url);
        if (!f.exists() || f.length() == 0) return null;
        try {
            return decodeScaled(f.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap fromNet(Context ctx, String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", "RadioBudik/1.0");
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;

            InputStream in = c.getInputStream();
            File f = fileFor(ctx, url);
            FileOutputStream out = new FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                // pojistka proti obrim souborum
                if (total > 1024 * 1024) break;
                out.write(buf, 0, n);
            }
            out.close();
            in.close();

            Bitmap b = decodeScaled(f.getAbsolutePath());
            if (b == null) f.delete();   // treba .ico, ktere BitmapFactory neumi
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Dekoduje s omezenim velikosti, at to nezere pamet. */
    private static Bitmap decodeScaled(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;

        int max = Math.max(o.outWidth, o.outHeight);
        int sample = 1;
        while (max / sample > 256) sample *= 2;

        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, o2);
    }
}
