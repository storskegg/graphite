package io.github.storskegg.graphite

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import io.github.storskegg.graphite.ui.Answer
import io.github.storskegg.graphite.ui.Screen
import java.util.concurrent.CompletableFuture

class App : Application() {
    lateinit var controller: Controller
        private set

    override fun onCreate() {
        super.onCreate()
        controller = Controller(this)
    }
}

val Activity.controller get() = (application as App).controller

/**
 * The only activity. It draws the controller's current [Screen] and passes
 * taps back; all the logic is in the flows. It keeps the screen on: a locked
 * GrapheneOS phone turns its USB port charging-only.
 */
class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var content: LinearLayout
    private val listener = { render() }
    private var drawn: Screen? = null
    private var prompting = false
    private var authError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = (16 * resources.displayMetrics.density).toInt()
        statusView = TextView(this).apply { textSize = 12f; setPadding(0, 0, 0, pad / 2) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(statusView)
            addView(content)
        }
        setContentView(ScrollView(this).apply { addView(col); fitsSystemWindows = true })
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != 0) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        current = this
        handleIntent(intent)
        controller.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        // A finished session starts again when graphite is opened again or the other phone reconnects.
        controller.start()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) log("accessory attached (launched by the other phone)")
        if (controller.debuggable && i?.hasExtra("test_scope") == true) controller.testScope = i.getStringExtra("test_scope")
    }

    override fun onStart() {
        super.onStart()
        controller.onChange(listener)
        drawn = null
        render()
    }

    override fun onStop() {
        super.onStop()
        controller.removeListener(listener)
        // Leaving graphite ends the proof: the user authenticates again on return.
        if (!isChangingConfigurations) controller.gate.invalidate("graphite left the foreground")
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    private fun render() {
        val c = controller
        val st = c.status
        statusView.text = listOf(st.role, st.usb, st.auth).filter { it.isNotEmpty() }.joinToString("\n")
        val s = c.screen
        if (s === drawn) return
        drawn = s
        authError = null
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = s.title; textSize = 22f; setTypeface(typeface, Typeface.BOLD)
        })
        if (s.code != null) content.addView(TextView(this).apply {
            text = s.code.chunked(3).joinToString(" ")
            textSize = 48f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        })
        if (s.body.isNotEmpty()) content.addView(TextView(this).apply {
            text = s.body; textSize = 16f; setPadding(0, 16, 0, 16); setTextIsSelectable(true)
        })
        s.progress?.let { p ->
            content.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                if (p.total > 0) { max = 1000; progress = (p.done * 1000 / p.total).toInt() } else isIndeterminate = true
            })
            content.addView(TextView(this).apply { text = p.text; textSize = 14f })
        }
        val checks = mutableMapOf<String, () -> Boolean>()
        s.choices.groupBy { it.group }.forEach { (group, items) ->
            if (group == null) items.forEach { ch ->
                val box = CheckBox(this).apply {
                    text = if (ch.detail.isEmpty()) ch.label else "${ch.label}\n${ch.detail}"
                    isChecked = ch.checked; isEnabled = ch.enabled
                }
                checks[ch.id] = { box.isChecked }
                content.addView(box)
            } else {
                val rg = RadioGroup(this)
                items.forEach { ch ->
                    val rb = RadioButton(this).apply {
                        id = View.generateViewId()
                        text = if (ch.detail.isEmpty()) ch.label else "${ch.label}\n${ch.detail}"
                        isEnabled = ch.enabled
                    }
                    rg.addView(rb)
                    if (ch.checked) rg.check(rb.id)
                    checks[ch.id] = { rb.isChecked }
                }
                content.addView(rg)
            }
        }
        s.buttons.forEach { label ->
            content.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    if (s.authReason != null) promptAuth(s.authReason)
                    else c.answer(Answer(label, checks.filterValues { it() }.keys))
                }
            })
        }
        if (s.authReason != null) promptAuth(s.authReason)
    }

    private fun promptAuth(reason: String) {
        if (prompting) return
        prompting = true
        controller.gate.authenticate(this, reason) { err ->
            prompting = false
            if (err == null) controller.answer(Answer("authenticated", emptySet()))
            else {
                authError = err
                content.addView(TextView(this).apply { text = "Not confirmed: $err"; textSize = 14f })
            }
        }
    }

    // Removal shows Android's own trash confirmation, which needs an activity.
    private var intentResult: CompletableFuture<Boolean>? = null

    fun launchForResult(sender: IntentSender): CompletableFuture<Boolean> {
        val f = CompletableFuture<Boolean>()
        intentResult = f
        startIntentSenderForResult(sender, 42, null, 0, 0, 0)
        return f
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42) intentResult?.complete(resultCode == RESULT_OK)
    }

    companion object {
        @Volatile var current: MainActivity? = null
    }
}
