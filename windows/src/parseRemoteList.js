function parseRemoteList(output) {
  const devices = [];
  let current = null;

  for (const line of output.split(/\r?\n/)) {
    const metadata = line.match(/^\s*\(([0-9a-fA-F]{4}):([0-9a-fA-F]{4})\)\s*$/);
    if (metadata && current) {
      current.vidPid = `${metadata[1]}:${metadata[2]}`;
      continue;
    }

    let match = line.match(/^\s*-?\s*busid\s*:?\s*([\w.-]+)\s*:\s*(.*?)\s*$/i);
    if (!match) match = line.match(/^\s*-?\s*([\w.-]+)\s*:\s*(.*?)\s*$/i);
    if (!match || !match[2] || match[1].toLowerCase() === 'busid') {
      current = null;
      continue;
    }

    const inlineMetadata = match[2].match(/^(.*?)(?:\s*\(([0-9a-fA-F]{4}):([0-9a-fA-F]{4})\))\s*$/);
    current = {
      busId: match[1],
      name: (inlineMetadata ? inlineMetadata[1] : match[2]).trim() || 'USB device',
      vidPid: inlineMetadata ? `${inlineMetadata[2]}:${inlineMetadata[3]}` : 'USB device'
    };
    devices.push(current);
  }

  return devices;
}

module.exports = { parseRemoteList };