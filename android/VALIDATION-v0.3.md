# 0.3 验证记录（2026-09-22）

最终产物：`D:\NSFC\output\apk\rail-bandwidth-v0.3.0.apk`。

SHA256：`d886f96e77f9e12bff64c9ea08d54c565f3d2b08f13f0fd25bd45f671cea34af`。

## 已执行

- Linux Android SDK36 / NDK28.2 构建成功，APK签名和16KB对齐验证通过。包含arm64-v8a及x86_64。
- 与0.2签名证书SHA256一致：`87bc9837ec0e1463fb6987090aca22c03fe045116bda2399635892f8f4a88f34`。
- OptionsTest通过：参数、双向与反向互斥、持续模式、位置时效、双向独立统计、按实际时长加权平均、真实零值、缺失值、曲线缓存边界及清空。
- 使用首轮手机导出的3次会话、25条样本的内存副本验证v1→v3升级，所有原记录保持，旧位置仍为空，方向正确迁移；坐标导出关联正确。没有修改原JSONL。
- Windows WHPX硬件加速可用，RailBand_API_36 Android16模拟器启动成功。覆盖安装0.3前备份模拟器应用数据，没有卸载或清空应用。
- V3SmokeTest在最终APK上通过：连接东京真实iperf3，双向每方向1 Mbps、5秒，生成5条上行、5条下行；曲线数据、两个方向的最新值和平均值、最后一次会话导出均正常。
- 首轮升级实测保留1次原有会话；最终图形调整后复测保留之前2次会话。测试新增会话标记为「模拟器0.3自动验证」，不当作真实列车测量。
- 删除最后一次、删除全部、测量运行期间拒绝删除及无孤立关联记录，在独立临时数据库验证通过。没有删除主数据库日志。
- 最终界面截图人工检查：上行绿色实线、下行蓝色虚线，重合时仍可辨别；平均及上一秒值、结束汇总、进度显示正常。

## 可检查的本地产物

- `D:\NSFC\output\validation-v03\instrumentation-final.txt`：最终模拟器测试结果，INSTRUMENTATION_CODE=-1为Android RESULT_OK。
- `D:\NSFC\output\validation-v03\v03-screen.png`：最终截图。
- `D:\NSFC\output\validation-v03\emulator-app-before-update.tar`：升级前模拟器数据备份。

## 验证范围

本次未完成小米14上的0.3真机复测，真实GPS、蜂窝切换和高铁场景仍需现场验证。测试期间定位关闭，导出位置与删除关联通过临时数据库样本验证。系统文件选择器的实际分享目的地需用户选择。本版仍为本地SQLite保存和JSONL导出，未新增云端数据库上传。
