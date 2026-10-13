<img width="1280" height="640" alt="banner" src="https://github.com/user-attachments/assets/5616747e-c5e3-4220-abe7-893e4bbd99e0" />


# LiteTV

A lightweight live TV (IPTV) app for Android TV. Bring your own M3U playlists and guide (EPG) files; LiteTV plays the channels, shows what's on, and keeps memory use low.

> LiteTV does not include or provide any channels, playlists or guide data. You add your own.

> **100% vibe coded.** Every line of this app was written by AI (Claude) from plain-language instructions. I don't write code myself, so expect rough edges, and use it at your own risk.

## Features

- Live channels from one or more M3U playlists, with quick switching between playlists
- TV guide (EPG): now/next on every channel, a full guide screen by day, and a program info page
- Guide stored on disk instead of RAM, so memory use stays low even with long guides
- Guide refresh setting (manual, every 6, 12, 24 or 48 hours) and an "Update guide now" button
- Guide length setting (2 to 14 days, limited by what your guide file contains)
- Favorites, hide/unhide channels, and search across all playlists
- Audio track, subtitle and aspect ratio (fit, zoom, stretch) settings per channel
- Channel logos and clear stream error messages (for example "Server returned HTTP 404 (stream not found)")
- Themes: six built-in plus a custom theme where you pick your own colors
- Seasonal decorations for holidays (can be turned off)
- Set up playlists and guides from your phone by scanning a QR code
- Backup and restore of playlists, guides and favorites

## Screenshots

**Channel list**

<img width="1920" height="1080" alt="channels" src="https://github.com/user-attachments/assets/b59a2fee-2f8a-4ebe-8c48-87f26783198e" />


**TV guide**

<img width="1920" height="1080" alt="guide" src="https://github.com/user-attachments/assets/d34b4a95-8ff8-4c81-843b-db770040b3ec" />


**Program info**

<img width="1920" height="1080" alt="program_info" src="https://github.com/user-attachments/assets/c7cf86b3-0e19-4f6a-9fd3-f058c7009536" />


**Main menu**

<img width="1920" height="1080" alt="main_menu" src="https://github.com/user-attachments/assets/33c0ef5d-5533-4224-a08e-45f05747f529" />


**Settings**

<img width="1920" height="1080" alt="settings" src="https://github.com/user-attachments/assets/15ee5859-1ceb-42a3-bf1b-031275e20726" />


**Themes**

<img width="1920" height="1080" alt="themes" src="https://github.com/user-attachments/assets/fa7262f8-f408-4c32-a1f4-0b4ee4d0f529" />


## Install

1. Download the latest `LiteTV-x.y.z.apk` from the [Releases](../../releases) page.
2. Install it on your Android TV. The easiest way is with `adb`:
   ```
   adb connect <tv-ip>:<port>
   adb install -r LiteTV-x.y.z.apk
   ```
   Turn on Developer options on the TV first: Settings > Device Preferences > About, tap "Build" 7 times, then enable USB/network debugging.
3. If you have more than one device connected, add `-s <tv-ip>:<port>` after `adb`.

Updating with `adb install -r` keeps your playlists and settings.

## First start

1. Press OK on the remote to open the menu, then choose **Playlists**.
2. Scan the QR code with your phone (or open the address shown on the screen). The phone and the TV must be on the same Wi-Fi network.
3. On the web page, add your playlists (a name and the M3U link) and press **Save**.
4. Guides: either add an EPG link to a playlist on the same page, or open **Shared EPGs** to add one guide that you can apply to all playlists or only some. If a playlist has no guide set, LiteTV uses the one named in the M3U file's header, if it has one.

You can also use **Add here** on the TV to type a playlist in with the remote.

## Remote control

| Button | What it does |
| --- | --- |
| OK / Menu | Open the main menu (while watching) |
| Left | Open the channel list |
| Right / Guide / Blue | Open the TV guide |
| Up / Down | Next / previous channel |
| Info | Show the channel banner |
| 0-9 | Type a channel number |
| Back | Go to the last channel (or step back in menus) |
| Hold Back | Exit the app completely |

## Main menu

Search, Playlists, TV guide, On air now, Show/Hide hidden channels, Last channel and **Settings**.

**Settings** holds Themes, Seasonal decorations, Guide refresh, Guide length and Update guide now.

## Backup

Open the phone setup page and use **Download backup** to save your playlists, guides and favorites in one file. On another TV, open the same page and use **Restore from file**. Restoring replaces the lists on that TV.

## Build from source

1. Open the project in Android Studio.
2. Let Gradle sync.
3. Build > Build APK(s), or press Run with your TV connected.

The code is Kotlin. The main app lives in `MainActivity.kt` and uses a custom drawn interface (no layout XML for the screens) and Media3 ExoPlayer for playback.

## Notes

- Guide data is kept in a local database on the TV and is refreshed from the guide links you provide. Only channels that exist in your playlists are stored.
- How many days of guide you get depends on the guide file you use.
- LiteTV is not affiliated with any playlist or guide provider.

## License

MIT. See the LICENSE file.
