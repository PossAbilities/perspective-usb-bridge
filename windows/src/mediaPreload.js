const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('media', {
  connect: (host, options) => ipcRenderer.invoke('media:connect', host, options),
  disconnect: () => ipcRenderer.invoke('media:disconnect'),
  onAccepted: cb => ipcRenderer.on('media:accepted', (_e, data) => cb(data)),
  onFrame: cb => ipcRenderer.on('media:frame', (_e, data) => cb(data)),
  onError: cb => ipcRenderer.on('media:error', (_e, message) => cb(message)),
  onClosed: cb => ipcRenderer.on('media:closed', () => cb())
});
