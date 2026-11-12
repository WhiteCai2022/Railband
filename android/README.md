# 轨途测速 0.4（历史版本）

当前 0.5 的无线信号及网络类型说明见 [README-v0.5.md](README-v0.5.md)。以下是 0.4 的历史说明。

安装新版 `rail-bandwidth-v0.4.0.apk` 可覆盖0.1/0.2/0.3，使用同一开发签名，保留旧记录。不要先卸载旧版；首次升级前可以先导出备份。

## 功能

- TCP上行、下行或双向同时测量（默认双向），原生iperf3真实测速。双向通过 `--bidir` 同时运行两个方向，所选限速分别作用于每个方向，总流量估算包含双向。
- 采样间隔可选0.1/0.5/1/2/5/10/30/60秒或自定义0.1–60秒，默认1秒。直接设置iperf `-i`，每个实际区间保存一组记录，双向各一条。结束时可能产生不足一个间隔的尾段；保留真实起止时间和seconds，不将尾段冒充完整区间。
- 顶部展示最近60秒曲线：绿色实线上行、蓝色虚线下行；两个方向分别显示上一完整采样区间的速率和本次平均值。平均值按实际采样字节数除以累计采样时长计算；缺失数据留空，不补零。
- 「导出最后一次日志」导出最新建立的一次会话及其标签、位置、采样和原始事件，包含取消或失败的会话；「导出全部日志」仍保留。「删除日志」可选择最后一次或全部，二次确认后删除本地数据库对应记录，不删除已经导出的文件。测量期间不允许删除。
- 速率：5/10/20/50/100 Mbps预设、自定义Mbps（支持小数）、不限速。默认仍为5 Mbps。
- 时长：10/30/60秒、5/10分钟、自定义1–86400秒、持续测量。iperf的0表示持续到停止；应用不为该模式设置固定时长截止。
- 有限时长显示进度，收到完整结束事件后才显示100%；持续模式显示运行时长和动态进度。超过设定时间但尚未收尾时明确显示正在收尾。
- 最新interval刷新速率，标题为「最近一次」。无新采样提示阈值随所选间隔调整，长间隔期间不因正常等待而隐藏上一条速度；真实零速率仍显示0，不补缺失区间。结束后显示发送端与接收端平均值。
- 场景标签支持过程中切换，保留独立事件时间线。
- 测量期间独立请求手机GPS/网络定位，约1秒一次；实际频率由设备决定。每条样本都关联本次最近一次有效位置，无10秒过期截止。新定位未到时沿用旧坐标，保留原始位置时间和不断增长的location_age_ms。界面显示坐标、精度、来源及年龄，超过10秒显示「沿用最近位置」。本次尚未定位、未授权或未启用时坐标仍为空。
- SQLite本地保存、JSONL导出。没有云端上传，也不采集基站。

## 安装后的首测

打开APP，首次定位授权选择「使用期间允许」和「精确位置」，同时开启手机系统定位。推荐在窗边/室外先用5 Mbps、30秒验证，过程中切换标签。位置权限被拒绝或定位关闭时测速仍可运行，缺失坐标明确留空。

使用「不限速」前关闭无关下载，明确是否启用了VPN/代理。用户选择不限速或持续测量会增加流量消耗；界面给出限速模式下的流量估算，不限速不能提前准确估算。东京端点服务器上行20/下行100 Mbps及跨境路径仍会限制结果，不应将本工具结果直接等同无线接入链路容量。

Android系统仍可能因后台配额、省电策略、权限回收或网络错误结束测量。当前使用dataSync+location前台服务；Android15+的dataSync后台运行有24小时内累计6小时配额，持续模式不能绕过此限制。APP不会自动重连、自动启动新一轮或在重启后采集位置。无引擎输出的超时阈值为max(30秒, 采样间隔+15秒)，每10秒检查；TCP收发等待超时15秒。

