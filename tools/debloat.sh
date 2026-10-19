#!/usr/bin/env bash
# Make Homebase the home screen on an onn (Google TV) box and strip the ad/promo apps.
#
#   ./debloat.sh 192.168.1.50            # switch launcher + remove ads/promos
#   ./debloat.sh 192.168.1.50 --streaming  # ...also remove preinstalled streaming apps
#   ./debloat.sh 192.168.1.50 --list       # just show what's installed, change nothing
#
# Everything here is reversible with ./restore.sh — nothing is deleted from the
# system image; apps are disabled or removed for your user only.

set -u
IP="${1:-}"
MODE="${2:-}"
HOMEBASE="com.taajaveli.homebase"

if [ -z "$IP" ]; then
  echo "Usage: $0 <onn-ip-address> [--streaming|--list]"; exit 1
fi

adb connect "$IP:5555" >/dev/null
if ! adb -s "$IP:5555" get-state >/dev/null 2>&1; then
  echo "Can't reach the box. Accept the 'Allow USB debugging?' prompt on the TV, then run again."
  exit 1
fi
A="adb -s $IP:5555 shell"

INSTALLED="$($A pm list packages | sed 's/^package://' | tr -d '\r')"
has() { echo "$INSTALLED" | grep -qx "$1"; }

if [ "$MODE" = "--list" ]; then
  echo "== All packages =="; echo "$INSTALLED" | sort
  echo; echo "== Likely onn / Walmart extras =="
  echo "$INSTALLED" | grep -iE 'onn|walmart|vudu|tivo|sling|pluto|tubi|freevee' || echo "(none matched)"
  exit 0
fi

# Safety: never take away the stock home screen unless Homebase is installed.
if ! has "$HOMEBASE"; then
  echo "Homebase isn't installed yet. Install the APK first, then run this again."
  exit 1
fi

# 1) Stock Google TV home + its setup shim (the shim grabs Home if left on).
LAUNCHER=(
  com.google.android.apps.tv.launcherx
  com.google.android.tungsten.setupwraith
)

# 2) Ads, promos and recommendation feeds.
ADS=(
  com.google.android.tvrecommendations
  com.google.android.apps.tv.dreamx          # ambient-mode screensaver with promos
  com.google.android.leanbacklauncher.recommendations
  com.google.android.videos                  # Google TV / Play Movies storefront
  com.google.android.play.games
)

# 3) Preinstalled streaming apps (only with --streaming). Reinstall any from the Play Store.
STREAMING=(
  com.netflix.ninja
  com.amazon.amazonvideo.livingroom
  com.disney.disneyplus
  com.hulu.livingroomplus
  com.wbd.stream
  com.peacocktv.peacockandroid
  com.google.android.youtube.tvmusic
)

echo "== Turning on Continue Watching =="
if $A pm grant "$HOMEBASE" android.permission.READ_TV_LISTINGS 2>/dev/null; then
  echo "  granted   Continue Watching access"
else
  echo "  (this box wouldn't grant READ_TV_LISTINGS; Continue Watching may stay off)"
fi
$A pm grant "$HOMEBASE" com.android.providers.tv.permission.READ_EPG_DATA >/dev/null 2>&1 || true

echo "== Switching home screen to Homebase =="
for p in "${LAUNCHER[@]}"; do
  if has "$p"; then $A pm disable-user --user 0 "$p" >/dev/null && echo "  disabled  $p"; fi
done

echo "== Removing ads / promos =="
for p in "${ADS[@]}"; do
  if has "$p"; then $A pm uninstall -k --user 0 "$p" >/dev/null && echo "  removed   $p"; fi
done

if [ "$MODE" = "--streaming" ]; then
  echo "== Removing preinstalled streaming apps =="
  for p in "${STREAMING[@]}"; do
    if has "$p"; then $A pm uninstall -k --user 0 "$p" >/dev/null && echo "  removed   $p"; fi
  done
fi

echo
echo "Done. Press Home on the remote — you should land on Homebase."
echo "To see anything else onn installed:  $0 $IP --list"
