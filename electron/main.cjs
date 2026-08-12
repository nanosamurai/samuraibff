const path = require('node:path');
const {
  app,
  BrowserWindow,
  desktopCapturer,
  dialog,
  ipcMain,
  session,
} = require('electron');

const policy = require('./policy.cjs');

/**
 * Electron main process entrypoint.
 *
 * The configured SamuraiBFF origin serves the renderer and remains the only
 * origin allowed to invoke the preload bridge. OIDC runs in an isolated child
 * window without a preload script.
 */

const isDev = !app.isPackaged;
const appIconPath = path.join(__dirname, '..', 'build', 'icon.png');
const windowsAppUserModelId = 'ai.nanosamurai.samuraibff';

let backendOrigin;
let mainWindow;
let authWindow;
let desktopCaptureConsentGranted = false;

/**
 * Throw unless an IPC event came from the configured BFF in the main window.
 */
function assertTrustedMainSender(event) {
  const senderUrl = event.senderFrame?.url || event.sender?.getURL?.() || '';
  const mainSender = mainWindow && !mainWindow.isDestroyed()
    && event.sender === mainWindow.webContents;

  if (!mainSender || !policy.isAuthorizedSender(senderUrl, backendOrigin)) {
    throw new Error('Electron IPC request rejected for an untrusted sender');
  }
}

/**
 * Install navigation and popup restrictions on the privileged main window.
 */
function secureMainWindow(win) {
  const guardNavigation = (event, navigationUrl) => {
    if (!policy.isAllowedMainNavigation(navigationUrl, backendOrigin)) {
      event.preventDefault();
      console.warn('Blocked Electron main-window navigation', {
        destinationOrigin: (() => {
          try {
            return new URL(navigationUrl).origin;
          } catch (_error) {
            return '<invalid-url>';
          }
        })(),
      });
    }
  };

  win.webContents.on('will-navigate', guardNavigation);
  win.webContents.on('will-redirect', guardNavigation);

  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
}

/**
 * Load the configured BFF, offering an explicit retry when it is unavailable.
 */
async function loadBackend(win) {
  try {
    await win.loadURL(`${backendOrigin}/`);
  } catch (error) {
    if (win.isDestroyed()) {
      return;
    }

    const result = await dialog.showMessageBox(win, {
      type: 'error',
      title: 'Cannot connect to nanosamur.ai',
      message: `Electron could not load ${backendOrigin}.`,
      detail: 'Check that the BFF is running and that NANOSAMURAI_API_URL is correct.',
      buttons: ['Retry', 'Quit'],
      defaultId: 0,
      cancelId: 1,
      noLink: true,
    });

    if (result.response === 0) {
      await loadBackend(win);
    } else {
      app.quit();
    }
  }
}

/**
 * Create the privileged application window and load the trusted BFF UI.
 */
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

  secureMainWindow(win);
  void loadBackend(win);

  if (isDev) {
    win.webContents.openDevTools({ mode: 'detach' });
  }

  win.on('closed', () => {
    if (mainWindow === win) {
      mainWindow = undefined;
      desktopCaptureConsentGranted = false;
    }
  });

  return win;
}

/**
 * Ask once per main-window session before exposing desktop source identifiers.
 */
async function ensureDesktopCaptureConsent() {
  if (desktopCaptureConsentGranted) {
    return;
  }

  const result = await dialog.showMessageBox(mainWindow, {
    type: 'question',
    title: 'Allow system audio capture?',
    message: 'Allow nanosamur.ai to list screens and windows for system audio capture?',
    detail: 'Only source names and identifiers are returned. Screen thumbnails are not captured.',
    buttons: ['Allow', 'Cancel'],
    defaultId: 0,
    cancelId: 1,
    noLink: true,
  });

  if (result.response !== 0) {
    throw new Error('Desktop capture permission was denied');
  }
  desktopCaptureConsentGranted = true;
}

/**
 * Run the backend-managed OIDC flow in an isolated, no-preload child window.
 *
 * Returns a Promise resolving after the callback reaches the requested BFF
 * route. The default Electron session is shared with the main window, so the
 * HttpOnly authentication cookie is available after completion.
 */
