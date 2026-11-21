# telly for Android TV and Fire TV

A small, fast IPTV player for Fire TV and Android TV. Bring your own provider (Xtream Codes or
an M3U playlist); telly gives you a clean, remote-friendly way to watch it.

- **Home** with Continue watching, My List, recently added films and series
- **Live TV** as a quick channel list with a **programme guide** (now and next)
- **Series and films** in rows, with detail pages, seasons and episodes
- **Picks up where you left off**, including the next episode once the credits roll
- **New episodes** of a series you've finished bring it back to Continue watching
- **Search everything**, by typing or with the remote's voice button
- **Favourites** (My List) with the remote's ☰ button
- **Aspect ratio** per channel or title, including a custom fit
- Lists are saved on the device, so telly opens instantly and refreshes in the background

telly contains no channels or content. You need your own subscription from an IPTV provider.

telly is also on [iPad and Mac](https://github.com/tellyteam/telly-apple) and
[LG TVs](https://github.com/tellyteam/telly-lg).

## Install on a Fire TV

1. Install **Downloader** from the Amazon Appstore.
2. Go to **Settings → My Fire TV → Developer options → Install unknown apps** and turn on
   **Downloader**. (If you can't see Developer options: **Settings → My Fire TV → About**, then
   press OK on your device name seven times.)
3. Open Downloader and enter the code **`3327748`** (or `aftv.news/3327748`), or this address:

   ```
   https://github.com/tellyteam/telly-android/releases/latest/download/telly.apk
   ```

4. Choose **Install**, then **Open**. Add your provider and you're watching.

**Updating:** telly checks for a new version once a week and lets you know; install it from
**Settings → Updates** (you can also check there any time). Android asks you to confirm, and only
accepts an update signed by telly. Running the Downloader code again also works. Either way,
everything you've set up is kept.

## Install on Android TV / Google TV

Install **Downloader** from Google Play, allow it to install unknown apps
(**Settings → Apps → Security & restrictions**), then follow steps 3–4 above.

## Privacy

- telly talks only to your provider, plus a weekly check with GitHub for a new telly version, and
  telly's relay while a setup QR code shows (or if you turn on sync). No accounts, analytics,
  adverts or tracking.
- It never reads device identifiers or starts at boot, and never installs anything without asking.
- Your provider login is encrypted on the device with an Android Keystore key. It's never synced.
- Setup shows a QR code so the details can be typed on a phone instead of with the remote. Sent from
  the phone, they're sealed with a key that's only in the QR code, so only this TV can read them;
  telly's relay holds them for at most ten minutes and can't open them. They're shown for you to
  check, and nothing is saved until you press Connect.
- Plain HTTP is refused unless you allow it for your provider (some providers have no HTTPS).

## Building from source

Requires JDK 17+ and the Android SDK (compileSdk 35).

```bash
./gradlew testDebugUnitTest   # unit tests
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

The icons, TV banners and splash are drawn by [telly-brand](https://github.com/tellyteam/telly-brand).

For development you can create a git-ignored `provider.local.properties` in the project root
(`type=xtream`, `name`, `server`, `username`, `password`, or `type=m3u` with `url`) to pre-fill
the setup screen in **debug** builds. Release builds never include it.

## Releasing

Push a version tag; GitHub Actions tests, builds, signs and publishes `telly.apk`:

```bash
git tag -a v1.0.1 -m "- What changed, in a line or two" && git push origin v1.0.1
```

The tag's message becomes the release notes, which telly shows in Settings → Updates.

The release workflow needs two repository secrets: `TELLY_KEYSTORE_BASE64` (the release keystore,
base64-encoded) and `TELLY_KEYSTORE_PASSWORD`. Every release must be signed with the same key, or
existing installs can't update.

## Licence

telly is free for personal, non-commercial use under the [PolyForm Noncommercial License 1.0.0](LICENSE). Commercial use, including selling telly or bundling it with a paid service, needs permission: ask through GitHub.
