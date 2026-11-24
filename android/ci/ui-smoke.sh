#!/usr/bin/env bash
# UI тест в емулатор: инсталира APK, пуска демо режим, прави скрийншоти и проверява за сривове.
# Скрийншотите се отпечатват в лога като base64 (между маркери), за да се четат и без artifacts.
set -u
APK="$1"
PKG=bg.drone.receiver
OUT=ui-out
mkdir -p "$OUT"

shot() { # shot <име>
  adb exec-out screencap -p > "$OUT/$1.png"
  python3 -c "from PIL import Image; im=Image.open('$OUT/$1.png').convert('RGB'); im.thumbnail((320,720)); im.save('$OUT/$1.jpg', quality=55)"
  echo "=====SCREENSHOT $1 BEGIN====="
  base64 -w 0 "$OUT/$1.jpg"; echo
  echo "=====SCREENSHOT $1 END====="
}

texts() { # texts <име>: видимите текстове от UI дървото
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1
  echo "----- UI текстове ($1) -----"
  python3 - "$OUT/$1.xml" <<'PY'
import sys, re
x = open(sys.argv[1], encoding='utf-8').read()
vals = [v for v in re.findall(r'(?:text|content-desc)="([^"]+)"', x) if v.strip()]
print(' | '.join(vals[:120]))
PY
}

tap_text() { # tap_text <текст>: натиска центъра на първия елемент с този текст
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/tap.xml" >/dev/null 2>&1
  python3 - "$OUT/tap.xml" "$1" <<'PY' | while read -r X Y; do adb shell input tap "$X" "$Y"; done
import sys, re
x = open(sys.argv[1], encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    if f'text="{sys.argv[2]}"' in n or f'content-desc="{sys.argv[2]}"' in n:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups()); print((x1+x2)//2, (y1+y2)//2); break
PY
}

adb install -r "$APK" || exit 1
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION BLUETOOTH_SCAN POST_NOTIFICATIONS; do
  adb shell pm grant $PKG android.permission.$p 2>/dev/null || true
done
adb shell cmd location set-location-enabled true 2>/dev/null || true
adb emu geo fix 23.3219 42.6977 600 || true   # София
adb logcat -c

adb shell am start -n $PKG/.MainActivity --ez autostart_demo true
sleep 8; adb emu geo fix 23.3219 42.6977 600 || true
sleep 50
shot map
texts map

adb shell am start -n $PKG/.MainActivity --es select "rid:DE:M0:00:00:00:00"
sleep 6
shot selected
texts selected

tap_text "Списък"; sleep 4
shot list
texts list

tap_text "Новини"; sleep 15
shot news
texts news

tap_text "Знания"; sleep 3
texts wiki
tap_text "Настройки"; sleep 3
texts settings

adb logcat -d > "$OUT/logcat.txt"
echo "----- logcat (DroneRadar / грешки) -----"
grep -E "AndroidRuntime|FATAL|maplibre|MapLibre|bg.drone" "$OUT/logcat.txt" | tail -60
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "СРИВ НА ПРИЛОЖЕНИЕТО"; grep -A40 "FATAL EXCEPTION" "$OUT/logcat.txt"; exit 1
fi
adb shell pidof $PKG >/dev/null || { echo "Приложението не върви"; exit 1; }
echo "UI SMOKE OK"
