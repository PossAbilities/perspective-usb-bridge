const $ = s => document.querySelector(s);
const host = $('#host'), status = $('#status'), devices = $('#devices');
let lastDiscovery = null;
let runtimeReady = false;

function setStatus(text, error=false){ status.textContent=text; status.style.color=error?'#F4592B':'#CFE96A'; }

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

$('#installRuntime').addEventListener('click', async e => {
  e.target.disabled = true;
  $('#runtimeStatus').textContent = 'Windows will ask for Administrator permission. Installing signed USB driver…';
  try {
    const result = await window.bridge.installRuntime();
    if (!result.installed) throw new Error('The installer completed but usbip.exe was not found. A restart may be required.');
    await checkRuntime();
    setStatus('Windows USB driver installed. Ready to find your Samsung.');
  } catch (err) {
    $('#runtimeStatus').textContent = err.message;
    e.target.disabled = false;
  }
});

async function scan(auto=false) {
  const target = host.value.trim();
  if (!runtimeReady) return setStatus('Install the Windows USB driver first.', true);
  if (!target) return setStatus('Waiting for your Samsung tablet…');
  devices.innerHTML=''; setStatus(auto ? 'Tablet found. Checking shared drives…' : 'Scanning…');
  try {
    const result = await window.bridge.list(target);
    if (!result.devices.length) return setStatus('Tablet found, but no USB drives are currently shared.');
    setStatus(`${result.devices.length} shared USB device${result.devices.length===1?'':'s'} found.`);
    for (const d of result.devices) {
      const el=document.createElement('article'); el.className='device';
      el.innerHTML=`<div><h2>${escapeHtml(d.name)}</h2><div class="meta">${escapeHtml(d.busId)} · ${escapeHtml(d.vidPid)}</div></div><button>Connect drive</button>`;
      el.querySelector('button').addEventListener('click', async e => {
        e.target.disabled=true; setStatus(`Connecting ${d.name}…`);
        try {
          await window.bridge.attach(target, d.busId);
          setStatus(`${d.name} connected to Windows.`);
          e.target.textContent='Connected';
        } catch(err){ setStatus(err.message,true); e.target.disabled=false; }
      });
      devices.appendChild(el);
    }
  } catch(err) { setStatus(err.message,true); }
}

$('#scan').addEventListener('click', () => scan(false));
$('#detach').addEventListener('click', async()=>{ try{await window.bridge.detachAll();setStatus('All USB devices disconnected safely.');}catch(err){setStatus(err.message,true);} });

window.bridge.onDiscovered(info => {
  const changed = !lastDiscovery || lastDiscovery.host !== info.host || lastDiscovery.sharedCount !== info.sharedCount;
  lastDiscovery = info;
  host.value = info.host;
  const countText = Number.isFinite(info.sharedCount) ? ` · ${info.sharedCount} shared` : '';
  $('#discoveryHint').textContent = `Perspective USB Bridge found on ${info.host}${countText}.`;
  if (changed && runtimeReady) scan(true);
});

function escapeHtml(value) {
  return String(value).replace(/[&<>'"]/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[ch]));
}

checkRuntime();
