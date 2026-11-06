/* Hanzi Path: learn → review, along a path where every part comes before the
   first common character that uses it (data/path.json, tools/build.py).

   A card is a small FSRS (FSRS-4.5 with
   its default weights): a stability S in days, the time after which an item
   would be recalled nine times out of ten, and a difficulty D, 1-10. Grades
   are the garden's three: 1 again, 2 unsure, 3 knew. Learning an item isn't
   a grade: the round that follows a batch is, so a new item starts on what
   you showed you know.

   Progress is in Store (store.js): SQLite in the app, IndexedDB in a browser.
   S holds it in memory; every change is written through as it happens. */
"use strict";

const DAY = 86400e3;
const APP = window.HanziApp || null;

// ── the scheduler (FSRS-4.5, in milliseconds) ────────────────────────────
const W = [0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031, 1.6474, 0.1367,
  1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755];
const F = 19 / 81, C = -0.5;
const RELEARN = 10 * 60e3;      // an item you didn't know comes back this soon
const MAX_S = 365;
const MATURE_D = 10;            // strength's maturity: 1 - e^(-S / this)
const KNOWN = 0.5;              // strength from which an item counts as known

const recall = (c, now) => Math.pow(1 + F * Math.max(0, now - c.last) / DAY / Math.max(c.s, 0.01), C);
const d0 = g => Math.min(10, Math.max(1, W[4] - (g - 3) * W[5]));
const nextD = (d, g) => {
  d = d - W[6] * (g - 3);
  d = W[7] * d0(3) + (1 - W[7]) * d;
  return Math.min(10, Math.max(1, d));
};
const recallS = (s, d, r, g) =>
  s * (1 + Math.exp(W[8]) * (11 - d) * Math.pow(s, -W[9]) * (Math.exp(W[10] * (1 - r)) - 1) * (g === 2 ? W[15] : 1));
const lapseS = (s, d, r) =>
  Math.min(s, W[11] * Math.pow(d, -W[12]) * (Math.pow(s + 1, W[13]) - 1) * Math.exp(W[14] * (1 - r)));

/** A grade, written through; returns what undo needs to put things back. */
function grade(h, g, mode, ms, now = Date.now()) {
  const before = S.cards[h] ? { ...S.cards[h] } : null;
  let c = S.cards[h], r = 0;
  if (!c) {
    c = S.cards[h] = { s: W[g - 1], d: d0(g), reps: 0, lapses: 0, first: now };
  } else {
    r = recall(c, now);
    if (g === 1) {
      c.s = Math.max(0.05, lapseS(c.s, c.d, r));
      c.lapses = (c.lapses || 0) + 1;
    } else {
      c.s = Math.max(recallS(c.s, c.d, r, g), c.reps ? 0 : W[g - 1]);
    }
    c.d = nextD(c.d, g);
  }
  c.s = Math.min(c.s, MAX_S);
  c.reps += 1;
  c.last = now;
  c.grade = g;
  c.due = now + (g === 1 ? RELEARN : c.s * DAY);
  Store.putCard(h, c);
  Store.addReview({ at: now, h, g, mode, ms, r: +r.toFixed(4), s: +c.s.toFixed(4), d: +c.d.toFixed(4) });
  bumpDay("reviews", 1);
  return { h, before };
}

function ungrade({ h, before }) {
  if (before) { S.cards[h] = before; Store.putCard(h, before); } else { delete S.cards[h]; Store.delCard(h); }
  Store.popReview();
  bumpDay("reviews", -1);
}

function strength(h, now = Date.now()) {
  const c = S.cards[h];
  if (!c) return 0;
  const fresh = Math.min(1, recall(c, now) / 0.9) ** 2;
  return (1 - Math.exp(-c.s / MATURE_D)) * fresh;
}

// ── state ──────────────────────────────────────────────────────────────────
const OPTS = { batch: 5, perDay: 15, mode: "mix", say: true };
let S = { cards: {}, stories: {}, opts: { ...OPTS }, today: { d: "", n: 0 }, days: {} };

