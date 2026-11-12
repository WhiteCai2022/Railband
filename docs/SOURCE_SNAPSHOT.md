# 源码快照

- 发布日期：2026-11-12。
- Android：v0.7.3，versionCode 10；实际交付包名 `cn.railband.mvp.radio`。
- 原生基线：ESnet iperf commit `c9b74229d0d9bfec6d2307b66b43c29a7665ad0b`。本仓库保留该基线的构建输入和源码快照，没有导入全部上游提交历史。
- Android 与服务端功能源码从当前工作目录复制，未为这次代码上传更改测量行为。
- 2026-11-12 对部署目录中的 20 个程序、模板及静态文件进行了 SHA-256 对照，与发布前拷贝的 server 文件完全一致。Git 入库时将文本换行统一为 LF，方便 Linux 构建，程序内容未改动。部署所用 Python 包为 Flask 3.1.3、Gunicorn 25.1.0。
- 未复制服务器环境配置、数据库、用户测量、SSH 凭据或 Android 签名私钥。只包含公开依赖资源和模拟器/人工测试素材。
- 上游 `.github` 与 GitLab CI 配置未导入，避免本项目触发不适用的上游流程。
- 上游公开的 `src/private.pem` / `src/public.pem` 是认证测试素材，本快照没有携带密钥文件。默认 Android 构建使用 `--without-openssl`，不受影响；若要运行上游 OpenSSL 认证测试，请从官方上述 commit 获取相应公开测试素材。
- Windows 打包脚本仍含原开发环境默认路径；需要调整为自己的 SDK、已有 APK 和签名位置。Linux 构建与 instrumentation 的包名差异见根 README。
- 本地旧版历史文档在 android 中保留；当前使用说明以 docs/QUICK_START.md 和根 README 为准。

## 本次上传前的验证

- 服务端 `python -m unittest discover -s server -p 'test_*.py'`：21 项通过，包含上传鉴权、去重、下载、删除、分块与地图投影。
- 纯 Java `OptionsTest`、`TransferWatchdogTest`：均通过，包含参数、显示累计、字节停滞、方向、重复区间与低限速等检查。
- Windows 受限环境的默认临时目录导致第一轮服务端测试出现文件权限错误；将 TEMP/TMP 指向工作区并在正常权限下重跑后通过。
- 此次是现有代码的审查快照，没有重新完成全套 Android 原生构建、真机与长途实测。测试通过不代表已知测量问题已经解决。
