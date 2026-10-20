package cz.budik.radio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Jedna rozhlasova stanice. Muze mit vic variant streamu
 * (ruzny kodek / bitrate), mezi kterymi jde prepinat.
 */
public class Station {

    /** Jedna varianta streamu - konkretni kvalita. */
    public static class Variant {
        public String url;
        public String codec;
        public int bitrate;

        public Variant(String url, String codec, int bitrate) {
            this.url = url;
            this.codec = codec == null ? "" : codec;
            this.bitrate = bitrate;
        }

        /** Napr. "MP3 · 128 kbps" */
        public String label() {
            StringBuilder sb = new StringBuilder();
            if (codec.length() > 0) sb.append(codec.toUpperCase());
            if (bitrate > 0) {
                if (sb.length() > 0) sb.append(" \u00b7 ");
                sb.append(bitrate).append(" kbps");
            }
            return sb.length() == 0 ? "neznámá kvalita" : sb.toString();
        }
    }

    public String name;
    public String favicon;
    public final List<Variant> variants = new ArrayList<Variant>();
    /** Ktera varianta je vybrana. */
    public int selected = 0;
    /** true = stanici pridal uzivatel rucne, neprijde z databaze. */
    public boolean custom = false;

    public Station(String name, String favicon) {
        this.name = name;
        this.favicon = favicon == null ? "" : favicon;
    }

    /** Jednoducha stanice s jednim streamem. */
    public Station(String name, String url, String favicon, String codec, int bitrate) {
        this(name, favicon);
        variants.add(new Variant(url, codec, bitrate));
    }

    public Variant current() {
        if (variants.isEmpty()) return new Variant("", "", 0);
        if (selected < 0 || selected >= variants.size()) selected = 0;
        return variants.get(selected);
    }

    public String url() {
        return current().url;
    }

    /** Vybere variantu podle URL. Vraci true, kdyz se nasla. */
    public boolean selectByUrl(String url) {
        for (int i = 0; i < variants.size(); i++) {
            if (variants.get(i).url.equals(url)) {
                selected = i;
                return true;
            }
        }
        return false;
    }

    /** Popisek pod nazvem stanice v seznamu. */
    public String subtitle() {
        String s = current().label();
        if (variants.size() > 1) {
            s += "  (" + variants.size() + " variant)";
        }
        return s;
    }

    // ---- ukladani ----

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("favicon", favicon);
        o.put("selected", selected);
        o.put("custom", custom);
        JSONArray arr = new JSONArray();
        for (Variant v : variants) {
            JSONObject vo = new JSONObject();
            vo.put("url", v.url);
            vo.put("codec", v.codec);
            vo.put("bitrate", v.bitrate);
            arr.put(vo);
        }
        o.put("variants", arr);
        return o;
    }

    public static Station fromJson(JSONObject o) {
        Station s = new Station(o.optString("name", "?"), o.optString("favicon", ""));
        JSONArray arr = o.optJSONArray("variants");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject vo = arr.optJSONObject(i);
                if (vo == null) continue;
                s.variants.add(new Variant(
                        vo.optString("url", ""),
                        vo.optString("codec", ""),
                        vo.optInt("bitrate", 0)));
            }
        }
        // zpetna kompatibilita se starsim formatem
        if (s.variants.isEmpty()) {
            String u = o.optString("url", "");
            if (u.length() > 0) {
                s.variants.add(new Variant(u,
                        o.optString("codec", ""), o.optInt("bitrate", 0)));
            }
        }
        s.selected = o.optInt("selected", 0);
        s.custom = o.optBoolean("custom", false);
        return s;
    }

    @Override
    public String toString() {
        return name;
    }
}
