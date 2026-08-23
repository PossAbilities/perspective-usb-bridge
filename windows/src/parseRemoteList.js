'use strict';

// Lines that `usbip list -r` prints around the device records, plus the shapes
// its error output takes. None of these are devices, and matching them is how
// the client used to invent phantom drives.
const NOISE = /^(exportable usb devices|usbip:|error:|warning:|-+|=+)/i;

/**
 * Parse the output of `usbip list -r <host>`.
 *
 * Real output looks like:
 *
 *   Exportable USB devices
 *   ======================
 *    - 192.168.1.42
 *           1-1: VIA Labs, Inc. : VL817 SATA Adaptor (2109:0715)
 *              : /sys/devices/platform/usb/1-1
 *              : (Defined at Interface level) (00/00/00)
 *              :  0 - Mass Storage / SCSI / Bulk-Only (08/06/50)
 *
 * Some builds wrap the vendor/product ids onto the following line instead.
 */
function parseRemoteList(output) {
  const devices = [];
  let current = null;

  for (const rawLine of String(output || '').split(/\r?\n/)) {
    const line = rawLine.replace(/\s+$/, '');
    if (!line.trim()) {
      current = null;
      continue;
    }

    // Continuation line carrying only the vendor:product pair.
    const metadata = line.match(/^\s*\(([0-9a-fA-F]{4}):([0-9a-fA-F]{4})\)\s*$/);
    if (metadata) {
      if (current) current.vidPid = `${metadata[1]}:${metadata[2]}`;
      continue;
    }

    // Detail lines are indented and start with the colon, e.g. "   : /sys/...".
    if (/^\s*:/.test(line)) continue;

    if (NOISE.test(line.trim())) {
      current = null;
      continue;
    }

    // " - 192.168.1.42" is the host header, never a device.
    if (/^\s*-\s/.test(line) && !line.includes(':')) {
      current = null;
      continue;
    }

    const match = line.match(/^\s*-?\s*([0-9]+-[0-9]+(?:[.\-][0-9]+)*)\s*:\s*(.+?)\s*$/);
    if (!match) {
      current = null;
      continue;
    }

    const inlineMetadata = match[2].match(/^(.*?)\s*\(([0-9a-fA-F]{4}):([0-9a-fA-F]{4})\)\s*$/);
    current = {
      busId: match[1],
      name: (inlineMetadata ? inlineMetadata[1] : match[2]).trim() || 'USB device',
      vidPid: inlineMetadata ? `${inlineMetadata[2]}:${inlineMetadata[3]}` : ''
    };
    devices.push(current);
  }

  return devices;
}

/**
 * Parse `usbip port`, which lists the virtual ports currently attached on this
 * PC. Used to show what is already connected and to detach one drive without
 * disturbing the others.
 *
 *   Imported USB devices
 *   ====================
 *   Port 00: <Port in Use> at High Speed(480Mbps)
 *          VIA Labs, Inc. : VL817 SATA Adaptor (2109:0715)
 *          3-1 -> usbip://192.168.1.42:3240/1-1
 */
function parseAttachedPorts(output) {
  const ports = [];
  let current = null;

  for (const rawLine of String(output || '').split(/\r?\n/)) {
    const portMatch = rawLine.match(/^\s*Port\s+(\d+)\s*:\s*(.*)$/i);
    if (portMatch) {
      current = { port: Number(portMatch[1]), state: portMatch[2].trim(), host: '', busId: '', name: '' };
      ports.push(current);
      continue;
    }
    if (!current) continue;

    const remote = rawLine.match(/usbip:\/\/([^:/\s]+)(?::(\d+))?\/(\S+)/i);
    if (remote) {
      current.host = remote[1];
      current.busId = remote[3];
      continue;
    }
    const named = rawLine.match(/^\s{2,}(\S.*?)\s*(?:\(([0-9a-fA-F]{4}):([0-9a-fA-F]{4})\))?\s*$/);
    if (named && !current.name && !/^\d+-\d+/.test(named[1])) {
      current.name = named[1].trim();
      if (named[2]) current.vidPid = `${named[2]}:${named[3]}`;
    }
  }

  return ports.filter(p => p.busId || /in use/i.test(p.state));
}

module.exports = { parseRemoteList, parseAttachedPorts };