function take(all) {
  const m = all.meta || {};
  S = {
    cards: all.cards || {}, stories: all.stories || {},
    opts: { ...OPTS, ...(m.opts || {}) }, today: m.today || { d: "", n: 0 }, days: m.days || {},
  };
}
const putOpts = () => Store.putMeta("opts", S.opts);
const today = () => new Date().toLocaleDateString("en-CA");
function newLeftToday() {
  if (S.today.d !== today()) S.today = { d: today(), n: 0 };
  return Math.max(0, S.opts.perDay - S.today.n);
}
function bumpDay(what, n) {
  const d = S.days[today()] || (S.days[today()] = { reviews: 0, learnt: 0 });
  d[what] = Math.max(0, (d[what] || 0) + n);
  Store.putMeta("days", S.days);
}
/** Days in a row with something done, up to today (or yesterday, if today's still empty). */
function streak() {
  const day = new Date();
  const active = d => { const x = S.days[d.toLocaleDateString("en-CA")]; return x && (x.reviews || x.learnt); };
  if (!active(day)) day.setDate(day.getDate() - 1);
  let n = 0;
  while (active(day)) { n += 1; day.setDate(day.getDate() - 1); }
  return n;
}

// ── the path ───────────────────────────────────────────────────────────────
let PATH = [], AT = {}, CHUNK = 60;
const chunks = {};

function strokes(h) {
  const n = Math.floor(AT[h] / CHUNK);
  if (!chunks[n]) chunks[n] = fetch(`data/strokes-${n}.json`).then(r => {
    if (!r.ok) throw new Error(r.status);
    return r.json();
  });
  return chunks[n].then(c => c[h]);
}
const loader = (h, ok, fail) => { strokes(h).then(d => d ? ok(d) : fail()).catch(fail); };

