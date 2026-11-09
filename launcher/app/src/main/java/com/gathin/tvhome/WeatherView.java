package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Current temperature and conditions under the date on Home, from Open-Meteo (no key, no account).
 * Location comes from res/values/weather.xml; an empty latitude turns it off. Refreshes every
 * 30 minutes while Home is visible, 5 minutes after a failure, and stays hidden until the first reading.
 */
public class WeatherView extends TextView {
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final long RETRY_MS = 5 * 60 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastOk = 0;
    private boolean loading = false;
    private boolean attached = false;

    private final Runnable tick = this::refresh;

    public WeatherView(Context c) { super(c); }
    public WeatherView(Context c, AttributeSet a) { super(c, a); }
    public WeatherView(Context c, AttributeSet a, int s) { super(c, a, s); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (getText() == null || getText().length() == 0) setVisibility(View.GONE);
        refresh();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        main.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // Home coming back after the TV slept: refresh if the reading is stale.
        if (visibility == View.VISIBLE && attached
                && SystemClock.elapsedRealtime() - lastOk > REFRESH_MS) refresh();
    }

    private void refresh() {
        main.removeCallbacks(tick);
        if (!attached || loading) return;
        final String lat = getResources().getString(R.string.weather_latitude).trim();
        final String lon = getResources().getString(R.string.weather_longitude).trim();
        if (lat.isEmpty() || lon.isEmpty()) { setVisibility(View.GONE); return; }
        loading = true;
        new Thread(() -> {
            String text = null;
            try { text = fetch(lat, lon); } catch (Exception ignored) { }
            final String result = text;
            main.post(() -> {
                loading = false;
                if (!attached) return;
                if (result != null) {
                    lastOk = SystemClock.elapsedRealtime();
                    setText(result);
                    setVisibility(View.VISIBLE);
                    main.postDelayed(tick, REFRESH_MS);
                } else {
                    main.postDelayed(tick, RETRY_MS);
                }
            });
        }, "weather").start();
    }

    private static String fetch(String lat, String lon) throws Exception {
        URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code&timezone=auto");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        try {
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            JSONObject cur = new JSONObject(out.toString("UTF-8")).getJSONObject("current");
            long temp = Math.round(cur.getDouble("temperature_2m"));
            return String.format(Locale.ROOT, "%d°  ·  %s", temp, describe(cur.getInt("weather_code")));
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather interpretation codes, as Open-Meteo returns them. */
    private static String describe(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: case 56: case 57: return "Drizzle";
            case 61: case 63: case 66: return "Rain";
            case 65: case 67: return "Heavy rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: case 81: return "Showers";
            case 82: return "Heavy showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Storm, hail";
            default: return "";
        }
    }
}
