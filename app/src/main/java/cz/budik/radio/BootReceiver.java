package cz.budik.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/**
 * Po startu systemu spusti aplikaci, pripadne rovnou zacne hrat
 * posledni poslouchanou stanici (pokud je autostart zapnuty).
 */
public class BootReceiver extends BroadcastReceiver {

    /** Pockame, az se dosynchronizuje WiFi. */
    private static final long DELAY_MS = 20000;

    @Override
    public void onReceive(final Context context, Intent intent) {
        SharedPreferences p = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(MainActivity.KEY_AUTOSTART, false)) return;

        final String name = p.getString(MainActivity.KEY_LAST_NAME, "");
        final String url = p.getString(MainActivity.KEY_LAST_URL, "");
        if (url.length() == 0) return;

        final Context app = context.getApplicationContext();

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                Intent svc = new Intent(app, PlayerService.class);
                svc.setAction(PlayerService.ACTION_PLAY);
                svc.putExtra(PlayerService.EXTRA_NAME, name);
                svc.putExtra(PlayerService.EXTRA_URL, url);
                app.startService(svc);

                Intent ui = new Intent(app, MainActivity.class);
                ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(ui);
            }
        }, DELAY_MS);
    }
}
