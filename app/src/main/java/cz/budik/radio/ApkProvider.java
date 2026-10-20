package cz.budik.radio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimalisticka obdoba FileProvideru z podpurnych knihoven.
 *
 * Od Androidu 7 nelze poslat jine aplikaci cestu k souboru (file://) -
 * skonci to vyjimkou FileUriExposedException. Systemovy instalator proto
 * potrebuje adresu content://, za kterou stoji tenhle provider.
 *
 * Napsane rucne, aby aplikace zustala bez jedine zavislosti.
 * Zpristupnuje VYHRADNE soubory z cache teto aplikace - nic jineho.
 */
public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "cz.budik.radio.apk";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** Z adresy vytahne jmeno souboru a odmitne pokusy vylezt z cache. */
    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException("Neplatná adresa: " + uri);
        }
        File f = new File(getContext().getCacheDir(), name);
        if (!f.exists()) throw new FileNotFoundException("Soubor není: " + name);
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        // jen pro cteni - instalator nic prepisovat nepotrebuje
        return ParcelFileDescriptor.open(resolve(uri),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * Instalator se pred instalaci pta na jmeno a velikost souboru.
     * Bez teto odpovedi instalaci odmitne.
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }

        String[] cols = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    // ---- zapis provider nepodporuje ----

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
