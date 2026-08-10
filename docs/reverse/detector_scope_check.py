#!/usr/bin/env python3
"""检查检测器进程是否加载了模块（LSPosed scope 是否生效）。"""
import subprocess
import re

p = subprocess.run(["adb", "logcat", "-d"], capture_output=True, timeout=120)
out = p.stdout.decode("utf-8", errors="replace")
lines = out.splitlines()
keys = [
    "framework env hooks installed",
    "onPackageReady",
    "onModuleLoaded",
    "sensor injector",
    "StepHook",
    "EnvCache",
    "Entry]",
    "VirEnvDetector",
]
seen = set()
for l in lines:
    for k in keys:
        if k in l:
            pid = l.split()[2] if len(l.split()) > 2 else "?"
            if l.strip() not in seen:
                seen.add(l.strip())
                print(l.strip())
            break
