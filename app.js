import * as THREE from "https://cdn.jsdelivr.net/npm/three@0.181.1/build/three.module.js";

// Replace this after deploying the Worker. No password or auth token belongs here.
const API_URL = "https://hearts-api.dark-aurelien-poke-worker.workers.dev";
const SESSION_KEY = "hearts.session-token";

const login = document.querySelector("#login");
const room = document.querySelector("#room");
const form = document.querySelector("#login-form");
const password = document.querySelector("#password");
const togglePassword = document.querySelector("#toggle-password");
const loginError = document.querySelector("#login-error");
const presence = document.querySelector("#presence");
const toast = document.querySelector("#toast");
const screenTrigger = document.querySelector(".screen-trigger");
const soundToggle = document.querySelector("#sound-toggle");

let sessionToken = "";
let socket;
let reconnectTimer;
let visual;
let audioContext;
let soundEnabled = true;

togglePassword.addEventListener("click", () => {
  const reveal = password.type === "password";
  password.type = reveal ? "text" : "password";
  togglePassword.setAttribute("aria-pressed", String(reveal));
  togglePassword.setAttribute("aria-label", reveal ? "Masquer le mot de passe" : "Afficher le mot de passe");
});

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  ensureAudio();
  loginError.textContent = "";
  const submit = form.querySelector("button[type=submit]");
  submit.disabled = true;
  try {
    if (API_URL.includes("YOUR-WORKER")) throw new Error("Cette app doit d'abord être reliée à son Worker.");
    const response = await fetch(`${API_URL}/auth`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ password: password.value })
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || "Mot de passe incorrect.");
    if (!isSessionValid(data.token)) throw new Error("Session invalide reçue du serveur.");
    saveSession(data.token);
    password.value = "";
    enterRoom();
  } catch (error) {
    loginError.textContent = error.message || "Connexion impossible.";
    password.focus();
  } finally {
    submit.disabled = false;
  }
});

room.addEventListener("click", (event) => {
  if (event.target.closest("#sound-toggle")) return;
  sendHeart(event);
});

soundToggle.addEventListener("click", (event) => {
  event.stopPropagation();
  soundEnabled = !soundEnabled;
  soundToggle.setAttribute("aria-pressed", String(soundEnabled));
  soundToggle.setAttribute("aria-label", soundEnabled ? "Couper le son" : "Réactiver le son");
  soundToggle.classList.toggle("muted", !soundEnabled);
  if (soundEnabled) {
    ensureAudio();
    playParticleSound(false);
  }
});

function sendHeart(event) {
  if (!socket || socket.readyState !== WebSocket.OPEN) return;
  const keyboardClick = event.detail === 0;
  const point = keyboardClick ? { x: innerWidth / 2, y: innerHeight / 2 } : { x: event.clientX, y: event.clientY };
  socket.send(JSON.stringify({
    type: "heart",
    x: Math.max(0, Math.min(1, point.x / innerWidth)),
    y: Math.max(0, Math.min(1, point.y / innerHeight))
  }));
  visual.burst(true, point);
  playParticleSound(false, point.x);
  showToast("Cœur envoyé");
}

function connect() {
  clearTimeout(reconnectTimer);
  if (!isSessionValid(sessionToken)) {
    clearSession();
    showLogin("Ta session a expiré. Entre à nouveau le mot de passe.");
    return;
  }
  const url = new URL(API_URL);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.pathname = "/room";
  url.searchParams.set("token", sessionToken);
  socket = new WebSocket(url);
  presence.classList.remove("online");
  presence.querySelector("b").textContent = "Connexion…";
  screenTrigger.disabled = true;

  socket.addEventListener("open", () => {
    presence.classList.add("online");
    room.classList.add("connected");
    screenTrigger.disabled = false;
    presence.querySelector("b").textContent = "Connecté·e";
  });
  socket.addEventListener("message", ({ data }) => {
    try {
      const message = JSON.parse(data);
      if (message.type === "heart" && isNormalizedCoordinate(message.x) && isNormalizedCoordinate(message.y)) {
        const point = { x: message.x * innerWidth, y: message.y * innerHeight };
        visual.burst(false, point);
        playParticleSound(true, point.x);
        showToast("Un cœur vient d'arriver");
      }
      if (message.type === "presence") {
        presence.querySelector("b").textContent = message.count === 2 ? "Vous êtes deux" : "En attente de l'autre";
      }
      if (message.type === "full") {
        showToast("Cet espace est déjà occupé");
      }
    } catch { /* Ignore malformed server frames. */ }
  });
  socket.addEventListener("close", (event) => {
    room.classList.remove("connected");
    screenTrigger.disabled = true;
    presence.classList.remove("online");
    presence.querySelector("b").textContent = event.code === 4003 ? "Espace complet" : "Hors ligne";
    if (event.code !== 4003 && isSessionValid(sessionToken)) reconnectTimer = setTimeout(connect, 2000);
    else if (!isSessionValid(sessionToken)) {
      clearSession();
      showLogin("Ta session a expiré. Entre à nouveau le mot de passe.");
    }
  });
}

