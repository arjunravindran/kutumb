/*
 * remember.js against a small in-memory IndexedDB and localStorage, and against the absence of both.
 *
 * The fake is only as faithful as it needs to be: requests and transactions complete on a later turn
 * of the event loop, and a transaction completes after its requests, which is the ordering the module
 * depends on. What is being tested is the module's own logic - one slot, replace, forget, and above all
 * "every failure is quiet" - not IndexedDB itself.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

function installBrowser({ indexedDB = true, localStorage = true, persist = true } = {}) {
  const data = new Map();
  if (indexedDB) {
    globalThis.indexedDB = {
      open() {
        const request = {};
        queueMicrotask(() => {
          const db = {
            created: false,
            createObjectStore() { db.created = true; },
            close() {},
            transaction() {
              const tx = {};
              const store = {
                put(value, key) { const r = {}; setTimeout(() => { data.set(key, value); }, 0); return r; },
                get(key) { const r = {}; setTimeout(() => { r.result = data.get(key); }, 0); return r; },
                delete(key) { const r = {}; setTimeout(() => { data.delete(key); }, 0); return r; },
              };
              tx.objectStore = () => store;
              setTimeout(() => tx.oncomplete?.(), 5);
              return tx;
            },
          };
          request.result = db;
          request.onupgradeneeded?.();
          request.onsuccess?.();
        });
        return request;
      },
    };
  } else {
    delete globalThis.indexedDB;
  }
  const prefs = new Map();
  if (localStorage) {
    globalThis.localStorage = {
      getItem: (k) => (prefs.has(k) ? prefs.get(k) : null),
      setItem: (k, v) => { prefs.set(k, String(v)); },
    };
  } else {
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, get() { throw new Error('blocked'); } });
  }
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: persist ? { storage: { persist: async () => true } } : {},
  });
  return { data, prefs };
}

// Re-import per test so module state cannot leak between them.
const load = () => import(`./remember.js?${Math.random()}`);

test('a tree that is remembered comes back as it went in', async () => {
  installBrowser();
  const { rememberTree, recallTree } = await load();
  const bytes = new Uint8Array([1, 2, 3, 4]);
  assert.equal(await rememberTree('family.ftree', bytes), true);
  const back = await recallTree();
  assert.equal(back.name, 'family.ftree');
  assert.deepEqual([...back.bytes], [1, 2, 3, 4]);
});

test('there is one slot: remembering a second tree replaces the first', async () => {
  const { data } = installBrowser();
  const { rememberTree, recallTree } = await load();
  await rememberTree('one.ftree', new Uint8Array([1]));
  await rememberTree('two.ftree', new Uint8Array([2]));
  assert.equal(data.size, 1);
  assert.equal((await recallTree()).name, 'two.ftree');
});

test('forgetting leaves nothing behind, and forgetting nothing is fine', async () => {
  const { data } = installBrowser();
  const { rememberTree, recallTree, forgetTree } = await load();
  await rememberTree('family.ftree', new Uint8Array([1]));
  assert.equal(await forgetTree(), true);
  assert.equal(data.size, 0);
  assert.equal(await recallTree(), null);
  assert.equal(await forgetTree(), true);
});

test('with no IndexedDB at all, nothing throws and nothing is remembered', async () => {
  installBrowser({ indexedDB: false });
  const { rememberTree, recallTree, forgetTree } = await load();
  assert.equal(await rememberTree('x.ftree', new Uint8Array([1])), false);
  assert.equal(await recallTree(), null);
  assert.equal(await forgetTree(), false);
});

test('a record that is not a tree is not offered as one', async () => {
  const { data } = installBrowser();
  const { recallTree } = await load();
  data.set('last', { name: 42, bytes: null });
  assert.equal(await recallTree(), null);
});

test('remembering is on until it is switched off, and off if storage cannot be read', async () => {
  installBrowser();
  let m = await load();
  assert.equal(m.rememberingIsOn(), true);
  m.setRemembering(false);
  assert.equal(m.rememberingIsOn(), false);
  m.setRemembering(true);
  assert.equal(m.rememberingIsOn(), true);

  installBrowser({ localStorage: false });
  m = await load();
  assert.equal(m.rememberingIsOn(), false, 'blocked storage must read as "do not remember"');
  assert.doesNotThrow(() => m.setRemembering(true));
});

test('switching remembering off also deletes what was kept', async () => {
  const { data } = installBrowser();
  const m = await load();
  await m.rememberTree('family.ftree', new Uint8Array([1]));
  assert.equal(data.size, 1);
  m.setRemembering(false);
  await new Promise((r) => setTimeout(r, 30));
  assert.equal(data.size, 0);
});

test('a browser with no persist() still remembers', async () => {
  installBrowser({ persist: false });
  const { rememberTree, recallTree } = await load();
  assert.equal(await rememberTree('family.ftree', new Uint8Array([9])), true);
  assert.equal((await recallTree()).name, 'family.ftree');
});
