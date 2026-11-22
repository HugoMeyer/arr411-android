<p align="center">
  <img src="docs/icon.png" width="96" alt="Arr411 logo">
</p>

<h1 align="center">Arr411</h1>

<p align="center">
  An all-in-one Android app for <b>C411</b>: browse the tracker, send torrents to <b>qBittorrent</b> with the category you choose,
  and manage <b>Radarr</b>, <b>Sonarr</b> and <b>Lidarr</b>, all in one place.
</p>

<p align="center">
  <img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white">
  <img alt="qBittorrent" src="https://img.shields.io/badge/qBittorrent-WebUI%20API%20v2-2F67BA?logo=qbittorrent&logoColor=white">
  <img alt="Radarr" src="https://img.shields.io/badge/Radarr-v3%2B-FFC230">
  <img alt="Sonarr" src="https://img.shields.io/badge/Sonarr-v3%2B-35C5F4">
  <img alt="Lidarr" src="https://img.shields.io/badge/Lidarr-v1%2B-00A65B">
  <img alt="MIT License" src="https://img.shields.io/badge/License-MIT-green">
</p>

<p align="center">
  <b>C411</b> · <b>Radarr</b> · <b>Sonarr</b> · <b>Lidarr</b> · <b>qBittorrent</b>: one tab each, one bottom bar.
</p>

> The app's interface is in French, like C411.

---

## ✨ Features

