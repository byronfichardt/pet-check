"""Minimal Firebase client over REST: email sign-in and Firestore writes.

The monitor signs in as the owner, exactly like the Android app, so the Firestore security
rules apply to it and no service-account key has to be kept on the device. Only the standard
library is used.

Setting FIREBASE_AUTH_EMULATOR_HOST and FIRESTORE_EMULATOR_HOST points it at the local
Firebase emulators, as with the official SDKs.
"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

TIMEOUT_SECONDS = 20
SERVER_TIME = object()   # sentinel: let Firestore fill in the time of the write


class FirebaseError(Exception):
    def __init__(self, message: str, status: int | None = None):
        super().__init__(message)
        self.status = status


def _post(url: str, body: dict, token: str | None = None, form: bool = False) -> dict:
    if form:
        data = urllib.parse.urlencode(body).encode()
        headers = {"Content-Type": "application/x-www-form-urlencoded"}
    else:
        data = json.dumps(body).encode()
        headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace")
        try:
            detail = json.loads(detail)["error"]["message"]
        except (ValueError, KeyError, TypeError):
            pass
        raise FirebaseError(f"{error.code}: {detail}", error.code) from None
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise FirebaseError(f"network error: {error}") from None


def encode_value(value):
    """Python value -> Firestore REST value."""
    if isinstance(value, bool):
        return {"booleanValue": value}
    if isinstance(value, int):
        return {"integerValue": str(value)}
    if isinstance(value, float):
        return {"doubleValue": value}
    if isinstance(value, str):
        return {"stringValue": value}
    if isinstance(value, datetime):
        stamp = value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + f"{value.microsecond // 1000:03d}Z"
        return {"timestampValue": stamp}
    raise TypeError(f"cannot store {type(value).__name__} in Firestore")


def timestamp_ms(epoch_ms: int) -> datetime:
    return datetime.fromtimestamp(epoch_ms / 1000, tz=timezone.utc)


class FirebaseSession:
    """A signed-in user. Refreshes its ID token as needed."""

    def __init__(self, api_key: str, project_id: str, refresh_token: str):
        self.api_key = api_key
        self.project_id = project_id
        self.refresh_token = refresh_token
        self.uid: str | None = None
        self._id_token: str | None = None
        self._expires_at = 0.0

    # --- Authentication ---

    @staticmethod
    def _auth_base(service: str) -> str:
        emulator = os.environ.get("FIREBASE_AUTH_EMULATOR_HOST")
        return f"http://{emulator}/{service}" if emulator else f"https://{service}"

    @classmethod
    def sign_in(cls, api_key: str, project_id: str, email: str, password: str) -> "FirebaseSession":
        url = f"{cls._auth_base('identitytoolkit.googleapis.com')}/v1/accounts:signInWithPassword?key={api_key}"
        reply = _post(url, {"email": email, "password": password, "returnSecureToken": True})
        session = cls(api_key, project_id, reply["refreshToken"])
        session.uid = reply["localId"]
        session._id_token = reply["idToken"]
        session._expires_at = time.time() + int(reply.get("expiresIn", 3600))
        return session

    def id_token(self) -> str:
        if self._id_token is None or time.time() > self._expires_at - 300:
            url = f"{self._auth_base('securetoken.googleapis.com')}/v1/token?key={self.api_key}"
            reply = _post(url, {"grant_type": "refresh_token", "refresh_token": self.refresh_token}, form=True)
            self._id_token = reply["id_token"]
            self.refresh_token = reply.get("refresh_token", self.refresh_token)
            self.uid = reply["user_id"]
            self._expires_at = time.time() + int(reply.get("expires_in", 3600))
        return self._id_token

    # --- Firestore ---

    def _documents(self) -> str:
        return f"projects/{self.project_id}/databases/(default)/documents"

    def _firestore_base(self) -> str:
        emulator = os.environ.get("FIRESTORE_EMULATOR_HOST")
        return f"http://{emulator}/v1" if emulator else "https://firestore.googleapis.com/v1"

    def build_write(self, path: str, fields: dict, merge: bool = False, delete: tuple[str, ...] = ()) -> dict:
        """One Firestore write. Values equal to SERVER_TIME become the server's timestamp.

        With merge, only the given fields are touched and the fields named in delete are
        removed; otherwise the document is replaced.
        """
        plain = {key: value for key, value in fields.items() if value is not SERVER_TIME}
        server_times = [key for key, value in fields.items() if value is SERVER_TIME]
        write: dict = {
            "update": {
                "name": f"{self._documents()}/{path}",
                "fields": {key: encode_value(value) for key, value in plain.items()},
            }
        }
        if merge:
            write["updateMask"] = {"fieldPaths": sorted([*plain, *delete])}
        if server_times:
            write["updateTransforms"] = [
                {"fieldPath": key, "setToServerValue": "REQUEST_TIME"} for key in sorted(server_times)
            ]
        return write

    def commit(self, writes: list[dict]) -> None:
        if not writes:
            return
        token = self.id_token()
        _post(f"{self._firestore_base()}/{self._documents()}:commit", {"writes": writes}, token=token)

    def user_path(self, *parts: str) -> str:
        if self.uid is None:
            self.id_token()
        return "/".join(("users", self.uid, *parts))
