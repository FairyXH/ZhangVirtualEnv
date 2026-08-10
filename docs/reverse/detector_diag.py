#!/usr/bin/env python3
"""诊断检测器状态：进程、UI、logcat。"""
import subprocess
import time


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== detector process ===")
print(sh("adb shell ps -A | grep VirEnvDetector"))
print("=== top activity ===")
print(sh("adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -5"))
print("=== UI dump ===")
print(sh("adb shell uiautomator dump /sdcard/det2.xml && adb shell cat /sdcard/det2.xml")[:3000])
print("=== logcat last 100 (any) ===")
print(sh("adb logcat -d -t 100 2>/dev/null | grep -iE 'VirEnvDetector|ZVirtualEnv|framework env' | tail -40"))
