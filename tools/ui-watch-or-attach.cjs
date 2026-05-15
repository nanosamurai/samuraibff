const { spawn } = require('node:child_process');

/**
 * Start `shadow-cljs watch app` unless it is already running.
 *
 * Motivation:
 * - `shadow-cljs watch app` connects to an already-running shadow server.
 * - When the :app worker is already started, shadow exits with
 *   `ExceptionInfo: already started`.
 * - Our `electron:dev` script wants to be idempotent: running it while another
 *   watch is already active should still allow Electron to start.
 *
 * Behavior:
 * - If shadow exits non-zero and output contains "already started", exit 0.
 * - Otherwise, forward the exit code.
 */
function main() {
  const cmd = process.platform === 'win32' ? 'cmd.exe' : 'npx';
  const args =
    process.platform === 'win32'
      ? ['/d', '/s', '/c', 'npx shadow-cljs watch app']
      : ['shadow-cljs', 'watch', 'app'];

  const child = spawn(cmd, args, {
    stdio: ['inherit', 'pipe', 'pipe'],
    windowsHide: false,
  });

  let combined = '';

  child.stdout.on('data', (buf) => {
    process.stdout.write(buf);
    combined += buf.toString('utf8');
  });

  child.stderr.on('data', (buf) => {
    process.stderr.write(buf);
    combined += buf.toString('utf8');
  });

  child.on('close', (code) => {
    if (code === 0) {
      process.exit(0);
      return;
    }

    if (combined.includes('already started')) {
      process.exit(0);
      return;
    }

    process.exit(code ?? 1);
  });
}

main();
