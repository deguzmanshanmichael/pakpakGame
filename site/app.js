// Fills in download links from config.js and the repository's latest GitHub Release.
(function () {
  const cfg = window.PAKPAK || {};
  const repo = cfg.repo || "";
  const $ = (id) => document.getElementById(id);

  // Stable links that always point at the newest release (asset names are fixed by release.yml).
  const latest = `https://github.com/${repo}/releases/latest`;
  const apkUrl = `${latest}/download/PakPak.apk`;
  const ipaUrl = `${latest}/download/PakPak-iOS-unsigned.ipa`;

  $("btn-apk").href = apkUrl;
  $("releases-link").href = `https://github.com/${repo}/releases`;

  // Highlight the visitor's platform.
  const ua = navigator.userAgent || "";
  const isIOS = /iPhone|iPad|iPod/.test(ua) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  const isAndroid = /Android/.test(ua);
  if (isAndroid) { $("android-badge").hidden = false; $("card-android").classList.add("mine"); }
  if (isIOS) { $("ios-badge").hidden = false; $("card-ios").classList.add("mine"); document.querySelector(".downloads").prepend($("card-ios")); }

  // iPhone: App Store > TestFlight > sideload (iPhones cannot install apps directly from a website).
  const iosBtn = $("btn-ios");
  const help = $("ios-help");
  if (cfg.appStoreUrl) {
    iosBtn.href = cfg.appStoreUrl;
    iosBtn.textContent = "Download on the App Store";
    $("ios-sub").textContent = "iOS 15 or newer · Free on the App Store";
  } else if (cfg.testflightUrl) {
    iosBtn.href = cfg.testflightUrl;
    iosBtn.textContent = "Join the beta on TestFlight";
    help.innerHTML = `<details><summary>How to install</summary><ol>
      <li>Install Apple's free <b>TestFlight</b> app from the App Store.</li>
      <li>Tap <b>Join the beta on TestFlight</b> above, then <b>Accept</b> and <b>Install</b>.</li>
      <li>PakPak appears on your home screen. TestFlight tells you when there's an update.</li></ol></details>`;
  } else {
    iosBtn.href = ipaUrl;
    iosBtn.textContent = "Download .ipa (advanced)";
    iosBtn.classList.add("btn-muted");
    help.innerHTML = `<p class="note">The App Store and TestFlight versions are coming soon. Until then, iPhones can't install
      apps straight from a website. You can sideload this file with your own Apple ID:</p>
      <details><summary>How to sideload</summary><ol>
      <li>On a computer, install a sideloading tool such as Sideloadly or AltStore.</li>
      <li>Connect your iPhone, open the downloaded <b>.ipa</b> in the tool and sign in with your Apple ID.</li>
      <li>On the iPhone, open <b>Settings &rsaquo; General &rsaquo; VPN &amp; Device Management</b> and trust your Apple ID.
        On iOS 16 and newer, also turn on <b>Settings &rsaquo; Privacy &amp; Security &rsaquo; Developer Mode</b>.</li>
      <li>With a free Apple ID the app has to be re-installed every 7 days.</li></ol></details>`;
  }

  if (cfg.contact) { $("contact").hidden = false; $("contact").textContent = `Contact: ${cfg.contact}`; }

  // QR code for desktop visitors.
  if (!isIOS && !isAndroid && window.QRCode) {
    $("qr-section").hidden = false;
    new QRCode($("qr"), { text: location.href, width: 148, height: 148, colorDark: "#1c1612", colorLight: "#fffdf4" });
  }

  // Version, date and file size from the GitHub API (works without a token for public repos).
  if (!repo || repo.startsWith("YOUR-")) { $("version").textContent = "Setup needed: set your repo in config.js"; return; }
  fetch(`https://api.github.com/repos/${repo}/releases/latest`, { headers: { Accept: "application/vnd.github+json" } })
    .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
    .then((rel) => {
      const date = new Date(rel.published_at).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
      $("version").textContent = `Version ${rel.tag_name.replace(/^v/, "")} · ${date}`;
      const apk = (rel.assets || []).find((a) => a.name === "PakPak.apk");
      if (apk) $("apk-size").textContent = `APK · ${(apk.size / 1048576).toFixed(1)} MB`;
      if (!(rel.assets || []).some((a) => a.name === "PakPak-iOS-unsigned.ipa") && !cfg.testflightUrl && !cfg.appStoreUrl) {
        iosBtn.removeAttribute("href");
        iosBtn.textContent = "iPhone version coming soon";
        iosBtn.classList.add("btn-disabled");
      }
    })
    .catch(() => {
      $("version").textContent = "No release published yet";
      $("btn-apk").removeAttribute("href");
      $("btn-apk").textContent = "Coming soon";
      $("btn-apk").classList.add("btn-disabled");
    });
})();
