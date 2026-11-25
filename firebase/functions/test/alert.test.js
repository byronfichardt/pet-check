const { describe, it } = require("node:test");
const assert = require("node:assert/strict");
const { buildMessage, goneDevices, selectTargets } = require("../alert");

const alert = { deviceId: "pi", deviceName: "Living room Pi", noisySeconds: 75, windowMinutes: 10, type: "bark" };

describe("selectTargets", () => {
  it("notifies every other device that can receive a push", () => {
    const targets = selectTargets(alert, [
      { id: "pi", fid: "fid-pi" },
      { id: "phone", fid: "fid-phone" },
      { id: "tablet", fid: "fid-tablet" },
      { id: "old-phone" },
      { id: "broken", fid: 42 },
      { id: "empty", fid: "" },
    ]);
    assert.deepEqual(targets, [
      { id: "phone", fid: "fid-phone" },
      { id: "tablet", fid: "fid-tablet" },
    ]);
  });

  it("returns nothing when the monitor is the only device", () => {
    assert.deepEqual(selectTargets(alert, [{ id: "pi", fid: "fid-pi" }]), []);
  });
});

describe("buildMessage", () => {
  it("sends a high-priority data message with string values", () => {
    const message = buildMessage("a1", alert, [{ id: "phone", fid: "fid-phone" }]);
    assert.deepEqual(message, {
      fids: ["fid-phone"],
      data: { kind: "alert", alertId: "a1", deviceName: "Living room Pi", noisySeconds: "75", windowMinutes: "10", type: "bark" },
      android: { priority: "high", ttl: 3600000 },
    });
    assert.equal("notification" in message, false);
  });

  it("copes with missing or odd fields", () => {
    const message = buildMessage("a2", { noisySeconds: "lots", deviceName: "x".repeat(200) }, [{ id: "p", fid: "f" }]);
    assert.equal(message.data.noisySeconds, "0");
    assert.equal(message.data.windowMinutes, "0");
    assert.equal(message.data.type, "other");
    assert.equal(message.data.deviceName.length, 60);
  });
});

describe("goneDevices", () => {
  it("picks out devices whose registration no longer exists", () => {
    const targets = [{ id: "phone", fid: "a" }, { id: "tablet", fid: "b" }, { id: "watch", fid: "c" }];
    const responses = [
      { success: true },
      { success: false, error: { code: "messaging/installation-id-not-registered" } },
      { success: false, error: { code: "messaging/internal-error" } },
    ];
    assert.deepEqual(goneDevices(targets, responses), ["tablet"]);
  });
});