参考：[Android前台服务时限](https://developer.android.com/develop/background-work/services/fgs/timeout)、[定位权限](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime)。

## 数据格式 v3

UTF-8 JSONL，每行一个对象，type为metadata、sessions、labels、locations、location_events、samples或events。

- sessions.config：duration_s=0表示持续测量，rate_cap_bps=0表示不限速；sample_interval_s记录本次所选采样间隔（秒），location_association_policy记录沿用本次最近位置的规则；含方向、定位请求、设备、行程、版本等。0.3及以前的记录没有新配置字段，原采样间隔为1秒。
- locations：独立定位点，含fix_utc_ms、fix_elapsed_ms、received_utc_ms、received_elapsed_ms、provider、latitude、longitude、accuracy_m、altitude_m、speed_mps、bearing_deg、is_mock。缺失的可选属性为null。使用手机Location API坐标，不转换为地图厂商坐标。
- location_events：定位未授权、系统关闭、来源启停等状态事件；不伪造坐标。
- samples：保留原有真实区间起止秒、seconds、bytes、bits_per_second、sender、接收时间、标签和raw；包含location_id及location_age_ms。0.4起在采样接收时关联本次测量最新有效位置，不按年龄过期；不关联未来位置或其他会话位置。本次无有效位置则null。
- direction 为 phone_upload 或 phone_download，以手机视角定义。双向每个区间有两条样本，共用 seq，以 direction 区分；不能再仅用 session_id + seq 作为唯一键。上行实时统计来自手机发送端，下行来自手机接收端；结束事件保留双方汇总。
- metadata.export_session_id 为指定会话编号或 null（全部）；sessions.config 的 direction 可为 bidirectional，rate_cap_scope 为 per_direction。
- 导出samples行还直接附带latitude、longitude、location_accuracy_m、location_fix_utc_ms、location_fix_elapsed_ms、location_provider、location_is_mock，便于直接按序列分析；独立locations表保留完整轨迹。
- events：所有原始iperf事件，包括结束时双方汇总。发送端字节数不一定等同接收端成功收到的字节数，必须区分。

位置和标签都是接收时关联，**不代表整个测量区间的精确位置或场景**。原生iperf时钟和定位单调时钟尚未增加精确原生锚点。定位失锁不自动认定为隧道。重复使用旧位置时，samples引用同一个locations记录，不伪造新的定位时间或轨迹点。旧版缺失的位置升级后仍为null，不回填；因此导出全部时需结合会话app_version及实际location_age_ms理解关联规则。数据库/导出schema仍为3，本次无需修改既有表结构。

## 构建与验证

Linux：OpenJDK17、Android platform36、build-tools36.0.0、NDK28.2.13676358、make和zip。

```sh
export ANDROID_SDK_ROOT=/opt/rail-android/sdk
export RAIL_DEBUG_KEYSTORE=/opt/rail-android/rail-debug.jks
bash android/scripts/build.sh
```

产物android/build/rail-bandwidth-mvp.apk，包含arm64-v8a和x86_64。纯Java界面，直接使用SDK工具构建，不依赖Gradle。iperf PIE可执行文件作为libiperf_exec.so随APK解压到原生库目录，通过ProcessBuilder执行，不拼接shell。16 KB ELF对齐。未修改上游iperf测量算法，源码基线c9b74229d0d9bfec6d2307b66b43c29a7665ad0b。

构建签名验证之外，scripts/OptionsTest.java验证参数、进度、位置有效性、间隔相关超时和双向加权统计；scripts/test_migration.py使用旧版真实导出数据的内存副本验证无损升级和坐标导出关联。scripts/V4IntervalTest.java验证真实双向自定义间隔、导出和旧记录保留；scripts/V4LocationTest.java在独立临时数据库验证长期沿用位置及会话隔离。具体结果见VALIDATION-v0.4.md；模拟器不能替代真机GPS验证。

## 服务端

新的scripts/rail-iperf.service配置移除旧8 Mbps/60秒限制，保留非root身份、内存/进程限制、固定TCP5201及空闲检查。部署需要用户明确同意，是否已应用以VALIDATION-v0.2.md记录为准。公开端点仍无认证，只允许单客户端同时测试；容量由服务器和网络实际资源决定。
