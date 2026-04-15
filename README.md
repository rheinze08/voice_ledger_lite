# Voice Ledger Lite

Voice Ledger Lite is a small local-first Android client for quick document capture plus opt-in Gemma rollups through Ollama.

## What it does

- saves notes locally with a title, body, created timestamp, and updated timestamp
- keeps everything on-device in a Room database
- lets you point the app at an Ollama server and default to `gemma4:e2b`
- runs a lightweight semantic rollup over recent notes to surface highlights and themes
- caches the latest insight locally so the app stays useful offline

## Lightweight design choices

- no login flow
- no backend dependency
- no background jobs hitting the model unless you tap `Refresh with Gemma`
- small schema: `notes` and `insights`
- prompt windows are capped by note count and date range to minimize token usage

## Project layout

- `app/src/main/java/com/voiceledger/lite/data`: Room entities, DAO, repositories, and local settings
- `app/src/main/java/com/voiceledger/lite/ollama`: thin Ollama client plus structured insight models
- `app/src/main/java/com/voiceledger/lite/ui`: Compose app shell and view model

## Running it

This shell does not have Android Studio, Gradle, Java, or the Android SDK on PATH, so the project was scaffolded without a generated Gradle wrapper. Open the repository root in Android Studio and let it finish setup, or run `gradle wrapper` once from a machine with Gradle installed.

Important settings:

- Android emulator default Ollama URL: `http://10.0.2.2:11434`
- Real device Ollama URL: use your machine's LAN IP, for example `http://192.168.1.42:11434`
- Default model: `gemma4:e2b`

## GitHub distribution

The repo includes:

- `Build Android APK` at `.github/workflows/android-apk.yml`
- `Deploy Download Page` at `.github/workflows/pages.yml`
- a static Pages site in `docs/index.html`

The intended path is:

1. Run the `Build Android APK` workflow from GitHub.
2. Let it create or update a release with `voice-ledger-lite-debug.apk`.
3. Open the GitHub Pages download page and install the APK from there.

This is currently a debug APK for fast testing. A signed release build would need a keystore plus GitHub Actions secrets.

## Intended first test

1. Create a few short notes in the `Compose` tab.
2. Open `Settings` and confirm the Ollama host is reachable.
3. Open `Insights` and tap `Refresh with Gemma`.
4. Review the generated overview, highlights, and theme buckets.
