#!/bin/bash
# Emülatörde uygulamayı kurup açar, ekran görüntüsü ve günlük toplar.
set -x
P=com.hakstudio.yazkonustur.debug
mkdir -p smoke
adb install -r out/yaz-konustur-deneme.apk
adb logcat -c
adb shell am start -n $P/com.hakstudio.yazkonustur.MainActivity
sleep 45
adb exec-out screencap -p > smoke/1-acilis.png
# Paylaş ile metin gönder ve okumayı başlat
adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'Bu metin paylaş menüsünden geldi. Yapay zekâ sesi cihazda okuyor.'" -n $P/com.hakstudio.yazkonustur.MainActivity
sleep 25
adb exec-out screencap -p > smoke/2-paylasim.png
adb shell dumpsys notification --noredact | grep -A3 "yazkonustur" | head -40 > smoke/bildirim.txt
adb shell input keyevent KEYCODE_HOME
sleep 10
adb shell dumpsys activity services $P > smoke/servis.txt
adb logcat -d > smoke/logcat-tam.txt
grep -E "chromium|Console|AndroidRuntime|YazKonustur|FATAL|yazkonustur" smoke/logcat-tam.txt | tail -300 > smoke/logcat.txt
adb shell pidof $P > smoke/pid.txt || echo "uygulama kapanmış" > smoke/pid.txt
exit 0
