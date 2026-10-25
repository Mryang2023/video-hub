# -*- coding: utf-8 -*-
"""闭环导航:dump 焦点 -> 决定按键,直到触发搜索并等待结果"""
import re, subprocess, sys, time

ADB = r"C:/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe"

def sh(*args, timeout=20):
    return subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout).stdout

def dump():
    sh("shell", "rm -f /sdcard/ui.xml")
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return sh("shell", "cat", "/sdcard/ui.xml").decode("utf-8", "ignore")

def key(code):
    sh("shell", "input", "keyevent", str(code))
    time.sleep(1.6)

def focused(xml):
    for m in re.finditer(r"<node[^>]*focused=.true.[^>]*/?>", xml):
        n = m.group(0)
        rid = re.search(r"id/([a-zA-Z]+)", n)
        tx = re.search(r'text=.([^"]{0,12})', n)
        return (rid.group(1) if rid else "-"), (tx.group(1) if tx else "")
    return "?", ""

def wait_search_done(deadline=40):
    t0 = time.time()
    while time.time() - t0 < deadline:
        xml = dump()
        cards = xml.count("id/card")
        rid, tx = focused(xml)
        if cards > 0:
            print("RESULT: cards=%d focus=%s%r" % (cards, rid, tx))
            return True
        if "没有找到" in xml or "搜索失败" in xml:
            print("RESULT: empty/fail hint")
            return False
        time.sleep(2)
    print("RESULT: TIMEOUT still searching")
    return False

# 1) 到搜索页
for _ in range(10):
    xml = dump()
    if "searchInput" in xml:
        break
    rid, tx = focused(xml)
    if rid.startswith("tab"):
        tab = rid[3:]
        if tab != "Search":
            key(21 if tab in ("Set", "Hist", "Fav") else 22)
        else:
            key(20)  # DOWN 进内容
    else:
        key(19)  # UP 回标签栏
else:
    print("NAV FAIL: cannot reach search page"); sys.exit(1)

print("on search page")

# 2) 焦点到热词 chip
for _ in range(6):
    xml = dump()
    rid, tx = focused(xml)
    if rid == "chip" and tx:
        break
    key(20)
else:
    print("NAV FAIL: no chip focus"); sys.exit(1)

print("chip focused:", tx)

# 3) RIGHT×2 换到第三个热词（未搜过的冷词）
key(22); key(22)

# 4) OK 触发搜索
key(23)
ok = wait_search_done()
print("SEARCH_OK" if ok else "SEARCH_BAD")
