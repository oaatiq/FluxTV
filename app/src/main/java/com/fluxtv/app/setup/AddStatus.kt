package com.fluxtv.app.setup

/**
 * How adding a playlist went, shown in Settings and, for a link sent from a phone, on the phone too,
 * in the same words.
 */
sealed interface AddStatus {
    /** The TV is downloading the playlist to see that it works. */
    data object Checking : AddStatus

    /**
     * Saved. [inUse]: it was the first playlist, so TV shows it now; any other waits for the
     * administrator to choose "Use this playlist".
     */
    data class Added(val playlistId: String, val name: String, val count: Int, val inUse: Boolean) : AddStatus

    /** The TV has this link saved already, as [name]. */
    data class AlreadySaved(val playlistId: String, val name: String) : AddStatus

    /** Not saved, and nothing on the TV changed. */
    data class Failed(val message: String) : AddStatus
}
