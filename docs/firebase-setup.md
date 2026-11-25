# Setting up remote viewing

Remote viewing lets a second device show what a monitor has logged. It runs on a Firebase
project that **you** create, so your data stays in your own account. Nothing here is needed
if you only want a standalone monitor.

The free Spark plan covers sign-in and the database. Only push alerts to a closed app need
the pay-as-you-go Blaze plan (step 6), and that step is optional.

## 1. Create the project

1. Open the [Firebase console](https://console.firebase.google.com) and create a project.
   Google Analytics is not needed.
2. Add an **Android app** to it with the package name `io.github.byronfichardt.petcheck`.
3. Download `google-services.json` and put it at `app/google-services.json`.

That file is listed in `.gitignore`. Keep it out of the repository so that everyone who
builds the app uses their own project.

## 2. Turn on sign-in

In **Authentication > Sign-in method**, enable **Email/Password**. The Raspberry Pi monitor
needs this method, and it works on Android with no further setup.

Google sign-in is optional. To offer it as well:

1. Enable the **Google** provider.
2. Add your signing certificate's SHA-1 fingerprint to the Android app in
   **Project settings**. This prints the fingerprint of the debug key:

   ```bash
   ./gradlew signingReport
   ```

3. Download `google-services.json` again and replace the old copy.

The "Continue with Google" button only appears in builds whose `google-services.json`
includes a web client ID, which Firebase adds once the Google provider is enabled.

## 3. Create the database

In **Firestore Database**, create a database in **production mode** and choose a location
near you. The location cannot be changed later.

## 4. Deploy the security rules

The rules make each account's data private to that account. Until they are deployed, a
production-mode database refuses everything.

```bash
cd firebase
```

```bash
npm install
```

```bash
npx firebase login
```

```bash
npx firebase use --add
```

```bash
npm run deploy
```

## 5. Build and sign in

```bash
./gradlew installDebug
```

In the app, open **Settings**, create an account, and sign in with that same account on
every device. Sounds logged on one device now appear on the others.

## 6. Push alerts when the app is closed (optional)

A monitor can alert your other devices when there has been too much noise (**Settings >
Noise alerts**). Out of the box an alert reaches a device while Pet Check is running on it.
To reach a device when the app is closed, deploy the Cloud Function that sends the push.

Cloud Functions require the Blaze plan, which needs a payment card on file. Blaze keeps the
free allowances, and this function runs once per alert, so a household should stay inside
them. Set a budget alert in the Google Cloud console to be sure.

```bash
cd firebase/functions
```

```bash
npm install
```

```bash
cd ..
```

```bash
npm run deploy:functions
```

## How much of the free quota it uses

Each logged sound is one database write, and one read for each viewing device. A monitor
also writes a status update every ten minutes while it is listening, which is about 144
writes a day. Firestore's free daily quota is in the tens of thousands of reads and writes,
so even a very noisy pet stays far below it. Check the
[Firebase pricing page](https://firebase.google.com/pricing) for the current figures.

## Where the data lives

| Path | Contents |
| --- | --- |
| `users/{uid}/devices/{deviceId}` | name, platform, whether it is listening, when it was last seen |
| `users/{uid}/events/{eventId}` | type, start time, duration, peak loudness, confidence, device |
| `users/{uid}/alerts/{alertId}` | which device raised it, how much noise, in what window |

To delete everything, delete the `users` collection in the console, or delete the project.
**Clear history** in the app only clears that device's local copy.

## Testing the rules

```bash
cd firebase && npm test
```

This starts the Firestore emulator and runs `firebase/test/rules.test.js` against it. Java
21 or newer is needed for the emulator.
