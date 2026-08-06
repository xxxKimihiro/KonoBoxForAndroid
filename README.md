# KonoBox for Android

[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![Releases](https://img.shields.io/github/v/release/xxxKimihiro/KonoBoxForAndroid)](https://github.com/xxxKimihiro/KonoBoxForAndroid/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

基于 sing-box 的 Android 代理客户端（由 [NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid) 衍生）。

A sing-box based Android proxy client, forked from [NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid).

## 下载 / Downloads

仅通过 GitHub Releases 发布，**不提供 Google Play 版本**。

Released only via GitHub Releases. **No Google Play build is provided.**

[![GitHub All Releases](https://img.shields.io/github/downloads/xxxKimihiro/KonoBoxForAndroid/total?label=downloads-total&logo=github&style=flat-square)](https://github.com/xxxKimihiro/KonoBoxForAndroid/releases)

→ [GitHub Releases](https://github.com/xxxKimihiro/KonoBoxForAndroid/releases)

手机请优先安装 `arm64-v8a` 包。

Prefer the `arm64-v8a` APK on modern phones.

## 相对上游的主要改动 / Changes from upstream

* 应用更名为 **KonoBox**（`moe.konobox`）
* **Tailscale**：以 endpoint 接入；控制面/数据面先经当前选中的订阅节点，再进入 tailnet
* **自动出站**：全局「顺序 fallback / 延迟 urltest」（探测复用连接测试 URL）；可自建「自动出站组」子集，支持选中连接或作为路由出站
* **可信 Wi‑Fi 直连**：白名单 SSID 下保持 VPN，但流量全部走 Direct；离开后自动恢复所选代理
* **订阅体验**：
  * 更新后尽量按节点名跟随已选节点，必要时自动重连
  * 更新结果用 Toast 提示（不再弹 Diff 对话框）
  * 可选「启动时更新订阅」（默认开启）
  * 代理路径拉取失败时，默认走底层网络直连重试（绕过 VPN），避免坏节点导致无法更新

* Rebranded as **KonoBox** (`moe.konobox`)
* **Tailscale** endpoint: control/data plane goes through the selected subscription node first, then into the tailnet
* **Auto outbound**: global ordered fallback / latency urltest (probes reuse Connection Test URL); custom Auto Outbound Groups usable as the selected profile or a route outbound
* **Trusted Wi‑Fi Direct**: on whitelisted SSIDs keep VPN up but route all traffic Direct; restore the selected proxy when leaving
* **Subscriptions**:
  * Prefer following the selected node by name across updates, with reconnect when needed
  * Update results use Toast (no Diff dialog)
  * Optional “Update subscriptions on start” (default on)
  * When proxy-path fetch fails, retry on the underlying network by default (bypass VPN) so a dead node cannot block updates

## 支持的代理协议 / Supported Proxy Protocols

* SOCKS (4/4a/5)
* HTTP(S)
* SSH
* Shadowsocks
* VMess
* Trojan
* VLESS
* AnyTLS
* ShadowTLS
* TUIC
* Hysteria 1/2
* WireGuard
* Tailscale（内置 endpoint）
* Trojan-Go / NaïveProxy / Mieru（需对应插件）

部分协议仍依赖插件；本仓库默认不捆绑全部第三方插件。

Some protocols still need plugins; this repo does not ship every third-party plugin by default.

## 支持的订阅格式 / Supported Subscription Format

* 常见格式（如 Shadowsocks、ClashMeta、v2rayN）
* sing-box 出站

仅解析出站节点；分流规则等信息会被忽略。

* Common formats (Shadowsocks, ClashMeta, v2rayN, etc.)
* sing-box outbound

Only outbounds / nodes are parsed; routing rules and similar metadata are ignored.

## Tailscale 简要说明 / Tailscale notes

1. 在全局设置中启用 Tailscale，并填入 auth key（`tskey-...`）
2. 选择可用的订阅节点后连接 VPN
3. Control server / Hostname / Exit node / Accept subnet routes 等一般可留空或保持默认

1. Enable Tailscale in global settings and paste an auth key (`tskey-...`)
2. Select a working subscription node, then connect the VPN
3. Control server / Hostname / Exit node / Accept subnet routes can usually stay empty / default

## 可信 Wi‑Fi 直连 / Trusted Wi‑Fi Direct

1. 设置 → **可信 Wi‑Fi** → 打开「可信 Wi‑Fi 下直连」
2. 在「可信 SSID」中填入家里/公司等 Wi‑Fi 名称（每行一个，精确匹配）
3. 按提示授予定位 / 附近的 Wi‑Fi 权限（否则可能读不到 SSID）
4. VPN 可保持开启：连上白名单 Wi‑Fi 后通知栏标题会带 `· Direct`，流量走直连；离开后自动恢复代理节点

1. Settings → **Trusted Wi‑Fi** → enable “Direct on trusted Wi‑Fi”
2. Add home/office SSIDs under “Trusted SSIDs” (one per line, exact match)
3. Grant location / nearby Wi‑Fi permission when prompted (otherwise SSID may be unreadable)
4. Leave VPN on: on a whitelisted SSID the notification title shows `· Direct` and traffic goes Direct; leaving restores the selected proxy

## 自动出站 / Auto Outbound

1. 设置 → **自动出站** → 选择「顺序（fallback）」或「延迟（urltest）」：对当前选中分组的节点生效
2. 健康检查 / 延迟探测使用「连接测试链接」（`connectionTestURL`）
3. 自建子集：配置列表 → 添加 → **自动出站组**，挑选成员并选择策略；该配置可选中连接，也可在路由规则里作为出站

1. Settings → **Auto Outbound** → choose “Order (fallback)” or “Latency (urltest)” for the selected group
2. Health / latency probes use the Connection Test URL (`connectionTestURL`)
3. Custom subsets: Profiles → Add → **Auto Outbound Group**, pick members and a strategy; usable as the selected profile or as a route outbound

## Credits

基于 / Based on:

- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)

Core:

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [MatsuriDayo/sing-box](https://github.com/MatsuriDayo/sing-box) / [MatsuriDayo/libneko](https://github.com/MatsuriDayo/libneko)

Android GUI:

- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)

Web Dashboard:

- [Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)
