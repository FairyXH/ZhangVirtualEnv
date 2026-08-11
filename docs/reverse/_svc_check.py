import subprocess

def sh(*args):
    return subprocess.run(args, capture_output=True, text=True).stdout

serial = "3B6F6JE910B4WVXT"
print("== overlay permission ==")
print(sh("adb", "-s", serial, "shell", "appops get io.github.fairyxh.VirtualEnv SYSTEM_ALERT_WINDOW"))
print("== service state ==")
print(sh("adb", "-s", serial, "shell", "dumpsys activity services io.github.fairyxh.VirtualEnv | grep -E 'ServiceRecord|app=ProcessRecord' | head -10"))
print("== recent app logcat ==")
print(sh("adb", "-s", serial, "logcat", "-d", "-t", "200").splitlines()[-60:])
