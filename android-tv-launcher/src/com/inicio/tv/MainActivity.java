package com.inicio.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pantalla de inicio: un WebView a pantalla completa con la interfaz en
 * assets/index.html. Las teclas del mando se pasan a JS con onRemote(), y
 * JS lanza apps y ajustes a través del objeto "Android".
 */
public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0D1A20);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        // La página declara un viewport de 1920 px; que el WebView la encaje en la pantalla
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d("Inicio", m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        js("window.onResumeHome && onResumeHome()");
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        String key;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: key = "left"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: key = "right"; break;
            case KeyEvent.KEYCODE_DPAD_UP: key = "up"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: key = "down"; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: key = "enter"; break;
            case KeyEvent.KEYCODE_BACK: key = "back"; break;
            default: return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) js("window.onRemote && onRemote('" + key + "')");
        return true;
    }

    private void start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private static String toBase64Png(Drawable d, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(bmp));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private class Bridge {
        /** Abre la primera app instalada de una lista de paquetes separados por comas. */
        @JavascriptInterface
        public boolean launch(String packages) {
            PackageManager pm = getPackageManager();
            for (String pkg : packages.split(",")) {
                pkg = pkg.trim();
                Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                if (i != null) {
                    final Intent intent = i;
                    runOnUiThread(() -> start(intent));
                    return true;
                }
            }
            return false;
        }

        /** Abre una pantalla de ajustes por su acción; si no existe, los ajustes generales. */
        @JavascriptInterface
        public void settings(String action) {
            runOnUiThread(() -> {
                try {
                    start(new Intent(action));
                } catch (Exception ex) {
                    start(new Intent(Settings.ACTION_SETTINGS));
                }
            });
        }

        /** Abre una actividad concreta ("paquete/clase"). */
        @JavascriptInterface
        public boolean component(String flat) {
            String[] parts = flat.split("/", 2);
            if (parts.length != 2) return false;
            Intent i = new Intent();
            i.setClassName(parts[0], parts[1].startsWith(".") ? parts[0] + parts[1] : parts[1]);
            if (getPackageManager().resolveActivity(i, 0) == null) return false;
            runOnUiThread(() -> {
                try {
                    start(i);
                } catch (Exception ex) {
                    start(new Intent(Settings.ACTION_SETTINGS));
                }
            });
            return true;
        }

        @JavascriptInterface
        public void screensaver() {
            if (!component("com.android.systemui/.Somnambulator")) settings(Settings.ACTION_DREAM_SETTINGS);
        }

        /** Lista de apps para "Todas las apps": [{label, pkg, img, wide}] */
        @JavascriptInterface
        public String apps() {
            PackageManager pm = getPackageManager();
            List<ResolveInfo> found = new ArrayList<>();
            for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                found.addAll(pm.queryIntentActivities(q, 0));
            }
            Set<String> seen = new HashSet<>();
            List<JSONObject> list = new ArrayList<>();
            for (ResolveInfo ri : found) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("label", ri.loadLabel(pm).toString());
                    o.put("pkg", pkg);
                    Drawable banner = ri.activityInfo.loadBanner(pm);
                    if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                    if (banner != null) {
                        o.put("img", toBase64Png(banner, 320, 180));
                        o.put("wide", true);
                    } else {
                        o.put("img", toBase64Png(ri.loadIcon(pm), 128, 128));
                        o.put("wide", false);
                    }
                    list.add(o);
                } catch (Exception ignored) {
                }
            }
            Collections.sort(list, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return a.optString("label").compareToIgnoreCase(b.optString("label"));
                }
            });
            return new JSONArray(list).toString();
        }

        /** Estado de red para los botones de arriba: {online, ssid, btOn, bt}. */
        @JavascriptInterface
        public String netStatus() {
            JSONObject o = new JSONObject();
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                android.net.NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
                o.put("online", ni != null && ni.isConnected());
                String ssid = "";
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null && wm.isWifiEnabled()) {
                    android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
                    if (wi != null && wi.getSSID() != null) {
                        String s = wi.getSSID().replace("\"", "");
                        if (!s.isEmpty() && !s.equalsIgnoreCase("<unknown ssid>") && !s.equals("0x")) ssid = s;
                    }
                }
                o.put("ssid", ssid);
                String bt = "";
                android.bluetooth.BluetoothAdapter ba = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                boolean btOn = ba != null && ba.isEnabled();
                o.put("btOn", btOn);
                if (btOn) {
                    // isConnected() está oculto en el SDK; se llama por reflexión
                    for (android.bluetooth.BluetoothDevice d : ba.getBondedDevices()) {
                        try {
                            java.lang.reflect.Method m = d.getClass().getMethod("isConnected");
                            if (Boolean.TRUE.equals(m.invoke(d))) { bt = d.getName(); break; }
                        } catch (Exception ignored) {}
                    }
                }
                o.put("bt", bt);
            } catch (Exception e) {
                try { o.put("err", e.toString()); } catch (Exception ignored) {}
            }
            return o.toString();
        }
    }
}
