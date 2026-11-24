<div align="center">

  <h1>NuvioTv-Clean</h1>

  <p>
    A family-friendly version of <a href="https://github.com/DavidVamaiotu/NuvioTV-Reshaped">NuvioTV Reshaped</a> for Android TV.
    <br />
    It skips unwanted scenes and mutes bad language by itself, using skip times shared by the community.
  </p>

  [Download the latest APK](https://github.com/raad113/NuvioTv-Clean/releases/latest/download/NuvioTv-Clean.apk) · [All releases](https://github.com/raad113/NuvioTv-Clean/releases) · [CleanStream server](https://github.com/raad113/stremio-cleanstream)

</div>

## Install on Android TV

**Downloader code: `XXXXXXX`** <!-- replace with the code from go.aftvnews.com -->

1. Install the free **Downloader** app (by AFTVnews) from the TV's app store.
2. Open it, type the code above in the URL box and press **Go**.
3. When Android asks, allow Downloader to install unknown apps, then press **Install**.

The code always points to the newest build. If you prefer to type the full link, use:

```
https://github.com/raad113/NuvioTv-Clean/releases/latest/download/NuvioTv-Clean.apk
```

New builds install as an update over the old one, so your profiles, add-ons and settings stay. The app can also update itself: it checks this repository's releases only.

## What NuvioTv-Clean does

### Skips scenes automatically

While a movie or episode plays, the app asks the CleanStream server for the scenes flagged in that title and jumps over them. Scenes marked as audio only, such as a burst of strong language, are muted instead of skipped. A short notice such as "Skipped 12s: Violence" appears each time, so you always know something was left out. Skipping works with both of Nuvio's players (ExoPlayer and mpv) and pauses for guests in a watch party.

### Lets you choose how strict it is

Open **Settings > Integrations > NuvioTv-Clean (CleanStream)**. Each category (nudity, sexual content and kissing, violence, language, drugs and frightening scenes) can be set to Off, Low, Medium or High. High skips every flagged scene in that category, and Low skips only the strongest ones. The same page has a **safety margin** that skips a little extra on each side of a scene (one second by default) and a **timing adjustment** for copies of a film whose timing differs slightly.

### Mutes swear words using subtitles, per profile

Each profile can turn on subtitle muting. The app then reads the subtitle in use (the one you selected, otherwise an English or Arabic one from your subtitle add-ons) and mutes the lines that contain strong English or Arabic swear words. A profile can add its own words to the list. Subtitles built into the video file itself are not read yet.

### Lets you share a scene with two presses

The player has a **♥** button next to Play/Pause. Press it when a kissing or sexual scene starts and press it again when the scene ends. The scene is sent five seconds later, and pressing ♥ once more during those five seconds cancels it. Nothing else needs to be typed.

### Protects against wrong or mischievous timings

A scene shared by one viewer is not skipped silently for anyone else. It first appears as a **"Skip … (unconfirmed)"** button, and pressing that button skips the scene and counts as a confirmation. A scene becomes automatic once a second, independent viewer confirms it or marks the same moment. The server also limits how long a shared scene may be, rejects scenes that viewers vote down, and stops accepting scenes from a device whose marks keep getting rejected. Scenes imported from other public skip databases follow the same rule and stay unconfirmed until viewers confirm them.

### Sets itself up

On first start the app adds the CleanStream add-on, which shows rows of titles with "clean" tags and digital-release dates on their posters, and imports a ready-made family collection for each profile. Each happens once, so if you remove them they stay removed.

### Works with your Nuvio account

Sign in with your Nuvio account by scanning the QR code, as in the official app and Reshaped. Your library, add-ons and watch progress sync as usual.

## Settings at a glance

| Setting | Where | What it does |
| --- | --- | --- |
| Skipping on/off | Integrations > NuvioTv-Clean | Turns all automatic skipping on or off |
| Category levels | Integrations > NuvioTv-Clean | Off, Low, Medium or High for each category |
| Safety margin | Integrations > NuvioTv-Clean | Extra time skipped before and after each scene |
| Timing adjustment | Integrations > NuvioTv-Clean | Moves all skips earlier or later |
| Mute bad language | Integrations > NuvioTv-Clean (per profile) | Mutes subtitle lines with swear words |
| Extra words | Integrations > NuvioTv-Clean (per profile) | Your own words to mute, separated by commas |
| Server | Integrations > NuvioTv-Clean | The CleanStream server (default `https://familyskips.duckdns.org`) |

## Features kept from NuvioTV Reshaped

Everything Reshaped adds is still here, under **Settings > Nuvio Reshaped**:

- **Auto Sync Subtitles.** Align an add-on subtitle with subtitles embedded in the video. Set a tolerance to leave small differences alone, or enable **Thorough Auto Sync Search** to compare more references before applying a confident match.
- **AutoSync bubble notifications.** Show synchronization progress and results in an optional bubble.
- **Automatically try another source.** If playback fails to start, try the next source using your **First Source** or **Regex** autoplay preference. Requires an internal player and one of those autoplay modes.
- **Sound and picture formats — Arc format handling.** Enable the format handling ported from Nuvio Arc for the built-in player. It adds TrueHD/DTS-HD passthrough handling, automatic or manual surround-format capabilities, system passthrough, an ARC soundbar mode, optional AC-3 conversion, and a stereo/5.1/7.1 decode target. You can retest lossless passthrough, clear learned audio refusals or tunnel stalls, and enable HDR10 metadata injection when supported Dolby Vision Profile 8 video is played as HDR10. Actual output depends on your device and audio system.
- **Seekr API key.** Supply your own Seekr key for seek-preview thumbnails.
- **Generate previews on device.** Make seek thumbnails from video already downloaded during playback. Seekr fills gaps for parts you have not watched yet.
- **Custom subtitle fonts.** Choose a subtitle font by uploading it from your phone.
- **Connection-aware stream ordering.** Use measured playback speed to move streams that are likely too heavy for your connection further down the list.
- **Pill menu.** Replace the sidebar with a floating menu at the top. Press Up to reach it and Back to jump to it.
- **Netflix-style Home.** Use a billboard at the top and rows whose focused title expands to show details. It changes the presentation while keeping the existing rows and actions. The pill menu stays enabled while this layout is on.
- **Live TV.** Configure your live-channel sources and programme guide from the Reshaped Live TV options.
- **Sync with Google.** Sync Reshaped settings and Live TV across your TVs and phone. Settings sync and Live TV sync have separate switches, plus a **Sync now** action. Buffer sizes and channel-preview options stay per device.

For the phone and tablet options, see the [mobile README](https://github.com/DavidVamaiotu/NuvioMobile-Reshaped#readme).

Problems with these Reshaped features belong in [the Reshaped repository](https://github.com/DavidVamaiotu/NuvioTV-Reshaped/issues); problems with skipping, muting or the ♥ button belong [here](https://github.com/raad113/NuvioTv-Clean/issues).

## Build from source

Every push to the `ccr-ec2cc868-lz49fa` branch builds the APKs with `.github/workflows/build-nuviotv-clean.yml` and publishes a release. To build locally with Android Studio, a JDK and the Android SDK:

```bash
git clone -b ccr-ec2cc868-lz49fa https://github.com/raad113/NuvioTv-Clean.git
cd NuvioTv-Clean
./gradlew :app:assembleFullDebug
```

See [NUVIOTV_CLEAN.md](./NUVIOTV_CLEAN.md) for the build secrets and the full list of changed files.

## Credits and license

Built on [NuvioTV Reshaped](https://github.com/DavidVamaiotu/NuvioTV-Reshaped) and [NuvioTV](https://github.com/tapframe/NuvioTV) by their contributors and the [NuvioMedia](https://github.com/NuvioMedia) team. NuvioTv-Clean is unofficial and is not made or supported by the Nuvio or Reshaped teams. Consider [supporting Nuvio](https://nuvio.tv/support).

Licensed under the [GNU General Public License v3.0](./LICENSE), the same as upstream. Movie and show information comes from [TMDB](https://www.themoviedb.org); this product uses the TMDB API but is not endorsed or certified by TMDB.

## Legal

NuvioTv-Clean browses metadata and plays media from user-installed add-ons or user-provided sources. Use it only for content you own or are authorized to access. It does not host, store or distribute media. Skipping and muting happen on your own device during private viewing and do not change or copy the film, which is the kind of filtering the U.S. Family Movie Act of 2005 permits.
