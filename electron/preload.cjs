const { contextBridge, ipcRenderer } = require('electron');

/**
 * Preload bridge.
 *
 * We expose a very small API surface to the renderer.
 * The renderer stays “browser-like” and does NOT get Node.js access.
 */

contextBridge.exposeInMainWorld('samuraibffElectron', {
  listDesktopSources: async (opts) => {
    return ipcRenderer.invoke('samuraibff:listDesktopSources', opts || {});
  },
  isElectron: true,
});
