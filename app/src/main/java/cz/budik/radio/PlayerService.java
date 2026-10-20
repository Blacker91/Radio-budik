package cz.budik.radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Prehrava stream na pozadi jako foreground service.
 * Diky notifikaci ji nezabije ani MediaTek DuraSpeed ani launcher.
 */
public class PlayerService extends Service implements
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnCompletionListener {

    public static final String ACTION_PLAY = "cz.budik.radio.PLAY";
    public static final String ACTION_STOP = "cz.budik.radio.STOP";
    /** Aplikace se pta, jestli uz neco hraje. */
    public static final String ACTION_QUERY = "cz.budik.radio.QUERY";
    /** Uzivatel zmenil interval srovnavani - prepocitat odpocet. */
    public static final String ACTION_RESYNC_PREF = "cz.budik.radio.RESYNC_PREF";

    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_LOGO = "logo";

    /** Broadcast se stavem prehravace pro MainActivity. */
    public static final String BROADCAST_STATE = "cz.budik.radio.STATE";
    public static final String EXTRA_STATE = "state";      // idle|loading|playing|error|reconnecting|resync
    public static final String EXTRA_TITLE = "title";      // prave hraje
    public static final String EXTRA_HASMETA = "hasmeta";  // posila stanice metadata?

    private static final String CHANNEL_ID = "radio";
    private static final int NOTIF_ID = 1;
    /** Po vypadku site zkousime znovu - prodlevy v sekundach. */
    private static final int[] RETRY_DELAYS_S = {3, 5, 10, 20, 30, 60};
    private static final long META_INTERVAL_MS = 20000;
    /** Prvni pokusy delame rychle po sobe, at je nazev videt hned. */
    private static final long META_FIRST_RETRY_MS = 5000;

    private MediaPlayer player;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    private String currentName = "";
    private String currentUrl = "";
    private String currentLogo = "";
    private String currentTitle = "";
    private String state = "idle";
    /** false = stanice ICY metadata vubec neposila */
    private boolean hasMeta = true;

    /** Kolikaty pokus o obnoveni spojeni prave probiha. */
    private int retryCount = 0;
    /** Bezi odpocet do dalsiho pokusu? */
    private boolean retryScheduled = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Thread metaThread;
    private volatile boolean metaRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "radio:wifi");
            wifiLock.setReferenceCounted(false);
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "radio:cpu");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            String name = intent.getStringExtra(EXTRA_NAME);
            String url = intent.getStringExtra(EXTRA_URL);
            String logo = intent.getStringExtra(EXTRA_LOGO);
            currentLogo = logo == null ? "" : logo;
            if (url != null && url.length() > 0) {
                play(name == null ? "" : name, url);
            }
        } else if (ACTION_QUERY.equals(action)) {
            // aplikace se ptá, co hraje - jen odpovíme
            broadcastState();
        } else if (ACTION_RESYNC_PREF.equals(action)) {
            // nový interval má platit hned, ne až po příštím připojení
            if ("playing".equals(state)) scheduleResync();
            else cancelResync();
        } else if (ACTION_STOP.equals(action)) {
            stopPlayback(true);
            stopForeground(true);
            stopSelf();
        }
        return START_STICKY;
    }

    private void play(String name, String url) {
        // POZOR: bez "false" by se rozeslal stav "idle" a aplikace by to
        // pochopila jako konec prehravani - prepnula by zpet na seznam
        stopPlayback(false);

        currentName = name;
        currentUrl = url;
        currentTitle = "";
        hasMeta = true;
        retryCount = 0;
        cancelRetry();
        setState("loading");

        startForeground(NOTIF_ID, buildNotification());

        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setOnPreparedListener(this);
            player.setOnErrorListener(this);
            player.setOnCompletionListener(this);
            player.setDataSource(url);
            player.prepareAsync();

            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception e) {
            scheduleRetry();
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        mp.start();
        retryCount = 0;
        setState("playing");
        updateNotification();
        startMetaLoop();
        // odpocet do dalsiho srovnani se pocita od tohoto okamziku -
        // ted je stream presne na zive urovni
        scheduleResync();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        // Nejcastejsi pricina je vypadek Wi-Fi nebo prerusene spojeni
        // ze strany stanice. Nema smysl to vzdavat - zkusime znovu.
        scheduleRetry();
        return true;
    }

    /**
     * Zive vysilani nikdy "neskonci" - kdyz prijde konec, znamena to,
     * ze server spojeni ukoncil. Take duvod k novemu pripojeni.
     */
    @Override
    public void onCompletion(MediaPlayer mp) {
        scheduleRetry();
    }

    private void scheduleRetry() {
        if (retryScheduled || currentUrl.length() == 0) return;

        // pripravit prehravac na novy pokus
        cancelResync();
        stopMetaLoop();
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }

        int idx = Math.min(retryCount, RETRY_DELAYS_S.length - 1);
        int delay = RETRY_DELAYS_S[idx];
        retryCount++;
        retryScheduled = true;

        state = "reconnecting";
        broadcastState();
        updateNotification();

        handler.postDelayed(retryRunnable, delay * 1000L);
    }

    private void cancelRetry() {
        retryScheduled = false;
        handler.removeCallbacks(retryRunnable);
    }

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            retryScheduled = false;
            if (currentUrl.length() == 0) return;
            // znovu se pripojit - tim se zaroven srovna zpozdeni
            // proti zivemu vysilani, ktere se behem prehravani nabira
            reconnect();
        }
    };

    // ---- srovnavani zpozdeni proti zivemu vysilani ----
    //
    // Pri kazdem zakolisani site prehravac pocka, nez se znovu naplni
    // buffer, a pak pokracuje normalni rychlosti. Ten rozdil se uz nikdy
    // nedohoni a za hodiny poslouchani naroste na minuty. Jediny zpusob,
    // jak ho srovnat, je pripojit se znovu - tim zacneme odtud, kde
    // stanice prave vysila.

    /** Spusti odpocet do dalsiho srovnani podle nastaveni. */
    private void scheduleResync() {
        cancelResync();
        int min = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getInt(MainActivity.KEY_RESYNC_MIN, MainActivity.DEFAULT_RESYNC_MIN);
        if (min <= 0) return;   // uzivatel si to vypnul
        handler.postDelayed(resyncRunnable, min * 60000L);
    }

    private void cancelResync() {
        handler.removeCallbacks(resyncRunnable);
    }

    private final Runnable resyncRunnable = new Runnable() {
        @Override
        public void run() {
            // srovnavat ma smysl jen kdyz se vazne hraje; pri obnovovani
            // spojeni se zpozdeni srovna samo a odpocet se rozjede znovu
            if (currentUrl.length() == 0 || !"playing".equals(state)) return;
            doResync();
        }
    };

    /** Tiche prepojeni na stejnou stanici - zahodi nabrane zpozdeni. */
    private void doResync() {
        stopMetaLoop();
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) {}
            try { player.reset(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        // currentTitle zamerne nemazeme - za tu vterinu se skladba nezmeni
        // a nemelo by to na obrazovce zbytecne poskakovat
        setState("resync");
        updateNotification();
        reconnect();
    }

    /** Nove pripojeni na stejnou stanici. */
    private void reconnect() {
        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setOnPreparedListener(this);
            player.setOnErrorListener(this);
            player.setOnCompletionListener(this);
            player.setDataSource(currentUrl);
            player.prepareAsync();

            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception e) {
            scheduleRetry();
        }
    }

    private void stopPlayback() {
        stopPlayback(true);
    }

    /**
     * @param notify rozeslat stav "idle"? Pri prepinani stanice ne -
     *               hned nato prijde "loading" pro novou stanici.
     */
    private void stopPlayback(boolean notify) {
        cancelRetry();
        cancelResync();
        stopMetaLoop();
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
            } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        currentTitle = "";
        currentUrl = "";
        retryCount = 0;
        state = "idle";
        if (notify) broadcastState();
    }

    // ---- ICY metadata ----

    private void startMetaLoop() {
        stopMetaLoop();
        metaRunning = true;
        final String url = currentUrl;
        metaThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int emptyRounds = 0;
                boolean gotAnything = false;
                while (metaRunning) {
                    final String t = IcyMeta.read(url);
                    if (!metaRunning) return;

                    if (t == null) {
                        emptyRounds++;
                        // po trech marnych pokusech to vzdame a rekneme to uzivateli
                        if (emptyRounds == 3) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    hasMeta = false;
                                    broadcastState();
                                }
                            });
                        }
                    } else if (!t.equals(currentTitle)) {
                        gotAnything = true;
                        emptyRounds = 0;
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                hasMeta = true;
                                currentTitle = t;
                                broadcastState();
                                updateNotification();
                            }
                        });
                    }
                    try {
                        // dokud jsme nic nedostali, zkousime rychleji
                        Thread.sleep(gotAnything ? META_INTERVAL_MS : META_FIRST_RETRY_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        });
        metaThread.setPriority(Thread.MIN_PRIORITY);
        metaThread.start();
    }

    private void stopMetaLoop() {
        metaRunning = false;
        if (metaThread != null) {
            metaThread.interrupt();
            metaThread = null;
        }
    }

    // ---- stav + notifikace ----

    private void setState(String s) {
        state = s;
        broadcastState();
    }

    private void broadcastState() {
        Intent i = new Intent(BROADCAST_STATE);
        i.putExtra(EXTRA_STATE, state);
        i.putExtra(EXTRA_NAME, currentName);
        i.putExtra(EXTRA_TITLE, currentTitle);
        i.putExtra(EXTRA_HASMETA, hasMeta);
        i.putExtra(EXTRA_URL, currentUrl);
        i.putExtra(EXTRA_LOGO, currentLogo);
        sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                // IMPORTANCE_MIN = notifikace bez zvuku, bez ikony ve stavovem
                // radku; system ji pro beh sluzby na pozadi vyzaduje, ale
                // takhle prakticky neprekazi
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Přehrávání",
                        NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.enableLights(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT);

        String text;
        if ("reconnecting".equals(state)) {
            text = "Spojení přerušeno, zkouším znovu…";
        } else if ("resync".equals(state)) {
            text = "Srovnávám zpoždění…";
        } else if (currentTitle.length() > 0) {
            text = currentTitle;
        } else if ("loading".equals(state)) {
            text = "Připojuji…";
        } else {
            text = "";
        }

        // tlacitko Zastavit primo v notifikaci
        Intent stopIntent = new Intent(this, PlayerService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(currentName.length() > 0 ? currentName : "Free Radio")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Zastavit", stopPi);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setVisibility(Notification.VISIBILITY_SECRET);
        }
        return b.build();
    }

    private void updateNotification() {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    @Override
    public void onDestroy() {
        stopPlayback();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
