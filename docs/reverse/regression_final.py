#!/usr/bin/env python3
"""最终回归：干净日志下六项判定。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


def devsh(cmd: str, timeout: int = 60) -> str:
    return sh("adb shell " + cmd, timeout)


def tap_center(xml: str, text: str):
    m = re.search(r'text="' + re.escape(text) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return False
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    devsh(f"input tap {x} {y}")
    print(f"tap {text} at {x},{y}")
    return True


sh("adb shell am force-stop io.github.fairyxh.VirEnvDetector")
time.sleep(1)
sh("adb logcat -c")
sh("adb shell am start -n io.github.fairyxh.VirEnvDetector/.MainActivity")
time.sleep(5)
for attempt in range(8):
    devsh("uiautomator dump /sdcard/d.xml")
    xml = devsh("cat /sdcard/d.xml")
    for perm_text in ["Allow", "While using the app", "仅在使用中允许", "允许", "Allow all the time", "始终允许"]:
        if tap_center(xml, perm_text):
            time.sleep(2)
            devsh("uiautomator dump /sdcard/d.xml")
            xml = devsh("cat /sdcard/d.xml")
    if tap_center(xml, "随机模拟"):
        print("random simulate tapped")
        break
    time.sleep(2)

time.sleep(25)
p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
verdicts = [l for l in out.splitlines() if "VirEnvDetector" in l and any(k in l for k in ["location:", "cell:", "ble:", "wifi:", "sensor:", "gnss:"])]
for line in verdicts[-6:]:
    print(line[:170])
