# Building and installing the iOS version

Apple only allows iOS apps to be compiled on a Mac with Xcode. You don't need to own one: the included GitHub Actions workflow builds the app on GitHub's Mac machines for free (public repos) or within the free monthly minutes (private repos).

## 1. Put the project on GitHub (one time)

1. Create an account at github.com and a new **empty** repository, for example `pakpak`.
2. In a terminal in the `flappyBird` folder:
   ```bash
   git init
   git add .
   git commit -m "PakPak"
   git branch -M main
   git remote add origin https://github.com/YOUR-NAME/pakpak.git
   git push -u origin main
   ```
   `.gitignore` already keeps out secrets (`.env`, `keystore.properties`, `local.properties`) and build folders.

## 2. Let the cloud Mac build it

Every push runs **Actions → iOS**. You can also start it by hand with **Run workflow**. It takes about 20–40 minutes the first time, because Kotlin/Native gets downloaded, and less after that.

When it's green, open the run and download the **Artifacts**:

| Artifact | What it's for |
|---|---|
| `PakPak-simulator` | `PakPak.app` for the iOS Simulator. If you ever use a Mac, unzip it and drag it onto a running simulator. |
| `PakPak-unsigned` | `PakPak-unsigned.ipa` for a real iPhone. It must be signed before installing (step 3). |

To make the iPhone build talk to your online server, add a repository **variable** under Settings → Secrets and variables → Actions → Variables: `PAKPAK_API_BASE_URL = https://your-server`. It must be HTTPS.

## 3. Install on your iPhone

iPhones only run apps signed with an Apple ID. Pick one of these:

**A. Free, for testing (re-sign every 7 days).** Use a sideloading tool such as Sideloadly or AltStore on your Windows PC. Plug in the iPhone, drop `PakPak-unsigned.ipa` in, and sign in with your Apple ID. On the iPhone, go to Settings → General → VPN & Device Management and trust your Apple ID, then on iOS 16 and later turn on Settings → Privacy & Security → Developer Mode. Free Apple IDs are limited to 7-day installs and 3 apps.

**B. Proper distribution: TestFlight and the App Store.** This needs the Apple Developer Program ($99/year). With it you can upload builds to TestFlight, invite up to 10,000 testers by link, and later publish to the App Store. Uploading needs a signed archive, which needs your signing certificate and an App Store Connect API key. Once you have the account, those can be added to the workflow as GitHub secrets, or the upload can be done once from any Mac with Xcode (Product → Archive → Distribute).

## Building on a Mac yourself (optional)

```bash
brew install xcodegen
cd mobile/iosApp
xcodegen generate
open PakPak.xcodeproj
```

Choose your iPhone or a simulator and press Run. Xcode runs Gradle automatically to compile the shared Kotlin code. For a real device, put your Team ID in `mobile/iosApp/Config/Debug.xcconfig` (`PAKPAK_TEAM_ID`), or select your team under Signing & Capabilities.

The development server URL for iOS is set in `Config/Debug.xcconfig`. `localhost` works from the simulator. For a real iPhone, use your computer's LAN IP; plain HTTP is allowed only for local-network addresses.

## What's shared and what's platform-specific

Everything you see and play is shared Kotlin code in `mobile/shared/src/commonMain`: the game, all screens, saves, sync, the API client and the art. It's drawn with Compose Multiplatform.

Each platform implements only a small set of hooks, in `Platform.android.kt` and `Platform.ios.kt`:

| Hook | Android | iOS |
|---|---|---|
| Files | `filesDir`, temp file + rename | Application Support, atomic NSString write |
| HTTP | HttpURLConnection | NSURLSession |
| Network status | ConnectivityManager | NWPathMonitor |
| Sound | SoundPool + MediaPlayer | AVAudioPlayer (ambient, respects the silent switch) |
| Bundled data | APK assets | app bundle `assets/` folder |

The Android side is compiled and tested on every change. The iOS hooks (about 200 lines) can only be compiled on macOS, so the iOS workflow is their compile check. If it fails, the log shows the exact Kotlin or Swift error to fix.
