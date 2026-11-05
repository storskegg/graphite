package io.github.storskegg.graphite.ui

/**
 * What the app shows, as data. The flows build screens; [MainActivity]
 * draws whichever is current. A screen with buttons is a question: the flow
 * blocks until one is tapped and gets back the button and the ticked choices.
 */
data class Screen(
    val title: String,
    val body: String = "",
    /** The six digits, drawn large. */
    val code: String? = null,
    val progress: Progress? = null,
    /** The transfer's treemap, drawn above the progress. */
    val tiles: io.github.storskegg.graphite.media.Tiles? = null,
    val choices: List<Choice> = emptyList(),
    val buttons: List<String> = emptyList(),
    /** Ask for the user's authentication as soon as this screen shows. */
    val authReason: String? = null,
)

data class Progress(val done: Long, val total: Long, val text: String)

data class Choice(
    val id: String,
    val label: String,
    val detail: String = "",
    val checked: Boolean = false,
    val enabled: Boolean = true,
    /** Choices sharing a group are radio buttons; null makes a checkbox. */
    val group: String? = null,
)

data class Answer(val button: String, val checked: Set<String>)

/** The lines above every screen. */
data class Status(
    val role: String = "",
    val usb: String = "",
    val auth: String = "",
)
