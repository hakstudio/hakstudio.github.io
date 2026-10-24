package com.hakstudio.yazkonustur;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.window.OnBackInvokedDispatcher;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tek ekranlı uygulama: arayüz assets/www içindeki sayfadır ve
 * https://appassets.androidplatform.net/ adresinden (internete çıkmadan) sunulur.
 */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String START = "https://" + HOST + "/index.html";
    static final int REQ_FILE = 11, REQ_EXPORT = 12, REQ_NOTIF = 13;

    WebView web;
    Bridge bridge;
    ProStore pro;
    boolean pageReady;
    Intent pendingIntent;
    ValueCallback<Uri[]> fileCallback;
    /** Paylaşılan dosyalar /shared/<id> adresinden sayfaya verilir. */
    final Map<String, Uri> shared = new ConcurrentHashMap<>();
    int sharedSeq;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        // Sistem çubukları ve klavye kadar iç boşluk bırak
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        root.setBackgroundColor(night ? Color.parseColor("#0E131A") : Color.parseColor("#ECEFF4"));
        setBars(night ? "#0E131A" : "#ECEFF4", night);

        pro = new ProStore(this);
        bridge = new Bridge(this);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(bridge, "YKNative");

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/shared/", path -> openShared(path))
                .addPathHandler("/", path -> openAsset(path))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                bridge.openUrl(u.toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pro.pushToPage();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                String[] types = p.getAcceptTypes();
                boolean pdfOnly = types != null && types.length > 0 && types[0].contains("pdf") && !String.join(",", types).contains("text");
                i.setType(pdfOnly ? "application/pdf" : "*/*");
                if (!pdfOnly) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "text/plain"});
                try {
                    startActivityForResult(i, REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }

        pendingIntent = getIntent();
        web.loadUrl(START);
        PlaybackService.activity = this;
    }

    WebResourceResponse openAsset(String path) {
        if (path.isEmpty()) path = "index.html";
        try {
            InputStream in = getAssets().open("www/" + path);
            WebResourceResponse r = new WebResourceResponse(mime(path), mime(path).startsWith("text/") ? "utf-8" : null, in);
            Map<String, String> h = new HashMap<>();
            // Çok çekirdekli wasm (SharedArrayBuffer) için izolasyon başlıkları
            h.put("Cross-Origin-Opener-Policy", "same-origin");
            h.put("Cross-Origin-Embedder-Policy", "require-corp");
            h.put("Cross-Origin-Resource-Policy", "same-origin");
            h.put("Cache-Control", "no-cache");
            r.setResponseHeaders(h);
            return r;
        } catch (IOException e) {
            return null;
        }
    }

    WebResourceResponse openShared(String id) {
        Uri u = shared.get(id);
        if (u == null) return null;
        try {
            InputStream in = getContentResolver().openInputStream(u);
            String type = getContentResolver().getType(u);
            WebResourceResponse r = new WebResourceResponse(type == null ? "application/octet-stream" : type, null, in);
            Map<String, String> h = new HashMap<>();
            h.put("Cross-Origin-Resource-Policy", "same-origin");
            r.setResponseHeaders(h);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    static String mime(String p) {
        String l = p.toLowerCase();
        if (l.endsWith(".html")) return "text/html";
        if (l.endsWith(".js") || l.endsWith(".mjs")) return "text/javascript";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".json")) return "application/json";
        if (l.endsWith(".wasm")) return "application/wasm";
        if (l.endsWith(".woff2")) return "font/woff2";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".svg")) return "image/svg+xml";
        if (l.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    /** Sayfa hazır olunca (YKNative.ready) bekleyen paylaşımı ilet. */
    void onPageReady() {
        pageReady = true;
        if (pendingIntent != null) {
            Intent i = pendingIntent;
            pendingIntent = null;
            handleIntent(i);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (pageReady) handleIntent(intent);
        else pendingIntent = intent;
    }

    void handleIntent(Intent i) {
        if (i == null || i.getAction() == null) return;
        try {
            String a = i.getAction();
            JSONObject d = new JSONObject();
            if (Intent.ACTION_PROCESS_TEXT.equals(a)) {
                CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
                if (t == null) return;
                d.put("type", "text").put("text", t.toString());
            } else if (Intent.ACTION_SEND.equals(a)) {
                Uri stream = i.getParcelableExtra(Intent.EXTRA_STREAM);
                CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (stream != null) {
                    putFile(d, stream, i.getType());
                } else if (t != null) {
                    d.put("type", "text").put("text", t.toString());
                    String subj = i.getStringExtra(Intent.EXTRA_SUBJECT);
                    if (subj != null) d.put("subject", subj);
                } else return;
            } else if (Intent.ACTION_VIEW.equals(a) && i.getData() != null) {
                putFile(d, i.getData(), i.getType());
            } else return;
            js("YKApp.receive(" + d + ")");
        } catch (Exception ignored) {
        }
    }

    void putFile(JSONObject d, Uri u, String type) throws Exception {
        String id = String.valueOf(++sharedSeq);
        shared.put(id, u);
        String name = "";
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        }
        if (type == null) type = getContentResolver().getType(u);
        d.put("type", "file").put("url", "/shared/" + id).put("name", name).put("mime", type == null ? "" : type);
    }

    void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    void handleBack() {
        web.evaluateJavascript("window.YKApp ? YKApp.back() : false", v -> {
            if (!"true".equals(v)) moveTaskToBack(true); // okuma arka planda sürsün
        });
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    void setBars(String color, boolean dark) {
        runOnUiThread(() -> {
            try {
                int c = parseCss(color);
                View root = findViewById(android.R.id.content);
                if (root != null && root instanceof FrameLayout && ((FrameLayout) root).getChildCount() > 0)
                    ((FrameLayout) root).getChildAt(0).setBackgroundColor(c);
                getWindow().getDecorView().setBackgroundColor(c);
                WindowInsetsControllerCompat ctl = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
                ctl.setAppearanceLightStatusBars(!dark);
                ctl.setAppearanceLightNavigationBars(!dark);
            } catch (Exception ignored) {
            }
        });
    }

    static int parseCss(String c) {
        c = c.trim();
        if (c.startsWith("rgb")) {
            String[] p = c.substring(c.indexOf('(') + 1, c.indexOf(')')).split(",");
            return Color.rgb(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        }
        return Color.parseColor(c);
    }

    void keepScreen(boolean on) {
        runOnUiThread(() -> {
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (!getSharedPreferences("yk", MODE_PRIVATE).getBoolean("askedNotif", false)) {
                getSharedPreferences("yk", MODE_PRIVATE).edit().putBoolean("askedNotif", true).apply();
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE) {
            if (fileCallback != null) {
                Uri u = res == RESULT_OK && data != null ? data.getData() : null;
                fileCallback.onReceiveValue(u == null ? null : new Uri[]{u});
                fileCallback = null;
            }
        } else if (req == REQ_EXPORT) {
            bridge.onExportPicked(res == RESULT_OK && data != null ? data.getData() : null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        pro.refresh();
        if (pageReady) js("YKApp.resume && YKApp.resume()");
    }

    // WebView'i onPause'da durdurmuyoruz: ekran kapanınca ya da uygulama arka plandayken okuma sürsün.

    @Override
    protected void onDestroy() {
        if (PlaybackService.activity == this) PlaybackService.activity = null;
        PlaybackService.stop(this);
        bridge.shutdown();
        pro.close();
        web.destroy();
        super.onDestroy();
    }
}
