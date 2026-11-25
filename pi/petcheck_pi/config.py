"""Configuration file and on-disk state for the Pi monitor."""

from __future__ import annotations

import json
import os
import socket
import tomllib
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from .alerts import AlertRule

DEFAULT_CONFIG_PATH = Path.home() / ".config" / "petcheck" / "config.toml"
DEFAULT_STATE_DIR = Path.home() / ".local" / "state" / "petcheck"
# When installed from a clone of the repository, the model the Android app ships is reused.
REPO_ASSETS = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets"

EXAMPLE_CONFIG = """\
# Pet Check monitor settings. Save as ~/.config/petcheck/config.toml

[monitor]
device_name = "Living room Pi"
sensitivity = 50            # 0 = only very loud sounds, 100 = anything above the room's background
log_other_sounds = true     # keep loud sounds that were not recognised as a pet
# input_device = "USB"      # part of a microphone's name from `petcheck-pi devices`; default microphone if unset

[alerts]
enabled = false
noisy_seconds = 60          # alert after this much noise...
window_minutes = 10         # ...within this many minutes
include_other = false       # count sounds that were not recognised as a pet

# Remote viewing. Leave this section out to keep everything on the Pi.
# Both values are on the Firebase console under Project settings > General.
[firebase]
project_id = "your-project-id"
api_key = "your-web-api-key"
"""


@dataclass
class Config:
    device_name: str = field(default_factory=socket.gethostname)
    sensitivity: int = 50
    log_other_sounds: bool = True
    input_device: str | None = None
    model_path: Path = REPO_ASSETS / "yamnet.tflite"
    labels_path: Path = REPO_ASSETS / "yamnet_label_list.txt"
    alerts: AlertRule = field(default_factory=AlertRule)
    firebase_project_id: str | None = None
    firebase_api_key: str | None = None
    state_dir: Path = DEFAULT_STATE_DIR

    @property
    def remote_configured(self) -> bool:
        return bool(self.firebase_project_id and self.firebase_api_key)


def load_config(path: Path | None = None) -> Config:
    """Reads the config file. A missing default file means default settings; a missing named file is an error."""
    chosen = Path(path) if path else DEFAULT_CONFIG_PATH
    if not chosen.exists():
        if path:
            raise FileNotFoundError(f"config file not found: {chosen}")
        return Config()
    with open(chosen, "rb") as handle:
        raw = tomllib.load(handle)

    monitor = raw.get("monitor", {})
    alerts = raw.get("alerts", {})
    firebase = raw.get("firebase", {})
    config = Config()
    config.device_name = str(monitor.get("device_name", config.device_name))[:40]
    config.sensitivity = min(100, max(0, int(monitor.get("sensitivity", 50))))
    config.log_other_sounds = bool(monitor.get("log_other_sounds", True))
    config.input_device = monitor.get("input_device") or None
    if "model_path" in monitor:
        config.model_path = Path(monitor["model_path"]).expanduser()
    if "labels_path" in monitor:
        config.labels_path = Path(monitor["labels_path"]).expanduser()
    if "state_dir" in monitor:
        config.state_dir = Path(monitor["state_dir"]).expanduser()
    config.alerts = AlertRule(
        enabled=bool(alerts.get("enabled", False)),
        noisy_seconds=int(alerts.get("noisy_seconds", 60)),
        window_minutes=int(alerts.get("window_minutes", 10)),
        include_other=bool(alerts.get("include_other", False)),
    )
    project = firebase.get("project_id")
    key = firebase.get("api_key")
    if project and key and not str(project).startswith("your-"):
        config.firebase_project_id = str(project)
        config.firebase_api_key = str(key)
    return config


class State:
    """Small files kept between runs: this device's id and the sign-in token."""

    def __init__(self, directory: Path):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)

    @property
    def database_path(self) -> Path:
        return self.directory / "events.db"

    def device_id(self) -> str:
        path = self.directory / "device_id"
        if path.exists():
            return path.read_text().strip()
        value = str(uuid.uuid4())
        path.write_text(value + "\n")
        return value

    def load_refresh_token(self) -> str | None:
        path = self.directory / "credentials.json"
        if not path.exists():
            return None
        try:
            return json.loads(path.read_text()).get("refresh_token")
        except (ValueError, OSError):
            return None

    def save_refresh_token(self, token: str) -> None:
        """Stores the token readable by this user only. The password itself is never stored."""
        path = self.directory / "credentials.json"
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w") as handle:
            json.dump({"refresh_token": token}, handle)
        os.chmod(path, 0o600)

    def clear_refresh_token(self) -> None:
        (self.directory / "credentials.json").unlink(missing_ok=True)
