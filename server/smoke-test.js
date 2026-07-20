// Test de fumée du serveur de signalisation : lance le serveur, connecte 2 pairs,
// vérifie le relai offer/answer/ICE et le refus strict d'un 3e pair. Exit 0 = OK.

const { spawn } = require("child_process");
const WebSocket = require("ws");

const PORT = 8787;
const BASE = `ws://127.0.0.1:${PORT}/room/couple-test`;

function connect(peer) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${BASE}?peer=${peer}`);
    ws.on("open", () => resolve(ws));
    ws.on("error", reject);
  });
}

function nextMessage(ws) {
  return new Promise((resolve) => ws.once("message", (d) => resolve(d.toString())));
}

async function main() {
  const server = spawn("node", ["signaling-server.js"], { cwd: __dirname, env: { ...process.env, PORT } });
  await new Promise((r) => setTimeout(r, 600)); // laisse le serveur écouter

  try {
    const alice = await connect("fp-alice");
    const bob = await connect("fp-bob");

    // 1. Relai SDP : l'offre d'Alice arrive telle quelle chez Bob, et inversement.
    const offer = JSON.stringify({ type: "offer", sdp: "v=0 fake-sdp-alice" });
    alice.send(offer);
    if ((await nextMessage(bob)) !== offer) throw new Error("relai offer cassé");

    const answer = JSON.stringify({ type: "answer", sdp: "v=0 fake-sdp-bob" });
    bob.send(answer);
    if ((await nextMessage(alice)) !== answer) throw new Error("relai answer cassé");

    // 2. Relai ICE.
    const ice = JSON.stringify({ type: "ice", candidate: "candidate:0 1 udp ...", sdpMid: "0", sdpMLineIndex: 0 });
    alice.send(ice);
    if ((await nextMessage(bob)) !== ice) throw new Error("relai ICE cassé");

    // 3. Room stricte : un 3e fingerprint est refusé avec le code 4003.
    const rejection = await new Promise((resolve) => {
      const intruder = new WebSocket(`${BASE}?peer=fp-intruder`);
      intruder.on("close", (code) => resolve(code));
      intruder.on("error", () => {});
    });
    if (rejection !== 4003) throw new Error(`intrus non refusé (code ${rejection})`);

    // 4. Reconnexion du même fingerprint : acceptée (remplace l'ancienne socket).
    const aliceAgain = await connect("fp-alice");
    aliceAgain.send(offer);
    if ((await nextMessage(bob)) !== offer) throw new Error("relai après reconnexion cassé");

    console.log("SMOKE OK : relai SDP/ICE, room stricte de 2, reconnexion même pair");
    [alice, bob, aliceAgain].forEach((w) => w.close());
    process.exitCode = 0;
  } catch (error) {
    console.error("SMOKE FAILED :", error.message);
    process.exitCode = 1;
  } finally {
    server.kill();
  }
}

main();
