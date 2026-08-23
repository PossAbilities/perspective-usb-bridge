const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('bridge', {
  runtimeStatus: () => ipcRenderer.invoke('usbip:runtime-status'),
  installRuntime: () => ipcRenderer.invoke('usbip:install-runtime'),
  list: host => ipcRenderer.invoke('usbip:list', host),
  ports: () => ipcRenderer.invoke('usbip:ports'),
  attach: (host, busId) => ipcRenderer.invoke('usbip:attach', host, busId),
  detach: port => ipcRenderer.invoke('usbip:detach', port),
  detachAll: () => ipcRenderer.invoke('usbip:detach-all'),
  probe: () => ipcRenderer.invoke('usbip:probe'),
  openExplorer: () => ipcRenderer.invoke('shell:open-explorer'),
  onDiscovered: callback => ipcRenderer.on('bridge:discovered', (_event, data) => callback(data)),
  onDiscoveryError: callback => ipcRenderer.on('bridge:discovery-error', (_event, message) => callback(message))
});
