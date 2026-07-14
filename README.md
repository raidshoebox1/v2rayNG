# v2rayNG

A V2Ray client for Android, support [Xray core](https://github.com/XTLS/Xray-core) and [v2fly core](https://github.com/v2fly/v2ray-core)

[![API](https://img.shields.io/badge/API-24%2B-yellow.svg?style=flat)](https://developer.android.com/about/versions/lollipop)
[![Kotlin Version](https://img.shields.io/badge/Kotlin-2.4.0-blue.svg)](https://kotlinlang.org)
[![GitHub commit activity](https://img.shields.io/github/commit-activity/m/2dust/v2rayNG)](https://github.com/2dust/v2rayNG/commits/master)
[![CodeFactor](https://www.codefactor.io/repository/github/2dust/v2rayng/badge)](https://www.codefactor.io/repository/github/2dust/v2rayng)
[![GitHub Releases](https://img.shields.io/github/downloads/2dust/v2rayNG/latest/total?logo=github)](https://github.com/2dust/v2rayNG/releases)
[![Chat on Telegram](https://img.shields.io/badge/Chat%20on-Telegram-brightgreen.svg)](https://t.me/v2rayn)

---

## Download / 下载

Download the latest release here:

在这里下载最新版本：

[https://github.com/2dust/v2rayNG/releases](https://github.com/2dust/v2rayNG/releases)

> [!TIP]
> v2rayNG is the mobile version. For the desktop version, please visit the v2rayN \
> v2rayNG 是手机版，电脑版请访问 v2rayN
>
> https://github.com/2dust/v2rayN

---

### Geoip and Geosite

- geoip.dat and geosite.dat files are in `Android/data/com.v2ray.ang/files/assets` (path may differ on some Android device)
- download feature will get enhanced version in this [repo](https://github.com/Loyalsoldier/v2ray-rules-dat) (note: it needs a working proxy)
- latest official [domain list](https://github.com/Loyalsoldier/v2ray-rules-dat) and [ip list](https://github.com/Loyalsoldier/geoip) can be imported manually
- possible to use a third-party dat file in the same folder, like [h2y](https://guide.v2fly.org/routing/sitedata.html#%E5%A4%96%E7%BD%AE%E7%9A%84%E5%9F%9F%E5%90%8D%E6%96%87%E4%BB%B6)

More in our [wiki](https://github.com/2dust/v2rayNG/wiki)

### Geoip 与 Geosite

- geoip.dat 和 geosite.dat 文件位于 `Android/data/com.v2ray.ang/files/assets`（部分设备路径可能不同）
- 下载功能将获取该 [仓库](https://github.com/Loyalsoldier/v2ray-rules-dat) 中的增强版本（注意：此功能需要一个可用的代理）
- 最新官方 [域名列表](https://github.com/Loyalsoldier/v2ray-rules-dat) 和 [IP 列表](https://github.com/Loyalsoldier/geoip) 可手动导入
- 也可在同一文件夹中使用第三方 dat 文件，例如 [h2y](https://guide.v2fly.org/routing/sitedata.html#%E5%A4%96%E7%BD%AE%E7%9A%84%E5%9F%9F%E5%90%8D%E6%96%87%E4%BB%B6)

更多内容请见我们的 [wiki](https://github.com/2dust/v2rayNG/wiki)

---

## Development guide / 开发指南

### Note

- Android project under the V2rayNG folder can be compiled directly in Android Studio, or using the Gradle wrapper. But the v2ray core inside the aar is (probably) outdated.
- The aar can be compiled from the Golang project [AndroidLibV2rayLite](https://github.com/2dust/AndroidLibV2rayLite) or [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite). For a quick start, read the guides for [Go Mobile](https://github.com/golang/go/wiki/Mobile) and [Makefiles for Go Developers](https://tutorialedge.net/golang/makefiles-for-go-developers/).
- v2rayNG can run on Android Emulators. For WSA, VPN permission needs to be granted via `appops set [package name] ACTIVATE_VPN allow`.

### 提示

- V2rayNG 文件夹下的 Android 项目可直接在 Android Studio 中编译，或使用 Gradle wrapper 编译。但 aar 内置的 v2ray core（可能）已过时。
- aar 可由 Golang 项目 [AndroidLibV2rayLite](https://github.com/2dust/AndroidLibV2rayLite) 或 [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) 编译而成。快速入门可参考 [Go Mobile](https://github.com/golang/go/wiki/Mobile) 指南和 [Makefiles for Go Developers](https://tutorialedge.net/golang/makefiles-for-go-developers/)。
- v2rayNG 可在 Android 模拟器上运行。对于 WSA，需要通过 `appops set [package name] ACTIVATE_VPN allow` 授予 VPN 权限。

---


## GPG Verification / GPG 签名校验

Release files are signed with GPG to verify authenticity and integrity, helping prevent mirror, ISP, or CDN hijacking.

发布文件已使用 GPG 签名，可用于校验文件真实性与完整性，预防镜像站、运营商或 CDN 劫持。

### Fingerprint / 公钥指纹

```text
7694 5E9F 3E9A 168F 8070 F195 805D 661C
134D FAF6 8903 C199 463C 31E5 AE90 3AE0
```

---

## Community / 社区

Telegram Group / Telegram 群组：

[https://t.me/v2rayN](https://t.me/v2rayN)

Telegram Channel / Telegram 频道：

[https://t.me/github_2dust](https://t.me/github_2dust)

---

## EasyTier Mesh VPN Plugin

This fork adds an **EasyTier** mesh-network plugin to v2rayNG, letting you access remote LAN/mesh hosts through EasyTier's P2P/relay network while using v2rayNG for internet proxy.

### Architecture

EasyTier runs in **no-tun + SOCKS5** mode (loopback only) inside the v2rayNG process. It does **not** compete for the Android VpnService slot. Xray-core routes LAN/mesh CIDRs to the EasyTier SOCKS5 outbound via an injected routing rule.

```
App traffic → VpnService → Xray-core routing
  ├─ public traffic  → proxy outbound (VMess/VLESS/Trojan/SS…)
  └─ LAN/mesh CIDRs  → easytier outbound (SOCKS5 127.0.0.1:10852) → EasyTier mesh
```

### Configuration

In v2rayNG Settings → **EasyTier Mesh VPN** → **EasyTier Settings**:

| Setting | Required | Default | Description |
|---------|----------|---------|-------------|
| Enable | Yes | OFF | Master switch |
| Network Name | Yes | (empty) | Must match across all peers |
| Network Secret | Recommended | (empty) | Shared secret for mesh encryption |
| Virtual IP | No | (auto) | e.g. `10.144.144.1`; leave empty for auto-assign |
| Peers | Yes | (empty) | One per line; e.g. `tcp://public.easytier.top:11010` |
| SOCKS5 Port | No | 10852 | Loopback only; must not conflict with v2rayNG's SOCKS (10808) |
| Log Enabled | No | ON | Capture EasyTier logs for in-app viewer |
| Log Level | No | warn | error/warn/info/debug/trace |

### Security notes

- **SOCKS5 binds to `127.0.0.1` only** — the EasyTier SOCKS5 listener is not exposed to other devices on the LAN.
- **Network secret is stored in plaintext** in SharedPreferences (same security model as v2rayNG's own settings). Consider Android Keystore encryption for future enhancement.
- **Debug logs are suppressed in release builds** — only W/E levels go to logcat; the in-app log viewer still shows all levels.
- **EasyTier submodule** points to a fork (`raidshoebox1/EasyTier`) for JNI log-callback support. Audit the fork diff before building release APKs.
- **No-TUN mode is mandatory** — EasyTier's TUN mode would conflict with v2rayNG's VpnService.

### Build

```bash
# 1. Build EasyTier JNI .so libraries (requires Rust + Android NDK)
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
cargo install cargo-ndk
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/29.0.14206865
./build-easytier-jni.sh            # all architectures
# or: ./build-easytier-jni.sh arm64-v8a

# 2. Build APK
cd V2rayNG
echo "sdk.dir=${ANDROID_HOME}" > local.properties
./gradlew assembleDebug            # or assembleRelease
```

CI: `.github/workflows/build-easytier.yml` builds JNI + APK on push/PR. Use `build_release: true` for a release build.

### Files modified in v2rayNG

| File | Change |
|------|--------|
| `settings.gradle.kts` | +1 line: `include(":easytier-plugin")` |
| `app/build.gradle.kts` | +3 lines: `implementation(project(":easytier-plugin"))` |
| `AppConfig.kt` | (no EasyTier constants — plugin uses its own keys) |
| `CoreConfigManager.kt` | `injectEasyTier()` + `injectEasyTierIntoCustomConfig()` |
| `CoreServiceManager.kt` | `startEasyTier()` / `stopEasyTier()` lifecycle |
| `CoreVpnService.kt` | mesh CIDR `addRoute()` in bypass-LAN mode |
| `RootProxyManager.kt` | mesh CIDR re-MARK in iptables + LAN-sharing ip-rule |
| `SettingsActivity.kt` | EasyTier settings entry point |
| `res/xml/pref_settings.xml` | EasyTier PreferenceCategory |
| `res/values*/strings.xml` | 6 strings each (en, zh-rCN, zh-rTW) |

### Known limitations

- `EasyTierDataPlaneJNI.kt` is reserved (unused) for future data-plane TCP/UDP APIs.
- IPv6 mesh CIDRs are not yet routed in the VPN/Root bypass logic (EasyTier defaults to IPv4).
- `getMeshCidrsStatic()` caches results for 5 seconds; topology changes may take up to 5s to propagate.
