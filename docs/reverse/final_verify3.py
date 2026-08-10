#!/usr/bin/env python3
"""完整验证：GNSS 12 参 addSatellite + sensor pending + LTE 合法值。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 90) -> str:
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


print("=== wait boot ===")
subprocess.run(["adb", "wait-for-device"], capture_output=True, timeout=180)
for _ in range(120):
    if devsh("getprop sys.boot_completed") == "1":
        print("BOOT_OK")
        break
    time.sleep(2)
time.sleep(5)

print("=== start detector + random simulate ===")
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

print("=== wait 30s ===")
time.sleep(30)
p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
print("=== detector verdicts (latest 6) ===")
verdicts = [l for l in out.splitlines() if "VirEnvDetector" in l and any(k in l for k in ["location:", "cell:", "ble:", "wifi:", "sensor:", "gnss:"])]
for line in verdicts[-6:]:
    print(line[:160])
print("=== gnss/sensor diag ===")
seen = set()
for line in out.splitlines():
    if "ZVirtualEnv" in line and any(k in line for k in ["gnss injector started", "build virtual gnss status failed", "sensor injector started", "sensor injector pending"]):
        key = line[:130]
        if key not in seen:
            seen.add(key)
            print(line[:220])
