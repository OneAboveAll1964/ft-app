package app.ft.core

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import app.ft.FTTouchService
import app.ft.aa.AaHeadUnitService
import app.ft.carlife.CarLifeService
import java.net.NetworkInterface

object DiagSnapshot {
    private val UUID_NAMES = mapOf(
        "a45bc7e5-bb50-4949-9de1-f78299cf6d78" to "CarLife",
        "00001101-0000-1000-8000-00805f9b34fb" to "SPP",
        "4de17a00-52cb-11e6-bdf4-0800200c9a66" to "AndroidAutoWireless",
        "0000110a-0000-1000-8000-00805f9b34fb" to "A2DP-source",
        "0000110b-0000-1000-8000-00805f9b34fb" to "A2DP-sink",
        "0000110c-0000-1000-8000-00805f9b34fb" to "AVRCP-target",
        "0000110e-0000-1000-8000-00805f9b34fb" to "AVRCP",
        "0000111e-0000-1000-8000-00805f9b34fb" to "HFP",
        "0000111f-0000-1000-8000-00805f9b34fb" to "HFP-gateway",
        "00001112-0000-1000-8000-00805f9b34fb" to "HSP-gateway",
        "0000112f-0000-1000-8000-00805f9b34fb" to "PBAP",
        "00001132-0000-1000-8000-00805f9b34fb" to "MAP",
        "00001116-0000-1000-8000-00805f9b34fb" to "PAN-NAP",
        "00001200-0000-1000-8000-00805f9b34fb" to "PnP"
    )
    private val SECRET = Regex("(?i)(pin|pass|secret|token|psk)")
    private const val GEARHEAD = "com.google.android.projection.gearhead"

    private fun line(label: String, block: () -> Any?): String =
        "$label: " + (runCatching { block()?.toString() ?: "-" }.getOrElse { "error ${it.javaClass.simpleName} ${it.message ?: ""}" })

    fun device(c: Context): List<String> = buildList {
        add(line("ft") { pkg(c, c.packageName)?.let { "${it.versionName} (code ${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else 0}), installed ${java.util.Date(it.firstInstallTime)}, updated ${java.util.Date(it.lastUpdateTime)}" } })
        add(line("installer") { if (Build.VERSION.SDK_INT >= 30) c.packageManager.getInstallSourceInfo(c.packageName).installingPackageName else null })
        add(line("phone") { "${Build.MANUFACTURER} ${Build.MODEL} brand=${Build.BRAND} device=${Build.DEVICE} product=${Build.PRODUCT} board=${Build.BOARD} hardware=${Build.HARDWARE}" })
        add(line("chip") { if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE })
        add(line("android") { "${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT} patch ${Build.VERSION.SECURITY_PATCH} build ${Build.DISPLAY}" })
        add(line("fingerprint") { Build.FINGERPRINT })
        add(line("abis") { Build.SUPPORTED_ABIS.joinToString() })
        add(line("kernel") { System.getProperty("os.version") })
        add(line("uptime") { "${android.os.SystemClock.elapsedRealtime() / 60000} min since boot" })
        add(line("locale") { "${java.util.Locale.getDefault()} tz=${java.util.TimeZone.getDefault().id}" })
        add(line("memory") {
            val am = c.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            "total ${mi.totalMem / 1048576} MB, free ${mi.availMem / 1048576} MB, low=${mi.lowMemory}, ft memory class ${am.memoryClass} MB"
        })
        add(line("battery") {
            val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = when (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) { 1 -> "AC"; 2 -> "USB"; 4 -> "wireless"; 8 -> "dock"; 0 -> "unplugged"; else -> "?" }
            "${b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)}% $plugged ${(b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0}C"
        })
        add(line("power") {
            val pm = c.getSystemService(PowerManager::class.java)
            "saver=${pm.isPowerSaveMode} thermal=${pm.currentThermalStatus} idle=${pm.isDeviceIdleMode} interactive=${pm.isInteractive}"
        })
        add(line("displays") {
            c.getSystemService(DisplayManager::class.java).displays.joinToString(" | ") { d ->
                val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") d.getRealMetrics(it) }
                "#${d.displayId} '${d.name}' ${m.widthPixels}x${m.heightPixels} ${m.densityDpi}dpi ${"%.0f".format(d.refreshRate)}Hz flags=0x${Integer.toHexString(d.flags)}"
            }
        })
    }

