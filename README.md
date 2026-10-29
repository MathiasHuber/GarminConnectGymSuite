# Garmin Connect Gym Suite

An Android app for looking at your **strength training** data from Garmin
Connect. The Connect app doesn't do much with it. Everything runs on the phone:
there is no server, and the app only talks to Garmin.

- **Download all strength workouts.** Every strength training activity and its
  sets (exercise, reps, weight, duration) are stored on the phone. They can be
  exported as a flat CSV (one row per set) or JSON for analysis elsewhere.
- **Progress graphs per exercise.** Pick any exercise and see how the weight
  changed over time.
- **Estimated 1RM (e1RM).** "5 × 100 kg" and "3 × 110 kg" can be compared on one
  line instead of a jagged weight chart. You can choose Epley, Brzycki, Lombardi
  or their average, and a rep cut-off (default 12) so high-rep sets don't
  distort it.
- Other metrics: top weight, volume (Σ weight × reps), total reps, best set
  reps (for bodyweight exercises).
- Records are marked, there is an optional 5-session moving average, and time
  ranges (all / 1 year / 6 months / 3 months) and kg/lb are available.
- **Favorites.** Tap the heart on an exercise or group to pin it to the top
  of the list.
- **Exercise groups.** Garmin often logs the same movement under different
  names. Long-press exercises to select them, then tap **Group**. The group is
  charted and summarised as one exercise. Edit it, or add a single exercise to
  a group, with the pencil on the exercise screen.
- **Excluding outliers.** Tap a session to untick individual sets or exclude
  the whole workout. Or tap a point on the chart and choose **Exclude this
  set**. Excluded data stays visible (struck through) and can be restored; it
  only drops out of charts, records and e1RM.

Favorites, groups and exclusions live in their own file on the phone. A full
resync or **Delete local data** doesn't touch them. The JSON export includes
them, and the CSV has `group` and `excluded` columns.

## Install

Every push builds a signed APK in GitHub Actions:

1. Open the repository's **Actions** tab, then the latest **Build APK** run.
2. Download the **GarminConnectGymSuite-apk** artifact. It's a zip; unzip it to
   get `GarminConnectGymSuite.apk`.
3. Copy it to the phone and open it. Android asks you to allow installs from
   that app (browser or file manager) once.

Pushing a tag like `v0.1.0` also attaches the APK to a GitHub Release, which is
easier to download on the phone.

Every build is signed with the same key (`app/gymsuite.keystore`), so a new
APK installs over the old one and keeps your data. That key is committed on
purpose and is **not secret**. Only install APKs you built from this repo.

## Signing in to Garmin

Garmin has no public API for personal use. This app uses the same unofficial
login and endpoints as
[python-garminconnect](https://github.com/cyberjunky/python-garminconnect)
(0.3.x), ported to Kotlin:

1. **Sign in with Garmin** opens Garmin's own sign-in page in an in-app browser.
   You type your credentials and any MFA code there. Garmin's bot protection
   sees a real browser, which is why this works on a phone where the Python
   library's TLS tricks (`curl_cffi`) are unavailable.
2. When Garmin redirects back with a service ticket, the app exchanges it for
   the OAuth2 bearer token the Garmin Connect mobile app uses (`diauth.garmin.com`)
   and stores it privately on the phone. The token refreshes itself; your
   password is never seen or stored by the app.
3. If the mobile sign-in doesn't work, try **Use web sign-in instead** (Garmin's
   connect.garmin.com sign-in flow).
4. Last resort: log in with python-garminconnect on a computer and import the
   token:
   ```bash
   pip install -r tools/requirements.txt
   python tools/garmin_tokens.py login     # prints {"di_token": ..., ...}
   ```
   Paste the output into **Import tokens from python-garminconnect** in the
   app. Use it in only one place afterwards, since refreshing the token in one
   place can invalidate it in the other.

This is an unofficial, non-commercial tool. Garmin can change their login or
endpoints at any time.

### Data used

| What | Endpoint |
|---|---|
| Strength activities | `GET connectapi.garmin.com/activitylist-service/activities/search/activities?activityType=fitness_equipment` (filtered to `strength_training` in the app) |
| Sets of an activity | `GET connectapi.garmin.com/activity-service/activity/{id}/exerciseSets` |

Garmin reports set weight in grams; the app converts it to kg. Rest sets are
dropped. If Garmin listed several candidate exercises for a set, the one with
the highest probability is used, so edit wrong exercises in Garmin Connect and
run **Full resync**. Normal **Sync** only fetches activities it hasn't seen yet.
Garmin's raw `exerciseSets` responses are also kept on the phone.

### Analysis on a computer

`python tools/garmin_tokens.py export sets.csv` writes the same CSV as the
app's export, straight from Garmin.

## Building locally

Needs JDK 17+ and the Android SDK (Android Studio, or `ANDROID_HOME` set):

```bash
./gradlew testDebugUnitTest assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

## Project layout

```
app/src/main/java/io/github/mathiashuber/gymsuite/
  garmin/    login ticket → token exchange, refresh, API calls, JSON parsing
  data/      models, on-device storage, sync, CSV export
  analysis/  e1RM formulas and per-session progression
  ui/        Compose screens and the progress chart
tools/       python-garminconnect helper (token export, CSV export)
```
