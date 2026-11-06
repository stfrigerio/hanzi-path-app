/* Where the progress lives. One interface, three places:
     sqlite     in the app: Android's SQLite through window.HanziApp (android/…/Db.java)
     indexeddb  in a browser
     memory     when the browser keeps nothing (a private window): this visit only
   The page loads everything once (load) and writes each change through as it
   happens; nothing waits on a write. What's kept:
     cards    {h: {s, d, reps, lapses, first, last, due, grade}}
     stories  {h: text}
     meta     {opts, today, days}: settings and small state
     reviews  every grade given, in order (kept for fitting FSRS to you later)
   The first progress, before the database, was one localStorage blob
   ("hanzi-path", v 1): it is moved in once, and left where it was. */
"use strict";

const Store = (() => {
  const OLD_KEY = "hanzi-path";
  const app = window.HanziApp;

  function fromOld() {
    try {
      const o = JSON.parse(localStorage.getItem(OLD_KEY) || "null");
      if (o && o.v === 1 && o.cards && Object.keys(o.cards).length) {
        return { cards: o.cards, stories: o.stories || {}, meta: { opts: o.opts, today: o.today }, reviews: [] };
      }
    } catch (e) { /* nothing there */ }
    return null;
  }

  if (app) {
    return {
      kind: "sqlite",
      async load() {
        const all = JSON.parse(app.load() || "{}");
        if (!Object.keys(all.cards || {}).length) {
          const old = fromOld();
          if (old) { app.replaceAll(JSON.stringify(old)); return old; }
        }
        return { cards: all.cards || {}, stories: all.stories || {}, meta: all.meta || {} };
      },
      putCard: (h, c) => app.putCard(h, JSON.stringify(c)),
      delCard: h => app.delCard(h),
      putStory: (h, t) => app.putStory(h, t || ""),
      putMeta: (k, v) => app.putMeta(k, JSON.stringify(v)),
      addReview: r => app.addReview(JSON.stringify(r)),
      popReview: () => app.popReview(),
      async reviews() { return JSON.parse(app.reviews() || "[]"); },
      async replace(all) { return app.replaceAll(JSON.stringify(all)); },
    };
  }

  // ── IndexedDB ──
  const STORES = ["cards", "stories", "meta", "reviews"];
  let db = null;
  const memory = { cards: {}, stories: {}, meta: {}, reviews: [] };

  const req = r => new Promise((ok, bad) => { r.onsuccess = () => ok(r.result); r.onerror = () => bad(r.error); });
  function open() {
    return new Promise((ok, bad) => {
      const r = indexedDB.open("hanzi-path", 1);
      r.onupgradeneeded = () => {
        const d = r.result;
        d.createObjectStore("cards");
        d.createObjectStore("stories");
        d.createObjectStore("meta");
        d.createObjectStore("reviews", { autoIncrement: true });
      };
      r.onsuccess = () => ok(r.result);
      r.onerror = () => bad(r.error);
      r.onblocked = () => bad(new Error("blocked"));
    });
  }
  async function keyed(store) {
    const tx = db.transaction(store);
    const [keys, vals] = await Promise.all([req(tx.objectStore(store).getAllKeys()), req(tx.objectStore(store).getAll())]);
    const out = {};
    keys.forEach((k, i) => { out[k] = vals[i]; });
    return out;
  }
  function write(store, fn) {
    if (!db) return;
    try { fn(db.transaction(store, "readwrite").objectStore(store)); } catch (e) { /* kept in memory */ }
  }
  async function replaceIDB(all) {
    const tx = db.transaction(STORES, "readwrite");
    STORES.forEach(s => tx.objectStore(s).clear());
    Object.entries(all.cards || {}).forEach(([h, c]) => tx.objectStore("cards").put(c, h));
    Object.entries(all.stories || {}).forEach(([h, t]) => tx.objectStore("stories").put(t, h));
    Object.entries(all.meta || {}).forEach(([k, v]) => v !== undefined && tx.objectStore("meta").put(v, k));
    (all.reviews || []).forEach(r => tx.objectStore("reviews").add(r));
    return new Promise((ok, bad) => { tx.oncomplete = () => ok(true); tx.onerror = () => bad(tx.error); });
  }

  return {
    get kind() { return db ? "indexeddb" : "memory"; },
    async load() {
      try { db = await open(); } catch (e) { db = null; }
      if (!db) return fromOld() || memory;
      const [cards, stories, meta] = await Promise.all([keyed("cards"), keyed("stories"), keyed("meta")]);
      if (!Object.keys(cards).length) {
        const old = fromOld();
        if (old) { await replaceIDB(old); return old; }
      }
      return { cards, stories, meta };
    },
    putCard: (h, c) => write("cards", s => s.put(c, h)),
    delCard: h => write("cards", s => s.delete(h)),
    putStory: (h, t) => write("stories", s => (t ? s.put(t, h) : s.delete(h))),
    putMeta: (k, v) => write("meta", s => s.put(v, k)),
    addReview: r => { memory.reviews.push(r); write("reviews", s => s.add(r)); },
    popReview: () => write("reviews", s => {
      const c = s.openCursor(null, "prev");
      c.onsuccess = () => { if (c.result) c.result.delete(); };
    }),
    async reviews() {
      if (!db) return memory.reviews;
      return req(db.transaction("reviews").objectStore("reviews").getAll());
    },
    async replace(all) {
      if (!db) { Object.assign(memory, all); return true; }
      return replaceIDB(all);
    },
  };
})();
