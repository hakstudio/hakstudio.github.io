package com.hakstudio.yazkonustur;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * EMA Lightning'i ONNX Runtime ile telefonun işlemcisinde, birden çok çekirdekle çalıştırır.
 * harfler -> süreler -> gizli ses -> 48 kHz ses. ema-worker.js'teki synth() ile aynı adımlar.
 */
public class EmaEngine {
    static final int MAX_WORD_FRAMES = 250, MAX_FRAMES = 3000;

    final OrtEnvironment env = OrtEnvironment.getEnvironment();
    OrtSession sText, sSound, sDecode;
    Map<String, Integer> stoi = new HashMap<>();
    int steps, latentDim, hop, sampleRate;
    public int threads;

    public JSONArray vocab;

    public void load(Context c) throws Exception {
        JSONObject meta = new JSONObject(new String(read(c, "www/model/meta.json"), "UTF-8"));
        vocab = meta.getJSONArray("vocab");
        for (int i = 0; i < vocab.length(); i++) stoi.put(vocab.getString(i), i);
        steps = meta.getJSONArray("times").length();
        latentDim = meta.getInt("latent_dim");
        hop = meta.getInt("hop");
        sampleRate = meta.getInt("sample_rate");
        threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setIntraOpNumThreads(threads);
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        sText = env.createSession(read(c, "www/model/text.onnx"), so);
        sSound = env.createSession(read(c, "www/model/sound.onnx"), so);
        sDecode = env.createSession(read(c, "www/model/decoder.onnx"), so);
    }

    static byte[] read(Context c, String path) throws Exception {
        try (InputStream in = c.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** Tohumlu normal dağılımlı gürültü (JS'teki xorshift + Box-Muller ile aynı). */
    static float[] gaussian(int n, long seed) {
        final int[] s = {(int) seed == 0 ? 1 : (int) seed};
        float[] out = new float[n];
        for (int i = 0; i < n; i += 2) {
            double u = rnd(s), v = rnd(s), r = Math.sqrt(-2 * Math.log(u));
            out[i] = (float) (r * Math.cos(2 * Math.PI * v));
            if (i + 1 < n) out[i + 1] = (float) (r * Math.sin(2 * Math.PI * v));
        }
        return out;
    }

    static double rnd(int[] st) {
        int s = st[0];
        s ^= s << 13;
        s ^= s >>> 17;
        s ^= s << 5;
        st[0] = s;
        return ((s & 0xFFFFFFFFL) + 0.5) / 4294967296.0;
    }

    public static class Result {
        public float[] audio;
        public double sec, ms;
    }

    /** text: normalize.js ile hazırlanmış, modelin alfabesindeki metin. */
    public synchronized Result synth(String text, long seed) throws Exception {
        long t0 = System.nanoTime();
        int[] cps = text.codePoints().toArray();
        int L = cps.length;
        long[] ids = new long[L];
        for (int i = 0; i < L; i++) {
            Integer id = stoi.get(new String(Character.toChars(cps[i])));
            ids[i] = id == null ? 1 : id;
        }
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < L; i++) if (cps[i] != ' ' && (i == 0 || cps[i - 1] == ' ')) starts.add(i);
        if (starts.isEmpty()) starts.add(0);
        int nWords = starts.size();
        int[] bounds = new int[nWords + 1];
        bounds[0] = 0;
        for (int w = 1; w < nWords; w++) bounds[w] = starts.get(w);
        bounds[nWords] = L;
        long[] cw = new long[L], wstart = new long[L];
        for (int w = 0; w < nWords; w++)
            for (int i = bounds[w]; i < bounds[w + 1]; i++) {
                cw[i] = w;
                wstart[i] = bounds[w];
            }

        try (OnnxTensor tIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[]{1, L});
             OrtSession.Result tx = sText.run(Map.of("ids", tIds))) {
            OnnxTensor h = (OnnxTensor) tx.get("h").get();
            FloatBuffer durBuf = ((OnnxTensor) tx.get("dur").get()).getFloatBuffer();
            float[] dur = new float[L];
            durBuf.get(dur);

            int[] counts = new int[nWords];
            double[] acc = new double[nWords];
            for (int i = 0; i < L; i++) acc[(int) cw[i]] += dur[i];
            int T = 0;
            for (int w = 0; w < nWords; w++) {
                counts[w] = (int) Math.min(MAX_WORD_FRAMES, Math.max(1, Math.round(acc[w])));
                T += counts[w];
            }
            T = Math.min(MAX_FRAMES, T);
            long[] fw = new long[T];
            float[] fp = new float[T];
            int f = 0;
            for (int w = 0; w < nWords && f < T; w++)
                for (int k = 0; k < counts[w] && f < T; k++, f++) {
                    fw[f] = w;
                    fp[f] = (float) k / counts[w];
                }

            Map<String, OnnxTensor> in = new HashMap<>();
            in.put("h", h);
            in.put("dur", OnnxTensor.createTensor(env, FloatBuffer.wrap(dur), new long[]{1, L}));
            in.put("cw", OnnxTensor.createTensor(env, LongBuffer.wrap(cw), new long[]{1, L}));
            in.put("wstart", OnnxTensor.createTensor(env, LongBuffer.wrap(wstart), new long[]{1, L}));
            in.put("fw", OnnxTensor.createTensor(env, LongBuffer.wrap(fw), new long[]{1, T}));
            in.put("fp", OnnxTensor.createTensor(env, FloatBuffer.wrap(fp), new long[]{1, T}));
            in.put("noise", OnnxTensor.createTensor(env, FloatBuffer.wrap(gaussian(steps * T * latentDim, seed)), new long[]{1, steps, T, latentDim}));
            try (OrtSession.Result sd = sSound.run(in)) {
                for (Map.Entry<String, OnnxTensor> e : in.entrySet()) if (!"h".equals(e.getKey())) e.getValue().close();
                OnnxTensor lat = (OnnxTensor) sd.get("latents").get();
                try (OrtSession.Result dec = sDecode.run(Map.of("z", lat))) {
                    FloatBuffer ab = ((OnnxTensor) dec.get("audio").get()).getFloatBuffer();
                    int n = Math.min(ab.remaining(), T * hop);
                    Result r = new Result();
                    r.audio = new float[n];
                    ab.get(r.audio, 0, n);
                    r.sec = (double) n / sampleRate;
                    r.ms = (System.nanoTime() - t0) / 1e6;
                    return r;
                }
            }
        }
    }
}
