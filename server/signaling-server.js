// Serveur de signalisation Bubble — volontairement minuscule et amnésique.
//
// Rôle UNIQUE : router SDP/ICE entre les 2 pairs d'un couple. Le serveur :
//  - ne stocke RIEN (aucune base, aucun log de contenu, tout est en mémoire vive) ;
//  - n'accepte que des rooms STRICTES de 2 pairs (pairage 1:1 exclusif) : un 3e
//    connecté est refusé immédiatement (code 4003) ;
//  - ne lit jamais les messages : relai octet-à-octet vers l'autre pair.
//
// Usage : node signaling-server.js  (PORT=8787 par défaut)
// Route : ws://host:8787/room/<coupleId>?peer=<fingerprint>

const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 8787;
const HEARTBEAT_MS = 30_000;

/** coupleId -> Map<fingerprint, WebSocket> (mémoire vive uniquement). */
const rooms = new Map();

const server = new WebSocketServer({ port: PORT });

server.on("connection", (socket, request) => {
  const url = new URL(request.url, "ws://localhost");
  const match = url.pathname.match(/^\/room\/([\w-]+)$/);
  const peerId = url.searchParams.get("peer");

  if (!match || !peerId) {
    socket.close(4000, "room et peer requis");
    return;
  }
  const roomId = match[1];

  let room = rooms.get(roomId);
  if (!room) {
    room = new Map();
    rooms.set(roomId, room);
  }

  // Room stricte de 2 : une reconnexion du même fingerprint remplace l'ancienne
  // socket (réseau mobile), mais un 3e fingerprint est refusé net.
  if (!room.has(peerId) && room.size >= 2) {
    socket.close(4003, "room pleine : pairage 1:1 exclusif");
    return;
  }
  const previous = room.get(peerId);
  if (previous) previous.close(4001, "remplacé par une nouvelle connexion");
  room.set(peerId, socket);

  socket.isAlive = true;
  socket.on("pong", () => (socket.isAlive = true));

  socket.on("message", (data) => {
    // Relai aveugle : jamais parsé, jamais journalisé, jamais persisté.
    for (const [otherId, other] of room) {
      if (otherId !== peerId && other.readyState === other.OPEN) {
        other.send(data.toString());
      }
    }
  });

  socket.on("close", () => {
    if (room.get(peerId) === socket) room.delete(peerId);
    if (room.size === 0) rooms.delete(roomId);
  });
});

// Nettoyage des connexions mortes (réseau mobile) : ping/pong toutes les 30 s.
const heartbeat = setInterval(() => {
  for (const room of rooms.values()) {
    for (const socket of room.values()) {
      if (!socket.isAlive) {
        socket.terminate();
        continue;
      }
      socket.isAlive = false;
      socket.ping();
    }
  }
}, HEARTBEAT_MS);

server.on("close", () => clearInterval(heartbeat));

console.log(`[bubble-signaling] ws://0.0.0.0:${PORT} — rooms strictes de 2, zéro persistance`);
