'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const WebSocket = require('ws');
const { createRelay, CLOSE } = require('../server');

async function start() {
  const relay = createRelay();
  await new Promise(resolve => relay.server.listen(0, '127.0.0.1', resolve));
  const url = `ws://127.0.0.1:${relay.server.address().port}/relay`;
  return { relay, url };
}

function open(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    ws.once('open', () => resolve(ws));
    ws.once('error', reject);
  });
}

const nextMessage = ws => new Promise(resolve => ws.once('message', data => resolve(Buffer.from(data))));
const closed = ws => new Promise(resolve => ws.once('close', (code, reason) => resolve({ code, reason: String(reason) })));

test('pipes bytes both ways between a tablet and a viewer with the same code', async () => {
  const { relay, url } = await start();
  const tablet = await open(`${url}?role=tablet&code=ABCD2345`);
  const viewer = await open(`${url}?role=viewer&code=abcd2345`);

  const atTablet = nextMessage(tablet);
  viewer.send(Buffer.from('PSPMEDIA-request'));
  assert.equal((await atTablet).toString(), 'PSPMEDIA-request');

  const atViewer = nextMessage(viewer);
  tablet.send(Buffer.from([0, 1, 2, 255]));
  assert.deepEqual([...(await atViewer)], [0, 1, 2, 255]);

  tablet.close(); viewer.close();
  relay.close();
});

test('a viewer is told when the tablet is not online', async () => {
  const { relay, url } = await start();
  const viewer = new WebSocket(`${url}?role=viewer&code=NOBODY123`);
  const { code } = await closed(viewer);
  assert.equal(code, CLOSE.TABLET_OFFLINE);
  relay.close();
});

test('codes are isolated from each other', async () => {
  const { relay, url } = await start();
  const tabletA = await open(`${url}?role=tablet&code=AAAAAAAA`);
  await open(`${url}?role=tablet&code=BBBBBBBB`);
  const viewerB = await open(`${url}?role=viewer&code=BBBBBBBB`);
  let leaked = false;
  tabletA.on('message', () => { leaked = true; });
  viewerB.send(Buffer.from('for B only'));
  await new Promise(r => setTimeout(r, 100));
  assert.equal(leaked, false);
  relay.close();
});

test('when the viewer leaves, the tablet is closed so it can start fresh', async () => {
  const { relay, url } = await start();
  const tablet = await open(`${url}?role=tablet&code=CCCC2222`);
  const viewer = await open(`${url}?role=viewer&code=CCCC2222`);
  const tabletClosed = closed(tablet);
  viewer.close();
  assert.equal((await tabletClosed).code, CLOSE.PEER_LEFT);
  relay.close();
});

test('bad codes and roles are refused', async () => {
  const { relay, url } = await start();
  assert.equal((await closed(new WebSocket(`${url}?role=tablet&code=x`))).code, CLOSE.BAD_REQUEST);
  assert.equal((await closed(new WebSocket(`${url}?role=admin&code=ABCDEFGH`))).code, CLOSE.BAD_REQUEST);
  relay.close();
});

test('a reconnecting tablet replaces its stale socket', async () => {
  const { relay, url } = await start();
  const old = await open(`${url}?role=tablet&code=DDDD3333`);
  const oldClosed = closed(old);
  await open(`${url}?role=tablet&code=DDDD3333`);
  assert.equal((await oldClosed).code, CLOSE.REPLACED);
  relay.close();
});
