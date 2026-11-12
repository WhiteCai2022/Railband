# Railband / 轨途测速

面向城轨、高铁移动场景的 Android TCP 测量工具，以及数据上传、下载和轨迹查看服务端。

**当前提交是供排查问题的 v0.7.3 源码快照，包含已知数据质量问题。** 请先看 [已知问题与复现入口](docs/KNOWN_ISSUES.md)，不要将发送端的一秒零值直接视为无线断网。

## 从哪里开始审查

| 内容 | 路径 |
| --- | --- |
| 行程循环、子进程、重连与区间接收 | [JourneyRun.java](android/app/src/main/java/cn/railband/mvp/JourneyRun.java) |
| 字节停滞监控 | [TransferWatchdog.java](android/app/src/main/java/cn/railband/mvp/TransferWatchdog.java) |
| 本地 SQLite、时间与位置关联 | [Store.java](android/app/src/main/java/cn/railband/mvp/Store.java) |
| 上传调度与分块 | [UploadJob.java](android/app/src/main/java/cn/railband/mvp/UploadJob.java)、[JourneyChunks.java](android/app/src/main/java/cn/railband/mvp/JourneyChunks.java) |
| 原生发送与统计计数 | [iperf_tcp.c](src/iperf_tcp.c)、[iperf_api.c](src/iperf_api.c)、[net.c](src/net.c) |
| 服务端上传、归档与行程接口 | [app.py](server/app.py)、[journeys.py](server/journeys.py) |
| 地图与统计投影 | [detail_data.py](server/detail_data.py)、[detail.js](server/static/detail.js) |

## 功能与结构

- Android：上行、下行、双向测量；可调统计间隔、限速和时长；场景标签；位置、NR/LTE 信号；行程自动重连；SQLite 保存、JSONL 导出、HTTPS 分块补传。
- 服务端：Flask + Gunicorn + SQLite，支持登录、下载、删除、行程分块去重及地图查看；Nginx 和 systemd 示例在 `server/deploy/`。
- `src/`、`configure` 等为 ESnet iperf 基线源码。来源和构建差异见 [源码快照说明](docs/SOURCE_SNAPSHOT.md)。
- 本仓库不含真实行程文件、数据库、登录密码、上传密钥、签名私钥和本机构建产物。`server/fixtures/` 仅包含模拟器或人工构造的测试素材。

## 运行无需真实服务器的检查

Python 3.12+：

```sh
python -m venv .venv
# Linux/macOS: source .venv/bin/activate
# Windows: .venv\Scripts\Activate.ps1
python -m pip install -r server/requirements.txt
python -m unittest discover -s server -p 'test_*.py'
```

这些测试使用临时 SQLite 和测试客户端，不访问生产服务器。

纯 Java 检查（JDK 17+，不需要 Android SDK）：

```sh
mkdir -p build/review-unit
javac -encoding UTF-8 -d build/review-unit \
  android/app/src/main/java/cn/railband/mvp/TestOptions.java \
  android/app/src/main/java/cn/railband/mvp/LiveRates.java \
  android/app/src/main/java/cn/railband/mvp/TransferWatchdog.java \
  android/scripts/OptionsTest.java \
  android/scripts/TransferWatchdogTest.java
java -cp build/review-unit OptionsTest
java -cp build/review-unit cn.railband.mvp.TransferWatchdogTest
```

## Android 构建与模拟器测试

原生引擎完整构建脚本是 `android/scripts/build.sh`：Linux、JDK 17、Android platform 36、build-tools 36.0.0、NDK 28.2.13676358、make、zip。设置 `ANDROID_SDK_ROOT` 后执行：

```sh
bash android/scripts/build.sh
```

`android/scripts/build-local-parallel.ps1` 是当前 Windows 打包路径，复用已有 APK 中的原生引擎，需传入自己的 SDK、已有 APK 和输出路径；脚本内开发签名路径也应改成自己的。原始开发签名没有发布，因此自行构建的包不能保证覆盖安装原作者 APK。

两个历史构建入口的 applicationId 不一致：Linux 脚本使用清单中的 `cn.railband.mvp`，Windows 脚本会重写为实际交付的 `cn.railband.mvp.radio`。当前 instrumentation 脚本针对后者；使用 Linux 构建时，需同步修改测试清单的 targetPackage。Java 功能源码相同。

`StallRecoveryTest.java` 使用模拟器上的本地 TCP 代理与原生 iperf，复现数据 socket 失败、控制 socket 仍存活的持续零速，并验证重连。`UploadSchedulingTest.java` 验证上传调度与连续分块。其他标记 `remote` 的脚本或带公网默认端点的测试可能访问服务器，运行前须改成自己的测试环境；不要用它们操作研究数据。

## 服务端部署

依赖版本与当前服务端一致，见 `server/requirements.txt`。配置项：`RAILBAND_SECRET_KEY`、`RAILBAND_ADMIN_HASH`、`RAILBAND_UPLOAD_HASH`、`RAILBAND_DB`。用 `server/make_credentials.py` 在仓库之外生成自己的配置，不要提交生成的文件。

Linux 启动入口：

```sh
gunicorn --chdir server --bind 127.0.0.1:8765 --workers 2 --timeout 180 'app:create_app()'
```

配置、TLS、数据库目录与 iperf 服务说明见 [server/README.md](server/README.md)。代码中的公网地址是历史部署默认值，部署和复现应填写自己的地址。

## 使用和数据含义

- [使用说明](docs/QUICK_START.md)
- [JSONL 数据字典](docs/JSONL_DATA_DICTIONARY.md)
- [行程模式](docs/JOURNEY_MODE.md)
- [上传调度修复](docs/UPLOAD-v0.7.2.md)
- [持续零速恢复](docs/TUNNEL-v0.7.3.md)

上行实时值是手机发送端计数；下行是手机接收端计数。缺测不补零，沿用的位置和信号保留原始时间与年龄。不要只删除零值后重新计算平均数，也不要用 GPS 丢失自动推断隧道。

上游 iperf 的说明保留在 [README.iperf.md](README.iperf.md)，许可证与第三方资源的原许可证一并保留。
