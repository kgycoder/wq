package com.sync.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.StringReader;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 단어(음절) 단위 싱크 가사 — 제공처 조회 + 포맷 파서.
 *
 *  우선순위
 *    1) LRCLIB  : /api/search 결과의 `lyricsfile`(YAML, Lyricsfile 1.0) 안의 words[]
 *    2) Better Lyrics API (api.betterlyrics.org) : Apple Music 계열 TTML (음절 단위, 키 불필요)
 *    3) NetEase Cloud Music : YRC (단어 단위)
 *
 *  결과는 기존 가사 포맷과 호환된다:
 *    [{ "start":sec, "end":sec, "text":"...", "w":[["단어 ", startSec, endSec], ...] }, ...]
 *  `w` 가 없으면 기존처럼 줄 단위 가사다. 어떤 제공처에서도 못 찾으면 null 을 돌려주며,
 *  호출 측(MainActivity)이 기존 LRC 경로(LRCLIB syncedLyrics → NetEase)로 그대로 폴백한다.
 */
final class WordLyrics {

    /** MainActivity 의 기존 제목/아티스트 정제·유사도 로직을 그대로 재사용하기 위한 훅. */
    interface Hooks {
        String cleanTitle(String t);
        String cleanArtist(String c);
        String stripBrackets(String t);
        double titleSim(String a, String b);
        String readUtf8(Response r) throws IOException;
    }

    private static final String UA = "SYNCApp/1.0 (https://github.com/kgycoder/sunday-morning)";
    private static final long TOTAL_MS = 11_000;          // JS callCs 타임아웃(18s) 안쪽에서 끝낸다

