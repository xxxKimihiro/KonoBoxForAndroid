package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.OrderFallbackGroup
import io.nekohasekai.sagernet.fmt.internal.AutoGroupBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxOutboundWireguardBean
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.utils.WifiDirectHelper
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_PROXY_MAIN = "proxy-main"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"

const val LOCALHOST = "127.0.0.1"

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
    /** Ordered-fallback selector groups that need app-side health checks. */
    val orderFallbackGroups: List<OrderFallbackGroup> = emptyList(),
    /**
     * Trusted Wi‑Fi can hot-switch via selector (proxy ↔ bypass) without rebuilding
     * the core. When true, [wifiDirectProxyTag] is the outbound to select when leaving
     * Direct (empty = use the currently selected profile tag from [profileTagMap]).
     */
    val wifiDirectHotSwitch: Boolean = false,
    val wifiDirectProxyTag: String = "",
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
    data class OrderFallbackGroup(val groupTag: String, val memberTags: List<String>)
}

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false
): ConfigBuildResult {

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            return ConfigBuildResult(
                bean.config,
                listOf(),
                proxy.id, //
                mapOf(TAG_PROXY to listOf(proxy)), //
                mapOf(proxy.id to TAG_PROXY), //
                -1L
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    val selectorNames = ArrayList<String>()
    val orderFallbackGroups = ArrayList<OrderFallbackGroup>()
    val group = SagerDatabase.groupDao.getById(proxy.groupId)
    val autoOutboundMode =
        if (forTest || forExport) AutoOutboundMode.OFF else DataStore.autoOutboundMode

    fun ProxyEntity.resolveChainInternal(): MutableList<ProxyEntity> {
        val bean = requireBean()
        if (bean is ChainBean) {
            val beans = SagerDatabase.proxyDao.getEntities(bean.proxies)
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = beansMap[proxyId] ?: continue
                beanList.addAll(item.resolveChainInternal())
            }
            return beanList.asReversed()
        }
        // AutoGroup is a parallel group, not a sequential chain.
        return mutableListOf(this)
    }

    fun selectorName(name_: String): String {
        var name = name_
        var count = 0
        while (selectorNames.contains(name)) {
            count++
            name = "$name_-$count"
        }
        selectorNames.add(name)
        return name
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        val thisGroup = SagerDatabase.groupDao.getById(groupId)
        val frontProxy = thisGroup?.frontProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val landingProxy = thisGroup?.landingProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val list = resolveChainInternal()
        if (frontProxy != null) {
            list.add(frontProxy)
        }
        if (landingProxy != null) {
            list.add(0, landingProxy)
        }
        return list
    }

    val extraRules = if (forTest) listOf() else SagerDatabase.rulesDao.enabledRules()
    val extraProxies =
        if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    // autoSwitchOnFail：把当前分组成员都放进 selector，连接失败时可热切换而不必整表重建
    val buildSelector =
        !forTest && group != null && !forExport && autoOutboundMode == AutoOutboundMode.OFF &&
            (group.isSelector || DataStore.autoSwitchOnFail)
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val enableDnsRouting = DataStore.enableDnsRouting
    // Feature enabled → build hot-switch-friendly config (selector can pick bypass).
    val wifiDirectFeature = !forTest && !forExport && DataStore.wifiDirectEnabled
    val wifiDirect = !forTest && WifiDirectHelper.shouldUseDirect()
    if (!forTest) DataStore.wifiDirectActive = wifiDirect
    // FakeDNS + Direct is unsafe; keep it off whenever Trusted Wi‑Fi hot-switch is built in.
    val useFakeDns = DataStore.enableFakeDns && !forTest && !wifiDirectFeature && !wifiDirect
    val needSniff = DataStore.trafficSniffing > 0
    val needSniffOverride = DataStore.trafficSniffing == 2
    val externalIndexMap = ArrayList<IndexEntity>()
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode
    var wifiDirectHotSwitch = false
    var wifiDirectProxyTag = ""

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    var resultSelectorGroupId = -1L

    return MyOptions().apply {
        if (!forTest && DataStore.enableClashAPI) experimental = ExperimentalOptions().apply {
            clash_api = ClashAPIOptions().apply {
                external_controller = "127.0.0.1:9090"
                external_ui = "../files/yacd"
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
            independent_cache = true
        }

        fun autoDnsDomainStrategy(s: String): String? {
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN) inbounds.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = "tun-in"
                stack = when (DataStore.tunImplementation) {
                    TunImplementation.GVISOR -> "gvisor"
                    TunImplementation.SYSTEM -> "system"
                    else -> "mixed"
                }
                endpoint_independent_nat = true
                mtu = DataStore.mtu
                domain_strategy = genDomainStrategy(DataStore.resolveDestination)
                sniff = needSniff
                sniff_override_destination = needSniffOverride
                when (ipv6Mode) {
                    IPv6Mode.DISABLE -> {
                        inet4_address = listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                    }

                    IPv6Mode.ONLY -> {
                        inet6_address = listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    }

                    else -> {
                        inet4_address = listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                        inet6_address = listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    }
                }
            })
            inbounds.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
                domain_strategy = genDomainStrategy(DataStore.resolveDestination)
                sniff = needSniff
                sniff_override_destination = needSniffOverride
            })
        }

        outbounds = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            rules = mutableListOf()
            rule_set = mutableListOf()
            // Legacy path (feature on but hot-switch not applied yet): final → bypass.
            // When wifiDirectFeature is on we instead put bypass into the proxy selector
            // and hot-switch; route.final_ stays on TAG_PROXY.
            if (wifiDirect && !wifiDirectFeature) {
                final_ = TAG_BYPASS
            }
        }

        fun emitGroupOutbound(
            groupTag: String,
            memberTags: List<String>,
            defaultTag: String?,
            strategyOrder: Boolean,
            traffic: List<ProxyEntity>,
        ): String {
            if (memberTags.isEmpty()) {
                // No members: keep a selector pointing nowhere useful; traffic falls to later rules.
                outbounds.add(0, Outbound_SelectorOptions().apply {
                    type = "selector"
                    tag = groupTag
                    outbounds = listOf(TAG_DIRECT)
                    default_ = TAG_DIRECT
                })
                trafficMap[groupTag] = traffic
                return groupTag
            }
            if (strategyOrder) {
                outbounds.add(0, Outbound_SelectorOptions().apply {
                    type = "selector"
                    tag = groupTag
                    outbounds = memberTags
                    default_ = defaultTag?.takeIf { it in memberTags } ?: memberTags.first()
                    _hack_config_map["interrupt_exist_connections"] = true
                })
                orderFallbackGroups.add(OrderFallbackGroup(groupTag, memberTags.toList()))
            } else {
                outbounds.add(0, Outbound_URLTestOptions().apply {
                    type = "urltest"
                    tag = groupTag
                    outbounds = memberTags
                    url = DataStore.connectionTestURL
                    tolerance = 50
                    _hack_config_map["interval"] = "3m"
                    _hack_config_map["idle_timeout"] = "30m"
                    _hack_config_map["interrupt_exist_connections"] = true
                })
            }
            trafficMap[groupTag] = traffic
            return groupTag
        }

        // returns outbound tag
        fun buildChain(
            chainId: Long, entity: ProxyEntity
        ): String {
            val profileList = entity.resolveChain()
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                plusAssign(profileList)
                add(entity)
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<SingBoxOption>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            val defaultServerDomainStrategy = SingBoxOptionsUtil.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id
                    bypassDNSBeans += proxyEntity.requireBean()
                }

                // last profile set as "proxy"
                if (chainId == 0L && index == 0) {
                    tagOut = TAG_PROXY
                }

                // selector human readable name
                if (buildSelector && index == 0) {
                    tagOut = selectorName(bean.displayName())
                }


                // chain rules
                if (index > 0) {
                    // chain route/proxy rules
                    if (pastEntity!!.needExternal()) {
                        route.rules.add(Rule_DefaultOptions().apply {
                            inbound = listOf(pastInboundTag)
                            outbound = tagOut
                        })
                    } else {
                        pastOutbound._hack_config_map["detour"] = tagOut
                    }
                } else {
                    // index == 0 means last profile in chain / not chain
                    chainTagOut = tagOut
                }

                // now tagOut is determined
                if (needGlobal) {
                    globalOutbounds[proxyEntity.id]?.let {
                        if (index == 0) chainTagOut = it // single, duplicate chain
                        return@forEachIndexed
                    }
                    globalOutbounds[proxyEntity.id] = tagOut
                }

                if (proxyEntity.needExternal()) { // externel outbound
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                    }
                } else {
                    // internal outbound

                    currentOutbound = when (bean) {
                        is ConfigBean -> CustomSingBoxOption(bean.config)

                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean)

                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean)

                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean)

                        is TuicBean ->
                            buildSingBoxOutboundTuicBean(bean)

                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean)

                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean)

                        is WireGuardBean ->
                            buildSingBoxOutboundWireguardBean(bean)

                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean)

                        is AnyTLSBean ->
                            buildSingBoxOutboundAnyTLSBean(bean)

                        else -> throw IllegalStateException("can't reach")
                    }

                    // internal mux
                    if (!muxApplied) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj != null && muxObj.enabled) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    // domain_strategy
                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    _hack_config_map["domain_strategy"] =
                        if (forTest) "" else defaultServerDomainStrategy

                    _hack_config_map["tag"] = tagOut

                    _hack_custom_config = bean.customOutboundJson
                }

                // External proxy need a dokodemo-door inbound to forward the traffic
                // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    // With ss protect, don't use mapping
                    var needExternal = true
                    if (index == profileList.lastIndex) {
                        val pluginId = when (bean) {
                            is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria-plugin" else "hysteria2-plugin"
                            else -> ""
                        }
                        if (Plugins.isUsingMatsuriExe(pluginId)) {
                            needExternal = false
                        } else if (Plugins.getPluginExternal(pluginId) != null) {
                            throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
                        }
                    }
                    if (needExternal) {
                        val mappingPort = mkPort()
                        bean.finalAddress = LOCALHOST
                        bean.finalPort = mappingPort

                        inbounds.add(Inbound_DirectOptions().apply {
                            type = "direct"
                            listen = LOCALHOST
                            listen_port = mappingPort
                            tag = "$chainTag-mapping-${proxyEntity.id}"

                            override_address = bean.serverAddress
                            override_port = bean.serverPort

                            pastInboundTag = tag

                            // no chain rule and not outbound, so need to set to direct
                            if (index == profileList.lastIndex) {
                                route.rules.add(Rule_DefaultOptions().apply {
                                    inbound = listOf(tag)
                                    outbound = TAG_DIRECT
                                })
                            }
                        })
                    }
                }

                outbounds.add(currentOutbound)
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        fun buildOutboundEntity(chainId: Long, entity: ProxyEntity): String {
            val bean = entity.requireBean()
            if (bean is AutoGroupBean) {
                val memberTags = ArrayList<String>()
                val trafficSet = HashSet<ProxyEntity>()
                trafficSet.add(entity)
                for (proxyId in bean.proxies) {
                    val item = SagerDatabase.proxyDao.getById(proxyId) ?: continue
                    if (item.id == entity.id) continue
                    val nested = item.requireBean()
                    val tag = if (nested is AutoGroupBean) {
                        buildOutboundEntity(item.id, item)
                    } else {
                        buildChain(item.id, item)
                    }
                    memberTags.add(tag)
                    trafficSet.add(item)
                }
                val groupTag = if (chainId == 0L) TAG_PROXY else "ag-${entity.id}"
                return emitGroupOutbound(
                    groupTag,
                    memberTags,
                    memberTags.firstOrNull(),
                    bean.strategy == AutoGroupBean.STRATEGY_ORDER,
                    trafficSet.toList(),
                )
            }
            return buildChain(chainId, entity)
        }

        // build outbounds
        resultSelectorGroupId = if (buildSelector) group!!.id else -1L
        when {
            proxy.requireBean() is AutoGroupBean -> {
                tagMap[proxy.id] = buildOutboundEntity(0, proxy)
                resultSelectorGroupId = -1L
            }

            autoOutboundMode != AutoOutboundMode.OFF && group != null -> {
                val list = SagerDatabase.proxyDao.getByGroup(group.id).filter {
                    it.type != ProxyEntity.TYPE_AUTO_GROUP && it.type != ProxyEntity.TYPE_CHAIN
                }
                list.forEach {
                    tagMap[it.id] = buildOutboundEntity(it.id, it)
                }
                val memberTags = list.mapNotNull { tagMap[it.id] }
                val traffic = list.toMutableList().also { it.add(proxy) }
                emitGroupOutbound(
                    TAG_PROXY,
                    memberTags,
                    tagMap[proxy.id],
                    autoOutboundMode == AutoOutboundMode.ORDER,
                    traffic,
                )
                resultSelectorGroupId =
                    if (autoOutboundMode == AutoOutboundMode.ORDER) group.id else -1L
            }

            buildSelector -> {
                val list = group!!.id.let { SagerDatabase.proxyDao.getByGroup(it) }
                list.forEach {
                    tagMap[it.id] = buildOutboundEntity(it.id, it)
                }
                outbounds.add(0, Outbound_SelectorOptions().apply {
                    type = "selector"
                    tag = TAG_PROXY
                    default_ = tagMap[proxy.id]
                    outbounds = tagMap.values.toList()
                })
            }

            else -> buildOutboundEntity(0, proxy)
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildOutboundEntity(key, p)
        }

        // apply user rules
        for (rule in extraRules) {
            if (rule.packages.isNotEmpty()) {
                PackageCache.awaitLoadSync()
            }
            val uidList = rule.packages.map {
                if (!isVPN) {
                    Toast.makeText(
                        SagerNet.application,
                        SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                PackageCache[it]?.takeIf { uid -> uid >= 1000 }
            }.toHashSet().filterNotNull()
            val ruleSets = mutableListOf<RuleSet>()

            val ruleObj = Rule_DefaultOptions().apply {
                if (uidList.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                    user_id = uidList
                }
                var domainList: List<String>? = null
                if (rule.domains.isNotBlank()) {
                    domainList = rule.domains.listByLineOrComma()
                    makeSingBoxRule(domainList, false)
                }
                if (rule.ip.isNotBlank()) {
                    makeSingBoxRule(rule.ip.listByLineOrComma(), true)
                }

                if (rule_set != null) generateRuleSet(rule_set, ruleSets)

                if (rule.port.isNotBlank()) {
                    port = mutableListOf<Int>()
                    port_range = mutableListOf<String>()
                    rule.port.listByLineOrComma().map {
                        if (it.contains(":")) {
                            port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { port.add(this) }
                        }
                    }
                }
                if (rule.sourcePort.isNotBlank()) {
                    source_port = mutableListOf<Int>()
                    source_port_range = mutableListOf<String>()
                    rule.sourcePort.listByLineOrComma().map {
                        if (it.contains(":")) {
                            source_port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { source_port.add(this) }
                        }
                    }
                }
                if (rule.network.isNotBlank()) {
                    network = listOf(rule.network)
                }
                if (rule.source.isNotBlank()) {
                    source_ip_cidr = rule.source.listByLineOrComma()
                }
                if (rule.protocol.isNotBlank()) {
                    protocol = rule.protocol.listByLineOrComma()
                }

                fun makeDnsRuleObj(): DNSRule_DefaultOptions {
                    return DNSRule_DefaultOptions().apply {
                        if (uidList.isNotEmpty()) user_id = uidList
                        domainList?.let { makeSingBoxRule(it) }
                    }
                }

                when (rule.outbound) {
                    -1L -> {
                        userDNSRuleList += makeDnsRuleObj().apply { server = "dns-direct" }
                    }

                    0L -> {
                        if (useFakeDns) userDNSRuleList += makeDnsRuleObj().apply {
                            server = "dns-fake"
                            inbound = listOf("tun-in")
                        }
                        userDNSRuleList += makeDnsRuleObj().apply {
                            server = "dns-remote"
                        }
                    }

                    -2L -> {
                        userDNSRuleList += makeDnsRuleObj().apply {
                            server = "dns-block"
                            disable_cache = true
                        }
                    }
                }

                outbound = when (val outId = rule.outbound) {
                    0L -> TAG_PROXY
                    -1L -> TAG_BYPASS
                    -2L -> TAG_BLOCK
                    else -> if (outId == proxy.id) TAG_PROXY else tagMap[outId] ?: ""
                }

                _hack_custom_config = rule.config
            }

            if (!ruleObj.checkEmpty()) {
                if (ruleObj.outbound.isNullOrBlank()) {
                    Toast.makeText(
                        SagerNet.application,
                        "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // block 改用新的写法
                    if (ruleObj.outbound == TAG_BLOCK) {
                        ruleObj.outbound = null
                        ruleObj.action = "reject"
                    }
                    route.rules.add(ruleObj)
                    route.rule_set.addAll(ruleSets)
                }
            }
        }

        // 对 rule_set tag 去重
        if (route.rule_set != null) {
            route.rule_set = route.rule_set.distinctBy { it.tag }
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) outbounds.add(Outbound().apply {
            tag = freedom
            type = "direct"
        })

        // Trusted Wi‑Fi hot-switch: ensure TAG_PROXY selector can select TAG_BYPASS.
        if (wifiDirectFeature) {
            val proxyOb = outbounds.firstOrNull { ob ->
                (ob as? Outbound)?.tag == TAG_PROXY
            }
            when (proxyOb) {
                is Outbound_SelectorOptions -> {
                    val members = proxyOb.outbounds?.toMutableList() ?: mutableListOf()
                    if (TAG_BYPASS !in members) members.add(TAG_BYPASS)
                    proxyOb.outbounds = members
                    if (wifiDirect) proxyOb.default_ = TAG_BYPASS
                    proxyOb._hack_config_map["interrupt_exist_connections"] = true
                    wifiDirectHotSwitch = true
                    wifiDirectProxyTag = "" // resolve via selected profile tag at runtime
                }

                is Outbound -> {
                    // Wrap urltest / single outbound as proxy-main under a mode selector.
                    proxyOb.tag = TAG_PROXY_MAIN
                    if (trafficMap.containsKey(TAG_PROXY)) {
                        trafficMap[TAG_PROXY_MAIN] = trafficMap.remove(TAG_PROXY)!!
                    }
                    outbounds.add(
                        0,
                        Outbound_SelectorOptions().apply {
                            type = "selector"
                            tag = TAG_PROXY
                            outbounds = listOf(TAG_PROXY_MAIN, TAG_BYPASS)
                            default_ = if (wifiDirect) TAG_BYPASS else TAG_PROXY_MAIN
                            _hack_config_map["interrupt_exist_connections"] = true
                        },
                    )
                    wifiDirectHotSwitch = true
                    wifiDirectProxyTag = TAG_PROXY_MAIN
                }
            }
        }

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                domainListDNSDirectForce.add("full:${serverAddr}")
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        dns.servers.add(DNSServerOptions().apply {
            address = "rcode://success"
            tag = "dns-block"
        })

        dns.servers.add(DNSServerOptions().apply {
            address = "local"
            tag = "dns-local"
            detour = TAG_DIRECT
        })

        directDNS.firstOrNull().let {
            dns.servers.add(DNSServerOptions().apply {
                address = it ?: throw Exception("No direct DNS, check your settings!")
                tag = "dns-direct"
                detour = TAG_DIRECT
                address_resolver = "dns-local"
                strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))
            })
        }

        remoteDns.firstOrNull().let {
            // Always use direct DNS for urlTest
            if (!forTest) dns.servers.add(DNSServerOptions().apply {
                address = it ?: throw Exception("No remote DNS, check your settings!")
                tag = "dns-remote"
                address_resolver = "dns-direct"
                strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))
            })
        }

        // With Trusted Wi‑Fi hot-switch, DNS follows the selected default outbound
        // (proxy or bypass) via dns-remote — no rebuild needed on Wi‑Fi change.
        dns.final_ = when {
            forTest -> "dns-direct"
            wifiDirectFeature -> "dns-remote"
            wifiDirect -> "dns-direct"
            else -> "dns-remote"
        }

        // dns object user rules
        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }

        // Tailscale：作为 endpoint 接入。其控制面/数据面连接通过 detour 强制走当前选中的
        // 订阅出站(TAG_PROXY),满足“连接先经订阅、再连接境外 tailscale”的要求;
        // 同时把 tailnet 网段路由到该 endpoint,使发往 tailnet 的流量经此转发。
        if (!forTest && DataStore.tailscaleEnabled && DataStore.tailscaleAuthKey.isNotBlank()) {
            val tsTag = "tailscale"
            // 控制面 DNS 始终走直连解析：dns-remote 依赖 TAG_PROXY，选中节点失效
            // （如 Reality 校验失败）时会拖垮 Tailscale 启动，进而表现为订阅/代理起不来。
            // TCP 经 detour=TAG_PROXY：热切时 selector 选到 bypass 即等价直连。
            val tsDetour =
                if (wifiDirect && !wifiDirectFeature) TAG_DIRECT else TAG_PROXY
            endpoints = mutableListOf<SingBoxOption>().apply {
                add(Endpoint_TailscaleOptions().apply {
                    type = "tailscale"
                    tag = tsTag
                    detour = tsDetour
                    domain_resolver = "dns-direct"
                    auth_key = DataStore.tailscaleAuthKey
                    accept_routes = DataStore.tailscaleAcceptRoutes
                    if (DataStore.tailscaleControlUrl.isNotBlank()) {
                        control_url = DataStore.tailscaleControlUrl
                    }
                    if (DataStore.tailscaleHostname.isNotBlank()) {
                        hostname = DataStore.tailscaleHostname
                    }
                    if (DataStore.tailscaleExitNode.isNotBlank()) {
                        exit_node = DataStore.tailscaleExitNode
                    }
                })
            }
            // tailnet CGNAT 网段 (IPv4 100.64.0.0/10, IPv6 fd7a:115c:a1e0::/48) 走 tailscale endpoint
            route.rules.add(0, Rule_DefaultOptions().apply {
                ip_cidr = listOf("100.64.0.0/10", "fd7a:115c:a1e0::/48")
                outbound = tsTag
            })
            // 解析 Tailscale 控制面域名时强制走直连 DNS，避免与代理节点健康状况耦合
            val tsDnsDomains = mutableListOf(
                "controlplane.tailscale.com",
                "login.tailscale.com",
                "log.tailscale.com",
            )
            DataStore.tailscaleControlUrl.trim().toHttpUrlOrNull()?.host?.let { host ->
                if (host.isNotBlank() && !host.isIpAddress()) tsDnsDomains.add(host)
            }
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                domain = tsDnsDomains.distinct()
                server = "dns-direct"
            })
        }

        if (forTest) {
            dns.rules = listOf()
        } else {
            // built-in DNS rules
            route.rules.add(0, Rule_DefaultOptions().apply {
                protocol = listOf("dns")
                action = "hijack-dns"
            })
            route.rules.add(0, Rule_DefaultOptions().apply {
                port = listOf(53)
                action = "hijack-dns"
            })
            if (DataStore.bypassLanInCore) {
                route.rules.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    ip_is_private = true
                })
            }
            // block mcast
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            // FakeDNS obj
            if (useFakeDns) {
                dns.fakeip = DNSFakeIPOptions().apply {
                    enabled = true
                    inet4_range = "198.18.0.0/15"
                    inet6_range = "fc00::/18"
                }
                dns.servers.add(DNSServerOptions().apply {
                    address = "fakeip"
                    tag = "dns-fake"
                    strategy = "ipv4_only"
                })
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    inbound = listOf("tun-in")
                    server = "dns-fake"
                    disable_cache = true
                })
            }
            // avoid loopback
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                outbound = mutableListOf("any")
                server = "dns-direct"
            })
            // force bypass (always top DNS rule)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                })
            }
        }

        if (!forTest) _hack_custom_config = DataStore.globalCustomConfig
    }.let {
        val configMap = it.asMap()
        Util.mergeJSON(configMap, proxy.requireBean().customConfigJson)
        ConfigBuildResult(
            gson.toJson(configMap),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            resultSelectorGroupId,
            orderFallbackGroups.toList(),
            wifiDirectHotSwitch,
            wifiDirectProxyTag,
        )
    }

}
