package cz.budik.radio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * Kdyz stanice nema v databazi logo, vyrobime nahradu:
 * barevna dlazdice s inicialami. Barva se odvozuje z nazvu,
 * takze stejna stanice ma vzdy stejnou barvu.
 */
public class LogoPlaceholder {

    // 128 px staci - v seznamu se kresli do 36dp, na pozadi se stejne roztahne.
    // Mensi bitmapa = ctvrtinova pamet, coz se na 1 GB RAM pocita.
    private static final int SIZE = 128;

    /** Sytejsi, ale ne kricive barvy - vypadaji dobre i jako pozadi. */
    private static final int[][] PALETTE = {
            {0xFF1E5C8A, 0xFF2E8BC0},   // modra
            {0xFF7A2E5C, 0xFFB8478E},   // purpurova
            {0xFF1F6B4F, 0xFF34A176},   // zelena
            {0xFF8A4B1E, 0xFFC97B2E},   // oranzova
            {0xFF5C2E7A, 0xFF8E47B8},   // fialova
            {0xFF8A1E3C, 0xFFC02E5C},   // cervena
            {0xFF1E7A7A, 0xFF2EB8B8},   // tyrkysova
            {0xFF4A5568, 0xFF718096}    // sedomodra
    };

    public static Bitmap create(String stationName) {
        String name = (stationName == null || stationName.trim().length() == 0)
                ? "?" : stationName.trim();

        // pozor: Math.abs(Integer.MIN_VALUE) je zaporne, proto maskujeme bit
        int idx = (name.hashCode() & 0x7FFFFFFF) % PALETTE.length;
        int[] colors = PALETTE[idx];

        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bmp);

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, SIZE, SIZE,
                colors[0], colors[1], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, SIZE, SIZE, bg);

        String initials = initialsOf(name);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(initials.length() > 1 ? SIZE * 0.38f : SIZE * 0.52f);

        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = SIZE / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(initials, SIZE / 2f, baseline, text);

        return bmp;
    }

    /** Vezme prvni pismena prvnich dvou slov, jinak prvni dve pismena. */
    private static String initialsOf(String name) {
        StringBuilder sb = new StringBuilder();
        String[] words = name.split("[\\s\\-_]+");
        for (String w : words) {
            if (w.length() == 0) continue;
            char ch = w.charAt(0);
            if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toUpperCase(ch));
            }
            if (sb.length() >= 2) break;
        }
        if (sb.length() == 0) {
            String up = name.toUpperCase();
            return up.length() >= 2 ? up.substring(0, 2) : up;
        }
        return sb.toString();
    }
}
