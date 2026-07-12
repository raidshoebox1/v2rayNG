# v2rayNG + EasyTier Mesh VPN 插件 — 技术文档

> **版本**: 1.0  
> **更新日期**: 2026-07-12  
> **项目路径**: `v2rayNG/`  
> **插件模块**: `v2rayNG/easytier-plugin/`

---

## 目录

1. [功能概述](#1-功能概述)
2. [架构设计](#2-架构设计)
3. [新增模块：easytier-plugin](#3-新增模块easytier-plugin)
4. [v2rayNG 代码修改详解](#4-v2rayng-代码修改详解)
5. [内网地址冲突排查与修复](#5-内网地址冲突排查与修复)
6. [构建与 CI/CD](#6-构建与-cicd)
7. [配置指南](#7-配置指南)
8. [数据流详解](#8-数据流详解)
9. [维护指南](#9-维护指南)
10. [已知限制与未来工作](#10-已知限制与未来工作)

---

## 1. 功能概述

### 1.1 新增功能

本插件为 v2rayNG 增加了 **EasyTier mesh VPN 内网穿透** 能力。启用后，用户可以在使用 v2rayNG 翻墙的同时，访问 EasyTier 组成的大局域网内任意一台主机。

具体能力：

| 能力 | 说明 |
|------|------|
| **Mesh 内网穿透** | 通过 EasyTier 的 P2P/中继网络连接到远程内网主机 |
| **与 VPN 翻墙共存** | EasyTier 以 no-tun + SOCKS5 模式运行，不竞争 Android VpnService |
| **自动路由** | Xray-core 自动将内网 CIDR（10.0.0.0/8、172.16.0.0/12、192.168.0.0/16）及动态 mesh CIDR 路由到 EasyTier |
| **VPN 模式兼容** | 修复了 v2rayNG "跳过内网地址" 功能与 EasyTier 的路由冲突 |
| **Root 模式兼容** | 修复了 Root 模式 iptables bypass 规则和 LAN 共享策略路由与 EasyTier 的冲突 |
| **自定义配置兼容** | 标准配置和自定义 JSON 配置均支持 EasyTier 注入 |
| **独立设置页面** | 在 v2rayNG 设置中新增 EasyTier Mesh VPN 配置入口 |

### 1.2 使用场景

```
用户手机
  ├── v2rayNG (VPN)
  │   ├── Xray-core → 翻墙流量 → 国外代理服务器
  │   └── EasyTier outbound → 内网流量 → EasyTier mesh → 远程内网主机
  └── EasyTier (no-tun, SOCKS5 :10852)
        ├── Peer 1: tcp://server1:11010
        ├── Peer 2: udp://server2:11010
        └── ...
```

用户可以在翻墙的同时，访问公司内网 `10.x.x.x` 或家庭网络 `192.168.x.x` 中的设备，这些流量通过 EasyTier mesh 网络路由到目标主机。

---

## 2. 架构设计

### 2.1 核心问题

Android 只允许一个 app 同时持有 VpnService。v2rayNG 需要VpnService 来创建 TUN 接口，EasyTier 原生也需要 TUN fd。如果两者都请求 VpnService，会冲突。

### 2.2 解决方案：No-TUN + SOCKS5 模式

```
┌─────────────────────────────────────────────────────────┐
│                      v2rayNG App                         │
│                                                          │
│  ┌────────────────┐      ┌────────────────────────────┐ │
│  │   Xray-core     │      │    EasyTier Plugin         │ │
│  │   (VpnService)  │      │    (no-tun + SOCKS5)       │ │
│  │                  │      │                            │ │
│  │  outbounds:      │      │  EasyTierJNI (native .so) │ │
│  │  - proxy (VMess) │◄─────│  - runNetworkInstance()    │ │
│  │  - direct        │      │  - SOCKS5 :127.0.0.1:10852│ │
│  │  - easytier ★    │      │                            │ │
│  │                  │      │  Mesh Peers:               │ │
│  │  routing:        │      │  - tcp://peer1:11010       │ │
│  │  - LAN CIDRs     │      │  - udp://peer2:11010       │ │
│  │    → easytier ★  │      │  - ws://peer3:11010        │ │
│  │  - other         │      │                            │ │
│  │    → proxy       │      │  Network: my-mesh          │ │
│  └────────────────┘      └────────────────────────────┘ │
│                                                          │
│  ┌────────────────────────────────────────────────────┐  │
│  │         Android VpnService (唯一，由 Xray-core 持有) │  │
│  └────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

**关键设计决策**：

| 决策 | 原因 |
|------|------|
| EasyTier 使用 no-tun 模式 | 避免与 v2rayNG 竞争 VpnService |
| EasyTier 只开 SOCKS5 listener | Xray-core 通过 SOCKS5 outbound 连接 EasyTier |
| EasyTier 在 Xray-core 之前启动 | 确保 SOCKS5 端口就绪后 Xray-core 才尝试连接 |
| EasyTier 在 Xray-core 之后停止 | 确保 Xray-core 不再发送流量到已关闭的 SOCKS5 端口 |
| 使用独立 SharedPreferences | 与 v2rayNG 的 MMKV 存储隔离，降低耦合 |
| `System.loadLibrary` 懒加载 | .so 不存在时优雅降级，不影响 v2rayNG 正常运行 |

### 2.3 EasyTier TOML 配置对应关系

插件生成的 TOML 配置与 EasyTier Rust `Config` 结构体 (`easytier/src/common/config.rs`) 的字段精确对应：

| TOML 字段 | Rust Config 字段 | 说明 |
|-----------|------------------|------|
| `instance_name` | `instance_name: String` | 实例名称 |
| `[network_identity]` | `network_identity: NetworkIdentity` | 网络身份 |
| `network_name` | `network_identity.network_name` | 网络名（所有节点需一致） |
| `network_secret` | `network_identity.network_secret` | 网络密钥 |
| `[flags]` | `flags: Option<Map<String, Value>>` | 标志位 |
| `no_tun` | `flags["no_tun"]` | 禁用 TUN（必须为 true） |
| `mtu` | `flags["mtu"]` | MTU 覆盖 |
| `socks5_proxy` | `socks5_proxy: Option<Url>` | SOCKS5 监听地址 |
| `[[peer]]` | `peer: Vec<PeerConfig>` | 对端列表 |
| `uri` | `PeerConfig.uri` | 对端 URI |
| `ipv4` | `ipv4: String` | 虚拟 IPv4 地址 |

> **注意**：`[console_logger]` 和 `[file_logger]` 不是 `Config` 结构体的字段（它们属于 `LoggingConfig`，仅 CLI 使用）。TOML 中包含它们会被静默忽略（`Config` 没有 `deny_unknown_fields`），但为保持整洁未包含。JNI 层使用 `android_logger` 固定 Debug 级别。

---

## 3. 新增模块：easytier-plugin

### 3.1 文件结构

```
easytier-plugin/
├── build.gradle.kts                         # 模块构建配置
├── consumer-rules.pro                       # ProGuard keep 规则
└── src/main/
    ├── AndroidManifest.xml                  # 声明 EasyTierSettingsActivity
    ├── jniLibs/                             # .so 文件目录（构建时填充）
    │   ├── arm64-v8a/
    │   ├── armeabi-v7a/
    │   ├── x86/
    │   └── x86_64/
    ├── kotlin/com/easytier/
    │   ├── jni/
    │   │   ├── EasyTierJNI.kt               # JNI 原生方法声明
    │   │   └── EasyTierDataPlaneJNI.kt      # 数据面 JNI（预留）
    │   └── plugin/
    │       ├── EasyTierPlugin.kt            # 主插件类
    │       ├── EasyTierConfig.kt            # 配置数据类 + TOML 序列化
    │       ├── EasyTierSettingsManager.kt   # SharedPreferences 管理
    │       └── ui/
    │           └── EasyTierSettingsActivity.kt  # 设置 UI
    └── res/
        ├── layout/easytier_settings_activity.xml
        ├── values/strings.xml               # 中英文字符串
        ├── values-en/strings.xml
        └── xml/easytier_preferences.xml     # PreferenceScreen XML
```

### 3.2 核心类详解

#### `EasyTierPlugin.kt` — 主插件类

**职责**：管理 EasyTier 实例的生命周期，生成 Xray-core 配置片段。

```kotlin
class EasyTierPlugin(private val context: Context) {
    // 生命周期
    fun start(config: EasyTierConfig): Boolean   // 启动 EasyTier 实例
    fun stop()                                    // 停止 EasyTier 实例
    fun isRunning(): Boolean                      // 查询运行状态
    
    // Xray-core 配置生成
    fun buildOutboundJson(): JsonObject?          // SOCKS5 outbound JSON
    fun buildRoutingRules(customCidrs): List<JsonObject>?  // 路由规则 JSON
    fun getSocks5Endpoint(): Socks5Endpoint?      // SOCKS5 地址
    
    // Mesh 信息
    fun getMeshCidrs(): List<String>              // 动态获取 mesh CIDR
    
    companion object {
        const val DEFAULT_SOCKS5_PORT = 10852
        const val OUTBOUND_TAG = "easytier"
        val DEFAULT_LAN_CIDRS = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
        fun getMeshCidrsStatic(): List<String>    // 静态版本，无需实例
    }
}
```

**关键实现细节**：

- `start()` 调用 `EasyTierJNI.parseConfig(toml)` 验证配置，然后 `EasyTierJNI.runNetworkInstance(toml)` 启动实例
- `stop()` 调用 `EasyTierJNI.stopAllInstances()`（即 `retainNetworkInstance(null)`）
- `getMeshCidrs()` 调用 `EasyTierJNI.collectNetworkInfos(10)` 获取 JSON，解析 `routes[].proxy_cidrs` 和 `routes[].direct_cidrs`
- `getMeshCidrsStatic()` 是 companion object 方法，可在无插件实例时调用（需要 EasyTier 实例已在运行）
- 所有 JNI 调用都 catch `Throwable`（`UnsatisfiedLinkError` 继承 `Error` 而非 `Exception`）

#### `EasyTierConfig.kt` — 配置数据类

**职责**：将用户设置序列化为 EasyTier TOML 配置字符串。

```kotlin
data class EasyTierConfig(
    var enabled: Boolean = false,
    var instanceName: String = "v2rayng_plugin",
    var networkName: String = "",
    var networkSecret: String = "",
    var virtualIp: String? = null,
    var peers: List<String> = emptyList(),
    var listeners: List<String> = emptyList(),
    var socks5Port: Int = 10852,
    var noTun: Boolean = true,
    var mtu: Int? = null,
    var logLevel: String = "warn",
)
```

`toToml()` 生成的配置示例：

```toml
instance_name = "v2rayng_plugin"

[network_identity]
network_name = "my-mesh"
network_secret = "secret123"

[flags]
no_tun = true

socks5_proxy = "socks5://0.0.0.0:10852"

[[peer]]
uri = "tcp://public.easytier.top:11010"

ipv4 = "10.144.144.1"
```

`fromMap()` 从 SharedPreferences 的键值对构建 `EasyTierConfig`。

#### `EasyTierSettingsManager.kt` — 设置管理

**职责**：使用 `PreferenceManager.getDefaultSharedPreferences()` 存储 EasyTier 配置，与 v2rayNG 的 MMKV 存储完全隔离。

| 方法 | 说明 |
|------|------|
| `isEnabled(context)` | 是否启用 EasyTier |
| `getNetworkName(context)` | 网络名 |
| `getNetworkSecret(context)` | 网络密钥 |
| `getVirtualIp(context)` | 虚拟 IP |
| `getPeers(context)` | 对端 URI 列表 |
| `getSocks5Port(context)` | SOCKS5 端口 |
| `isNoTun(context)` | 是否禁用 TUN |
| `getEasyTierConfig(context)` | 组合方法，返回 `EasyTierConfig?`（未启用或网络名为空时返回 null） |

所有键以 `easytier_` 前缀存储。

#### `EasyTierJNI.kt` — JNI 桥接

**职责**：声明 EasyTier 原生库的 JNI 方法。

```kotlin
object EasyTierJNI {
    init { System.loadLibrary("easytier_android_jni") }
    
    external fun parseConfig(config: String): Int
    external fun runNetworkInstance(config: String): Int
    external fun retainNetworkInstance(instanceNames: Array<String>?): Int
    external fun collectNetworkInfos(maxLength: Int): String?
    external fun listInstances(maxLength: Int): String?
    external fun setTunFd(instanceName: String, fd: Int): Int
    external fun getLastError(): String?
    // ... 更多 RPC 方法
}
```

原生库名：`libeasytier_android_jni.so`，位于 `easytier-plugin/src/main/jniLibs/<abi>/`。

#### `EasyTierSettingsActivity.kt` — 设置 UI

基于 `PreferenceFragmentCompat`，提供以下设置项：

| 设置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| Enable | Switch | OFF | 启用/禁用 EasyTier |
| Network Name | Text | (空) | EasyTier 网络名 |
| Network Secret | Text (password) | (空) | 网络密钥 |
| Virtual IP | Text | (空) | 虚拟 IP，留空自动分配 |
| Peer Addresses | Text | (空) | 逗号分隔的对端 URI |
| SOCKS5 Port | Number | 10852 | 本地 SOCKS5 端口 |
| No TUN Mode | Switch (锁定 ON) | ON | 禁用 TUN，避免 VpnService 冲突 |
| Log Level | Text | warn | 日志级别 |

### 3.3 ProGuard 规则 (`consumer-rules.pro`)

```proguard
# JNI 桥接类（包名 com.easytier.jni，不是 com.easytier.plugin）
-keep class com.easytier.jni.EasyTierJNI { *; }
-keep class com.easytier.jni.EasyTierDataPlaneJNI { *; }
-keep class com.easytier.jni.ConfigServerEventCallback { *; }
# ... 其他 JNI 数据类

# 插件类
-keep class com.easytier.plugin.EasyTierPlugin { *; }
-keep class com.easytier.plugin.EasyTierPlugin$* { *; }
-keep class com.easytier.plugin.EasyTierConfig { *; }
-keep class com.easytier.plugin.EasyTierConfig$* { *; }
-keep class com.easytier.plugin.EasyTierSettingsManager { *; }
```

### 3.4 模块构建配置 (`build.gradle.kts`)

```kotlin
android {
    namespace = "com.easytier.plugin"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation(libs.gson)
    implementation(libs.preference.ktx)
    implementation(libs.androidx.appcompat)
    // ...
}
```

作为 `implementation(project(":easytier-plugin"))` 被 v2rayNG app 模块依赖。

---

## 4. v2rayNG 代码修改详解

### 4.1 修改文件清单

| # | 文件 | 修改类型 | 行数变化 |
|---|------|----------|----------|
| 1 | `settings.gradle.kts` | 新增 include | +1 |
| 2 | `app/build.gradle.kts` | 新增依赖 | +3 |
| 3 | `app/.../AppConfig.kt` | 新增常量 | +9 |
| 4 | `app/.../core/CoreConfigManager.kt` | 新增注入逻辑 | +141 |
| 5 | `app/.../core/CoreServiceManager.kt` | 新增生命周期管理 | +57 |
| 6 | `app/.../root/RootProxyManager.kt` | 修复 iptables 冲突 | +44 |
| 7 | `app/.../service/CoreVpnService.kt` | 修复 VPN 路由冲突 | +30 |
| 8 | `app/.../ui/SettingsActivity.kt` | 新增设置入口 | +9 |
| 9 | `app/.../res/xml/pref_settings.xml` | 新增 PreferenceCategory | +9 |
| | **合计** | | **+298 / -5** |

### 4.2 各文件修改详情

#### 4.2.1 `settings.gradle.kts`

```diff
+include(":easytier-plugin")
```

将 `easytier-plugin` 模块纳入 Gradle 构建。

#### 4.2.2 `app/build.gradle.kts`

```diff
+    // EasyTier Plugin
+    implementation(project(":easytier-plugin"))
```

app 模块依赖 easytier-plugin 模块。

#### 4.2.3 `AppConfig.kt`

新增 EasyTier 相关 SharedPreferences 键常量：

```kotlin
const val PREF_EASYTIER_ENABLED = "easytier_enabled"
const val PREF_EASYTIER_NETWORK_NAME = "easytier_network_name"
const val PREF_EASYTIER_NETWORK_SECRET = "easytier_network_secret"
const val PREF_EASYTIER_VIRTUAL_IP = "easytier_virtual_ip"
const val PREF_EASYTIER_PEERS = "easytier_peers"
const val PREF_EASYTIER_SOCKS5_PORT = "easytier_socks5_port"
const val PREF_EASYTIER_NO_TUN = "easytier_no_tun"
```

#### 4.2.4 `CoreConfigManager.kt` — Xray 配置注入

**核心修改**：在 Xray-core 配置生成流程中注入 EasyTier outbound 和路由规则。

**新增方法 `injectEasyTier(context, v2rayConfig)`**：

在 `buildUnifiedConfig()` 末尾调用，针对 v2rayNG 管理的配置（非自定义 JSON）：

1. 检查 `EasyTierSettingsManager.getEasyTierConfig()` 是否启用
2. 检查是否已存在 `easytier` outbound（避免重复注入）
3. 添加 SOCKS5 outbound：
   ```kotlin
   V2rayConfig.OutboundBean(
       tag = "easytier",
       protocol = "socks",
       settings = OutSettingsBean(address = "127.0.0.1", port = 10852)
   )
   ```
4. 构建路由规则：`DEFAULT_LAN_CIDRS` + `getMeshCidrsStatic()` → `outboundTag = "easytier"`
5. 规则插入到 `routing.rules` 的 **index 0**，优先于用户规则（包括 `geoip:private → direct`）

**新增方法 `injectEasyTierIntoCustomConfig(context, json)`**：

在 `buildCustomConfig()` 中调用，针对用户自定义 JSON 配置：

1. 解析 JSON 为 `JsonObject`
2. 添加 SOCKS5 outbound（Xray 原生 JSON 格式）：
   ```json
   {
     "tag": "easytier",
     "protocol": "socks",
     "settings": {
       "servers": [{"address": "127.0.0.1", "port": 10852}]
     }
   }
   ```
3. 添加路由规则到 `routing.rules[0]`
4. 重新序列化为 JSON

**两条注入路径的区别**：

| | `injectEasyTier()` | `injectEasyTierIntoCustomConfig()` |
|---|---|---|
| 配置类型 | v2rayNG 管理的配置 | 用户自定义 JSON |
| 数据结构 | Kotlin `V2rayConfig` 对象 | Gson `JsonObject` |
| outbound 格式 | `OutboundBean(address, port)` | Xray 原生 `servers` 数组 |
| 调用位置 | `buildUnifiedConfig()` 末尾 | `buildCustomConfig()` 返回前 |

#### 4.2.5 `CoreServiceManager.kt` — 生命周期管理

**启动顺序**：

```
startCoreLoop()
  ├── startEasyTier(context)        ← 先启动 EasyTier
  ├── coreController.startLoop()    ← 再启动 Xray-core
  └── ...
```

**停止顺序**：

```
stopCoreLoop()
  ├── stopEasyTier()                ← 先停止 EasyTier（Xray-core 可能还有残留流量）
  ├── coreController.stopLoop()     ← 再停止 Xray-core
  └── ...
```

**新增方法**：

```kotlin
private var easyTierPlugin: EasyTierPlugin? = null

private fun startEasyTier(context: Context) {
    val etConfig = EasyTierSettingsManager.getEasyTierConfig(context)
    if (etConfig == null || !etConfig.enabled) return
    try {
        val plugin = EasyTierPlugin(context)
        if (plugin.start(etConfig)) {
            easyTierPlugin = plugin
        }
    } catch (e: Throwable) { /* catch Throwable for UnsatisfiedLinkError */ }
}

private fun stopEasyTier() {
    easyTierPlugin?.let { 
        try { it.stop() } catch (e: Throwable) { ... }
    }
    easyTierPlugin = null
}
```

#### 4.2.6 `CoreVpnService.kt` — VPN 路由冲突修复

**问题**：v2rayNG 的 "跳过内网地址"（bypass LAN）功能在 `configureNetworkSettings()` 中使用 `ROUTED_IP_LIST`，该列表排除了 `10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`。当 EasyTier 启用时，这些 CIDR 的流量不会经过 VPN 隧道，因此无法到达 Xray-core 的 EasyTier outbound。

**修复**：在 `bypassLan == true` 分支中，检查 EasyTier 是否启用，若启用则将 mesh CIDR 重新 `addRoute()` 到 VPN builder：

```kotlin
// 在 ROUTED_IP_LIST 遍历之后追加：
val etConfig = EasyTierSettingsManager.getEasyTierConfig(this)
if (etConfig != null && etConfig.enabled) {
    // 重新添加 LAN CIDR 到 VPN 路由
    EasyTierPlugin.DEFAULT_LAN_CIDRS.forEach { cidr ->
        val parts = cidr.split("/")
        if (parts.size == 2) builder.addRoute(parts[0], parts[1].toInt())
    }
    // 添加动态发现的 mesh CIDR
    EasyTierPlugin.getMeshCidrsStatic().forEach { cidr ->
        val parts = cidr.split("/")
        if (parts.size == 2 && cidr !in EasyTierPlugin.DEFAULT_LAN_CIDRS) {
            builder.addRoute(parts[0], parts[1].toInt())
        }
    }
}
```

#### 4.2.7 `RootProxyManager.kt` — Root 模式冲突修复

**冲突 1：iptables mangle 标记链 (`buildMangleMarking`)**

`bypassCidrs` 列表包含所有私有 IP 范围，这些 CIDR 被标记为 `RETURN`（跳过标记），流量直接走本地路由表，不进入 tun。

**修复**：在 bypass RETURN 规则之后、应用标记规则之前，为 mesh CIDR 添加重新 MARK 的规则：

```bash
# 原有 bypass 规则（流量跳过 tun）：
iptables -t mangle -A $CHAIN -d 10.0.0.0/8 -j RETURN
iptables -t mangle -A $CHAIN -d 172.16.0.0/12 -j RETURN
iptables -t mangle -A $CHAIN -d 192.168.0.0/16 -j RETURN

# 新增 re-MARK 规则（EasyTier mesh CIDR 重新标记进入 tun）：
iptables -t mangle -A $CHAIN -d 10.0.0.0/8 -j MARK --set-xmark $MARK
iptables -t mangle -A $CHAIN -d 172.16.0.0/12 -j MARK --set-xmark $MARK
iptables -t mangle -A $CHAIN -d 192.168.0.0/16 -j MARK --set-xmark $MARK
```

**方法签名变更**：`buildMangleMarking(cmd, appUid, ...)` → `buildMangleMarking(context, cmd, appUid, ...)`，新增 `context` 参数以访问 `EasyTierSettingsManager`。

**冲突 2：LAN 共享策略路由 (`buildLanShareSetup`)**

`buildLanShareSetup` 添加 `ip rule add to 10.0.0.0/8 lookup main pref 5025` 等规则，将目的地为私有 IP 的流量导向 main 路由表（直连），绕过 tun。

**修复**：在 LAN-direct 规则之前添加更高优先级的 mesh CIDR 规则：

```bash
# 新增：mesh CIDR 走 tun 表（pref 5024, 5023, ... 优先于 5025）
ip rule add to 10.0.0.0/8 lookup $TABLE pref 5024
ip rule add to 172.16.0.0/12 lookup $TABLE pref 5023
ip rule add to 192.168.0.0/16 lookup $TABLE pref 5022

# 原有：LAN 直连规则（pref 5025+）
ip rule add to 10.0.0.0/8 lookup main pref 5025
ip rule add to 172.16.0.0/12 lookup main pref 5026
ip rule add to 192.168.0.0/16 lookup main pref 5027
```

**方法签名变更**：`buildLanShareSetup(captureDeviceTraffic, ipv6)` → `buildLanShareSetup(context, captureDeviceTraffic, ipv6)`。

#### 4.2.8 `SettingsActivity.kt` — 设置入口

在 v2rayNG 设置页面添加 EasyTier 入口：

```kotlin
findPreference<Preference>("easytier_settings_entry")
    ?.setOnPreferenceClickListener {
        startActivity(Intent(requireContext(), EasyTierSettingsActivity::class.java))
        true
    }
```

#### 4.2.9 `pref_settings.xml` — 设置 UI

```xml
<PreferenceCategory android:title="EasyTier Mesh VPN">
    <Preference
        android:key="easytier_settings_entry"
        android:title="EasyTier Settings"
        android:summary="Configure mesh network (SOCKS5 mode)" />
</PreferenceCategory>
```

---

## 5. 内网地址冲突排查与修复

### 5.1 冲突全景

v2rayNG 中涉及内网/私有 IP 地址处理的功能共有 5 处，逐一排查：

| # | 功能 | 文件 | 冲突？ | 修复状态 |
|---|------|------|--------|----------|
| 1 | VPN 层 bypass LAN | `CoreVpnService.kt` `configureNetworkSettings()` | ✅ 冲突 | ✅ 已修复 |
| 2 | Root 模式 iptables bypass | `RootProxyManager.kt` `buildMangleMarking()` | ✅ 冲突 | ✅ 已修复 |
| 3 | Root 模式 LAN 共享策略路由 | `RootProxyManager.kt` `buildLanShareSetup()` | ✅ 冲突 | ✅ 已修复 |
| 4 | Xray 路由层 `geoip:private → direct` | `CoreConfigManager.kt` 路由规则 | ⚠️ 低风险 | ✅ 已处理 |
| 5 | Per-App 代理 | `CoreVpnService.kt` per-app 设置 | ❌ 无冲突 | 无需修改 |
| 6 | HevTun 模式 | `CoreVpnService.kt` HevTun 分支 | ❌ 无冲突 | 无需修改 |
| 7 | DNS 配置 | DNS 服务器选择 | ❌ 无冲突 | 无需修改 |
| 8 | IPv6 bypass | `bypassCidrsV6` | ⚠️ 低风险 | 未修改（见 10.3） |

### 5.2 各冲突详解

#### 冲突 1：VPN 层 bypass LAN

**位置**：`CoreVpnService.kt` → `configureNetworkSettings()` → `bypassLan == true` 分支

**机制**：v2rayNG "跳过内网地址"功能使用 `ROUTED_IP_LIST`，该列表包含公网 IP 但排除 `10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`。当 bypass 开启时，这些私有 IP 不被 `addRoute()` 到 VPN builder，流量直接走系统路由表，不经过 Xray-core。

**影响**：EasyTier mesh 网络使用这些私有 IP 段（如 `10.144.144.0/24`），流量不会到达 Xray-core 的 EasyTier outbound。

**修复**：EasyTier 启用时，在 bypass 分支末尾重新 `addRoute()` 所有 mesh CIDR。

#### 冲突 2：Root 模式 iptables bypass

**位置**：`RootProxyManager.kt` → `buildMangleMarking()` → `bypassCidrs` 遍历

**机制**：Root 模式使用 iptables mangle 表标记流量。`bypassCidrs` 列表中的 CIDR 被 `RETURN`（跳过标记），这些流量不进入 tun。

**影响**：EasyTier mesh CIDR 被 bypass，流量不经过 Xray-core。

**修复**：在 bypass RETURN 规则之后添加 re-MARK 规则，将 mesh CIDR 重新标记。

#### 冲突 3：Root 模式 LAN 共享策略路由

**位置**：`RootProxyManager.kt` → `buildLanShareSetup()` → `ip rule add to <CIDR> lookup main`

**机制**：LAN 共享模式下，目的地为 `10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16` 的流量被导向 `main` 路由表（直连），绕过 tun。

**影响**：EasyTier mesh 流量被当作 LAN 直连流量，不经过 tun。

**修复**：在 LAN-direct 规则之前添加更高优先级的 mesh CIDR 规则（pref 5024 递减），将 mesh 流量导向 tun 路由表。

#### 冲突 4：Xray 路由层 geoip:private

**机制**：用户路由配置可能包含 `geoip:private → direct` 规则，将私有 IP 路由到 direct outbound。

**影响**：低风险。EasyTier 路由规则插入在 `routing.rules[0]`，优先于用户规则。且 `geoip:private` 是 IP 规则，EasyTier 规则也是 IP 规则，前者匹配后后者不会执行。

**处理**：无需额外修改。EasyTier 规则已经通过 index 0 插入获得了最高优先级。

#### 无冲突的功能

- **Per-App 代理**：控制哪些 app 的流量进入 VPN，与 IP 层路由无关
- **HevTun 模式**：流量通过 SOCKS inbound 而非 VPN tun 路由，`bypassLan` 的 `addRoute` 不影响 HevTun 流量
- **DNS 配置**：DNS 服务器选择不影响 IP 层路由

### 5.3 冲突修复的调用链

```
CoreServiceManager.startCoreLoop()
  └── startEasyTier(context)
       └── EasyTierPlugin.start(config)
            └── EasyTierJNI.runNetworkInstance(toml)

CoreConfigManager.buildUnifiedConfig()
  └── injectEasyTier(context, v2rayConfig)
       ├── 添加 SOCKS5 outbound
       └── 添加路由规则 (DEFAULT_LAN_CIDRS + meshCidrs → easytier)

CoreVpnService.configureNetworkSettings()
  └── bypassLan == true 时:
       └── 重新 addRoute(DEFAULT_LAN_CIDRS + meshCidrs)  [修复冲突 1]

RootProxyManager.buildMangleMarking()
  └── bypass RETURN 之后 re-MARK mesh CIDRs  [修复冲突 2]

RootProxyManager.buildLanShareSetup()
  └── LAN-direct 规则之前添加 mesh CIDR 高优先级规则  [修复冲突 3]
```

---

## 6. 构建与 CI/CD

### 6.1 本地构建

#### 前置条件

| 工具 | 版本 | 用途 |
|------|------|------|
| Rust | 1.95+ | 编译 EasyTier JNI |
| Android NDK | r21+ | 交叉编译 |
| cargo-ndk | latest | Rust → Android 交叉编译 |
| Android SDK | API 37 | 构建 APK |
| JDK | 21 | Gradle |
| Rust targets | aarch64-linux-android, armv7-linux-androideabi, x86_64-linux-android, i686-linux-android | 四架构 |

#### 编译 JNI 原生库

```bash
# 安装 Rust targets
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android

# 安装 cargo-ndk
cargo install cargo-ndk

# 设置 NDK
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/29.0.14206865

# 编译所有架构
cd v2rayNG
./build-easytier-jni.sh

# 或只编译 arm64
./build-easytier-jni.sh arm64-v8a
```

输出：`easytier-plugin/src/main/jniLibs/<abi>/libeasytier_android_jni.so`

#### 构建 APK

```bash
cd V2rayNG
echo "sdk.dir=${ANDROID_HOME}" > local.properties
./gradlew assembleDebug
```

### 6.2 GitHub Actions CI/CD

**文件**：`.github/workflows/build-easytier.yml`

**触发条件**：
- `push` 到 `master`/`main` 分支（路径: `V2rayNG/**`, `easytier-plugin/**`, `EasyTier/**`, `build-easytier-jni.sh`, workflow 自身）
- `pull_request` 到 `master`/`main`
- 手动触发 (`workflow_dispatch`)

**构建流程**：

```
Job 1: build-jni (matrix: 4 architectures)
  ├── Checkout repo
  ├── Setup Rust toolchain
  ├── Install cargo-ndk
  ├── Setup Android NDK
  ├── ./build-easytier-jni.sh <arch>
  └── Upload .so artifact

Job 2: build-apk (depends on build-jni)
  ├── Checkout repo (with submodules)
  ├── Setup Android SDK + NDK
  ├── Download all .so artifacts → jniLibs/
  ├── Build libhevtun (cached)
  ├── Download libv2ray.aar
  ├── Setup Java 21
  ├── ./gradlew assembleDebug
  └── Upload APKs (per-ABI + universal)
```

**手动触发选项**：
- `build_jni_only`: 只编译 JNI .so，跳过 APK
- `build_apk_only`: 只构建 APK，使用缓存的 .so
- `abi_filters`: 指定 ABI（分号分隔）

### 6.3 构建脚本 (`build-easytier-jni.sh`)

| 参数 | 说明 |
|------|------|
| 无参数 | 编译所有 4 个架构 |
| `arm64-v8a` | 只编译 arm64 |
| `armeabi-v7a` | 只编译 arm32 |
| `x86_64` | 只编译 x86_64 |
| `x86` | 只编译 x86 |

脚本会检查 `cargo-ndk`、`ANDROID_NDK_HOME` 等前置条件，自动安装缺失的 Rust target，编译后将 `.so` 复制到 `easytier-plugin/src/main/jniLibs/<abi>/`。

---

## 7. 配置指南

### 7.1 用户配置

在 v2rayNG 设置 → **EasyTier Mesh VPN** → **EasyTier Settings** 中：

| 设置项 | 必填 | 示例 | 说明 |
|--------|------|------|------|
| Enable | 是 | ON | 启用 EasyTier |
| Network Name | 是 | `my-mesh` | 所有节点需一致 |
| Network Secret | 推荐 | `secret123` | 网络加密密钥 |
| Virtual IP | 否 | `10.144.144.1` | 留空则自动分配 |
| Peer Addresses | 是 | `tcp://public.easytier.top:11010` | 逗号分隔的对端 URI |
| SOCKS5 Port | 否 | `10852` | 默认 10852，不与 v2rayNG 的 10808 冲突 |
| No TUN Mode | — | ON (锁定) | 必须开启，避免 VPN 冲突 |
| Log Level | 否 | `warn` | error/warn/info/debug/trace |

### 7.2 TOML 配置生成

`EasyTierConfig.toToml()` 生成的 TOML 与 EasyTier Rust `Config` 结构体精确对应：

```toml
instance_name = "v2rayng_plugin"

[network_identity]
network_name = "my-mesh"
network_secret = "secret123"

[flags]
no_tun = true

socks5_proxy = "socks5://0.0.0.0:10852"

[[peer]]
uri = "tcp://public.easytier.top:11010"

ipv4 = "10.144.144.1"
```

### 7.3 排除项

以下字段**不包含**在 TOML 中（因为它们不属于 Rust `Config` 结构体）：

| 字段 | 原因 |
|------|------|
| `[console_logger]` | 属于 `LoggingConfig`（CLI 专用），JNI 层使用 `android_logger` |
| `[file_logger]` | 同上 |
| `listeners` 中的 SOCKS5 | SOCKS5 由 `socks5_proxy` 字段控制，不需要在 `listeners` 中声明 |

---

## 8. 数据流详解

### 8.1 启动流程

```
用户点击 v2rayNG 连接按钮
  └── CoreServiceManager.startCoreLoop()
       ├── startEasyTier(context)
       │    ├── EasyTierSettingsManager.getEasyTierConfig(context)
       │    │    └── 读取 SharedPreferences
       │    ├── EasyTierPlugin(config).start()
       │    │    ├── EasyTierConfig.toToml()
       │    │    ├── EasyTierJNI.parseConfig(toml)     ← 验证配置
       │    │    └── EasyTierJNI.runNetworkInstance(toml) ← 启动实例
       │    └── easyTierPlugin = plugin
       ├── coreController.startLoop(config, tunFd)
       │    └── Xray-core 启动，使用注入了 EasyTier outbound 的配置
       └── NotificationManager.showNotification()
```

### 8.2 配置注入流程

```
CoreConfigManager.buildUnifiedConfig()
  ├── ... (v2rayNG 原有配置生成)
  ├── applySpeedDisabled(v2rayConfig)
  ├── resolveOutboundDomainsToHosts(v2rayConfig)
  └── injectEasyTier(context, v2rayConfig)        ← 新增
       ├── EasyTierSettingsManager.getEasyTierConfig()
       ├── 检查是否已存在 easytier outbound
       ├── 添加 SOCKS5 outbound (tag="easytier", 127.0.0.1:10852)
       ├── getMeshCidrsStatic()                   ← 获取动态 mesh CIDR
       └── routing.rules.add(0, easyTierRule)     ← 插入到规则列表最前
```

### 8.3 流量路由

```
App 发出流量
  │
  ▼
Android VpnService (v2rayNG)
  │
  ├── bypass LAN 关闭：全部流量进入 tun
  │
  └── bypass LAN 开启：
       ├── 公网 IP → tun (addRoute)
       ├── 私有 IP → tun (EasyTier 修复：重新 addRoute)
       └── mesh CIDR → tun (EasyTier 修复：动态 addRoute)
  │
  ▼
Xray-core 路由引擎
  │
  ├── 匹配 EasyTier 规则 (rules[0])
  │   ├── 10.0.0.0/8    → easytier outbound
  │   ├── 172.16.0.0/12 → easytier outbound
  │   ├── 192.168.0.0/16 → easytier outbound
  │   └── mesh CIDRs    → easytier outbound
  │
  ├── 用户规则 (rules[1+])
  │   ├── geoip:private → direct (已被 EasyTier 规则优先匹配)
  │   ├── geosite:...   → proxy/direct
  │   └── ...
  │
  └── catch-all → proxy/direct
  │
  ▼ (easytier outbound)
SOCKS5 127.0.0.1:10852
  │
  ▼
EasyTier (no-tun mode)
  │
  ▼
Mesh Peers (tcp/udp/ws/wss)
  │
  ▼
远程内网主机
```

### 8.4 Root 模式流量路由

```
App 发出流量 (Root 模式)
  │
  ▼
iptables mangle 表
  │
  ├── bypass CIDR RETURN (10.0.0.0/8 等)
  │   └── EasyTier re-MARK (10.0.0.0/8 等) ← 修复
  │       └── 流量被标记 → 进入 tun
  │
  └── 其他流量 MARK → 进入 tun
  │
  ▼
Xray-core (tun)
  │
  ├── EasyTier 规则 → SOCKS5 → EasyTier mesh
  └── 其他规则 → proxy/direct
```

### 8.5 停止流程

```
CoreServiceManager.stopCoreLoop()
  ├── stopEasyTier()
  │    └── EasyTierPlugin.stop()
  │         └── EasyTierJNI.stopAllInstances()
  └── coreController.stopLoop()
       └── Xray-core 停止
```

---

## 9. 维护指南

### 9.1 修改 EasyTier 配置参数

| 需求 | 修改位置 |
|------|----------|
| 新增配置项 | `EasyTierConfig.kt` → 数据类 + `toToml()` + `fromMap()`；`EasyTierSettingsManager.kt` → getter/setter；`easytier_preferences.xml` → Preference；`strings.xml` → 字符串 |
| 修改 SOCKS5 端口默认值 | `EasyTierPlugin.kt` → `DEFAULT_SOCKS5_PORT` |
| 修改默认 LAN CIDR | `EasyTierPlugin.kt` → `DEFAULT_LAN_CIDRS` |
| 修改 outbound tag | `EasyTierPlugin.kt` → `OUTBOUND_TAG` |

### 9.2 升级 EasyTier Rust 源码

1. 更新 `EasyTier/` 子模块到目标版本
2. 检查 `easytier/src/common/config.rs` 中 `Config` 结构体是否有字段变更
3. 如有变更，更新 `EasyTierConfig.kt` 的 `toToml()` 方法
4. 检查 `easytier/src/proto/` 中 protobuf 定义是否变化
5. 如有变化，更新 `EasyTierPlugin.kt` 的 `getMeshCidrs()` JSON 解析
6. 运行 `./build-easytier-jni.sh` 重新编译 .so
7. 运行 `./gradlew assembleDebug` 验证编译

### 9.3 JNI 接口变更

如果 `easytier-android-jni` 的 JNI 方法签名发生变化：

1. 更新 `EasyTierJNI.kt` 中的 `external fun` 声明
2. 更新 `consumer-rules.pro` 中的 keep 规则（如有新类）
3. 更新 `EasyTierPlugin.kt` 中的调用
4. 重新编译 .so
5. 验证 `UnsatisfiedLinkError` 处理

### 9.4 v2rayNG 升级合并

当 v2rayNG 上游更新时：

1. `git remote add upstream <v2rayNG-upstream-url>`
2. `git fetch upstream`
3. `git merge upstream/master`（或 rebase）
4. **重点检查冲突的文件**：
   - `CoreConfigManager.kt` — `buildUnifiedConfig()` 和 `buildCustomConfig()` 末尾的注入点
   - `CoreServiceManager.kt` — `startCoreLoop()` 和 `stopCoreLoop()` 中的 EasyTier 启停调用
   - `CoreVpnService.kt` — `configureNetworkSettings()` 中 bypass LAN 分支
   - `RootProxyManager.kt` — `buildMangleMarking()` 和 `buildLanShareSetup()` 签名和 EasyTier 修复
   - `SettingsActivity.kt` — EasyTier 入口 preference
   - `pref_settings.xml` — EasyTier PreferenceCategory
5. 如果 v2rayNG 修改了 `ROUTED_IP_LIST`、`bypassCidrs`、`bypassLan` 逻辑，需要重新评估 EasyTier 冲突修复
6. 如果 v2rayNG 修改了 `V2rayConfig.OutboundBean` 或 `RoutingBean.RulesBean` 构造函数，需要更新 `injectEasyTier()`
7. 运行 `./gradlew assembleDebug` 验证编译

### 9.5 调试技巧

| 问题 | 检查方法 |
|------|----------|
| EasyTier 不启动 | 查看 logcat 过滤 `EasyTier` tag，检查 JNI 是否加载成功 |
| SOCKS5 连接失败 | 确认端口 10852 未被占用：`adb shell netstat -tlnp \| 10852` |
| 内网不通 | 检查 Xray 配置中是否有 `easytier` outbound 和路由规则 |
| bypass LAN 冲突 | 检查 VPN 路由表中是否包含 `10.0.0.0/8` 等 CIDR |
| Root 模式不通 | 检查 iptables mangle 规则：`adb shell iptables -t mangle -L -n -v` |
| TOML 解析失败 | 查看 `EasyTierJNI.getLastError()` 返回值 |
| mesh CIDR 未发现 | 调用 `EasyTierJNI.collectNetworkInfos(50)` 检查返回 JSON |

### 9.6 添加新的内网 CIDR

如果 EasyTier mesh 使用非标准 CIDR（如 `100.64.0.0/10` CGNAT）：

1. 在 `EasyTierPlugin.kt` 的 `DEFAULT_LAN_CIDRS` 中添加
2. `getMeshCidrsStatic()` 会自动发现 EasyTier 运行时的动态 CIDR
3. VPN 路由、iptables 规则、Xray 路由规则都会自动包含新 CIDR

---

## 10. 已知限制与未来工作

### 10.1 当前限制

| 限制 | 说明 |
|------|------|
| JNI .so 未编译 | `jniLibs/` 目录为空，需运行 `build-easytier-jni.sh` 编译 |
| 未实际测试 | 代码通过 review，但未在真机上运行 |
| `EasyTierDataPlaneJNI.kt` 预留 | 数据面 JNI 类已定义但未使用 |
| `EasyTierSettingsActivity` 无中文翻译 | `values/strings.xml` 和 `values-en/strings.xml` 内容相同，均为英文 |
| logLevel 仅存储不生效 | `EasyTierConfig.logLevel` 已定义但 JNI 层使用固定 `android_logger` Debug 级别 |

### 10.2 待完成工作

- [ ] 编译 JNI .so 库并验证 APK 构建
- [ ] 真机功能测试（VPN 模式 + Root 模式）
- [ ] `AngConfigManager.kt`、`SocksFmt.kt`、`MainActivity.kt` 集成审查
- [ ] 添加 `easytier_log_level` 偏好以控制 `android_logger` 级别（需 JNI 支持）
- [ ] 验证 GitHub Actions workflow 端到端
- [ ] 为 `EasyTierSettingsActivity` 添加中文字符串 (`values-zh/strings.xml`)

### 10.3 IPv6 支持考虑

当前 EasyTier 默认使用 IPv4。`RootProxyManager.bypassCidrsV6` 包含 `fc00::/7`（IPv6 ULA），如果 EasyTier 未来使用 IPv6 ULA 地址，可能需要在 IPv6 bypass 规则后也添加 re-MARK 规则。

`CoreVpnService` 的 IPv6 路由（`builder.addRoute()`）在 bypass LAN 模式下也需要类似处理。目前标记为低风险，因为 EasyTier 默认 IPv4。

### 10.4 性能考虑

- `getMeshCidrsStatic()` 每次 VPN 配置构建时调用 `EasyTierJNI.collectNetworkInfos(10)`，这是一个 JNI RPC 调用，可能有一定延迟。如果性能敏感，可考虑缓存结果。
- EasyTier 和 Xray-core 在同一进程中运行，CPU 和内存共享。EasyTier no-tun + SOCKS5 模式的开销主要在 SOCKS5 转发，通常可忽略。

### 10.5 安全考虑

- `network_secret` 存储在 SharedPreferences 中（明文），如果设备 root 可能被读取。未来可考虑使用 Android Keystore 加密。
- EasyTier TOML 配置中的 `network_secret` 通过 `Log.d` 打印（debug 级别），release 构建应移除或脱敏。
- SOCKS5 监听在 `0.0.0.0:10852`（TOML 中 `socks5_proxy = "socks5://0.0.0.0:10852"`），理论上可被同一网络的设备访问。如果需要限制，可改为 `127.0.0.1:10852`。

---

## 附录 A：完整文件清单

### 新增文件

| 文件 | 说明 |
|------|------|
| `easytier-plugin/build.gradle.kts` | 模块构建配置 |
| `easytier-plugin/consumer-rules.pro` | ProGuard keep 规则 |
| `easytier-plugin/src/main/AndroidManifest.xml` | Activity 声明 |
| `easytier-plugin/.../EasyTierPlugin.kt` | 主插件类 (330 行) |
| `easytier-plugin/.../EasyTierConfig.kt` | 配置数据类 (138 行) |
| `easytier-plugin/.../EasyTierSettingsManager.kt` | 设置管理 (142 行) |
| `easytier-plugin/.../EasyTierJNI.kt` | JNI 声明 (147 行) |
| `easytier-plugin/.../EasyTierDataPlaneJNI.kt` | 数据面 JNI (451 行，预留) |
| `easytier-plugin/.../EasyTierSettingsActivity.kt` | 设置 UI (115 行) |
| `easytier-plugin/.../easytier_settings_activity.xml` | 布局 |
| `easytier-plugin/.../easytier_preferences.xml` | PreferenceScreen |
| `easytier-plugin/.../values/strings.xml` | 字符串 |
| `easytier-plugin/.../values-en/strings.xml` | 英文字符串 |
| `build-easytier-jni.sh` | JNI 编译脚本 |
| `.github/workflows/build-easytier.yml` | CI/CD workflow |
| `README_EASYTIER_PLUGIN.md` | 项目 README |

### 修改文件

| 文件 | 修改行数 |
|------|----------|
| `settings.gradle.kts` | +1 |
| `app/build.gradle.kts` | +3 |
| `AppConfig.kt` | +9 |
| `CoreConfigManager.kt` | +141 |
| `CoreServiceManager.kt` | +57 |
| `RootProxyManager.kt` | +44 |
| `CoreVpnService.kt` | +30 |
| `SettingsActivity.kt` | +9 |
| `pref_settings.xml` | +9 |
| **合计** | **+298 / -5** |

---

## 附录 B：EasyTier JNI ↔ Rust 对应关系

| Kotlin (EasyTierJNI.kt) | Rust (lib.rs) | 功能 |
|--------------------------|---------------|------|
| `parseConfig(toml)` | `Java_..._parseConfig` | 验证 TOML 配置 |
| `runNetworkInstance(toml)` | `Java_..._runNetworkInstance` | 启动网络实例 |
| `retainNetworkInstance(names)` | `Java_..._retainNetworkInstance` | 保留指定实例 |
| `stopAllInstances()` | → `retainNetworkInstance(null)` | 停止所有实例 |
| `setTunFd(name, fd)` | `Java_..._setTunFd` | 设置 TUN fd (no-tun 模式不用) |
| `collectNetworkInfos(max)` | `Java_..._collectNetworkInfos` | 收集运行信息 (JSON) |
| `listInstances(max)` | `Java_..._listInstances` | 列出实例 |
| `callJsonRpc(...)` | `Java_..._callJsonRpc` | RPC 方法调用 |
| `getLastError()` | `Java_..._getLastError` | 获取错误信息 |

`collectNetworkInfos` 返回 `NetworkInstanceRunningInfoMap` JSON：

```json
{
  "map": {
    "v2rayng_plugin": {
      "running": true,
      "routes": [
        {
          "proxy_cidrs": ["10.144.144.0/24"],
          "direct_cidrs": ["100.64.0.0/10"]
        }
      ]
    }
  }
}
```

`getMeshCidrsStatic()` 解析此 JSON，提取所有 `proxy_cidrs` 和 `direct_cidrs`。

---

## 附录 C：v2rayNG 内网地址常量参考

| 常量 | 值 | 位置 | 用途 |
|------|-----|------|------|
| `ROUTED_IP_LIST` | 排除私有 IP 的公网路由列表 | `CoreVpnService.kt` | VPN 模式 bypass LAN |
| `PRIVATE_IP_LIST` | `0.0.0.0/8, 10.0.0.0/8, 127.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 224.0.0.0/4` | `CoreVpnService.kt` | DNS 劫持判断 |
| `bypassCidrs` | `0.0.0.0/8, 10.0.0.0/8, 127.0.0.0/8, 169.254.0.0/16, 172.16.0.0/12, 192.168.0.0/16, 224.0.0.0/4, 240.0.0.0/4` | `RootProxyManager.kt` | iptables bypass |
| `bypassCidrsV6` | `::1/128, fe80::/10, fc00::/7, ff00::/8` | `RootProxyManager.kt` | IPv6 iptables bypass |
| `PREF_VPN_BYPASS_LAN` | `"0"`=Follow config, `"1"`=Bypass, `"2"`=Not Bypass | `AppConfig.kt` | bypass LAN 设置 |
| `EasyTierPlugin.DEFAULT_LAN_CIDRS` | `10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16` | `EasyTierPlugin.kt` | EasyTier 默认 mesh CIDR |
