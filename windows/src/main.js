'use strict';

const { app, BrowserWindow, ipcMain, shell } = require('electron');
const path = require('path');
const os = require('os');
const dgram = require('dgram');
const { execFile, spawn } = require('child_process');
const fs = require('fs');
const { parseRemoteList, parseAttachedPorts } = require('./parseRemoteList');

const DISCOVERY_PORT = 32401;
const DISCOVERY_MAGIC_V2 = 'PERSPECTIVE_USB_BRIDGE_V2';
const DISCOVERY_MAGIC_V1 = 'PERSPECTIVE_USB_BRIDGE_V1';
const DISCOVERY_PROBE = 'PERSPECTIVE_USB_BRIDGE_DISCOVER';
const PROBE_INTERVAL_MS = 3000;
const USBIP_TIMEOUT_MS = 20000;

let mainWindow = null;
let discoverySocket = null;
let probeTimer = null;

function usbipCandidates() {
  return [
    process.env.PERSPECTIVE_USBIP_EXE,
    'C:\\Program Files\\USBip\\usbip.exe',
    'C:\\Program Files\\usbip-win2\\usbip.exe',
    'C:\\Program Files (x86)\\USBip\\usbip.exe',
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
    execFile(
      executable,
      args,
      { windowsHide: true, timeout: USBIP_TIMEOUT_MS, maxBuffer: 4 * 1024 * 1024 },
      (error, stdout, stderr) => {
        const combined = `${stdout || ''}${stderr || ''}`.trim();
        if (error) {
          if (error.killed) {
            return reject(new Error(`The tablet did not answer within ${USBIP_TIMEOUT_MS / 1000} seconds.`));
          }
          // usbip reports real problems on stderr while still exiting non-zero,
          // so prefer its own words over Node's generic message.
          return reject(new Error(combined || error.message));
        }
        resolve(combined);
      }
    );
  });
}

// --------------------------------------------------------------------- discovery

function broadcastTargets() {
  const targets = new Set(['255.255.255.255']);
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const entry of interfaces[name] || []) {
      if (entry.family !== 'IPv4' || entry.internal || !entry.netmask) continue;
      const address = entry.address.split('.').map(Number);
      const mask = entry.netmask.split('.').map(Number);
      if (address.length !== 4 || mask.length !== 4) continue;
      targets.add(address.map((octet, i) => (octet & mask[i]) | (~mask[i] & 0xff)).join('.'));
    }
  }
  return [...targets];
}

function sendProbe() {
  if (!discoverySocket) return;
  const message = Buffer.from(DISCOVERY_PROBE, 'utf8');
  for (const target of broadcastTargets()) {
    try {
      discoverySocket.send(message, DISCOVERY_PORT, target, () => {});
    } catch { /* interface disappeared between enumeration and send */ }
  }
}

