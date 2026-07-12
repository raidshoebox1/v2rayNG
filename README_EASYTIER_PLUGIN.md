# v2rayNG + EasyTier Mesh VPN Plugin

## 架构概览

```
┌─────────────────────────────────────────────────┐
│                   v2rayNG App                    │
│                                                   │
│  ┌─────────────┐     ┌─────────────────────────┐ │
│  │  Xray-core   │     │   EasyTier Plugin       │ │
│  │  (VpnService)│     │   (no-tun + SOCKS5)     │ │
│  │              │     │                         │ │
│  │  outbound:   │     │  EasyTierJNI (native)   │ │
│  │  - proxy     │◄────│  - runNetworkInstance() │ │
│  │  - direct    │     │  - SOCKS5 :10852        │ │
│  │  - easytier  │     │                         │ │
│  │              │     │  Mesh Peers:            │ │
│  │  routing:    │     │  - tcp://peer1:11010    │ │
│  │  - LAN CIDRs │     │  - udp://peer2:11010    │ │
│  │  → easytier  │     │  - ...                  │ │
│  └─────────────┘     └─────────────────────────┘ │
│                                                   │
│  ┌─────────────────────────────────────────────┐ │
│  │          Android VpnService (sole)           │ │
│  │          Owned by Xray-core only              │ │
│  └─────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────┘
```

## 核心设计

### 问题
EasyTier 原生需要 Android VpnService 的 TUN fd，而 v2rayNG 也需要 VpnService。
Android 只允许一个 app 同时持有 VpnService，两者冲突。

### 解决方案：No-TUN + SOCKS5 模式

EasyTier 以 **no-tun** 模式运行，不创建 TUN 设备，只开启 SOCKS5 listener。
v2rayNG 的 Xray-core 通过一个额外的 SOCKS5 outbound 连接到 EasyTier，
路由规则将内网/mesh CIDR 导向 EasyTier outbound。

**优势**：
- v2rayNG 独占 VpnService，无冲突
- EasyTier 处理内网 mesh 流量，Xray-core 处理外网代理流量
- 用户可在 v2rayNG 设置中开关 EasyTier，无需切换 app

## 文件结构

```
v2rayNG/
├── easytier-plugin/                    # 新增：EasyTier 插件模块
│   ├── build.gradle.kts
│   ├── consumer-rules.pro
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── jniLibs/                    # .so 文件 (由 build-easytier-jni.sh 生成)
│   │   │   ├── arm64-v8a/
│   │   │   ├── armeabi-v7a/
│   │   │   ├── x86/
│   │   │   └── x86_64/
│   │   ├── kotlin/com/easytier/plugin/
│   │   │   ├── EasyTierPlugin.kt       # 主插件类：启停 EasyTier、构建 outbound
│   │   │   ├── EasyTierConfig.kt       # 配置数据类 + TOML 序列化
│   │   │   ├── EasyTierJNI.kt          # JNI 桥接层
│   │   │   ├── EasyTierSettingsManager.kt  # SharedPreferences 设置管理
│   │   │   └── ui/
│   │   │       └── EasyTierSettingsActivity.kt  # 设置 UI
│   │   └── res/
│   │       ├── layout/easytier_settings_activity.xml
│   │       ├── values/strings.xml
│   │       ├── values-en/strings.xml
│   │       └── xml/easytier_preferences.xml
│   └── build-easytier-jni.sh           # (在上层目录)
│
├── app/src/main/java/com/v2ray/ang/
│   ├── AppConfig.kt                    # 修改：添加 PREF_EASYTIER_* 常量
│   ├── core/
│   │   ├── CoreConfigManager.kt        # 修改：injectEasyTier() + injectEasyTierIntoCustomConfig()
│   │   └── CoreServiceManager.kt       # 修改：startEasyTier() / stopEasyTier() 生命周期
│   ├── ui/
│   │   └── SettingsActivity.kt         # 修改：添加 EasyTier 设置入口
│   └── res/xml/
│       └── pref_settings.xml           # 修改：添加 EasyTier PreferenceCategory
│
├── settings.gradle.kts                 # 修改：include(":easytier-plugin")
└── app/build.gradle.kts                # 修改：implementation(project(":easytier-plugin"))
```

## 修改清单

### 1. 新增模块 `easytier-plugin`
- **EasyTierPlugin.kt** — 主插件类，管理 EasyTier 实例生命周期
  - `start(config)` / `stop()` / `isRunning()` / `getSocks5Endpoint()`
  - `buildOutboundJson()` — 生成 Xray SOCKS5 outbound JSON
  - `buildRoutingRules()` — 生成 LAN CIDR 路由规则
  - `getMeshCidrs()` — 从 EasyTier RPC 获取动态 mesh CIDR
- **EasyTierConfig.kt** — 配置数据类，序列化为 EasyTier TOML
- **EasyTierJNI.kt** — JNI native 方法声明（对应 easytier-android-jni）
- **EasyTierSettingsManager.kt** — SharedPreferences 存储（独立于 MMKV）
- **EasyTierSettingsActivity.kt** — PreferenceFragmentCompat 设置 UI

