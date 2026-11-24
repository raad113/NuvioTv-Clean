# NuvioTv-Family

NuvioTv-Family is an unofficial, modified version of [NuvioTV Reshaped](https://github.com/DavidVamaiotu/NuvioTV-Reshaped)
(itself a community fork of [NuvioTV](https://github.com/NuvioMedia/NuvioTV))
that skips unwanted scenes automatically, using skip data from a
[CleanStream](https://github.com/raad113/stremio-cleanstream) server.
It is not made or supported by the Nuvio team.

## What it adds

* **Nothing to set up.** On first start the app adds the CleanStream add-on (rows with
  tagged posters) and imports the family collection from `https://familyskips.duckdns.org`.
  Each happens once; if you remove them later they are not added back.
* **♥ button in the player.** Press it when a kissing or sexual scene starts and again when
  it ends. The scene is shared five seconds later (press ♥ again to cancel).
* **Mute bad language using subtitles (per profile).** When turned on for a profile, the app
  scans the subtitle in use (the selected one, otherwise an English or Arabic one from your
  subtitle add-ons) and mutes the lines with strong English or Arabic swear words, plus any
  extra words that profile adds. Subtitles inside the video file are not read yet.
* **Unconfirmed scenes are never skipped silently.** Scenes marked by other viewers that no
  second viewer has confirmed yet appear as a **"Skip … (unconfirmed)"** button. Pressing it
  skips the scene and confirms it for everyone.

* **Automatic skipping.** While a movie or episode plays, scenes flagged on the
  CleanStream server are jumped over. Scenes flagged as "audio only" (for example
  strong language) are muted instead. A short notice ("Skipped 12s: Violence")
  appears each time.
* **Settings → Integrations → NuvioTv-Family (CleanStream).** Turn skipping on or off,
  choose the CleanStream server, and set each filter (nudity, sexual content and
  kissing, violence, language, drugs, frightening scenes) to Off, Low, Medium or High.
  High skips every flagged scene; Low skips only the strongest.
* **Safety margin** (default 1 s) skips a little extra before and after each scene,
  and **Timing adjustment** moves all skips earlier or later for a differently timed copy.
* It works with Nuvio's ExoPlayer and mpv players, and is paused for guests in a watch party.

## Other changes from NuvioTV

* App name "NuvioTv-Family" and app ID `com.nuviotvfamily.tv`, so it installs next to the official app and Reshaped.
* The in-app updater only looks at this repository's releases, never official Nuvio's or Reshaped's.
* The CleanStream add-on's warning subtitle tracks are hidden, because the app skips by itself.
* `.github/workflows/build-nuviotv-family.yml` builds the APKs on every push to the
  NuvioTv-Family branch and publishes them as a release. Reshaped's own workflows are unchanged.
* Nuvio account sync, Trakt and other services need keys that are not included.
  Add your own keys (for example `TMDB_API_KEY`, `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET`)
  as one `KEY=value` per line in the repository secret `NUVIOTV_FAMILY_LOCAL_PROPERTIES`.
  Nuvio account sync uses the Nuvio team's own server and is not available.

## Signing (so updates install over the old version)

Add two repository secrets: `NUVIOTV_FAMILY_KEYSTORE_BASE64` (the keystore file, base64 encoded,
key alias `nuviotvfamily`) and `NUVIOTV_FAMILY_KEYSTORE_PASSWORD`. Keep a copy of the keystore:
without it, future builds cannot update an installed NuvioTv-Family.

## Installing on Android TV

1. Open the repository's **Releases** page and copy the link to the latest `NuvioTv-Family-*.apk`.
2. On the TV, install the **Downloader** app, enter the link and install the APK
   (allow "install unknown apps" for Downloader when asked).

## Licence

NuvioTV is licensed under the GNU GPL v3, and so is this modified version (see `LICENSE`).
Changed files: `app/build.gradle.kts`, the `app_name` strings, `SettingsScreen.kt`,
`PlayerRuntimeController.kt`, `PlayerRuntimeControllerObservers.kt`,
`PlayerRuntimeControllerPlaybackEvents.kt`, `NuvioMpvSurfaceView.kt` and the workflows.
New files: `core/cleanstream/CleanStream.kt`, `PlayerRuntimeControllerCleanStream.kt`,
`CleanStreamSettingsScreen.kt` and `CleanStreamClientTest.kt`.
