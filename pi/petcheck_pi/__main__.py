"""Command line for the Pet Check Raspberry Pi monitor."""

from __future__ import annotations

import argparse
import getpass
import logging
import signal
import sys
import threading
from datetime import datetime
from pathlib import Path

from . import __version__
from .config import EXAMPLE_CONFIG, Config, State, load_config
from .firebase import FirebaseError, FirebaseSession

log = logging.getLogger("petcheck")


def _session(config: Config, state: State) -> FirebaseSession | None:
    if not config.remote_configured:
        return None
    token = state.load_refresh_token()
    if token is None:
        log.warning("Firebase is configured but nobody is signed in: run `petcheck-pi login`. Logging locally only.")
        return None
    return FirebaseSession(config.firebase_api_key, config.firebase_project_id, token)


def cmd_run(args) -> int:
    from .classify import SoundTypeMapper, YamnetClassifier, load_labels
    from .monitor import Monitor, SyncThread, run_microphone, run_wav
    from .store import EventStore

    config = load_config(args.config)
    state = State(config.state_dir)
    for path in (config.model_path, config.labels_path):
        if not Path(path).exists():
            log.error("model file not found: %s (install from a clone of the repository, or set model_path)", path)
            return 2
    mapper = SoundTypeMapper(load_labels(str(config.labels_path)))
    classifier = YamnetClassifier(str(config.model_path))
    store = EventStore(state.database_path)
    session = None if args.no_upload else _session(config, state)
    monitor = Monitor(config, store, state.device_id(), classifier, mapper, session)

    if args.wav:
        run_wav(monitor, args.wav)
        ok = monitor.sync(monitoring=False, force_heartbeat=True)
        if session is not None:
            state.save_refresh_token(session.refresh_token)
        log.info("%d sound(s) logged from %s", monitor.event_count, args.wav)
        return 0 if ok else 1

    stop = threading.Event()
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda *_: stop.set())
    sync = SyncThread(monitor)
    sync.start()
    try:
        run_microphone(monitor, stop, config.input_device)
    finally:
        sync.stop()
        monitor.sync(monitoring=False, force_heartbeat=True)
        if session is not None:
            state.save_refresh_token(session.refresh_token)
        store.close()
    log.info("stopped after %d sound(s)", monitor.event_count)
    return 0


def cmd_login(args) -> int:
    config = load_config(args.config)
    if not config.remote_configured:
        log.error("add a [firebase] section with project_id and api_key to the config file first")
        return 2
    email = args.email or input("Email: ").strip()
    password = getpass.getpass("Password: ")
    try:
        session = FirebaseSession.sign_in(config.firebase_api_key, config.firebase_project_id, email, password)
    except FirebaseError as error:
        log.error("sign-in failed: %s", error)
        return 1
    State(config.state_dir).save_refresh_token(session.refresh_token)
    print(f"Signed in as {email}. This monitor will appear in the app as {config.device_name!r}.")
    return 0


def cmd_logout(args) -> int:
    State(load_config(args.config).state_dir).clear_refresh_token()
    print("Signed out. Sounds are logged on this device only.")
    return 0


def cmd_devices(args) -> int:
    import sounddevice

    for index, device in enumerate(sounddevice.query_devices()):
        if device["max_input_channels"] > 0:
            print(f"{index:3d}  {device['name']}")
    return 0


def cmd_log(args) -> int:
    from .store import EventStore

    config = load_config(args.config)
    store = EventStore(State(config.state_dir).database_path)
    for event in reversed(store.recent(args.count)):
        started = datetime.fromtimestamp(event.started_at_ms / 1000).strftime("%Y-%m-%d %H:%M:%S")
        mark = "" if event.synced else "  (not uploaded)"
        print(f"{started}  {event.type:<5}  {event.duration_ms / 1000:6.1f} s  peak {event.peak_db:4.0f} dB{mark}")
    return 0


def cmd_example_config(args) -> int:
    print(EXAMPLE_CONFIG, end="")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="petcheck-pi", description="Pet Check sound monitor for a Raspberry Pi.")
    parser.add_argument("--version", action="version", version=__version__)
    parser.add_argument("--config", type=Path, help="config file (default: ~/.config/petcheck/config.toml)")
    parser.add_argument("-v", "--verbose", action="store_true")
    commands = parser.add_subparsers(dest="command", required=True)

    run = commands.add_parser("run", help="listen and log sounds")
    run.add_argument("--wav", help="process a WAV recording instead of the microphone, then exit")
    run.add_argument("--no-upload", action="store_true", help="log on this device only")
    run.set_defaults(handler=cmd_run)

    login = commands.add_parser("login", help="sign in for remote viewing")
    login.add_argument("--email")
    login.set_defaults(handler=cmd_login)

    commands.add_parser("logout", help="forget the sign-in").set_defaults(handler=cmd_logout)
    commands.add_parser("devices", help="list microphones").set_defaults(handler=cmd_devices)
    show = commands.add_parser("log", help="show recently logged sounds")
    show.add_argument("-n", "--count", type=int, default=20)
    show.set_defaults(handler=cmd_log)
    commands.add_parser("example-config", help="print a config file to start from").set_defaults(handler=cmd_example_config)

    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, format="%(message)s")
    try:
        return args.handler(args)
    except FileNotFoundError as error:
        log.error("%s", error)
        return 2


if __name__ == "__main__":
    sys.exit(main())
