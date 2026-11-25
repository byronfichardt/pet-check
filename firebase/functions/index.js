// Pet Check push alerts.
//
// A monitor that hears more noise than its owner allows writes users/{uid}/alerts/{alertId}.
// This function sends that alert to the owner's other devices as a push, so it arrives even
// when the app is closed. It is optional: without it, alerts still reach devices on which the
// app is running. Cloud Functions need the Blaze plan. See docs/firebase-setup.md.
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { setGlobalOptions } = require("firebase-functions/v2");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { FieldValue, getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { buildMessage, goneDevices, selectTargets } = require("./alert");

initializeApp();
// A household raises a handful of alerts a day: keep the bill bounded if something goes wrong.
setGlobalOptions({ maxInstances: 3 });

exports.sendAlert = onDocumentCreated("users/{uid}/alerts/{alertId}", async (event) => {
  const alert = event.data?.data();
  if (!alert) return;
  const { uid, alertId } = event.params;

  const devices = await getFirestore().collection(`users/${uid}/devices`).get();
  const targets = selectTargets(
    alert,
    devices.docs.map((doc) => ({ id: doc.id, fid: doc.get("fid") })),
  );
  if (targets.length === 0) {
    logger.info("Alert has no other devices to notify", { alertId });
    return;
  }

  const result = await getMessaging().sendEachForMulticast(buildMessage(alertId, alert, targets));
  logger.info("Alert sent", { alertId, sent: result.successCount, failed: result.failureCount });

  // Forget registrations of devices where the app has been removed.
  const gone = goneDevices(targets, result.responses);
  await Promise.all(
    gone.map((deviceId) => getFirestore().doc(`users/${uid}/devices/${deviceId}`).update({ fid: FieldValue.delete() })),
  );
});
