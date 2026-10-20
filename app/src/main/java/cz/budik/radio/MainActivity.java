package cz.budik.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements WebServer.Handler {

    public static final String PREFS = "radiobudik";
    public static final String KEY_LAST_NAME = "last_name";
    public static final String KEY_LAST_URL = "last_url";
    public static final String KEY_LAST_LOGO = "last_logo";
    public static final String KEY_AUTOSTART = "autostart";
    public static final String KEY_IDLE_SEC = "idle_sec";
    public static final String KEY_QUALITY_PREFIX = "q_";
    public static final String KEY_FAVS = "favs";
    public static final String KEY_ONLY_FAVS = "only_favs";
    /** Jak casto se samo prepojit, aby se srovnalo zpozdeni. Minuty, 0 = nikdy. */
    public static final String KEY_RESYNC_MIN = "resync_min";
    public static final String KEY_UPDATE_URL = "update_url";
    public static final String KEY_UPDATE_AUTO = "update_auto";
    public static final String KEY_UPDATE_LAST = "update_last";

    /**
     * Adresa JSON s informacemi o nejnovejsi verzi.
     * Kdo si aplikaci forkne, prepise si to na svuj ucet - jinak by mu
     * budik nabizel cizi aktualizace (s jinym podpisem, ktere nepujdou
     * nainstalovat). Zastupne slovo ZMENME kontrolu uplne vypne.
     */
    public static final String DEFAULT_UPDATE_URL =
            "https://raw.githubusercontent.com/Blacker91/radio-budik/main/verze.json";

    /** Automaticka kontrola nejvyse jednou za 24 hodin. */
    private static final long UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /** Po jake dobe necinnosti se prepnout na hodiny. 0 = nikdy. */
    private static final int DEFAULT_IDLE_SEC = 60;

    public static final int DEFAULT_RESYNC_MIN = 60;

    private static final int[] RESYNC_VALUES = {0, 15, 30, 60, 120, 240};
    private static final String[] RESYNC_LABELS = {
            "Nikdy", "každých 15 minut", "každou půlhodinu", "každou hodinu",
            "každé 2 hodiny", "každé 4 hodiny"};

    private static final int[] IDLE_VALUES = {0, 15, 30, 60, 120, 300, 600};
    private static final String[] IDLE_LABELS = {
            "Nikdy", "15 sekund", "30 sekund", "1 minuta",
            "2 minuty", "5 minut", "10 minut"};

    // obrazovky
    private static final int SCREEN_LIST = 0;
    private static final int SCREEN_NOW = 1;
    private static final int SCREEN_CLOCK = 2;

    private View viewList, viewNow, viewClock;
    private ListView list;
    private ImageView imgBg;
    private TextView tvListClock, tvNpClock, tvNpStation, tvNpTitle;
    private TextView tvListStation, tvListTitle;
    private View boxNowPlaying;
    private TextView tvBigClock, tvBigDate, tvBigStation, tvBigTitle;
    private View clockBottom;
    private Button btnStop, btnRefresh, btnSettings, btnBack, btnListStop, btnPlayPause;
    private Button btnSearch, btnSearchClose;
    private View searchBar;
    private EditText etSearch;
    /** Aktuální hledaný výraz, prázdný = nefiltruje se. */
    private String query = "";
    /** Uživatel si seznam otevřel sám - nepřepínat ho zpátky na přehrávání. */
    private boolean userOpenedList = false;

    /** Vsechny nactene stanice. */
    private final List<Station> allStations = new ArrayList<Station>();
    /** Stanice pridane rucne uzivatelem. */
    private final List<Station> customStations = new ArrayList<Station>();
    /** Co je videt v seznamu (po serazeni a pripadnem filtru). */
    private final List<Station> stations = new ArrayList<Station>();
    /** Nazvy oblibenych stanic v poradi, jak je chce uzivatel mit. */
    private final List<String> favOrder = new ArrayList<String>();
    private StationAdapter adapter;
    private WebServer web;

    private int screen = SCREEN_LIST;
    /** Kam se vratit, kdyz uzivatel klepne na hodiny. */
    private int screenBeforeClock = SCREEN_LIST;

    private Station playing = null;
    private String currentTitle = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat fmtTime = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat fmtDate =
            new SimpleDateFormat("EEEE d. MMMM", new Locale("cs", "CZ"));

    // ---- zivotni cyklus ----

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        viewList = findViewById(R.id.viewList);
        viewNow = findViewById(R.id.viewNowPlaying);
        viewClock = findViewById(R.id.viewClock);

        list = (ListView) findViewById(R.id.list);
        imgBg = (ImageView) findViewById(R.id.imgBg);

        tvListClock = (TextView) findViewById(R.id.tvListClock);
        tvListStation = (TextView) findViewById(R.id.tvListStation);
        tvListTitle = (TextView) findViewById(R.id.tvListTitle);
        boxNowPlaying = findViewById(R.id.boxNowPlaying);
        tvNpClock = (TextView) findViewById(R.id.tvNpClock);
        tvNpStation = (TextView) findViewById(R.id.tvNpStation);
        tvNpTitle = (TextView) findViewById(R.id.tvNpTitle);
        tvBigClock = (TextView) findViewById(R.id.tvBigClock);
        tvBigDate = (TextView) findViewById(R.id.tvBigDate);
        tvBigStation = (TextView) findViewById(R.id.tvBigStation);
        tvBigTitle = (TextView) findViewById(R.id.tvBigTitle);
        clockBottom = findViewById(R.id.clockBottom);

        btnStop = (Button) findViewById(R.id.btnStop);
        btnRefresh = (Button) findViewById(R.id.btnRefresh);
        btnSettings = (Button) findViewById(R.id.btnSettings);
        btnBack = (Button) findViewById(R.id.btnBack);
        btnListStop = (Button) findViewById(R.id.btnListStop);
        btnPlayPause = (Button) findViewById(R.id.btnPlayPause);
        btnSearch = (Button) findViewById(R.id.btnSearch);
        btnSearchClose = (Button) findViewById(R.id.btnSearchClose);
        searchBar = findViewById(R.id.searchBar);
        etSearch = (EditText) findViewById(R.id.etSearch);

        // marquee u nazvu skladby
        tvNpTitle.setSelected(true);
        tvListTitle.setSelected(true);
        tvBigTitle.setSelected(true);

        adapter = new StationAdapter();
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Station st = stations.get(pos);
                if (searchBar.getVisibility() == View.VISIBLE) closeSearch();
                startStation(st);
            }
        });

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                showStationMenu(stations.get(pos));
                return true;
            }
        });

        // hlavni tlacitko: kdyz hraje -> zastavit, kdyz nehraje -> pustit posledni
        btnPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (playing != null) {
                    stopPlayback();
                } else {
                    Station last = lastStation();
                    if (last != null) startStation(last);
                }
            }
        });

        View.OnClickListener stopListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPlayback();
                userOpenedList = true;
                showScreen(SCREEN_LIST);
            }
        };
        btnStop.setOnClickListener(stopListener);
        btnListStop.setOnClickListener(stopListener);

        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                userOpenedList = true;
                showScreen(SCREEN_LIST);
            }
        });

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshStations(true);
            }
        });

        // klepnutí na "co právě hraje" v horní liště vrátí zpátky
        // na obrazovku přehrávání, bez přerušení streamu
        View.OnClickListener backToPlaying = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (playing != null) {
                    userOpenedList = false;
                    showScreen(SCREEN_NOW);
                }
            }
        };
        // obsluha i na textech uvnitř - jinak by dotyk skončil na nich
        boxNowPlaying.setOnClickListener(backToPlaying);
        tvListStation.setOnClickListener(backToPlaying);
        tvListTitle.setOnClickListener(backToPlaying);

        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (searchBar.getVisibility() == View.VISIBLE) {
                    closeSearch();
                } else {
                    searchBar.setVisibility(View.VISIBLE);
                    etSearch.requestFocus();
                    InputMethodManager im = (InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
                    if (im != null) im.showSoftInput(etSearch, 0);
                }
            }
        });

        btnSearchClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSearch();
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override
            public void afterTextChanged(Editable e) {
                query = e.toString().trim();
                applyOrder();
                resetIdle();
            }
        });

        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });

        // klepnuti na hodiny = zpet
        viewClock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showScreen(screenBeforeClock);
            }
        });

        loadFavs();
        customStations.clear();
        customStations.addAll(StationStore.loadCustom(this));
        allStations.clear();
        allStations.addAll(customStations);
        allStations.addAll(StationStore.load(this));
        restoreQualities();
        applyOrder();
        if (allStations.isEmpty()) refreshStations(false);

        updateListHeader();
        updatePlayButton();
        showScreen(SCREEN_LIST);
        tickClock();

        // webové ovládání z domácí sítě
        web = new WebServer(this);
        web.start();

        maybeAutoCheckUpdate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(stateReceiver, new IntentFilter(PlayerService.BROADCAST_STATE));

        // sluzba mohla hrat uz pred spustenim aplikace (napr. autostart
        // po zapnuti, nebo prezila prepnuti do pozadi) - zeptame se ji
        Intent q = new Intent(this, PlayerService.class);
        q.setAction(PlayerService.ACTION_QUERY);
        startService(q);

        resetIdle();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
        handler.removeCallbacks(idleRunnable);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.stop();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (searchBar.getVisibility() == View.VISIBLE && screen == SCREEN_LIST) {
            closeSearch();
        } else if (screen == SCREEN_CLOCK) {
            showScreen(screenBeforeClock);
        } else if (screen == SCREEN_NOW) {
            showScreen(SCREEN_LIST);
        } else {
            super.onBackPressed();
        }
    }

    private void stopPlayback() {
        Intent i = new Intent(this, PlayerService.class);
        i.setAction(PlayerService.ACTION_STOP);
        startService(i);
        playing = null;
        currentTitle = "";
        updateListHeader();
        updatePlayButton();
    }

    /** Naposledy poslouchana stanice - pro tlacitko play. */
    private Station lastStation() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String url = p.getString(KEY_LAST_URL, "");
        if (url.length() == 0) return null;
        Station s = findStation(url);
        if (s != null) return s;
        return new Station(p.getString(KEY_LAST_NAME, "Rádio"), url,
                p.getString(KEY_LAST_LOGO, ""), "", 0);
    }

    /** Prepne vzhled hlavniho tlacitka podle toho, jestli hraje. */
    private void updatePlayButton() {
        if (playing != null) {
            btnPlayPause.setText("\u25A0");            // stop
            btnPlayPause.setBackgroundColor(Color.parseColor("#CC3A5A6A"));
        } else {
            btnPlayPause.setText("\u25B6");            // play
            btnPlayPause.setBackgroundColor(Color.parseColor("#CC2A2A38"));
        }
    }

    /** Horni pruh seznamu: co hraje + tlacitko stop. */
    private void updateListHeader() {
        if (playing == null) {
            tvListStation.setText("Stanice");
            tvListStation.setTextColor(Color.parseColor("#8A8A99"));
            tvListTitle.setVisibility(View.GONE);
            btnListStop.setVisibility(View.GONE);
            boxNowPlaying.setBackgroundColor(Color.TRANSPARENT);
        } else {
            // "‹" napoví, že se dá klepnout a vrátit se k přehrávání
            tvListStation.setText("\u203A " + playing.name);
            tvListStation.setTextColor(Color.parseColor("#4FC3F7"));
            btnListStop.setVisibility(View.VISIBLE);
            boxNowPlaying.setBackgroundColor(Color.parseColor("#22FFFFFF"));
            if (currentTitle.length() > 0) {
                tvListTitle.setText(currentTitle);
                tvListTitle.setVisibility(View.VISIBLE);
            } else {
                tvListTitle.setVisibility(View.GONE);
            }
        }
    }

    // ---- prepinani obrazovek ----

    private void showScreen(int s) {
        screen = s;
        viewList.setVisibility(s == SCREEN_LIST ? View.VISIBLE : View.GONE);
        viewNow.setVisibility(s == SCREEN_NOW ? View.VISIBLE : View.GONE);
        viewClock.setVisibility(s == SCREEN_CLOCK ? View.VISIBLE : View.GONE);

        if (s == SCREEN_CLOCK) updateClockScreen();
        updateClockViews();
        resetIdle();
    }

    // ---- casovac necinnosti ----

    private final Runnable idleRunnable = new Runnable() {
        @Override
        public void run() {
            if (screen != SCREEN_CLOCK) {
                screenBeforeClock = (playing != null) ? SCREEN_NOW : SCREEN_LIST;
                showScreen(SCREEN_CLOCK);
            }
        }
    };

    private void resetIdle() {
        handler.removeCallbacks(idleRunnable);
        int sec = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_IDLE_SEC, DEFAULT_IDLE_SEC);
        if (sec > 0 && screen != SCREEN_CLOCK) {
            handler.postDelayed(idleRunnable, sec * 1000L);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) resetIdle();
        return super.dispatchTouchEvent(ev);
    }

    // ---- hodiny ----

    private void updateClockViews() {
        Date now = new Date();
        String t = fmtTime.format(now);
        tvListClock.setText(t);
        tvNpClock.setText(t);
        tvBigClock.setText(t);

        String d = fmtDate.format(now);
        if (d.length() > 0) d = d.substring(0, 1).toUpperCase() + d.substring(1);
        tvBigDate.setText(d);
    }

    /** Spodní část spořiče - když nic nehraje, schová se a hodiny se roztáhnou. */
    private void updateClockScreen() {
        if (playing == null) {
            clockBottom.setVisibility(View.GONE);
            return;
        }
        clockBottom.setVisibility(View.VISIBLE);
        tvBigStation.setVisibility(View.VISIBLE);
        tvBigStation.setText(playing.name);

        if (currentTitle.length() > 0) {
            tvBigTitle.setVisibility(View.VISIBLE);
            tvBigTitle.setText(currentTitle);
        } else {
            tvBigTitle.setVisibility(View.GONE);
        }
    }

    private void tickClock() {
        updateClockViews();
        long delay = 60000 - (System.currentTimeMillis() % 60000);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                tickClock();
            }
        }, delay + 200);
    }

    // ---- stanice ----

    private void startStation(Station s) {
        Intent i = new Intent(this, PlayerService.class);
        i.setAction(PlayerService.ACTION_PLAY);
        i.putExtra(PlayerService.EXTRA_NAME, s.name);
        i.putExtra(PlayerService.EXTRA_URL, s.url());
        i.putExtra(PlayerService.EXTRA_LOGO, s.favicon == null ? "" : s.favicon);
        startService(i);

        playing = s;
        currentTitle = "";

        tvNpStation.setText(s.name);
        tvNpTitle.setText("Připojuji…");
        loadBackground(s);
        updateListHeader();
        updatePlayButton();

        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putString(KEY_LAST_NAME, s.name);
        e.putString(KEY_LAST_URL, s.url());
        e.putString(KEY_LAST_LOGO, s.favicon == null ? "" : s.favicon);
        e.apply();

        userOpenedList = false;
        showScreen(SCREEN_NOW);
    }

    private void loadBackground(final Station s) {
        // hned ukazeme nahradu, at obrazovka neni prazdna
        try {
            imgBg.setImageBitmap(LogoCache.placeholderFor(s.name));
        } catch (Throwable t) {
            imgBg.setImageDrawable(null);
        }

        if (s.favicon == null || s.favicon.length() == 0) return;

        final String want = s.favicon;
        LogoCache.load(this, want, new LogoCache.Callback() {
            @Override
            public void onLogo(String url, Bitmap bitmap) {
                // mezitim uz mohl uzivatel prepnout na jinou stanici
                if (playing == null || !want.equals(playing.favicon)) return;
                // kdyz se logo nepovedlo, zustane nahrada
                if (bitmap != null) imgBg.setImageBitmap(bitmap);
            }
        });
    }

    private void closeSearch() {
        query = "";
        etSearch.setText("");
        searchBar.setVisibility(View.GONE);
        InputMethodManager im = (InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
        applyOrder();
    }

    // ---- oblibene a poradi ----

    private void loadFavs() {
        favOrder.clear();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FAVS, "");
        if (raw.length() == 0) return;
        try {
            org.json.JSONArray a = new org.json.JSONArray(raw);
            for (int i = 0; i < a.length(); i++) favOrder.add(a.getString(i));
        } catch (Exception ignored) {}
    }

    private void saveFavs() {
        org.json.JSONArray a = new org.json.JSONArray();
        for (String n : favOrder) a.put(n);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_FAVS, a.toString()).apply();
    }

    private boolean isFav(Station s) {
        return favOrder.contains(s.name);
    }

    private boolean onlyFavs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_ONLY_FAVS, false);
    }

    /**
     * Poskláda viditelný seznam: nejdřív oblíbené v uživatelově pořadí,
     * pak zbytek tak, jak přišel z databáze (podle popularity).
     */
    private void applyOrder() {
        stations.clear();

        for (String name : favOrder) {
            for (Station s : allStations) {
                if (s.name.equals(name) && matchesQuery(s)) {
                    stations.add(s);
                    break;
                }
            }
        }
        // při hledání se filtr "jen oblíbené" ignoruje - hledá se ve všem
        if (!onlyFavs() || query.length() > 0) {
            for (Station s : allStations) {
                if (!favOrder.contains(s.name) && matchesQuery(s)) stations.add(s);
            }
        }
        adapter.notifyDataSetChanged();
    }

    /** Hledá se bez ohledu na velikost písmen i diakritiku. */
    private boolean matchesQuery(Station s) {
        if (query.length() == 0) return true;
        return normalize(s.name).contains(normalize(query));
    }

    /** "Rádio Impuls" -> "radio impuls", ať se najde i bez háčků. */
    private static String normalize(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        n = n.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return n.toLowerCase();
    }

    private void moveFav(Station s, int delta) {
        int i = favOrder.indexOf(s.name);
        if (i < 0) return;
        int j = i + delta;
        if (j < 0 || j >= favOrder.size()) return;
        String tmp = favOrder.get(i);
        favOrder.set(i, favOrder.get(j));
        favOrder.set(j, tmp);
        saveFavs();
        applyOrder();
    }

    /** Dlouhy stisk na stanici - co s ni chces delat. */
    private void showStationMenu(final Station s) {
        final boolean fav = isFav(s);
        final int favIdx = favOrder.indexOf(s.name);

        final List<String> items = new ArrayList<String>();
        items.add("Přehrát");
        items.add("Kvalita streamu (" + s.variants.size() + ")");
        items.add(fav ? "Odebrat z oblíbených" : "Přidat mezi oblíbené");
        if (fav && favIdx > 0) items.add("Posunout nahoru");
        if (fav && favIdx < favOrder.size() - 1) items.add("Posunout dolů");
        items.add("Spouštět po zapnutí");
        if (s.custom) {
            items.add("Upravit stanici");
            items.add("Odstranit stanici");
        }

        new AlertDialog.Builder(this)
                .setTitle((fav ? "★ " : "") + s.name)
                .setItems(items.toArray(new String[items.size()]),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                String chosen = items.get(which);
                                if (chosen.startsWith("Přehrát")) {
                                    startStation(s);
                                } else if (chosen.startsWith("Kvalita")) {
                                    askQuality(s);
                                } else if (chosen.startsWith("Přidat")) {
                                    favOrder.add(s.name);
                                    saveFavs();
                                    applyOrder();
                                    toast("Přidáno mezi oblíbené");
                                } else if (chosen.startsWith("Odebrat")) {
                                    favOrder.remove(s.name);
                                    saveFavs();
                                    applyOrder();
                                    toast("Odebráno z oblíbených");
                                } else if (chosen.startsWith("Posunout nahoru")) {
                                    moveFav(s, -1);
                                } else if (chosen.startsWith("Posunout dolů")) {
                                    moveFav(s, 1);
                                } else if (chosen.startsWith("Upravit")) {
                                    showAddStation(s);
                                } else if (chosen.startsWith("Odstranit stanici")) {
                                    deleteCustom(s);
                                } else {
                                    askAutostart(s);
                                }
                            }
                        })
                .show();
    }

    /** Vyber kvality streamu u stanice, ktera jich nabizi vic. */
    private void askQuality(final Station s) {
        final String[] labels = new String[s.variants.size()];
        for (int i = 0; i < s.variants.size(); i++) {
            labels[i] = s.variants.get(i).label();
        }

        new AlertDialog.Builder(this)
                .setTitle(s.name + " – kvalita")
                .setSingleChoiceItems(labels, s.selected,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                s.selected = which;
                                saveQuality(s);
                                adapter.notifyDataSetChanged();
                                d.dismiss();

                                // hraje-li prave tahle stanice, prepnout hned
                                if (playing != null && playing.name.equals(s.name)) {
                                    startStation(s);
                                } else {
                                    toast("Nastaveno: " + labels[which]);
                                }
                            }
                        })
                .setNegativeButton("Zavřít", null)
                .show();
    }

    /** Vybranou kvalitu si pamatujeme napric spustenimi. */
    private void saveQuality(Station s) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_QUALITY_PREFIX + s.name, s.url())
                .apply();
    }

    /** Obnovi drive vybrane kvality po nacteni seznamu. */
    private void restoreQualities() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        for (Station s : allStations) {
            String url = p.getString(KEY_QUALITY_PREFIX + s.name, "");
            if (url.length() > 0) s.selectByUrl(url);
        }
    }

    private void askAutostart(final Station s) {
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        final boolean on = p.getBoolean(KEY_AUTOSTART, false);
        final boolean isThis = on && s.url().equals(p.getString(KEY_LAST_URL, ""));

        new AlertDialog.Builder(this)
                .setTitle(s.name)
                .setMessage(isThis
                        ? "Tato stanice se spouští po zapnutí budíku.\nChceš to vypnout?"
                        : "Spouštět tuto stanici automaticky po zapnutí budíku?")
                .setPositiveButton(isThis ? "Vypnout" : "Zapnout",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                SharedPreferences.Editor e = p.edit();
                                if (isThis) {
                                    e.putBoolean(KEY_AUTOSTART, false);
                                    toast("Autostart vypnut");
                                } else {
                                    e.putBoolean(KEY_AUTOSTART, true);
                                    e.putString(KEY_LAST_NAME, s.name);
                                    e.putString(KEY_LAST_URL, s.url());
                                    e.putString(KEY_LAST_LOGO,
                                            s.favicon == null ? "" : s.favicon);
                                    toast("Po startu se spustí " + s.name);
                                }
                                e.apply();
                            }
                        })
                .setNegativeButton("Zrušit", null)
                .show();
    }

    // ---- nastaveni ----

    private void showSettings() {
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        final boolean of = p.getBoolean(KEY_ONLY_FAVS, false);

        String[] items = {
                "Přidat vlastní stanici",
                "Zobrazit hodiny po nečinnosti",
                of ? "Zobrazit všechny stanice" : "Zobrazit jen oblíbené",
                "Srovnávat zpoždění vysílání",
                pendingUpdate != null
                        ? "Aktualizace – je nová verze " + pendingUpdate.versionName
                        : "Aktualizace",
                "O aplikaci"
        };

        new AlertDialog.Builder(this)
                .setTitle("Nastavení")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            showAddStation(null);
                        } else if (which == 1) {
                            askIdleTime();
                        } else if (which == 2) {
                            p.edit().putBoolean(KEY_ONLY_FAVS, !of).apply();
                            applyOrder();
                            toast(!of ? "Zobrazuji jen oblíbené"
                                      : "Zobrazuji všechny stanice");
                        } else if (which == 3) {
                            askResync();
                        } else if (which == 4) {
                            showUpdates();
                        } else {
                            showAbout();
                        }
                    }
                })
                .setNegativeButton("Zavřít", null)
                .show();
    }

    /**
     * Přidání nebo úprava vlastní stanice.
     * @param edit stanice k úpravě, nebo null pro novou
     */
    private void showAddStation(final Station edit) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText etName = new EditText(this);
        etName.setHint("Název stanice");
        etName.setSingleLine(true);
        box.addView(etName);

        final EditText etUrl = new EditText(this);
        etUrl.setHint("Adresa streamu (http://…)");
        etUrl.setSingleLine(true);
        etUrl.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(etUrl);

        if (edit != null) {
            etName.setText(edit.name);
            etUrl.setText(edit.url());
        }

        new AlertDialog.Builder(this)
                .setTitle(edit == null ? "Vlastní stanice" : "Upravit stanici")
                .setView(box)
                .setPositiveButton("Uložit", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = etName.getText().toString().trim();
                        String url = etUrl.getText().toString().trim();

                        if (url.length() == 0) {
                            toast("Adresa streamu je povinná");
                            return;
                        }
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            url = "http://" + url;
                        }
                        if (name.length() == 0) name = "Vlastní stanice";

                        if (edit != null) {
                            customStations.remove(edit);
                            allStations.remove(edit);
                            favOrder.remove(edit.name);
                        }

                        Station st = new Station(name, url, "", "", 0);
                        st.custom = true;

                        customStations.add(st);
                        allStations.add(0, st);
                        StationStore.saveCustom(MainActivity.this, customStations);
                        applyOrder();
                        toast(edit == null ? "Stanice přidána" : "Stanice upravena");
                    }
                })
                .setNegativeButton("Zrušit", null)
                .show();
    }

    private void deleteCustom(final Station s) {
        new AlertDialog.Builder(this)
                .setTitle(s.name)
                .setMessage("Odstranit tuto vlastní stanici?")
                .setPositiveButton("Odstranit", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (playing != null && playing.name.equals(s.name)) stopPlayback();
                        customStations.remove(s);
                        allStations.remove(s);
                        favOrder.remove(s.name);
                        saveFavs();
                        StationStore.saveCustom(MainActivity.this, customStations);
                        applyOrder();
                        toast("Stanice odstraněna");
                    }
                })
                .setNegativeButton("Zrušit", null)
                .show();
    }

    /** Informace o aplikaci - verze, datum sestavení, autor. */
    private void showAbout() {
        String verze = "?";
        try {
            verze = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}

        StringBuilder sb = new StringBuilder();
        sb.append("Verze ").append(verze);
        sb.append(" (build ").append(Updater.installedVersion(this)).append(")\n");
        sb.append("Sestaveno ").append(BuildConfig.BUILD_DATE).append("\n");
        sb.append("Autor: Václav\n\n");
        sb.append("Rádio na míru pro Xiaomi Mi Smart Clock.\n\n");
        sb.append("Seznam stanic pochází z veřejné databáze ");
        sb.append("radio-browser.info.\n");
        sb.append("Název skladby se čte z ICY metadat streamu – ");
        sb.append("stanice, které je nevysílají, ho nezobrazí.\n\n");
        String addr = WebServer.address(this);
        sb.append("Ovládání z prohlížeče:\n");
        sb.append(addr == null ? "nedostupné (není Wi-Fi)" : addr).append("\n\n");

        sb.append("Stanic v seznamu: ").append(allStations.size());
        sb.append("\nVlastních: ").append(customStations.size());
        sb.append("\nOblíbených: ").append(favOrder.size());

        new AlertDialog.Builder(this)
                .setTitle("Free Radio")
                .setMessage(sb.toString())
                .setPositiveButton("Zavřít", null)
                .show();
    }

    private void askIdleTime() {
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        int cur = p.getInt(KEY_IDLE_SEC, DEFAULT_IDLE_SEC);
        int sel = 0;
        for (int i = 0; i < IDLE_VALUES.length; i++) {
            if (IDLE_VALUES[i] == cur) { sel = i; break; }
        }

        new AlertDialog.Builder(this)
                .setTitle("Zobrazit hodiny po nečinnosti")
                .setSingleChoiceItems(IDLE_LABELS, sel,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                p.edit().putInt(KEY_IDLE_SEC, IDLE_VALUES[which]).apply();
                                d.dismiss();
                                resetIdle();
                                toast(IDLE_VALUES[which] == 0
                                        ? "Hodiny se nebudou zobrazovat automaticky"
                                        : "Nastaveno: " + IDLE_LABELS[which]);
                            }
                        })
                .setNegativeButton("Zavřít", null)
                .show();
    }

    /**
     * Jak casto se prehravac sam prepoji, aby srovnal nabrane zpozdeni
     * proti zivemu vysilani. Prerusi to zvuk asi na vterinu.
     */
    private void askResync() {
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        int cur = p.getInt(KEY_RESYNC_MIN, DEFAULT_RESYNC_MIN);
        int sel = 0;
        for (int i = 0; i < RESYNC_VALUES.length; i++) {
            if (RESYNC_VALUES[i] == cur) { sel = i; break; }
        }

        new AlertDialog.Builder(this)
                .setTitle("Srovnávat zpoždění vysílání")
                .setSingleChoiceItems(RESYNC_LABELS, sel,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                p.edit().putInt(KEY_RESYNC_MIN,
                                        RESYNC_VALUES[which]).apply();
                                d.dismiss();
                                // novy interval ma platit hned
                                Intent i = new Intent(MainActivity.this,
                                        PlayerService.class);
                                i.setAction(PlayerService.ACTION_RESYNC_PREF);
                                startService(i);
                                toast(RESYNC_VALUES[which] == 0
                                        ? "Zpoždění se nebude srovnávat"
                                        : "Srovnávám " + RESYNC_LABELS[which]);
                            }
                        })
                .setNegativeButton("Zavřít", null)
                .show();
    }

    // ---- online aktualizace ----

    /** Nalezena novejsi verze, dokud ji uzivatel nenainstaluje. */
    private Updater.Info pendingUpdate;

    private String updateUrl() {
        String u = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_UPDATE_URL, DEFAULT_UPDATE_URL);
        if (u == null) return "";
        // dokud je v adrese zastupne slovo, neni co kontrolovat
        if (u.contains("ZMENME")) return "";
        return u.trim();
    }

    private void showUpdates() {
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        final boolean auto = p.getBoolean(KEY_UPDATE_AUTO, true);

        String[] items = {
                pendingUpdate != null
                        ? "Nainstalovat verzi " + pendingUpdate.versionName
                        : "Zkontrolovat nyní",
                auto ? "Nekontrolovat automaticky" : "Kontrolovat automaticky",
                "Adresa pro aktualizace"
        };

        new AlertDialog.Builder(this)
                .setTitle("Aktualizace")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            if (pendingUpdate != null) offerUpdate(pendingUpdate);
                            else checkUpdate(true);
                        } else if (which == 1) {
                            p.edit().putBoolean(KEY_UPDATE_AUTO, !auto).apply();
                            toast(!auto
                                    ? "Budu kontrolovat jednou denně"
                                    : "Automatická kontrola vypnuta");
                        } else {
                            askUpdateUrl();
                        }
                    }
                })
                .setNegativeButton("Zavřít", null)
                .show();
    }

    private void askUpdateUrl() {
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        final EditText et = new EditText(this);
        et.setHint("https://…/verze.json");
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(p.getString(KEY_UPDATE_URL, DEFAULT_UPDATE_URL));

        new AlertDialog.Builder(this)
                .setTitle("Adresa pro aktualizace")
                .setMessage("Soubor verze.json na GitHubu nebo vlastním webu. "
                        + "Pohodlněji se vyplní z mobilu přes webové rozhraní.")
                .setView(et)
                .setPositiveButton("Uložit", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String u = et.getText().toString().trim();
                        p.edit().putString(KEY_UPDATE_URL, u).apply();
                        pendingUpdate = null;
                        toast(u.length() == 0
                                ? "Adresa smazána, aktualizace se nekontrolují"
                                : "Adresa uložena");
                    }
                })
                .setNegativeButton("Zrušit", null)
                .show();
    }

    /** Kontrola nejvyse jednou za den, a jen kdyz si to uzivatel preje. */
    private void maybeAutoCheckUpdate() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!p.getBoolean(KEY_UPDATE_AUTO, true)) return;
        if (updateUrl().length() == 0) return;

        long last = p.getLong(KEY_UPDATE_LAST, 0);
        long now = System.currentTimeMillis();
        // zaporny rozdil = hodiny se posunuly dozadu, taky duvod zkusit
        if (last != 0 && now - last > 0 && now - last < UPDATE_CHECK_INTERVAL_MS) return;

        checkUpdate(false);
    }

    /**
     * @param manual true = uzivatel klikl sam, takze mu odpovime i kdyz
     *               je vsechno v poradku. Automaticka kontrola mlci.
     */
    private void checkUpdate(final boolean manual) {
        final String url = updateUrl();
        if (url.length() == 0) {
            if (manual) {
                toast("Není nastavená adresa pro aktualizace");
                askUpdateUrl();
            }
            return;
        }
        if (manual) toast("Zjišťuji…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final Updater.Info info = Updater.check(url);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putLong(KEY_UPDATE_LAST, System.currentTimeMillis())
                                .apply();

                        if (info == null) {
                            if (manual) toast("Aktualizaci se nepodařilo zjistit. "
                                    + "Zkontroluj Wi-Fi a adresu.");
                            return;
                        }
                        if (!Updater.isNewer(MainActivity.this, info)) {
                            pendingUpdate = null;
                            if (manual) toast("Máš nejnovější verzi");
                            return;
                        }
                        pendingUpdate = info;
                        offerUpdate(info);
                    }
                });
            }
        }).start();
    }

    private void offerUpdate(final Updater.Info info) {
        StringBuilder sb = new StringBuilder();
        sb.append("Je k dispozici verze ").append(info.versionName).append(".\n\n");
        if (info.novinky.length() > 0) {
            sb.append(info.novinky).append("\n\n");
        }
        sb.append("Nastavení, oblíbené ani vlastní stanice se neztratí. ");
        sb.append("Během instalace se přehrávání na chvíli přeruší ");
        sb.append("a aplikace se zavře – potom ji znovu spusť.");

        new AlertDialog.Builder(this)
                .setTitle("Nová verze")
                .setMessage(sb.toString())
                .setPositiveButton("Nainstalovat",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                startUpdate(info);
                            }
                        })
                .setNegativeButton("Později", null)
                .show();
    }

    private void startUpdate(final Updater.Info info) {
        // bez tohohle povoleni nemuze zadna aplikace instalovat jine
        if (!Updater.canInstall(this)) {
            askInstallPermission(info);
            return;
        }

        toast("Stahuji aktualizaci…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.io.File apk = Updater.download(
                        MainActivity.this, info.url);
                if (apk == null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("Stažení se nepovedlo");
                        }
                    });
                    return;
                }

                // S rootem to projde potichu, bez nej vyskoci dialog systemu.
                // Zkousime root prvni - je to pohodlnejsi.
                final boolean silent = Updater.installWithRoot(apk);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (silent) {
                            // sem se uz vetsinou nedostaneme, proces konci
                            toast("Nainstalováno");
                            Updater.cleanup(MainActivity.this);
                            return;
                        }
                        if (!Updater.installWithDialog(MainActivity.this, apk)) {
                            toast("Instalaci se nepodařilo spustit");
                        }
                    }
                });
            }
        }).start();
    }

    private void askInstallPermission(final Updater.Info info) {
        new AlertDialog.Builder(this)
                .setTitle("Chybí povolení")
                .setMessage("Aby se aplikace mohla sama aktualizovat, "
                        + "musíš jí v nastavení povolit instalaci aplikací. "
                        + "Stačí to udělat jednou – pak už to platí pořád.\n\n"
                        + "Na další obrazovce zapni přepínač a vrať se zpátky.")
                .setPositiveButton("Otevřít nastavení",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                if (!Updater.openInstallSettings(MainActivity.this)) {
                                    showAppopsHint();
                                }
                            }
                        })
                .setNegativeButton("Teď ne", null)
                .show();
    }

    /** Zaloha pro pripad, ze okrajena ROM tu obrazovku vubec nema. */
    private void showAppopsHint() {
        new AlertDialog.Builder(this)
                .setTitle("Obrazovka nenalezena")
                .setMessage("Tahle ROM nastavení instalace neznámých aplikací "
                        + "nemá. Povolení se dá nastavit z počítače:\n\n"
                        + "adb shell appops set cz.budik.radio "
                        + "REQUEST_INSTALL_PACKAGES allow\n\n"
                        + "Taky to stačí udělat jen jednou.")
                .setPositiveButton("Zavřít", null)
                .show();
    }

    private void refreshStations(final boolean manual) {
        if (manual) toast("Načítám seznam…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Station> fetched = StationStore.fetch();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fetched == null || fetched.isEmpty()) {
                            toast(allStations.isEmpty()
                                    ? "Seznam se nepodařilo načíst. Zkontroluj Wi-Fi."
                                    : "Aktualizace se nepovedla, používám uložený seznam.");
                            return;
                        }
                        // vlastni stanice zustavaji, obnovuje se jen databaze
                        allStations.clear();
                        allStations.addAll(customStations);
                        allStations.addAll(fetched);
                        restoreQualities();
                        applyOrder();
                        StationStore.save(MainActivity.this, fetched);
                        if (manual) toast("Načteno " + fetched.size() + " stanic");
                    }
                });
            }
        }).start();
    }

    /** Najde stanici v seznamu podle URL streamu. */
    private Station findStation(String url) {
        for (Station st : allStations) {
            if (st.selectByUrl(url)) return st;
        }
        return null;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---- stav prehravace ----

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(PlayerService.EXTRA_STATE);
            String title = intent.getStringExtra(PlayerService.EXTRA_TITLE);
            String name = intent.getStringExtra(PlayerService.EXTRA_NAME);
            String url = intent.getStringExtra(PlayerService.EXTRA_URL);
            String logo = intent.getStringExtra(PlayerService.EXTRA_LOGO);
            boolean hasMeta = intent.getBooleanExtra(PlayerService.EXTRA_HASMETA, true);

            // sluzba hraje neco, o cem aktivita nevi (prezila restart aplikace)
            if (!"idle".equals(state) && playing == null
                    && url != null && url.length() > 0) {
                playing = findStation(url);
                if (playing == null) {
                    playing = new Station(name == null ? "Rádio" : name,
                            url, logo == null ? "" : logo, "", 0);
                }
                tvNpStation.setText(playing.name);
                loadBackground(playing);
                // aplikace se právě spustila a něco už hrálo -> ukaž to.
                // Když si uživatel seznam otevřel sám, nepřepínáme mu ho.
                if (screen == SCREEN_LIST && !userOpenedList) showScreen(SCREEN_NOW);
            }

            if ("idle".equals(state)) {
                playing = null;
                currentTitle = "";
                updateListHeader();
                updatePlayButton();
                if (screen == SCREEN_NOW) showScreen(SCREEN_LIST);
                adapter.notifyDataSetChanged();
                return;
            }

            if ("error".equals(state)) {
                tvNpTitle.setText("Stanici se nepodařilo přehrát");
            } else if ("reconnecting".equals(state)) {
                tvNpTitle.setText("Spojení přerušeno, zkouším znovu…");
            } else if ("resync".equals(state)) {
                tvNpTitle.setText("Srovnávám zpoždění…");
            } else if ("loading".equals(state)) {
                tvNpTitle.setText("Připojuji…");
            } else {
                currentTitle = (title == null) ? "" : title;
                if (currentTitle.length() > 0) {
                    tvNpTitle.setText(currentTitle);
                } else if (!hasMeta) {
                    // stanice nazev skladby nevysila - rekneme to narovinu
                    tvNpTitle.setText("Stanice nevysílá název skladby");
                } else {
                    tvNpTitle.setText("Načítám název skladby…");
                }
            }
            updateListHeader();
            updatePlayButton();
            if (screen == SCREEN_CLOCK) updateClockScreen();
            adapter.notifyDataSetChanged();
        }
    };

    // ---- webové rozhraní ----
    // Volá se z vlákna serveru, proto vše, co sahá na UI,
    // posíláme přes handler na hlavní vlákno.

    @Override
    public String stateJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"playing\":").append(playing != null);
        sb.append(",\"station\":\"").append(jsonEsc(playing == null ? "" : playing.name)).append("\"");
        sb.append(",\"title\":\"").append(jsonEsc(currentTitle)).append("\"");
        sb.append(",\"playingUrl\":\"")
          .append(jsonEsc(playing == null ? "" : playing.url())).append("\"");
        sb.append(",\"stations\":[");

        List<Station> snapshot = new ArrayList<Station>(stations);
        for (int i = 0; i < snapshot.size(); i++) {
            Station st = snapshot.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(jsonEsc(st.name)).append("\"");
            sb.append(",\"sub\":\"")
              .append(jsonEsc(st.custom ? "vlastní stanice" : st.subtitle())).append("\"");
            sb.append(",\"url\":\"").append(jsonEsc(st.url())).append("\"");
            sb.append(",\"fav\":").append(favOrder.contains(st.name));
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String jsonEsc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    @Override
    public void play(final int index) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (index >= 0 && index < stations.size()) {
                    startStation(stations.get(index));
                }
            }
        });
    }

    @Override
    public void stop() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                stopPlayback();
            }
        });
    }

    @Override
    public void addStation(final String name, final String url) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                String u = url.trim();
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
                String n = name == null ? "" : name.trim();
                if (n.length() == 0) n = "Vlastní stanice";

                Station st = new Station(n, u, "", "", 0);
                st.custom = true;
                customStations.add(st);
                allStations.add(0, st);
                StationStore.saveCustom(MainActivity.this, customStations);
                applyOrder();
                toast("Přidáno z webu: " + st.name);
            }
        });
    }

    @Override
    public void toggleFav(final int index) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (index < 0 || index >= stations.size()) return;
                Station st = stations.get(index);
                if (favOrder.contains(st.name)) favOrder.remove(st.name);
                else favOrder.add(st.name);
                saveFavs();
                applyOrder();
            }
        });
    }

    @Override
    public String updateUrlForWeb() {
        String u = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_UPDATE_URL, DEFAULT_UPDATE_URL);
        // zastupne slovo uzivateli neukazujeme, at vidi prazdne pole
        if (u == null || u.contains("ZMENME")) return "";
        return u;
    }

    @Override
    public void setUpdateUrl(final String url) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_UPDATE_URL, url.trim()).apply();
                pendingUpdate = null;
                toast(url.trim().length() == 0
                        ? "Adresa aktualizací smazána"
                        : "Adresa aktualizací uložena");
            }
        });
    }

    @Override
    public void checkUpdateFromWeb() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                checkUpdate(true);
            }
        });
    }

    // ---- adapter ----

    private class StationAdapter extends ArrayAdapter<Station> {
        StationAdapter() {
            super(MainActivity.this, 0, stations);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_station, parent, false);
            }
            final Station s = stations.get(position);

            TextView n = (TextView) v.findViewById(R.id.tvName);
            TextView sub = (TextView) v.findViewById(R.id.tvSub);
            final ImageView logo = (ImageView) v.findViewById(R.id.imgLogo);

            String label = s.name;
            if (isFav(s)) label = "★ " + label;
            n.setText(label);
            // vlastní stanice pozná uživatel podle popisku
            sub.setText(s.custom ? "vlastní stanice" : s.subtitle());

            boolean active = playing != null && s.name.equals(playing.name);
            n.setTextColor(active ? Color.parseColor("#4FC3F7") : Color.WHITE);
            v.setBackgroundColor(active ? Color.parseColor("#1A2733") : Color.TRANSPARENT);

            // Logo: vzdy neco zobrazit. Kdyz stanice logo nema nebo se
            // nepodari stahnout, zustanou inicialy - radek nikdy neni prazdny.
            final String want = s.favicon == null ? "" : s.favicon;
            logo.setTag(want);

            Bitmap shown = null;
            if (want.length() > 0) shown = LogoCache.peek(want);

            if (shown == null) {
                try {
                    shown = LogoCache.placeholderFor(s.name);
                } catch (Throwable t) {
                    shown = null;   // radeji prazdno nez pad seznamu
                }
            }
            logo.setImageBitmap(shown);

            if (want.length() > 0 && LogoCache.peek(want) == null) {
                LogoCache.load(MainActivity.this, want, new LogoCache.Callback() {
                    @Override
                    public void onLogo(String url, Bitmap bitmap) {
                        // radek uz mohl byt recyklovan pro jinou stanici
                        if (bitmap != null && want.equals(logo.getTag())) {
                            logo.setImageBitmap(bitmap);
                        }
                    }
                });
            }
            return v;
        }
    }
}
