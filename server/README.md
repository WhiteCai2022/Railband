# 轨途测速服务器数据管理

2026-10-14 已加入测量详情与轨迹地图。普通测量和行程列表均可点击“查看详情与轨迹”，显示基本信息、上下行统计以及按有效定位序列绘制的地图。网页已上线，手机无需更新。使用与数据规则见 [网页地图说明](../docs/WEB_MAP.md)。

v0.7 已增加行程模式：独立采集、自动重连及分块补传。行程页面为 `https://62.234.140.204/journeys`，新接口为 `/api/journeys/chunks`（每块最多 2 MiB）。完整用法和格式见 [行程模式说明](../docs/JOURNEY_MODE.md)。原普通测量接口继续兼容。

服务器：`62.234.140.204`。网页：`https://62.234.140.204/`。

APP 先保存本地测量，测量结束后由 Android 后台任务上传单次 JSONL。网络错误按系统退避策略重试；密钥无效、格式冲突或超出大小限制时显示错误，修改设置后可手动重新同步。已同步的本地记录不会因为网页删除而再次自动上传。

管理网页需要独立密码，支持分页查看、单次 JSONL 下载和确认删除。下载保留原始文件的全部字段与字节，包括场景标签、地理位置、NR/LTE 指标、网络类型和原始 iperf 输出。网页删除仅影响服务器副本。

## 部署位置

- `/opt/railband`：Flask 程序，由 Gunicorn 监听本机 `127.0.0.1:8765`。
- `/var/lib/railband/archive.sqlite3`：SQLite 数据库。`measurements` 表保存完整 JSONL BLOB，以及设备、手机测量编号、路线、标签、时间、采样条数、内容 SHA-256 等索引。以设备 UUID 和手机测量编号防止重试生成重复记录。
- `/etc/railband/railband.env`：会话密钥、管理密码哈希、上传密钥哈希，权限为 root `600`。
- `/etc/nginx/sites-available/railband`：公网 HTTPS 反向代理。
- `/etc/letsencrypt/live/62.234.140.204`：IP 证书。
- `railband-web.service`：数据服务，已开机自启。
- `railband-certbot-renew.timer`：每天两次检查续期，续期成功后重新加载 nginx。
- `rail-iperf.service`：原有 TCP 5201 测速服务。

需保留公网 TCP 80（证书验证）、443（网页与上传）、5201（测速）。数据接口只接受 HTTPS 上传密钥；管理密码不能用于上传。一次上传最多 128 MiB、250000 行，超出限制时手机本地记录仍可导出。

## APP 接入

在 APP 的“服务器数据同步”中填写 `https://62.234.140.204/api/upload` 与上传密钥，点击“保存设置并上传待同步记录”。这也会同步手机内尚未上传的历史记录。密钥保存在 APP 私有设置中，没有写进 APK。

新版包名为 `cn.railband.mvp.radio`，可覆盖安装此前的 v0.5 版本，保留其数据。旧版 v0.4 应用的本地数据库属于另一个包；先在旧应用导出保存。

## 检查与维护

检查服务：`systemctl status railband-web nginx railband-certbot-renew.timer`。

续期演练：`sudo /opt/railband-certbot/bin/certbot renew --dry-run --cert-name 62.234.140.204`。

备份数据库请使用 SQLite 的在线备份接口，或停止数据服务后复制数据库与其 WAL 文件；不要在写入时只复制主数据库文件。管理密码和上传密钥的原文只在本机交付文件里，服务器只保存其哈希。

## 验证

`test_app.py` 覆盖未登录访问、无效上传密钥、重复上传、混合测量拒绝、下载一致性、删除确认和 CSRF 校验。`smoke_remote.py <credentials.txt> 0` 使用公网 HTTPS 创建并清理一份模拟器测试记录，验证部署后的完整流程；第二个参数为 SSH 隧道端口时可通过隧道测试。
