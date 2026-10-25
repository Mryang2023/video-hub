#!/bin/bash
# TV 端遥控路径回归：详情焦点 / 播放器 OSD 焦点链 / 选集返回
ADB="C:/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe"

foc() {
  "$ADB" shell "rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1
  "$ADB" shell "cat /sdcard/ui.xml" | python -c "
import sys, re
xml = sys.stdin.read()
best = ''
for m in re.finditer(r'<node[^>]*focused=.true.[^>]*/?>', xml):
    n = m.group(0)
    rid = re.search(r'id/([a-zA-Z]+)', n)
    tx = re.search(r'text=.([^\"]{0,10})', n)
    best = (rid.group(1) if rid else '-') + ' ' + (repr(tx.group(1)) if tx else '')
print('   FOCUS:', best or '(none)')"
}

"$ADB" shell am force-stop com.videohub.tv
"$ADB" shell am start -n com.videohub.tv/.MainActivity >/dev/null 2>&1
sleep 10

"$ADB" shell input keyevent 20; "$ADB" shell input keyevent 20; "$ADB" shell input keyevent 20; sleep 1
"$ADB" shell input keyevent 21; sleep 1
"$ADB" shell input keyevent 23; sleep 3
echo "== 详情页 =="; foc

"$ADB" shell input keyevent 23; sleep 13
echo "== 播放器: OK 唤 OSD =="; "$ADB" shell input keyevent 23; sleep 0.4; foc
echo "== DOWN ==";    "$ADB" shell input keyevent 20; sleep 0.3; foc
echo "== RIGHT ==";   "$ADB" shell input keyevent 22; sleep 0.3; foc
echo "== RIGHT ==";   "$ADB" shell input keyevent 22; sleep 0.3; foc
echo "== RIGHT(选集) =="; "$ADB" shell input keyevent 22; sleep 0.3; foc
echo "== CENTER(回详情) =="; "$ADB" shell input keyevent 23; sleep 1.5
"$ADB" shell "rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1
echo "   eGrid present: $("$ADB" shell "cat /sdcard/ui.xml" | grep -c 'id/eGrid')"
