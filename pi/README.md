# Pet Check on a Raspberry Pi

A headless monitor: a Pi with a microphone that logs sounds and, if you want, uploads the
records so the Android app can show them. It uses the same detection and the same
sound-recognition model as the app.

## What you need

- A Raspberry Pi 3, 4, 5 or Zero 2 W running **64-bit** Raspberry Pi OS (Bookworm or newer).
  The sound model has no 32-bit build, so the original Pi Zero and Pi 1 will not work.
- A USB microphone, or a USB sound card with a microphone. The Pi has no microphone input
  of its own.

## Install

```bash
sudo apt install -y git python3-venv libportaudio2
```

```bash
git clone https://github.com/byronfichardt/pet-check.git
```

```bash
cd pet-check/pi
```

```bash
python3 -m venv .venv
```

```bash
.venv/bin/pip install -e .
```

Install from the clone as shown: the monitor reads the sound model from the repository's
`app/src/main/assets` folder.

## Try it

List the microphones the Pi can see:

```bash
.venv/bin/petcheck-pi devices
```

Start listening. Each sound is printed as it is logged; stop with Ctrl+C.

```bash
.venv/bin/petcheck-pi run
```

To test without making noise, run a recording through it. Any 16-bit WAV file works:

```bash
.venv/bin/petcheck-pi run --no-upload --wav bark.wav
```

Show what has been logged:

```bash
.venv/bin/petcheck-pi log
```

## Settings

Create a config file to start from:

```bash
mkdir -p ~/.config/petcheck
```

```bash
.venv/bin/petcheck-pi example-config > ~/.config/petcheck/config.toml
```

Edit it to set the device name, the sensitivity, which microphone to use and the alert
rule. Without a config file the defaults are used and everything stays on the Pi.

## Remote viewing

1. Set up a Firebase project as described in
   [docs/firebase-setup.md](../docs/firebase-setup.md), with **Email/Password** sign-in
   enabled, and create your account in the Android app.
2. Put the project's ID and Web API key in the `[firebase]` section of the config file.
   Both are in the Firebase console under **Project settings > General**.
3. Sign in on the Pi with that same account:

   ```bash
   .venv/bin/petcheck-pi login
   ```

The Pi keeps a sign-in token in `~/.local/state/petcheck/credentials.json`, readable only
by your user. Your password is not stored. The monitor signs in as you, so it can only
touch your own data, and no admin key is kept on the device.

If the network drops, sounds keep being logged locally and are uploaded when it returns.

## Run it at boot

```bash
mkdir -p ~/.config/systemd/user
```

```bash
cp petcheck.service ~/.config/systemd/user/
```

```bash
systemctl --user enable --now petcheck
```

```bash
sudo loginctl enable-linger $USER
```

The unit file assumes the repository is at `~/pet-check`. Edit `ExecStart` if it is not.
Follow the log with:

```bash
journalctl --user -u petcheck -f
```

## Tests

```bash
python3 -m unittest discover -s tests
```

`tests/e2e_emulator.py` runs the monitor against the Firebase emulators. Start it from the
`firebase` directory with `npm run test:pi`.