function enterRoom() {
  login.hidden = true;
  room.hidden = false;
  if (!visual) visual = createHeartScene(document.querySelector("#scene"));
  connect();
}

function showLogin(message = "") {
  clearTimeout(reconnectTimer);
  socket?.close(1000, "session ended");
  login.hidden = false;
  room.hidden = true;
  loginError.textContent = message;
  password.focus();
}

function saveSession(token) {
  sessionToken = token;
  try { sessionStorage.setItem(SESSION_KEY, token); } catch { /* Private browsing may block storage. */ }
}

function clearSession() {
  sessionToken = "";
  try { sessionStorage.removeItem(SESSION_KEY); } catch { /* Storage may be unavailable. */ }
}

function isSessionValid(token) {
  if (typeof token !== "string") return false;
  const [payload, signature, extra] = token.split(".");
  if (!payload || !signature || extra) return false;
  try {
    const base64 = payload.replaceAll("-", "+").replaceAll("_", "/").padEnd(Math.ceil(payload.length / 4) * 4, "=");
    const data = JSON.parse(atob(base64));
    return Number.isFinite(data.exp) && data.exp > Date.now();
  } catch {
    return false;
  }
}

function restoreSession() {
  let token = "";
  try { token = sessionStorage.getItem(SESSION_KEY) || ""; } catch { /* Storage may be unavailable. */ }
  if (!isSessionValid(token)) {
    clearSession();
    return;
  }
  sessionToken = token;
  enterRoom();
}

function isNormalizedCoordinate(value) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 1;
}

function showToast(message) {
  toast.textContent = message;
  toast.classList.add("show");
  setTimeout(() => toast.classList.remove("show"), 2200);
}

function ensureAudio() {
  if (!audioContext) {
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) return null;
    audioContext = new AudioContextClass();
  }
  if (audioContext.state === "suspended") audioContext.resume().catch(() => {});
  return audioContext;
}

function playParticleSound(incoming, screenX = innerWidth / 2) {
  if (!soundEnabled) return;
  const context = ensureAudio();
  if (!context) return;
  const now = context.currentTime;
  const master = context.createGain();
  const filter = context.createBiquadFilter();
  filter.type = "lowpass";
  filter.frequency.setValueAtTime(3200, now);
  master.gain.setValueAtTime(.0001, now);
  master.gain.exponentialRampToValueAtTime(.11, now + .018);
  master.gain.exponentialRampToValueAtTime(.0001, now + .62);
  filter.connect(master);

  if (context.createStereoPanner) {
    const panner = context.createStereoPanner();
    panner.pan.setValueAtTime(Math.max(-.7, Math.min(.7, (screenX / innerWidth) * 1.4 - .7)), now);
    master.connect(panner);
    panner.connect(context.destination);
  } else {
    master.connect(context.destination);
  }

  const notes = incoming ? [523.25, 783.99, 1046.5] : [659.25, 880, 1318.51];
  notes.forEach((frequency, index) => {
    const oscillator = context.createOscillator();
    const voice = context.createGain();
    oscillator.type = index === 0 ? "sine" : "triangle";
    oscillator.frequency.setValueAtTime(frequency, now + index * .035);
    oscillator.frequency.exponentialRampToValueAtTime(frequency * 1.035, now + .3);
    voice.gain.setValueAtTime(.0001, now + index * .035);
    voice.gain.exponentialRampToValueAtTime(index === 0 ? .72 : .24, now + .025 + index * .035);
    voice.gain.exponentialRampToValueAtTime(.0001, now + .42 + index * .06);
    oscillator.connect(voice);
    voice.connect(filter);
    oscillator.start(now + index * .035);
    oscillator.stop(now + .7);
  });
}

