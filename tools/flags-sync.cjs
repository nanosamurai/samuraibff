const fs = require('node:fs');
const path = require('node:path');

/**
 * Sync a minimal subset of `flag-icons` SVGs into resources/public so they can be
 * served by the backend and also packaged with Electron.
 *
 * We intentionally do NOT copy the whole library (it's large). Instead we copy
 * only flags for regions we currently derive from Intl.Locale(...).maximize().region.
 *
 * Currently included:
 * - CZ, US, GB, DE, FR, ES, IT, NL, PL, UA, RU, SK
 *
 * If a needed flag is missing at runtime, UI falls back to the globe emoji.
 */

const REPO_ROOT = path.join(__dirname, '..');

const SRC_DIR = path.join(
  REPO_ROOT,
  'node_modules',
  'flag-icons',
  'flags',
  '4x3'
);

const DEST_DIR = path.join(REPO_ROOT, 'resources', 'public', 'img', 'flags', '4x3');

const REGIONS = [
  'cz',
  'us',
  'gb',
  'de',
  'fr',
  'es',
  'it',
  'nl',
  'pl',
  'ua',
  'ru',
  'sk',
];

function ensureDir(p) {
  fs.mkdirSync(p, { recursive: true });
}

function copyIfNeeded(src, dest) {
  const srcStat = fs.statSync(src);
  const destStat = fs.existsSync(dest) ? fs.statSync(dest) : null;

  // Copy if dest missing or file size differs.
  if (!destStat || destStat.size !== srcStat.size) {
    fs.copyFileSync(src, dest);
  }
}

function main() {
  if (!fs.existsSync(SRC_DIR)) {
    console.error(`[flags:sync] Missing source dir: ${SRC_DIR}`);
    console.error('[flags:sync] Did you run `npm install`?');
    process.exit(1);
  }

  ensureDir(DEST_DIR);

  for (const region of REGIONS) {
    const filename = `${region}.svg`;
    const src = path.join(SRC_DIR, filename);
    const dest = path.join(DEST_DIR, filename);

    if (!fs.existsSync(src)) {
      console.warn(`[flags:sync] Missing flag-icons SVG for region: ${region} (${src})`);
      continue;
    }

    copyIfNeeded(src, dest);
  }

  console.log(`[flags:sync] Synced ${REGIONS.length} flags to ${DEST_DIR}`);
}

main();
