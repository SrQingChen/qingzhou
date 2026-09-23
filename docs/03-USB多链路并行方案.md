# 青舟 有线+无线并行传输方案（v1）

> 2026-09-23 · 作者：SrQingChen
> 目标：WiFi 与 USB-C 同时传输，吞吐 ≈ 两链路之和，且 USB 不可用时零感知回退。

## 路线结论（为什么是 AOA）

| 路线 | 结论 |
|---|---|
| USB 网络共享 + usb0 网卡 | ❌ 配置系统网络栈需 root/shell，App 层不可达 |
| adb over USB | ❌ 依赖设备调试模式，不可作为产品路径 |
| **AOA 配件模式（Android Open Accessory）** | ✅ 双手机 USB-C 直连：Host 侧 App 经 control transfer 请求对端进入 accessory 模式；双端各持 FileDescriptor 全双工读写。USB2 bulk 实测 ~30-40MB/s，与 WiFi 完全独立 → 真实叠加 |

## 架构设计

**关键洞察（零协议重构）**：数据块帧自带 `(fileIdx, chunkIdx)`，接收端位图天然乱序聚合 —— **任何链路送达的块都自动计入**，接收端几乎零改造；ChunkScheduler 单游标多消费者天然支持多 sink 并发取块。

```
发送端                          接收端
fileReady{streams:4, usb:1} ──→ completion 期望 = 4+1
  ├─ TCP sink ×4（hello应答版）
  └─ USB sink ×1（纯写流，无应答）──→ 常驻 USB 读循环（与 TCP 同一 handleSinkStream）
```

**USB sink 纯写设计**（解决 fd 读写争用）：AOA fd 全双工，但两端各自的"常驻接收循环"占用读端。USB 发送 sink 只写不读（hello 无应答、AEAD 校验即认证），双端同时互发也安全。sink 异常时 best-effort 补写 bye，保证接收端完成计数闭环；另有 30s 无进展空闲保险，避免等满 10 分钟兜底。

## 兼容性策略（保证效果的同时最大化兼容）

1. **能力协商先行**：announce 能力位新增 `Cap.USB=8`，仅双端新版 + USB 已连接 + 设置开启三条件齐备才启用，否则纯 WiFi（行为与现在完全一致）
2. **AOA 探测全 try-catch 降级**：Host 枚举/切换请求/权限任一步失败 → 记日志、静默回退
3. **旧版对端**：不认识 `usb` 字段则 completion 只算 TCP streams —— 发送端也只在 `peer.capabilities & Cap.USB` 时才发 USB 块，互洽
4. **HyperOS 适配**：accessory attach 用 manifest filter + 系统弹窗授权；USB 权限单独 requestPermission

## 实施状态

- [x] `core:network/usb/UsbAccessoryLink`：双角色检测、Host 侧 accessory 切换请求、fd 生命周期、常驻接收循环
- [x] 引擎 sink 抽象：TCP/USB 统一 chunk 循环、fileReady.usb 协商、接收端空闲保险
- [x] 设置开关「USB 有线加速」（默认开）
- [ ] **待真机验证**：Host 侧 controlTransfer 切换在澎湃OS 的行为、双端同时互发、线缆方向无关性（两端都实现双角色即可）、聚合吞吐实测

## v1.1 展望

接收端多链路负载反馈（慢链路少分块的动态权重）；USB 最大传输块对齐（bulk buffer 16KB 对齐实验）。
