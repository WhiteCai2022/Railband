# 轨途测速 JSONL 数据说明

适用版本：手机 APP v0.7.1 及截至 2026 年 10 月 15 日的服务器导出。本文说明每类记录的字段、单位、关联关系和分析方法；旧版本缺少的新字段不应补成 0。

当前数据分为实际带宽采样、独立位置与无线观测、人工标签、连接事件四部分。分析带宽时以 `samples` 为准；分析断网期间的环境变化时结合 `telemetry` 和 `attempt_events`。手机与服务器均使用 SQLite。

## 1 数据库与保存方式

| 位置 | 数据库与文件 | 保存内容 |
| --- | --- | --- |
| 手机 APP | SQLite，应用私有目录中的 `railband.db`，当前内部迁移版本为 6 | `sessions`、`samples`、定位、无线信号、标签、事件及补传队列；先落地，联网后上传。 |
| Linux 服务器 | SQLite，当前项目数据库为 `/var/lib/railband/archive.sqlite3` | 普通测量索引与完整 JSONL；行程索引与不可变 JSONL 数据块。 |

当前没有使用 MySQL。SQLite 是嵌入式数据库，不需要单独运行 MySQL 服务；启用 WAL 后写入期间可能还有 `-wal`、`-shm` 辅助文件。运行中备份应使用 SQLite 在线备份，而不是只复制主文件。[SQLite 文件说明](https://www.sqlite.org/onefile.html)

服务器目前以“索引字段 + 原始 JSONL 的 BLOB 二进制内容”保存数据。它没有把每条 SINR、PCI 和速率分别展开为服务器上的 SQL 行。网页详情和地图会在读取时解析 JSONL；因此“数据已经进数据库”和“每个测量字段都能直接用 SQL 列查询”是不同层次。

服务器主要表：

| 表 | 关键字段与含义 |
| --- | --- |
| `measurements` | `id` 网页普通测量编号；`device_id` 上传设备安装标识；`source_session_id` 手机测试编号；`uploaded_utc_ms` 接收时间；起止时间、状态、路线、设备、`sample_count`、标签索引；`sha256` 内容摘要；`jsonl` 原始文件。 |
| `journeys` | `id` 网页行程编号；`device_id` 与 `journey_id` 共同识别行程；`source_session_id` 手机测试编号；路线、设备、配置；`final_index` 最后块号；`final_status` 结束状态；`deleted` 删除标记。 |
| `journey_chunks` | `journey` 关联服务器行程编号；`chunk_index` 块号；`sha256` 原始块摘要；`sample_count` 块内速率行数；`jsonl` 原始块内容。 |

手机还有 `uploaded_sessions`、`journey_chunks`、`journey_cursors`、`upload_failures` 等同步管理表，它们不作为上述类型的测量观测行导出。服务器 `journey_chunks.journey`、网页卡片编号和手机 `session_id` 不可混用。

## 2 先理解文件结构

JSONL 是 UTF-8 文本，**每一行是一个独立 JSON 对象**，不在文件外包一对方括号。逐行读取并解析，不能将整个文件直接当成一个普通 JSON 对象读取。

每行的 `type` 决定含义：

| `type` | 含义 | 分析时的用途 |
| --- | --- | --- |
| `metadata` | 文件或数据块的格式说明 | 识别版本、下载清单、分块完整性。 |
| `sessions` | 一次测试或整次行程的配置与状态 | 确定设备、路线、测试条件与开始结束时间。 |
| `samples` | 一个方向、一个测速区间的真实吞吐结果 | 绘制网速曲线、计算方向均速。 |
| `labels` | 人工场景标签事件 | 按时间标注城区、山区、隧道、进站等。 |
| `locations` | 独立定位回调结果 | 保存定位时间、坐标、精度和移动信息。 |
| `location_events` | 定位启动、权限、来源变化等状态 | 判断为何没有坐标或定位质量下降。 |
| `radio_observations` | 手机读到的一批 NR 和 LTE 信号信息 | 分析 SINR、RSRP、PCI 与观测新旧。 |
| `events` | iperf 原始输出行 | 排查引擎错误，读取 TCP、端点和结束汇总。 |
| `telemetry` | 行程模式的独立定时环境采集 | 断网时仍保存位置、信号、网络和标签。 |
| `attempt_events` | 行程开始、连接、断开、恢复、重试等事件 | 识别中断范围与每次重连。 |

文件按表导出，表内通常按 `id` 排序；**文件行序不等于全局时间顺序**。位置表可能整段出现在测速表前面，分块也不必恰好对应一整分钟的所有表。需要时间序列时，应在解析后按相应时间字段排序。

## 3 导出形式与版本

| 来源 | `schema_version` 与 `format` | 文件组织 |
| --- | --- | --- |
| 普通测量的手机导出或网页下载 | 当前为 4；旧文件可能为 1、2、3；通常无 `format` | 一行 `metadata`，然后各表记录。网页保留上传文件的原始字节。 |
| 含行程的手机本地导出 | 5，`railband_local_journey` | 一行 `metadata`，然后各表记录；指定导出时为一次测试，“导出全部”时可包含多个测试。 |
| 一块行程补传内容 | 5，`railband_journey_chunk` | 块 `metadata`、一行 `sessions` 快照、该块的新增观测行。 |
| 网页下载整次行程 | 5，`railband_journey_download` | 第一行是下载清单，之后按块号拼接原始数据块；每块仍保留自己的 `metadata` 和 `sessions`。 |

`schema_version` 是 JSONL 格式版本，和手机 SQLite 内部迁移版本 6 不是同一编号。不能因普通导出写 4，就认为没有升级数据库。

手机“导出全部”可混合普通测试和多个行程。只要所选范围包含任意一条行程，当前导出就使用版本 5 和 `railband_local_journey`。应按各行的 `session_id` 与对应 `sessions.id` 分组，不能只看 `format` 就认定文件只有一个行程。

网页行程下载中会有多行 `metadata`、多行 `sessions`，这是正常的。`sessions` 是快照，按块号取最后一份作为较新的状态；其他测量行按本次行程内的 `(type, id)` 识别。不同手机、不同安装的数据合并时还应加入设备标识和行程或会话标识，不能只用 `id` 作为整个数据集的主键。

## 4 元数据字段

### 4.1 普通导出与本地行程导出

| 字段 | 类型与单位 | 含义 |
| --- | --- | --- |
| `type` | 字符串 | 固定为 `metadata`。 |
| `schema_version` | 整数 | JSONL 导出版本。 |
| `format` | 字符串，可缺失 | 行程文件的具体形式，见第 3 节。 |
| `export_session_id` | 整数或 `null` | 指定导出的手机测试编号；`null` 可表示全部导出，不能据此认定文件只有一次测试。 |
| `rate_unit` | 字符串 | 速率单位声明，当前为 `bits/s`。 |
| `clock` | 字符串 | 使用 UTC 毫秒与 Android 开机单调毫秒。 |
| `label_semantics` | 字符串 | 标签是接收时快照；区间标注需结合标签时间线。 |
| `missing_intervals` | 字符串 | 缺失区间不补零。 |
| `mode` | 字符串 | TCP、无限速或持续测量等约定。 |
| `sample_semantics` | 字符串 | 一行对应一个方向的一个区间，`sender` 是手机的角色。 |
| `location_semantics` | 字符串 | WGS84、本次会话最近有效位置关联、年龄保留等约定。 |
| `radio_semantics` | 字符串 | NR 与 LTE 区分、异步观测及空值约定。 |
| `network_semantics` | 字符串 | Android 默认网络快照的分类与局限。 |

这些 `*_semantics` 是供阅读的说明文本，不是独立测量数值。旧格式可能缺少其中部分说明。

### 4.2 行程块和网页下载清单

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `journey_id` | UUID 字符串 | 手机为整次行程生成的随机标识；重连不变。 |
| `chunk_index` | 从 0 开始的整数 | 本数据块的顺序号；不是测速采样序号。 |
| `is_final` | 布尔值 | 是否为整次行程的最后一个块。手动停止也能生成最终块，不等于测试成功。 |
| `semantics` | 字符串 | 快照、身份、时间与缺失处理说明。 |
| `complete` | 布尔值，仅下载清单 | 是否已收到最终块，且块号 0 到 `final_index` 连续齐全。 |
| `final_index` | 整数或 `null`，仅下载清单 | 应有的最后块号；`null` 表示尚未收到结束块。 |
| `chunks` | 数组，仅下载清单 | 本次下载冻结的块列表，包含每块的 `chunk_index` 和 `sha256`。 |
| `sha256` | 64 个十六进制字符 | 对应原始块字节的 256 bit SHA-256 内容摘要，用于核对一致性与重试去重；不是密码。 |

下载清单中的 `chunks` 是本次下载包含的列表，之后新到的块需要再次下载才会出现。`complete=true` 表示文件传齐，**不表示沿途网络不断、GPS 每秒刷新或 SINR 全部有值**。校验摘要时应针对原始块字节，不能将 JSON 重新排版后再算。

## 5 测试记录和测试配置

### 5.1 sessions 外层字段

| 字段 | 类型与单位 | 含义 |
| --- | --- | --- |
| `id` | 整数 | 手机本地测试编号，其他记录的 `session_id` 指向它。 |
| `started_utc_ms` | 整数，毫秒 | APP 创建测试记录的 Unix UTC 时间；包含后续连接准备时间。 |
| `started_elapsed_ms` | 整数，毫秒 | 同一开始时刻的 Android 开机单调时间。 |
| `ended_utc_ms` | 整数，毫秒或 `null` | 测试记录结束时间；运行中快照为 `null`。 |
| `status` | 字符串 | 测试结束或运行状态，见下表。 |
| `config` | JSON 字符串 | 测试参数；需要再做一次 JSON 解析。 |
| `error` | 字符串或 `null` | APP 保存的错误或中断说明；空字符串通常表示没有附加错误。不能单靠此字段判断数据有效。 |
| `journey_id` | UUID 字符串或 `null` | 行程模式的标识，普通测量通常为 `null` 或旧格式缺失。 |

| `status` | 含义 |
| --- | --- |
| `running` | 尚在采集；历史数据异常退出后通常会被恢复为 `interrupted`。 |
| `completed` | 按该模式的结束条件完成；不保证行程期间从未断网。 |
| `cancelled` | 用户手动停止，已采集数据仍有效保留。 |
| `failed` | 测试或保存失败；可检查错误，并按实际记录使用已保存部分。 |
| `timeout` | APP 或系统时限导致结束。 |
| `interrupted` | 进程或服务中断，未正常完成收尾。 |

### 5.2 config 二次解析后的字段

| 字段 | 类型与单位 | 含义 |
| --- | --- | --- |
| `host`, `port` | 字符串、整数 | iperf 服务端地址和 TCP 端口，通常为 5201；不是数据库地址。 |
| `protocol` | 字符串 | 当前 APP 测试为 `TCP`。 |
| `direction` | 字符串 | `phone_upload` 手机上行；`phone_download` 手机下行；`bidirectional` 同时双向。 |
| `duration_s` | 整数，秒 | 用户设置的时长；0 表示持续到手动停止或被系统结束。 |
| `duration_semantics` | 字符串，行程模式 | `wall time including outages` 表示整次行程按包含连接与断网等待的总时长计时。 |
| `sample_interval_s` | 数值，秒 | 用户设置的测速报告及行程独立采集目标间隔，当前可设 0.1 到 60 秒；不是严格实时保证。 |
| `rate_cap_bps` | 整数，bit/s | 用户设置的目标限速；0 表示不限速，不表示带宽无限。 |
| `rate_cap_scope` | 字符串 | 当前为 `per_direction`，双向的两个方向分别应用该限速。 |
| `route` | 字符串 | 用户填写的路线或行程备注，不是自动识别的铁路线路名称。 |
| `device` | 字符串 | Android 厂商与机型描述，不是唯一设备编号。 |
| `android` | 字符串 | Android 系统版本。 |
| `app_version` | 字符串 | APP 版本。 |
| `iperf_commit` | 字符串 | APK 内 iperf 引擎源码的构建提交标识；与服务端 iperf 版本可能不同。 |
| `location_requested` | 布尔值 | 本次是否请求定位采集。 |
| `location_permission` | 布尔值 | 启动时是否具备精确或大致位置权限，不代表一定能收到定位。 |
| `radio_requested` | 布尔值，部分模式有 | 当前普通模式写 `true`，表示启动无线采集流程；行程配置可能省略。 |
| `radio_permission` | 布尔值 | 启动时是否具备无线接口所需的精确位置权限；返回数据还取决于系统定位、基带等。 |
| `location_association_policy` | 字符串 | 当前 `latest_valid_fix_in_session_no_age_limit`：同一测试最近有效位置，不按年龄清空。 |
| `network_observation` | 字符串，部分模式有 | 网络类型是在收到测速区间报告时观察到的默认网络。 |
| `journey_mode` | 布尔值，行程模式 | 为 `true` 时启用整次行程及自动重连。 |
| `journey_id` | UUID 字符串，行程模式 | 与外层行程标识一致。 |
| `upload_during_measurement` | 布尔值，行程模式 | 启动时保存的“测速期间补传”设置快照，不保证之后设置从未变过。 |

例如 `rate_cap_bps=5000000` 代表目标 5 Mbps，每个方向分别适用。此条件下测到约 5 Mbps，说明这次限速传输能达到该水平，不能推出线路最大可用带宽只有 5 Mbps。`duration_s=0`、`rate_cap_bps=0`、真实测速为 0、缺失值 `null` 的含义各不相同。

## 6 带宽采样字段

`type="samples"` 是带宽分析的主表。一个区间单向一行；双向通常两行，具有相同 `seq`，方向不同。

| 字段 | 类型与单位 | 含义 |
| --- | --- | --- |
| `id`, `session_id` | 整数 | 样本编号、所属手机测试编号。 |
| `seq` | 整数 | APP 收到的测速区间事件序号。双向两行共享序号；行程重连后继续递增。 |
| `start_s`, `end_s` | 数值，秒 | 该次 iperf 连接内的区间起止时间；重连后重新从新连接计时。 |
| `seconds` | 数值，秒 | iperf 实际统计的区间长度；可能略偏离设置的采样间隔。计算均速应使用此值。 |
| `bytes` | 整数，字节 | 该方向在本区间统计的传输字节数；不是 bit，也不是整个行程累计。 |
| `bits_per_second` | 数值，bit/s | 该方向本区间平均吞吐速率；不是瞬时物理层带宽。 |
| `sender` | 整数 0 或 1 | 手机在该方向的角色：1 手机发送，即上行；0 手机接收，即下行。 |
| `direction` | 字符串或 `null` | 明确方向，优先使用此字段。旧文件没有时可由 `sender` 推断。 |
| `received_utc_ms` | 整数，毫秒 | APP 保存区间结果时的 UTC 时间，不是区间第一包时间。 |
| `received_elapsed_ms` | 整数，毫秒 | 同一时刻的 Android 开机单调时间。 |
| `label_at_receive` | 字符串 | 收到区间结果时当前标签；不能保证整个区间均属于该场景。 |
| `raw` | JSON 字符串 | iperf 区间事件的 `data` 对象，含 `streams`、`sum` 等；需二次解析。 |
| `attempt_id` | 整数或 `null` | 行程中的第几次连接尝试，从 1 开始；普通模式通常为 `null`。 |
| `journey_start_s`, `journey_end_s` | 数值，秒或 `null` | 对齐整次行程开始的区间起止时间，保留重连之间的间隔；见第 7 节。 |
| `location_id`, `location_age_ms` | 整数或 `null` | 关联定位编号与位置年龄；详细含义见第 9 节。 |
| `radio_observation_id`, `radio_age_ms` | 整数或 `null` | 关联无线观测编号与无线观测年龄；见第 10 节。 |
| 位置、无线指标、网络类型的其他列 | 按对应字段类型 | 为方便分析复制到样本的副本；第 9 到 11 节逐项说明。 |

上下行两行的 `raw` 可能完全相同，因为这个原始区间对象同时含上行和下行汇总。读取扁平化的 `direction` 与 `bits_per_second` 最直接；如果已经统计了两行 `samples`，不要再把每个 `raw` 的两个方向重复累计一遍。

## 7 时间字段与重连时间轴

| 时间类别 | 常见字段 | 如何使用 |
| --- | --- | --- |
| UTC Unix 毫秒 | `*_utc_ms`、`utc_ms` | 除以 1000 得 Unix 秒；显示北京时间时明确转换到 `Asia/Shanghai`。手机系统校时可能让 UTC 有跳变。 |
| Android 开机单调毫秒 | `*_elapsed_ms`、`elapsed_ms` | 从本次开机起累计，包含休眠时间。用于同一手机、同一次开机内对齐和计算间隔；不能跨手机或跨重启直接相减。 |
| 本次连接相对秒 | `start_s`、`end_s` | 按 `attempt_id` 分组后解释；不能将重连前后的相同 `start_s` 当成同一时刻。 |
| 整次行程相对秒 | `journey_start_s`、`journey_end_s`、`journey_elapsed_s` | 统一观察行程进展，保留断网等待时段。 |

行程区间时间的计算是：收到本轮 iperf `start` 事件的本机时间锚点减行程开始时间，再加区间的 `start_s` 或 `end_s`。它是 APP 的对齐估计，不是经过跨端硬件同步的包时间。`received_*` 还包含报告传递和处理延迟。

行程有限时长包含断网等待；普通模式的 `duration_s` 是传给 iperf 的测试时长，而 `sessions` 的起止时间还包括连接与收尾。两者不要求完全相等。

## 8 人工场景标签

| 字段 | 类型与单位 | 含义 |
| --- | --- | --- |
| `id`, `session_id` | 整数 | 标签事件编号与所属测试编号。 |
| `utc_ms`, `elapsed_ms` | 整数，毫秒 | APP 保存标签事件的 UTC 时间与开机单调时间。 |
| `label` | 字符串 | 用户选择的场景文本；开始时也会记录初始标签。 |

将标签事件按时间排序，通常把一条标签从记录时刻延续到下一条标签之前。若一个速率区间跨过标签切换，可标为混合区间，或按事先声明的时间重叠规则处理；不能只凭 `label_at_receive` 就认为整个区间都在隧道中。标签是人工标记，不是自动地形识别结果。

## 9 位置与定位状态

`type="locations"`。每行是一条 Android 回调给 APP 的定位结果。定位请求的最短时间参数为 1 秒，实际回调频率由系统和定位源决定，与测速采样间隔不是同一个时钟。APP 请求 GPS 与网络定位源（只有大致位置权限时不请求 GPS）。

| 字段 | 类型／单位 | 说明 |
| --- | --- | --- |
| `id` | 整数 | 手机本地 `locations` 表的记录编号。 |
| `session_id` | 整数 | 所属本地测试编号。关联时同时校验测试编号，避免混入其他测试位置。 |
| `fix_utc_ms` | 整数，毫秒 | Android 定位结果自身的 Unix UTC 时间，不是 APP 保存时间。 |
| `fix_elapsed_ms` | 整数，毫秒 | 定位结果对应的 Android 开机单调时间，来自 `getElapsedRealtimeNanos()/1000000`。 |
| `received_utc_ms` | 整数，毫秒 | APP 保存这条定位记录时的 UTC 时间。 |
| `received_elapsed_ms` | 整数，毫秒 | APP 保存时的开机单调时间，可与 `fix_elapsed_ms` 比较定位回调延迟。 |
| `provider` | 字符串 | 定位源名称，正常采集通常为 `gps` 或 `network`；测试数据可出现其他名称。 |
| `latitude` | 数值，度 | 纬度，范围 −90～90。导出保留 Android 原始坐标，按项目约定使用 WGS84。 |
| `longitude` | 数值，度 | 经度，范围 −180～180。注意顺序是纬度、经度；有些地图接口要求经度、纬度。 |
| `accuracy_m` | 数值，米或 `null` | 水平位置估计误差半径，对应 Android 给出的约 68% 置信水平；不是保证不超过此距离。 |
| `altitude_m` | 数值，米或 `null` | 相对 WGS84 参考椭球的高度，不能直接理解成海拔。 |
| `speed_mps` | 数值，米／秒或 `null` | 定位源提供的移动速度；换成 km/h 时乘 3.6。 |
| `bearing_deg` | 数值，度或 `null` | 定位源提供的行进方向，范围 `[0,360)`，不是手机屏幕朝向。 |
| `is_mock` | 整数 `0`／`1` | Android 是否将此结果标记为模拟定位。`0` 只代表未被标记，不能作为真实位置证明；模拟器结果也可能为 `0`。 |

位置字段的单位与 API 语义见 [Android Location 官方说明](https://developer.android.com/reference/android/location/Location)。

当前版本只接纳经纬度有限、落在合法范围内，且 `fix_elapsed_ms > 0`、不晚于接收时刻的定位。接收后没有“超过 10 秒就丢弃”的规则：本次测试会一直沿用本次收到的最近有效位置，直至更新。坐标沿用不表示列车仍停在原位置。旧版本已保存的数据保留旧关联方式，不会重新补齐历史位置。

### 9.1 测速记录中的位置字段

| 字段 | 类型／单位 | 说明 |
| --- | --- | --- |
| `location_id` | 整数或 `null` | 本样本关联的 `locations.id`。收到有效位置前为 `null`。 |
| `location_age_ms` | 整数，毫秒或 `null` | 样本入库时刻减所引用位置的 `fix_elapsed_ms`；数值很大表示沿用旧位置。 |
| `latitude`, `longitude` | 数值，度或 `null` | 从关联的定位记录复制，方便直接分析；不是每个测速间隔重新测出的位置。 |
| `location_accuracy_m` | 数值，米或 `null` | 对应 `locations.accuracy_m`。 |
| `location_fix_utc_ms`, `location_fix_elapsed_ms` | 整数，毫秒或 `null` | 对应关联位置的原始定位时间。 |
| `location_provider` | 字符串或 `null` | 对应 `locations.provider`。 |
| `location_is_mock` | 整数 `0`／`1` 或 `null` | 对应 `locations.is_mock`。 |

`samples` 没有复制高度、速度或方位；需要这些量时，用 `location_id` 与 `session_id` 关联原始 `locations` 行。

### 9.2 独立采集中的位置

解析 `telemetry` 的 JSON 字符串 `raw` 后：`location` 是整条位置记录对象（或 `null`），`latitude`、`longitude` 是快捷副本，`location_age_ms` 是该独立采集时刻的位置年龄。`location_state` 取 `fresh`（年龄 ≤10000 ms）、`carried`（年龄 >10000 ms，仍保留坐标）、`unavailable`（尚无可用位置）。这里的“fresh”是 APP 的时间分类，不是定位精度认证。

### 9.3 定位状态事件

| 字段 | 类型／单位 | 说明 |
| --- | --- | --- |
| `id`, `session_id` | 整数 | 事件编号与所属本地测试编号。 |
| `utc_ms` | 整数，毫秒 | APP 记录定位状态事件的 UTC 时间。 |
| `elapsed_ms` | 整数，毫秒 | 同一事件的开机单调时间。 |
| `status` | 字符串 | APP 可读状态，例如等待定位、未开启定位、权限不足、定位来源开启／关闭。其中文本可能随版本变化，不宜当作固定枚举。 |

`type="location_events"`。这些是状态变化／启动说明，不是坐标点；正常收到每个坐标时不会再各写一条 `location_events`。

## 10 无线信号观测

`type="radio_observations"`。无线信号通过 Android 电话接口读取。当前 APP 大约每 5 秒执行一次刷新：先读取小区缓存，再请求一次更新；重复内容会去重，所以不能将记录数理解为固定 5 秒采样数，也不能因为测速间隔设为 0.1 秒，就认为 SINR 同样有 0.1 秒分辨率。获取条件还包括精确位置权限、系统定位开启、设备提供蜂窝接口以及存在默认移动数据订阅。

当前实现最多保存一个被选中的 NR 小区和一个 LTE 小区：优先取系统返回列表中首个注册小区或主／辅服务小区，不采集完整邻区列表；没有对应小区对象时，尝试从 `SignalStrength` 缓存补充该制式的首个信号对象。补充信号对象没有小区身份，因此可能出现 RSRP 有值而 PCI、registered 和 connection_status 为空。

| 字段 | 类型／单位 | 说明 |
| --- | --- | --- |
| `id`, `session_id` | 整数 | 本地无线观测编号与所属测试编号。 |
| `subscription_id` | 整数 | Android 当时的默认移动数据订阅 ID。不是手机号、IMSI、ICCID，也不应当作跨手机的永久 SIM 身份或卡槽号。其来源为 [SubscriptionManager.getDefaultDataSubscriptionId](https://developer.android.com/reference/android/telephony/SubscriptionManager#getDefaultDataSubscriptionId())。 |
| `radio_rat` | 字符串 | APP 的展示优先项：存在选中的 NR 小区或 NR 信号对象时写 `NR`，否则写 `LTE`。 |
| `modem_elapsed_ms` | 整数，毫秒 | `radio_rat` 对应项的时间；选 NR 时取 `nr_modem_elapsed_ms`，否则取 LTE。若该时间 ≤0 或晚于当前开机时间，主字段写 `0` 表示未知。 |
| `received_utc_ms`, `received_elapsed_ms` | 整数，毫秒 | APP 保存这条无线观测的 UTC 时间／开机单调时间。与基带观测时间不同。 |
| `nr_ss_sinr_db` | 整数，dB 或 `null` | 5G NR 同步信号 SS 的信号与干扰加噪声比（SS-SINR）。API 标示范围 −23～40 dB。 |
| `nr_ss_rsrp_dbm` | 整数，dBm 或 `null` | 5G NR 同步信号参考信号接收功率（SS-RSRP）。API 标示范围 −156～−31 dBm。 |
| `nr_csi_sinr_db` | 整数，dB 或 `null` | 5G NR CSI 参考信号的 SINR；与 SS-SINR 是不同测量。API 标示范围 −23～23 dB。 |
| `nr_csi_rsrp_dbm` | 整数，dBm 或 `null` | 5G NR CSI 参考信号的 RSRP。API 标示范围 −156～−31 dBm。 |
| `nr_pci` | 整数或 `null` | NR 物理小区标识 PCI，合法范围 0～1007。 |
| `nr_registered` | 整数 `0`／`1` 或 `null` | Android 是否报告手机在该小区注册，可用于或将用于网络信令。不是“本次测速一定由该小区承载”的证明。 |
| `nr_connection_status` | 整数或 `null` | Android 的 NR 小区连接状态：0 非服务小区；1 主服务；2 辅服务；2147483647 状态未知。当前代码直接保存此状态值，未知状态没有归一化成 `null`。 |
| `nr_modem_elapsed_ms` | 整数，毫秒或 `null` | NR 小区／信号对象的 Android 开机时间戳；看 `nr_signal_source` 判断来源。原值保留；未知时可能为 0。 |
| `nr_signal_source` | 字符串或 `null` | `cell_info`：来自被选小区的信号对象；`signal_strength_cache`：小区缺失，改用信号强度缓存。 |
| `lte_rsrp_dbm` | 整数，dBm 或 `null` | 4G LTE 参考信号接收功率 RSRP。API 标示范围 −140～−43 dBm。 |
| `lte_rssnr_db` | 整数，dB 或 `null` | LTE 参考信号信噪比 RSSNR。当前使用的 `getRssnr()` API 单位为 dB，范围 −20～30 dB；导出值无需再除以 10。不能直接当作 NR SS-SINR。 |
| `lte_pci` | 整数或 `null` | LTE 物理小区标识 PCI，合法范围 0～503。 |
| `lte_registered` | 整数 `0`／`1` 或 `null` | LTE 对应的注册标记，含义同 NR。 |
| `lte_connection_status` | 整数或 `null` | LTE 对应连接状态，数值含义同上。 |
| `lte_modem_elapsed_ms` | 整数，毫秒或 `null` | LTE 对应小区／信号对象的开机时间戳。 |
| `lte_signal_source` | 字符串或 `null` | 含义同 `nr_signal_source`。 |
| `source` | 字符串 | `cache` 表示这一批小区列表来自 `getAllCellInfo()`；`update` 表示来自请求更新的回调。它与每个制式的 `*_signal_source` 是两层来源信息。 |
| `raw` | JSON 字符串 | APP 组装的本条无线观测内容，解析后可读到上述业务字段；不是基带厂商的完整原始日志。`*_registered` 在这个嵌套 JSON 中是布尔值，外层数据库导出为 0／1。不存在的制式字段可能在 raw 中省略，而外层表字段为 `null`。 |

NR 的指标单位与 API 范围见 [CellSignalStrengthNr](https://developer.android.com/reference/android/telephony/CellSignalStrengthNr)；LTE 见 [CellSignalStrengthLte](https://developer.android.com/reference/android/telephony/CellSignalStrengthLte)。PCI 范围分别见 [CellIdentityNr](https://developer.android.com/reference/android/telephony/CellIdentityNr#getPci()) 与 [CellIdentityLte](https://developer.android.com/reference/android/telephony/CellIdentityLte#getPci())。PCI 是物理小区标识，可以复用；当前没有同时采集 NCI／ECI、频点、MCC／MNC，因此仅靠 PCI 无法定位唯一基站。

时间戳还应区分：`cell_info` 使用 `CellInfo.getTimestampMillis()`（系统从基带收到此小区信息的近似时间）；补充信号使用 `SignalStrength.getTimestampMillis()`（该信号被基带测量、上报的近似时间）。二者都是 Android 开机单调时钟，不是 UTC，也不是 APP 请求刷新时间。[CellInfo 时间说明](https://developer.android.com/reference/android/telephony/CellInfo#getTimestampMillis())、[SignalStrength 时间说明](https://developer.android.com/reference/android/telephony/SignalStrength#getTimestampMillis())。

APP 将信号值与 PCI 的 Android `Integer.MAX_VALUE`（2147483647，未上报）改成 JSON `null`，不改成 0，也不估算补值。`cache`／`update` 不能保证得到新的基带测量：请求刷新受系统限制，回调仍可能包含缓存。[TelephonyManager 小区接口说明](https://developer.android.com/reference/android/telephony/TelephonyManager#getAllCellInfo())。

`radio_rat="NR"` 的含义是当前观测选中了 NR 指标。它不区分 SA／NSA，也不证明测速字节全由 NR 承载；同一行可同时有 LTE 指标。对于 `network_type="wifi"` 或 `"vpn"` 的测速，不应把速度直接归因于这条蜂窝信号。无线字段保留是为了联合分析。

### 10.1 测速记录中的无线字段

| 字段 | 说明 |
| --- | --- |
| `radio_observation_id` | 引用本次测试最近有效的 `radio_observations.id`；未有观测时为 `null`。 |
| `radio_age_ms` | 取样附近的开机时间减该观测的主字段 `modem_elapsed_ms`；无有效基带时间时为 `null`。不是“距离 APP 保存无线行的时间”。 |
| `radio_rat` | 对应原始无线行的同名字段。 |
| `radio_subscription_id` | 对应原始行的 `subscription_id`。 |
| `radio_modem_elapsed_ms` | 对应原始行的主字段 `modem_elapsed_ms`。 |
| `nr_ss_sinr_db`, `nr_ss_rsrp_dbm`, `nr_csi_sinr_db`, `nr_csi_rsrp_dbm`, `nr_pci`, `nr_registered`, `nr_modem_elapsed_ms`, `nr_signal_source` | 对应原始行的 NR 字段，含义、单位不变。 |
| `lte_rsrp_dbm`, `lte_rssnr_db`, `lte_pci`, `lte_registered`, `lte_modem_elapsed_ms`, `lte_signal_source` | 对应原始行的 LTE 字段，含义、单位不变。 |

注意 `samples` 没有复制 `nr_connection_status`、`lte_connection_status`、`source` 和无线行 `raw`；需要时关联原始无线行。`samples.raw` 是 iperf 的区间内容，不是无线内容。多行测速样本可能引用同一无线观测，不能当作多次独立的基带测量。若同一观测含 NR 和 LTE，`radio_age_ms` 只对应展示优先项；计算 LTE 的单独年龄应使用 `lte_modem_elapsed_ms`。

独立采集 `telemetry.raw` 中的 `radio` 是完整无线观测对象（或 `null`）；`radio_age_ms` 的含义同上。即便无线值较旧也可能继续被引用；分析时应自行按时间新鲜程度筛选。

## 11 网络类型与 VPN 字段

测速样本的 `network_type` 等字段，以及 `telemetry.raw.network`，都是 APP 当时查询到的 Android 默认网络快照。它们不能严格证明整个测速间隔都走同一条路径，也不表示已验证服务器可达。该实现没有给每个测速 socket 记录实际路由。

| 字段 | 类型／取值 | 说明 |
| --- | --- | --- |
| `network_type` | 字符串 | `cellular` 移动数据；`wifi` Wi-Fi；`vpn` VPN；`other` 其他传输；`none` 无默认网络；`unknown` 无法取得／识别网络信息。分类优先顺序为 VPN、Wi-Fi、蜂窝。 |
| `vpn_active` | 布尔／0、1／`null` | 默认网络能力是否包含 VPN 传输。`samples` 外层为 0／1，`telemetry.raw.network` 内为 `false`／`true`。这不是扫描设备上所有 VPN 进程的结果。 |
| `transport_wifi` | 同上 | 默认网络能力是否标注 Wi-Fi。 |
| `transport_cellular` | 同上 | 默认网络能力是否标注蜂窝。 |
| `underlying_hint` | 字符串 | VPN 时按能力标记给出 `wifi`、`cellular`、`wifi+cellular` 或 `unknown` 提示；非 VPN 时等于 `network_type`。仅是提示，不能确认分流、代理出口或实际底层路径。 |
| `network_handle` | 字符串 | Android 默认网络对象的句柄，供观察网络对象是否改变。无默认网络时为空字符串。它出现在 `telemetry.raw.network` 以及部分连接事件的网络快照中，当前 `samples` 外层不保存此字段。不能当作公网 IP、运营商编号、基站号或跨设备的网络 ID。 |

正常读到“没有默认网络”时，三项传输标记记录为 `false`（或 0）；存在网络对象但暂时无法取得能力时为 `null`。异常分支可能只保留 `network_type`、`underlying_hint` 两个 `unknown` 字段。不能把缺字段一律转换为 `false`。

这些标记来自 Android [`NetworkCapabilities.hasTransport`](https://developer.android.com/reference/android/net/NetworkCapabilities#hasTransport(int))。能力快照会随连接变化，采样后可能立即过时。[Android 官方网络能力说明](https://developer.android.com/reference/android/net/NetworkCapabilities)。

## 12 行程独立采集记录

`type="telemetry"` 外层只有 `type`、`id`、`session_id`、`raw`。`raw` 是 JSON 字符串，再解析后得到以下内容：

| raw 内字段 | 类型与单位 | 含义 |
| --- | --- | --- |
| `utc_ms`, `elapsed_ms` | 整数，毫秒 | 本次独立采集的 UTC 与开机单调时间。 |
| `journey_elapsed_s` | 数值，秒 | 距行程开始的相对时间。 |
| `attempt_id` | 整数 | 当时已进行到的连接尝试号；第一次尝试前可为 0，离线等待时可能保留前一次号。 |
| `measurement_state` | 字符串 | `connecting` 正在连接；`testing` 正在测速；`no_network` 无默认网络；`retry_wait` 等待重试；`stopping` 收尾。 |
| `label` | 字符串 | 独立采集时刻的当前场景。 |
| `network` | 对象 | 第 11 节的网络快照，内层标记为布尔值。 |
| `throughput_bps` | 当前为 `null` | 本行本身没有测得带宽，刻意不填最近速度，也不补 0。真实速率在 `samples`。 |
| `location` | 对象或 `null` | 关联的完整 `locations` 行，不带顶层类型标签。 |
| `latitude`, `longitude` | 数值或 `null` | 上述位置的快捷副本。 |
| `location_age_ms` | 整数，毫秒或 `null` | 独立采集时刻减关联位置的原始单调时间。 |
| `location_state` | 字符串 | `fresh` 年龄不超过 10 秒；`carried` 超过 10 秒但仍沿用；`unavailable` 没有可用位置。 |
| `radio` | 对象或 `null` | 关联的完整 `radio_observations` 行；该对象的 `raw` 仍可能是 JSON 字符串。 |
| `radio_age_ms` | 整数，毫秒或 `null` | 无线观测年龄，与第 10 节相同。 |

独立采集按所选目标间隔尽力调度，定位和信号源仍各自异步刷新。多条 `telemetry` 引用同一位置或无线行是正常的。网络中断期间继续有这些记录，并不意味着这段时间仍测得了吞吐。

## 13 行程连接和重试事件

`type="attempt_events"` 外层同样为 `id`、`session_id`、`raw`。解析 `raw` 后：

| 字段 | 含义 |
| --- | --- |
| `attempt_id` | 当前连接尝试编号。0 用于首次连接前或行程级事件；失败尝试也占号。 |
| `state` | 本事件名称，见下表。 |
| `detail` | 说明字符串；有时是普通文本，有时是被编码成字符串的网络快照或行程 ID，不可一律当对象。 |
| `utc_ms`, `elapsed_ms` | APP 保存事件的 UTC 毫秒和开机单调毫秒。 |

| `state` | 含义与 detail 内容 |
| --- | --- |
| `journey_started` | 创建整次行程；detail 通常为行程 UUID。 |
| `connecting` | 发起一轮连接；detail 通常为网络快照的 JSON 字符串。 |
| `connected` | 收到 iperf start 事件；detail 当前为本轮锚点的单调毫秒文本。 |
| `disconnected` | 本轮退出，保存断开原因或退出信息。 |
| `retry_wait` | 本轮之后安排重试，文本包含原因和计划等待毫秒数。 |
| `no_network` | 没有默认网络，等待网络恢复。 |
| `network_lost`, `network_available` | v0.7.1 记录默认网络消失或恢复／切换；恢复 detail 可为新的 `network_handle`。不表示服务器已可达。 |
| `network_callback_unavailable` | 无法注册网络回调，已降级为定时检查网络。 |
| `journey_finished` | 整次行程收尾，文本含结果与说明。 |

`connected` 后仍需要等一个采样区间，才会出现新 `samples`；网络 `available` 后也可能先经历几次失败尝试。可用这些事件分别计算“网络恢复到发起尝试”“到连接成功”“到第一条真实速率”的时间，不应混成一个指标。

## 14 iperf 原始输出

### 14.1 events 外层字段

| 字段 | 类型与单位 | 含义 |
| --- | --- | --- |
| `id`, `session_id` | 整数 | 原始输出行编号与所属测试编号。 |
| `received_utc_ms`, `received_elapsed_ms` | 整数，毫秒 | APP 保存原始输出行的时刻。 |
| `attempt_id` | 整数或 `null` | 行程连接号；新普通模式原始事件可为 0，旧迁移记录可为空。 |
| `raw` | 字符串 | 原始 iperf 输出。通常可解析为 `{"event":...,"data":...}`，但可能是普通文字或 `stderr: ...`。 |

常见 `event` 为 `start`、`interval`、`end`、`error`。前三者的 `data` 通常是对象，`error.data` 是错误说明字符串。发生中断时 `end` 可为空对象，也可能完全没有结束事件，不能虚构双方汇总。

### 14.2 start 中的常见字段

| 路径 | 含义与单位 |
| --- | --- |
| `version`, `system_info` | iperf 构建版本和运行环境描述。 |
| `timestamp.time`, `timestamp.timesecs`, `timestamp.timemillisecs` | 启动时间文本、Unix 秒、Unix 毫秒；不是每个区间的接收时刻。 |
| `connected[].socket` | 本进程当时的套接字文件描述符，不是稳定的连接全局编号。 |
| `connected[].local_host`, `local_port`, `remote_host`, `remote_port` | 测速数据流的本地与对端地址／端口。 |
| `connecting_to.host`, `connecting_to.port` | 本轮目标服务器。 |
| `cookie` | iperf 协议用的连接识别字符串，与网页登录密码和上传密钥无关。 |
| `tcp_mss_default` | TCP 默认最大报文段长度，字节。 |
| `sock_bufsize`, `sndbuf_actual`, `rcvbuf_actual` | 请求的套接字缓冲区设置及系统实际收发缓冲区，字节。请求值 0 通常代表系统默认。 |
| `target_bitrate`, `fq_rate` | 目标限速和内核 pacing 设置，bit/s；不是实测吞吐。 |
| `test_start.protocol`, `num_streams`, `blksize` | 协议、并行流数、应用读写块大小（字节）。后两者也在 `test_start` 对象内。 |
| `test_start.omit`, `duration`, `interval` | 预热忽略时间、本轮引擎时长、报告间隔，秒。行程的每轮引擎可持续运行，整体时长由 APP 管。 |
| `test_start.bytes`, `blocks` | 按字节数或块数结束的目标设置；APP 通常不用而为 0，不是实测流量。 |
| `test_start.reverse`, `bidir`, `tos` | 反向开关、双向开关、IP 流量类别设置数值。 |
| `test_start.target_bitrate`, `fqrate` | 本轮目标限速与内核 pacing，bit/s。 |
| `test_start.gso`, `gro` | UDP 分段与接收合并相关开关，当前 TCP 测试通常为 0。 |

### 14.3 interval 中的常见字段

`samples.raw` 正是这一层 `data` 对象，没有 `event/data` 外壳。

| 字段或路径 | 含义与单位 |
| --- | --- |
| `streams[]` | 各测速流的区间报告；不是另一组需要重复计入的总流量。 |
| `sum`, `sum_bidir_reverse` | 当前轮正向、双向反向的区间汇总。单向反向测试的 `sum` 可对应下行，方向以角色为准。 |
| `start`, `end`, `seconds`, `bytes`, `bits_per_second`, `sender` | 与第 6 节对应；原始 JSON 的 `sender` 为布尔值。 |
| `omitted` | 是否属于按 iperf 设置忽略的预热区间；当前 APP 没有提供预热选项，分析其他来源文件时须留意。 |
| `socket` | 流的套接字文件描述符。 |
| `retransmits` | 该 TCP 区间的重传计数，不是丢包率；接收方向可能不提供。 |
| `snd_cwnd`, `snd_wnd` | TCP 拥塞窗口与发送窗口，字节。 |
| `rtt`, `rttvar` | 内核估计 RTT 与 RTT 变化量，微秒；除以 1000 得毫秒，不是 ICMP ping。 |
| `pmtu` | 路径 MTU，字节。 |
| `reorder` | 内核截至该时刻累计观察到的乱序事件计数，不能直接当成本区间丢包数。 |

### 14.4 end 中的常见字段

| 字段或路径 | 含义与单位 |
| --- | --- |
| `streams[].sender`, `streams[].receiver` | 每条流的发送端／接收端整轮汇总对象，内部仍有起止时间、字节数和速率。 |
| `sum_sent`, `sum_received` | 正向流两端的整轮统计；同一方向，不是一个代表上行、另一个代表下行。 |
| `sum_sent_bidir_reverse`, `sum_received_bidir_reverse` | 双向反向流两端的整轮统计。 |
| `retransmits`, `reorder` | 发送端整轮重传、累计乱序计数。 |
| `max_snd_cwnd`, `max_snd_wnd` | 整轮观测到的最大拥塞／发送窗口，字节。 |
| `max_rtt`, `min_rtt`, `mean_rtt` | 整轮 RTT 观测的最大、最小、平均值，微秒。 |
| `cpu_utilization_percent.host_total`, `host_user`, `host_system` | 本机也就是手机端的总／用户态／内核态 CPU 使用统计，百分数。 |
| `cpu_utilization_percent.remote_total`, `remote_user`, `remote_system` | 对端 CPU 使用统计，百分数；异常结束时不能把缺失汇总中的 0 当作可靠 CPU 观测。 |
| `sender_tcp_congestion`, `receiver_tcp_congestion` | TCP 拥塞控制算法名称。 |

汇总对象内的 `sender` 布尔值仍反映手机在对应方向的角色。两端字节数或统计时长可略有差异，分析时先声明使用手机端区间还是发送／接收端汇总，再保持口径一致。原始 TCP 附加字段受运行系统、构建和 iperf 版本影响，不保证每条都有。

## 15 怎么读取和关联

### 15.1 最简读取示例

下面只展示如何读取真实速率行，未替代整次行程完整性检查：

```python
import json

with open("测试数据.jsonl", encoding="utf-8") as f:
    for line in f:
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get("type") == "sessions":
            config = row.get("config")
            config = json.loads(config) if isinstance(config, str) else config
        if row.get("type") == "samples":
            direction = row.get("direction")
            if direction is None:
                direction = {1: "phone_upload", 0: "phone_download"}.get(row.get("sender"))
            value = row.get("bits_per_second")
            mbps = None if value is None else value / 1_000_000
            print(row.get("session_id"), row.get("attempt_id"),
                  row.get("seq"), direction, mbps)
```

`config` 和各种 `raw` 外面有引号与转义，是因为数据库里保存的是字符串。比如 `raw = json.loads(row["raw"])` 后，`telemetry` 的坐标在 `raw["location"]` 或 `raw["latitude"]`，网络在 `raw["network"]`。解析 `events.raw` 要捕获非 JSON 文字；不要对所有 `detail` 强制 JSON 解析。`telemetry.raw.radio.raw` 可能还需第三次解析，但一般使用 `radio` 对象的字段即可。

### 15.2 推荐的处理顺序

1. 先看首行 `metadata`，识别普通、本地行程或网页分块下载；保留文件来源、设备安装标识或行程 ID。
2. 网页行程下载先检查 `complete`、块号与摘要，再读取每块。缺块应保留缺口，不能默默补成连续测量。
3. 同一行程的 `sessions` 按最大块号取最新快照；其余行按 `(type, id)` 去重，跨数据源增加设备和会话范围。
4. 以 `(session_id, seq, direction)` 识别当前 APP 的速率行；保留 `attempt_id`。双向同 `seq` 不应互相覆盖。
5. 优先使用样本中已复制的位置、无线值；需完整内容时，分别关联 `location_id → locations.id`、`radio_observation_id → radio_observations.id`，同时校验 `session_id`。
6. 按相应单调时间或行程时间排序，加入标签时间线与连接事件；区分缺失时段、真实零速率和旧值沿用。

### 15.3 推荐分析列

分析表至少保留：行程或会话标识、`attempt_id`、`seq`、`direction`、`journey_start_s`、`journey_end_s`、`received_utc_ms`、`seconds`、`bytes`、`bits_per_second`、`label_at_receive`、`network_type`、`vpn_active`、经纬度、`location_age_ms`、`radio_rat`、NR 与 LTE 各自指标和时间戳。保留原始 JSONL，分析表是派生结果。

## 16 数值换算与常见误读

| 项目 | 正确解释 |
| --- | --- |
| `bits_per_second=8000000` | 8 Mbps，约 1 MB/s。Mbps 用十进制百万，不是 MiB/s。 |
| `bytes=625000`, `seconds=1` | 这一方向约 5 Mbps；实际报告可能因计时与取整略有差异。 |
| 多个区间的方向均速 | 同一方向有效区间总字节数乘 8，再除以这些区间 `seconds` 之和；不要对不同长度区间直接取算术平均。 |
| 整个行程的平均速率 | 先声明分母。网页平均是有效测速区间均速，不包括无结果的离线时长；按全行程墙钟时间计算是另一指标。 |
| 只有 `telemetry` 而无 `samples` | 有环境采集，不等于有带宽结果；其 `throughput_bps=null` 不改为 0。 |
| `null` 或旧版本缺字段 | 未取得、未采集、不适用或未知，不能解释为真实零值。 |
| 数值 0 | 可能是有效零速率、0 dB、0 号 PCI、0 速度、合法经纬度、布尔假值，或特定字段的设置／未知约定；应逐字段判断。 |
| `location_age_ms=45000` | 引用的位置在该次观测时已有 45 秒，不能将它当作列车这一刻的精确位置。 |
| `nr_ss_rsrp_dbm=-95` 与 `-115` | 前者接收到的参考信号功率更强；不能仅凭一个指标推出吞吐一定更大。 |
| `nr_ss_sinr_db=0` | 可以是有效的 0 dB，不是缺失。 |
| 多行相同 PCI 或信号值 | 可能是同一观测被多次关联，也可能是不同观测的值相同；结合观测编号和时间判断。 |
| `radio_rat=NR` 同时 `network_type=wifi` | 手机仍可观测蜂窝信号，但该段测速默认网络是 Wi-Fi，不能直接归因于 5G。 |
| 地图连线与抽稀 | 网页只按有效定位画大致轨迹，定位缺口会分段，长轨迹显示点会抽稀；下载文件仍保留原始序列。 |

例如两个上行区间：第一个 1 秒传 1000000 字节，第二个 3 秒传 0 字节。正确的有效区间均速为 8 × 1000000 ÷ 4 = 2000000 bit/s，也就是 2 Mbps；直接平均 8 Mbps 与 0 Mbps 得到的 4 Mbps 是错误口径。

本工具测得的是手机到所选服务器路径上的 TCP 吞吐表现，会受限速、信号、干扰、网络切换、服务器与路径、VPN、同时上传等影响。它不是运营商无线空口的理论容量，也不能从一个测试值分离出每一种原因。

## 17 版本与核对依据

本说明以当前实际保存和导出代码为准：`Store.java`、`JourneyChunks.java`、`JourneyRun.java`、`MeasureService.java`、`RadioRecorder.java`、`NetworkRecorder.java`、`LocationRecorder.java` 以及服务器 `app.py`、`journeys.py`。已核对早期手机 schema 1 文件、普通 schema 4 文件、本地行程、原始块与网页整次行程下载。

示例和回归日志中含模拟器或测试注入的位置，不能将这些文件当作真实高铁数据集，也不能用模拟器中字段存在来证明小米真机会返回每一种 SINR。真机没有上报的字段仍应保留 `null`。

JSONL 格式会随版本扩展：旧文件保留原始字段与原有位置关联，不会因为安装新版而重新测出历史 GPS 或 SINR。读取程序应允许缺字段，并保留无法识别的新字段，避免静默丢失数据。

v0.7.3 补充：行程 config 新增 `zero_progress_timeout_ms`（无字节进展检测时限，毫秒）。`attempt_events.raw.state` 新增 `data_stalled`，其 `detail` 是 JSON 字符串，包含 `reason="no_byte_progress"`、`direction_mask`（1 上行、2 下行、3 双向）、`timeout_ms`、`upload_age_ms`、`download_age_ms`。年龄表示距最近一次有字节进展的时间，仅解释测量方向对应的值。该事件表示 APP 因停滞主动重建测速连接，不证明当时无线空口带宽为零；参见 [v0.7.3 说明](TUNNEL-v0.7.3.md)。重复的原生区间在 events 中保留，samples 不再重复累计。

外部接口说明可参照文中 Android 官方链接及 [iperf 官方说明](https://software.es.net/iperf/invoking.html)。iperf 网页手册可能随版本更新，原始 `raw` 字段的最终解释应结合文件内 `version`、APK 的构建提交和该版本源码。
