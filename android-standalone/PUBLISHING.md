# Publishing Sonos Lyrics Local to Google Play

This guide covers everything from creating a signing key to a compliant store
listing. The app is already wired for **AdMob** (banner + interstitial) and
targets **API 36**, which meets Play's requirement for new submissions.

> [!IMPORTANT]
> **Lyrics licensing.** This app fetches lyrics from lrclib.net and lyrics.ovh,
> which are community/free sources — not licensed lyrics providers. Publishing a
> monetized lyrics app carries a real risk of copyright complaints and removal.
> Understand this risk (and consider a licensed lyrics provider) before you
> publish commercially. This is a legal/business decision, not a technical one.

---

## 1. One-time: create an upload keystore

On any machine with a JDK:

```bash
keytool -genkeypair -v \
  -keystore sonos-lyrics-release.jks \
  -alias sonos-upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

Keep the `.jks` file and passwords **safe and private** — losing them means you
can't update the app (unless you enroll in Play App Signing, which is
recommended). Never commit the keystore.

### Add the signing secrets to GitHub (for CI builds)

Base64-encode the keystore and add these **repository secrets**
(Settings → Secrets and variables → Actions):

```bash
base64 -w0 sonos-lyrics-release.jks   # copy the output
```

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | the base64 string above |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `sonos-upload` |
| `KEY_PASSWORD` | key password |

## 2. One-time: set up AdMob

1. Create an app in [AdMob](https://apps.admob.com/) for **Android**.
2. Create **one banner** and **one interstitial** ad unit.
3. Copy the IDs and add them as GitHub secrets:

| Secret | Example |
| --- | --- |
| `ADMOB_APP_ID` | `ca-app-pub-1234567890123456~1234567890` |
| `ADMOB_BANNER_ID` | `ca-app-pub-1234567890123456/1111111111` |
| `ADMOB_INTERSTITIAL_ID` | `ca-app-pub-1234567890123456/2222222222` |

> Until these are set, builds use Google's **test** ad IDs. Test ads are fine
> for development but you must **not** publish a build serving test ads, and you
> must **not** click your own live ads.

## 3. Build the signed release bundle (AAB)

Push a tag or run the workflow manually:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

This runs **`.github/workflows/android-standalone-release.yml`**, which produces
a signed **`app-release.aab`** as a downloadable artifact
(`sonos-lyrics-standalone-release-aab`).

To build locally instead:

```bash
cd android-standalone
KEYSTORE_FILE=/path/sonos-lyrics-release.jks \
KEYSTORE_PASSWORD=... KEY_ALIAS=sonos-upload KEY_PASSWORD=... \
gradle bundleRelease \
  -PADMOB_APP_ID=... -PADMOB_BANNER_ID=... -PADMOB_INTERSTITIAL_ID=...
# Output: app/build/outputs/bundle/release/app-release.aab
```

## 4. Google Play Console

1. Pay the one-time \$25 developer registration fee (if you haven't).
2. **Create app** → name **Sonos Lyrics Local**, type **App**, **Free**.
3. Enable **Play App Signing** (recommended) and upload the AAB to an
   **Internal testing** track first.
4. Complete the required declarations:
   - **Privacy policy URL** — host `PRIVACY_POLICY.md` somewhere public (e.g.
     GitHub Pages / a gist) and add your contact email first.
   - **Data safety** — declare: *Advertising or marketing* use; data types
     **Device or other IDs** (Advertising ID) and approximate location/app
     activity as collected by the ads SDK; data is shared with Google for ads.
   - **Ads** — answer **Yes, the app contains ads**.
   - **Content rating** questionnaire.
   - **Target audience** — not directed at children.
   - **Government apps / financial** — No.
5. Add store listing assets: short + full description, app icon (512×512),
   feature graphic (1024×500), and at least 2 phone/tablet screenshots
   (landscape is fine — the app is landscape-locked).
6. Roll out to Internal testing, verify on a device, then promote to Production.

## 5. Pre-submission checklist

- [ ] Real AdMob IDs configured (not the test IDs).
- [ ] Signed AAB built and uploaded.
- [ ] Privacy policy hosted at a public URL with a real contact email.
- [ ] Data safety form matches the ads SDK's data collection.
- [ ] `versionCode` bumped for each upload (see `app/build.gradle`).
- [ ] Verified on a real device on the same Wi‑Fi as your Sonos speakers.
- [ ] Considered the lyrics-licensing risk noted above.

## Bumping the version for updates

Edit `android-standalone/app/build.gradle`:

```gradle
versionCode 2          // must increase every upload
versionName '1.0.1'
```
