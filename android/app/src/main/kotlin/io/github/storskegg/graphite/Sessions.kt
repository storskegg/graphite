package io.github.storskegg.graphite

/** The flow thread's loop: run sessions one after another until one says to stop. */
object Sessions {
    /** Runs [session] again for as long as it returns true (finished, ready for the next one). */
    fun loop(session: () -> Boolean) {
        while (session()) Unit
    }
}