    fun app(c: Context): List<String> = buildList {
        val pm = c.getSystemService(PowerManager::class.java)
        val am = c.getSystemService(ActivityManager::class.java)
        add(line("root") { Root.state })
        add(line("battery optimization ignored") { pm.isIgnoringBatteryOptimizations(c.packageName) })
        add(line("background restricted") { if (Build.VERSION.SDK_INT >= 28) am.isBackgroundRestricted else null })
        add(line("standby bucket") { if (Build.VERSION.SDK_INT >= 28) c.getSystemService(UsageStatsManager::class.java).appStandbyBucket else null })
        add(line("notifications allowed") { c.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() })
        add(line("touch control (accessibility)") {
            val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            "enabled=${list.contains("${c.packageName}/")} running=${FTTouchService.instance != null}"
        })
        add(line("draw over apps") { Settings.canDrawOverlays(c) })
        add(line("screen sharing without asking") { appop(c, "android:project_media") })
        add(line("permissions") {
            val info = c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_PERMISSIONS)
            val names = info.requestedPermissions.orEmpty()
            val flags = info.requestedPermissionsFlags ?: IntArray(names.size)
            names.indices.joinToString { i ->
                val granted = flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
                "${names[i].substringAfterLast('.')}=${if (granted) "yes" else "NO"}"
            }
        })
        add(line("carlife state") { CarLifeService.state.value })
        add(line("android auto bridge state") { AaHeadUnitService.state.value })
    }

    fun settings(c: Context): List<String> = runCatching {
        listOf("(only settings that were changed are listed, the rest are at their defaults)") +
            c.getSharedPreferences("ft", Context.MODE_PRIVATE).all.toSortedMap().map { (k, v) ->
                "$k = " + if (SECRET.containsMatchIn(k) && v?.toString()?.isNotEmpty() == true) "<hidden, ${v.toString().length} chars>" else v.toString()
            }
    }.getOrElse { listOf("settings: error ${it.message}") }

    @SuppressLint("MissingPermission")
    fun radios(c: Context): List<String> = buildList {
        val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        add(line("radios") { app.ft.ui.home.readRadios(c) })
        add(line("wifi") {
            @Suppress("DEPRECATION") val ci = wm?.connectionInfo
            val other = otherWifiMhz(c)
            "on=${wm?.isWifiEnabled} 5GHz=${wm?.is5GHzBandSupported} p2p=${c.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)} " +
                "connected to other wifi=${if (other != null) "yes, ${band(other)} ${other}MHz" else "no"} rssi=${ci?.rssi} link=${ci?.linkSpeed}Mbps"
        })
        add(line("usb") {
            val um = c.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
            val st = c.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))?.extras
            val on = st?.keySet()?.sorted()?.filter { st.getBoolean(it, false) }.orEmpty()
            "state: ${on.joinToString().ifBlank { "nothing" }} accessories: ${um?.accessoryList?.joinToString { "${it.manufacturer} ${it.model} ${it.version}" } ?: "none"} devices: ${um?.deviceList?.size ?: 0}"
        })
        add(line("network") {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.joinToString(" | ") { n ->
                val mobile = Regex("^(rmnet|ccmni|v4-rmnet|clat|ipa|ifb|dummy|r_rmnet)").containsMatchIn(n.name)
                val addrs = if (mobile) "mobile data" else n.inetAddresses.toList().joinToString { a -> a.hostAddress.orEmpty().substringBefore('%') }
                "${n.name} [$addrs]"
            }
        })
        add(line("hotspot address") { app.ft.carlife.NetUtil.hotspotIpv4() })
        val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        add(line("bluetooth") { "on=${adapter?.isEnabled} state=${adapter?.state} scanning=${runCatching { adapter?.isDiscovering }.getOrNull()}" })
        val carAddress = runCatching { c.getSharedPreferences("ft", Context.MODE_PRIVATE).getString("carBtAddress", "") }.getOrNull().orEmpty()
        runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrElse { add("paired: error ${it.message}"); emptyList() }.forEach { d ->
            add(line("paired") {
                val name = runCatching { d.name }.getOrNull() ?: "?"
                val address = if (d.address.equals(carAddress, true)) d.address else d.address.substring(0, 8) + ":xx:xx:xx"
                val cls = runCatching { Integer.toHexString(d.bluetoothClass?.deviceClass ?: 0) }.getOrNull()
                val uuids = runCatching { d.uuids?.joinToString { u -> UUID_NAMES[u.uuid.toString().lowercase()] ?: u.uuid.toString() } }.getOrNull()
                "'$name' $address class=$cls type=${runCatching { d.type }.getOrNull()} services: ${uuids ?: "unknown"}"
            })
        }
    }

    fun androidAuto(c: Context): List<String> = buildList {
        add(line("android auto") { pkg(c, GEARHEAD)?.let { "${it.versionName} (code ${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else 0}) enabled=${it.applicationInfo?.enabled}" } ?: "not installed" })
        add(line("head unit server ${prefsInt(c, "aaSelfPort", 5277)}") { if (app.ft.aa.AaInstaller.serverRunning(prefsInt(c, "aaSelfPort", 5277))) "running" else "off" })
        add(line("ft head unit listener") { AaHeadUnitService.state.value.listening })
        add(line("ft stash") { app.ft.aa.AaInstaller.step(c) })
        listOf("com.baidu.carlife", "com.huawei.hicar").forEach { p ->
            add(line("other car app $p") { pkg(c, p)?.let { "${it.versionName} installed" } ?: "not installed" })
        }
    }

    fun audio(c: Context): List<String> = buildList {
        val am = c.getSystemService(AudioManager::class.java)
        add(line("audio") { "mode=${am.mode} music active=${am.isMusicActive} rate=${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} frames=${am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)} volume=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" })
        add(line("outputs") { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { d -> "${d.type}:${d.productName}" + if (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) "(bt)" else "" } })
    }

    fun codecs(): List<String> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.supportedTypes.any { it.equals("video/avc", true) || it.equals("video/hevc", true) }
        }.map { info ->
            val type = info.supportedTypes.first { it.startsWith("video/") }
            val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull()
            val v = caps?.videoCapabilities
            fun rate(w: Int, h: Int) = runCatching { v?.getSupportedFrameRatesFor(w, h)?.upper?.toInt()?.toString() }.getOrNull() ?: "no"
            "${if (info.isEncoder) "encoder" else "decoder"} ${info.name} $type hw=${if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else "?"} " +
                "w=${v?.supportedWidths} h=${v?.supportedHeights} bitrate=${v?.bitrateRange} fps1920x720=${rate(1920, 720)} fps1920x1080=${rate(1920, 1080)} fps1280x720=${rate(1280, 720)} " +
                "profiles=${caps?.profileLevels?.joinToString(",") { "${it.profile}/${it.level}" }} surface=${caps?.colorFormats?.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)}"
        }
    }.getOrElse { listOf("codecs: error ${it.message}") }

    fun connectionStart(c: Context): List<String> = buildList {
        addAll(app(c).filterNot { it.startsWith("carlife state") || it.startsWith("android auto bridge state") })
        addAll(radios(c))
        addAll(androidAuto(c))
    }

    fun otherWifiMhz(c: Context): Int? = runCatching {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            .firstOrNull { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) } ?: return@runCatching null
        val info = if (Build.VERSION.SDK_INT >= 31) caps.transportInfo as? android.net.wifi.WifiInfo else null
        @Suppress("DEPRECATION")
        val mhz = info?.frequency ?: (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.frequency
        mhz?.takeIf { it > 0 } ?: 0
    }.getOrNull()

    fun band(mhz: Int): String = when {
        mhz <= 0 -> "unknown band"
        mhz < 3000 -> "2.4 GHz"
        mhz < 5925 -> "5 GHz"
        else -> "6 GHz"
    }

    private fun pkg(c: Context, name: String): PackageInfo? = runCatching { c.packageManager.getPackageInfo(name, 0) }.getOrNull()

    private fun appop(c: Context, op: String): String = runCatching {
        when (c.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(op, Process.myUid(), c.packageName)) {
            AppOpsManager.MODE_ALLOWED -> "allowed"; AppOpsManager.MODE_IGNORED -> "ignored"; AppOpsManager.MODE_ERRORED -> "denied"; else -> "default"
        }
    }.getOrElse { "error ${it.message}" }

    private fun prefsInt(c: Context, key: String, default: Int): Int =
        runCatching { c.getSharedPreferences("ft", Context.MODE_PRIVATE).getInt(key, default) }.getOrDefault(default)
}
