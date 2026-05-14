const fs = require('node:fs');
const path = require('node:path');

/**
 * Sync `flag-icons` SVGs into resources/public so they can be served by the
 * backend and also packaged with Electron.
 *
 * Why copy at all?
 * - Electron production loads renderer via file:// and needs assets to be
 *   present in our packaged resources.
 *
 * Implementation:
 * - We currently sync ALL 4x3 SVGs to avoid missing icons for less-common
 *   Whisper languages.
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

// We used to sync only a small subset; keep no allowlist for now.

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

  const files = fs.readdirSync(SRC_DIR).filter((f) => f.endsWith('.svg'));
  for (const filename of files) {
    const src = path.join(SRC_DIR, filename);
    const dest = path.join(DEST_DIR, filename);
    copyIfNeeded(src, dest);
  }

  console.log(`[flags:sync] Synced ${files.length} flags to ${DEST_DIR}`);
}

main();