function createHeartScene(container) {
  const reduced = matchMedia("(prefers-reduced-motion: reduce)");
  let noMotion = reduced.matches;
  reduced.addEventListener("change", (event) => { noMotion = event.matches; });
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(42, innerWidth / innerHeight, .1, 100);
  camera.position.set(0, 0, 12);
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.setSize(innerWidth, innerHeight);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  container.appendChild(renderer.domElement);

  scene.add(new THREE.AmbientLight(0x8ffaff, 1.65));
  const key = new THREE.PointLight(0x00f0ff, 38, 30);
  key.position.set(4, 4, 6);
  scene.add(key);
  const fill = new THREE.PointLight(0xff2b88, 28, 22);
  fill.position.set(-5, -2, 4);
  scene.add(fill);

  const shape = new THREE.Shape();
  shape.moveTo(0, -1.8);
  shape.bezierCurveTo(-.3, -1.35, -2.25, -.35, -2.25, 1);
  shape.bezierCurveTo(-2.25, 2.5, -.3, 2.65, 0, 1.45);
  shape.bezierCurveTo(.3, 2.65, 2.25, 2.5, 2.25, 1);
  shape.bezierCurveTo(2.25, -.35, .3, -1.35, 0, -1.8);
  const geometry = new THREE.ExtrudeGeometry(shape, { depth: .65, bevelEnabled: true, bevelSegments: 6, steps: 1, bevelSize: .22, bevelThickness: .22, curveSegments: 32 });
  geometry.center();
  const particles = [];
  const particlePalette = [0xff2b88, 0xff5fa7, 0x00f0ff, 0xf4f000, 0xffffff];
  const clock = new THREE.Clock();
  function screenToWorld(x, y) {
    const point = new THREE.Vector3((x / innerWidth) * 2 - 1, -(y / innerHeight) * 2 + 1, .2);
    point.unproject(camera);
    const direction = point.sub(camera.position).normalize();
    return camera.position.clone().add(direction.multiplyScalar(-camera.position.z / direction.z));
  }
  function burst(self, screenPosition) {
    if (noMotion) {
      return;
    }
    const origin = screenPosition?.x != null
      ? screenToWorld(screenPosition.x, screenPosition.y)
      : screenToWorld(innerWidth * .82, innerHeight * .78);
    for (let i = 0; i < 20; i += 1) {
      const material = new THREE.MeshPhysicalMaterial({
        color: particlePalette[Math.floor(Math.random() * particlePalette.length)],
        emissive: self ? 0x260012 : 0x003038,
        emissiveIntensity: .72,
        roughness: .18,
        clearcoat: 1,
        transparent: true,
        depthWrite: false
      });
      const particle = new THREE.Mesh(geometry, material);
      const size = .035 + Math.random() * .075;
      particle.scale.setScalar(size);
      particle.position.copy(origin);
      particle.position.x += (Math.random() - .5) * .42;
      particle.position.y += (Math.random() - .5) * .24;
      particle.position.z = -.3 + Math.random() * 2.2;
      particle.rotation.set(
        (Math.random() - .5) * .8,
        (Math.random() - .5) * 1.2,
        (Math.random() - .5) * .5
      );
      particle.userData = {
        age: 0,
        life: 1.65 + Math.random() * 1.35,
        speed: 1.15 + Math.random() * 1.6,
        drift: (Math.random() - .5) * 1.2,
        phase: Math.random() * Math.PI * 2,
        spin: (Math.random() - .5) * 2.4,
        baseScale: size
      };
      particles.push(particle);
      scene.add(particle);
    }
    while (particles.length > 90) {
      const oldest = particles.shift();
      scene.remove(oldest);
      oldest.material.dispose();
    }
  }
  function render() {
    requestAnimationFrame(render);
    const dt = Math.min(clock.getDelta(), .04);
    for (let index = particles.length - 1; index >= 0; index -= 1) {
      const particle = particles[index];
      particle.userData.age += dt;
      const progress = particle.userData.age / particle.userData.life;
      particle.position.y += particle.userData.speed * dt;
      particle.position.x += (particle.userData.drift + Math.sin(particle.userData.age * 4 + particle.userData.phase) * .75) * dt;
      particle.rotation.y += particle.userData.spin * dt;
      particle.rotation.z += particle.userData.spin * .35 * dt;
      const pulse = 1 + Math.sin(particle.userData.age * 8 + particle.userData.phase) * .08;
      particle.scale.setScalar(particle.userData.baseScale * pulse);
      particle.material.opacity = Math.min(1, progress * 5) * Math.max(0, 1 - Math.pow(progress, 2.2));
      if (progress >= 1) {
        scene.remove(particle);
        particle.material.dispose();
        particles.splice(index, 1);
      }
    }
    renderer.render(scene, camera);
  }
  render();
  addEventListener("resize", () => {
    camera.aspect = innerWidth / innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(innerWidth, innerHeight);
  });
  return { burst };
}

restoreSession();
