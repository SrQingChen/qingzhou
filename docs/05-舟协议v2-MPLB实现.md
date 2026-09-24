# 青舟 舟协议 v2 · MPLB 实现文档

> 2026-09-24 · 作者：SrQingChen
> 本文是 [docs/04](04-多路径并行传输优化调研.md) 调研的落地实现：D1-D8 全部缺陷修复 + NCM usb0 有线链路 + 自研 MPLB（Multi-Path Link Bonding）多路径传输核心。
> 版本 v0.2.0；对旧版对端（v0.1.x）完全回退 v1 行为，双向兼容。

---

## 一、缺陷修复对照表（docs/04 §1.2 → 实现）

| # | 缺陷 | 修复 | 位置 |
|---|---|---|---|
| D1 | 丢块无重传 | **块级补发协议**：接收端收尾扫描内存位图得缺失清单，经控制通道 `fileRepair` 定向补发（≤6 轮，2 轮无进展放弃）；传输中 SACK 位图周期上报，发送端租约超时/链路死亡自动回收重派。拔线/坏块从致命错误降级为一次补发轮 | `FileTransferEngine.handleOffer` 补发循环 + `LeaseScheduler.onRepairMiss/sweep/laneDead` |
| D2 | 无每链路信用 | **租约调度**：块出队挂 (lane, deadline)；每链路 inflight ≤ credit 才派块（接收端按各 lane 消费速度 ×2 + 8MB 底数动态下发 `credit` 帧，BLEST 思想）；EWMA goodput 自适应截止期 | `LeaseScheduler` + 接收端 `laneConsumption()` |
| D3 | 位图每块全量重写 | 位图常驻内存；落盘节流为每 64 块或 2s 一次 + 完成强制；控制通道传输用位压缩（8 块/字节） | `ReceiverState.maybeFlushBitmaps` + `SackCodec` |
| D4 | 发送端全量暂存 | **零暂存直读**：SAF URI 经 `openAssetFileDescriptor` 取 seekable pfd → FileChannel 按 (offset,len) 直读（position 探测失败回退暂存）；token 改用 (name,size,mtime) 派生；SHA-256 发送后单遍补算，经数据面 `fileHashes` 帧送达 | `prepareSource` + `SourceHandle` + `sendHashesFrame` |
| D5 | 接收端双写 | **MediaStore pending 行直写**：接收即对 pending 行 pfd 通道 position+write；校验通过仅翻 `IS_PENDING=0`，删除 .part 拷贝；恢复/新建/翻转任一失败回退 .part 路径 | `PendingUriSink` + `tryPrepareDirect` |
| D6 | Host 侧 AOA fd 流 | Host 侧优先 `claimInterface` + **UsbRequest 异步队列**（16KB × 12 深度，完成泵分发，信号量背压）；失败回退 openAccessory fd。AOA 与 NCM 互斥自动避让 | `UsbBulkChannel` + `UsbAccessoryLink.tryBindBulkAsHost` |
| D7 | USB 块粒度粗 | 异步队列摊薄 16KB syscall；USB 侧写缓冲 256KB；信用上限约束 USB 突发在飞量 | `UsbBulkChannel` + `usbAoaWorker` |
| D8 | WifiLock 废弃常量 | 传输锁显式 `WIFI_MODE_FULL_LOW_LATENCY`（与 HIGH_PERF 实际同映射），源码留档官方吞吐提示 | `WifiLocks.holdTransfer` |

## 二、NCM usb0 有线链路（本次吞吐天花板跃迁）

依据 docs/04 §2.2：`svc usb setFunctions ncm` 经 Shizuku（shell uid 持 MANAGE_USB）即可执行；gadget 被枚举后 Tethering 模块自动对 usb0 起 DHCP 服务端 + NAT；Android 15+ host 手机框架把 usb0 当 ETHERNET transport 自动跟踪（13/14 默认不跟踪 → 回退 AOA）。

### 2.1 组件

- `UsbNetLink`（core/network/usb）：
  - **角色探测**：`USB_STATE` 广播（本机为 gadget 侧）+ `NetworkRequest(TRANSPORT_ETHERNET)` 过滤 `usb\d+` 接口（本机为 host 侧），暴露 ethNet/ethIp/ethGateway（= gadget 的 usb0 地址）/gadgetIp/linkSpeed；
  - **gadget 切换**：Shizuku `svc usb setFunctions ncm`（切换前记录原始 functions，拔线/停止自动还原，不劫持用户 USB 口）；`svc usb getUsbSpeed` 读实际协商速率（SuperSpeed → 3 条 usb0 车道，否则 2 条）。
- 免 Shizuku 手动路径：gadget 侧用户在系统设置开「USB 网络共享」→ host 侧 ethNet 照样就绪 → 车道直接建立。

### 2.2 协商协议（防 USB 口被无关设备劫持）

NCM 切换**必须经控制通道双向确认**，能力位（`Cap.USB_NET=16`）仅表示"可能支持"：

```
sender                              receiver
fileReady{v:2, wired{role,szk,usb0,eth}, ncmReq} ──→
                                    我是 gadget+Shizuku 且对端请求 → setFunctions ncm → 等 usb0 IP
        ←──── fileGo{v:2, wired{...}, ncmGo}
我是 gadget 且对端（host，A15+）要求切换 → setFunctions ncm → 等 wiredUp 帧
        ←──── wiredUp{ip}（host 侧 DHCP 拿到地址后由 SACK 上报器补发）
```

