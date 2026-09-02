package com.fadcam.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Durable local catalog restricted to FadCam-originated channels. */
public final class ChannelStore {
    private static final String PREFS = "tv_east_channels";
    private static final String KEY_CHANNELS = "channels";
    private static final String SOURCE_FADCAM = "fadcam";

    public static final class Channel {
        public final String id;
        public final String name;
        public final String owner;
        public final String url;
        public final boolean featured;
        public final String source;

        public Channel(String id, String name, String owner, String url, boolean featured) {
            this(id, name, owner, url, featured, SOURCE_FADCAM);
        }

        public Channel(String id, String name, String owner, String url, boolean featured, String source) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.url = url;
            this.featured = featured;
            this.source = source == null ? "" : source.trim().toLowerCase();
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("owner", owner);
                o.put("url", url);
                o.put("featured", featured);
                o.put("source", source);
            } catch (Exception ignored) { }
            return o;
        }

        static Channel fromJson(JSONObject o) {
            return new Channel(
                    o.optString("id", ""),
                    o.optString("name", "TV East Channel"),
                    o.optString("owner", "FadCam creator"),
                    o.optString("url", ""),
                    o.optBoolean("featured", false),
                    o.optString("source", "legacy"));
        }
    }

    private final SharedPreferences prefs;
    public ChannelStore(Context context) { prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public List<Channel> load() {
        ArrayList<Channel> result = new ArrayList<>();
        String raw = prefs.getString(KEY_CHANNELS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                Channel c = Channel.fromJson(array.getJSONObject(i));
                if (!c.url.isEmpty() && SOURCE_FADCAM.equals(c.source)) result.add(c);
            }
            // Persist the filtered set so legacy IPTV records are removed from the device.
            if (array.length() != result.size()) save(result);
        } catch (Exception ignored) { }
        return result;
    }

    public void upsert(Channel channel) {
        if (channel == null || channel.url.isEmpty() || !SOURCE_FADCAM.equals(channel.source)) return;
        ArrayList<Channel> all = new ArrayList<>(load());
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(channel.id) || all.get(i).url.equals(channel.url)) {
                all.set(i, channel); replaced = true; break;
            }
        }
        if (!replaced) all.add(channel);
        save(all);
    }

    public void remove(String id) {
        ArrayList<Channel> all = new ArrayList<>(load());
        all.removeIf(c -> c.id.equals(id));
        save(all);
    }

    private void save(List<Channel> channels) {
        JSONArray array = new JSONArray();
        for (Channel c : channels) if (SOURCE_FADCAM.equals(c.source)) array.put(c.toJson());
        prefs.edit().putString(KEY_CHANNELS, array.toString()).apply();
    }
}