function startDiscovery() {
  discoverySocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });

  discoverySocket.on('message', (msg, rinfo) => {
    const text = msg.toString('utf8').trim();
    if (text.startsWith(DISCOVERY_PROBE)) return; // our own probe looped back
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

  discoverySocket.on('error', err => {
    mainWindow?.webContents.send('bridge:discovery-error', err.message);
    try { discoverySocket.close(); } catch { /* already closed */ }
    discoverySocket = null;
  });

  discoverySocket.on('listening', () => {
    try { discoverySocket.setBroadcast(true); } catch { /* not fatal */ }
    sendProbe();
  });

  discoverySocket.bind(DISCOVERY_PORT, '0.0.0.0');

  // Announcements from the tablet can be dropped by the access point or by
  // Windows Firewall. Probing outwards opens the return path either way.
  probeTimer = setInterval(sendProbe, PROBE_INTERVAL_MS);
}

// ------------------------------------------------------------------------ window

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 560,
    height: 780,
    minWidth: 470,
    minHeight: 620,
    backgroundColor: '#1A1546',
    icon: path.join(__dirname, 'assets', 'icon-256.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  mainWindow.setMenuBarVisibility(false);
  mainWindow.loadFile(path.join(__dirname, 'index.html'));
  mainWindow.on('closed', () => { mainWindow = null; });
}

// --------------------------------------------------------------------------- ipc

ipcMain.handle('usbip:runtime-status', async () => runtimeStatus());

ipcMain.handle('usbip:probe', async () => { sendProbe(); return true; });

ipcMain.handle('usbip:install-runtime', async () => {
  const installer = bundledRuntimeInstaller();
  if (!installer) throw new Error('The signed USB/IP runtime was not bundled with this build.');

  // Elevate explicitly. The bundled Inno Setup package then performs the driver install.
  const escaped = installer.replace(/'/g, "''");
  const ps = `$p = Start-Process -FilePath '${escaped}' -ArgumentList '/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART' -Verb RunAs -Wait -PassThru; exit $p.ExitCode`;
  await new Promise((resolve, reject) => {
    const child = spawn('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', ps], {
      windowsHide: true,
      stdio: 'ignore'
    });
    child.on('error', reject);
    child.on('exit', code => {
      // Inno Setup uses 3010 for "succeeded, restart required".
      if (code === 0 || code === 3010) return resolve();
      if (code === 1223) return reject(new Error('Administrator permission was declined.'));
      reject(new Error(`Driver installer exited with code ${code}.`));
    });
  });
  return runtimeStatus();
});

ipcMain.handle('usbip:list', async (_event, host) => {
  if (!host) throw new Error('No tablet address yet.');
  const raw = await runUsbip(['list', '-r', host]);
  return { devices: parseRemoteList(raw), raw };
});

ipcMain.handle('usbip:ports', async () => {
  try {
    const raw = await runUsbip(['port']);
    return { ports: parseAttachedPorts(raw), raw };
  } catch (error) {
    return { ports: [], raw: error.message };
  }
});

async function attachedPort(host, busId) {
  try {
    const ports = parseAttachedPorts(await runUsbip(['port']));
    return ports.find(p => p.busId === busId && (!host || p.host === host)) || null;
  } catch {
    return null;
  }
}

ipcMain.handle('usbip:attach', async (_event, host, busId) => {
  const existing = await attachedPort(host, busId);
  if (existing) return `Already connected on port ${existing.port}.`;

  try {
    return await runUsbip(['attach', '-r', host, '-b', busId]);
  } catch (firstError) {
    // Retry once. Only detach the port belonging to *this* bus id — tearing down
    // every attachment would disconnect the user's other drives.
    const stale = await attachedPort(host, busId);
    if (stale) {
      try { await runUsbip(['detach', '-p', String(stale.port)]); } catch { /* best effort */ }
    }
    await new Promise(resolve => setTimeout(resolve, 1200));
    try {
      return await runUsbip(['attach', '-r', host, '-b', busId]);
    } catch (secondError) {
      throw new Error(`Connect failed after one automatic retry. ${secondError.message || firstError.message}`);
    }
  }
});

ipcMain.handle('usbip:detach', async (_event, port) => runUsbip(['detach', '-p', String(port)]));

ipcMain.handle('usbip:detach-all', async () => {
  const ports = parseAttachedPorts(await runUsbip(['port']).catch(() => ''));
  if (!ports.length) return 'Nothing was connected.';
  const failures = [];
  for (const entry of ports) {
    try {
      await runUsbip(['detach', '-p', String(entry.port)]);
    } catch (error) {
      failures.push(`port ${entry.port}: ${error.message}`);
    }
  }
  if (failures.length) throw new Error(failures.join('; '));
  return `Disconnected ${ports.length} device${ports.length === 1 ? '' : 's'}.`;
});

ipcMain.handle('shell:open-explorer', async () => { await shell.openPath('C:\\'); return true; });

// -------------------------------------------------------------------- lifecycle

const singleInstance = app.requestSingleInstanceLock();
if (!singleInstance) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) { if (mainWindow.isMinimized()) mainWindow.restore(); mainWindow.focus(); }
  });
  app.whenReady().then(() => {
    createWindow();
    startDiscovery();
  });
}

app.on('before-quit', () => {
  if (probeTimer) clearInterval(probeTimer);
  probeTimer = null;
  try { discoverySocket?.close(); } catch { /* already closed */ }
  discoverySocket = null;
});
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
