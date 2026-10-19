# Homebase

A plain home screen for the onn 4K / 4K Pro (Google TV). Your apps, a clock, nothing else.

- No ads, no "recommended for you" rows, no tracking. The only thing it downloads is
  the artwork your own apps list in Continue Watching
- No dependencies: no AndroidX, no SDKs. The APK is tiny
- Favorites row on top (hold **OK** on any app → Pin, Move left/right)
- **Continue Watching** row: whatever your apps report to Android TV's system
  "Watch Next" list, with artwork and a progress bar. OK resumes; hold OK to remove one
- **Live backgrounds**: Aurora, Starfield, Waves, Embers, or Still. Change them from the
  **Background** button. They only animate while you're on the home screen
- Every installed app below, A–Z, including sideloaded phone apps
- Hide apps you never use (Options → Show hidden apps to bring them back)
- Wi-Fi and Settings one click from home
- Home on the remote always jumps back to the top; Back never leaves home

## 1. Get the APK

Push this folder to a GitHub repo (e.g. `Taajaveli/homebase`). The included
workflow builds it automatically. After the first run on `main`, the APK is at:

```
https://github.com/Taajaveli/homebase/releases/download/latest/homebase.apk
```

(Or open the project in Android Studio and hit Run.)

## 2. Install it on the onn

Easiest: install **Downloader** (by AFTVnews) from the Play Store on the box,
type the URL above, and install. Allow "Install unknown apps" for Downloader
when it asks.

Homebase now shows up in your app list. Open it to try it out.

## 3. Make it the real home screen

Google TV has no "choose your launcher" setting, so you switch it over with ADB
from a computer on the same Wi-Fi.

1. On the onn: **Settings → System → About**, click **Android TV OS build** 7 times.
2. **Settings → System → Developer options** → turn on **USB debugging**.
3. Note the box's IP: **Settings → Network & Internet →** your Wi-Fi.
4. On your computer, install Android platform-tools (gives you `adb`), then:

```bash
cd tools
chmod +x debloat.sh restore.sh
./debloat.sh 192.168.1.50                # your box's IP
```

Accept the "Allow USB debugging?" popup on the TV the first time.

That disables the stock Google TV home screen and removes the ad / promo
feeds. Add `--streaming` to also remove preinstalled Netflix, Prime Video,
Disney+, etc. (you can reinstall any of them from the Play Store). Run with
`--list` first if you want to see what's on the box without changing anything.

**Windows without Git Bash?** Run these one at a time in a terminal instead:

```
adb connect 192.168.1.50:5555
adb shell pm grant com.taajaveli.homebase android.permission.READ_TV_LISTINGS
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
adb shell pm uninstall -k --user 0 com.google.android.tvrecommendations
adb shell pm uninstall -k --user 0 com.google.android.apps.tv.dreamx
```

## Good to know

- **Continue Watching** asks for access the first time Homebase opens. If the box
  won't show that prompt, `debloat.sh` grants it. Run it again if you set Homebase up
  before version 1.1. Only apps that report progress to the system list show up.
  Some apps only send it to Google's servers, so they won't appear.

- **What you lose:** the stock Google TV tabs (For You, Live, Library) and its
  profile switcher. Voice search on the remote, casting, and all your apps keep working.
- **Screensaver:** removing `dreamx` drops the promo-filled ambient mode. Pick
  another screensaver (or none) under Settings → System → Ambient mode / Screen saver.
- **After a system update** Google sometimes re-enables its launcher. Just run
  `debloat.sh` again.
- **To undo everything:** `./tools/restore.sh 192.168.1.50`
- **Updating Homebase:** install the new APK over the old one; favorites and
  hidden apps are kept. (Builds share one signing key, `app/homebase.keystore`,
  for exactly this reason. It's a convenience key — keep the repo private if
  that bothers you.)
