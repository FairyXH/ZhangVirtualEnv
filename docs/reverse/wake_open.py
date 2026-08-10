#!/usr/bin/env python3
"""唤醒屏幕 → 解锁 → 启动 App → 设置页。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
    p = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


def dump():
    sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    return sh("cat /sdcard/ui.xml")


print("wake:", sh("input keyevent KEYCODE_WAKEUP"))
time.sleep(2)
print("swipe:", sh("input swipe 632 2000 632 800 300"))
time.sleep(2)
# 可能锁屏密码，检查
xml = dump()
print("has keyguard:", "锁屏" in xml or "密码" in xml or "PIN" in xml)

sh("am force-stop io.github.fairyxh.VirtualEnv")
time.sleep(1)
sh("am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(12)
xml = dump()
print("主页:", "主页" in xml)
m = re.search(r'text="设置"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    sh(f"input tap {x} {y}")
    time.sleep(5)
    xml = dump()
    print("设置页:", "环境实时测试" in xml)
else:
    print("no tab; 前台:", sh("dumpsys window | grep mCurrentFocus"))
    for mm in re.finditer(r'<node[^>]*text="([^"]{0,40})"', xml):
        if mm.group(1).strip():
            print("  ", mm.group(1))
