// EMA Lightning'i tarayıcıda çalıştırır: harfler -> süreler -> gizli ses -> 48 kHz ses.
// Her şey bu cihazda olur; model dosyaları bir kez indirilir ve önbellekte kalır.
// onnxruntime-web 1.30.0, sitenin kendisinde barınıyor (CDN'e bağımlı değil)
const ORT_BASE = new URL("ort/", self.location.href).href;
importScripts(ORT_BASE + "ort.wasm.min.js", "normalize.js");

const MAX_WORD_FRAMES = 250, MAX_FRAMES = 3000;
let meta, stoi, sText, sSound, sDecode;

async function fetchModel(url, onProgress) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(url + " indirilemedi (" + res.status + ")");
  const total = +res.headers.get("content-length") || 0;
  if (!res.body || !total) return new Uint8Array(await res.arrayBuffer());
  const reader = res.body.getReader(), parts = [];
  let got = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    parts.push(value); got += value.length;
    onProgress(got, total);
  }
  const out = new Uint8Array(got);
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}

async function init(base) {
  ort.env.wasm.wasmPaths = ORT_BASE;
  // Uygulama içinde (Android WebView) SharedArrayBuffer genelde izolasyon olmadan da var
  const canThreads = self.crossOriginIsolated || typeof SharedArrayBuffer !== "undefined";
  ort.env.wasm.numThreads = canThreads ? Math.min(4, navigator.hardwareConcurrency || 2) : 1;
  meta = await (await fetch(base + "meta.json")).json();
  stoi = new Map(meta.vocab.map((c, i) => [c, i]));
  const names = ["text", "sound", "decoder"];
  const sizes = meta.sizes || {};
  const totalAll = names.reduce((a, n) => a + (sizes[n] || 0), 0);
  let doneAll = 0;
  const sessions = [];
  for (const n of names) {
    const buf = await fetchModel(base + n + ".onnx", (got, total) => {
      postMessage({ type: "progress", got: doneAll + got, total: totalAll || total });
    });
    doneAll += buf.length;
    sessions.push(await ort.InferenceSession.create(buf, { executionProviders: ["wasm"], graphOptimizationLevel: "all" }));
  }
  [sText, sSound, sDecode] = sessions;
  postMessage({ type: "ready", threads: ort.env.wasm.numThreads });
}

// Tohumlu normal dağılımlı gürültü
function gaussian(n, seed) {
  let s = seed >>> 0 || 1;
  const rnd = () => { s ^= s << 13; s >>>= 0; s ^= s >>> 17; s ^= s << 5; s >>>= 0; return (s + 0.5) / 4294967296; };
  const out = new Float32Array(n);
  for (let i = 0; i < n; i += 2) {
    const u = rnd(), v = rnd(), r = Math.sqrt(-2 * Math.log(u));
    out[i] = r * Math.cos(2 * Math.PI * v);
    if (i + 1 < n) out[i + 1] = r * Math.sin(2 * Math.PI * v);
  }
  return out;
}

const i64 = arr => BigInt64Array.from(arr, x => BigInt(x));

async function synth(text, speed, seed) {
  const t0 = performance.now();
  const chars = [...text];
  const L = chars.length;
  const ids = chars.map(c => stoi.get(c) ?? 1);
  const starts = [];
  chars.forEach((c, i) => { if (c !== " " && (i === 0 || chars[i - 1] === " ")) starts.push(i); });
  if (!starts.length) starts.push(0);
  const bounds = [0, ...starts.slice(1), L];
  const cw = [], wstart = [];
  for (let w = 0; w + 1 < bounds.length; w++) {
    for (let i = bounds[w]; i < bounds[w + 1]; i++) { cw.push(w); wstart.push(bounds[w]); }
  }
  const nWords = bounds.length - 1;

  const tx = await sText.run({ ids: new ort.Tensor("int64", i64(ids), [1, L]) });
  const h = tx.h, durRaw = tx.dur.data;
  const dur = new Float32Array(L);
  for (let i = 0; i < L; i++) dur[i] = durRaw[i] / speed;

  const counts = new Array(nWords).fill(0);
  for (let i = 0; i < L; i++) counts[cw[i]] += dur[i];
  for (let w = 0; w < nWords; w++) counts[w] = Math.min(MAX_WORD_FRAMES, Math.max(1, Math.round(counts[w])));
  let T = Math.min(MAX_FRAMES, counts.reduce((a, b) => a + b, 0));
  const fw = new Array(T), fp = new Float32Array(T);
  let f = 0;
  const wordFrames = [];
  for (let w = 0; w < nWords && f < T; w++) {
    wordFrames.push([f, Math.min(T, f + counts[w])]);
    for (let k = 0; k < counts[w] && f < T; k++, f++) { fw[f] = w; fp[f] = k / counts[w]; }
  }
  const S = meta.times.length, D = meta.latent_dim;
  const sd = await sSound.run({
    h,
    dur: new ort.Tensor("float32", dur, [1, L]),
    cw: new ort.Tensor("int64", i64(cw), [1, L]),
    wstart: new ort.Tensor("int64", i64(wstart), [1, L]),
    fw: new ort.Tensor("int64", i64(fw), [1, T]),
    fp: new ort.Tensor("float32", fp, [1, T]),
    noise: new ort.Tensor("float32", gaussian(S * T * D, seed), [1, S, T, D]),
  });
  const t1 = performance.now();
  const dec = await sDecode.run({ z: sd.latents });
  const audio = dec.audio.data.slice(0, T * meta.hop);
  const t2 = performance.now();
  const sec = audio.length / meta.sample_rate;
  return { audio, words: wordFrames.map(([a, b]) => [a / 25, b / 25]), stats: { sec, think: t1 - t0, decode: t2 - t1, rtf: (t2 - t0) / 1000 / sec } };
}

let queue = Promise.resolve(), gen = 0;
function prepare(raw) {
  const t = EmaText.alphabet(EmaText.spoken(raw), meta.vocab);
  return /[a-zçğıöşü]/.test(t) ? EmaText.finish(t) : "";
}
onmessage = e => {
  const m = e.data;
  if (m.type === "gen") { gen = m.gen; return; }
  if (m.type === "init") {
    init(m.base).catch(err => postMessage({ type: "error", message: String(err && err.message || err) }));
  } else if (m.type === "synth") {
    queue = queue.then(async () => {
      if ((m.gen || 0) < gen) { postMessage({ type: "error", id: m.id, message: "stale" }); return; }
      try {
        const text = prepare(m.text);
        const r = text ? await synth(text, m.speed, m.seed)
          : { audio: new Float32Array(4800), words: [], stats: { sec: 0.1, think: 0, decode: 0, rtf: 0 } };
        postMessage({ type: "audio", id: m.id, audio: r.audio, words: r.words, stats: r.stats }, [r.audio.buffer]);
      } catch (err) {
        postMessage({ type: "error", id: m.id, message: String(err && err.message || err) });
      }
    });
  }
};
