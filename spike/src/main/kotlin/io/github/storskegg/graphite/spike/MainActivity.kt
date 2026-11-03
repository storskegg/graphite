package io.github.storskegg.graphite.spike

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Throwaway feasibility spike. Every result goes to logcat (tag GSPIKE), the screen,
// and files/spike.log. Buttons can also be fired with: am start -n PKG/.MainActivity --es run NAME
class MainActivity : Activity() {
    lateinit var out: TextView
    lateinit var scroll: ScrollView
    val um by lazy { getSystemService(UsbManager::class.java)!! }
    val worker = Executors.newSingleThreadExecutor()
    val logFile by lazy { File(filesDir, "spike.log") }

    val actions = linkedMapOf<String, () -> Unit>(
        "status" to { status() },
        "aoa" to { Aoa(this).host() },
        "accessory" to { Aoa(this).serve() },
        "adb" to { AdbProbe(this).run(install = false) },
        "adbinstall" to { AdbProbe(this).run(install = true) },
        "perms" to { requestRestricted() },
        "media" to { MediaTest(this).run() },
        "video" to { MediaTest(this).videos() },
        "mediaquery" to { MediaTest(this).query() },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A locked GrapheneOS phone turns its port charging-only: never let the screen time out.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (name in actions.keys) buttons.addView(Button(this).apply {
            text = name
            setOnClickListener { fire(name) }
        })
        out = TextView(this).apply { setTextIsSelectable(true); textSize = 11f }
        scroll = ScrollView(this).apply { addView(out) }
        col.addView(buttons)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(col)
        col.fitsSystemWindows = true
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    fun handle(i: Intent?) {
        if (i == null) return
        if (i.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) fire("accessory")
        i.getStringExtra("run")?.let { fire(it) }
    }

    fun fire(name: String) {
        log("== $name (${Build.MODEL}, Android ${Build.VERSION.RELEASE}, ${Build.DISPLAY})")
        if (name == "perms") { requestRestricted(); return }
        worker.execute {
            try { actions[name]?.invoke() ?: log("unknown action $name") }
            catch (t: Throwable) { log("FAILED: ${Log.getStackTraceString(t)}") }
        }
    }

    fun log(s: String) {
        Log.i("GSPIKE", s)
        synchronized(this) { logFile.appendText(s + "\n") }
        runOnUiThread {
            out.append(s + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    fun status() {
        log("devices (we are USB host if any): ${um.deviceList.size}")
        for (d in um.deviceList.values) log("  " + describe(d))
        log("accessories (we are in accessory mode if any): ${um.accessoryList?.toList()}")
    }

    fun describe(d: UsbDevice): String = buildString {
        append("%04x:%04x %s %s".format(d.vendorId, d.productId, d.manufacturerName, d.productName))
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            append("\n    if$i class=${f.interfaceClass} sub=${f.interfaceSubclass} proto=${f.interfaceProtocol}")
            for (e in 0 until f.endpointCount) {
                val ep = f.getEndpoint(e)
                append(" ep%02x/%d".format(ep.address, ep.maxPacketSize))
            }
        }
    }

    // Blocks the worker until the user answers the system USB permission dialog.
    fun awaitPermission(d: UsbDevice? = null, a: UsbAccessory? = null): Boolean {
        if (d != null && um.hasPermission(d)) return true
        if (a != null && um.hasPermission(a)) return true
        val action = "$packageName.USB_PERMISSION"
        val latch = CountDownLatch(1)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { latch.countDown() }
        }
        registerReceiver(r, IntentFilter(action), Context.RECEIVER_EXPORTED)
        val pi = PendingIntent.getBroadcast(this, 0, Intent(action).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        log("asking for USB permission (tap Allow)")
        if (d != null) um.requestPermission(d, pi) else um.requestPermission(a, pi)
        latch.await(90, TimeUnit.SECONDS)
        unregisterReceiver(r)
        val ok = if (d != null) um.hasPermission(d) else um.hasPermission(a)
        log("USB permission: $ok")
        return ok
    }

    val restricted = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG)

    fun requestRestricted() {
        val src = packageManager.getInstallSourceInfo(packageName)
        log("install source: installer=${src.installingPackageName} initiator=${src.initiatingPackageName} packageSource=${src.packageSource}")
        for (p in restricted) log("  before: $p granted=${checkSelfPermission(p) == 0} rationale=${shouldShowRequestPermissionRationale(p)}")
        requestPermissions(restricted, 7)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        for (i in perms.indices) log("  result: ${perms[i]} granted=${res[i] == 0}")
    }
}
