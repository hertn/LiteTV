# Changelog

## 2.0.1

Bug fixes, mostly for slower TVs like the Chromecast with Google TV HD.

- Streams no longer stop for a few seconds every now and then. The player used to wait until its buffer was almost empty before loading more, so any slow moment on the network ran it dry. It now keeps loading all the time.
- Buffer Size is now **Buffering**, with two choices: Fast Start (default) or Steady (for weak Wi-Fi).
- New **Transparency** setting under Appearance. Turn it off for solid menus, which are lighter on slow TVs.
- The blur behind menus uses far less of the TV's processor and looks the same.
- A guide or playlist download that fails (for example right after the TV wakes up, before Wi-Fi is ready) is tried again up to 3 times.
- "Reading EPG 1 of 3" now counts every EPG you have set up, not just the current playlist's.
- In Themes, the New Year row no longer covers the OK and BACK hints.

## 2.0.0

A whole new look, made to feel like an Apple TV app, plus reminders and remote button settings.

**New look**

- Dark glass menus over the picture, with white focus, rounded corners and bigger, clearer text. On TVs that can do it, the picture behind the menus is blurred (Settings > Blur Behind Menus).
- New app icon and banner.
- Themes are now one accent colour (Purple, Blue, Teal, Green, Yellow, Orange, Pink, Graphite), plus seasonal themes: Christmas, Halloween and New Year.
- Seasonal decorations are newly painted, with one for each holiday.

**Channel list and guide**

- The channel list shows a LIVE badge, minutes left and a progress bar for what's on now. The playing channel keeps a grey box around its logo.
- The guide is split into days, channel logos and the shows on the selected channel. The selected show's details and buttons sit in a card beside it.
- Upcoming shows have a "Remind Me" button. When the show starts, a card asks if you want to switch to it.

**Watching**

- Changing channel shows the channel, the show, how many minutes are left and when it ends.
- OK (or Info) opens Channel Settings: four tiles for audio, subtitles, aspect ratio and audio delay.
- Hold OK to open the main menu, so remotes without a Menu button (like the Chromecast remote) can get there.
- Program Info is a full page with the show's details and what's on later on that channel.

**Remote Buttons**

- New page in the main menu that lists what every button does.
- Add your own buttons to any action, or reset them to the defaults.

**Other**

- Settings is one line per setting, with an explanation of the selected one at the bottom.
- "On Air Now" left the main menu. Left opens the same list while watching.

## 1.4.7

Update notices you can actually see:

- LiteTV now checks for a new version every time it starts (and then about every 12 hours while it runs), instead of skipping the check when one ran in the last 12 hours.
- When a new version is out, a message says so for 15 seconds every time the app starts, until you install it. Before, it showed only once, for 8 seconds.
- The main menu shows "Settings ● Update" and Settings > App update shows the new version, even after the app or TV restarts.
- Category names like `Docs &amp; Factual` now show as `Docs & Factual`.

## 1.4.6

Audio delay fixes:

- A saved negative delay (sound earlier) now shows correctly in Channel settings, instead of showing 0 ms and resetting to 0 when you press OK.
- Catch-up skipping (+10 s / -10 s) and the catch-up bar no longer drift by the channel's delay on every press or seek.
- Changing the delay while a channel plays no longer freezes the picture (when lowering it); the stream briefly reloads instead.
- Switching between channels with different delays no longer glitches the last frames of the old channel.
- Setting a delay before any channel has played now saves it for the channel shown, instead of doing nothing.
- The delay list hint now says which way Left and Right go (Left: -1 s, Right: +1 s).

## 1.4.5

- New "Audio delay" tile in Channel settings to fix sound that is out of sync with the picture. It goes from -30 s to +30 s in 50 ms steps (Left / Right in the list jump 1 s; + = sound later, - = sound earlier; big delays pause the picture once while the player buffers ahead), is saved per channel, and is used again whenever that channel (or its catch-up) plays.

## 1.4.4

- Fix "Decoding failed" on every channel after the TV had been on for a long time: the live stream now stops when you leave the app (Home, another app), so the TV's video decoders are freed instead of held in the background.

## 1.4.3

- Program info boxes are bigger: the guide info box is taller, and on the full info page the description no longer runs into "On air" and "Duration". Text that still doesn't fit ends in "...".
- Guide descriptions are now kept up to 1000 characters (was 300), so long descriptions are no longer cut off. The guide is re-imported once after updating.
- Favorites from all playlists now show up right at launch, without waiting for the guide to finish loading.
- Faster guide import: programme times are parsed without `SimpleDateFormat`.

## 1.4.2

- Settings now explain today's seasonal icon under "Seasonal decorations" (for example "Today: Halloween season").
- Code cleanup: the 2,900-line `MainActivity.kt` is split into smaller files (Themes, Seasons, Catchup, Ui, Settings, Epg, Playlists, Favorites, Phone and more). No behaviour changes.

## 1.4.1

- Redesigned the playlist setup panel: text on the left, QR code on the right.
