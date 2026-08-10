#!/usr/bin/env python3
"""精确诊断：清空 logcat → 重启 → tap → 8s 内抓 14695 gnss 日志。"""
import subprocess
import time
import re


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


def devsh(cmd: str, timeout: int = 60) -> str:
    return sh("adb shell " + cmd, timeout)


print("=== clear + restart ===")
sh("adb shell am force-stop io.github.fairyxh.VirEnvDetector")
time.sleep(1)
sh("adb logcat -c")
sh("adb shell am start -n io.github.fairyxh.VirEnvDetector/.MainActivity")
time.sleep(4)

for attempt in range(8):
    devsh("uiautomator dump /sdcard/d.xml")
    xml = devsh("cat /sdcard/d.xml")
    for perm_text in ["Allow", "While using the app", "仅在使用中允许", "允许", "Allow all the time", "始终允许"]:
        m = re.search(r'text="' + re.escape(perm_text) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if m:
            x = (int(m.group(1)) + int(m.group(3))) // 2
            y = (int(m.group(2)) + int(m.group(4))) // 2
            devsh(f"input tap {x} {y}")
            print(f"tap perm {perm_text} at {x},{y}")
            time.sleep(2)
            devsh("uiautomator dump /sdcard/d.xml")
            xml = devsh("cat /sdcard/d.xml")
    m = re.search(r'text="随机模拟"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if m:
        x = (int(m.group(1)) + int(m.group(3))) // 2
        y = (int(m.group(2)) + int(m.group(4))) // 2
        devsh(f"input tap {x} {y}")
        print(f"tap random at {x},{y}")
        break
    time.sleep(2)

time.sleep(8)
p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
lines = out.splitlines()
rows = []
for line in lines:
    parts = line.split()
    if len(parts) < 3:
        continue
    pid = parts[2]
    if pid == "14695" or ("ZVirtualEnv" in line and any(k in line for k in ["gnss", "Gnss", "hooked LocationManager", "findCallbackArg", "framework env hooks", "onPackageReady", "onModuleLoaded"])):
        rows.append(line)
for line in rows[:60]:
    print(line[:240])
print(f"---total {len(rows)}---")
