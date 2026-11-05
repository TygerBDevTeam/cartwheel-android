# Cartwheel 0.1.2 preview

Update Cartwheel directly from your handheld.

- Open **Settings → Updates** to check GitHub for a newer APK, even before connecting your library.
- See your installed version, last successful check and release notes.
- Download an update in the app, verify it and open Android's installer.
- Include preview builds and optionally check on startup, once per day.
- Resume downloads after reopening the app, cancel a download or retry after a connection failure.
- Verify the APK's checksum, package, version and signing certificate before installation.

Download `Cartwheel-0.1.2-preview.apk` for the first installation. Future compatible releases can be installed through **Settings → Updates**. Android may ask you to allow installations from Cartwheel; return to the app and tap **Install update** after allowing it. Android requires your confirmation to install an update.

This preview uses the same signing key as the earlier Cartwheel previews. Updating normally preserves your library, themes, filters, favorites and play list. ROMs and ES-DE files remain untouched.

Existing features included:

- Import ES-DE gamelists or scan system folders in your ROM library.
- Select the ES-DE folder once to find gamelists and downloaded box art automatically, using ES-DE's ROM naming scheme.
- Pick a game, save it with **Yes, this one**, or **Skip** to another.
- Get online Game of the Week recommendations without personal ratings or API keys, and **reroll any time**.
- Use Metacritic scores recorded in Wikidata where titles and systems match; fall back to discovery picks when data is missing. Online score coverage varies and unspecified editions are labeled.
- Look up game background on Wikipedia, with source links.
- Filter systems, genres, favorites and unplayed games.
- Switch between Retro and Clean appearances.
- Use touch or D-pad controls in landscape, from 4:3 to 16:9.

Open **Connect your library** and grant access to your ES-DE and/or ROMs folders. Selecting ES-DE also finds its default artwork folder. Direct emulator launching is not included in this version.

Android 8.0+ required. The APK can be installed on Android 16; the Retroid Pocket Nova still needs hands-on testing. This is a debug-signed development build. Your ROMs and ES-DE files are never modified.

Online scores are cached for a week. A connection is needed to fetch new scores and Wikipedia background; roulette and cached/discovery weekly picks work offline. No API key or extra account is required.

All 51 automated tests passed, including update versions, preview filtering, download/cancel behavior, integrity and signing checks on Android 8 and Android 15, library imports, roulette and landscape screens. APK build, lint and signing-certificate checks passed. Real handheld testing of Android's installer is still needed.
