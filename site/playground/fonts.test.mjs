/*
 * The site's typefaces are served from site/fonts/, not from Google. These checks keep it that way:
 * every file the stylesheet names exists and really is a WOFF2, every page that wants the fonts links
 * to a stylesheet that resolves, nothing under the site asks a third party for a font, and the
 * service worker caches the files the pages need so the installed viewer looks right offline.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const site = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const fonts = path.join(site, 'fonts');
const css = readFileSync(path.join(fonts, 'fonts.css'), 'utf8');

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) yield* walk(full);
    else yield full;
  }
}

test('every font file the stylesheet names exists and is a real WOFF2', () => {
  const urls = [...css.matchAll(/url\(([^)]+)\)/g)].map((m) => m[1].replace(/['"]/g, ''));
  assert.ok(urls.length >= 6, 'the stylesheet should name the Literata and JetBrains Mono files');
  for (const u of new Set(urls)) {
    assert.ok(!/^[a-z]+:/i.test(u), `${u} must be a local file, not a URL`);
    const file = path.join(fonts, u);
    assert.ok(existsSync(file), `${u} is named in fonts.css but is not in site/fonts/`);
    assert.equal(readFileSync(file).subarray(0, 4).toString('latin1'), 'wOF2', `${u} is not a WOFF2 file`);
  }
});

test('the two families the pages use are both declared, upright and italic where the pages need them', () => {
  for (const family of ['Literata', 'JetBrains Mono']) assert.ok(css.includes(`font-family: '${family}'`), family);
  assert.ok(/font-family: 'Literata';\s*font-style: italic/.test(css), 'italic Literata');
  assert.ok(css.includes('font-display: swap'), 'text must show in a fallback face while the real one loads');
});

test('the licence texts travel with the fonts', () => {
  const text = readFileSync(path.join(fonts, 'LICENSES.txt'), 'utf8');
  assert.ok(/SIL OPEN FONT LICENSE/i.test(text));
  assert.ok(text.includes('Literata') && text.includes('JetBrains Mono'));
});

test('no page, stylesheet or script on the site asks a third party for a font', () => {
  const offenders = [];
  for (const file of walk(site)) {
    if (!/\.(html|css|js|mjs)$/.test(file) || file.includes(`${path.sep}book${path.sep}`) || file.endsWith('fonts.test.mjs')) continue;
    if (/fonts\.googleapis\.com|fonts\.gstatic\.com/.test(readFileSync(file, 'utf8'))) offenders.push(path.relative(site, file));
  }
  assert.deepEqual(offenders, []);
});

test('every page that links the stylesheet links one that exists', () => {
  let linked = 0;
  for (const file of walk(site)) {
    if (!file.endsWith('.html') || file.includes(`${path.sep}book${path.sep}`)) continue;
    for (const m of readFileSync(file, 'utf8').matchAll(/<link[^>]*href="([^"]*fonts\/fonts\.css)"/g)) {
      linked++;
      assert.ok(existsSync(path.resolve(path.dirname(file), m[1])), `${path.relative(site, file)} links ${m[1]}, which does not resolve`);
    }
  }
  assert.ok(linked >= 5, `expected the five public pages to link the fonts, found ${linked}`);
});

test('the installed viewer caches the fonts it needs to look right offline', () => {
  const sw = readFileSync(path.join(site, 'playground', 'sw.js'), 'utf8');
  const shell = eval(sw.match(/const SHELL = (\[[\s\S]*?\]);/)[1]);
  for (const f of shell.filter((s) => s.startsWith('../fonts/'))) {
    assert.ok(existsSync(path.resolve(site, 'playground', f)), `${f} is precached but does not exist`);
  }
  assert.ok(shell.includes('../fonts/fonts.css'));
  assert.ok(shell.some((s) => s.includes('literata-latin-normal')));
  assert.ok(!/fonts\.googleapis|FONT_HOSTS/.test(sw), 'the worker must not special-case Google any more');
});