### 🌐 C411
- The full **C411 website**, with the [C411 → qBittorrent](https://github.com/HugoMeyer/arr411-chrome) extension built in.
- **qBittorrent button** next to C411's **Télécharger** button on torrent pages, and at the end of each row in result lists.
- **Send dialog**: qBittorrent category, add paused, and **add to Radarr, Sonarr or Lidarr** in the same step, with the root folder, the series type (Standard, Anime, Daily), or the album / artist for music.
- Detects the **IMDb, TMDB and TheTVDB** IDs on the torrent page, and the album from C411's music card (MusicBrainz, Deezer and Discogs links).
- Handles C411's **"Téléchargement normal"** prompt automatically when it appears.
- **Badges** for torrents already in qBittorrent (`✔ qBit` / `⇩ 42 %`) in result lists, plus a status badge on torrent pages.
- **Score badges** (0 to 100) on every version of a torrent and on result rows, weighing language, quality, seeders and size. They single out the best choice (★), the best quality for the size (⚖︎), the best quality (◆) and the most seeders (⚡︎). Tap a badge for the detail.
- **Quick menu** (tap the C411 tab again): reload, C411 home, desktop mode, copy or share the link, settings.

### ⬇️ qBittorrent
- Torrent list with live **speeds** and **free space**, filters (downloading, seeding, completed, paused, active, stalled, errors), **categories and tags**, search and many sort orders.
- **Hidden tags**: torrents tagged `zen-farm` (ratio farming) are hidden by default, so the list stays fast with tens of thousands of them. A chip above the list shows them again, and the funnel menu lets you choose other tags to hide. *Resume all* and *Pause all* still act on every torrent, hidden or not.
- **Ratio and Zen dashboard** (qBittorrent menu → *Tableau de bord ratio*): ratio and totals for the `zen-farm` tag versus the other torrents, how many are really seeding and why the rest are not (queued, paused, error), **hit-and-run alerts** (also shown as a banner above the list), and **clean-up rules** you run by hand: add or remove a tag, pause, resume or delete. A rule always shows a preview and asks for confirmation; incomplete or at-risk torrents are never deleted, and files stay on disk unless you tick the box.
- **Multi-select** with bulk actions.
- **Torrent details** with *Infos*, *Fichiers* (priorities, rename), *Trackers* and *Pairs* (peers) tabs.
- **Actions**: pause / resume, force resume, recheck, reannounce, category, tags, move, rename, speed and share limits, sequential download, queue priority, copy magnet links, delete with or without files.
- **Add torrents** from magnet links, URLs or `.torrent` files: category, tags, save path, add paused, sequential download, first and last pieces first, skip hash check, content layout, rename, speed limits.
- **Global speed limits** and **alternative speed** mode.
- **Categories and tags manager**, **qBittorrent preferences**, **log**, and a shortcut to the WebUI.
- **Importer dans Radarr / Sonarr / Lidarr…**: manual import of a finished torrent, straight from its menu.
- **Voir la fiche sur C411** and **Rechercher sur C411** from a torrent's menu.

### 🎬 Radarr · 📺 Sonarr · 🎵 Lidarr
- **Library** in grid or list view, with filters (downloaded, missing, not released yet, complete, continuing, anime…) and sort options.
- **Detail pages**: movie info, files and history; seasons and episodes; artist discography with albums and tracks (multi-disc aware).
- **Add** by searching a title or pasting an IMDb, TMDB, TheTVDB or MusicBrainz link or ID. Choose the root folder, quality profile, tags, series type, and for Lidarr the metadata profile and a single album or just the artist.
- **Edit**, **rename files**, **retag** audio files (Lidarr), **delete** (optionally with files and an import exclusion).
- **Activity**: queue and history, plus **manual import** with per-file matching (movie, episode, or album and track). *Copier* uses a hardlink when possible, so the torrent keeps seeding.
- **Calendar** of upcoming releases.
- **System**: status, health, disk space, scheduled tasks, logs, blocklist, tags, backup, root folders (with import of existing folders), quality profiles editor, and Lidarr metadata profiles.
- **Chercher sur C411** on every movie, series, episode, artist and album: opens the C411 tab with the search already filled in.
- **Tout passer en non surveillé**: unmonitors the whole library in one tap.
- 🛡️ **Never monitored, never searched.** Everything Arr411 adds is **unmonitored**, and the app **never triggers a search or an RSS sync**. Radarr, Sonarr and Lidarr never download anything on their own. C411 stays your only source.

### 🔗 Links and sharing
- **c411.org links** open in the C411 tab, and **magnet links** and **`.torrent` files** open qBittorrent's add sheet.
- **Share** to Arr411 from any app:

| Shared content | Goes to |
|---|---|
| Magnet link, `.torrent` URL or file | qBittorrent |
| c411.org link | C411 tab |
| TMDB movie link | Radarr |
| TMDB TV or TheTVDB series link | Sonarr |
| MusicBrainz artist, release-group or release link | Lidarr |
| IMDb link or `tt…` ID | Radarr or Sonarr (you choose) |
| Any other text | C411 search |

### 🔔 Notifications and design
- **Notification** when a torrent sent from C411 finishes downloading (optionally for every torrent).
- **Badge** with the number of active downloads on the qBittorrent tab.
- **Dark, green interface** inspired by C411. The bottom bar hides while you type.
- **Long-press** the Radarr, Sonarr, Lidarr or qBittorrent tab to jump to its settings.
- **Diagnostic** (Settings → Diagnostic): a log of crashes, memory problems and the reasons why Android stopped the app, ready to copy or share. See [Diagnostics](#-diagnostics).
- **Backup** (Settings → Sauvegarde des réglages): export your settings to a JSON file (**Enregistrer** saves it in your Downloads folder, **Copier** copies the text) and import them on another device or after a reinstall. The default export leaves out passwords and API keys; the full export includes them, encrypted with a passphrase (AES-256-GCM). An import is checked and previewed before anything changes.
- **Updates** (Settings → Mises à jour): Arr411 looks for a new release on GitHub about every 6 hours, downloads it, checks its size and SHA-256, then installs it by itself when Android allows it, or tells you when it needs a tap. You can also choose to be notified only, or switch the check off. See [Updates](#-updates).
- **Quick Settings tile** « Vitesse alternative »: shows whether qBittorrent's alternative speed limits are on, and a tap switches them. The subtitle says *Activée* or *Désactivée*, or what is wrong: *Hors ligne*, *Identifiants refusés*, or *À configurer* (a tap then opens the settings). It uses the address and login of the qBittorrent settings. A long press opens the qBittorrent tab.
- **App shortcuts**: long-press the Arr411 icon to jump straight to the qBittorrent, C411, Radarr, Sonarr or Lidarr tab.

---

## 📦 Installation

Arr411 is not on the Play Store. Install the APK yourself:

1. Download `Arr411.apk` from the [**Releases**](https://github.com/HugoMeyer/arr411-android/releases) page of this repository, or build it yourself (see *Building from source* below).
2. Open it on your phone. When Android asks, allow your browser or file manager to install unknown apps.
3. Launch **Arr411**, sign in to C411 in the **C411** tab, then fill in your servers in the settings.

> Requires **Android 10** or later. To update, let Arr411 update itself (see [Updates](#-updates)) or install the new APK over the old one: your settings are kept.

---

## ⚙️ Configuration

Open **Réglages** from any tab's menu, or long-press a tab in the bottom bar.

| Card | Settings |
|---|---|
| **qBittorrent** | WebUI URL, username (leave empty if authentication is disabled), password → **Enregistrer et tester**; default category |
| **Radarr** *(optional)* | URL, API key → **Enregistrer et tester**; default quality profile, default root folder, qBittorrent category |
| **Sonarr** *(optional)* | Same as Radarr |
| **Lidarr** *(optional)* | Same, plus the default metadata profile; the qBittorrent category is `lidarr` by default |
| **C411** | List badges, desktop mode, startup tab (C411, Radarr, Sonarr, Lidarr, qBittorrent or the last one used) |
| **Notifications** | Download completed; for all torrents, not only those sent from C411 |
| **Mises à jour** | Mode (automatic, notify only, off), check now, install the latest version. See [Updates](#-updates) |

- API keys are under **Settings → General → Security** in Radarr, Sonarr and Lidarr.
- URLs can be LAN addresses (`http://192.168.1.10:8080`) or HTTPS domains. Your phone must be able to reach them: same Wi-Fi, a VPN, or a public domain.
- The root folder is asked every time you add something, so you can keep anime and series apart. The default one is just pre-selected.
- Lidarr's **metadata profile** decides which albums are listed for a newly added artist. The built-in `None` profile keeps only the albums you add.

---

## 🚀 Usage

### From C411
1. Open a torrent page, or a results list, in the **C411** tab.
2. Tap **qBittorrent**.
3. Choose:
   - **Add to:** Radarr, Sonarr, Lidarr, or nothing *(torrent page only)*
   - **Root folder**, plus the **series type** for Sonarr, or **album / artist only** (with editable names) for Lidarr
   - **qBittorrent category**
   - **Add paused** *(optional)*
4. Tap **Envoyer**. A toast confirms that the torrent was sent and that the title was added.

### From Radarr, Sonarr or Lidarr
1. Tap **+** and search a title, or paste an IMDb, TMDB, TheTVDB or MusicBrainz link.
2. Pick the root folder and profiles, then tap **Ajouter**. The item is added unmonitored.
3. Tap **Chercher sur C411** to find a release, then send it from the C411 tab.

### Once the download is done
- If your \*arr app watches the torrent's qBittorrent category, it can import the files on its own.
- Otherwise, open the torrent's menu in the **qBittorrent** tab and choose **Importer dans Radarr / Sonarr / Lidarr…**.

> Tip: share a TMDB, IMDb or MusicBrainz link from your browser to Arr411 to add it directly.
>
> Tip: to switch qBittorrent's alternative speed limits from anywhere, pull the Quick Settings panel down, tap its edit (pencil) button and drag **Vitesse alternative** into it.

---

## 🔧 How it works

```
C411 tab ─────── c411.org in a WebView + the extension's content.js
   │  window.QbtPage (a short whitelist of messages)
   ▼
Engine ───────── hidden WebView running the extension's background.js
   │
   ├──▶ qBittorrent WebUI API (/api/v2/torrents/add)
   └──▶ Radarr / Sonarr / Lidarr API (add, unmonitored)

Other tabs ───── web app served from the APK at https://arr411.local/app/
   │  window.Native
   ▼
Native HTTP ──── the same APIs, plus a cover proxy with a disk cache
```

- **Same code as the extension.** `background.js`, `content.js` and `page-hook.js` are copied from the Chrome extension at build time. Small shims (`shim-engine.js`, `shim-page.js`) provide the `chrome.*` APIs they use.
- **No C411 credentials needed.** The app reuses C411's own download button with your logged-in session, then fetches the `.torrent` natively with the WebView's cookies.
- **Native HTTP.** Requests to your servers are made in Java, so there's no CORS, `http://` LAN addresses work, and the `Origin` and `Referer` headers that qBittorrent's CSRF protection expects are set.
- **Covers** from Radarr, Sonarr and Lidarr go through a local proxy that adds the API key, so the key never appears in image URLs. They're cached for a week (about 150 MB max).
- **Two WebViews behind a native bottom bar**: the C411 page keeps its state while you switch tabs. If Android kills a WebView under memory pressure, the app rebuilds itself instead of crashing, and waits a little longer each time if it keeps dying.
- **Bounded memory.** A response from your servers is read in chunks and refused beyond a size limit (16 MB for the background checks, 64 MB otherwise), instead of being copied several times in memory. The background check only asks qBittorrent for the torrents that are downloading, so a library of tens of thousands of torrents no longer exhausts the phone's memory.

---

## 🔐 Permissions and privacy

| Permission | Why |
|---|---|
| `INTERNET` | Reach C411 and your qBittorrent, Radarr, Sonarr and Lidarr servers |
| `POST_NOTIFICATIONS` | Download-completed notifications (asked at first launch on Android 13+) |
| `REQUEST_INSTALL_PACKAGES` | Install the update of Arr411 itself, once you have allowed it (*Install unknown apps*) |
| `UPDATE_PACKAGES_WITHOUT_USER_ACTION` | Let that update install without one more confirmation when Android allows it (Android 12 and later) |
| `RECEIVE_BOOT_COMPLETED` | Keep the periodic check for updates after the phone restarts |

- Settings, passwords and API keys are stored in the app's **private storage**, excluded from Android backups, and only sent to your own servers.
- The C411 page **never sees them**: it can only send a short whitelist of messages to the app (categories, status, send). Every message is size-capped and rate limited (30 per 10 seconds, 5 sends per 10 seconds, 4 downloads at a time).
- **The C411 page is treated as untrusted.** Sending a torrent or downloading a file is only accepted while the C411 tab is on screen and you touched it within the last 30 seconds, so a script can't trigger them on its own. What the page only reads (scores, status) is served while the tab is on screen and for a minute after you left it.
- **Only the script the app injects can talk to the app.** A random key is made when the C411 tab is created and goes into that script only; every call without it (an ad, another frame of the page) is ignored and doesn't use up the limits of the page.
- **No open proxy.** The native `.torrent` download only follows `https://` links on `c411.org` and its subdomains, at every redirect, and refuses IP addresses and private networks: a page can't reach the servers of your LAN. Links from other apps and web pages are only opened as `https:`, `http:` or `magnet:`, and an external app launched by a page needs a tap. The C411 tab itself is `https` only.
- **Credentials stay with their server.** The qBittorrent session cookie is kept per server, and a redirect to another host is refused instead of followed, so an API key is never forwarded elsewhere.
- Cleartext `http://` is allowed so LAN addresses work. Prefer HTTPS for anything exposed to the internet.
- No analytics, no tracking. Besides C411 and your servers, the app only contacts the public **MusicBrainz** API (to match C411 music releases for Lidarr), **GitHub** (to look for a new release of Arr411 and download it, see [Updates](#-updates)) and, as a fallback, the image servers your \*arr apps use for posters.
- The diagnostic log stays in the app's private storage. Passwords, API keys, tokens and C411 passkeys are masked in it, but the addresses of your servers are not. Nothing leaves the phone unless you share it yourself.

---

## 🩺 Diagnostics

If Arr411 closes by itself, open **Settings → Diagnostic**, tap **Copier le rapport** (or **Partager**) and send the text to whoever is helping you.

The report holds:
- the versions of the app, Android and WebView, and the device;
- the memory the app uses and what is left, sampled every 15 minutes while it runs;
- why Android ended the previous runs of Arr411 (low memory, crash, killed from the recent apps, …), on Android 11 and later;
- the last crash, with its stack trace, written even if the process dies right after;
- the last 200 log lines: slow or failed requests to your servers, web pages that stopped, memory warnings;
- your settings with the secrets left out: only whether a login or an API key is set.

The logs are `files/logs/arr411.log` and `crash.log` in the app's private storage, about 1.5 MB at most (they rotate). **Effacer** deletes them.

When the previous run ended badly, the app also says so with a message at the next launch.

---

## 🔄 Updates

Arr411 can update itself from the [Releases](https://github.com/HugoMeyer/arr411-android/releases) page of this repository. **Settings → Mises à jour** shows the installed and the latest version, the release notes, a **Vérifier maintenant** button and the mode:

| Mode | What the app does |
|---|---|
| **Automatique** *(default)* | Looks for a new release about every 6 hours and shortly after it starts, downloads and checks it, then installs it when you are not using the app and Android would not ask you to confirm. Otherwise it tells you with a notification. |
| **Me prévenir seulement** | Looks for a new release and tells you. Nothing is downloaded until you tap **Installer la mise à jour**. |
| **Désactivée** | No automatic check. **Vérifier maintenant** still works (at most once a minute). |

- **What is checked before anything is installed.** The size and the SHA-256 of the APK must match what GitHub published for the release (the `digest` of the API, and `SHA256SUMS.txt` when the release has one), and the file must be Arr411, with a higher version than the installed one. The file is hashed again while it is handed to Android, and the installation is dropped if it differs. A release that publishes no checksum is never installed from the app: take it from the Releases page. Android itself still refuses an APK signed with another key.
- **Where it connects.** Only to GitHub (`api.github.com`, `github.com` and its download servers), over HTTPS, one redirect at a time, with size and time limits. Nothing you typed or stored goes with the requests, only the name and the version of the app.
- **Confirmation.** The first time, Android asks you to allow Arr411 to install apps (*Install unknown apps*). After that, Android 12 to 16 can install an update without another prompt, provided Arr411 installed the version you are running: the first update after you installed the APK by hand may still ask. Android 10 and 11 and Android 17 ask you to confirm every time. When a confirmation is needed, Arr411 opens it right away if you are in the app, or posts a notification to tap if you are not.
- **When something fails** (no network, GitHub unreachable, a file that does not match), nothing is installed and the app keeps working. The reason is shown in Settings and written to the diagnostic log.

Android closes the app while it installs the update. A notification (*Arr411 a été mis à jour en …*) tells you when the new version is in place.

---

## 🛠️ Building from source

No Gradle or Android Studio: just a JDK, the Android SDK command-line tools, and a Windows PowerShell script.

**Requirements**
- JDK 17 (for example Eclipse Temurin)
- Android SDK `build-tools;34.0.0` and `platforms;android-34`
- The [arr411-chrome](https://github.com/HugoMeyer/arr411-chrome) repository, cloned next to this one: `background.js`, `content.js` and `page-hook.js` are packed into the app

**Folder layout** expected by `build.ps1`:

```
Github Repos\                   any parent folder works
├─ android-toolchain\           yours, not in a repository
│  ├─ jdk\                      JDK 17
│  └─ sdk\
│     ├─ build-tools\34.0.0\
│     └─ platforms\android-34\
├─ arr411-chrome\               the Chrome extension
└─ arr411-android\              this repository
```

The SDK packages can be installed with the command-line tools, from this repository's folder:

```powershell
sdkmanager --sdk_root=..\android-toolchain\sdk "build-tools;34.0.0" "platforms;android-34"
```

Prefer other locations? Set the `ARR411_TOOLCHAIN` environment variable to the toolchain folder, and `ARR411_EXTENSION` to the extension folder.

**Build**

```powershell
cd arr411-android
$env:ARR411_KEYSTORE_PASS = '<your keystore password>'
powershell -ExecutionPolicy Bypass -File build.ps1
```

The script compiles the resources (aapt2), the Java code (javac, then d8), packs the extension and the web app as assets, then aligns and signs the APK. The result is `Arr411.apk` in the repository folder, also copied to your **Downloads** folder (add `-NoCopy` to skip that copy).

The keystore password is only read from the `ARR411_KEYSTORE_PASS` environment variable, so it never appears on a command line or in the repository. The script stops with a message when it is missing.

> 🔑 **Signing.** The first build creates `signing.keystore`, protected by the password in `ARR411_KEYSTORE_PASS`. Back the file up and **never commit it** (it's in `.gitignore`): Android only accepts an update signed with the same key. If you publish your own builds, choose your own keystore password and keep it out of the repository.

**Tests**

```powershell
powershell -ExecutionPolicy Bypass -File test.ps1
```

This compiles the classes that don't use `android.*` (URL and redirect policy, page-message gate, log helpers, redaction, the decisions and checks of the self-update, the decisions of the speed tile…) with the toolchain's JDK and runs `test\PureTests.java`. When Node.js is installed it also runs `test\shim`, the checks of the C411 page shim. It exits with a non-zero code if a check fails. No device or emulator is needed.

---

## 🩹 Troubleshooting

| Problem | Fix |
|---|---|
| `Failed to fetch` or another connection error | The phone can't reach the server. Check the URL (`http://` or `https://`, port included) and your network or VPN, then tap **Enregistrer et tester**. |
| `Connexion qBittorrent refusée` | Wrong username or password, or qBittorrent banned the IP after failed logins. A refused login isn't retried with the same credentials for 10 minutes. |
| `Fichier .torrent expiré` | The download link from C411 expired. Reload the page and tap **qBittorrent** again. |
| Categories don't load | Configure qBittorrent in the settings, then tap **Enregistrer et tester**. |
| Radarr, Sonarr or Lidarr is greyed out in the C411 dialog | The service isn't configured in the settings. |
| `Cette adresse répond « … » et pas …` | The URL points to another app (for example Sonarr's URL in the Radarr card). |
| Lidarr lists too many albums for a new artist | Use a narrower metadata profile. `None` keeps only the albums you add. |
| No completion notification | Allow notifications for Arr411 in Android's settings. Checks run while the app is open or in the background, and Android may stop them after a while. |
| The app closes by itself | Open **Settings → Diagnostic** and copy the report: it says why Android stopped the app and shows the last crash. See [Diagnostics](#-diagnostics). |
| "App not installed" when updating | The new APK was signed with another key. Uninstall the old version first (its settings are lost). |
| The update does not install | **Settings → Mises à jour** says why. Allow Arr411 to install apps when Android asks (*Install unknown apps*), then tap **Installer la mise à jour** again. The APK is also on the [Releases](https://github.com/HugoMeyer/arr411-android/releases) page. |

---

## 📁 Project structure

Paths are relative to the repository root.

```
AndroidManifest.xml     Package, permissions, intent filters (C411 links, magnets, .torrent files, sharing), the update's job service and receivers, the Quick Settings tile and the shortcuts' hop
build.ps1               Gradle-free build: aapt2 → javac → d8 → zipalign → apksigner (-NoCopy skips the copy to Downloads)
test.ps1                Compiles the android-free classes, runs test/PureTests.java and test/shim (needs node)
test/PureTests.java     Checks for the classes below that don't use android.*
test/shim/              Checks for assets-extra/shim-page.js (the chrome.* shim of the C411 page)
test/fixtures/          A real GitHub release answer (v2.3.1) that the update checks read
src/dev/c411/qbittorrent/
  MainActivity.java     Two WebViews (C411 and the web app), bottom bar, intents, back navigation
  NavBar.java           Bottom bar with the active-downloads badge
  Engine.java           Hidden WebView running background.js: native HTTP, storage, notifications
  PageBridge.java       window.QbtPage for the C411 tab (key-protected, whitelisted messages, native .torrent download)
  SpaBridge.java        window.Native for the web app
  SpaClient.java        Serves the web app at https://arr411.local/app/, proxies and caches covers
  Shared.java           Turns shared links and .torrent files into app actions
  App.java              Application class: starts the diagnostic log, forwards the system's memory warnings
  Diag.java             Diagnostic log and report (crashes, memory, exit reasons), shown in Settings → Diagnostic
  LogFile.java          Size-capped rotating log file
  Redact.java           Masks passwords, keys, tokens and passkeys before anything is logged
  DiagText.java         Text helpers of the report (durations, exit reasons, stack traces)
  BodyReader.java       Reads a server response in chunks, with a size limit
  RestartDelay.java     Back-off before rebuilding a web view that keeps dying
  UrlPolicy.java        Which URLs may be loaded in the C411 tab, fetched for the page or opened outside
  RedirectPolicy.java   Which redirects may be followed (same server only)
  CookieJars.java       One cookie jar per server, so the qBittorrent session cookie never goes to another host
  PageGate.java         Gesture window, on-screen grace, flows and rate limits for the messages sent by the C411 page
  BridgeKey.java        Per-session secret that tells the script injected in the C411 tab from the other frames
  ConsoleBudget.java    Budget for the console lines of the web pages in the log
  LruThrottle.java      Bounded per-key throttle for repeated log lines
  FileNames.java        Sanitises the names of the .torrent files saved from the C411 page
  QbSpeed.java          The tile's decisions: how it reads and flips the alternative speed limits, logs in, and what each answer shows
  SpeedTile.java        The Quick Settings tile « Vitesse alternative »
  ShortcutActivity.java Invisible hop of the app shortcuts and of a long press on the tile: opens a tab without restarting the app
  Updater.java          Self-update: looks for a release on GitHub, downloads, verifies and installs it, and tells the web app (window.Native.update*)
  UpdateJobService.java Periodic check for updates (JobScheduler, about every 6 hours, kept after a reboot)
  InstallStatusReceiver.java Answer of Android's installer: done, needs a confirmation, failed
  PackageReplacedReceiver.java After an update: tells you it is installed and sets the check up again
  UpdateInstaller.java  Examines the downloaded APK and streams it into a PackageInstaller session, hashing it again on the way
  UpdateNet.java        HTTP of the update: https only, GitHub's hosts only (at every redirect), size and time limits
  UpdateNotifier.java   Notifications of the update: available, ready, confirm, allow, installed
  UpdateStore.java      What the updater remembers: last answer and ETag, verified file, notifications already shown
  UpdateFlow.java       The decisions: mode, what follows a check or a download, which job to keep, what to tell the user
  UpdateState.java      The state of the update that the web app reads (JSON shared with the desktop app)
  UpdateRelease.java    Reads the GitHub release: tag, notes, APK asset, size and SHA-256 digest
  UpdateIntegrity.java  Size and SHA-256 checks: the digest of the API and SHA256SUMS.txt
  HashedCopy.java       Copies a stream while hashing it, never beyond a size limit
  UpdateHosts.java      The addresses and redirects the update may follow
  UpdateInstallStatus.java What the status codes of the installer mean for the app
  UpdateFailure.java    The French message of a failed update
  UpdateFiles.java      Which downloaded APKs to keep or delete
  UpdateThrottle.java   When to check (every 6 hours; by hand, once a minute) and how often to show the progress
  UpdateVersion.java    Compares the versions of the releases (v2.3.1)
  UpdateJson.java       Small JSON reader and writer, so the answer of GitHub can be checked without a device
app/                    Web app (plain JavaScript, no build step)
  index.html, app.css   Shell and theme
  logo.png              Logo shown in Settings → About
  core.js, icons.js     UI toolkit, API clients and icons
  qbit.js, qbit-more.js qBittorrent tab
  dash.js               qBittorrent ratio and Zen dashboard, hit-and-run alerts, clean-up rules
  arr.js, arr-more.js   Radarr, Sonarr and Lidarr screens: library, add, activity, calendar, import, system
  lidarr.js             Lidarr specifics: artists, albums, tracks, metadata profiles
  settings.js, main.js  Settings, startup and shared-link routing
assets-extra/           engine.html and the chrome.* shims for the extension
res/                    Launcher icon, bottom-bar icons, theme, the tile's icon, the app shortcuts and their icons
docs/icon.png           Logo used in this README
```

---

## 📄 License

Released under the [MIT License](LICENSE). The icons come from [Heroicons](https://heroicons.com) (MIT, Tailwind Labs): see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

## ⚠️ Disclaimer

This is an unofficial, personal project. It is not affiliated with C411, qBittorrent, Radarr, Sonarr, Lidarr or MusicBrainz. Use it in line with your tracker's rules and the laws of your country.
