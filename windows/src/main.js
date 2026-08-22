const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const dgram = require('dgram');
const { execFile, spawn } = require('child_process');
const fs = require('fs');

const DISCOVERY_PORT = 32401;
const DISCOVERY_MAGIC_V2 = 'PERSPECTIVE_USB_BRIDGE_V2';
const DISCOVERY_MAGIC_V1 = 'PERSPECTIVE_USB_BRIDGE_V1';
let mainWindow = null;
let discoverySocket = null;

function usbipCandidates() {
  return [
    process.env.PERSPECTIVE_USBIP_EXE,
    'C:\\Program Files\\USBip\\usbip.exe',
    'C:\\Program Files\\usbip-win2\\usbip.exe',
    path.join(process.resourcesPath || '', 'usbip', 'usbip.exe')
  ].filter(Boolean);
}

function usbipPath() {
  return usbipCandidates().find(candidate => fs.existsSync(candidate)) || usbipCandidates()[0];
}

function bundledRuntimeInstaller() {
  const candidates = [
    path.join(process.resourcesPath || '', 'runtime', 'usbip-win2-installer.exe'),
    path.join(__dirname, '..', 'runtime', 'usbip-win2-installer.exe')
  ];
  return candidates.find(candidate => fs.existsSync(candidate)) || '';
}

function runtimeBuildInfo() {
  const candidates = [
    path.join(process.resourcesPath || '', 'runtime', 'RUNTIME-BUILD-INFO.txt'),
    path.join(__dirname, '..', 'runtime', 'RUNTIME-BUILD-INFO.txt')
  ];
  const infoPath = candidates.find(candidate => fs.existsSync(candidate));
  if (!infoPath) return { release: '', asset: '', sha256: '' };
  const fields = {};
  for (const line of fs.readFileSync(infoPath, 'utf8').split(/\r?\n/)) {
    const index = line.indexOf(':');
    if (index <= 0) continue;
    fields[line.slice(0, index).trim().toLowerCase()] = line.slice(index + 1).trim();
  }
  return {
    release: fields.release || '',
    asset: fields.asset || '',
    sha256: fields.sha256 || ''
  };
}

function runtimeStatus() {
  const exe = usbipPath();
  const installer = bundledRuntimeInstaller();
  return {
    installed: Boolean(exe && fs.existsSync(exe)),
    path: exe || '',
    bundledInstaller: Boolean(installer),
    installerPath: installer,
    buildInfo: runtimeBuildInfo()
  };
}

function runUsbip(args) {
  return new Promise((resolve, reject) => {
    const executable = usbipPath();
    if (!executable || !fs.existsSync(executable)) {
      return reject(new Error('The Windows USB/IP runtime is not installed yet.'));
    }
    execFile(executable, args, { windowsHide: true }, (error, stdout, stderr) => {
      if (error) return reject(new Error((stderr || error.message).trim()));
      resolve((stdout || '').trim());
    });
  });
}

function parseRemoteList(output) {
  const devices = [];
  const lines = output.split(/\r?\n/);
  for (const line of lines) {
    let match = line.match(/^\s*-?\s*busid\s*:\s*([\w.-]+)\s*\((.*?)\)\s*$/i);
    if (match) {
      const metadata = match[2].match(/^(.*?)(?:\s+\(([0-9a-fA-F]{4}):([0-9a-fA-F]{4})\))?$/);
      devices.push({
        busId: match[1],
        name: metadata[1],
        vidPid: metadata[2] && metadata[3] ? `${metadata[2]}:${metadata[3]}` : 'USB device'
      });
      continue;
    }

    match = line.match(/^\s*-?\s*([\w.-]+):\s*(.*?)\s*(?:\(([0-9a-fA-F]{4}):([0-9a-fA-F]{4})\))?\s*$/i);
    if (!match || !match[2] || match[1].toLowerCase() === 'busid') continue;
    devices.push({
      busId: match[1],
      name: match[2],
      vidPid: match[3] && match[4] ? `${match[3]}:${match[4]}` : 'USB device'
    });
  }
  return devices;
}

function startDiscovery() {
  discoverySocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  discoverySocket.on('message', (msg, rinfo) => {
    const text = msg.toString('utf8');
    const parts = text.split('|');
    if (parts[0] === DISCOVERY_MAGIC_V2 && parts.length >= 3) {
      mainWindow?.webContents.send('bridge:discovered', {
        host: rinfo.address,
        usbipPort: Number(parts[1]) || 3240,
        sharedCount: Number(parts[2]) || 0,
        protocol: 2
      });
      return;
    }
    if (parts[0] === DISCOVERY_MAGIC_V1 && parts.length >= 4) {
      mainWindow?.webContents.send('bridge:discovered', {
        host: rinfo.address,
        busId: parts[1],
        name: parts[2],
        usbipPort: Number(parts[3]) || 3240,
        sharedCount: 1,
        protocol: 1
      });
    }
  });
  discoverySocket.on('error', () => {});
  discoverySocket.bind(DISCOVERY_PORT, '0.0.0.0');
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 560,
    height: 760,
    minWidth: 470,
    minHeight: 620,
    backgroundColor: '#1A1546',
    webPreferences: { preload: path.join(__dirname, 'preload.js') }
  });
  mainWindow.setMenuBarVisibility(false);
  mainWindow.loadFile(path.join(__dirname, 'index.html'));
  mainWindow.on('closed', () => { mainWindow = null; });
}

ipcMain.handle('usbip:runtime-status', async () => runtimeStatus());

ipcMain.handle('usbip:install-runtime', async () => {
  const installer = bundledRuntimeInstaller();
  if (!installer) throw new Error('The signed USB/IP runtime was not bundled with this build.');

  // Elevate explicitly. The bundled Inno Setup package then performs the driver install.
  const escaped = installer.replace(/'/g, "''");
  const ps = `Start-Process -FilePath '${escaped}' -ArgumentList '/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART' -Verb RunAs -Wait`;
  await new Promise((resolve, reject) => {
    const child = spawn('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', ps], {
      windowsHide: true,
      stdio: 'ignore'
    });
    child.on('error', reject);
    child.on('exit', code => code === 0 ? resolve() : reject(new Error(`Driver installer exited with code ${code}.`)));
  });
  return runtimeStatus();
});

ipcMain.handle('usbip:list', async (_event, host) => {
  const raw = await runUsbip(['list', '-r', host]);
  return { devices: parseRemoteList(raw), raw };
});

ipcMain.handle('usbip:attach', async (_event, host, busId) => {
  try {
    return await runUsbip(['attach', '-r', host, '-b', busId]);
  } catch (firstError) {
    try { await runUsbip(['detach', '--all']); } catch {}
    await new Promise(resolve => setTimeout(resolve, 900));
    try {
      return await runUsbip(['attach', '-r', host, '-b', busId]);
    } catch (secondError) {
      throw new Error(`Connect failed after automatic retry. ${secondError.message || firstError.message}`);
    }
  }
});

ipcMain.handle('usbip:detach-all', async () => {
  try { return await runUsbip(['detach', '-all']); }
  catch { return await runUsbip(['detach', '--all']); }
});

app.whenReady().then(() => {
  createWindow();
  startDiscovery();
});
app.on('before-quit', () => { try { discoverySocket?.close(); } catch {} });
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