- **sender 是 host**：usb0 车道 = `ethNet.socketFactory` 建 socket → gadget 的 usb0 地址（fileGo.wired.usb0 或本机 ethGateway）；
- **sender 是 gadget**：usb0 车道 = 普通 socket → host 的 usb0 地址（wiredUp 帧异步送达；内核 connected route 保证走 usb0 出接口）；
- AOA 与 NCM 互斥：任一侧计划 NCM 时不再启用 AOA sink；host 侧枚举到 CDC/NCM/RNDIS 类设备时**绝不发送 AOA 切换请求**（防止把刚切好的网络设备打回 accessory）。

## 三、MPLB 核心（租约调度 + 接收端信用 + SACK + 补发 + 尾块冗余）

### 3.1 分层（落地形态）

```
应用层    offer/accept/fileReady/fileGo/inbox（不变，v1 兼容）
─────────────────────────────────────────────
调度层    LeaseScheduler：租约(块,lane,deadline) · 每链路信用 · SACK 释放 · 过期回收 · 尾块复制
          （接收端每 2s 上报 sack{f,bits} + credit{lane:bytes}）
─────────────────────────────────────────────
链路实现  WiFi TCP ×N · USB AOA bulk 队列(fd 兜底) · usb0/NCM TCP ×2-3
─────────────────────────────────────────────
数据面    块帧 (fileIdx, chunkIdx, len, AEAD)（v1 兼容）+ hello 追加 lane 字节（v2）
```

### 3.2 关键机制

1. **块租约**：`lease(lane)` 派块挂截止期（预期送达时间×4，按 EWMA 自适应，钳 1.5-25s）；sweeper 每 500ms 回收过期租约回队首并 EWMA 减半惩罚 —— stall 链路（拔线/卡死）不再囤死块。
2. **每链路信用**：接收端按 lane 消费量下发 credit（×2 + 8MB，8-48MB 钳制）；发送端 inflight ≤ credit 才派块 —— 速率比例分配自动成立。
3. **SACK**：位压缩位图（8 块/字节）周期上报；发送端据此释放租约/更新 EWMA。
4. **补发闭环**：接收端收尾扫缺失 → `fileRepair{miss:[[f,c]…]}` → 发送端 `onRepairMiss` 强制回队（活动 worker 直接取走；全部退出则开补发车道）→ 循环到位图全满。
5. **尾块冗余复制**：剩余 <5% 且双车道时，`dupLease` 允许第二车道复制在飞未确认块（每车道 ≤2 块，信用放宽 8MB）—— 用 <5% 冗余换尾部时延，替代 FEC。
6. **nonce 覆盖 lane**（安全硬要求）：v2 nonce = salt(4) + fileIdx(3) + chunkIdx(4) + lane(1)。补发/复制会跨链路重复加密同一块，lane 入 nonce 杜绝 (key,nonce) 复用；同链路重发产生相同密文（明文相同的确定性重加密），仅泄露等价性——已是公开信息（chunkIdx）。
7. **fileHashes 走数据面**：零暂存源的哈希经短连接（WiFi→usb0→AOA 三级兜底）写入 `FRAME_HASHES` 帧送达，避免接收端控制通道读写争用；每轮补发车道顺路重送。
8. **校验期心跳**：接收端大文件哈希期间每 30s 发 `ping`，防发送方控制通道 150s 读超时误判。

### 3.3 双向兼容

| 组合 | 行为 |
|---|---|
| v2 → v2 | MPLB 全量（SACK/信用/租约/补发/冗余/零暂存/直写/NCM） |
| v2 → v1 | 完整 v1 路径：暂存 + ChunkScheduler + 旧 nonce + 无补发（resume 位图按 v1 每块一字节格式） |
| v1 → v2 | v1 接收语义（无 lane/补发）；接收端本地优化（内存位图/直写）仍生效 |

能力位：`Cap.USB_NET=16`、`Cap.V2=32`；发现 announce 与 TCP register 均已携带。

## 四、其他修正

- **FGS**：接收守护服务类型改 `dataSync|connectedDevice`（USB 外设交互符合语义，规避 A15+ dataSync 6h 配额强停）。
- **expectedStreams 韧性**：接收端完成判定新增「位图收满即完成」——承诺车道未开（如 gadget 侧 wiredUp 未达）也不必干等 30s 空转。
- **设置**：新增「USB 网络链路 NCM（需 Shizuku）」开关（默认开，协商确认后才切换）。

## 五、验证清单（真机，衔接 docs/04 §五）

| 实验 | 预期 |
|---|---|
| 双机 v2 纯 WiFi 大文件 | SACK/信用帧出现在日志（xfer 标签）；速率曲线应比 v1 更平滑（慢流不再拖尾） |
| USB-C 直连 + 一侧 Shizuku（A15+ host） | 日志出现 `usb0 车道：… ×2/×3`；`svc usb getUsbSpeed` 记录协商速率 |
| 传输 30% 时拔 USB 线 | v1：整任务失败；v2：`补发轮 #1` 后完成 |
| 10GB 级文件发送 | 发送侧 cache 不再出现 send_src 暂存（零暂存生效）；接收侧 Download/QingZhou 直出 |
| AOA 对比 usb0 | 同机型 AOA ~35-42MB/s；usb0 视协商速率（HS ~30-40、SS 预期 >100MB/s，待实测） |

## 六、代码索引

| 模块 | 文件 |
|---|---|
| 调度核心 | `core/network/transfer/mplb/LeaseScheduler.kt`、`SackCodec.kt`（+ 各自单测） |
| 引擎 | `core/network/transfer/FileTransferEngine.kt`（sendV2/补发/直写/零暂存/ChunkSink） |
| USB | `core/network/usb/UsbBulkChannel.kt`、`UsbNetLink.kt`、`UsbAccessoryLink.kt`（bulk + NCM 守卫） |
| 模型 | `core/model/Protocol.kt`（Cap）、`TransferModels.kt`（FileMeta.mtimeEpoch） |
| 服务 | `service/QzTransferService.kt` + app manifest（connectedDevice） |
