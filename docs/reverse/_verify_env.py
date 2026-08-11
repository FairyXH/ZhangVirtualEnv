import subprocess, re, time, urllib.request

def sh(*args):
    return subprocess.run(args, capture_output=True, text=True).stdout

serial = "3B6F6JE910B4WVXT"
token = open(r"D:\Files\Develop\Android\ZhangVirtualProject\ZhangVirtualEnv\app\src\main\assets\api_token.txt", encoding="utf-8").read().strip()

def dump():
    sh("adb", "-s", serial, "shell", "uiautomator dump /sdcard/ui.xml")
    return sh("adb", "-s", serial, "shell", "cat /sdcard/ui.xml")

def tap_text(xml, text):
    m = re.search(r'text="' + re.escape(text) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        return False
    cx = (int(m.group(1)) + int(m.group(3))) // 2
    cy = (int(m.group(2)) + int(m.group(4))) // 2
    sh("adb", "-s", serial, "shell", f"input tap {cx} {cy}")
    return True

# start app
sh("adb", "-s", serial, "shell", "am start -n io.github.fairyxh.VirtualEnv/.app.MainActivity")
time.sleep(5)

# random env via API
req = urllib.request.Request("http://127.0.0.1:18790/api/debug/random-env", method="POST",
                             headers={"X-ZVE-Token": token})
try:
    resp = urllib.request.urlopen(req, timeout=5).read().decode()
    print("random-env:", resp[:120])
except Exception as e:
    print("random-env failed:", e)

time.sleep(2)
# go to env tab
xml = dump()
print("home feature status present:", "位置" in xml and "已启用" in xml)
tap_text(xml, "环境")
time.sleep(2)
xml = dump()
texts = re.findall(r'text="([^"]+)"', xml)
print("env texts:", texts[:60])
