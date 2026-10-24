// Türkçe metni modelin okuyabileceği hale getirir: sayılar, tarihler, saatler, para, birimler,
// kısaltmalar ve semboller yazıya çevrilir; sonra harfler modelin alfabesine indirgenir.
(function (root) {
  const ONES = ["", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz"];
  const TENS = ["", "on", "yirmi", "otuz", "kırk", "elli", "altmış", "yetmiş", "seksen", "doksan"];
  const SCALES = ["", "bin", "milyon", "milyar", "trilyon", "katrilyon"];
  const MONTHS = ["ocak", "şubat", "mart", "nisan", "mayıs", "haziran", "temmuz", "ağustos", "eylül", "ekim", "kasım", "aralık"];

  function under1000(n) {
    const h = Math.floor(n / 100), t = Math.floor((n % 100) / 10), o = n % 10;
    const out = [];
    if (h) out.push(h === 1 ? "yüz" : ONES[h] + " yüz");
    if (t) out.push(TENS[t]);
    if (o) out.push(ONES[o]);
    return out.join(" ");
  }

  // Tam sayı (string olarak, büyük sayılar için) -> yazı
  function intWords(s) {
    s = s.replace(/^0+(?=\d)/, "");
    if (/^0+$/.test(s)) return "sıfır";
    if (s.length > 18) return s.split("").map(d => d === "0" ? "sıfır" : ONES[+d]).join(" ");
    const groups = [];
    for (let i = s.length; i > 0; i -= 3) groups.unshift(+s.slice(Math.max(0, i - 3), i));
    const out = [];
    groups.forEach((g, i) => {
      const scale = groups.length - 1 - i;
      if (!g) return;
      if (scale === 1 && g === 1) out.push("bin");
      else out.push((under1000(g) + " " + SCALES[scale]).trim());
    });
    return out.join(" ");
  }

  // Sıra sayısı: son kelimeye ünlü uyumuna göre -(ı)ncı eki
  function ordinal(words) {
    const parts = words.split(" ");
    let w = parts.pop();
    const vowels = w.match(/[aeıioöuü]/g) || ["e"];
    const v = vowels[vowels.length - 1];
    const suf = { a: "ıncı", ı: "ıncı", e: "inci", i: "inci", o: "uncu", u: "uncu", ö: "üncü", ü: "üncü" }[v];
    w = /[aeıioöuü]$/.test(w) ? w + suf.slice(1) : w + suf;
    parts.push(w);
    return parts.join(" ");
  }

  function digitsWords(s) { // ondalık kısmı: baştaki sıfırlar tek tek, kalanı sayı olarak
    const m = s.match(/^(0*)(\d*)$/);
    const zeros = m[1].split("").map(() => "sıfır");
    return zeros.concat(m[2] ? [intWords(m[2])] : []).join(" ");
  }

  function numberWords(raw) {
    // 1.234.567 binlik ayraçlı, 3,14 ondalık
    let s = raw;
    if (/^\d{1,3}(\.\d{3})+(,\d+)?$/.test(s)) s = s.replace(/\./g, "");
    const neg = s.startsWith("-");
    if (neg) s = s.slice(1);
    let out;
    if (s.includes(",")) {
      const [a, b] = s.split(",");
      out = intWords(a) + " virgül " + digitsWords(b);
    } else if (/^\d+\.\d+$/.test(s)) {
      const [a, b] = s.split(".");
      out = intWords(a) + " nokta " + digitsWords(b);
    } else out = intWords(s.replace(/\D/g, "") || "0");
    return (neg ? "eksi " : "") + out;
  }

  const ABBR = {
    "dr": "doktor", "prof": "profesör", "doç": "doçent", "av": "avukat", "yrd": "yardımcı", "sn": "sayın",
    "bkz": "bakınız", "vb": "ve benzeri", "vs": "vesaire", "örn": "örneğin", "mah": "mahallesi", "cad": "caddesi",
    "sok": "sokak", "apt": "apartmanı", "tel": "telefon", "no": "numara", "ltd": "limited", "şti": "şirketi",
    "yy": "yüzyıl", "bşk": "başkanı", "gn": "genel", "müd": "müdürü", "sf": "sayfa", "s": "sayfa", "vd": "ve diğerleri",
    "st": "saint", "mr": "mister", "mrs": "misis", "a.ş": "anonim şirketi", "t.c": "te ce", "abd": "a be de",
    "ab": "a be", "tbmm": "te be me me", "chp": "ce ha pe", "akp": "a ka pe", "mhp": "me ha pe", "trt": "te re te",
    "ntv": "en te ve", "pc": "pe ce", "usb": "yu es bi", "tl": "lira", "km": "kilometre", "kg": "kilogram",
    "cm": "santimetre", "mm": "milimetre", "gr": "gram", "ml": "mililitre", "lt": "litre", "sa": "saat", "dk": "dakika", "sn.": "saniye"
  };
  const UNITS = {
    "km/sa": "kilometre saat", "km/s": "kilometre saat", "km": "kilometre", "m": "metre", "cm": "santimetre", "mm": "milimetre",
    "kg": "kilogram", "g": "gram", "gr": "gram", "mg": "miligram", "lt": "litre", "l": "litre", "ml": "mililitre",
    "tl": "lira", "₺": "lira", "$": "dolar", "€": "avro", "£": "sterlin", "usd": "dolar", "eur": "avro",
    "gb": "gigabayt", "mb": "megabayt", "kb": "kilobayt", "tb": "terabayt", "mhz": "megahertz", "ghz": "gigahertz",
    "kw": "kilovat", "w": "vat", "kwh": "kilovat saat", "sa": "saat", "dk": "dakika", "sn": "saniye", "°c": "derece", "°": "derece"
  };
  // Harf harf okunan kısaltmalar
  const LETTER = { a: "a", b: "be", c: "ce", ç: "çe", d: "de", e: "e", f: "fe", g: "ge", ğ: "yumuşak ge", h: "ha", ı: "ı", i: "i",
    j: "je", k: "ka", l: "le", m: "me", n: "ne", o: "o", ö: "ö", p: "pe", r: "re", s: "se", ş: "şe", t: "te", u: "u", ü: "ü",
    v: "ve", y: "ye", z: "ze", q: "ku", w: "ve", x: "iks" };

  const lowerTr = s => s.replace(/İ/g, "i").replace(/I/g, "ı").toLowerCase();

  function spoken(text) {
    let t = " " + text + " ";
    t = t.replace(/[’‘ʼ´`]/g, "'").replace(/[“”„«»]/g, '"').replace(/[–—−]/g, "-").replace(/…/g, "...");
    // Web adresleri ve e-posta
    t = t.replace(/https?:\/\/\S+|www\.\S+/gi, " bağlantı ");
    t = t.replace(/\b[\w.+-]+@[\w-]+\.[\w.]+\b/g, m => m.replace("@", " et ").replace(/\./g, " nokta "));
    // Tarih: 24.10.2026 / 24/10/2026
    t = t.replace(/\b(\d{1,2})[./](\d{1,2})[./](\d{4})\b/g, (m, d, mo, y) =>
      +mo >= 1 && +mo <= 12 && +d >= 1 && +d <= 31 ? intWords(d) + " " + MONTHS[+mo - 1] + " " + intWords(y) : m);
    // Saat: 14:30, 09.05'te (sadece iki nokta ile güvenli)
    t = t.replace(/\b([01]?\d|2[0-3])[:.]([0-5]\d)(?!\d)'?(?=[a-zçğıöşü]|\s|[.,!?;)]|$)/gi, (m, h, mi) => m.includes(":") || m.endsWith("'") ? intWords(h) + (mi === "00" ? "" : " " + intWords(mi)) : m);
    // Yüzde
    t = t.replace(/%\s?(\d+(?:[.,]\d+)?)/g, (m, n) => "yüzde " + numberWords(n));
    // Para birimi önde: $50, ₺100
    t = t.replace(/([$€£₺])\s?(\d[\d.,]*)/g, (m, c, n) => numberWords(n.replace(/[.,]$/, "")) + " " + UNITS[c]);
    // Sayı + birim: 5 kg, 100 TL, 3,5 km/sa, 20°C
    t = t.replace(/(\d[\d.,]*)\s?(km\/sa|km\/s|kwh|°c|°|[a-zA-Z₺$€£]{1,3})(?![\p{L}])/giu, (m, n, u) => {
      const key = lowerTr(u);
      if (!UNITS[key]) return m;
      return numberWords(n.replace(/[.,]$/, "")) + " " + UNITS[key] + (/[.,]$/.test(n) ? n.slice(-1) : "");
    });
    // Sıra sayısı: "3. kat", "19. yüzyıl" (ardından küçük harfle başlayan kelime)
    t = t.replace(/\b(\d+)\.(?=\s+[a-zçğıöşü])/g, (m, n) => ordinal(intWords(n)));
    // Sayıya gelen ekler: 2023'te -> iki bin yirmi üçte
    t = t.replace(/(\d[\d.,]*\d|\d)'([a-zçğıöşü]+)/gi, (m, n, suf) => numberWords(n) + suf);
    // Aralık: 10-15 -> on ile on beş arası değil, "on on beş" doğal okunmaz; "on, on beş" yerine "on ila on beş"
    t = t.replace(/\b(\d+)-(\d+)\b/g, (m, a, b) => intWords(a) + " ila " + intWords(b));
    // Kalan sayılar
    t = t.replace(/-?\d+(?:[.,]\d+)*/g, m => " " + numberWords(m.replace(/[.,]$/, "")) + (/[.,]$/.test(m) ? m.slice(-1) : "") + " ");
    // Noktalı kısaltmalar
    t = t.replace(/(^|[\s(])([A-Za-zÇĞİÖŞÜçğıöşü]{1,4}(?:\.[A-Za-zÇĞİÖŞÜçğıöşü]{1,2})?)\.(?=\s)/g, (m, pre, w) => {
      const key = lowerTr(w);
      return ABBR[key] ? pre + ABBR[key] : m;
    });
    // Büyük harfli kısaltmalar (2-5 harf, ünsüz ağırlıklı): THY -> te ha ye
    t = t.replace(/\b([A-ZÇĞİÖŞÜ]{2,5})\b/g, (m, w) => {
      const key = lowerTr(w);
      if (ABBR[key]) return ABBR[key];
      if (/[AEIİOÖUÜ]/.test(w) && w.length > 3) return m; // NATO, ASELSAN gibi okunabilir olanlar
      return key.split("").map(c => LETTER[c] || c).join(" ");
    });
    // Semboller
    t = t.replace(/&/g, " ve ").replace(/\+/g, " artı ").replace(/=/g, " eşittir ").replace(/@/g, " et ")
      .replace(/#/g, " ").replace(/\*/g, " ").replace(/_/g, " ").replace(/[<>|\\^~{}\[\]]/g, " ");
    t = t.replace(/(\S)\/(\S)/g, "$1 bölü $2");
    return t.replace(/\s+/g, " ").trim();
  }

  function alphabet(text, vocab) {
    const set = new Set(vocab);
    let t = lowerTr(text);
    let out = "";
    for (let ch of t) {
      if (!"çğıöşü".includes(ch)) ch = ch.normalize("NFKD").replace(/[̀-ͯ]/g, "");
      out += ch && [...ch].every(c => set.has(c)) ? ch : " ";
    }
    return out.replace(/\s+/g, " ").trim();
  }

  // Modelin eğitildiği gibi her parça cümle sonu işaretiyle bitsin
  function finish(piece) {
    if (/[.!?]["')]*$/.test(piece)) return piece;
    return piece.replace(/[,;:\- ]+$/, "") + ".";
  }

  root.EmaText = { spoken, alphabet, finish, intWords, numberWords, ordinal };
})(typeof self !== "undefined" ? self : globalThis);
