# Changelog

## 2.2.1

Small fixes to panels and the OK screen.

- The last item in the Playlists and Categories panels is no longer cut off by the panel's bottom edge.
- Favorite Groups and Error Log are now cards sized to what they hold, with their hints inside the card.
- Back from Program Info or the TV Guide, opened from the OK screen while watching, now goes straight back to the picture.

## 2.2.0

A TV Guide grid, a Reminders page, reminders that repeat, a channel list that goes round, and a smoother, animated look.

**TV guide**

- New **TV Guide** grid: every channel down the side and its shows across a two-hour timeline, with a line at the current time and the played part of each live show shaded. OK watches a live show, sets a reminder on a later one or plays catch-up on a past one; hold OK for Program Info. Open it from the main menu, the channel list's buttons, the OK screen or the Guide button.
- The per-channel guide is now the **Quick Guide** (▶ by default). The chosen day is a white pill, and picking another day lands on the show at the same time of day instead of last night's late show. OK on a date goes to its shows.

**Reminders**

- Reminders repeat: set one on a show and every airing of it at that time on that channel gets one, until you remove it. Episode numbers like (1/8) are ignored, and reminders you already had carry over.
- New **Reminders** page in the main menu: only the days that have reminders, each with its time, channel logo and show. OK opens Program Info.
- In Program Info, the "Later on" shows can be selected and OK sets or removes a reminder.

**Channel list and Quick Guide**

- The list goes round: past the last channel it starts again from the first, with a thin line and a round arrow where it does, so there are no more empty rows at the ends. Holding ▲ or ▼ stops at the end; one more press goes round.
- Three quick taps of ▲ or ▼ jump to the top or bottom.
- Scrolling glides: the channels spring into place while the highlight stays still.
- The TV Guide button is back in the channel list's buttons.

**OK screen and menus**

- OK while watching now shows Program Info and TV Guide tiles before Audio, Subtitles, Aspect and Delay, and hides itself after 5 seconds if nothing is pressed.
- Watch in Program Info just closes it when that show is already on.
- Main menu: Search, Playlists, TV Guide, Reminders, Hidden Channels and Settings. Last Channel is gone (Back does that) and Remote Buttons moved into Settings.
- New **Hidden Channels** page: hidden channels as a grid of logos, OK unhides one.
- Favorites is now the first row of the Playlists panel, with "All Favorites" and your playlist's groups as its categories.
- Closing the Playlists pop-up goes back to the main menu.

**Look and feel**

- Screens fade into each other, panels slide in and notices pop up. All of it is off when the TV's animations are turned off.
- The main menu rows shrink to fit.
- The shading behind the top of the TV Guide fades out instead of ending in a hard line.

## 2.1.0

A new About page, tidier pop-ups and notices, and aspect ratio settings that work on every channel.

**Aspect ratio**

- Most channels send a 16:9 picture even when a 4:3 show or a widescreen film is inside it with black bars, so the old Zoom and Stretch never did anything. The options are now **Fit**, **Zoom** (enlarges the picture and trims the edges), **Wider** (for bars at the sides) and **Taller** (for bars at the top and bottom).
- Option lists with up to five choices show them all, without empty space, and point at the tile they belong to, like the button bar does.

**Look and feel**

- New **About** page at the bottom of Settings: version, what LiteTV is, your playlists and TV guides, this TV, and QR codes for the GitHub page and Ko-fi.
- Search, Playlists & TV Guides and Add Playlist are now cards on the left, like the rest of the app, with rows like the main menu instead of big buttons. No more coloured border.
- Settings is narrower, and the selected row explains itself under its name. The version number moved to the main menu, next to the LiteTV name.
- Notices (reminders, sleep timer, aspect ratio and so on) appear as a small card in the top-right corner instead of over the menus.
- Typing a channel number shows just the number, big, in the top-right corner.
- Button hints show the remote's buttons as keys. Screens that you leave with Back no longer say so.
- New icons for Blur Behind Menus, Transparency and Error Log.
- The Themes preview card is only as big as its contents.

**Fixes**

- Audio or Subtitles with only one choice is dimmed and says "Only one", instead of showing a notice.
- Typing the number of the channel you're already watching no longer restarts it.
- The number buttons do nothing while you watch catch-up, so a stray press can't take you back to live.
- With Transparency off, Blur Behind Menus can no longer be changed.

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
