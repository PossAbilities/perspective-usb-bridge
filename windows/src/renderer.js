'use strict';

const $ = s => document.querySelector(s);
const host = $('#host');
const status = $('#status');
const devices = $('#devices');

let lastDiscovery = null;
let runtimeReady = false;
let attachedPorts = [];
let scanning = false;

function setStatus(text, error = false) {
  status.textContent = text;
  status.style.color = error ? '#F4592B' : '#CFE96A';
}

function escapeHtml(value) {
  return String(value).replace(/[&<>'"]/g, ch =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[ch]));
}

/** Turn usbip's terse failures into something a non-engineer can act on. */
function explain(message) {
  const text = String(message || '');
  if (/not installed yet/i.test(text)) return 'Install the Windows USB driver first.';
  if (/did not answer within/i.test(text)) {
    return `${text} Check the tablet is awake, on the same Wi-Fi, and that the Perspective app is open.`;
  }
  if (/connection refused|actively refused|10061/i.test(text)) {
    return 'The tablet refused the connection. Open Perspective USB Bridge on the tablet and share a drive.';
  }
  if (/timed out|10060|unreachable/i.test(text)) {
    return 'No reply from the tablet. Both devices must be on the same network, and Wi-Fi client isolation must be off.';
  }
  if (/no such (device|file)|not found/i.test(text)) {
    return 'That drive is no longer shared on the tablet. Tap Share with Windows again, then rescan.';
  }
  if (/access is denied|administrator/i.test(text)) {
    return 'Windows denied access. Run Perspective USB Bridge as Administrator and try again.';
  }
  return text;
}

async function checkRuntime() {
  try {
    const runtime = await window.bridge.runtimeStatus();
    const el = $('#runtimeStatus');
    const install = $('#installRuntime');
    const warning = $('#runtimeWarning');
    runtimeReady = runtime.installed;
    const info = runtime.buildInfo || {};
    const releaseText = info.release ? ` ${info.release}` : '';
    const hashText = info.sha256 ? ` · SHA-256 ${info.sha256.slice(0, 12)}…` : '';
    if (runtime.installed) {
      el.textContent = `Ready. USB/IP runtime${releaseText} found${hashText}.`;
      el.style.color = '#CFE96A';
      install.hidden = true;
      warning.hidden = true;
    } else {
      el.textContent = runtime.bundledInstaller
        ? `One setup step remains. Bundled USB/IP runtime${releaseText}${hashText}.`
        : 'USB/IP Windows driver is not installed and this development build does not contain the signed runtime.';
      el.style.color = '#F4592B';
      install.hidden = !runtime.bundledInstaller;
      warning.hidden = !runtime.bundledInstaller;
    }
  } catch (_err) {
    $('#runtimeStatus').textContent = 'Could not check the Windows USB/IP runtime.';
  }
}

async function refreshPorts() {
  if (!runtimeReady) return;
  try {
    const result = await window.bridge.ports();
    attachedPorts = result.ports || [];
  } catch {
    attachedPorts = [];
  }
}

function attachmentFor(busId) {
  const target = host.value.trim();
  return attachedPorts.find(p => p.busId === busId && (!p.host || !target || p.host === target)) || null;
}

function renderDevice(device, target) {
  const attachment = attachmentFor(device.busId);
  const el = document.createElement('article');
  el.className = 'device';
  const meta = [device.busId, device.vidPid].filter(Boolean).map(escapeHtml).join(' · ');
  el.innerHTML =
    `<div><h2>${escapeHtml(device.name)}</h2><div class="meta">${meta}</div></div>` +
    `<button>${attachment ? 'Disconnect' : 'Connect drive'}</button>`;

  const button = el.querySelector('button');
  button.addEventListener('click', async () => {
    button.disabled = true;
    try {
      if (attachmentFor(device.busId)) {
        setStatus(`Disconnecting ${device.name}…`);
        await window.bridge.detach(attachmentFor(device.busId).port);
        setStatus(`${device.name} disconnected safely.`);
      } else {
        setStatus(`Connecting ${device.name}… Windows may take a few seconds to mount it.`);
        await window.bridge.attach(target, device.busId);
        setStatus(`${device.name} connected. Open File Explorer to see the drive letter.`);
      }
      await refreshPorts();
      await scan(true);
    } catch (err) {
      setStatus(explain(err.message), true);
      button.disabled = false;
    }
  });
  return el;
}

async function scan(auto = false) {
  if (scanning) return;
  const target = host.value.trim();
  if (!runtimeReady) return setStatus('Install the Windows USB driver first.', true);
  if (!target) {
    window.bridge.probe();
    return setStatus('Waiting for your Samsung tablet… You can also type its IP address above.');
  }

  scanning = true;
  devices.innerHTML = '';
  setStatus(auto ? 'Tablet found. Checking shared drives…' : 'Scanning…');
  try {
    await refreshPorts();
    const result = await window.bridge.list(target);
    $('#rawResponse').textContent = result.raw || '(empty response)';
    $('#diagnostics').hidden = false;

    if (!result.devices.length) {
      return setStatus('Tablet found, but no USB drives are currently shared. Tap "Share with Windows" on the tablet.');
    }
    const connected = result.devices.filter(d => attachmentFor(d.busId)).length;
    setStatus(
      `${result.devices.length} shared USB device${result.devices.length === 1 ? '' : 's'} found` +
      `${connected ? `, ${connected} connected` : ''}.`
    );
    for (const device of result.devices) devices.appendChild(renderDevice(device, target));
  } catch (err) {
    setStatus(explain(err.message), true);
  } finally {
    scanning = false;
  }
}

$('#installRuntime').addEventListener('click', async e => {
  e.target.disabled = true;
  $('#runtimeStatus').textContent = 'Windows will ask for Administrator permission. Installing signed USB driver…';
  try {
    const result = await window.bridge.installRuntime();
    if (!result.installed) throw new Error('The installer completed but usbip.exe was not found. A restart may be required.');
    await checkRuntime();
    setStatus('Windows USB driver installed. Ready to find your Samsung.');
  } catch (err) {
    $('#runtimeStatus').textContent = explain(err.message);
    e.target.disabled = false;
  }
});

$('#scan').addEventListener('click', () => { window.bridge.probe(); scan(false); });
$('#detach').addEventListener('click', async () => {
  try {
    const message = await window.bridge.detachAll();
    setStatus(message || 'All USB devices disconnected safely.');
    await refreshPorts();
    await scan(true);
  } catch (err) {
    setStatus(explain(err.message), true);
  }
});
host.addEventListener('keydown', e => { if (e.key === 'Enter') scan(false); });

window.bridge.onDiscovered(info => {
  const previous = lastDiscovery;
  const changed = !previous || previous.host !== info.host || previous.sharedCount !== info.sharedCount;
  lastDiscovery = info;
  // Never clobber an address the user typed by hand.
  if (!host.value.trim() || (previous && host.value.trim() === previous.host)) host.value = info.host;
  const countText = Number.isFinite(info.sharedCount) ? ` · ${info.sharedCount} shared` : '';
  $('#discoveryHint').textContent = `Perspective USB Bridge found on ${info.host}${countText}.`;
  if (changed && runtimeReady) scan(true);
});

window.bridge.onDiscoveryError(message => {
  $('#discoveryHint').textContent =
    `Automatic discovery is unavailable (${message}). Type the tablet's IP address shown in the Android app.`;
});

(async () => {
  await checkRuntime();
  await refreshPorts();
  // Re-check periodically: drives get shared and unshared on the tablet while
  // this window is open, and the tablet may join the network later.
  setInterval(() => { if (runtimeReady && host.value.trim()) scan(true); }, 8000);
})();
