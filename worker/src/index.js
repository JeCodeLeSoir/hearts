const encoder = new TextEncoder();

export default {
  async fetch(request, env) {
    const origin = request.headers.get("Origin") || "";
    const cors = corsHeaders(origin, env.ALLOWED_ORIGIN);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    const url = new URL(request.url);
    if (url.pathname === "/health") return json({ ok: true }, 200, cors);

    if (url.pathname === "/auth" && request.method === "POST") {
      // Native clients do not send an Origin header. Browser origins remain restricted.
      if (origin && !cors["Access-Control-Allow-Origin"]) return json({ error: "Origine refusée." }, 403, cors);
      let body;
      try { body = await request.json(); } catch { return json({ error: "Requête invalide." }, 400, cors); }
      if (typeof body.password !== "string" || body.password.length > 128) return json({ error: "Requête invalide." }, 400, cors);
      const ip = request.headers.get("CF-Connecting-IP") || "unknown";
      const limiterId = env.ROOM.idFromName("hearts-private-room");
      const limiter = env.ROOM.get(limiterId);
      const limitResponse = await limiter.fetch("https://room.internal/rate-limit", { method: "POST", body: ip });
      if (!limitResponse.ok) return json({ error: "Trop d'essais. Réessaie dans quelques minutes." }, 429, cors);
      if (!env.APP_PASSWORD || !env.SESSION_SECRET) return json({ error: "Service non configuré." }, 503, cors);
      if (!constantTimeEqual(body.password, env.APP_PASSWORD)) return json({ error: "Mot de passe incorrect." }, 401, cors);
      const token = await signToken({ exp: Date.now() + 6 * 60 * 60 * 1000 }, env.SESSION_SECRET);
      return json({ token }, 200, { ...cors, "Cache-Control": "no-store" });
    }

    if (url.pathname === "/room") {
      if (origin && !cors["Access-Control-Allow-Origin"]) return json({ error: "Origine refusée." }, 403, cors);
      if (request.headers.get("Upgrade") !== "websocket") return json({ error: "WebSocket requis." }, 426, cors);
      if (!await verifyToken(url.searchParams.get("token") || "", env.SESSION_SECRET)) return json({ error: "Session invalide." }, 401, cors);
      const id = env.ROOM.idFromName("hearts-private-room");
      return env.ROOM.get(id).fetch(request);
    }
    return json({ error: "Introuvable." }, 404, cors);
  }
};

export class HeartRoom {
  constructor(state) {
    this.state = state;
    this.sessions = new Set();
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/rate-limit") return this.rateLimit(await request.text());
    if (this.sessions.size >= 2) return new Response("Espace complet", { status: 429 });
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();
    this.sessions.add(server);
    this.broadcast({ type: "presence", count: this.sessions.size });
    server.addEventListener("message", (event) => {
      if (typeof event.data !== "string" || event.data.length > 256) return;
      let data;
      try { data = JSON.parse(event.data); } catch { return; }
      if (data.type !== "heart") return;
      for (const peer of this.sessions) if (peer !== server) this.safeSend(peer, { type: "heart" });
    });
    const close = () => {
      this.sessions.delete(server);
      this.broadcast({ type: "presence", count: this.sessions.size });
    };
    server.addEventListener("close", close);
    server.addEventListener("error", close);
    return new Response(null, { status: 101, webSocket: client });
  }

  async rateLimit(ip) {
    const now = Date.now();
    const digest = await crypto.subtle.digest("SHA-256", encoder.encode(ip));
    const key = `rl:${base64url(new Uint8Array(digest))}`;
    const current = await this.state.storage.get(key) || { count: 0, reset: now + 10 * 60 * 1000 };
    if (now > current.reset) { current.count = 0; current.reset = now + 10 * 60 * 1000; }
    current.count += 1;
    await this.state.storage.put(key, current);
    await this.state.storage.setAlarm(now + 11 * 60 * 1000);
    return new Response(null, { status: current.count <= 10 ? 204 : 429 });
  }

  async alarm() {
    const now = Date.now();
    const records = await this.state.storage.list({ prefix: "rl:" });
    const expired = [];
    for (const [key, value] of records) if (value.reset < now) expired.push(key);
    if (expired.length) await this.state.storage.delete(expired);
    const remaining = await this.state.storage.list({ prefix: "rl:", limit: 1 });
    if (remaining.size) await this.state.storage.setAlarm(now + 11 * 60 * 1000);
  }

  safeSend(socket, data) { try { socket.send(JSON.stringify(data)); } catch { this.sessions.delete(socket); } }
  broadcast(data) { for (const socket of this.sessions) this.safeSend(socket, data); }
}

function corsHeaders(origin, allowed) {
  const isAllowed = origin && allowed && origin === allowed;
  return {
    ...(isAllowed ? { "Access-Control-Allow-Origin": origin } : {}),
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Vary": "Origin",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer"
  };
}
function json(value, status, headers) { return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json; charset=utf-8", ...headers } }); }
function constantTimeEqual(a, b) {
  const aa = encoder.encode(a); const bb = encoder.encode(b);
  let mismatch = aa.length ^ bb.length;
  const length = Math.max(aa.length, bb.length);
  for (let i = 0; i < length; i += 1) mismatch |= (aa.length ? aa[i % aa.length] : 0) ^ (bb.length ? bb[i % bb.length] : 0);
  return mismatch === 0;
}
async function hmac(value, secret) {
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(value));
  return base64url(new Uint8Array(signature));
}
async function signToken(payload, secret) {
  const data = base64url(encoder.encode(JSON.stringify(payload)));
  return `${data}.${await hmac(data, secret)}`;
}
async function verifyToken(token, secret) {
  if (!secret || !token.includes(".")) return false;
  const [data, signature] = token.split(".");
  if (!constantTimeEqual(signature, await hmac(data, secret))) return false;
  try { return JSON.parse(new TextDecoder().decode(base64urlDecode(data))).exp > Date.now(); } catch { return false; }
}
function base64url(bytes) { return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", ""); }
function base64urlDecode(value) { return Uint8Array.from(atob(value.replaceAll("-", "+").replaceAll("_", "/")), c => c.charCodeAt(0)); }
