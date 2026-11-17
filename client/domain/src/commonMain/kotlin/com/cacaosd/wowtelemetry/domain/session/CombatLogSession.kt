package com.cacaosd.wowtelemetry.domain.session

import com.cacaosd.wowtelemetry.domain.CombatStreamMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** How the client treats frames arriving from the daemon. */
enum class IngestMode {
    /** Decode and process frames immediately. */
    LIVE,

    /** Spill frames to disk undecoded; process them when the user asks. */
    COLLECT,
}

enum class FrameKind { BINARY, TEXT }

/** One WebSocket frame exactly as received from the daemon. */
class RawFrame(val kind: FrameKind, val payload: ByteArray)

data class CombatLogSession(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val frameCount: Long? = null,
)

data class RecordingState(
    val session: CombatLogSession? = null,
    val frameCount: Long = 0,
)

class CombatLogSessionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Append-only on-disk store of raw daemon frames. Methods perform blocking I/O. */
interface CombatLogSessionStore {
    val recording: StateFlow<RecordingState>

    /** Appends to the active recording, creating a new session file if none is open. */
    fun append(frame: RawFrame)

    /** Closes the active recording. The next [append] starts a new file. */
    fun finishRecording()

    fun list(): List<CombatLogSession>

    /** Validates the file header and counts its frames. */
    fun open(path: String): CombatLogSession

    /** Streams up to [maxFrames] frames after the first [skipFrames]. */
    fun read(path: String, skipFrames: Long = 0, maxFrames: Long = Long.MAX_VALUE): Flow<RawFrame>
}

/** Decodes stored frames into the same messages the live stream produces. */
interface CombatSessionReplayer {
    fun replay(path: String, skipFrames: Long = 0, maxFrames: Long = Long.MAX_VALUE): Flow<CombatStreamMessage>
}