// ── pinyin in the tones' inks ──────────────────────────────────────────────
const MARKS = { 1: "āēīōūǖ", 2: "áéíóúǘ", 3: "ǎěǐǒǔǚ", 4: "àèìòùǜ" };
function tone(syl) {
  const low = syl.toLowerCase();
  for (const t in MARKS) if ([...MARKS[t]].some(m => low.includes(m))) return +t;
  return 5;
}
const esc = s => String(s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
const inked = py => py.split(/\s+/).map(s => `<span class="t${tone(s)}">${esc(s)}</span>`).join(" ");
const zh = h => `<span lang="zh">${esc(h)}</span>`;

// ── the voice: Android's in the app, the browser's elsewhere ───────────────
let voice = null;
function findVoice() {
  if (!("speechSynthesis" in window)) return;
  const vs = speechSynthesis.getVoices();
  voice = vs.find(v => /^zh[-_]CN/i.test(v.lang)) || vs.find(v => /^(zh|cmn)/i.test(v.lang)) || null;
}
if ("speechSynthesis" in window) { findVoice(); speechSynthesis.onvoiceschanged = findVoice; }
const canSay = () => !!APP || !!voice;
function say(text) {
  if (APP) { APP.speak(text); return; }
  if (!voice) return;
  speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(text);
  u.voice = voice; u.lang = voice.lang; u.rate = 0.85;
  speechSynthesis.speak(u);
}
const SPEAKER = `<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="M4 9h4l5-4v14l-5-4H4z" fill="currentColor"/><path d="M16 8.5c1.2 1 1.8 2.2 1.8 3.5s-.6 2.5-1.8 3.5M18.5 6c2 1.6 3 3.6 3 6s-1 4.4-3 6" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>`;
const sayBtn = (text, label) => canSay()
  ? `<button class="say" type="button" data-say="${esc(text)}" aria-label="Say ${esc(label || text)}">${SPEAKER}</button>` : "";

// ── drawing a character ────────────────────────────────────────────────────
function ink(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }

function writer(box, h, opts = {}) {
  box.innerHTML = "";
  const size = box.clientWidth || 240;
  const t = tone((item(h).py[0] || ""));
  return HanziWriter.create(box, h, {
    width: size, height: size, padding: Math.round(size * 0.06),
    strokeColor: ink("--ink"), radicalColor: ink(`--t${t}`), outlineColor: ink("--grid"),
    drawingColor: ink("--ink"), highlightColor: ink("--hl") === "#ffff00" ? "#e6c800" : ink("--hl"),
    strokeAnimationSpeed: 1.4, delayBetweenStrokes: 140, showOutline: true,
    charDataLoader: loader, ...opts,
  });
}

// ── pieces of a card ───────────────────────────────────────────────────────
const item = h => PATH[AT[h]];

function readingHTML(it) {
  const [main, alt] = it.py;
  return `<div class="reading">
    <div class="py">${main ? inked(main) : "—"}${main ? sayBtn(it.h, main) : ""}${alt ? `<span class="alt">also ${esc(alt)}</span>` : ""}</div>
    <div class="en">${esc(it.en)}</div></div>`;
}

function chipHTML(h) {
  const p = item(h);
  const met = !!S.cards[h];
  return `<button class="chip${met ? "" : " ahead"}" type="button" data-open="${esc(h)}">${zh(h)}<span>${p.py[0] ? inked(p.py[0]) : ""} <span class="m">${esc(p.en)}</span></span></button>`;
}

function whyHTML(it) {
  const e = it.et;
  if (!e) return "";
  const name = h => (AT[h] !== undefined ? `${zh(h)} <i>${esc(item(h).en)}</i>` : zh(h));
  let s = "";
  if (e.type === "pictophonetic" && (e.semantic || e.phonetic)) {
    const bits = [];
    if (e.semantic) bits.push(`${name(e.semantic)} gives the meaning`);
    if (e.phonetic) {
      const p = AT[e.phonetic] !== undefined && item(e.phonetic).py[0];
      bits.push(`${zh(e.phonetic)}${p ? ` (${inked(p)})` : ""} the sound`);
    }
    s = bits.join(", ") + ".";
    if (e.hint) s += ` ${esc(e.hint)}.`;
  } else if (e.hint) {
    s = esc(e.hint).replace(/[㐀-鿿⺀-⿟]/g, m => zh(m)) + ".";
  }
  return s ? `<div class="block"><h3>Why it looks like this</h3><p class="why">${s}</p></div>` : "";
}

function storyPlaceholder(it) {
  if (!it.parts.length) return "Write a picture or a scene that makes this one stick.";
  return it.parts.map(p => `${p} ${item(p).en}`).join(" + ") + ` → ${it.en}. Make it a scene of your own.`;
}

function storyHTML(it) {
  return `<div class="block"><h3><label for="story">Your story</label></h3>
    <textarea class="story" id="story" data-h="${esc(it.h)}" placeholder="${esc(storyPlaceholder(it))}">${esc(S.stories[it.h] || "")}</textarea></div>`;
}

function wordsHTML(it) {
  if (!it.w) return "";
  return `<div class="block"><h3>Words you can read now</h3><ul class="words">${it.w.map(([w, py, en, l]) =>
    `<li${canSay() ? ` data-say="${esc(w)}"` : ""}><span class="w" lang="zh">${esc(w)}</span><span class="wp">${inked(py)}<span class="lvl">HSK ${l}</span></span><span class="we">${esc(en)}</span></li>`).join("")}</ul></div>`;
}

function partsHTML(it) {
  let s = "";
  if (it.parts.length) s += `<div class="block"><h3>Made of</h3><div class="chips">${it.parts.map(chipHTML).join("")}</div></div>`;
  if (it.used) s += `<div class="block"><h3>Leads to</h3><div class="chips">${it.used.map(chipHTML).join("")}</div></div>`;
  return s;
}

function tagHTML(it) {
  const pos = AT[it.h] + 1;
  return `<div class="tag"><span class="kind ${it.k}">${it.k === "part" ? "part" : "character"}</span><span>${it.n} strokes · no. ${pos} of ${PATH.length}</span></div>`;
}

// ── views ──────────────────────────────────────────────────────────────────
const $ = id => document.getElementById(id);
const VIEWS = ["home", "learn", "quiz", "done"];
let view = "home";
function show(v) {
  view = v;
  VIEWS.forEach(x => { $(x).hidden = x !== v; });
  window.scrollTo(0, 0);
  if (v === "home") renderHome();
}

function dueList(now = Date.now()) {
  return Object.entries(S.cards).filter(([h, c]) => c.due <= now && AT[h] !== undefined)
    .sort((a, b) => (now - b[1].last) / b[1].s - (now - a[1].last) / a[1].s)   // the most overdue for what it holds first
    .map(([h]) => h);
}
const nextNew = n => PATH.filter(it => !S.cards[it.h]).slice(0, n).map(it => it.h);

function renderHome() {
  const now = Date.now();
  const due = dueList(now);
  const met = Object.keys(S.cards).length;
  const known = PATH.filter(it => strength(it.h, now) >= KNOWN).length;
  const left = newLeftToday();
  const batch = Math.min(S.opts.batch, left);
  const next = nextNew(batch || S.opts.batch);
  const end = new Date(); end.setHours(24, 0, 0, 0);
  const tomorrow = Object.values(S.cards).filter(c => c.due > now && c.due <= end.getTime() + DAY).length;
  const t = S.days[today()] || {};
  const st = streak();

  $("stat").innerHTML = `<b>${known}</b> known · <b>${met}</b> of ${PATH.length} met`;
  $("stat2").innerHTML = [
    st ? `<b>${st}</b> day${st === 1 ? "" : "s"} in a row` : "no streak yet",
    `<b>${t.reviews || 0}</b> reviews today`,
    `<b>${tomorrow}</b> due by tomorrow`,
  ].join(" · ");
  const rv = $("go-review");
  rv.disabled = !due.length;
  rv.textContent = due.length ? `Review ${due.length}` : "Nothing due";
  const ln = $("go-learn");
  ln.disabled = !batch || !next.length;
  ln.textContent = !next.length ? "Path walked" : batch ? `Learn next ${Math.min(batch, next.length)}` : "Enough new for today";
  $("next-up").innerHTML = next.length ? `Next on the path: ${zh(next.join(" "))}` : "";

  const g = $("garden");
  const firstNew = PATH.findIndex(it => !S.cards[it.h]);
  g.innerHTML = PATH.map((it, i) => {
    let cls = "cell";
    if (it.k === "part") cls += " part";
    const c = S.cards[it.h];
    if (c) {
      const s = strength(it.h, now);
      cls += " met";
      if (s >= KNOWN) cls += " known";
      else if (c.due <= now && c.reps > 1) cls += " wilt";
    } else if (i >= firstNew && i < firstNew + (batch || S.opts.batch)) cls += " next";
    else if (i < firstNew + 40) cls += " ahead-near";
    const label = `${it.h} ${it.py[0] || ""} ${it.en}`;
    return `<button class="${cls}" type="button" role="listitem" data-open="${esc(it.h)}" title="${esc(label)}" aria-label="${esc(label)}" lang="zh">${esc(it.h)}</button>`;
  }).join("");

  $("opt-batch").value = S.opts.batch;
  $("opt-day").value = S.opts.perDay;
  $("opt-mode").value = S.opts.mode;
  $("opt-say").value = S.opts.say ? "on" : "off";
  $("row-say").hidden = !canSay();
  $("where").textContent = { sqlite: "in the app's database", indexeddb: "in this browser's database", memory: "for this visit only (this browser keeps nothing)" }[Store.kind];
  renderUpdates();
}

// ── learning a batch ───────────────────────────────────────────────────────
let L = null;   // { items, i, lookup }

function startLearn() {
  const n = Math.min(S.opts.batch, newLeftToday());
  const items = nextNew(n);
  if (!items.length) return;
  L = { items, i: 0, lookup: false };
  show("learn");
  renderLearn();
}

function openItem(h) {
  L = { items: [h], i: 0, lookup: true, from: view };
  show("learn");
  renderLearn();
}

let W1 = null;
function renderLearn() {
  const h = L.items[L.i];
  const it = item(h);
  $("learn-count").textContent = L.lookup ? (S.cards[h] ? `known ${Math.round(strength(h) * 100)}%` : "not met yet") : `new ${L.i + 1} / ${L.items.length}`;
  $("learn-card").innerHTML = `${tagHTML(it)}
    <div class="box-wrap"><div class="box" id="learn-box" role="img" aria-label="Stroke order of ${esc(h)}"></div></div>
    ${readingHTML(it)}${partsHTML(it)}${whyHTML(it)}${storyHTML(it)}${wordsHTML(it)}`;
  $("learn-next").textContent = L.lookup ? "Done" : L.i + 1 < L.items.length ? "Next" : "Check yourself";
  $("learn-write").textContent = "Write it";
  const box = $("learn-box");
  W1 = writer(box, h);
  W1.animateCharacter();
  if (!L.lookup && S.opts.say && it.py[0]) say(h);
  box.onclick = () => { W1.cancelQuiz(); W1.showOutline(); W1.animateCharacter(); $("learn-write").textContent = "Write it"; };
}

function learnWrite() {
  const btn = $("learn-write");
  btn.textContent = "Trace the strokes";
  W1.hideCharacter();
  W1.showOutline();
  W1.quiz({ showHintAfterMisses: 2, onComplete: () => { btn.textContent = "Again"; } });
}

function learnNext() {
  if (L.lookup) { back(); return; }
  if (L.i + 1 < L.items.length) { L.i += 1; renderLearn(); return; }
  S.today.n += L.items.length;
  Store.putMeta("today", S.today);
  bumpDay("learnt", L.items.length);
  startQuiz(L.items.slice(), "check");
}

// ── a round of questions: the check after a batch, or a review ─────────────
let Q = null;   // { queue, kind, done, total, tries, misses, mode, suggest, shown, last }

function startQuiz(items, kind) {
  Q = { queue: items, kind, done: 0, total: items.length, tries: {}, misses: 0, last: null };
  show("quiz");
  renderQuiz();
}
function startReview() {
  const due = dueList();
  if (due.length) startQuiz(due.slice(0, 60), "review");
}

function modeFor(h) {
  const it = item(h), c = S.cards[h];
  if (Q.kind === "check" || it.k === "part" || !c) return "read";
  if (S.opts.mode === "mix") return c.reps >= 2 && Math.random() < 0.4 ? "write" : "read";
  return S.opts.mode;
}

let W2 = null;
function renderQuiz(mode) {
  const h = Q.queue[0];
  const it = item(h);
  Q.mode = mode || modeFor(h);
  Q.suggest = 0;
  Q.shown = Date.now();
  $("quiz-count").textContent = `${Q.kind === "check" ? "check" : "review"} ${Math.min(Q.done + 1, Q.total)} / ${Q.total}`;
  $("quiz-undo").hidden = !Q.last;
  $("quiz-grades").hidden = true;
  $("quiz-show-row").hidden = false;
  [...document.querySelectorAll("#quiz-grades .pill")].forEach(b => b.classList.remove("suggest"));
  const write = Q.mode === "write";
  $("quiz-card").innerHTML = `
    <div class="prompt"><span class="ask">${write ? "Write the character" : "What is it, how is it read?"}</span>
      ${write ? readingHTML(it) : ""}</div>
    <div class="box-wrap"><div class="box" id="quiz-box" role="img" aria-label="${write ? "Drawing area" : esc(h)}">${write ? '<span class="hint">draw here</span>' : ""}</div></div>
    <div class="answer" id="quiz-answer" hidden></div>`;
  const box = $("quiz-box");
  if (write) {
    W2 = writer(box, h, { showCharacter: false, showOutline: false });
    W2.quiz({
      showHintAfterMisses: 3,
      onComplete: ({ totalMistakes }) => {
        Q.suggest = totalMistakes === 0 ? 3 : totalMistakes <= 3 ? 2 : 1;
        reveal();
      },
    });
  } else {
    W2 = writer(box, h, { showOutline: false });
    box.onclick = () => W2.animateCharacter();
  }
}

function reveal() {
  if (!$("quiz-grades").hidden) return;
  const h = Q.queue[0];
  const it = item(h);
  Q.ms = Date.now() - Q.shown;
  if (Q.mode === "write") {
    if (!Q.suggest) { Q.suggest = 1; W2.cancelQuiz(); W2.showCharacter(); }
  } else if (S.opts.say && it.py[0]) say(h);
  const a = $("quiz-answer");
  a.innerHTML = (Q.mode === "read" ? readingHTML(it) : "") + partsHTML(it) +
    (S.stories[h] ? `<div class="block"><h3>Your story</h3><p class="why">${esc(S.stories[h])}</p></div>` : whyHTML(it));
  a.hidden = false;
  $("quiz-show-row").hidden = true;
  $("quiz-grades").hidden = false;
  if (Q.suggest) document.querySelector(`#quiz-grades [data-g="${Q.suggest}"]`).classList.add("suggest");
}

function answer(g) {
  if ($("quiz-grades").hidden) return;
  const h = Q.queue.shift();
  const undo = grade(h, g, Q.mode, Q.ms);
  Q.last = { undo, mode: Q.mode, queue: [h, ...Q.queue], done: Q.done, misses: Q.misses, tries: { ...Q.tries } };
  Q.tries[h] = (Q.tries[h] || 0) + 1;
  if (g === 1) Q.misses += 1;
  if (g === 1 && Q.tries[h] < 3) Q.queue.push(h);   // once more at the end of the round
  else Q.done += 1;
  if (!Q.queue.length) return finish();
  renderQuiz();
}

/** The last grade taken back: the card as it was, and the question again. */
function undoLast() {
  if (!Q || !Q.last) return;
  const l = Q.last;
  ungrade(l.undo);
  Object.assign(Q, { queue: l.queue, done: l.done, misses: l.misses, tries: l.tries, last: null });
  show("quiz");
  renderQuiz(l.mode);
}

function finish() {
  const n = Q.total;
  const learnt = Q.kind === "check";
  $("done-seal").textContent = Q.misses === 0 ? "好" : Q.misses <= n / 3 ? "行" : "练";
  $("done-text").textContent = learnt
    ? `${n} new on the path. They come back tomorrow, or sooner if you missed them.`
    : `${n} reviewed${Q.misses ? `, ${Q.misses} to go over again` : ", all of them held"}.`;
  $("done-undo").hidden = !Q.last;
  show("done");
}

/** Back, as Android's back button has it: a looked-up item to where it was opened, anything else to the path. */
function back() {
  if (view === "home") return false;
  if (view === "learn" && L && L.lookup && L.from === "quiz") { show("quiz"); return true; }
  show("home");
  return true;
}

// ── progress in and out ────────────────────────────────────────────────────
async function copyOut() {
  const all = { v: 2, cards: S.cards, stories: S.stories, meta: { opts: S.opts, today: S.today, days: S.days }, reviews: await Store.reviews() };
  const text = JSON.stringify(all);
  const io = $("io");
  io.value = text;
  const ok = () => { $("io-msg").textContent = `Copied: ${Object.keys(S.cards).length} cards, ${all.reviews.length} reviews. Paste it somewhere safe.`; };
  const sel = () => { io.focus(); io.select(); $("io-msg").textContent = "Selected: copy it from the box."; };
  try { navigator.clipboard.writeText(text).then(ok, sel); } catch (e) { sel(); }
}

/** A copy back in: this one's (v 2), or the first version's localStorage blob (v 1). */
function parseCopy(text) {
  const got = JSON.parse(text);
  if (got && got.v === 2 && typeof got.cards === "object") return got;
  if (got && got.v === 1 && typeof got.cards === "object") {
    return { cards: got.cards, stories: got.stories || {}, meta: { opts: got.opts, today: got.today }, reviews: [] };
  }
  throw new Error("not a copy");
}

let armed = false;
async function loadIn() {
  const msg = $("io-msg");
  let got;
  try { got = parseCopy($("io").value); } catch (e) { msg.textContent = "That isn't a progress copy: paste the whole text Copy progress gave you."; return; }
  if (!armed) {
    armed = true;
    $("io-load").textContent = `Replace with ${Object.keys(got.cards).length} cards`;
    msg.textContent = "This replaces the progress here. Press again to load it.";
    return;
  }
  armed = false;
  $("io-load").textContent = "Load pasted";
  try {
    await Store.replace(got);
    take(got);
    msg.textContent = `Loaded ${Object.keys(S.cards).length} cards.`;
  } catch (e) {
    msg.textContent = "Couldn't save it here. Nothing was changed.";
  }
  renderHome();
}

// ── updates (the app only) ─────────────────────────────────────────────────
function renderUpdates() {
  $("updates").hidden = !APP;
  if (!APP) return;
  let v = {};
  try { v = JSON.parse(APP.version()); } catch (e) { /* older app */ }
  const when = v.checked ? new Date(v.checked).toLocaleString([], { dateStyle: "short", timeStyle: "short" }) : "never";
  $("up-version").textContent = `App ${v.app ?? "?"} · content ${v.web ?? "?"} · checked ${when}`;
  $("up-status").textContent = v.status || "";
  $("up-token").placeholder = v.token ? "Token saved. Paste a new one to replace it." : "GitHub token (read-only, this repo)";
}

// the native side's calls
window.hanziBack = back;
window.hanziResume = () => { if (view === "home" && PATH.length) renderHome(); };
window.hanziUpdateStatus = () => renderUpdates();
window.hanziUpdated = () => { $("update-ready").hidden = false; renderUpdates(); };

function paintBars() {
  if (APP && APP.setBars) APP.setBars(ink("--paper"), ink("--paper").toLowerCase() !== "#ffffff");
}

// ── wiring ─────────────────────────────────────────────────────────────────
document.addEventListener("click", e => {
  const s = e.target.closest("[data-say]");
  if (s) { say(s.dataset.say); return; }
  const o = e.target.closest("[data-open]");
  if (o) openItem(o.dataset.open);
  const g = e.target.closest("[data-g]");
  if (g) answer(+g.dataset.g);
});
document.addEventListener("input", e => {
  if (e.target.id === "story") {
    const h = e.target.dataset.h, t = e.target.value.trim();
    if (t) S.stories[h] = t; else delete S.stories[h];
    clearTimeout(e.target._t);
    e.target._t = setTimeout(() => Store.putStory(h, t), 400);
  }
});
document.addEventListener("keydown", e => {
  if ($("quiz").hidden || /TEXTAREA|SELECT|INPUT/.test(e.target.tagName)) return;
  if (e.key === " " || e.key === "Enter") { e.preventDefault(); reveal(); }
  if (["1", "2", "3"].includes(e.key)) answer(+e.key);
  if (e.key === "z" || e.key === "Backspace") undoLast();
});
$("go-review").onclick = startReview;
$("go-learn").onclick = startLearn;
$("learn-next").onclick = learnNext;
$("learn-write").onclick = learnWrite;
$("learn-close").onclick = back;
$("quiz-close").onclick = () => show("home");
$("quiz-show").onclick = reveal;
$("quiz-undo").onclick = undoLast;
$("done-undo").onclick = undoLast;
$("done-home").onclick = () => show("home");
$("io-copy").onclick = copyOut;
$("io-load").onclick = loadIn;
$("opt-batch").onchange = e => { S.opts.batch = +e.target.value; putOpts(); renderHome(); };
$("opt-day").onchange = e => { S.opts.perDay = +e.target.value; putOpts(); renderHome(); };
$("opt-mode").onchange = e => { S.opts.mode = e.target.value; putOpts(); };
$("opt-say").onchange = e => { S.opts.say = e.target.value === "on"; putOpts(); };
$("up-save").onclick = () => {
  const t = $("up-token").value.trim();
  if (!t) { $("up-status").textContent = "Paste the token first."; return; }
  APP.setToken(t);
  $("up-token").value = "";
  APP.checkUpdates();
  renderUpdates();
};
$("up-check").onclick = () => { APP.checkUpdates(); $("up-status").textContent = "Checking…"; };
$("update-reload").onclick = () => (APP ? APP.reload() : location.reload());
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", paintBars);

Promise.all([
  fetch("data/path.json").then(r => r.json()),
  Store.load().catch(() => ({})),
]).then(([p, all]) => {
  PATH = p.items;
  CHUNK = p.chunk;
  PATH.forEach((it, i) => { AT[it.h] = i; });
  take(all);
  paintBars();
  show("home");
}).catch(() => { $("stat").textContent = "The path didn't load. Reload the page to try again."; });

if (!APP && "serviceWorker" in navigator && location.protocol !== "file:") {
  navigator.serviceWorker.register("sw.js").catch(() => { /* not offline, that's all */ });
}
