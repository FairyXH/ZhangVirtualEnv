# Android 17 Xiaomi Final Audit

> 日期：2026-08-18
> 审计类型：静态适配审计
> 目标材料：`D:\Files\Develop\Android\TIK-5-169-win\TI_Xiaomi17`

## Platform

- Android: 17 / API 37
- Vendor: Xiaomi
- ROM family: HyperOS / MIUI extension
- Build: `OS3.0.332.0.XPAMIXM`
- Vendor fingerprint: `Xiaomi/mivendor/mivendor:16/BQ2A.260225.001-BP2A.250705.008/OS3.0.332.0.XPAMIXM:user/release-keys`
- Architecture: arm64 / AArch64 native materials

## Java / LSPosed

- Common framework location classes are present and signatures were inspected with JADX.
- `android.sysprop.TelephonyProperties` is present under the Android 17 namespace.
- `RIL.getCellInfoList(Message, WorkSource)` and `RIL.getSignalStrength(Message)` are confirmed in `telephony-common.jar`.
- `requestCellInfoUpdate` is not present in the target `RIL` class and is not used as a required signature.
- Xiaomi sensor/location extension classes were inventoried but no vendor-only hook was added without a demonstrated need.
- WiFi and Bluetooth services are APEX oat/vdex-only in the extracted material; the existing runtime-discovery paths remain fail-open.

## Native

- `libsensor.so`: AArch64; exported `SensorEventQueue::write` at `.text+0x14e00`; prologue is `bti c`, `mov w3,#0x68`, branch helper.
- `libsensorservice.so`: AArch64; exported `SensorEventConnection::sendEvents` at `.text+0x95754`; prologue starts with `paciasp`.
- The assumed Oplus `sendEventsToAllClients` symbol is not present in the target Xiaomi binary. The old batch anchor therefore remains guarded and is not treated as Xiaomi-compatible.
- Native fallback resolution prefers `dlsym`, then checks Xiaomi17 and historical Oplus anchors against the complete prologue before patching.
- Failure of any symbol, mapping, prologue, branch range, `mprotect`, or stub allocation path returns an error and preserves the original behavior.

## Profile / code changes

- Added `app/src/main/assets/profiles/android17_xiaomi17.json`.
- Added API 37 profile selection in `VirtualEnvEntry`.
- Added Android 17 Xiaomi static selection before generic SDK wildcard fallback in `ProfileManager`.
- Updated RIL defensive documentation to reflect the target signatures.
- Added the Xiaomi17 native write anchor and prologue-gated fallback.
- Updated public README with the Android 17 Xiaomi static adaptation boundary.

## Verification

- Main module build: `BUILD SUCCESSFUL` with JDK 17.
- Detector build: `BUILD SUCCESSFUL` with JDK 17.
- `git diff --check`: passed for the task changes.
- Profile JSON parse: passed.
- Native CMake compilation: passed; only existing unused historical sendEvents helper warnings remain.
- adb: no devices listed.
- `DEVICE_VERIFIED`: NO.
- LSPosed load, logcat, SELinux mapping, native event delivery, and VirEnvDetector runtime checks: NOT RUN.

## Final disposition

The Xiaomi Android 17 static profile and native capability guard are complete. Runtime compatibility is intentionally not claimed until a matching rooted test device is connected and the module is loaded through LSPosed. No installation or reboot was performed.
