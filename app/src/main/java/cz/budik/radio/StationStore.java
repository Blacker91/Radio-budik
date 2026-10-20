package cz.budik.radio;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stahuje seznam ceskych stanic z radio-browser.info a uklada ho lokalne.
 * Zadny API klic ani registrace nejsou potreba.
 */
public class StationStore {

    private static final String FILE = "stanice.json";
    /** Vlastni stanice pridane uzivatelem - obnoveni seznamu je nesmaze. */
    private static final String FILE_CUSTOM = "vlastni.json";

    /** Zrcadla API - pokud jedno nejede, zkusi se dalsi. */
    private static final String[] MIRRORS = {
            "https://de1.api.radio-browser.info",
            "https://de2.api.radio-browser.info",
            "https://nl1.api.radio-browser.info",
            "https://at1.api.radio-browser.info"
    };

    private static final String PATH =
            "/json/stations/bycountrycodeexact/CZ"
                    + "?order=clickcount&reverse=true&hidebroken=true&limit=400";

    /** Nacte vlastni stanice pridane uzivatelem. */
    public static List<Station> loadCustom(Context ctx) {
        return readFile(ctx, FILE_CUSTOM);
    }

    /** Ulozi vlastni stanice. */
    public static void saveCustom(Context ctx, List<Station> stations) {
        writeFile(ctx, FILE_CUSTOM, stations);
    }

    /** Nacte ulozeny seznam z disku. Vraci prazdny seznam, kdyz jeste nic neni. */
    public static List<Station> load(Context ctx) {
        return readFile(ctx, FILE);
    }

    /** Ulozi seznam na disk. */
    public static void save(Context ctx, List<Station> stations) {
        writeFile(ctx, FILE, stations);
    }

    // ---- prace se soubory ----

