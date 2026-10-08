/*
 * Keeping the last tree on this device, so the viewer opens on the family instead of a file picker.
 *
 * Only the web page uses this. The desktop app has its own way (`ftreeDesktop.lastTree`), and the
 * file is only ever written to this browser's own storage (IndexedDB): it is never sent anywhere.
 *
 * Everything here is allowed to fail quietly. A private window, a blocked-storage setting, a full
 * disk and an old browser all end the same way - nothing is remembered and the viewer carries on
 * as if this file did not exist - because a tree that opens is better than a tree that fails to
 * open because it could not be remembered.
 *
 * One tree, one slot. Remembering a new one replaces the old one; forgetting leaves nothing behind.
 */

const DATABASE = 'kutumb-viewer';
const STORE = 'tree';
const KEY = 'last';
const PREF = 'ftree.remember';

function open() {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') { reject(new Error('no IndexedDB')); return; }
    const request = indexedDB.open(DATABASE, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('blocked'));
  });
}

/** Runs one request against the store and closes the connection, whatever happens. */
async function withStore(mode, run) {
  const db = await open();
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, mode);
      const result = run(tx.objectStore(STORE));
      tx.oncomplete = () => resolve(result?.result);
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error ?? new Error('aborted'));
    });
  } finally {
    db.close();
  }
}

/** Whether the person has left remembering on. On unless they turned it off; unreadable storage means off. */
export function rememberingIsOn() {
  try { return localStorage.getItem(PREF) !== '0'; } catch { return false; }
}

export function setRemembering(on) {
  try { localStorage.setItem(PREF, on ? '1' : '0'); } catch { /* the choice just does not stick */ }
  if (!on) forgetTree();
}

/** Saves the tree's bytes and name. Returns whether it worked. */
export async function rememberTree(name, bytes) {
  try {
    await withStore('readwrite', (store) => store.put({ name, bytes, savedAt: Date.now() }, KEY));
    // Ask the browser not to evict it under storage pressure. A hint; iOS may ignore it.
    try { await navigator.storage?.persist?.(); } catch { /* optional */ }
    return true;
  } catch {
    return false;
  }
}

/** The remembered tree as `{ name, bytes }`, or null if there is none or it cannot be read. */
export async function recallTree() {
  try {
    const saved = await withStore('readonly', (store) => store.get(KEY));
    if (saved && typeof saved.name === 'string' && saved.bytes) return { name: saved.name, bytes: saved.bytes };
  } catch { /* nothing to recall */ }
  return null;
}

/** Removes the remembered tree. True when it is gone (or there was none), false if removal itself failed. */
export async function forgetTree() {
  try {
    await withStore('readwrite', (store) => store.delete(KEY));
    return true;
  } catch {
    return false;
  }
}
