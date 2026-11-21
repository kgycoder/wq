/* ════════════════════════════════════════════════════════════════════
   WORD-SYNC LYRICS ENGINE  (Apple Music 스타일 단어/음절 싱크)
   ────────────────────────────────────────────────────────────────────
   · 글자 내부를 따라 빛이 흐르듯 채워지는 그라디언트 스윕 (단어별 길이·타임스탬프에 따라 속도가 달라진다)
   · 진행 중인 단어는 부드럽게 들어올려지고(lift), 지나간 단어는 들어올려진 채 유지, 줄이 끝나면 내려온다
   · 오래 끄는 단어는 빛나는 글로우
   · 공백·문장부호는 Java 쪽(WordLyrics.cleanWords)에서 인접 단어에 병합되어 하이라이트가 끊기거나 튀지 않는다

   성능 설계
   · 줄 전체가 아니라 "지금 재생 중인 줄의 아직 끝나지 않은 단어 몇 개"만 매 프레임 갱신한다.
   · 값은 양자화해 바뀔 때만 style 에 쓴다 (스타일 무효화 최소화). 레이아웃(reflow)을 일으키는 속성은 쓰지 않는다.
   · rAF 루프는 "재생 중 + 단어 싱크 줄이 활성"일 때만 돈다. 일시정지·백그라운드·줄 없음 → 즉시 정지(발열/배터리).
   · 프레임이 느린 기기(평균 >26ms)에서는 자동으로 30fps 로 낮춘다.
   · 비활성 줄은 일반 텍스트(색상만)로 그려져 background-clip:text / 변형 비용이 전혀 없다.
════════════════════════════════════════════════════════════════════ */
(function () {
    'use strict';

    const CJK_RE = /[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/;   // 한글은 띄어쓰기 단위라 제외
    const FILL_STEPS = 200, LIFT_STEPS = 80, GLOW_STEPS = 20;
    const LIFT_DELAY = 0.04;                                                  // 하이라이트보다 아주 살짝 뒤에 들어올림 시작(초)
    const GLOW_TAIL = 0.5;                                                    // 단어가 끝난 뒤 글로우가 사라지는 시간(초)

    const WS = {
        line: null, idx: -1, hosts: [], segs: [], W: null, n: 0,
        P: null, L: null, G: null, V: null, ptr: 0,
        raf: 0, lastT: -1, lastF: 0, ema: 16.7, skip: 0
    };

    /* ── 재생 시계: 플레이어 시간 샘플(갱신이 거칠 수 있음)을 프레임 단위로 부드럽게 보간 ── */
    const C = { ok: false, m: 0, p: 0, s: -1 };
    function _clock(now) {
        const s = getPlayheadTime();
        if (!S.playing) { C.ok = false; return s; }
        if (!C.ok) { C.ok = true; C.m = s; C.p = now; C.s = s; return s; }
        let m = C.m + (now - C.p) / 1000;
        if (s !== C.s) {                       // 새 샘플이 들어왔을 때만 보정 (같은 값이면 순수 외삽)
            C.s = s;
            const err = s - m;
            m = Math.abs(err) > 0.3 ? s : m + err * 0.4;
        }
        C.m = m; C.p = now;
        return m;
    }

    /* ── 줄 사전 계산: 채움 구간(fs~fe), 글자 수/오프셋, 글로우 대상 ─────────────
       · 종료 시각이 없거나 너무 짧은 단어는 다음 단어 시작까지로 늘리고, 짧은 공백은 이어서 흐르게 한다.
       · 아주 짧은 단어도 최소 0.22초는 쓸어 채워지도록 구간을 앞뒤로 넓힌다 (한 번에 칠해지는 현상 방지). */
    const MIN_FILL = 0.22;
    function _prep(line) {
        if (line._wsPrep) return;
        const W = line.words;
        let off = 0;
        const durs = [];
        for (let k = 0; k < W.length; k++) {
            const w = W[k], nx = W[k + 1];
            let e = w.e;
            if (!(e > w.s + 0.06)) e = nx ? Math.max(nx.s, w.s + 0.12) : Math.max(line.end || 0, w.s + 0.4);
            w.re = e;
            durs.push(e - w.s);
        }
        const sorted = durs.slice().sort((a, b) => a - b);
        const med = sorted[sorted.length >> 1] || 0.3;
        for (let k = 0; k < W.length; k++) {
            const w = W[k], nx = W[k + 1], dur = durs[k];
            w.gl = dur >= 0.9 || (dur >= 0.55 && dur >= med * 2);          // 다른 단어보다 오래 머무는 단어
            let fe = w.re;
            if (nx && nx.s > fe && nx.s - fe < 0.35) fe = nx.s;           // 짧은 사이는 끊지 않고 이어서 흐른다
            let fs = w.s;
            if (fe - fs < MIN_FILL) { const ex = MIN_FILL - (fe - fs); fs -= ex * 0.5; fe += ex * 0.5; }
            w.fs = fs; w.fe = fe;
            // 들어올림 방식: 기본은 단어 전체가 함께, 글로우 단어만 한 글자씩 순서대로(+웨이브)
            w.cm = w.gl;                          // 글자 단위 들어올림 + 웨이브는 글로우 단어에만
            // 들어올림 시간은 타임라인 길이에 맞춘다: 짧은 단어는 빠르게(단, 끊겨 보이지 않을 최소치), 긴 단어는 천천히 따라간다
            w.ld = w.cm ? dur + 0.35 : Math.min(0.75, Math.max(0.38, dur * 1.1 + 0.12));
            w.cs = Array.from(w.t.trim() || w.t);                           // 글자 단위(서로게이트 쌍 안전)
            w.n = w.cs.length;
            w.o = off; off += w.n;
            // 웨이브: 단어 시간 기준. 글자마다 같은 모양의 펄스(Tc초)를 시간차(dd)로 개별 재생 → 단어 전체가 물결치듯 보인다
            w.Tc = Math.min(0.9, Math.max(0.4, (fe - fs) * 0.5));
            w.dd = w.n > 1 ? Math.max(0.04, ((fe - fs) - w.Tc) / (w.n - 1)) : 0;
            w.Tc = Math.min(1.4, Math.max(w.Tc, w.dd * 1.8));          // 이웃 글자 펄스가 충분히 겹치도록 → 끊김 없는 물결
        }
        line._wsN = off;
        line._wsPrep = true;
    }

    /* ── DOM 구성: 단어 → 글자(span.ws-c). 글자는 레이아웃이 항상 같도록 처음부터 inline-block ── */
    function build(el, line) {
        const W = line.words;
        if (!W || !W.length) return false;
        _prep(line);
        el.textContent = '';
        const wrap = document.createElement('span');
        wrap.className = 'ws-wrap';
        const chars = new Array(line._wsN);
        let grp = null;
        for (let k = 0; k < W.length; k++) {
            const w = W[k];
            const m = /^(\s*)([\s\S]*?)(\s*)$/.exec(w.t);
            const lead = m[1], core = m[2], trail = m[3];
            if (lead) { grp = null; wrap.appendChild(document.createTextNode(lead)); }
            const cjk = CJK_RE.test(core);
            if (!grp || cjk) { grp = document.createElement('span'); grp.className = 'ws-g'; wrap.appendChild(grp); }
            const word = document.createElement('span');
            word.className = 'ws-w';
            for (let i = 0; i < w.n; i++) {
                const c = document.createElement('span');
                c.className = w.gl ? 'ws-c ws-gl' : 'ws-c';
                c.textContent = w.cs[i];
                word.appendChild(c);
                chars[w.o + i] = c;
            }
            grp.appendChild(word);
            if (trail) { grp = null; wrap.appendChild(document.createTextNode(trail)); }
            else if (cjk) grp = null;
        }
        el.appendChild(wrap);
        el.classList.add('ws-line');
        el._ws = { segs: chars };
        return true;
    }

    /* ── 글자 상태 계산/반영 ─────────────────────────────────────
       단어 진행도 p(0→1)를 "빛의 앞머리 위치" x = p·(n+F) 로 바꿔 글자마다 v=(x-i)/(1+F) 만큼 채운다.
       → 빛이 글자 안쪽을 지나 다음 글자로 흐르고(F=1.5 글자 폭의 넓은 번짐 경계),
         들어올림·글로우도 같은 앞머리를 따라 한 글자씩 점진적으로 일어난다. */
    const F = 1.5, FD = 1 + F;
    const WAVE_STEPS = 60;
    const FL = 2.5, FLD = 1 + FL;                                   // 들어올림은 더 넓게 퍼져 글자 사이가 물결처럼 이어진다
    const _smoother = v => v * v * v * (v * (v * 6 - 15) + 10);     // 시작·끝이 아주 부드러운 가감속
    function _set(j, name, v) {
        const segs = WS.segs;
        for (let h = 0; h < segs.length; h++) segs[h][j].style.setProperty(name, v);
    }
    const _clamp01 = v => v < 0 ? 0 : v > 1 ? 1 : v;
    const _smooth = v => v * v * (3 - 2 * v);

    /* 반환: 이 단어가 완전히 끝났는지(최종값까지 기록됨) */
    function _paint(k, t) {
        const w = WS.W[k], n = w.n;
        const dur = w.fe - w.fs;
        let p = _clamp01((t - w.fs) / dur);
        p = p * 0.7 + _smooth(p) * 0.3;                                    // 완만한 가감속(기계적 등속 제거)
        const x = p * (n + F);
        // 들어올림: 단어 단위(기본) 또는 글자 단위(긴 단어). 시간은 w.ld(타임라인 길이 기반)
        const lt = _clamp01((t - w.fs - LIFT_DELAY) / w.ld);
        const xl = lt * (n + FL);
        const wordLift = w.cm ? 0 : Math.round(_smoother(lt) * LIFT_STEPS);
        const tail = w.gl ? GLOW_TAIL : 0;
        const gf = w.gl ? (t <= w.fe ? 1 : 1 - _smooth(_clamp01((t - w.fe) / GLOW_TAIL))) : 0;

        for (let i = 0; i < n; i++) {
            const j = w.o + i;
            const pq = Math.round(_clamp01((x - i) / FD) * FILL_STEPS);
            if (pq !== WS.P[j]) { WS.P[j] = pq; _set(j, '--p', pq / FILL_STEPS); }
            const lq = w.cm ? Math.round(_smoother(_clamp01((xl - i) / FLD)) * LIFT_STEPS) : wordLift;
            if (lq !== WS.L[j]) { WS.L[j] = lq; _set(j, '--l', lq / LIFT_STEPS); }
            if (w.cm) {                                                    // 웨이브: 단어 시간에 맞춰 글자마다 개별 펄스(은은하게 커졌다 작아짐)
                // 이 글자만의 펄스: 단어 시작 + i·dd 에 시작, Tc 동안 sin² 종 모양(시작·끝 기울기 0)으로 커졌다 돌아온다
                const sv = Math.sin(Math.PI * _clamp01((t - w.fs - i * w.dd) / w.Tc));
                const vq = Math.round(sv * sv * WAVE_STEPS);
                if (vq !== WS.V[j]) { WS.V[j] = vq; _set(j, '--w', vq / WAVE_STEPS); }
            }
            if (w.gl) {                                                    // 글로우도 빛의 앞머리와 함께 점진적으로 차오른다
                const gq = Math.round(_smooth(_clamp01((x - i) / FD)) * gf * GLOW_STEPS);
                if (gq !== WS.G[j]) {
                    const was = WS.G[j] > 0;
                    WS.G[j] = gq;
                    _set(j, '--g', gq / GLOW_STEPS);
                    if ((gq > 0) !== was) for (let h = 0; h < WS.segs.length; h++) WS.segs[h][j].classList.toggle('ws-gon', gq > 0);
                }
            }
        }
        return t >= w.fs + LIFT_DELAY + Math.max(dur, w.ld, w.cm ? (n - 1) * w.dd + w.Tc : 0) + tail + 0.02;
    }

    function _fullPaint(t) {
        let ptr = -1;
        for (let k = 0; k < WS.n; k++) {
            const done = _paint(k, t);
            if (!done && ptr < 0) ptr = k;
        }
        WS.ptr = ptr < 0 ? WS.n : ptr;
        WS.lastT = t;
    }

    /* ── 프레임 루프 ────────────────────────────────────────────── */
    function _frame(now) {
        WS.raf = 0;
        if (!WS.line || !S.playing || window.__bgMode) return;
        const dt = now - WS.lastF; WS.lastF = now;
        if (dt > 0 && dt < 250) WS.ema = WS.ema * 0.9 + dt * 0.1;
        WS.raf = requestAnimationFrame(_frame);
        if (WS.ema > 26 && (WS.skip ^= 1)) return;                           // 느린 기기: 30fps

        const t = _clock(now);
        if (t < WS.lastT - 0.12 || t > WS.lastT + 0.6) { _fullPaint(t); return; }   // 시크
        const W = WS.W, n = WS.n;
        for (let k = WS.ptr; k < n; k++) {
            if (W[k].fs > t) break;
            if (_paint(k, t) && k === WS.ptr) WS.ptr++;         // 끝난 단어는 최종값을 기록한 뒤 더 이상 건드리지 않는다
        }
        WS.lastT = t;
    }

    function _start() {
        if (WS.raf || !WS.line || !S.playing || window.__bgMode) return;
        C.ok = false;
        WS.lastF = performance.now();
        WS.raf = requestAnimationFrame(_frame);
    }
    function _stop() { if (WS.raf) { cancelAnimationFrame(WS.raf); WS.raf = 0; } }

    /* ── 활성 줄 전환 (_highlightLine / Lv / Fs / Ls 에서 호출. 같은 줄·같은 호스트면 아무것도 안 함) ── */
    function activate(idx) {
        const line = idx >= 0 ? LY.lines[idx] : null;
        const hosts = [];
        if (line && line.words) {
            const keys = ['el', 'el_lv', 'el_fs', 'el_ls'];
            for (let i = 0; i < 4; i++) {
                const e = line[keys[i]];
                if (e && e._ws && e.isConnected) hosts.push(e);
            }
        }
        if (line === WS.line && hosts.length === WS.hosts.length && hosts.every((h, i) => h === WS.hosts[i])) return;
        _deactivate();
        if (!line || !hosts.length) return;

        WS.line = line; WS.idx = idx; WS.hosts = hosts;
        WS.segs = hosts.map(h => h._ws.segs);
        WS.W = line.words; WS.n = line.words.length;
        _prep(line);
        WS.P = new Int16Array(line._wsN).fill(-1);
        WS.L = new Int16Array(line._wsN).fill(-1);
        WS.G = new Int16Array(line._wsN).fill(-1);
        WS.V = new Int16Array(line._wsN).fill(-1);
        for (const h of hosts) h.classList.add('ws-on');
        C.ok = false;
        _fullPaint(S.playing ? _clock(performance.now()) : getPlayheadTime());
        _start();
    }

    function _deactivate() {
        _stop();
        for (const h of WS.hosts) h.classList.remove('ws-on');
        for (const sg of WS.segs) for (const sp of sg) if (sp.classList.contains('ws-gon')) sp.classList.remove('ws-gon');
        WS.line = null; WS.idx = -1; WS.hosts = []; WS.segs = []; WS.W = null; WS.n = 0; WS.ptr = 0;
    }

    /* 가사 틱(40ms)에서 호출: 재생이 다시 시작되면 루프 재개, 일시정지 중 시크하면 한 번 다시 그림 */
    function poke() {
        if (!WS.line) return;
        if (S.playing) { if (!WS.raf) _start(); return; }
        const t = getPlayheadTime();
        if (Math.abs(t - WS.lastT) > 0.03) _fullPaint(t);
    }

    document.addEventListener('visibilitychange', () => { if (document.hidden) _stop(); else _start(); });

    window.WS = { build, activate, poke, reset: _deactivate };
})();
