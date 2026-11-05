# Cartwheel 0.1.1 preview

The first test build for Android retro handhelds.

- Import ES-DE gamelists or scan system folders in your ROM library.
- Select the ES-DE folder once to find gamelists and downloaded box art automatically, using ES-DE's ROM naming scheme.
- Pick a game, save it with **Yes, this one**, or **Skip** to another.
- Get online Game of the Week recommendations without personal ratings or API keys, and **reroll any time**.
- Use Metacritic scores recorded in Wikidata where titles and systems match; fall back to discovery picks when data is missing. Online score coverage varies and unspecified editions are labeled.
- Look up game background on Wikipedia, with source links.
- Filter systems, genres, favorites and unplayed games.
- Switch between Retro and Clean appearances.
- Use touch or D-pad controls in landscape, from 4:3 to 16:9.

Install `Cartwheel-0.1.1-preview.apk` on the handheld, open **Connect your library**, and grant access to your ES-DE and/or ROMs folders. Selecting ES-DE also finds its default artwork folder. Direct emulator launching is not included in this version.

Android 8.0+ required. The APK can be installed on Android 16; the Retroid Pocket Nova still needs hands-on testing. This is a debug-signed development build. Your ROMs and ES-DE files are never modified.

Online scores are cached for a week. A connection is needed to fetch new scores and Wikipedia background; roulette and cached/discovery weekly picks work offline. No API key or extra account is required.

All 30 automated tests passed. Validation covers folder imports, artwork priorities, online score matching and cache failures, roulette, weekly rerolls, and screen tests at 640×480 and 960×540. APK build, Android lint, and install-signature verification passed. Real handheld testing is still needed.
