# Nuvio Clean privacy policy

*Last updated: 25 November 2026*

Nuvio Clean is a free, non-commercial, unofficial Android TV app based on the open-source Nuvio and Nuvio Reshaped apps. It adds family-friendly scene skipping. This policy explains what data the app and its skip server handle. The app has no ads, no analytics and no tracking, and nothing is ever sold.

## Scene skipping (the Nuvio Clean server)

Skip data comes from a CleanStream server run for this project (by default `familyskips.duckdns.org`; you can change it in Settings > Nuvio Clean).

**When you watch something**, the app asks the server for the skips of that title. The request contains the title's IMDb or TMDB id and your filter settings. To show poster tags, it also sends the ids of the titles on screen.

**When you share or confirm a scene** (the ♥ button, a "Skip" button for an unconfirmed scene, "Wrong skip", or the skip timing panel), the server stores:
- the start and end time of the scene, its category and your vote;
- a *timing fingerprint* of your copy of the film: the start and end times of its subtitle lines. Only times are sent, never the subtitle text;
- a random app ID that the app creates once when it is installed (random characters, nothing about you or your TV). The server keeps only a salted hash of it, to remember how many of this TV's marks were confirmed, so trusted contributors stay trusted when their internet address changes;
- an anonymous code made from your connection's IP address with a secret salt. It is used only to count each home's vote once, to stop one home from confirming its own marks, and to stop abuse. The server cannot turn it back into your IP address.

Like most web servers, the server may keep short-lived technical logs (IP address, time, request) to protect itself from abuse. It does not build profiles and does not share data with anyone. Shared scenes and timing fingerprints are community data and are kept so that others can use them.

## Trakt and Simkl

If you connect Trakt or Simkl (Settings > Tracking), you sign in on their websites with your own account. The app stores the sign-in token **on your TV only** and uses it to read and update your watch history, watchlist, progress and ratings with that service. That data goes directly between your TV and Trakt or Simkl; it never passes through the Nuvio Clean server. You can disconnect at any time in Settings > Tracking, and remove the app's access on the service's own website.

Nuvio Clean uses Trakt but is not endorsed or certified by Trakt.

## Movie and show information

Titles, posters and descriptions come from The Movie Database (TMDB) and from the add-ons you install. These requests go directly from your TV to those services. This product uses the TMDB API but is not endorsed or certified by TMDB.

## Nuvio account

If you sign in with a Nuvio account, your library, add-ons and progress sync through the Nuvio team's own service, under their terms and privacy policy. The Nuvio Clean developer does not receive that data.

## Sync with Google (from Nuvio Reshaped)

If you use Settings > Nuvio Reshaped > Sync, the app keeps one file in your own Google Drive with the settings you choose to sync. It uses only your email address (to show which account is signed in) and access to the files it created. That data goes only between your devices and your Google account.

## Children

The app is made to help families watch films with unwanted scenes skipped. It does not knowingly collect personal information from anyone, including children.

## Contact

Questions or requests (for example, to remove a scene you shared): open an issue at https://github.com/raad113/NuvioTv-Clean/issues.