    private static List<Station> readFile(Context ctx, String file) {
        List<Station> out = new ArrayList<Station>();
        File f = new File(ctx.getFilesDir(), file);
        if (!f.exists()) return out;
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new java.io.FileReader(f));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                out.add(Station.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            // poskozeny soubor - chovame se, jako by nebyl
        }
        return out;
    }

    private static void writeFile(Context ctx, String file, List<Station> stations) {
        try {
            JSONArray arr = new JSONArray();
            for (Station s : stations) arr.put(s.toJson());
            FileOutputStream fos = ctx.openFileOutput(file, Context.MODE_PRIVATE);
            fos.write(arr.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            // ulozeni se nepovedlo - nevadi, pristi spusteni to zkusi znovu
        }
    }

    /**
     * Stahne aktualni seznam z internetu. Vola se z vlakna na pozadi!
     * @return seznam stanic, nebo null pri chybe
     */
    public static List<Station> fetch() {
        for (String mirror : MIRRORS) {
            try {
                List<Station> r = fetchFrom(mirror + PATH);
                if (r != null && !r.isEmpty()) return r;
            } catch (Exception e) {
                // zkusime dalsi zrcadlo
            }
        }
        return null;
    }

    /** Bezne bitraty - podle nich poznáme číslo v názvu stanice. */
    /**
     * Slova, ktera na konci nazvu neznamenaji jine radio, ale jen jinak
     * pojmenovanou variantu - typicky jmeno provozovatele streamu.
     * POZOR: nesmi sem prijit slova, ktera odlisuji skutecne ruzne stanice
     * (napr. "plus", "wave", "praha", "dance").
     */
    private static final String[] NOISE_SUFFIX = {
            "actve", "streamtheworld", "radiojar", "icecast", "shoutcast",
            "official", "hd"};

    private static final String[] BITRATES = {
            "24", "32", "40", "48", "56", "64", "80", "96", "112",
            "128", "160", "192", "224", "256", "320"};

    /**
     * Stanice maji varianty casto pojmenovane ruzne:
     * "Evropa 2", "Evropa 2 128", "Evropa 2 (AAC)", "Evropa 2 MP3 64k".
     * Tahle metoda z nich udela stejny klic, aby se daly seskupit.
     */
    static String groupKey(String name) {
        String s = name.toLowerCase().trim();

        // pryc obsah zavorek, kdyz uvnitr je kodek nebo bitrate
        s = s.replaceAll("\\((?:[^()]*(?:aac|aacp|mp3|ogg|opus|flac|hls|kbps|kbit|hq|lq|hi|lo)[^()]*)\\)", " ");
        s = s.replaceAll("\\[(?:[^\\[\\]]*(?:aac|aacp|mp3|ogg|opus|flac|hls|kbps|kbit|hq|lq)[^\\[\\]]*)\\]", " ");

        // "128k", "128 kbps", "64kbit"
        s = s.replaceAll("\\b\\d{2,3}\\s*k(?:b(?:ps|it)?)?\\b", " ");

        // samostatna slova s kodekem / oznacenim kvality
        s = s.replaceAll("\\b(aacp|aac|mp3|ogg|opus|flac|hls|stream|high|low|quality|hq|lq)\\b", " ");

        s = s.replaceAll("[\\s\\-_/|,.]+", " ").trim();

        // cislo na konci, ktere vypada jako bitrate
        for (String br : BITRATES) {
            if (s.endsWith(" " + br)) {
                s = s.substring(0, s.length() - br.length() - 1).trim();
                break;
            }
        }

        // koncovky typu "- Actve", "- Radio", "FM", "live" - to nejsou
        // jina radia, jen jinak pojmenovana varianta tehoz streamu
        boolean changed = true;
        while (changed && s.indexOf(' ') > 0) {
            changed = false;
            int sp = s.lastIndexOf(' ');
            String last = s.substring(sp + 1);
            for (String noise : NOISE_SUFFIX) {
                if (last.equals(noise)) {
                    s = s.substring(0, sp).trim();
                    changed = true;
                    break;
                }
            }
        }

        return s.length() == 0 ? name.toLowerCase().trim() : s;
    }

    /**
     * Nazev pro zobrazeni - bez pomocnych pripon typu "- mp3", "- aac",
     * "- Actve", "128 kbps". Zustane cisté "Evropa 2".
     */
    static String displayName(String name) {
        String s = name.trim();
        s = s.replaceAll("(?i)\\s*[\\-–—|]\\s*(aacp|aac|mp3|ogg|opus|flac|hls|actve|streamtheworld|radiojar|icecast|shoutcast|hd|official)\\s*$", "");
        s = s.replaceAll("(?i)\\s*[\\-–—|]?\\s*\\d{2,3}\\s*k(?:b(?:ps|it)?)?\\s*$", "");
        s = s.replaceAll("(?i)\\s*\\((?:[^()]*(?:aac|mp3|kbps|kbit|hq|lq)[^()]*)\\)\\s*$", "");
        s = s.trim().replaceAll("[\\-–—|,]+$", "").trim();
        return s.length() == 0 ? name.trim() : s;
    }

    private static List<Station> fetchFrom(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestProperty("User-Agent", "RadioBudik/1.0");
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);

        if (c.getResponseCode() != 200) {
            c.disconnect();
            return null;
        }

        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        c.disconnect();

        JSONArray arr = new JSONArray(sb.toString());

        // Radio-browser vraci kazdou kvalitu jako samostatny zaznam.
        // Seskupime je podle nazvu stanice do variant.
        Map<String, Station> byName = new LinkedHashMap<String, Station>();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);

            String url = o.optString("url_resolved", "");
            if (url.length() == 0) url = o.optString("url", "");
            String name = o.optString("name", "").trim();

            if (url.length() == 0 || name.length() == 0) continue;

            // HLS stream MediaPlayer na Androidu 8.1 casto nezvladne - preskocit
            if (o.optInt("hls", 0) == 1) continue;

            String key = groupKey(name);
            Station st = byName.get(key);
            if (st == null) {
                st = new Station(displayName(name), o.optString("favicon", ""));
                byName.put(key, st);
            } else {
                // logo si vezmeme z te varianty, ktera ho ma
                if (st.favicon.length() == 0) {
                    st.favicon = o.optString("favicon", "");
                }
                // zobrazujeme nejkratsi nazev - "Evropa 2" misto "Evropa 2 - aac"
                String dn = displayName(name);
                if (dn.length() < st.name.length()) {
                    st.name = dn;
                }
            }

            // stejnou adresu nepridavat dvakrat
            boolean dup = false;
            for (Station.Variant v : st.variants) {
                if (v.url.equals(url)) { dup = true; break; }
            }
            if (!dup) {
                st.variants.add(new Station.Variant(url,
                        o.optString("codec", ""), o.optInt("bitrate", 0)));
            }
        }

        // --- druhy pruchod: spojit skupiny, ktere sdileji stejnou adresu ---
        // "Evropa 2 - Actve" a "Evropa 2 - Radio" maji identicky stream,
        // takze jde bez debat o jedno a totez radio
        Map<String, Station> owner = new LinkedHashMap<String, Station>();
        List<Station> merged = new ArrayList<Station>();

        for (Station st : byName.values()) {
            Station target = null;
            for (Station.Variant v : st.variants) {
                Station o = owner.get(v.url);
                if (o != null) { target = o; break; }
            }

            if (target == null) {
                merged.add(st);
                for (Station.Variant v : st.variants) owner.put(v.url, st);
            } else {
                // pripojit varianty k jiz existujici stanici
                for (Station.Variant v : st.variants) {
                    boolean dup = false;
                    for (Station.Variant e : target.variants) {
                        if (e.url.equals(v.url)) { dup = true; break; }
                    }
                    if (!dup) {
                        target.variants.add(v);
                        owner.put(v.url, target);
                    }
                }
                if (target.favicon.length() == 0) target.favicon = st.favicon;
                if (st.name.length() < target.name.length()) target.name = st.name;
            }
        }

        // varianty seradit od nejvyssi kvality
        List<Station> out = new ArrayList<Station>();
        for (Station st : merged) {
            if (st.variants.isEmpty()) continue;
            Collections.sort(st.variants, new Comparator<Station.Variant>() {
                @Override
                public int compare(Station.Variant a, Station.Variant b) {
                    return b.bitrate - a.bitrate;
                }
            });
            out.add(st);
        }
        return out;
    }
}
