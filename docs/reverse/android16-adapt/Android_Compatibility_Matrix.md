# Android Compatibility Matrix（最终封版）

> 生成时间：2026-08-16（Android 16 适配最终封版审计）
> 状态定义：`VERIFIED`=真机/实际运行确认；`STATIC_VERIFIED`=通过 Android 16 实际系统文件（JAR/APK/DEX）静态确认；`PARTIAL`=部分确认但材料缺失；`REQUIRES_DEVICE`=仅能真机确认；`NOT_ADAPTED`=未适配；`UNKNOWN`=无法确定

| 功能 | Android 15 / API 35 | Android 16 / API 36 | Android 17+ / API 37 | 验证等级说明 |
|---|---|---|---|---|
| 虚拟定位 | VERIFIED | STATIC_VERIFIED | NOT_ADAPTED | A16 LocationManagerService/GnssLocationProvider/LocationProviderManager 签名逐项一致 |
| GNSS | VERIFIED | STATIC_VERIFIED | NOT_ADAPTED | registerGnssStatus/Nmea/NavigationMessage/Measurements + GpsStatus.create 签名一致 |
| WiFi | VERIFIED | PARTIAL | NOT_ADAPTED | WifiServiceImpl 为 wifi APEX 动态发现；A16 实现类名需真机确认（REQUIRES_DEVICE 子项） |
| BLE | VERIFIED | STATIC_VERIFIED | NOT_ADAPTED | A16 落点 ScanController.startScan(4参) + ScannerMap$ScannerApp.mCallback 已静态确认；SDK<36 门控保证 A15 零变化 |
| 经典蓝牙 | VERIFIED | STATIC_VERIFIED | NOT_ADAPTED | AdapterService.startDiscovery / RemoteDevices.deviceFoundCallback 签名一致 |
| 蓝牙配对 | VERIFIED | STATIC_VERIFIED | NOT_ADAPTED | createBond/getBondState/removeBond 签名一致 |
| 基站 | VERIFIED | STATIC_VERIFIED | NOT_ADAPTED | PhoneInterfaceManager requestCellInfoUpdate/getAllCellInfo/getCellLocation/getNeighboringCellInfo 声明存在 |
| SIM 属性 | VERIFIED | STATIC_VERIFIED | NOT_ADAPTED | A16 TelephonyProperties 迁移到 android.sysprop（已适配双候选）；setter 签名 List<String> 一致；实际调用链 REQUIRES_DEVICE |
| SIM Binder | VERIFIED | PARTIAL | NOT_ADAPTED | A16 ITelephony 移除大部分 SIM 方法；IPhoneSubInfo 接口存在，实现类（PhoneSubInfoController）类体在 telephony-common.jar 缺失 |
| SIM Phone 对象 | VERIFIED | PARTIAL | NOT_ADAPTED | Phone 抽象类方法名确认；GsmCdmaPhone/Phone 类体在 telephony-common.jar 缺失 |
| SIM Subscription | VERIFIED | PARTIAL | NOT_ADAPTED | ISub 接口存在；SubscriptionManagerService/SubscriptionInfoInternal 类体缺失 |
| RIL | VERIFIED | PARTIAL | NOT_ADAPTED | RIL 类型引用存在，类体可能在 telephony-common.jar |
| TelephonyRegistry | VERIFIED | STATIC_VERIFIED | NOT_ADAPTED | notifyCellInfoForSubscriber(int, List<CellInfo>) 签名一致 |
| Oplus 服务启动 | VERIFIED | STATIC_VERIFIED | NOT_ADAPTED | IActiveServicesExt.interceptBringUpServices(4参) 签名一致；接口 default 是否被实现类覆盖 REQUIRES_DEVICE |
| FGS | VERIFIED | REQUIRES_DEVICE | NOT_ADAPTED | mAllowStart_*/mAllowWiu_* 字段保留；A16 新增 USE_NEW_BFSL_LOGIC/WIU 新逻辑是否读取旧字段仅真机可确认 |
| 传感器 | VERIFIED | STATIC_VERIFIED | NOT_ADAPTED | SensorManager registerListener/unregisterListener 存在 |
| Framework 环境 | VERIFIED | STATIC_VERIFIED | NOT_ADAPTED | TelephonyManager.getAllCellInfo / LocationManager Gnss / BluetoothLeScanner.startScan / WifiManager.getScanResults 存在 |

## 统计

| 状态 | 数量（Android 16 列） |
|---|---|
| STATIC_VERIFIED | 12 |
| PARTIAL | 4（WiFi 动态类、SIM Binder/Phone/Subscription、RIL） |
| REQUIRES_DEVICE | 1（FGS）+ 各 STATIC_VERIFIED 项的运行时子项 |

## 明确结论

- Android 15（API 35）：全部功能 VERIFIED（已有实际使用基础）。
- Android 16（API 36）：静态签名验证完成，编译通过；**无真机，运行级状态一律不标记 VERIFIED**。
- Android 17+（API 37）：NOT_ADAPTED；Profile 精确分段（android16 maxSdk=36）+ readSimProfileConfig 精确匹配（==36）保证**不误用 Android 16 配置**。
