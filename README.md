<div align="center">

# ⛵ 青舟 QingZhou

**轻舟已过万重山 —— 局域网/热点下极速、安全、好看的安卓互传**

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84.svg)]()
[![HyperOS](https://img.shields.io/badge/%E6%BE%8E%E6%B9%83OS-1%20%2F%202%20%2F%203-FF6900.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF.svg)]()
[![Version](https://img.shields.io/badge/version-0.1.0-00838F.svg)]()

两台设备连上同一 WiFi 或热点 → **1 秒互相发现 · 毫秒级送达消息 · 榨干链路传输文件**<br>
全程 Noise 端到端加密 · 澎湃超级岛适配 · WiFi+USB 有线并行（实验）

</div>

---

## ✨ 特性

| | 能力 | 实现要点 |
|---|---|---|
| 📡 | **极速发现** | UDP 定向广播/多播 + 网关与网段 `.1` 直连探测，零广播等待 |
| ⚡ | **微消息** | 已配对设备 UDP 单包 AEAD 直达（免握手，毫秒级），抗重放窗口 |
| 🚀 | **高速传输** | 4MB 分块 × 多 TCP 流并行 + 逐块 AES-256-GCM（ARMv8 硬件）+ SHA-256 全文件校验 + 断点续传 |
| 🔐 | **端到端加密** | 手写 Noise Protocol（XX 配对 / IK 1-RTT 复用），指纹短码人工核对防中间人 |
| 🏝 | **超级岛** | 四级降级：OS3 岛 → OS2 焦点通知 → Android 16 Live Updates → 进度通知；可选 Shizuku 兼容模式 |
| 🧵 | **有线并行（实验）** | USB-C 直连双机，WiFi + AOA 链路同时传块，吞吐≈两链路之和 |
| 💬 | **聊天界面** | 类微信会话（文本/文件/转发/长按菜单），聊天记录本地持久化 |
| 📊 | **速率曲线** | 每次传输的实时速率折线图（终态按真实起止归一，快慢文件皆准） |
| 📤 | **系统分享** | 任意应用「分享 → 青舟 → 点设备即发」 |
| 🔌 | **无网直连** | App 内一键自建热点 / WiFi Direct / BLE 存在感知 |
| 🧊 | **玻璃拟态 UI** | Jetpack Compose，多层半透明 + 渐变描边 + 柔光斑，四分区导航 |
| 🛡 | **Shizuku 增强** | 链路体检（dumpsys）等能力检测到即解锁，无则优雅隐藏 |

## 🚀 快速上手

1. 两台设备安装 APK，打开青舟（允许通知/附近设备权限）
2. 连同一 WiFi，或一台开热点另一台连接 —— **1 秒内互相可见**
3. 点设备 → 配对（核对双方配对码）→ 发消息 / 发文件 / 系统分享进来直接点设备发送
4. 文件保存在 `Download/QingZhou`，收件箱可一键打开或定位到文件夹

```bash
./gradlew :app:assembleDebug               # 构建 Debug APK
./gradlew :core:crypto:testDebugUnitTest   # Noise 加密层单元测试
```

> 要求 JDK 17+、Android SDK Platform 37。中文路径工程已放行（`android.overridePathCheck`）。

## 🧭 架构

```
┌─────────────── UI（Compose 玻璃拟态，单 Activity 四分区）───────────────┐
│        发现 Discover      传输 Transfer      收件箱 Inbox    设置 Settings │
├───────────────────────────── service（前台守护 · 通知中心 · 超级岛）─────┤
│  DiscoveryEngine   SessionManager(Noise)   MicroMessenger   FileTransferEngine
│  定向广播/直连探测    XX配对/IK会话/配对码     UDP AEAD 微消息   分块×多流×AEAD
│                                          + USB AOA sink（有线并行）      │
├───────────────────────────── core：crypto(Noise·BC) · data(JSON 持久化) ─┤
└──────────────────────────────────────────────────────────────────────────┘
```

**舟协议 v1**：UDP 39527 发现 / TCP 39528 控制 / UDP 39529 微消息 / TCP 39530+USB 数据。
数据块自带 `(fileIdx, chunkIdx)`，接收端位图乱序聚合 —— 任何链路（WiFi 多流/USB）送达即计入，
这是有线无线并行能以极小改动落地的关键。详见 [docs/02-总体规划](docs/02-总体规划.md)。

## 🔒 安全模型

- 首次配对：Noise XX 握手 + 双方屏幕核对同一 6 位配对码（防中间人），密码学身份与广播身份不一致直接拒绝
- 陌生设备的会话请求一律拒绝；**绝不零点击自动接收文件**（仅已配对设备可开自动接收）
- 身份私钥经 AndroidKeyStore 加密落盘；微消息密钥由双方静态 DH 派生
- 测试保护：加密层 8 项单元测试（双向握手/篡改检测/MITM/往返）

## 📚 文档

- [docs/01-调研报告](docs/01-调研报告.md) —— 传输/发现/安全选型依据与排除项（BBR/KCP/QUIC 等）
- [docs/02-总体规划](docs/02-总体规划.md) —— 架构 · 舟协议 · 里程碑与实施状态
- [docs/03-USB多链路并行方案](docs/03-USB多链路并行方案.md) —— AOA 双角色链路与兼容性策略

## 🗺 路线图

- [x] M0-M4：骨架 / 加密与发现 / 分块多流传输 / 超级岛与 Shizuku / 无网直连
- [x] 真机迭代：聊天界面、速率曲线、系统分享、日志系统、分享直达
- [x] USB 有线并行骨架（真机验证中）
- [ ] v1.1：USB 真机联调 · 慢链路动态权重 · 超级岛官方权限申请
- [ ] v2：QUIC 实验通道 · LocalSend 协议兼容

## 📄 开源协议与商业授权（请务必阅读）

本项目采用 **GPL-3.0 双重许可**：

- **开源使用（默认）**：任何人可自由使用、修改、分发，但**衍生作品必须以 GPL-3.0 开源**，且**必须保留对原作者 [SrQingChen](https://github.com/SrQingChen) 的署名与版权声明**（不得声明为完全自主开发）。
- **商业闭源（书面豁免）**：企业如需闭源使用，须向作者**递交书面申请**获得商业授权（详见 [COMMERCIAL.md](COMMERCIAL.md)）。商业授权**不豁免署名义务**：仍须在产品或文档中注明"基于 SrQingChen《青舟 QingZhou》"。

> 说明：GPL 的开源义务在"再分发"时触发；纯个人本地使用与修改不受约束——这是自由软件的底层共识，任何主流协议均无法（也不应）强制私有修改公开。若你的场景含网络服务改造，可联系作者评估 AGPL 选项。

## 🙏 致谢

- [BouncyCastle](https://www.bouncycastle.org/) —— Noise 原语（X25519 / ChaCha20-Poly1305）
- [Shizuku](https://github.com/RikkaApps/Shizuku) —— 增强能力通道
- [Jetpack Compose](https://developer.android.com/jetpack/compose) —— 声明式 UI
- 构建骨架与工程经验来自作者另一作品（尘露），超级岛接入路线参考社区逆向资料

---

<div align="center">

**作者 [SrQingChen](https://github.com/SrQingChen)** · 如果青舟帮到了你，欢迎点一个 ⭐ Star

⛵ *轻舟快渡，青出于蓝。*

</div>
