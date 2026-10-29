# STArchive Custom Cast Receiver

The Chromecast **default media receiver** can play archive MP3 tracks but fails on
`live.streambox.space` Icecast streams because it does not strip **ICY metadata**.
The receiver recognizes the live stream and counts time, but never outputs audio.

This folder contains a tiny custom receiver that uses the browser's `<audio>`
element, which handles Icecast/ICY streams correctly.

## Deploy

1. Host `cast-receiver/index.html` on any public HTTPS URL.
   Easy option with Vercel:

   ```bash
   cd cast-receiver
   npx vercel --prod
   # note the deployed URL, e.g. https://starchive-cast-receiver.vercel.app
   ```

2. Register a **Cast Receiver** app at https://cast.google.com/publish:
   - Type: **Receiver**
   - Receiver URL: the URL from step 1
   - Publish (or save as unpublished for testing)
   - Copy the **Application ID** (looks like `ABCD1234`)

3. In `app/src/main/java/com/starchive/player/playback/CastOptionsProvider.kt`,
   replace `CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID` with your
   app ID:

   ```kotlin
   .setReceiverApplicationId("YOUR_APP_ID_HERE")
   ```

4. Rebuild and install the app.

Now live streams will route to your custom receiver and play correctly.
