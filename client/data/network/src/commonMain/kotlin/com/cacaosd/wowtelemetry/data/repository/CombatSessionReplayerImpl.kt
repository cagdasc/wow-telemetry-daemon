package com.cacaosd.wowtelemetry.data.repository

import com.cacaosd.wowtelemetry.data.mapper.CombatStreamMessageDecoder
import com.cacaosd.wowtelemetry.data.mapper.toStreamMessage
import com.cacaosd.wowtelemetry.data.model.CombatEventDecodeException
import com.cacaosd.wowtelemetry.data.proto.Envelope
import com.cacaosd.wowtelemetry.domain.CombatStreamMessage
import com.cacaosd.wowtelemetry.domain.session.CombatLogSessionStore
import com.cacaosd.wowtelemetry.domain.session.CombatSessionReplayer
import com.cacaosd.wowtelemetry.domain.session.FrameKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import okio.IOException

class CombatSessionReplayerImpl(
    private val store: CombatLogSessionStore,
    private val decoder: CombatStreamMessageDecoder,
    private val logError: (String) -> Unit = ::println,
) : CombatSessionReplayer {
    override fun replay(path: String, skipFrames: Long, maxFrames: Long): Flow<CombatStreamMessage> =
        store.read(path, skipFrames, maxFrames).mapNotNull { frame ->
            try {
                when (frame.kind) {
                    FrameKind.BINARY -> Envelope.ADAPTER.decode(frame.payload).toStreamMessage()
                    FrameKind.TEXT -> decoder.decode(frame.payload.decodeToString())
                }
            } catch (error: IOException) {
                logError("Skipped invalid stored frame: ${error.message}")
                null
            } catch (error: CombatEventDecodeException) {
                logError("Skipped invalid stored frame: ${error.message}")
                null
            } catch (error: IllegalArgumentException) {
                logError("Skipped invalid stored frame: ${error.message}")
                null
            }
        }
}
