#!/usr/bin/env bash
# Undo debloat.sh: bring back the stock Google TV home screen and every removed app.
#   ./restore.sh 192.168.1.50

set -u
IP="${1:-}"
if [ -z "$IP" ]; then echo "Usage: $0 <onn-ip-address>"; exit 1; fi

adb connect "$IP:5555" >/dev/null
A="adb -s $IP:5555 shell"

for p in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith; do
  $A pm enable "$p" >/dev/null 2>&1 && echo "  enabled   $p"
done

for p in \
  com.google.android.tvrecommendations com.google.android.apps.tv.dreamx \
  com.google.android.leanbacklauncher.recommendations com.google.android.videos \
  com.google.android.play.games com.netflix.ninja com.amazon.amazonvideo.livingroom \
  com.disney.disneyplus com.hulu.livingroomplus com.wbd.stream \
  com.peacocktv.peacockandroid com.google.android.youtube.tvmusic; do
  $A cmd package install-existing "$p" 2>/dev/null | grep -q installed && echo "  restored  $p"
done

echo "Done. Press Home to get the stock screen back."
