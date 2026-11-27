# Builds, releases and in-app updates

Every push to `main` runs [.github/workflows/build.yml](../.github/workflows/build.yml):

1. runs the `:core` simulation tests,
2. builds a **signed release APK** (`AgesOfSiege-0.2.<build>.apk`),
3. publishes it as a GitHub release tagged `build-<build>` (the build number is the git commit count).

The game checks `https://api.github.com/repos/marian1x/towerdefence/releases/latest`:

* when the app starts or comes back to the foreground (at most every 30 min),
* every ~3 h in the background (WorkManager) → shows a notification "Ages of Siege 0.2.x is ready",
* on demand in **Settings → Updates → Check for updates**.

When a newer build exists, an **UPDATE NOW** card appears on the main menu, in Settings, and as a
"⬆ UPDATE" button in the in-game HUD. It downloads the APK and installs it through Android's
PackageInstaller. The game is saved first; the save survives because every build is signed with the same key.

## One-time setup (owner)

1. `gh auth refresh -h github.com -s workflow` – lets the CLI push workflow files.
2. `powershell -ExecutionPolicy Bypass -File tools\setup-signing.ps1` – creates the signing key
   (`%USERPROFILE%\.android\agesofsiege-release.jks`), writes the git-ignored `keystore.properties`
   and uploads the key to the repository secrets. **Back up both files** – without them no update can
   ever be installed over an existing copy again.

## Installing on a phone (first time only)

1. Open the latest release on GitHub (Releases → newest `build-…`) on the phone and download the APK.
2. Allow your browser / Files app to "install unknown apps" when Android asks, then install.
3. From then on the game updates itself; the first update asks once to allow *Ages of Siege* to install apps.

Friends do the same – send them the link to the Releases page.

## Notes

* Builds signed with a different key (e.g. an old debug build from Android Studio before the shared key
  existed) can't be updated in place: uninstall once, then install the release APK.
* Release builds are not minified for now (R8 package-scoped optimization crashed at startup).
