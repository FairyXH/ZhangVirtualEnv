#!/usr/bin/env python3
"""检查 APK 内资源字符串是否包含新文案。"""
import zipfile

LOCAL = r"D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv\app\build\outputs\apk\debug\app-debug.apk"
NEW_TITLE = "环境实时测试"
OLD_TITLE = "环境实时测试（普通 App 视角）"
PASS_STR = "未启用模拟"

with zipfile.ZipFile(LOCAL) as z:
    names = z.namelist()
    print("files:", [n for n in names if n.endswith((".dex", ".arsc"))])
    for n in names:
        if n.endswith(".dex"):
            data = z.read(n)
            print(f"{n}: new title in dex:", NEW_TITLE.encode("utf-8") in data)
            print(f"{n}: old title in dex:", OLD_TITLE.encode("utf-8") in data)
            print(f"{n}: pass str in dex:", PASS_STR.encode("utf-8") in data)
    arsc = z.read("resources.arsc")
    print("arsc new title:", NEW_TITLE.encode("utf-8") in arsc)
    print("arsc old title:", OLD_TITLE.encode("utf-8") in arsc)
    print("arsc pass str:", PASS_STR.encode("utf-8") in arsc)