    private static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "word-lyrics");
            t.setDaemon(true);
            return t;
        }
    });

    private WordLyrics() {}

    /* ───────────────────────────────────────────────────────────
       진입점
    ─────────────────────────────────────────────────────────── */
    /** 가사 + 출처(디버그 표시용: 제공처 · 매칭된 곡 · 길이). */
    static final class Result {
        final JSONArray lines; final String src;
        String prov = ""; double meta = 50, total = 0; String detail = "";
        Result(JSONArray lines, String src) { this.lines = lines; this.src = src; }
        Result tag(String prov, double meta) { this.prov = prov; this.meta = meta; return this; }
    }

    private static String fmt(double sec) {
        if (sec <= 0) return "?";
        int s = (int) Math.round(sec);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    static Future<Result> fetchAsync(final OkHttpClient base, final Hooks h,
                                     final String title, final String channel, final double dur) {
        return POOL.submit(new Callable<Result>() {
            @Override public Result call() {
                try { return fetch(base, h, title, channel, dur); }
                catch (Throwable t) { return null; }
            }
        });
    }

    static Result await(Future<Result> f, long ms) {
        try { return f.get(ms, TimeUnit.MILLISECONDS); }
        catch (Throwable t) { f.cancel(true); return null; }
    }

    private static final class Meta {
        JSONArray words; String wordSrc = ""; double wordScore = 0; String album = "", track = "", artist = "";
    }

    /* ───────────────────────────────────────────────────────────
       3개 제공처를 병렬 조회 → 후보마다 점수(매칭·길이·타이밍 품질·제공처 신뢰도) → 제공처 간 교차 검증 → 최고점 선택.
       (이전: LRCLIB lyricsfile 이 하나라도 있으면 즉시 확정 → LRCLIB 품질이 곧 최종 품질이었다)
    ─────────────────────────────────────────────────────────── */
    private static Result fetch(OkHttpClient base, final Hooks h,
                                String title, String channel, final double dur) {
        final OkHttpClient http = base.newBuilder().callTimeout(6, TimeUnit.SECONDS).build();
        final String ct = h.cleanTitle(title);
        final String ca = h.cleanArtist(channel);
        final long deadline = System.currentTimeMillis() + TOTAL_MS;
        if (ct.isEmpty()) return null;

        final Future<Meta> fl = POOL.submit(new Callable<Meta>() {
            @Override public Meta call() { return lrclib(http, h, ct, ca, dur); }
        });
        java.util.concurrent.ExecutorCompletionService<Result> ecs =
                new java.util.concurrent.ExecutorCompletionService<>(POOL);
        ecs.submit(new Callable<Result>() {
            @Override public Result call() {
                Meta m = peek(fl, deadline);
                return m != null && m.words != null ? new Result(m.words, m.wordSrc).tag("LRCLIB", m.wordScore) : null;
            }
        });
        ecs.submit(new Callable<Result>() {
            @Override public Result call() { return betterLyrics(http, h, peek(fl, deadline), ct, ca, dur); }
        });
        ecs.submit(new Callable<Result>() {
            @Override public Result call() { return netease(http, h, ct, ca, dur); }
        });

        List<Result> cands = new ArrayList<>();
        long cutoff = deadline;
        for (int pending = 3; pending > 0; pending--) {
            long left = cutoff - System.currentTimeMillis();
            if (left <= 0) break;
            try {
                Future<Result> f = ecs.poll(left, TimeUnit.MILLISECONDS);
                if (f == null) break;
                Result r = f.get();
                if (r != null) {
                    cands.add(r);
                    if (cands.size() == 1) cutoff = Math.min(deadline, System.currentTimeMillis() + 3500);  // 첫 후보 이후 나머지를 기다리는 시간
                    if (cands.size() >= 2 && hasAgreement(cands)) break;                                   // 서로 일치하는 두 후보가 있으면 충분
                }
            } catch (Throwable ignored) { }
        }
        return pick(cands, dur);
    }

    private static <T> T peek(Future<T> f, long deadline) {
        try { return f.get(Math.max(300, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS); }
        catch (Throwable t) { return null; }
    }

    /* ── 점수화 ── */
    private static boolean hasAgreement(List<Result> c) {
        for (int i = 0; i < c.size(); i++)
            for (int j = i + 1; j < c.size(); j++) {
                double d = medianDelta(c.get(i).lines, c.get(j).lines);
                if (!Double.isNaN(d) && d < 0.35) return true;
            }
        return false;
    }

    private static Result pick(List<Result> cands, double ytDur) {
        if (cands.isEmpty()) return null;
        int n = cands.size();
        double[] agree = new double[n], dur = new double[n], qual = new double[n], prior = new double[n];
        for (int i = 0; i < n; i++) {
            Result r = cands.get(i);
            dur[i]   = durScore(r.lines, ytDur);
            qual[i]  = quality(r.lines);
            prior[i] = r.prov.equals("Better Lyrics") ? 20 : r.prov.equals("NetEase") ? 10 : 0;   // Apple·NetEase 는 전문 제작 타이밍
            double best = Double.NaN;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                double d = medianDelta(r.lines, cands.get(j).lines);
                if (!Double.isNaN(d) && (Double.isNaN(best) || d < best)) best = d;
            }
            agree[i] = Double.isNaN(best) ? 0 : best < 0.35 ? 25 : best < 0.8 ? 10 : best > 1.8 ? -15 : 0;
        }
        int bi = 0; double bt = -1e9;
        StringBuilder others = new StringBuilder();
        for (int i = 0; i < n; i++) {
            Result r = cands.get(i);
            r.total = r.meta + dur[i] + qual[i] + prior[i] + agree[i];
            r.detail = String.format(java.util.Locale.US, "%s %d(매칭%d 길이%+d 품질%+d 신뢰%+d 교차%+d)",
                    r.prov, Math.round(r.total), Math.round(r.meta), Math.round(dur[i]), Math.round(qual[i]),
                    Math.round(prior[i]), Math.round(agree[i]));
            if (r.total > bt) { bt = r.total; bi = i; }
        }
        for (int i = 0; i < n; i++) others.append(i == bi ? "" : (others.length() > 0 ? " | " : "") + cands.get(i).detail);
        Result best = cands.get(bi);
        String src = best.src + "\n선택: " + best.detail + (others.length() > 0 ? "\n탈락: " + others : "\n(다른 제공처 결과 없음)");
        return new Result(best.lines, src);
    }

    private static double durScore(JSONArray lines, double ytDur) {
        try {
            if (ytDur <= 0 || lines.length() == 0) return 0;
            double lastEnd = lines.getJSONObject(lines.length() - 1).optDouble("end", 0);
            double first = lines.getJSONObject(0).optDouble("start", 0);
            double sc = 0;
            if (lastEnd > ytDur + 3) sc -= 40;                         // 영상보다 길면 다른 버전
            else if (lastEnd < ytDur * 0.55) sc -= 15;                 // 한참 모자라면 일부만 있거나 다른 버전
            else sc += 15;
            if (first > ytDur * 0.5) sc -= 20;
            return sc;
        } catch (JSONException e) { return 0; }
    }

    /** 타이밍 품질: 단어 싱크 비율, 균등 분할(가짜 단어 타이밍) 감지, 비정상 길이 */
    private static double quality(JSONArray lines) {
        try {
            int total = lines.length(), withW = 0, tested = 0, synth = 0, words = 0, tiny = 0;
            for (int i = 0; i < total; i++) {
                JSONArray w = lines.getJSONObject(i).optJSONArray("w");
                if (w == null) continue;
                withW++;
                words += w.length();
                double sum = 0, sum2 = 0; int cnt = 0;
                for (int k = 0; k < w.length(); k++) {
                    JSONArray a = w.getJSONArray(k);
                    double d = a.getDouble(2) - a.getDouble(1);
                    if (d < 0.05) tiny++;
                    int len = Math.max(1, a.getString(0).trim().length());
                    double r = d / len;
                    sum += r; sum2 += r * r; cnt++;
                }
                if (w.length() >= 4 && cnt > 0) {
                    double mean = sum / cnt, var = Math.max(0, sum2 / cnt - mean * mean);
                    tested++;
                    if (mean > 0 && Math.sqrt(var) / mean < 0.10) synth++;   // 글자 수에 정확히 비례 → 균등 분할로 만든 타이밍
                }
            }
            double q = ((total == 0 ? 0 : (double) withW / total) - 0.7) * 40;
            if (tested >= 3 && synth * 10 >= tested * 6) q -= 35;
            if (words > 0 && tiny * 10 > words * 3) q -= 10;
            return q;
        } catch (JSONException e) { return 0; }
    }

    /** 두 가사의 줄 시작 시각 차이(가까운 줄끼리)의 중앙값(초). 비교 불가면 NaN. 무작위 정렬이면 ≈1초라 0.35 미만이면 사실상 일치. */
    static double medianDelta(JSONArray a, JSONArray b) {
        try {
            int nb = b.length();
            if (a.length() < 4 || nb < 4) return Double.NaN;
            double[] sb = new double[nb];
            for (int i = 0; i < nb; i++) sb[i] = b.getJSONObject(i).optDouble("start", 0);
            List<Double> ds = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                double s = a.getJSONObject(i).optDouble("start", 0);
                int pos = Arrays.binarySearch(sb, s);
                if (pos < 0) pos = -pos - 1;
                double d = 1e9;
                if (pos < nb) d = Math.min(d, Math.abs(sb[pos] - s));
                if (pos > 0)  d = Math.min(d, Math.abs(sb[pos - 1] - s));
                if (d < 6) ds.add(d);
            }
            if (ds.size() < Math.min(a.length(), nb) * 0.5) return 6;     // 대부분 대응되는 줄이 없음 → 불일치
            java.util.Collections.sort(ds);
            return ds.get(ds.size() / 2);
        } catch (JSONException e) { return Double.NaN; }
    }

    private static <T> T get(Future<T> f, long deadline) {
        try {
            long left = Math.max(300, deadline - System.currentTimeMillis());
            return f.get(left, TimeUnit.MILLISECONDS);
        } catch (Throwable t) { f.cancel(true); return null; }
    }

    /* ───────────────────────────────────────────────────────────
       1) LRCLIB — lyricsfile(YAML)
    ─────────────────────────────────────────────────────────── */
    private static Meta lrclib(OkHttpClient http, Hooks h, String ct, String ca, double ytDur) {
        Meta meta = new Meta();
        try {
            String stripped = h.stripBrackets(ct);
            List<String> qs = new ArrayList<>();
            qs.add(ca.isEmpty() ? ct : ct + " " + ca);
            if (!ca.isEmpty()) qs.add(ct);
            if (!stripped.equals(ct) && !stripped.isEmpty()) qs.add(stripped);

            double bestScore = -1e9, bestMetaScore = -1e9;
            JSONArray bestWords = null; String bestSrc = "";
            for (String q : qs) {
                JSONArray arr = lrclibSearch(http, h, q);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.getJSONObject(i);
                    double score = scoreLrclib(h, it, ct, ca, ytDur);
                    if (score > bestMetaScore) {            // 단어 싱크가 없어도 앨범명은 Better Lyrics 질의에 쓴다
                        bestMetaScore = score;
                        meta.album  = it.optString("albumName", "");
                        meta.track  = it.optString("trackName", "");
                        meta.artist = it.optString("artistName", "");
                    }
                    String yaml = it.optString("lyricsfile", "");
                    if (yaml.isEmpty() || !yaml.contains("words")) continue;
                    if (score < 25) continue;
                    JSONArray lines = parseLyricsfile(yaml);
                    if (lines != null && score > bestScore) {
                        bestScore = score; bestWords = lines;
                        bestSrc = "LRCLIB lyricsfile · " + it.optString("trackName", "?") + " — " + it.optString("artistName", "?")
                                + " · " + fmt(it.optDouble("duration", 0)) + " (yt " + fmt(ytDur) + ") · score " + Math.round(score);
                    }
                }
                if (bestWords != null) break;               // 단어 싱크를 찾았으면 더 묻지 않는다
            }
            meta.words = validate(bestWords, ytDur);
            meta.wordSrc = bestSrc; meta.wordScore = bestScore;
        } catch (Throwable ignored) { }
        return meta;
    }

    private static JSONArray lrclibSearch(OkHttpClient http, Hooks h, String q) {
        try {
            Request req = new Request.Builder()
                    .url("https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8"))
                    .addHeader("User-Agent", UA).addHeader("Accept", "application/json").build();
            try (Response r = http.newCall(req).execute()) {
                if (r.body() == null || !r.isSuccessful()) return new JSONArray();
                Object o = new org.json.JSONTokener(h.readUtf8(r)).nextValue();
                return o instanceof JSONArray ? (JSONArray) o : new JSONArray();
            }
        } catch (Throwable t) { return new JSONArray(); }
    }

    private static double scoreLrclib(Hooks h, JSONObject it, String ct, String ca, double ytDur) {
        double s = 0, d = it.optDouble("duration", 0);
        if (ytDur > 0 && d > 0) {
            double diff = Math.abs(d - ytDur);
            s += diff <= 3 ? 50 : diff <= 10 ? 35 : diff <= 30 ? 15 : diff <= 60 ? 5 : -25;
        }
        s += h.titleSim(ct, it.optString("trackName", "")) * 30;
        s += h.titleSim(ca, it.optString("artistName", "")) * 20;
        return s;
    }

    /* ── Lyricsfile 1.0 (YAML) 파서 — 필요한 구조만 읽는 경량 파서 ── */
    private static final Pattern YKV = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*):(?:\\s+(.*))?$");

    private static final class RawLine { String text = ""; double s = Double.NaN, e = Double.NaN; List<W> words = new ArrayList<>(); }
    private static final class W { String t; double s, e; W(String t, double s, double e) { this.t = t; this.s = s; this.e = e; } }

    static JSONArray parseLyricsfile(String yaml) throws JSONException {
        String[] src = yaml.replace("\r", "").split("\n", -1);
        List<RawLine> out = new ArrayList<>();
        boolean inLines = false, inWords = false;
        int lineDash = -1, lineKey = -1;
        RawLine cur = null; W curW = null;

        for (int i = 0; i < src.length; i++) {
            String raw = src[i];
            String tr = raw.trim();
            if (tr.isEmpty() || tr.startsWith("#")) continue;
            int ind = raw.length() - raw.replaceAll("^\\s+", "").length();

            if (ind == 0 && !tr.startsWith("-")) {                 // 최상위 키
                Matcher km = YKV.matcher(tr);
                if (km.matches()) {
                    inLines = km.group(1).equals("lines");
                    if (!inLines) { cur = null; }
                }
                continue;
            }
            if (!inLines) continue;

            boolean dash = tr.startsWith("- ") || tr.equals("-");
            String body = dash ? (tr.length() > 2 ? tr.substring(2).trim() : "") : tr;
            boolean isWord = false;

            if (dash) {
                if (lineDash < 0) lineDash = ind;
                if (ind <= lineDash) {                              // 새 가사 줄
                    cur = new RawLine(); out.add(cur); inWords = false; lineKey = ind + 2;
                } else if (cur != null) {                           // 새 단어
                    inWords = true;
                    curW = new W("", Double.NaN, Double.NaN); cur.words.add(curW);
                    isWord = true;
                }
            } else if (cur != null && inWords && ind > lineKey) {
                isWord = true;
            }
            if (cur == null || body.isEmpty()) continue;

            Matcher m = YKV.matcher(body);
            if (!m.matches()) continue;
            String key = m.group(1), val = m.group(2) == null ? "" : m.group(2);

            if (val.equals("|") || val.equals(">") || val.equals("|-") || val.equals(">-")) {   // 블록 스칼라
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < src.length) {
                    String nx = src[j];
                    int ni = nx.length() - nx.replaceAll("^\\s+", "").length();
                    if (!nx.trim().isEmpty() && ni <= ind) break;
                    if (sb.length() > 0) sb.append(val.startsWith("|") ? "\n" : " ");
                    sb.append(nx.trim());
                    j++;
                }
                i = j - 1; val = "'" + sb.toString().replace("'", "''") + "'";
            }

            if (isWord && curW != null) {
                switch (key) {
                    case "text":     curW.t = yamlScalar(val); break;
                    case "start_ms": curW.s = num(val) / 1000.0; break;
                    case "end_ms":   curW.e = num(val) / 1000.0; break;
                    default: break;
                }
            } else {
                switch (key) {
                    case "text":     cur.text = yamlScalar(val); break;
                    case "start_ms": cur.s = num(val) / 1000.0; break;
                    case "end_ms":   cur.e = num(val) / 1000.0; break;
                    case "words":    inWords = !val.trim().equals("[]"); break;
                    default: break;
                }
            }
        }

        JSONArray res = new JSONArray();
        int withWords = 0;
        for (RawLine rl : out) {
            JSONObject o = buildLine(rl.text, rl.s, rl.e, rl.words);
            if (o != null) { res.put(o); if (o.has("w")) withWords++; }
        }
        sortByStart(res);
        return withWords > 0 && withWords * 10 >= res.length() * 3 ? res : null;
    }

    private static double num(String v) {
        try { return Double.parseDouble(v.trim().replaceAll("[^0-9.+\\-eE]", "")); }
        catch (Exception e) { return Double.NaN; }
    }

    private static String yamlScalar(String v) {
        v = v.trim();
        if (v.isEmpty()) return "";
        char c = v.charAt(0);
        if (c == '\'') {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < v.length(); i++) {
                char ch = v.charAt(i);
                if (ch == '\'') {
                    if (i + 1 < v.length() && v.charAt(i + 1) == '\'') { sb.append('\''); i++; continue; }
                    break;
                }
                sb.append(ch);
            }
            return sb.toString();
        }
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < v.length(); i++) {
                char ch = v.charAt(i);
                if (ch == '\\' && i + 1 < v.length()) {
                    char n = v.charAt(++i);
                    switch (n) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': break;
                        case '0': break;
                        case 'u':
                            if (i + 4 < v.length()) {
                                try { sb.append((char) Integer.parseInt(v.substring(i + 1, i + 5), 16)); i += 4; }
                                catch (Exception e) { sb.append('u'); }
                            }
                            break;
                        default: sb.append(n);
                    }
                    continue;
                }
                if (ch == '"') break;
                sb.append(ch);
            }
            return sb.toString();
        }
        int hash = v.indexOf(" #");
        return hash >= 0 ? v.substring(0, hash).trim() : v;
    }

    /* ───────────────────────────────────────────────────────────
       2) Better Lyrics API — TTML
    ─────────────────────────────────────────────────────────── */
    private static Result betterLyrics(OkHttpClient http, Hooks h, Meta m,
                                          String ct, String ca, double ytDur) {
        try {
            String song   = m != null && !m.track.isEmpty()  ? m.track  : ct;
            String artist = m != null && !m.artist.isEmpty() ? m.artist : ca;
            String album  = m != null ? m.album : "";
            if (artist.isEmpty()) return null;
            String url = "https://api.betterlyrics.org/getLyrics?s=" + URLEncoder.encode(song, "UTF-8")
                    + "&a=" + URLEncoder.encode(artist, "UTF-8")
                    + "&al=" + URLEncoder.encode(album, "UTF-8")
                    + "&d=" + (ytDur > 0 ? String.valueOf(Math.round(ytDur)) : "");
            Request req = new Request.Builder().url(url)
                    .addHeader("User-Agent", UA).addHeader("Accept", "application/json").build();
            try (Response r = http.newCall(req).execute()) {
                if (r.body() == null || !r.isSuccessful()) return null;
                JSONObject o = new JSONObject(h.readUtf8(r));
                String ttml = o.optString("ttml", "");
                if (ttml.isEmpty()) return null;
                JSONArray ln = validate(parseTtml(ttml), ytDur);
                return ln == null ? null : new Result(ln, "Better Lyrics (Apple TTML) · query: " + song + " / " + artist
                        + (album.isEmpty() ? "" : " / " + album) + " · yt " + fmt(ytDur)).tag("Better Lyrics", 60);
            }
        } catch (Throwable t) { return null; }
    }

    static JSONArray parseTtml(String ttml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Throwable ignored) { }
        Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(ttml)));
        NodeList ps = doc.getElementsByTagName("p");
        JSONArray res = new JSONArray();
        int withWords = 0;
        for (int i = 0; i < ps.getLength(); i++) {
            Element p = (Element) ps.item(i);
            double pb = ttmlTime(p.getAttribute("begin")), pe = ttmlTime(p.getAttribute("end"));
            List<W> lead = new ArrayList<>(), bg = new ArrayList<>();
            collectTtml(p, lead, bg, false);
            if (!bg.isEmpty() && !lead.isEmpty()) {                 // 배경 보컬은 리드가 끝난 뒤에 이어질 때만 같은 줄에 합친다
                double lastEnd = lead.get(lead.size() - 1).e;
                if (bg.get(0).s >= lastEnd - 0.05) {
                    appendSpace(lead.get(lead.size() - 1));
                    lead.addAll(bg);
                }
            }
            String text = lead.isEmpty() ? p.getTextContent().replaceAll("\\s+", " ").trim() : "";
            JSONObject o = buildLine(text, pb, pe, lead);
            if (o != null) { res.put(o); if (o.has("w")) withWords++; }
        }
        sortByStart(res);
        return withWords > 0 && withWords * 10 >= res.length() * 3 ? res : null;
    }

    private static void collectTtml(Node parent, List<W> lead, List<W> bg, boolean inBg) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            List<W> dst = inBg ? bg : lead;
            if (n.getNodeType() == Node.TEXT_NODE) {
                String s = n.getNodeValue();
                if (s != null && s.trim().isEmpty() && !s.isEmpty() && !dst.isEmpty()) appendSpace(dst.get(dst.size() - 1));
            } else if (n.getNodeType() == Node.ELEMENT_NODE && "span".equals(n.getNodeName())) {
                Element sp = (Element) n;
                if (sp.hasAttribute("begin")) {
                    double b = ttmlTime(sp.getAttribute("begin"));
                    double e = sp.hasAttribute("end") ? ttmlTime(sp.getAttribute("end")) : Double.NaN;
                    dst.add(new W(sp.getTextContent(), b, e));
                } else {
                    boolean isBg = inBg || "x-bg".equals(sp.getAttribute("ttm:role"));
                    collectTtml(sp, lead, bg, isBg);
                }
            }
        }
    }

    private static void appendSpace(W w) { if (!w.t.endsWith(" ") && !w.t.endsWith("\u3000")) w.t += " "; }

    static double ttmlTime(String t) {
        if (t == null || t.isEmpty()) return Double.NaN;
        try {
            t = t.trim();
            if (t.endsWith("ms")) return Double.parseDouble(t.substring(0, t.length() - 2)) / 1000.0;
            if (t.endsWith("s"))  return Double.parseDouble(t.substring(0, t.length() - 1));
            String[] p = t.split(":");
            double s = 0;
            for (String part : p) s = s * 60 + Double.parseDouble(part);
            return s;
        } catch (Exception e) { return Double.NaN; }
    }

    /* ───────────────────────────────────────────────────────────
       3) NetEase — YRC
    ─────────────────────────────────────────────────────────── */
    private static Result netease(OkHttpClient http, Hooks h, String ct, String ca, double ytDur) {
        try {
            String stripped = h.stripBrackets(ct);
            List<String> qs = new ArrayList<>();
            qs.add(ca.isEmpty() ? ct : ct + " " + ca);
            if (!stripped.equals(ct) && !stripped.isEmpty()) qs.add(stripped + (ca.isEmpty() ? "" : " " + ca));

            List<long[]> cand = new ArrayList<>();
            List<Double> sc = new ArrayList<>();
            List<String> nm = new ArrayList<>();
            for (String q : qs) {
                neteaseSearch(http, h, q, ct, ca, ytDur, cand, sc, nm);
                if (!cand.isEmpty()) break;
            }
            Integer[] idx = new Integer[cand.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            final List<Double> fs = sc;
            Arrays.sort(idx, (a, b) -> Double.compare(fs.get(b), fs.get(a)));
            double min = ytDur > 0 ? 50 : 35;
            for (int i = 0; i < Math.min(3, idx.length); i++) {
                if (sc.get(idx[i]) < min) break;
                JSONArray lines = neteaseYrc(http, h, cand.get(idx[i])[0]);
                lines = validate(lines, ytDur);
                if (lines != null) return new Result(lines, "NetEase YRC · " + nm.get(idx[i]) + " · id " + cand.get(idx[i])[0]
                        + " · score " + Math.round(sc.get(idx[i])) + " (yt " + fmt(ytDur) + ")").tag("NetEase", sc.get(idx[i]));
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void neteaseSearch(OkHttpClient http, Hooks h, String q, String ct, String ca,
                                      double ytDur, List<long[]> ids, List<Double> scores, List<String> names) {
        try {
            Request req = new Request.Builder()
                    .url("https://music.163.com/api/search/get?s=" + URLEncoder.encode(q, "UTF-8") + "&type=1&limit=8")
                    .addHeader("Referer", "https://music.163.com").addHeader("Cookie", "appver=8.0.0")
                    .addHeader("User-Agent", "Mozilla/5.0").build();
            try (Response r = http.newCall(req).execute()) {
                if (r.body() == null) return;
                JSONObject doc = new JSONObject(h.readUtf8(r));
                JSONObject res = doc.optJSONObject("result");
                JSONArray songs = res == null ? null : res.optJSONArray("songs");
                if (songs == null) return;
                for (int i = 0; i < songs.length(); i++) {
                    JSONObject s = songs.getJSONObject(i);
                    StringBuilder ab = new StringBuilder();
                    JSONArray art = s.optJSONArray("artists");
                    if (art != null) for (int j = 0; j < art.length(); j++)
                        ab.append(art.getJSONObject(j).optString("name", "")).append(' ');
                    double d = s.optDouble("duration", 0) / 1000.0;
                    double score = h.titleSim(ct, s.optString("name", "")) * 40
                                 + h.titleSim(ca, ab.toString().trim()) * 25;
                    if (ytDur > 0 && d > 0) {
                        double diff = Math.abs(d - ytDur);
                        score += diff <= 3 ? 30 : diff <= 10 ? 18 : diff <= 30 ? 8 : -15;
                    }
                    ids.add(new long[]{s.optLong("id")});
                    scores.add(score);
                    names.add(s.optString("name", "?") + " — " + ab.toString().trim() + " · " + fmt(d));
                }
            }
        } catch (Throwable ignored) { }
    }

    private static JSONArray neteaseYrc(OkHttpClient http, Hooks h, long id) {
        String[] urls = {
            "https://music.163.com/api/song/lyric?id=" + id + "&lv=1&kv=1&tv=-1&yv=1",
            "https://music.163.com/api/song/lyric/v1?id=" + id + "&lv=1&kv=1&tv=-1&yv=1"
        };
        for (String u : urls) {
            try {
                Request req = new Request.Builder().url(u)
                        .addHeader("Referer", "https://music.163.com").addHeader("Cookie", "appver=8.0.0")
                        .addHeader("User-Agent", "Mozilla/5.0").build();
                try (Response r = http.newCall(req).execute()) {
                    if (r.body() == null) continue;
                    JSONObject doc = new JSONObject(h.readUtf8(r));
                    JSONObject y = doc.optJSONObject("yrc");
                    String yrc = y == null ? "" : y.optString("lyric", "");
                    if (yrc.isEmpty()) continue;
                    JSONArray lines = parseYrc(yrc);
                    if (lines != null) return lines;
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),-?\\d+\\)");
    private static final Pattern CREDIT = Pattern.compile(
            "^\\s*(?:作词|作曲|编曲|混音|制作人|出品|录音|母带|OP|SP|厂牌|发行|监制|制作|ISRC|专辑|歌手|作詞|作曲|編曲)\\s*[：:].{0,80}$");

    static JSONArray parseYrc(String yrc) throws JSONException {
        JSONArray res = new JSONArray();
        int withWords = 0;
        for (String raw : yrc.split("\n")) {
            String ln = raw.trim();
            if (ln.isEmpty() || ln.startsWith("{")) continue;       // 상단 JSON 메타(작사/작곡) 줄 제외
            Matcher lm = YRC_LINE.matcher(ln);
            if (!lm.matches()) continue;
            double ls = Long.parseLong(lm.group(1)) / 1000.0;
            double le = ls + Long.parseLong(lm.group(2)) / 1000.0;
            String body = lm.group(3);
            Matcher wm = YRC_WORD.matcher(body);
            List<W> words = new ArrayList<>();
            int prevEnd = -1; double ws = 0, we = 0;
            while (wm.find()) {
                if (prevEnd >= 0) words.add(new W(body.substring(prevEnd, wm.start()), ws, we));
                ws = Long.parseLong(wm.group(1)) / 1000.0;
                we = ws + Long.parseLong(wm.group(2)) / 1000.0;
                prevEnd = wm.end();
            }
            if (prevEnd >= 0) words.add(new W(body.substring(prevEnd), ws, we));
            String text = words.isEmpty() ? body.trim() : "";
            if (!words.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (W w : words) sb.append(w.t);
                text = sb.toString().trim();
            }
            if (text.isEmpty() || CREDIT.matcher(text).matches()) continue;
            JSONObject o = buildLine(text, ls, le, words);
            if (o != null) { res.put(o); if (o.has("w")) withWords++; }
        }
        sortByStart(res);
        return withWords > 0 && withWords * 10 >= res.length() * 3 ? res : null;
    }

    /* ───────────────────────────────────────────────────────────
       공통: 줄/단어 정리
         · 공백만 있는 조각 → 앞 단어 뒤 공백으로 흡수
         · 문장부호 조각 → 앞(닫는 부호) 또는 뒤(여는 부호) 단어에 병합 → 하이라이트가 끊기거나 튀지 않는다
         · 시간 단조 증가/겹침 보정, 공백 없는 소스는 줄 텍스트 기준으로 공백 복원
    ─────────────────────────────────────────────────────────── */
    private static final Pattern PUNCT_ONLY = Pattern.compile("^[\\p{P}\\p{S}]+$");

    static JSONObject buildLine(String lineText, double ls, double le, List<W> raw) throws JSONException {
        List<W> ws = cleanWords(raw, lineText, le);
        String text;
        if (!ws.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (W w : ws) sb.append(w.t);
            text = sb.toString().trim();
        } else text = lineText == null ? "" : lineText.trim();
        if (text.isEmpty() || CREDIT.matcher(text).matches()) return null;

        double s = !ws.isEmpty() ? ws.get(0).s : ls;
        double e = !ws.isEmpty() ? ws.get(ws.size() - 1).e : le;
        if (!Double.isNaN(le) && !ws.isEmpty() && le > e && le - e < 1.5) e = le;   // 줄 끝 여운
        if (Double.isNaN(s)) return null;
        if (Double.isNaN(e) || e <= s) e = s + 3;

        JSONObject o = new JSONObject();
        o.put("start", round3(s));
        o.put("end", round3(e));
        o.put("text", text);
        if (ws.size() > 0) {
            JSONArray wa = new JSONArray();
            for (W w : ws) {
                JSONArray a = new JSONArray();
                a.put(w.t); a.put(round3(w.s)); a.put(round3(w.e));
                wa.put(a);
            }
            o.put("w", wa);
        }
        return o;
    }

    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }

    private static List<W> cleanWords(List<W> in, String lineText, double lineEnd) {
        List<W> out = new ArrayList<>();
        if (in == null) return out;
        // 공백이 하나도 없는 소스(일부 YAML 평문 스칼라): 줄 텍스트를 기준으로 단어 사이 공백을 먼저 복원한다
        List<W> src = new ArrayList<>();
        for (W w : in) if (w != null && w.t != null && !Double.isNaN(w.s)) src.add(new W(w.t, w.s, w.e));
        if (lineText != null && src.size() > 1 && lineText.indexOf(' ') >= 0) {
            boolean anyWs = false;
            for (W w : src) if (w.t.indexOf(' ') >= 0) { anyWs = true; break; }
            if (!anyWs) {
                boolean[] gap = new boolean[src.size()];
                int pos = 0; boolean ok = true;
                for (int i = 0; i < src.size(); i++) {
                    String c = src.get(i).t;
                    int at = c.isEmpty() ? -1 : lineText.indexOf(c, pos);
                    if (at < 0) { ok = false; break; }
                    int end = at + c.length(), p2 = end;
                    while (p2 < lineText.length() && Character.isWhitespace(lineText.charAt(p2))) p2++;
                    gap[i] = p2 > end;
                    pos = p2;
                }
                if (ok) for (int i = 0; i < src.size(); i++) if (gap[i]) src.get(i).t += " ";
            }
        }
        in = src;
        String pendingPrefix = "";
        for (W w : in) {
            if (w == null || w.t == null || Double.isNaN(w.s)) continue;
            String core = w.t.trim();
            if (core.isEmpty()) {                                   // 공백 조각
                if (!out.isEmpty()) appendSpace(out.get(out.size() - 1));
                continue;
            }
            if (PUNCT_ONLY.matcher(core).matches()) {
                W prev = out.isEmpty() ? null : out.get(out.size() - 1);
                boolean prefix = prev == null || prev.t.endsWith(" ") || prev.t.endsWith("\u3000");
                if (!prefix) {                                      // 닫는 부호: 앞 단어에 붙인다
                    prev.t = prev.t.replaceAll("\\s+$", "") + core + (w.t.endsWith(" ") ? " " : "");
                    if (!Double.isNaN(w.e) && w.e > prev.e) prev.e = w.e;
                    continue;
                }
                pendingPrefix += core;                              // 여는 부호: 다음 단어 앞에 붙인다
                continue;
            }
            String t = w.t;
            if (!pendingPrefix.isEmpty()) { t = pendingPrefix + t.replaceAll("^\\s+", ""); pendingPrefix = ""; }
            out.add(new W(t, w.s, w.e));
        }
        if (!pendingPrefix.isEmpty() && !out.isEmpty()) out.get(out.size() - 1).t += pendingPrefix;

        // 시간 보정
        for (int i = 0; i < out.size(); i++) {
            W w = out.get(i);
            if (i > 0 && w.s < out.get(i - 1).s) w.s = out.get(i - 1).s;
            if (Double.isNaN(w.e) || w.e < w.s + 0.06) {            // 종료 시각 누락/비정상 → 다음 단어 시작(없으면 줄 끝)까지
                double nx = i + 1 < out.size() ? out.get(i + 1).s : lineEnd;
                w.e = (!Double.isNaN(nx) && nx > w.s + 0.06) ? nx : w.s + 0.4;
            }
            if (i + 1 < out.size()) {
                double ns = out.get(i + 1).s;
                if (ns > w.s && w.e > ns) w.e = ns;
            }
        }
        return out;
    }

    private static void sortByStart(JSONArray arr) throws JSONException {
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) l.add(arr.getJSONObject(i));
        l.sort((a, b) -> Double.compare(a.optDouble("start"), b.optDouble("start")));
        for (int i = 0; i < l.size(); i++) arr.put(i, l.get(i));
    }

    /** 트랙 길이와 명백히 어긋나는(다른 곡/버전) 결과는 버린다. */
    private static JSONArray validate(JSONArray lines, double ytDur) {
        if (lines == null || lines.length() < 2) return null;
        try {
            double lastEnd = lines.getJSONObject(lines.length() - 1).optDouble("end", 0);
            if (ytDur > 0 && lastEnd > ytDur + 8) return null;
        } catch (JSONException e) { return null; }
        return lines;
    }
}
