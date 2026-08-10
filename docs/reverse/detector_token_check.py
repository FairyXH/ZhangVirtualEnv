#!/usr/bin/env python3
"""查找检测器 onCreate token 日志 + 进程启动时间。"""
import subprocess


def sh(cmd: str, timeout: int = 60) -> str:
    p = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", errors="replace").strip()


print("=== detector started log ===")
print(sh("adb logcat -d 2>&1 | grep -E 'VirEnvDetector started|random env applied|随机环境|随机模拟失败' | tail -10"))
print("=== detector process start ===")
print(sh("adb shell ps -A -o PID,PPID,NAME,START 2>&1 | grep VirEnvDetector"))
print("=== detector apk install time ===")
print(sh("adb shell dumpsys package io.github.fairyxh.VirEnvDetector 2>&1 | grep -E 'firstInstallTime|lastUpdateTime|versionName' | head -5"))
