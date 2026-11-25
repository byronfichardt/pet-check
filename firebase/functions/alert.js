// The decisions behind an alert push, kept free of Firebase so they can be unit tested.

/** Error codes meaning the app was uninstalled or its registration expired. */
const GONE_CODES = new Set([
  "messaging/installation-id-not-registered",
  "messaging/registration-token-not-registered",
]);

/**
 * Picks the devices to notify: every device of the account that can receive a push,
 * except the one that raised the alert.
 *
 * @param {{deviceId?: string}} alert
 * @param {Array<{id: string, fid?: unknown}>} devices
 * @returns {Array<{id: string, fid: string}>}
 */
function selectTargets(alert, devices) {
  return devices
    .filter((device) => device.id !== alert.deviceId && typeof device.fid === "string" && device.fid.length > 0)
    .map((device) => ({ id: device.id, fid: device.fid }));
}

/**
 * Builds the data-only message. The app draws the notification itself, so the same alert
 * arriving by push and by its live listener collapses into one notification.
 * FCM data values must be strings.
 */
function buildMessage(alertId, alert, targets) {
  return {
    fids: targets.map((target) => target.fid),
    data: {
      kind: "alert",
      alertId,
      deviceName: String(alert.deviceName ?? "A monitor").slice(0, 60),
      noisySeconds: String(Number.isFinite(alert.noisySeconds) ? Math.round(alert.noisySeconds) : 0),
      windowMinutes: String(Number.isFinite(alert.windowMinutes) ? Math.round(alert.windowMinutes) : 0),
      type: String(alert.type ?? "other"),
    },
    android: {
      priority: "high",
      // An alert about noise an hour ago is no longer worth waking a phone for.
      ttl: 60 * 60 * 1000,
    },
  };
}

/** Device ids whose push registration no longer exists, given the per-target send results. */
function goneDevices(targets, responses) {
  return targets.filter((_, index) => GONE_CODES.has(responses[index]?.error?.code)).map((target) => target.id);
}

module.exports = { selectTargets, buildMessage, goneDevices };
