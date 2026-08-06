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
* **Tailscale** 以 endpoint 形式接入：控制面/数据面经当前选中的订阅节点转发后再进 tailnet
* 订阅更新后尽量按节点名跟随已选节点，并在需要时自动重连
* 订阅更新结果用 Toast 提示（不再弹 Diff 对话框）
* 可选「启动时更新订阅」（默认开启）
* Release 构建可自动递增版本号，并与 GitHub Release 标签对齐

* Rebranded as **KonoBox** (`moe.konobox`)
* **Tailscale** endpoint support: traffic goes through the selected subscription outbound first, then into the tailnet
* Prefer following the selected node by name across subscription updates, with reconnect when needed
* Subscription update results use Toast (no Diff dialog)
* Optional “Update subscriptions on start” (default on)
* Release workflow can auto-bump versions and keep GitHub Release tags in sync

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
