# MVP 0.1 验证记录

## 后续补充：用户首轮真机导出

用户提供 `D:/NSFC/test/railband-1790068581992.jsonl`：Xiaomi / Android16成功完成3次上行尝试，25条采样均与原始interval一致，SQLite持久保存与JSONL导出得到真机证据。2次状态cancelled、1次completed；取消是否主动操作待确认。第三次发送端2.9345 Mbps、接收端0.5266 Mbps，末尾零吞吐且最后区间2.7897秒，需要排查实际网络环境。全部标签未标注，尚未验证标签切换或下载。详细核查见 `D:/NSFC/test/首轮真机数据核查.md`。以下为交付APK时的原始验证记录，真机未验证的旧描述由本补充更新。

日期：2026-09-22。

## 已通过

- Android API36 编译；NDK28.2 编译 arm64-v8a / x86_64 两种原生 PIE 引擎。
- SDK apksigner 验证开发签名通过（v3，最低API28）；zipalign 检查通过。
- arm64 ELF 的 LOAD 段均为 0x4000（16 KB）对齐；仅依赖 Android 系统 libc/libm/libdl，无外部 libiperf.so 依赖。
- APK 权限、Activity、前台服务声明和目标SDK经 aapt2 检查。
- Linux iperf3 基线回环：TCP上传/下载各3秒、每秒JSON输出，两个方向均有3条interval；发送/接收端字段核对。
- 正式服务 `rail-iperf.service` active/enabled，TCP5201；从本地电脑到公网端口连通。
- 服务端正常5 Mbps短测通过；61秒请求被拒绝；9 Mbps请求被拒绝。应用上限5 Mbps、60秒。
- 旧网站服务保持inactive；SSH、Codex和既有MySQL未停用。
- APK复制回本地后的SHA-256与服务器一致：`5d080970febdb83239eb3045fe85a5ce73ad4b21b408524e1ded7bc64db0e0e5`。

## 尚未通过运行验收

- 云端无KVM，Android16软件模拟器首次启动触发系统Watchdog重启，未成功完成APK安装。因此**不将模拟器启动尝试当作APP运行测试通过**。
- 已编译 `scripts/SmokeTest.java` 对应的集成测试APK，但尚未实际运行通过。用例覆盖真实上下行、SQLite逐区间记录、标签切换、取消、连接拒绝及JSONL导出。
- 本地已有Android模拟器、CPU虚拟化已开，但未安装可用加速组件。用户选择不改Windows系统，改用小米真机测试。没有启用Windows功能或安装驱动。
- 本地ADB当前未发现已连接设备。Xiaomi14/Android16安装启动、锁屏、断网、标签及导出仍待真机验收。

云端模拟器和仅限127.0.0.1:5202的临时测试服务已停止，手机公网5201服务保留。

## 下一次真机测试

先安装 `output/apk/rail-bandwidth-mvp.apk`；手机开启USB调试并在手机上授权电脑后，可直接使用本地 `D:/Android/SDK/platform-tools/adb.exe` 安装更新和读取应用日志。普通安装使用不要求USB调试。

首轮依次验证：10秒上行、10秒下行、途中切换隧道标签、提前停止、关闭网络、恢复后重测、导出记录。不得将固定5 Mbps原型的结果标成链路最大容量；不得把接收时标签当作整个区间的精确标签。
