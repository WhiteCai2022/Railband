# 轨途测速 0.5：蜂窝信号和测速网络

0.5 在 0.4 的原生 iperf3、可调采样间隔、位置及场景标签基础上，为每条带宽样本记录 Android 默认网络的类型，并关联最近一次蜂窝无线观测。APK 安装方式见 `D:\NSFC\output\apk\0.5安装说明.md`。

## 采集内容

- `network_type` 为 `cellular`、`wifi`、`vpn`、`other`、`none` 或 `unknown`；`vpn_active`、`transport_wifi`、`transport_cellular` 和 `underlying_hint` 一并保存。VPN 优先标为 `vpn`，底层类型仅为系统提示，不能据此确认实际经过哪条链路。
- 5G NR：`nr_ss_sinr_db`、`nr_ss_rsrp_dbm`、`nr_csi_sinr_db`、`nr_csi_rsrp_dbm`、`nr_pci`。4G LTE：`lte_rsrp_dbm`、`lte_rssnr_db`、`lte_pci`。NR SS-SINR 与 LTE RSSNR 分列，不能混为同一口径。未上报值为 JSON `null`，零值才表示真实的 0。
- 优先读取默认数据 SIM 的服务中小区；缺少小区身份时尝试从该 SIM 的缓存 SignalStrength 取得信号值，PCI 保持空值。`nr_signal_source` / `lte_signal_source` 标明来源。切换默认数据 SIM 后不沿用上一 SIM 的观测。
- 手机大约每 5 秒请求小区刷新，Android 会限频且不保证有新值。`radio_observations` 独立保留调制解调器时间戳、接收时间戳、SIM 标识及来源；`samples.radio_observation_id` 指向关联观测，`radio_age_ms` 表示测速区间收到时距离对应 RAT 观测的时间。双向同一区间的上、下行引用同一无线与网络快照。样本行直接展开主要无线字段，便于分析。

无线观测是手机下行环境信息，不代表服务器或基站测得的上行无线质量。Wi‑Fi 或 VPN 测速期间仍可能有蜂窝信号，但不能把测速值归因于蜂窝链路。网络类型和标签都在 iperf 区间**收到时**关联，不代表整个区间内一直不变。

## 权限与数据

读取小区身份需要“精确位置”运行时权限并开启系统定位；地理位置记录开关只控制 GPS/网络坐标记录。无权限或无服务时，带宽采样继续保存，信号字段为空或关联缺失。APP 不读取 IMEI/IMSI、电话号码，也没有云端上传。

本地 SQLite 从 schema 3 升到 4，旧数据不回填无线字段。JSONL 导出 `schema_version=4`，新增 `radio_observations` 类型和样本无线/网络字段；删除会话时同时清理无线观测。旧版 JSONL 仍可单独分析。

## 验证

- 本地 Android SDK 编译通过，APK 签名及 16 KB ZIP 对齐检查通过。
- Android 16 模拟器从新服务器完成 10 秒双向测速，保存 20 条样本、5 条无线观测；导出 JSONL 含网络类型、NR RSRP/PCI 及观测年龄；无 SS-SINR 时为 `null`。
- 模拟器删除该会话后 `sessions`、`samples`、`radio_observations` 数量均为 0。
- `test_migration.py` 用用户旧版真实导出验证 v1→v4 原记录保留、旧无线字段为空、跨会话无线关联不泄漏。

模拟器的 5G 数值是仿真值。小米 14 / Android 16 的实际 SS-SINR 是否上报、Wi‑Fi/VPN 标签在其系统上的表现，需要真机数据验证。
