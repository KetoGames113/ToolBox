package com.toolbox.app

import android.content.Context
import android.net.VpnService
import androidx.compose.runtime.*
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/** VPN سريع التشغيل بتقنية WireGuard: تشغيل بنقرة (Cloudflare WARP المجاني) أو استيراد أي إعداد WireGuard. */
object Vpn {
    private const val LAN4 = "0.0.0.0/5, 8.0.0.0/7, 11.0.0.0/8, 12.0.0.0/6, 16.0.0.0/4, 32.0.0.0/3, 64.0.0.0/3, 96.0.0.0/4, 112.0.0.0/5, 120.0.0.0/6, 124.0.0.0/7, 126.0.0.0/8, 128.0.0.0/3, 160.0.0.0/5, 168.0.0.0/8, 169.0.0.0/9, 169.128.0.0/10, 169.192.0.0/11, 169.224.0.0/12, 169.240.0.0/13, 169.248.0.0/14, 169.252.0.0/15, 169.255.0.0/16, 170.0.0.0/7, 172.0.0.0/12, 172.32.0.0/11, 172.64.0.0/10, 172.128.0.0/9, 173.0.0.0/8, 174.0.0.0/7, 176.0.0.0/4, 192.0.0.0/9, 192.128.0.0/11, 192.160.0.0/13, 192.169.0.0/16, 192.170.0.0/15, 192.172.0.0/14, 192.176.0.0/12, 192.192.0.0/10, 193.0.0.0/8, 194.0.0.0/7, 196.0.0.0/6, 200.0.0.0/5, 208.0.0.0/4, 240.0.0.0/5, 248.0.0.0/6, 252.0.0.0/7, 254.0.0.0/8, 255.0.0.0/9, 255.128.0.0/10, 255.192.0.0/11, 255.224.0.0/12, 255.240.0.0/13, 255.248.0.0/14, 255.252.0.0/15, 255.254.0.0/16, 255.255.0.0/17, 255.255.128.0/18, 255.255.192.0/19, 255.255.224.0/20, 255.255.240.0/21, 255.255.248.0/22, 255.255.252.0/23, 255.255.254.0/24, 255.255.255.0/25, 255.255.255.128/26, 255.255.255.192/27, 255.255.255.224/28, 255.255.255.240/29, 255.255.255.248/30, 255.255.255.252/31, 255.255.255.254/32"
    private const val LAN6 = "::/1, 8000::/2, c000::/3, e000::/4, f000::/5, f800::/6, fe00::/9, fec0::/10"
    private val WARP_ENDPOINTS = listOf("engage.cloudflareclient.com:2408", "engage.cloudflareclient.com:500",
        "engage.cloudflareclient.com:1701", "engage.cloudflareclient.com:4500", "162.159.192.1:2408", "162.159.193.1:2408")
    val DNS_OPTIONS = listOf("" to "تلقائي", "1.1.1.1, 1.0.0.1" to "Cloudflare", "8.8.8.8, 8.8.4.4" to "Google", "9.9.9.9, 149.112.112.112" to "Quad9", "1.1.1.2, 1.0.0.2" to "Cloudflare (حماية)")

    var state by mutableIntStateOf(0)       // 0 متوقف، 1 يتصل، 2 متصل، 3 خطأ
    var msg by mutableStateOf("")
    var rx by mutableLongStateOf(0L)
    var tx by mutableLongStateOf(0L)
    var endpoint by mutableStateOf("")
    private var backend: GoBackend? = null
    private val tunnel = object : Tunnel {
        override fun getName(): String = "toolbox"
        override fun onStateChange(newState: Tunnel.State) { if (newState == Tunnel.State.DOWN && state == 2) state = 0 }
    }

    private fun p() = App.i.prefs()
    fun hasConfig() = !p().getString("vpn_conf", "").isNullOrBlank()
    fun kind() = p().getString("vpn_kind", "") ?: ""
    var mode: Int get() = p().getInt("vpn_mode", 0); set(v) { p().edit().putInt("vpn_mode", v).apply() }
    var lan: Boolean get() = p().getBoolean("vpn_lan", true); set(v) { p().edit().putBoolean("vpn_lan", v).apply() }
    var dns: Int get() = p().getInt("vpn_dns", 0); set(v) { p().edit().putInt("vpn_dns", v).apply() }
    var auto: Boolean get() = p().getBoolean("vpn_auto", false); set(v) { p().edit().putBoolean("vpn_auto", v).apply() }
    var apps: Set<String> get() = p().getStringSet("vpn_apps", emptySet()) ?: emptySet(); set(v) { p().edit().putStringSet("vpn_apps", v).apply() }

    fun saveConfig(text: String, kind: String) { p().edit().putString("vpn_conf", text).putString("vpn_kind", kind).apply() }
    fun clearConfig() { p().edit().remove("vpn_conf").remove("vpn_kind").apply() }
    fun validate(text: String): String? = try { Config.parse(ByteArrayInputStream(text.toByteArray())); null } catch (t: Throwable) { t.message ?: "invalid config" }

