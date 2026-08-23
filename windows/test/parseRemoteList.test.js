'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { parseRemoteList, parseAttachedPorts } = require('../src/parseRemoteList');

test('parses the two-line VIA Labs response', () => {
  const raw = `1-3002 : VIA Labs, Inc. : VL817 SATA Adaptor
(2109:0715)`;
  assert.deepEqual(parseRemoteList(raw), [{
    busId: '1-3002',
    name: 'VIA Labs, Inc. : VL817 SATA Adaptor',
    vidPid: '2109:0715'
  }]);
});

test('parses the full usbip list -r layout', () => {
  const raw = [
    'Exportable USB devices',
    '======================',
    ' - 192.168.1.42',
    '        1-1: VIA Labs, Inc. : VL817 SATA Adaptor (2109:0715)',
    '           : /sys/devices/platform/usb/1-1',
    '           : (Defined at Interface level) (00/00/00)',
    '           :  0 - Mass Storage / SCSI / Bulk-Only (08/06/50)',
    '',
    '        1-2: SanDisk Corp. : Ultra Fit (0781:5583)',
    '           : /sys/devices/platform/usb/1-2',
    ''
  ].join('\r\n');

  assert.deepEqual(parseRemoteList(raw), [
    { busId: '1-1', name: 'VIA Labs, Inc. : VL817 SATA Adaptor', vidPid: '2109:0715' },
    { busId: '1-2', name: 'SanDisk Corp. : Ultra Fit', vidPid: '0781:5583' }
  ]);
});

test('never invents devices from headers, rules or errors', () => {
  const noise = [
    'Exportable USB devices',
    '======================',
    ' - 192.168.1.42',
    'usbip: error: failed to open /usr/share/hwdata/usb.ids',
    'error: unable to bind device on busid 1-1',
    ''
  ].join('\n');
  assert.deepEqual(parseRemoteList(noise), []);
});

test('handles an empty or absent response', () => {
  assert.deepEqual(parseRemoteList(''), []);
  assert.deepEqual(parseRemoteList(undefined), []);
  assert.deepEqual(parseRemoteList('Exportable USB devices\n======================\n - 192.168.1.42\n'), []);
});

test('accepts hub-style bus ids', () => {
  const raw = '        1-2.4: Seagate : Expansion Desk (0bc2:3312)';
  assert.deepEqual(parseRemoteList(raw), [
    { busId: '1-2.4', name: 'Seagate : Expansion Desk', vidPid: '0bc2:3312' }
  ]);
});

test('parses attached ports from usbip port', () => {
  const raw = [
    'Imported USB devices',
    '====================',
    'Port 00: <Port in Use> at High Speed(480Mbps)',
    '       VIA Labs, Inc. : VL817 SATA Adaptor (2109:0715)',
    '       3-1 -> usbip://192.168.1.42:3240/1-1',
    'Port 01: <Port in Use> at Super Speed(5000Mbps)',
    '       SanDisk Corp. : Ultra Fit (0781:5583)',
    '       3-2 -> usbip://192.168.1.42:3240/1-2'
  ].join('\r\n');

  const ports = parseAttachedPorts(raw);
  assert.equal(ports.length, 2);
  assert.equal(ports[0].port, 0);
  assert.equal(ports[0].host, '192.168.1.42');
  assert.equal(ports[0].busId, '1-1');
  assert.equal(ports[1].port, 1);
  assert.equal(ports[1].busId, '1-2');
});

test('reports no ports when nothing is attached', () => {
  assert.deepEqual(parseAttachedPorts('Imported USB devices\n====================\n'), []);
  assert.deepEqual(parseAttachedPorts(''), []);
});
