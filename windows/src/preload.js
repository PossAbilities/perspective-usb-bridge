const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('bridge', {
  runtimeStatus: () => ipcRenderer.invoke('usbip:runtime-status'),
  installRuntime: () => ipcRenderer.invoke('usbip:install-runtime'),
  list: host => ipcRenderer.invoke('usbip:list', host),
  attach: (host, busId) => ipcRenderer.invoke('usbip:attach', host, busId),
  detachAll: () => ipcRenderer.invoke('usbip:detach-all'),
  onDiscovered: callback => ipcRenderer.on('bridge:discovered', (_event, data) => callback(data))
});
