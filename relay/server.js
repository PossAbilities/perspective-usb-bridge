'use strict';

/**
 * Camera relay for Perspective USB Bridge.
 *
 * The tablet and the computer are often on different networks, where neither
 * can accept an incoming connection. Both dial out to this relay instead:
 *
 *   wss://<host>/relay?role=tablet&code=<pairing code>
 *   wss://<host>/relay?role=viewer&code=<pairing code>
 *
 * Once both ends of a code are present, every binary message from one is sent
 * unchanged to the other. The relay never parses the media protocol, so it
 * needs no changes when that protocol does.
 *
 * Holds no data and writes nothing to disk.
 */

const http = require('http');
const { WebSocketServer } = require('ws');

const CODE = /^[A-Z0-9]{8,32}$/;
const MAX_MESSAGE = 16 * 1024 * 1024;
const PING_MS = 25_000; // under Cloudflare's 100 s idle timeout

/** Close codes the clients turn into plain-language messages. */
const CLOSE = {
  BAD_REQUEST: 4000,
  TABLET_OFFLINE: 4004,
  REPLACED: 4009,
  PEER_LEFT: 4010
};

function createRelay({ log = () => {} } = {}) {
  /** code -> { tablet, viewer } */
  const rooms = new Map();

  const server = http.createServer((req, res) => {
    if (req.url === '/health') {
      res.writeHead(200, { 'content-type': 'text/plain', 'cache-control': 'no-store' });
      return res.end('ok');
    }
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('Perspective camera relay');
  });

  const wss = new WebSocketServer({ server, path: '/relay', maxPayload: MAX_MESSAGE });

  function room(code) {
    if (!rooms.has(code)) rooms.set(code, { tablet: null, viewer: null });
    return rooms.get(code);
  }

  function leave(code, role, socket) {
    const entry = rooms.get(code);
    if (!entry || entry[role] !== socket) return;
    entry[role] = null;
    const other = entry[role === 'tablet' ? 'viewer' : 'tablet'];
    // A session is one viewer's stream. When either side goes, end the other
    // so the tablet starts clean for the next viewer.
    if (other) other.close(CLOSE.PEER_LEFT, role === 'tablet' ? 'Tablet disconnected' : 'Viewer disconnected');
    if (!entry.tablet && !entry.viewer) rooms.delete(code);
  }

  wss.on('connection', (socket, req) => {
    const url = new URL(req.url, 'http://relay');
    const role = url.searchParams.get('role');
    const code = (url.searchParams.get('code') || '').toUpperCase();
    if ((role !== 'tablet' && role !== 'viewer') || !CODE.test(code)) {
      return socket.close(CLOSE.BAD_REQUEST, 'Bad role or pairing code');
    }

    const entry = room(code);
    if (role === 'viewer' && !entry.tablet) {
      return socket.close(CLOSE.TABLET_OFFLINE, 'Tablet is not connected to the relay');
    }
    // Newest connection wins: a reconnecting device must not be locked out
    // by its own stale socket.
    const previous = entry[role];
    if (previous) {
      entry[role] = null;
      previous.close(CLOSE.REPLACED, 'Replaced by a newer connection');
    }
    entry[role] = socket;
    log(`${role} joined ${code.slice(0, 3)}…`);

    socket.isAlive = true;
    socket.on('pong', () => { socket.isAlive = true; });
    socket.on('message', (data, isBinary) => {
      const other = entry[role === 'tablet' ? 'viewer' : 'tablet'];
      if (other && other.readyState === other.OPEN) other.send(data, { binary: isBinary });
    });
    socket.on('close', () => leave(code, role, socket));
    socket.on('error', () => socket.terminate());
  });

  const heartbeat = setInterval(() => {
    for (const socket of wss.clients) {
      if (!socket.isAlive) { socket.terminate(); continue; }
      socket.isAlive = false;
      socket.ping();
    }
  }, PING_MS);
  wss.on('close', () => clearInterval(heartbeat));

  function close() {
    clearInterval(heartbeat);
    for (const socket of wss.clients) socket.terminate();
    wss.close();
    server.close();
  }

  return { server, wss, rooms, close };
}

if (require.main === module) {
  const port = Number(process.env.PORT) || 8080;
  const relay = createRelay({ log: message => console.log(new Date().toISOString(), message) });
  relay.server.listen(port, () => console.log(`Perspective camera relay listening on ${port}`));
  process.on('SIGTERM', () => { relay.close(); process.exit(0); });
}

module.exports = { createRelay, CLOSE };
