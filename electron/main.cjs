const path = require('node:path');
const { app, BrowserWindow, ipcMain, desktopCapturer } = require('electron');

/**
 * Electron main process entrypoint.
 *
 * Responsibilities:
 * - Create a secure BrowserWindow.
 * - Provide an IPC method for listing desktop capture sources.
 *
 * The renderer (existing CLJS UI) remains responsible for:
 * - selecting a source
 * - calling getUserMedia with the returned chromeMediaSourceId
 */

const isDev = !app.isPackaged;

// In dev we point Electron at the running backend, which serves the UI from
// resources/public/ (shadow-cljs watch keeps resources/public/js/main.js fresh).
// In prod we load the packaged index.html.
const devUrl = process.env.SAMURAIBFF_ELECTRON_DEV_URL || 'http://localhost:8000';
const appIconPath = path.join(__dirname, '..', 'build', 'icon.png');

function createWindow() {
  const win = new BrowserWindow({
    width: 1400,
    height: 900,
    backgroundColor: '#0b0f14',
    icon: appIconPath,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      preload: path.join(__dirname, 'preload.cjs'),
    },
  });

  if (isDev) {
    win.loadURL(devUrl);
    win.webContents.openDevTools({ mode: 'detach' });
  } else {
    win.loadFile(path.join(__dirname, '..', 'resources', 'public', 'index.html'));
  }

  return win;
}

ipcMain.handle('samuraibff:listDesktopSources', async (_event, opts) => {
  const types = Array.isArray(opts?.types) ? opts.types : ['screen', 'window'];
  const thumbnailSize = opts?.thumbnailSize || { width: 320, height: 200 };

  const sources = await desktopCapturer.getSources({
    types,
    fetchWindowIcons: true,
    thumbnailSize,
  });

  return sources.map((s) => ({
    id: s.id,
    name: s.name,
    // Send thumbnails as data URLs to keep preload API simple.
    // (In the UI we can ignore these if not needed.)
    thumbnailDataUrl: s.thumbnail?.toDataURL?.() || null,
    appIconDataUrl: s.appIcon?.toDataURL?.() || null,
  }));
});

app.whenReady().then(() => {
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  // On Windows/Linux it’s conventional to quit when all windows are closed.
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
