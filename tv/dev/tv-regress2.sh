#!/bin/bash
# 快速连发版: 键序列一口气打完再 dump, 避免 5s OSD 计时器干扰
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

# 导航进详情并播放
"$ADB" shell input keyevent 20; "$ADB" shell input keyevent 20; "$ADB" shell input keyevent 20
sleep 1
"$ADB" shell input keyevent 21
sleep 1
"$ADB" shell input keyevent 23
sleep 3
"$ADB" shell input keyevent 23
sleep 13

# 一口气: OK(唤OSD) DOWN(按钮排) RIGHT RIGHT RIGHT(到选集)
"$ADB" shell input keyevent 23
sleep 0.15
"$ADB" shell input keyevent 20
sleep 0.15
"$ADB" shell input keyevent 22
sleep 0.15
"$ADB" shell input keyevent 22
sleep 0.15
"$ADB" shell input keyevent 22
sleep 0.3
echo "== 连发后焦点 (期望 btnEps 选集) =="; foc

# CENTER 应回详情选集
"$ADB" shell input keyevent 23
sleep 1.5
"$ADB" shell "rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1
echo "   eGrid present: $("$ADB" shell "cat /sdcard/ui.xml" | grep -c 'id/eGrid')"