function startLogin(nextPath) {
  if (authWindow && !authWindow.isDestroyed()) {
    authWindow.focus();
    return Promise.reject(new Error('Authentication is already in progress'));
  }

  const safeNextPath = policy.normalizeNextPath(nextPath, backendOrigin);
  const loginUrl = `${backendOrigin}/auth/login?next=${encodeURIComponent(safeNextPath)}`;

  return new Promise((resolve, reject) => {
    const flowState = policy.createAuthFlowState();
    const win = new BrowserWindow({
      parent: mainWindow,
      modal: true,
      show: false,
      width: 720,
      height: 820,
      backgroundColor: '#0b0f14',
      webPreferences: {
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
      },
    });
    authWindow = win;

    const finish = (error) => {
      const transitioned = error ? flowState.fail() : flowState.complete();
      if (!transitioned) {
        return;
      }
      authWindow = undefined;
      if (!win.isDestroyed()) {
        win.close();
      }
      if (error) {
        reject(error);
      } else {
        resolve({ ok: true });
      }
    };

    const observeNavigation = (_event, navigationUrl) => {
      if (policy.isAuthCompletion(navigationUrl, backendOrigin, safeNextPath)) {
        finish();
      }
    };

    const guardAuthNavigation = (event, navigationUrl) => {
      if (!policy.isAllowedAuthNavigation(navigationUrl, backendOrigin)) {
        event.preventDefault();
        finish(new Error('Authentication attempted an unsafe navigation'));
      }
    };

    win.webContents.on('will-navigate', guardAuthNavigation);
    win.webContents.on('will-redirect', guardAuthNavigation);
    win.webContents.on('did-navigate', observeNavigation);
    win.webContents.on('did-navigate-in-page', observeNavigation);
    win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    win.once('ready-to-show', () => win.show());
    win.once('closed', () => {
      authWindow = undefined;
      if (flowState.cancel()) {
        reject(new Error('Authentication was cancelled'));
      }
    });

    win.loadURL(loginUrl).catch((error) => finish(error));
  });
}

/**
 * Register the minimal IPC surface exposed by preload.cjs.
 */
function registerIpcHandlers() {
  ipcMain.handle('samuraibff:listDesktopSources', async (event) => {
    assertTrustedMainSender(event);
    await ensureDesktopCaptureConsent();

    const sources = await desktopCapturer.getSources({
      types: ['screen', 'window'],
      fetchWindowIcons: false,
      thumbnailSize: { width: 0, height: 0 },
    });

    return sources.map((source) => ({
      id: source.id,
      name: source.name,
    }));
  });

  ipcMain.handle('samuraibff:login', async (event, nextPath) => {
    assertTrustedMainSender(event);
    return startLogin(nextPath);
  });
}

/**
 * Deny Chromium permissions by default and allow media only for the trusted
 * main renderer. Desktop-source consent remains a separate explicit prompt.
 */
function configureSessionPermissions() {
  const allowedPermissions = new Set(['display-capture', 'media']);

  session.defaultSession.setPermissionRequestHandler((webContents, permission, callback) => {
    const trusted = mainWindow && !mainWindow.isDestroyed()
      && webContents === mainWindow.webContents
      && policy.isAuthorizedSender(webContents.getURL(), backendOrigin);
    callback(Boolean(trusted && allowedPermissions.has(permission)));
  });

  session.defaultSession.setPermissionCheckHandler((webContents, permission, requestingOrigin) => {
    const trusted = mainWindow && !mainWindow.isDestroyed()
      && webContents === mainWindow.webContents
      && policy.isAuthorizedSender(requestingOrigin, backendOrigin);
    return Boolean(trusted && allowedPermissions.has(permission));
  });
}

app.whenReady().then(() => {
  try {
    backendOrigin = policy.resolveBackendOrigin(process.env);
  } catch (error) {
    dialog.showErrorBox('Invalid nanosamur.ai backend URL', error.message);
    app.quit();
    return;
  }

  console.info('Starting Electron against configured BFF', { backendOrigin });

  if (process.platform === 'win32') {
    app.setAppUserModelId(windowsAppUserModelId);
  }

  configureSessionPermissions();
  registerIpcHandlers();
  mainWindow = createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      mainWindow = createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