### 2. 修改 `CoreConfigManager.kt`
- `injectEasyTier(context, v2rayConfig)` — 在 `buildUnifiedConfig()` 末尾调用
  - 添加 SOCKS5 outbound (tag="easytier", 127.0.0.1:10852)
  - 添加路由规则：LAN CIDRs → easytier outbound
  - 获取动态 mesh CIDRs
- `injectEasyTierIntoCustomConfig(context, json)` — 自定义配置也注入

### 3. 修改 `CoreServiceManager.kt`
- `startEasyTier(context)` — 在 `coreController.startLoop()` 之前启动 EasyTier
- `stopEasyTier()` — 在 `stopCoreLoop()` 中停止 EasyTier

### 4. 修改 `SettingsActivity.kt`
- 添加 EasyTier 设置入口，跳转到 `EasyTierSettingsActivity`

### 5. 修改 `AppConfig.kt`
- 添加 `PREF_EASYTIER_*` 常量

### 6. 修改 `settings.gradle.kts` / `app/build.gradle.kts`
- `include(":easytier-plugin")` 和依赖声明

## 构建步骤

### 1. 编译 EasyTier JNI 原生库

```bash
# 安装依赖
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
cargo install cargo-ndk

# 设置 NDK
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/26.1.10909125

# 编译所有架构
cd v2rayNG
./build-easytier-jni.sh

# 或只编译 arm64
./build-easytier-jni.sh arm64-v8a
```

### 2. 构建 v2rayNG APK

```bash
cd v2rayNG/V2rayNG
./gradlew assembleRelease
```

## 配置说明

在 v2rayNG 设置 → EasyTier Mesh VPN 中：

| 设置项 | 说明 | 默认值 |
|--------|------|--------|
| Enable | 启用/禁用 EasyTier 插件 | OFF |
| Network Name | EasyTier 网络名（所有节点需一致） | (空) |
| Network Secret | 网络密钥（可选加密） | (空) |
| Virtual IP | 虚拟 IP（留空自动分配） | (空) |
| Peer Addresses | 逗号分隔的对端 URI | (空) |
| SOCKS5 Port | 本地 SOCKS5 监听端口 | 10852 |
| No TUN Mode | 禁用 TUN（必须开启） | ON (锁定) |
| Log Level | EasyTier 日志级别 | warn |

### 示例配置

```
Network Name:    my-mesh
Network Secret:  secret123
Virtual IP:      10.144.144.1
Peer Addresses:  tcp://public.easytier.top:11010
SOCKS5 Port:     10852
```

## 数据流

```
App 流量
    │
    ▼
Android VpnService (v2rayNG)
    │
    ▼
Xray-core 路由
    │
    ├── 外网流量 → proxy outbound (VMess/VLESS/Trojan/SS...)
    │
    └── 内网流量 (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
            + EasyTier mesh CIDRs
            │
            ▼
        SOCKS5 outbound (127.0.0.1:10852)
            │
            ▼
        EasyTier (no-tun mode)
            │
            ▼
        Mesh Peers (tcp/udp/wss)
```

## 与 EasyTier JNI 的对应关系

| Kotlin (EasyTierJNI.kt) | Rust (lib.rs) | 功能 |
|--------------------------|---------------|------|
| `parseConfig(toml)` | `Java_com_easytier_jni_EasyTierJNI_parseConfig` | 验证 TOML 配置 |
| `runNetworkInstance(toml)` | `..._runNetworkInstance` | 启动网络实例 |
| `retainNetworkInstance(names)` | `..._retainNetworkInstance` | 保留指定实例，停止其他 |
| `stopAllInstances()` | → `retainNetworkInstance(null)` | 停止所有实例 |
| `setTunFd(name, fd)` | `..._setTunFd` | 设置 TUN fd (no-tun 模式下不用) |
| `collectNetworkInfos(max)` | `..._collectNetworkInfos` | 收集运行信息 (JSON) |
| `listInstances(max)` | `..._listInstances` | 列出实例 |
| `callJsonRpc(...)` | `..._callJsonRpc` | 调用 RPC 方法 |
| `getLastError()` | `..._getLastError` | 获取错误信息 |

## 注意事项

1. **No-TUN 模式是强制的** — UI 中 No TUN 开关已锁定为 ON，因为 EasyTier 的 TUN 模式会与 v2rayNG 的 VpnService 冲突。
2. **SOCKS5 端口冲突** — 默认 10852，确保不与 v2rayNG 的 SOCKS5 端口 (10808) 冲突。
3. **JNI 库缺失时优雅降级** — 如果 `libeasytier_android_jni.so` 未安装（如在非支持的 ABI 上），EasyTier 插件不会启动，v2rayNG 正常运行。
4. **独立 SharedPreferences** — EasyTier 设置存储在 SharedPreferences（非 MMKV），与 v2rayNG 核心设置隔离。
5. **原生库加载** — `EasyTierPlugin` 使用 `System.loadLibrary("easytier_android_jni")` 懒加载，失败时 catch `UnsatisfiedLinkError`。
