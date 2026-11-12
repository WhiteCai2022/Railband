# 5G信号采集可行性检查

检查日期：2026-10-03。基于当前Android APP 0.4源码及Android官方文档。本次为可行性评估，没有新增采集代码、APK或服务器配置。

## 结论

现有项目可以通过Android公开接口增加5G/4G信号特征采集，并与带宽、位置、场景标签一起保存。方案不依赖root，也无需修改iperf测量引擎或东京服务器。Xiaomi 14 / Android16具备调用这些接口的系统版本条件，但哪些字段能实际返回必须通过这台手机验证；接口存在不代表基带一定上报数值。

5G接口提供的是SS-SINR和CSI-SINR，即信号与干扰加噪声比，不能改名成不含干扰的纯SNR，也不能从带宽反推出。未上报值必须保存为null并说明状态。[Android NR信号接口](https://developer.android.com/reference/android/telephony/CellSignalStrengthNr)

## 建议首版字段

| 类别 | 字段 | 用途/约束 |
| --- | --- | --- |
| 5G接收强度 | SS-RSRP、CSI-RSRP，dBm | 描述手机侧收到的参考信号强度 |
| 5G接收质量 | SS-RSRQ、CSI-RSRQ，dB | 与速率下降、干扰或弱覆盖共同分析 |
| 5G干扰与噪声指标 | SS-SINR、CSI-SINR，dB | 保留两种口径，不混为同一数值 |
| 4G对照 | LTE RSRP、RSRQ、RSSNR | 用于退回LTE或NSA组合；不要把LTE值填入NR字段 |
| 小区/频段 | NR-ARFCN、bands、PCI、NCI、TAC、MCC/MNC | 按实际可用性保存，可分析观测到的小区变化 |
| 数据归属 | 活动网络传输类型、VPN状态、数据SIM订阅、网络制式及显示标识 | 说明测速流量与无线观测的关系 |
| 数据质量 | 原始观测时间（接口提供时）、回调接收时间、关联年龄、来源、不可用原因 | 区分新数据、缓存值和沿用值 |

NR基础指标为API29起的公开接口；NR频段列表为API30起，CQI相关接口为API31起，可作为后续可选字段。Xiaomi的Android16覆盖这些API版本，但字段可能不可用。[NR信号指标](https://developer.android.com/reference/android/telephony/CellSignalStrengthNr)、[NR小区信息](https://developer.android.com/reference/android/telephony/CellIdentityNr)、[LTE信号指标](https://developer.android.com/reference/android/telephony/CellSignalStrengthLte)

手机侧信号指标不等于基站测到的上行信号质量；可将它们作为上行和下行测速共同的环境观测，不应宣称同时采到了无线链路双方的SINR。

## 当前代码的接入点

- `MeasureService.java`：已有测量期间启动/停止LocationRecorder的流程，可并列加入RadioRecorder，按实际数据SIM注册信号、小区与网络状态监听，结束时解除注册。
- `MainActivity.java`：增加信号卡片、采集开关及权限状态。当前地理位置开关仅控制GPS相关权限流程，不能作为蜂窝信息采集的唯一前提。
- `AndroidManifest.xml`：已有精确/大致定位、网络状态和定位前台服务；目前没有READ_PHONE_STATE。拟采用的CellInfoListener要求电话状态与精确定位权限，应分别处理拒绝、仅大致定位和系统定位不可用等情况；不是所有信号API都具有相同权限要求。[CellInfoListener权限](https://developer.android.com/reference/android/telephony/TelephonyCallback.CellInfoListener)
- `Store.java`：建议数据库3→4，新增radio_observations与radio_events，samples增加radio_observation_id和radio_age_ms；旧记录不回填。指定会话导出、全部导出及删除操作同时包含新表。
- 双向同一区间先取一次无线快照，两条带宽记录引用同一快照，避免恰好发生回调时上下行关联到不同观测。

## 需要保留的边界

1. **刷新频率由手机决定。** 现有测速间隔0.1–60秒可以保持，但0.1秒一条带宽记录不代表0.1秒有一条新的无线测量。Android明确说明小区更新可能限频且不保证刷新。应保留独立观测及时间，采样时附带最近有效观测及年龄。[TelephonyManager更新限制](https://developer.android.com/reference/android/telephony/TelephonyManager#getAllCellInfo())
2. **5G图标不是足够证据。** DisplayInfo覆盖类型用于显示/运营商品牌，不应单靠它判定实际驻留制式或断言NR正在承载数据。保留原始LTE/NR信号和显示标识；SA/NSA证据不足时明确未知。[TelephonyDisplayInfo说明](https://developer.android.com/reference/android/telephony/TelephonyDisplayInfo#getOverrideNetworkType())
3. **小区和SIM切换后不能盲目沿用。** 可以保留此前观测供分析，但不能把前一个小区的SINR当成新小区当前SINR。观测到的变化时间也不是完整的底层切换信令时间。
4. **测速路由需要标明。** 现有原生iperf进程使用系统默认网络，项目尚无活动网络、VPN或数据SIM识别。Wi-Fi测速期间仍可能观察到蜂窝信号，应区分二者；VPN底层网络不明确时保留未知，不能强行归属5G。
5. **缺失值不能变成0。** 0 dB本身可能是有效观测，不能用它替代未上报、无权限或无服务。NR不可用时保留LTE观测，但字段各自独立。

## 建议推进方式

先在下一版加入同一套采集逻辑和诊断显示，让Xiaomi 14在实际移动网络测速期间导出一次能力报告：哪些字段有值、哪些为空、实际刷新间隔、所用SIM/网络以及NR与LTE是否同时出现。再根据真机结果确定稳定的数据集字段和图表。

验收覆盖NR/LTE、Wi-Fi/VPN、双SIM或数据卡切换、定位/电话权限拒绝、无服务及恢复、0.1秒和长间隔序列、旧日志升级与删除/导出。模拟器只验证数据处理和界面，不能证明真机5G SINR可用。
