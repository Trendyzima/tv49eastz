package com.fadcam.tv.football;

import android.os.Handler;
import android.os.Looper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tv49.com.BuildConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Native football data adapter. The endpoint is configurable for licensed/global providers. */
public final class FootballRepository {
    public interface CallbackResult { void onResult(List<FootballMatch> matches, Throwable error); }
    private final OkHttpClient client = new OkHttpClient();
    private final Handler main = new Handler(Looper.getMainLooper());

    public void loadMatches(String competition, String mode, CallbackResult callback) {
        String league = leagueCode(competition);
        String path = "standings".equalsIgnoreCase(mode) ? "standings" : "scoreboard";
        String base = BuildConfig.FOOTBALL_API_URL == null ? "" : BuildConfig.FOOTBALL_API_URL.trim();
        if (base.isEmpty()) { callback.onResult(new ArrayList<>(), new IOException("Football API is not configured")); return; }
        String url = base.replaceAll("/+$", "") + "/" + league + "/" + path;
        Request request = new Request.Builder().url(url).get().header("Accept", "application/json").build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) { main.post(() -> callback.onResult(new ArrayList<>(), e)); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) throw new IOException("Football provider HTTP " + r.code());
                    JsonObject root = JsonParser.parseString(r.body().string()).getAsJsonObject();
                    List<FootballMatch> out = "standings".equalsIgnoreCase(mode) ? parseStandings(root, competition) : parseEvents(root, competition, mode);
                    main.post(() -> callback.onResult(out, null));
                } catch (Throwable t) { main.post(() -> callback.onResult(new ArrayList<>(), t)); }
            }
        });
    }

    private List<FootballMatch> parseEvents(JsonObject root, String competition, String mode) {
        ArrayList<FootballMatch> out = new ArrayList<>();
        JsonArray events = root.has("events") && root.get("events").isJsonArray() ? root.getAsJsonArray("events") : new JsonArray();
        for (JsonElement e : events) {
            JsonObject event = e.getAsJsonObject();
            String status = event.has("status") && event.get("status").isJsonObject() ? text(event.getAsJsonObject("status"), "type", "detail", "name") : text(event, "status", "type", "state", "name");
            if ("live".equalsIgnoreCase(mode) && !isLive(status)) continue;
            if ("results".equalsIgnoreCase(mode) && isLive(status)) continue;
            JsonArray competitions = event.has("competitions") && event.get("competitions").isJsonArray() ? event.getAsJsonArray("competitions") : new JsonArray();
            if (competitions.size() == 0) continue;
            JsonObject match = competitions.get(0).getAsJsonObject();
            JsonArray competitors = match.has("competitors") && match.get("competitors").isJsonArray() ? match.getAsJsonArray("competitors") : new JsonArray();
            String home = "Home", away = "Away", hs = "-", as = "-";
            for (JsonElement c : competitors) {
                JsonObject co = c.getAsJsonObject();
                boolean isHome = co.has("homeAway") && "home".equalsIgnoreCase(co.get("homeAway").getAsString());
                String name = co.has("team") && co.get("team").isJsonObject() ? text(co.getAsJsonObject("team"), "displayName", "name") : "Team";
                String score = text(co, "score");
                if (isHome) { home = name; hs = score.isEmpty() ? "0" : score; } else { away = name; as = score.isEmpty() ? "0" : score; }
            }
            out.add(new FootballMatch(text(event, "id"), competition, home, away, hs, as, text(event, "date"), status, text(match, "status")));
        }
        return out;
    }

    private List<FootballMatch> parseStandings(JsonObject root, String competition) {
        ArrayList<FootballMatch> out = new ArrayList<>();
        JsonArray children = root.has("children") && root.get("children").isJsonArray() ? root.getAsJsonArray("children") : new JsonArray();
        for (JsonElement child : children) {
            JsonObject group = child.getAsJsonObject();
            if (!group.has("standings") || !group.get("standings").isJsonObject()) continue;
            JsonObject standings = group.getAsJsonObject("standings");
            JsonArray entries = standings.has("entries") && standings.get("entries").isJsonArray() ? standings.getAsJsonArray("entries") : new JsonArray();
            for (JsonElement item : entries) {
                JsonObject row = item.getAsJsonObject();
                String team = row.has("team") && row.get("team").isJsonObject() ? text(row.getAsJsonObject("team"), "displayName", "name") : "Team";
                String rank = text(row, "position", "rank");
                out.add(new FootballMatch(rank, competition, rank, team, "", "", "table", "", standingsSummary(row)));
            }
        }
        return out;
    }

    private String standingsSummary(JsonObject row) {
        if (!row.has("stats") || !row.get("stats").isJsonArray()) return "";
        StringBuilder s = new StringBuilder();
        for (JsonElement stat : row.getAsJsonArray("stats")) {
            JsonObject o = stat.getAsJsonObject(); String name = text(o, "name");
            if ("points".equalsIgnoreCase(name) || "wins".equalsIgnoreCase(name) || "ties".equalsIgnoreCase(name) || "losses".equalsIgnoreCase(name) || "pointDifferential".equalsIgnoreCase(name)) {
                if (s.length() > 0) s.append(" • "); s.append(name).append(" ").append(text(o, "displayValue", "value"));
            }
        }
        return s.toString();
    }

    private boolean isLive(String status) { String s = status == null ? "" : status.toLowerCase(); return s.contains("live") || s.contains("in progress") || s.contains("halftime") || s.contains("second half"); }

    private String leagueCode(String competition) {
        if (competition == null) return "eng.1";
        switch (competition) {
            case "Championship": return "eng.2";
            case "LaLiga": return "esp.1";
            case "Bundesliga": return "ger.1";
            case "Serie A": return "ita.1";
            case "Ligue 1": return "fra.1";
            case "Eredivisie": return "ned.1";
            case "Primeira Liga": return "por.1";
            case "Brazil Série A": return "bra.1";
            case "Argentina Primera": return "arg.1";
            case "Liga MX": return "mex.1";
            case "MLS": return "usa.1";
            case "UEFA Champions League": return "uefa.champions";
            case "UEFA Europa League": return "uefa.europa";
            case "Copa Libertadores": return "conmebol.libertadores";
            case "FIFA World Cup": return "fifa.world";
            default: return "eng.1";
        }
    }

    private String text(JsonObject o, String... keys) { for (String key : keys) if (o.has(key) && !o.get(key).isJsonNull()) { try { return o.get(key).getAsString(); } catch (Throwable ignored) {} } return ""; }
}
