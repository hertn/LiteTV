# Changelog

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
