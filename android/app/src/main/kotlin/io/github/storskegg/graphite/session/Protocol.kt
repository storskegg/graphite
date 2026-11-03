package io.github.storskegg.graphite.session

import io.github.storskegg.graphite.util.BinReader
import io.github.storskegg.graphite.util.BinWriter

/** Message types inside the encrypted channel. */
object Msg {
    /** The user confirmed the six digits on this phone. */
    const val CONFIRMED = 0x01
    /** What this phone is and can do (the capability inventory). */
    const val CAPS = 0x02
    /** Target: the user accepted this source. */
    const val ACCEPTED = 0x03
    /** Target: send a test payload to measure the link (and, in the tracer, a test file). */
    const val SPEED_TEST = 0x04

    /** Source: what is in scope and how much, before hashing. */
    const val INVENTORY = 0x20
    /** Target: the user's options (metadata choice, Android/media). */
    const val OPTIONS = 0x21
    /** Either: progress to show on the other phone too. */
    const val PROGRESS = 0x22
    /** Source: the signed manifest. */
    const val MANIFEST = 0x23
    /** Target: the signed plan. */
    const val PLAN = 0x24
    /** Source: the plan verified (or not, with a reason). */
    const val PLAN_ANSWER = 0x25
    /** Target: the user started the copy. */
    const val START = 0x26

    /** Target: send manifest entry `index`. */
    const val REQUEST = 0x30
    /** Source: a file's bytes follow (index, size). */
    const val FILE_BEGIN = 0x31
    /** Source: index + bytes. */
    const val FILE_DATA = 0x32
    /** Source: index + status ([FileEnd]). */
    const val FILE_END = 0x33
    /** Target: no more requests. */
    const val COPY_DONE = 0x34
    /** Target: the signed receipt. */
    const val RECEIPT = 0x35
    /** Source: the receipt verified (or not). */
    const val RECEIPT_ANSWER = 0x36

    /** Either: a stop with a reason the other phone shows. */
    const val ERROR = 0x40
    /** The last message of the session, sent as the stream's last chunk. */
    const val BYE = 0x7f
}

object FileEnd {
    const val OK = 0
    /** The file's bytes no longer match the manifest. */
    const val CHANGED = 1
    /** The file could not be read on the source. */
    const val UNREADABLE = 2
}

data class Caps(
    val role: Int,
    val sdk: Int,
    val manufacturer: String,
    val model: String,
    val release: String,
    val appVersion: String,
    val roleAttested: Boolean,
    val batteryPercent: Int,
    val charging: Boolean,
    /** Target: bytes it can allocate for new files; source: 0. */
    val allocatableBytes: Long,
    val usb: String,
    val allFilesAccess: Boolean,
) {
    fun encode() = BinWriter().u8(role).u32(sdk).str(manufacturer).str(model).str(release).str(appVersion)
        .bool(roleAttested).u8(batteryPercent).bool(charging).i64(allocatableBytes).str(usb).bool(allFilesAccess).finish()

    val name get() = "$manufacturer $model (Android $release)"

    companion object {
        fun decode(b: ByteArray) = BinReader(b).run {
            Caps(u8(), u32().toInt(), str(), str(), str(), str(), bool(), u8(), bool(), i64(), str(), bool())
        }
    }
}
