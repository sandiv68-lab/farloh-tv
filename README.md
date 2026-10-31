# Farloh TV

A simple Android IPTV player for Xtream Codes servers. It runs on Android TV boxes and Fire TV Sticks (remote control) and on phones and tablets (touch), Android 5.0 and newer.

## Features
- **Login** with server URL, username and password. The login is stored encrypted on the device.
- **Live TV** with categories, favourites, search and a TV guide (EPG) for each channel.
- **Movies** and **TV Shows** with posters, details, seasons and episodes, plus resume where you left off.
- **Manual refresh.** On the home screen: *Refresh* under Live TV, Movies and TV Shows, and *Refresh all*. Playlists are cached and are not re-downloaded on their own; when most groups are hidden, only the shown groups are downloaded.
- **Sorting** for Movies and TV Shows (default, recently added by server, rating, release date, A–Z, Z–A) per group or for all groups (not in "All").
- **Hide & arrange groups** (Settings → Parental control, optionally behind a PIN).
- **Built-in player.** AndroidX Media3 (ExoPlayer, open source). Press OK (or tap) for the overlay: playback controls in the middle and, at the bottom, one bar for Live TV, movies and episodes: Channels/Episodes, playback speed, audio track, audio sync, subtitles (CC), screen size, Multiview, favourite, Open in external and Settings (A–Z, including sound output, subtitle sync, Stats for Nerds, sleep timer and Record). Live TV shows the guide in the bar (Left / Right through the programmes) and what's on now in the channel list, with ← to the groups (TV shows: to the seasons). Picture-in-picture on devices that support it.
- **Multiview.** 2–4 videos at once (channels, movies or episodes from any playlist) in four layouts; every window is exactly 16:9. Only the chosen window has sound. Phones: pinch to zoom to fill the screen.
- **Recording (Live TV).** From the TV guide, *Record now* in the player, or set up by hand; once, every day or on chosen days, with extra time before and after. Runs in the background (foreground service + alarm), reconnects if the stream drops, and saves to device storage or a USB drive. Found in Downloads & Recordings.
- **Online subtitles** from Podnapisi and Addic7ed (no setup), plus OpenSubtitles with your own API key (optional).
- **Combined playlists, search with history (phones: voice search), EPG search, backup & restore, parental PIN, downloads.**
- **Open in external.** Opens the stream in MX Player (Pro or free), VLC or mpv – the first one installed – or Android's "Open with" list. Settings can make the external player the default.
- **Certificates.** Plain `http://` servers are allowed. For `https://`, an up-to-date bundle of public root certificates is included (`app/src/main/res/raw/extra_roots.pem`: Let's Encrypt ISRG X1/X2, Google Trust Services, DigiCert G2/G3, Sectigo, Amazon, SSL.com, GlobalSign, Certum). Older TV boxes are missing several of these. Certificates are always verified. To trust a server's own certificate, append its PEM to that file.

## Remote control (Live TV)
| Key | Action |
|---|---|
| Up / CH+ | Next channel |
| Down / CH- | Previous channel |
| OK / Right / Info / Menu | Overlay: the guide, ⏮ ⏸ ⏭ and the button bar (Up to the guide: Left / Right = earlier / later, OK = details & Record) |
| Left | Channel list (Left again: the groups) |
| Play/Pause | Pause / resume |
| Hold OK (in lists) | Add to / remove from Favourites (Menu: more options) |
| Back | Close overlay / exit player (or picture-in-picture, if set) |

## Remote control (Multiview)
| Key | Action |
|---|---|
| Arrows | Choose a window (the sound follows) |
| OK | Options: change channel/program, layout, full screen, swap, empty, leave |
| Menu | Layouts |
| CH+ / CH- | Next / previous channel in the chosen window |
| Back | Leave Multiview (the chosen window goes full screen) |

## Building
Every push to `main` builds a signed APK with GitHub Actions. You can download it from the **Releases** page or from the run's artifacts.

The release signing key is **not** stored in this repository. The build reads it from two repository secrets:
- `FARLOH_KEYSTORE_BASE64`: the `farloh-release.jks` file, base64-encoded
- `FARLOH_KEYSTORE_PASSWORD`: its password

Without these secrets the APK is signed with a temporary debug key. Keep the same release key forever, so that updates install over the existing app.

Local build: open the folder in Android Studio and choose *Build → Generate Signed APK*. Alternatively, put a `keystore.properties` file (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) in the project root and run `./gradlew assembleRelease`.
