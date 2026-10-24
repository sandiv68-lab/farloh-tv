# Farloh TV

A simple Android IPTV player for Xtream Codes servers. It runs on Android TV boxes and Fire TV Sticks (remote control) and on phones and tablets (touch), Android 5.0 and newer.

## Features
- **Login** with server URL, username and password. The login is stored encrypted on the device.
- **Live TV** with categories, favourites, search and a TV guide (EPG) for each channel.
- **Movies** and **TV Shows** with posters, details, seasons and episodes, plus resume where you left off.
- **Manual refresh for each section.** Live TV, Movies and TV Shows each have their own *Refresh* button, on the home screen and inside each section. Playlists are cached and are not re-downloaded on their own.
- **Built-in player.** AndroidX Media3 (ExoPlayer, open source). Press OK (or tap) for the overlay: playback controls in the middle and, at the bottom, one bar for Live TV, movies and episodes: Channels/Episodes, playback speed, audio track, audio sync, subtitles (CC), screen size, Multiview, favourite, Open in external and Settings. Picture-in-picture on devices that support it.
- **Multiview.** 2–4 videos at once (channels, movies or episodes from any playlist) in four layouts; every window is exactly 16:9. Only the chosen window has sound.
- **Combined playlists, search with history, EPG search, backup & restore, parental PIN, downloads, online subtitles.**
- **Open in external.** Opens the stream in MX Player (Pro or free), VLC or mpv – the first one installed – or Android's "Open with" list. Settings can make the external player the default.
- **Certificates.** Plain `http://` servers are allowed. For `https://`, an up-to-date bundle of public root certificates is included (`app/src/main/res/raw/extra_roots.pem`: Let's Encrypt ISRG X1/X2, Google Trust Services, DigiCert G2/G3, Sectigo, Amazon, SSL.com, GlobalSign, Certum). Older TV boxes are missing several of these. Certificates are always verified. To trust a server's own certificate, append its PEM to that file.

## Remote control (Live TV)
| Key | Action |
|---|---|
| Up / CH+ | Next channel |
| Down / CH- | Previous channel |
| OK / Right / Info / Menu | Overlay: now & next, ⏮ ⏸ ⏭ and the button bar |
| Left | Channel list |
| Play/Pause | Pause / resume |
| Hold OK or Menu (in lists) | Options: open in external, add to favourites, TV guide |
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
