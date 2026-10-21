# Download page (site/)

A one-page website where players download PakPak for Android or iPhone. It's hosted free on **GitHub Pages**. The app files live in **GitHub Releases**, and the page always links to the newest release, so you never edit links when you ship an update.

## One-time setup

1. Put the project on GitHub (see [IOS.md](IOS.md), step 1).
2. In `site/config.js`, set `repo` to your `"owner/name"`, for example `"shan/pakpak"`. Commit and push.
3. On GitHub, open **Settings → Pages → Source** and choose **GitHub Actions**.
4. The **Download page** workflow publishes the site. Your link is `https://<owner>.github.io/<repo>/` and appears in the workflow run.
5. Optional: add repository variable `PAKPAK_API_BASE_URL` (your HTTPS server) so released apps connect online.
6. Recommended: add the Android signing secrets listed at the top of `.github/workflows/release.yml`. Without them, the release contains a debug-signed APK.

## Publishing a version

```bash
git tag v1.1.0
git push origin v1.1.0
```

The **Release** workflow builds the Android APK and the iOS app on GitHub's machines. It then publishes `PakPak.apk`, `PakPak-iOS-unsigned.ipa` and `SHA256SUMS.txt` as a GitHub Release. The page picks up the new version, date and file size by itself.

## The iPhone button

iPhones can't install apps from a website. The page shows the best option you've configured in `config.js`:

| You set | The iPhone button |
|---|---|
| `appStoreUrl` | Download on the App Store |
| `testflightUrl` | Join the beta on TestFlight (public link, up to 10,000 testers) |
| nothing | "Download .ipa (advanced)" plus sideloading steps, or "coming soon" if no .ipa exists yet |

TestFlight and the App Store both need the Apple Developer Program ($99/year).

## Preview locally

```bash
cd site
python -m http.server 8000   # then open http://localhost:8000
```

The page detects Android and iPhone visitors and highlights their button. Desktop visitors get a QR code so they can open the page on their phone.
