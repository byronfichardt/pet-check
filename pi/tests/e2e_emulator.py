"""End-to-end check of the Pi monitor against the Firebase emulators.

Run from the firebase directory, which starts the emulators with the project's real
security rules and then runs this script:

    npm run test:pi

It signs in as a new user, runs a recording through the real model and monitor, and reads
back what reached Firestore. It also confirms the rules stop other users and extra fields.
"""

import json
import os
import sys
import tempfile
import urllib.request
import wave
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from petcheck_pi.__main__ import main  # noqa: E402
from petcheck_pi.config import State  # noqa: E402
from petcheck_pi.firebase import FirebaseError, FirebaseSession, _post  # noqa: E402

PROJECT = "demo-petcheck"
API_KEY = "fake-api-key"


def sign_up(email: str, password: str) -> None:
    host = os.environ["FIREBASE_AUTH_EMULATOR_HOST"]
    _post(f"http://{host}/identitytoolkit.googleapis.com/v1/accounts:signUp?key={API_KEY}",
          {"email": email, "password": password, "returnSecureToken": True})


def list_documents(session: FirebaseSession, collection: str) -> list[dict]:
    host = os.environ["FIRESTORE_EMULATOR_HOST"]
    url = f"http://{host}/v1/projects/{PROJECT}/databases/(default)/documents/{collection}"
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {session.id_token()}"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read()).get("documents", [])


def check(condition: bool, message: str) -> None:
    print(("ok   " if condition else "FAIL ") + message)
    if not condition:
        sys.exit(1)


def run() -> None:
    for name in ("FIREBASE_AUTH_EMULATOR_HOST", "FIRESTORE_EMULATOR_HOST"):
        if name not in os.environ:
            sys.exit(f"{name} is not set: run this through `npm run test:pi` in the firebase directory")

    sign_up("alice@example.com", "correct horse")
    sign_up("bob@example.com", "battery staple")

    with tempfile.TemporaryDirectory() as directory:
        home = Path(directory)
        config = home / "config.toml"
        config.write_text(
            f'[monitor]\ndevice_name = "Test Pi"\nstate_dir = "{home / "state"}"\n'
            '[alerts]\nenabled = true\nnoisy_seconds = 1\nwindow_minutes = 10\ninclude_other = true\n'
            f'[firebase]\nproject_id = "{PROJECT}"\napi_key = "{API_KEY}"\n'
        )
        recording = home / "tone.wav"
        tone = (np.sin(2 * np.pi * 700 * np.arange(32000) / 16000) * 12000).astype(np.int16)
        with wave.open(str(recording), "wb") as handle:
            handle.setnchannels(1)
            handle.setsampwidth(2)
            handle.setframerate(16000)
            handle.writeframes(tone.tobytes())

        alice = FirebaseSession.sign_in(API_KEY, PROJECT, "alice@example.com", "correct horse")
        State(home / "state").save_refresh_token(alice.refresh_token)

        check(main(["--config", str(config), "run", "--wav", str(recording)]) == 0, "monitor ran and uploaded")

        events = list_documents(alice, f"users/{alice.uid}/events")
        check(len(events) == 1, "one event reached Firestore")
        fields = events[0]["fields"]
        check(sorted(fields) == ["confidence", "deviceId", "deviceName", "durationMs", "peakDb", "startedAt",
                                 "syncedAt", "type"], "the event holds metadata only")
        check(fields["durationMs"] == {"integerValue": "2000"}, "duration is the 2 s of the recording")
        check("timestampValue" in fields["syncedAt"], "the server stamped the sync time")
        check(fields["deviceName"] == {"stringValue": "Test Pi"}, "the event names its device")

        devices = list_documents(alice, f"users/{alice.uid}/devices")
        check(len(devices) == 1, "the device registered itself")
        device = devices[0]["fields"]
        check(device["platform"] == {"stringValue": "pi"} and device["monitoring"] == {"booleanValue": False},
              "the device reported that it stopped")
        check("monitoringSince" not in device, "the start time was cleared on stopping")

        alerts = list_documents(alice, f"users/{alice.uid}/alerts")
        check(len(alerts) == 1 and alerts[0]["fields"]["windowMinutes"] == {"integerValue": "10"}, "the alert was raised")

        check(main(["--config", str(config), "run", "--wav", str(recording), "--no-upload"]) == 0, "second run, local only")
        check(len(list_documents(alice, f"users/{alice.uid}/events")) == 1, "a local-only run uploads nothing")
        check(main(["--config", str(config), "run", "--wav", str(recording)]) == 0, "third run")
        check(len(list_documents(alice, f"users/{alice.uid}/events")) == 3, "the held-back event uploaded with the next one")

        bob = FirebaseSession.sign_in(API_KEY, PROJECT, "bob@example.com", "battery staple")
        try:
            list_documents(bob, f"users/{alice.uid}/events")
            check(False, "another user is refused")
        except urllib.error.HTTPError as error:
            check(error.code == 403, "another user is refused")

        try:
            alice.commit([alice.build_write(alice.user_path("events", "with-audio"), {"audio": "base64"})])
            check(False, "an event with extra fields is refused")
        except FirebaseError as error:
            check(error.status == 403, "an event with extra fields is refused")

    print("all checks passed")


if __name__ == "__main__":
    run()
