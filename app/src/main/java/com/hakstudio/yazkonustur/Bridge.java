package com.hakstudio.yazkonustur;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sayfadaki JavaScript'in çağırdığı yerel işlevler (window.YKNative). */
public class Bridge {
    final MainActivity a;
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final ExecutorService ema = Executors.newSingleThreadExecutor();
    EmaEngine engine;
    volatile int emaGen;
    final Map<String, byte[]> emaOut = new ConcurrentHashMap<>();
    TextToSpeech tts;
    boolean ttsReady;
    OutputStream exportOut;
    Uri exportUri;
    int exportCb;
    String exportName;
    long exportBytes;

    Bridge(MainActivity a) {
        this.a = a;
    }

    @JavascriptInterface
    public String info() {
        try {
            return new JSONObject()
                    .put("pro", a.pro.isPro())
                    .put("price", a.pro.price())
                    .put("debug", BuildConfig.DEBUG)
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void ready() {
        a.runOnUiThread(a::onPageReady);
    }

    /* ---------- EMA sesi, yerel ONNX Runtime ile (çok çekirdekli) ---------- */
    @JavascriptInterface
    public void emaInit() {
        ema.execute(() -> {
            try {
                if (engine == null) {
                    EmaEngine e = new EmaEngine();
                    e.load(a);
                    engine = e;
                }
                a.js("YKApp.emaEvent(" + new JSONObject().put("type", "ready").put("threads", engine.threads).put("vocab", engine.vocab) + ")");
            } catch (Throwable t) {
                a.js("YKApp.emaEvent({type:'error',message:" + JSONObject.quote(String.valueOf(t.getMessage())) + "})");
            }
        });
    }

    @JavascriptInterface
    public void emaGen(int gen) {
        emaGen = gen;
    }

    @JavascriptInterface
    public void emaSynth(int id, int gen, String text, double seed) {
        ema.execute(() -> {
            if (gen < emaGen) {
                a.js("YKApp.emaEvent({type:'error',id:" + id + ",message:'stale'})");
                return;
            }
            try {
                EmaEngine.Result r = engine.synth(text, (long) seed);
                ByteBuffer b = ByteBuffer.allocate(r.audio.length * 4).order(ByteOrder.LITTLE_ENDIAN);
                b.asFloatBuffer().put(r.audio);
                emaOut.put(String.valueOf(id), b.array());
                a.js("YKApp.emaEvent(" + new JSONObject().put("type", "audio").put("id", id).put("url", "/ema/" + id).put("sec", r.sec).put("ms", r.ms) + ")");
            } catch (Throwable t) {
                a.js("YKApp.emaEvent({type:'error',id:" + id + ",message:" + JSONObject.quote(String.valueOf(t.getMessage())) + "})");
            }
        });
    }

    /** Üretilen ses sayfaya /ema/<id> adresinden bir kez verilir. */
    byte[] takeEma(String id) {
        return emaOut.remove(id);
    }

    /* ---------- Satın alma ---------- */
    @JavascriptInterface
    public void buyPro() {
        a.runOnUiThread(a.pro::buy);
    }

    @JavascriptInterface
    public void restorePro() {
        a.runOnUiThread(() -> a.pro.restore(true));
    }

    @JavascriptInterface
    public void debugTogglePro() {
        if (BuildConfig.DEBUG) a.runOnUiThread(a.pro::debugToggle);
    }

    /* ---------- Oynatma bildirimi ---------- */
    @JavascriptInterface
    public void media(String json) {
        a.runOnUiThread(() -> {
            try {
                JSONObject o = new JSONObject(json);
                String st = o.optString("state");
                if ("idle".equals(st)) {
                    PlaybackService.stop(a);
                    return;
                }
                if ("playing".equals(st)) a.askNotificationPermission();
                PlaybackService.update(a, "playing".equals(st), o.optString("title"), o.optString("text"), o.optInt("pos"), o.optInt("total"));
            } catch (Exception ignored) {
            }
        });
    }

    @JavascriptInterface
    public void keepScreen(boolean on) {
        a.keepScreen(on);
    }

    @JavascriptInterface
    public void setBars(String color, boolean dark) {
        a.setBars(color, dark);
    }

    @JavascriptInterface
    public String clipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData c = cm.getPrimaryClip();
            if (c == null || c.getItemCount() == 0) return "";
            CharSequence t = c.getItemAt(0).coerceToText(a);
            return t == null ? "" : t.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public void openUrl(String url) {
        a.runOnUiThread(() -> {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
            }
        });
    }

    /* ---------- Web sayfası indirme (CORS olmadan, doğrudan telefondan) ---------- */
    static final Pattern META_CHARSET = Pattern.compile("<meta[^>]+charset=[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE);

    @JavascriptInterface
    public void fetchUrl(String url, int cb) {
        io.execute(() -> {
            JSONObject r = new JSONObject();
            try {
                String cur = url;
                HttpURLConnection c = null;
                for (int hop = 0; hop < 6; hop++) {
                    c = (HttpURLConnection) new URL(cur).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36");
                    c.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8");
                    c.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.6");
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                        cur = new URL(new URL(cur), c.getHeaderField("Location")).toString();
                        c.disconnect();
                        continue;
                    }
                    if (code >= 400) throw new Exception("sunucu " + code + " döndü");
                    break;
                }
                byte[] body = readAll(c.getInputStream(), 8 * 1024 * 1024);
                String ct = c.getContentType() == null ? "" : c.getContentType();
                String cs = null;
                Matcher m = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(ct);
                if (m.find()) cs = m.group(1);
                if (cs == null) {
                    Matcher mm = META_CHARSET.matcher(new String(body, 0, Math.min(body.length, 4096), "ISO-8859-1"));
                    if (mm.find()) cs = mm.group(1);
                }
                Charset charset;
                try {
                    charset = Charset.forName(cs == null ? "UTF-8" : cs);
                } catch (Exception e) {
                    charset = Charset.forName("UTF-8");
                }
                if (ct.contains("pdf")) throw new Exception("bağlantı bir PDF, önce indirip PDF olarak aç");
                r.put("ok", true).put("url", cur).put("html", new String(body, charset));
            } catch (Exception e) {
                try {
                    r.put("ok", false).put("error", e.getMessage() == null ? e.toString() : e.getMessage());
                } catch (Exception ignored) {
                }
            }
            a.js("YKApp.cb(" + cb + "," + JSONObject.quote(r.toString()) + ")");
        });
    }

    static byte[] readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
            if (total > max) break;
        }
        in.close();
        return out.toByteArray();
    }

    /* ---------- MP3 kaydetme: önce konum seçilir, sonra sayfa parça parça yazar ---------- */
    @JavascriptInterface
    public void exportStart(String name, int cb) {
        exportCb = cb;
        exportName = name;
        exportBytes = 0;
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10 ve üzeri: izin istemeden doğrudan Müzik/Yaz Konuştur klasörüne yaz
            String res = "no";
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg");
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Music/Yaz Konuştur");
                v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
                Uri u = a.getContentResolver().insert(android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                if (u != null) {
                    exportOut = a.getContentResolver().openOutputStream(u, "w");
                    exportUri = u;
                    res = exportOut != null ? "ok" : "no";
                }
            } catch (Exception ignored) {
            }
            a.js("YKApp.cb(" + cb + ",'" + res + "')");
            return;
        }
        a.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/mpeg");
            i.putExtra(Intent.EXTRA_TITLE, name);
            try {
                a.startActivityForResult(i, MainActivity.REQ_EXPORT);
            } catch (Exception e) {
                a.js("YKApp.cb(" + cb + ",'no')");
            }
        });
    }

    void onExportPicked(Uri u) {
        String res = "no";
        if (u != null) {
            try {
                try {
                    a.getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                exportOut = a.getContentResolver().openOutputStream(u, "w");
                exportUri = u;
                res = exportOut != null ? "ok" : "no";
            } catch (Exception ignored) {
            }
        }
        a.js("YKApp.cb(" + exportCb + ",'" + res + "')");
    }

    @JavascriptInterface
    public void exportWrite(String b64) {
        try {
            if (exportOut != null) {
                byte[] d = Base64.decode(b64, Base64.DEFAULT);
                exportOut.write(d);
                exportBytes += d.length;
            }
        } catch (Exception ignored) {
        }
    }

    /** Kaydı bitirir. Başarılıysa kaydın adresini döner, kayıtlar listesine ekler. */
    @JavascriptInterface
    public String exportEnd(boolean ok, String title, double sec) {
        try {
            if (exportOut != null) exportOut.close();
        } catch (Exception ignored) {
            ok = false;
        }
        exportOut = null;
        Uri u = exportUri;
        exportUri = null;
        if (u == null) return "";
        if (!ok) {
            deleteUri(u);
            return "";
        }
        if (Build.VERSION.SDK_INT >= 29 && "media".equals(u.getAuthority())) {
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                a.getContentResolver().update(u, v, null, null);
            } catch (Exception ignored) {
            }
        }
        try {
            JSONArray list = recsRaw();
            JSONArray out = new JSONArray().put(new JSONObject()
                    .put("uri", u.toString()).put("name", exportName).put("title", title)
                    .put("sec", sec).put("size", exportBytes).put("date", System.currentTimeMillis()));
            for (int i = 0; i < list.length(); i++) out.put(list.get(i));
            a.getSharedPreferences("yk", Context.MODE_PRIVATE).edit().putString("recs", out.toString()).apply();
        } catch (Exception ignored) {
        }
        return u.toString();
    }

    /* ---------- Kaydedilen sesler ---------- */
    JSONArray recsRaw() {
        try {
            return new JSONArray(a.getSharedPreferences("yk", Context.MODE_PRIVATE).getString("recs", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    boolean exists(Uri u) {
        try (android.os.ParcelFileDescriptor fd = a.getContentResolver().openFileDescriptor(u, "r")) {
            return fd != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Hâlâ duran kayıtlar; telefondan silinmiş olanlar listeden de düşer. */
    @JavascriptInterface
    public String recList() {
        JSONArray list = recsRaw(), keep = new JSONArray();
        try {
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                if (exists(Uri.parse(o.getString("uri")))) keep.put(o);
            }
            if (keep.length() != list.length())
                a.getSharedPreferences("yk", Context.MODE_PRIVATE).edit().putString("recs", keep.toString()).apply();
        } catch (Exception ignored) {
        }
        return keep.toString();
    }

    @JavascriptInterface
    public void recOpen(String uri) {
        a.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(uri), "audio/mpeg");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                a.startActivity(Intent.createChooser(i, "Şununla dinle"));
            } catch (Exception e) {
                Toast.makeText(a, "Sesi açacak bir uygulama bulunamadı", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public void recShare(String uri, String title) {
        a.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("audio/mpeg");
            i.putExtra(Intent.EXTRA_STREAM, Uri.parse(uri));
            i.putExtra(Intent.EXTRA_TITLE, title);
            i.setClipData(ClipData.newRawUri(title, Uri.parse(uri)));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                a.startActivity(Intent.createChooser(i, "Sesi paylaş"));
            } catch (Exception ignored) {
            }
        });
    }

    void deleteUri(Uri u) {
        try {
            if ("media".equals(u.getAuthority())) a.getContentResolver().delete(u, null, null);
            else android.provider.DocumentsContract.deleteDocument(a.getContentResolver(), u);
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public boolean recDelete(String uri) {
        Uri u = Uri.parse(uri);
        deleteUri(u);
        boolean gone = !exists(u);
        if (gone) recList();
        return gone;
    }

    /* ---------- Telefonun kendi TTS motoru ---------- */
    void ensureTts(Runnable then) {
        if (tts != null) {
            if (ttsReady) then.run();
            return;
        }
        tts = new TextToSpeech(a, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                tts.setLanguage(new Locale("tr", "TR"));
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { a.js("YKApp.ttsDone(" + id + ")"); }
                    @Override public void onError(String id) { a.js("YKApp.ttsDone(" + id + ")"); }
                    @Override public void onStop(String id, boolean interrupted) { }
                });
                then.run();
            } else {
                a.runOnUiThread(() -> Toast.makeText(a, "Telefonun metin okuma motoru başlatılamadı", Toast.LENGTH_LONG).show());
            }
        });
    }

    @JavascriptInterface
    public String ttsVoices() {
        JSONArray arr = new JSONArray();
        try {
            if (tts == null) ensureTts(() -> a.js("YKApp.resume && YKApp.resume()"));
            if (ttsReady && tts.getVoices() != null) {
                for (Voice v : tts.getVoices()) {
                    if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                    arr.put(new JSONObject().put("id", v.getName()).put("name", v.getName() + (v.isNetworkConnectionRequired() ? " · çevrimiçi" : "")).put("lang", v.getLocale().toLanguageTag()));
                }
            }
        } catch (Exception ignored) {
        }
        return arr.toString();
    }

    @JavascriptInterface
    public void ttsSpeak(String text, double rate, double pitch, String voice, int id) {
        ensureTts(() -> {
            try {
                if (voice != null && !voice.isEmpty() && tts.getVoices() != null) {
                    for (Voice v : tts.getVoices()) if (v.getName().equals(voice)) { tts.setVoice(v); break; }
                } else tts.setLanguage(new Locale("tr", "TR"));
                tts.setSpeechRate((float) rate);
                tts.setPitch((float) pitch);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), String.valueOf(id));
            } catch (Exception e) {
                a.js("YKApp.ttsDone(" + id + ")");
            }
        });
    }

    @JavascriptInterface
    public void ttsStop() {
        if (tts != null) tts.stop();
    }

    void shutdown() {
        if (tts != null) tts.shutdown();
        exportEnd(false, "", 0);
        io.shutdownNow();
        ema.shutdownNow();
    }
}
