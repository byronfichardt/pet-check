// Security rules tests. Run with `npm test` in the firebase directory: that starts the
// Firestore emulator (Java 21 or newer is needed) and runs this file against it.
const { after, before, beforeEach, describe, it } = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const { assertFails, assertSucceeds, initializeTestEnvironment } = require("@firebase/rules-unit-testing");
const { deleteDoc, doc, getDoc, getDocs, collection, serverTimestamp, setDoc, Timestamp, updateDoc } = require("firebase/firestore");

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-petcheck",
    firestore: { rules: readFileSync(path.join(__dirname, "..", "firestore.rules"), "utf8") },
  });
});

after(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
});

const alice = () => env.authenticatedContext("alice").firestore();
const bob = () => env.authenticatedContext("bob").firestore();
const anonymous = () => env.unauthenticatedContext().firestore();

function event(overrides = {}) {
  return {
    deviceId: "device-1",
    deviceName: "Kitchen tablet",
    startedAt: Timestamp.fromMillis(1_795_000_000_000),
    durationMs: 4200,
    type: "bark",
    peakDb: -12.5,
    confidence: 0.82,
    syncedAt: serverTimestamp(),
    ...overrides,
  };
}

function alert(overrides = {}) {
  return {
    deviceId: "device-1",
    deviceName: "Kitchen tablet",
    createdAt: serverTimestamp(),
    noisySeconds: 75,
    windowMinutes: 10,
    type: "bark",
    ...overrides,
  };
}

describe("events", () => {
  it("lets the owner write and read their own events", async () => {
    const ref = doc(alice(), "users/alice/events/e1");
    await assertSucceeds(setDoc(ref, event()));
    await assertSucceeds(getDoc(ref));
    await assertSucceeds(getDocs(collection(alice(), "users/alice/events")));
    await assertSucceeds(setDoc(ref, event({ durationMs: 5000 })));
    await assertSucceeds(deleteDoc(ref));
  });

  it("accepts whole-number loudness and zero confidence", async () => {
    await assertSucceeds(setDoc(doc(alice(), "users/alice/events/e1"), event({ peakDb: -20, confidence: 0, type: "other" })));
  });

  it("keeps other users and signed-out clients out", async () => {
    await setDoc(doc(alice(), "users/alice/events/e1"), event());
    await assertFails(getDoc(doc(bob(), "users/alice/events/e1")));
    await assertFails(getDocs(collection(bob(), "users/alice/events")));
    await assertFails(setDoc(doc(bob(), "users/alice/events/e2"), event()));
    await assertFails(deleteDoc(doc(bob(), "users/alice/events/e1")));
    await assertFails(getDoc(doc(anonymous(), "users/alice/events/e1")));
    await assertFails(setDoc(doc(anonymous(), "users/alice/events/e2"), event()));
  });

  it("rejects malformed events", async () => {
    const write = (data) => setDoc(doc(alice(), "users/alice/events/bad"), data);
    await assertFails(write(event({ type: "screech" })));
    await assertFails(write(event({ durationMs: -1 })));
    await assertFails(write(event({ durationMs: 600001 })));
    await assertFails(write(event({ durationMs: 12.5 })));
    await assertFails(write(event({ peakDb: 20 })));
    await assertFails(write(event({ confidence: 1.5 })));
    await assertFails(write(event({ startedAt: 1795000000000 })));
    await assertFails(write(event({ deviceId: "" })));
    await assertFails(write(event({ deviceName: "x".repeat(61) })));
    // A client-chosen sync time would let an event hide from, or replay to, other devices.
    await assertFails(write(event({ syncedAt: Timestamp.fromMillis(1_000_000_000_000) })));
    // No extra fields, so audio or anything else cannot be attached to an event.
    await assertFails(write(event({ audio: "base64…" })));
    const { confidence, ...missing } = event();
    await assertFails(write(missing));
  });
});

describe("devices", () => {
  it("lets the owner register, update and remove a device", async () => {
    const ref = doc(alice(), "users/alice/devices/device-1");
    await assertSucceeds(setDoc(ref, { name: "Kitchen tablet", platform: "android", model: "Pixel", appVersion: "0.1.0", monitoring: false, lastSeen: serverTimestamp() }));
    await assertSucceeds(setDoc(ref, { monitoring: true, monitoringSince: serverTimestamp(), fid: "abc123" }, { merge: true }));
    await assertSucceeds(updateDoc(ref, { name: "Hallway" }));
    await assertSucceeds(deleteDoc(ref));
  });

  it("accepts a status report before the device is named", async () => {
    await assertSucceeds(setDoc(doc(alice(), "users/alice/devices/pi"), { monitoring: true, lastSeen: serverTimestamp() }, { merge: true }));
  });

  it("rejects bad fields and other users", async () => {
    const ref = doc(alice(), "users/alice/devices/device-1");
    await assertFails(setDoc(ref, { name: "x".repeat(61) }));
    await assertFails(setDoc(ref, { name: "ok", monitoring: "yes" }));
    await assertFails(setDoc(ref, { name: "ok", admin: true }));
    await assertFails(setDoc(doc(bob(), "users/alice/devices/device-1"), { name: "Mine now" }));
    await assertFails(getDocs(collection(bob(), "users/alice/devices")));
  });
});

describe("alerts", () => {
  it("lets the owner raise, read and clear alerts, but not edit them", async () => {
    const ref = doc(alice(), "users/alice/alerts/a1");
    await assertSucceeds(setDoc(ref, alert()));
    await assertSucceeds(getDoc(ref));
    await assertFails(updateDoc(ref, { noisySeconds: 1 }));
    await assertSucceeds(deleteDoc(ref));
  });

  it("rejects malformed alerts and other users", async () => {
    const write = (data) => setDoc(doc(alice(), "users/alice/alerts/bad"), data);
    await assertFails(write(alert({ type: "screech" })));
    await assertFails(write(alert({ windowMinutes: 0 })));
    await assertFails(write(alert({ noisySeconds: -5 })));
    await assertFails(write(alert({ createdAt: Timestamp.fromMillis(1_000_000_000_000) })));
    await assertFails(write(alert({ message: "<script>" })));
    await assertFails(setDoc(doc(bob(), "users/alice/alerts/a2"), alert()));
    await assertFails(getDocs(collection(anonymous(), "users/alice/alerts")));
  });
});

describe("everything else", () => {
  it("is closed", async () => {
    await assertFails(setDoc(doc(alice(), "users/alice"), { name: "Alice" }));
    await assertFails(getDoc(doc(alice(), "users/alice")));
    await assertFails(setDoc(doc(alice(), "users/alice/secrets/s1"), { value: 1 }));
    await assertFails(setDoc(doc(alice(), "public/anything"), { value: 1 }));
  });
});