    /** تسجيل جهاز مجاني لدى Cloudflare WARP وبناء إعداد WireGuard. */
    suspend fun registerWarp(): String = withContext(Dispatchers.IO) {
        val kp = KeyPair()
        val priv = kp.privateKey.toBase64(); val pub = kp.publicKey.toBase64()
        val body = JSONObject().put("key", pub).put("install_id", "").put("fcm_token", "")
            .put("tos", "2023-01-01T00:00:00.000Z").put("type", "Android").put("model", "PC").put("locale", "en_US")
        val c = URL("https://api.cloudflareclient.com/v0a2158/reg").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        c.setRequestProperty("User-Agent", "okhttp/3.12.1")
        c.setRequestProperty("CF-Client-Version", "a-6.3-2158")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) throw IllegalStateException("WARP: HTTP " + c.responseCode)
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val cfg = j.getJSONObject("config")
        val peer = cfg.getJSONArray("peers").getJSONObject(0)
        val addr = cfg.getJSONObject("interface").getJSONObject("addresses")
        val v6 = addr.optString("v6", "")
        val addrs = listOf(addr.getString("v4") + "/32") + (if (v6.isNotEmpty()) listOf("$v6/128") else emptyList())
        "[Interface]\nPrivateKey = $priv\nAddress = ${addrs.joinToString(", ")}\nDNS = 1.1.1.1, 1.0.0.1\nMTU = 1280\n\n" +
            "[Peer]\nPublicKey = ${peer.getString("public_key")}\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = ${WARP_ENDPOINTS[0]}\nPersistentKeepalive = 25\n"
    }

    /** يطبّق: تجاوز الشبكة المحلية، تقسيم التطبيقات، DNS، والنقطة الطرفية. */
    fun buildText(base: String, ep: String?): String {
        var out = ArrayList<String>()
        val lines = base.lines()
        val dnsSel = DNS_OPTIONS.getOrNull(dns)?.first ?: ""
        val appList = apps.joinToString(", ")
        for (raw in lines) {
            val l = raw.trim()
            val low = l.lowercase()
            when {
                low.startsWith("includedapplications") || low.startsWith("excludedapplications") -> {}
                low.startsWith("dns") && dnsSel.isNotEmpty() && low.substringBefore("=").trim() == "dns" -> {}
                low.startsWith("endpoint") && ep != null -> out.add("Endpoint = $ep")
                low.startsWith("allowedips") && lan -> {
                    val toks = l.substringAfter("=").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val nt = ArrayList<String>()
                    for (t in toks) when (t) { "0.0.0.0/0" -> nt.add(LAN4); "::/0" -> nt.add(LAN6); else -> nt.add(t) }
                    out.add("AllowedIPs = " + nt.joinToString(", "))
                }
                else -> out.add(raw)
            }
            if (low == "[interface]") {
                if (dnsSel.isNotEmpty()) out.add("DNS = $dnsSel")
                if (mode == 1 && appList.isNotEmpty()) out.add("IncludedApplications = $appList")
                if (mode == 2 && appList.isNotEmpty()) out.add("ExcludedApplications = $appList")
            }
        }
        return out.joinToString("\n")
    }

    private fun setState(ctx: Context, up: Boolean, text: String?) {
        val b = backend ?: GoBackend(ctx.applicationContext).also { backend = it }
        if (up) b.setState(tunnel, Tunnel.State.UP, Config.parse(ByteArrayInputStream(text!!.toByteArray())))
        else b.setState(tunnel, Tunnel.State.DOWN, null)
    }

    private fun gotReply(): Boolean = try { (backend?.getStatistics(tunnel)?.totalRx() ?: 0L) > 0L } catch (_: Throwable) { false }

    /** اتصال ذكي: يجرّب النقاط الطرفية بالتتابع حتى يصل أول رد. */
    suspend fun connect(ctx: Context) {
        val base = p().getString("vpn_conf", "") ?: ""
        if (base.isBlank()) { state = 3; msg = tr("لا يوجد إعداد VPN", "No VPN configuration"); return }
        if (mode == 1 && apps.isEmpty()) { state = 3; msg = tr("اختر التطبيقات أولاً", "Choose apps first"); return }
        state = 1; msg = ""; rx = 0; tx = 0
        val eps: List<String?> = if (kind() == "warp") WARP_ENDPOINTS else listOf(null)
        withContext(Dispatchers.IO) {
            var last = ""
            for (ep in eps) {
                try {
                    endpoint = ep ?: ""
                    setState(ctx, true, buildText(base, ep))
                    var waited = 0
                    while (waited < 6000 && !gotReply()) { delay(400); waited += 400 }
                    if (gotReply() || kind() != "warp") { state = 2; msg = ""; return@withContext }
                    setState(ctx, false, null)
                } catch (t: Throwable) { last = t.message ?: "error"; try { setState(ctx, false, null) } catch (_: Throwable) {} }
            }
            state = 3; msg = if (last.isNotEmpty()) last else tr("تعذّر الاتصال بأي نقطة", "Couldn't reach any endpoint")
        }
    }

    suspend fun disconnect(ctx: Context) {
        withContext(Dispatchers.IO) { try { setState(ctx, false, null) } catch (_: Throwable) {} }
        state = 0; msg = ""; rx = 0; tx = 0
    }

    suspend fun poll() {
        while (state == 2) {
            try { val s = backend?.getStatistics(tunnel); if (s != null) { rx = s.totalRx(); tx = s.totalTx() } } catch (_: Throwable) {}
            delay(1000)
        }
    }

    fun needsPermission(ctx: Context) = VpnService.prepare(ctx) != null
}
