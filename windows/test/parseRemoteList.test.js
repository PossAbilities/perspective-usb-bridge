const assert = require('node:assert/strict');
const { parseRemoteList } = require('../src/parseRemoteList');

const rawResponse = `1-3002 : VIA Labs, Inc. : VL817 SATA Adaptor
(2109:0715)`;

assert.deepEqual(parseRemoteList(rawResponse), [{
  busId: '1-3002',
  name: 'VIA Labs, Inc. : VL817 SATA Adaptor',
  vidPid: '2109:0715'
}]);

console.log('parseRemoteList detects the two-line VIA Labs device response');