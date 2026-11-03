package io.github.storskegg.graphite.usb

import io.github.storskegg.graphite.Controller
import io.github.storskegg.graphite.link.Link
import io.github.storskegg.graphite.log
import java.io.IOException

/**
 * Keeps the cable link attached: the target (USB host) puts the other phone
 * into accessory mode and opens it; the source opens the accessory. When the
 * link drops (cable pulled, USB role changed) it attaches again as soon as
 * the phones see each other, and [Link] resumes where it stopped.
 */
class Connection(private val ctl: Controller) {
    val isHost = ctl.isTarget
    val link = Link(isHost = isHost, localRole = ctl.role.role!!.wire, log = ::log)
    @Volatile private var closed = false
    @Volatile var guidance: String = ""
        private set
    private var keeper: Thread? = null
    private var lastSwitch = 0L

    init {
        link.onState = { describe() }
    }

    fun start() {
        if (keeper != null) return
        keeper = Thread({
            while (!closed) {
                if (link.state == Link.State.DOWN) {
                    try {
                        tryAttach()
                    } catch (e: Exception) {
                        log("attach: $e")
                        guidance = "Could not open the USB link: ${e.message}. Unplug and plug the cable back in."
                    }
                }
                describe()
                Thread.sleep(500)
            }
        }, "usb-keeper").apply { isDaemon = true; start() }
    }

    fun close(why: String) {
        closed = true
        link.close(why)
    }

    private fun tryAttach() {
        val um = ctl.usbManager
        if (isHost) {
            val dev = um.deviceList.values.firstOrNull() ?: run {
                guidance = when (Usb.role(ctl.app)) {
                    UsbRole.DEVICE -> SWAP_ON_TARGET
                    else -> "Connect the two phones with a USB-C cable (a USB 3 cable is 3-4 times faster)."
                }
                return
            }
            if (!Usb.requestPermission(ctl.app, device = dev)) {
                guidance = "graphite needs permission to use the other phone over USB: tap Allow."
                return
            }
            if (Usb.isAccessoryMode(dev)) {
                guidance = "Opening the link…"
                val (pipe, speed) = Usb.openHostPipe(um, dev)
                ctl.linkSpeed = speed
                log("host pipe open on ${Usb.describe(dev)}: $speed")
                link.attach(pipe)
            } else if (System.currentTimeMillis() - lastSwitch > 8000) {
                lastSwitch = System.currentTimeMillis()
                val v = Usb.switchToAccessory(um, dev)
                log("switched ${Usb.describe(dev)} to accessory mode (AOA $v)")
                guidance = "Starting graphite on the old phone. If it asks, allow graphite to open for this USB accessory."
            }
        } else {
            val role = Usb.role(ctl.app)
            if (role == UsbRole.HOST) { guidance = SWAP_ON_SOURCE; return }
            val acc = um.accessoryList?.firstOrNull() ?: run {
                guidance = if (role == UsbRole.DEVICE) "Waiting for the new phone to start the link. Open graphite on the new phone."
                    else "Connect the two phones with a USB-C cable (a USB 3 cable is 3-4 times faster)."
                return
            }
            if (!Usb.requestPermission(ctl.app, accessory = acc)) {
                guidance = "graphite needs permission to use the USB accessory: tap OK."
                return
            }
            guidance = "Opening the link…"
            link.attach(Usb.openAccessoryPipe(um, acc))
            log("accessory pipe open: $acc")
        }
    }

    private fun describe() {
        val role = runCatching { Usb.role(ctl.app) }.getOrDefault(UsbRole.NONE)
        val roleText = when (role) {
            UsbRole.HOST -> "this phone controls USB (host)"
            UsbRole.ACCESSORY -> "the other phone controls USB; this one is its accessory"
            UsbRole.DEVICE -> "the other phone controls USB"
            UsbRole.NONE -> "no USB data connection"
        }
        val state = when (link.state) {
            Link.State.UP -> "link up"
            Link.State.HANDSHAKING -> "starting the link"
            Link.State.DOWN -> "link down"
            Link.State.CLOSED -> "link closed"
        }
        val speed = ctl.linkSpeed?.let { ", $it" } ?: ""
        ctl.updateStatus("USB: $roleText; $state$speed")
    }

    /** Waits for the link, showing what the user has to do meanwhile. */
    fun awaitUp(show: (String) -> Unit) {
        start()
        var last = ""
        while (!link.awaitUp(500)) {
            if (link.state == Link.State.CLOSED) throw IOException("link closed")
            if (guidance != last) { last = guidance; show(guidance) }
        }
    }

    companion object {
        const val SWAP_ON_TARGET =
            "The old phone is controlling USB, but the new phone has to. On either phone, open the USB " +
                "notification (\"Charging connected device via USB\" or \"USB\") and under \"USB controlled by\" " +
                "choose so that this new phone controls it. graphite waits until the roles really change " +
                "(1-3 s); the label in Settings can name the wrong phone, so trust this screen."
        const val SWAP_ON_SOURCE =
            "This old phone is controlling USB, but the new phone has to. On either phone, open the USB " +
                "notification (\"Charging connected device via USB\" or \"USB\") and under \"USB controlled by\" " +
                "choose so that the new phone controls it. graphite waits until the roles really change " +
                "(1-3 s); the label in Settings can name the wrong phone, so trust this screen. " +
                "Only the data role moves: the phone that supplies power keeps doing so."
    }
}
