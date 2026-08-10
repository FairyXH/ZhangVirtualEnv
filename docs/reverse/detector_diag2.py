#!/usr/bin/env python3
"""进一步诊断：完整 UI dump + logcat 原始。"""
import subprocess


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== raw uiautomator output ===")
print(sh("adb shell uiautomator dump /sdcard/det3.xml 2>&1"))
print(sh("adb shell ls -la /sdcard/det3.xml 2>&1"))
print(sh("adb shell cat /sdcard/det3.xml 2>&1")[:4000])
print("=== dumpsys window focus ===")
print(sh("adb shell dumpsys window windows 2>&1 | grep -E 'mCurrentFocus|mFocusedApp' | head -5"))
print("=== logcat VirEnvDetector raw ===")
print(sh("adb logcat -d -s VirEnvDetector:* 2>&1 | tail -50"))
print("=== logcat ZVirtualEnv raw ===")
print(sh("adb logcat -d -s ZVirtualEnv:* 2>&1 | tail -50"))
