package com.cacaosd.wowtelemetry.data.session

import com.cacaosd.wowtelemetry.domain.session.CombatLogSession
import com.cacaosd.wowtelemetry.domain.session.CombatLogSessionException
import com.cacaosd.wowtelemetry.domain.session.CombatLogSessionStore
import com.cacaosd.wowtelemetry.domain.session.FrameKind
import com.cacaosd.wowtelemetry.domain.session.RawFrame
import com.cacaosd.wowtelemetry.domain.session.RecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * File layout: magic `WOWL`, one version byte, then records of
 * `[kind: u8][length: u32 big-endian][payload]`. Payloads are the daemon frames as received.
 * A truncated final record (crash, power loss) is ignored on read.
 */
class FileCombatLogSessionStore(
    private val sessionDirectory: () -> File,
    private val clock: () -> Long = System::currentTimeMillis,
) : CombatLogSessionStore {
    private val lock = Any()
    private var output: DataOutputStream? = null
    private var activeFile: File? = null
    private var activeFrames = 0L
    private var lastFlushAt = 0L

    private val mutableRecording = MutableStateFlow(RecordingState())
    override val recording: StateFlow<RecordingState> = mutableRecording.asStateFlow()

    override fun append(frame: RawFrame) {
        if (frame.payload.size > MAX_FRAME_BYTES) {
            throw CombatLogSessionException("Frame of ${frame.payload.size} bytes exceeds the $MAX_FRAME_BYTES byte limit.")
        }
        synchronized(lock) {
            val stream = output ?: startRecording()
            stream.writeByte(if (frame.kind == FrameKind.BINARY) KIND_BINARY else KIND_TEXT)
            stream.writeInt(frame.payload.size)
            stream.write(frame.payload)
            activeFrames++
            val now = clock()
            if (now - lastFlushAt >= FLUSH_INTERVAL_MILLIS) {
                stream.flush()
                lastFlushAt = now
            }
            mutableRecording.value = RecordingState(describe(activeFile!!, activeFrames), activeFrames)
        }
    }

    override fun finishRecording() {
        synchronized(lock) {
            output?.close()
            output = null
            activeFile = null
            activeFrames = 0
            mutableRecording.value = RecordingState()
        }
    }

    override fun list(): List<CombatLogSession> =
        sessionDirectory().listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            .orEmpty()
            .map { describe(it, frameCount = null) }
            .sortedByDescending { it.modifiedAtMillis }

    override fun open(path: String): CombatLogSession {
        val file = File(path)
        var count = 0L
        scan(file, skipFrames = 0, maxFrames = Long.MAX_VALUE, readPayload = false) { _, _ -> count++ }
        return describe(file, count)
    }

    override fun read(path: String, skipFrames: Long, maxFrames: Long): Flow<RawFrame> = flow {
        scan(File(path), skipFrames, maxFrames, readPayload = true) { kind, payload ->
            emit(RawFrame(kind, payload!!))
        }
    }.flowOn(Dispatchers.IO)

    private fun startRecording(): DataOutputStream {
        val directory = sessionDirectory()
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw CombatLogSessionException("Cannot create session directory ${directory.absolutePath}.")
        }
        val stamp = LocalDateTime.now().format(NAME_FORMAT)
        var file = File(directory, "session-$stamp$EXTENSION")
        var suffix = 1
        while (file.exists()) file = File(directory, "session-$stamp-${suffix++}$EXTENSION")
        val stream = DataOutputStream(BufferedOutputStream(FileOutputStream(file), BUFFER_BYTES))
        stream.write(MAGIC)
        stream.writeByte(VERSION)
        stream.flush()
        output = stream
        activeFile = file
        activeFrames = 0
        lastFlushAt = clock()
        return stream
    }

    private fun describe(file: File, frameCount: Long?) =
        CombatLogSession(file.absolutePath, file.name, file.length(), file.lastModified(), frameCount)

    /** Visits complete records, ignoring a truncated tail. Flushes first if [file] is being recorded. */
    private inline fun scan(
        file: File,
        skipFrames: Long,
        maxFrames: Long,
        readPayload: Boolean,
        onRecord: (FrameKind, ByteArray?) -> Unit,
    ) {
        synchronized(lock) { if (file == activeFile) output?.flush() }
        DataInputStream(BufferedInputStream(openChecked(file))).use { input ->
            var skipped = 0L
            var visited = 0L
            while (visited < maxFrames) {
                val kindByte = input.read()
                if (kindByte < 0) break
                val length = try {
                    input.readInt()
                } catch (_: EOFException) {
                    break
                }
                if (length !in 0..MAX_FRAME_BYTES) {
                    throw CombatLogSessionException("Corrupt frame length $length in ${file.name}.")
                }
                val kind = frameKindOf(kindByte, file)
                if (skipped < skipFrames || !readPayload) {
                    if (!input.skipFully(length)) break
                    if (skipped < skipFrames) {
                        skipped++
                        continue
                    }
                    visited++
                    onRecord(kind, null)
                    continue
                }
                val payload = ByteArray(length)
                try {
                    input.readFully(payload)
                } catch (_: EOFException) {
                    break
                }
                visited++
                onRecord(kind, payload)
            }
        }
    }

    private fun openChecked(file: File): InputStream {
        if (!file.isFile) throw CombatLogSessionException("Session file not found: ${file.absolutePath}")
        val stream = file.inputStream()
        try {
            val header = ByteArray(MAGIC.size + 1)
            try {
                DataInputStream(stream).readFully(header)
            } catch (_: EOFException) {
                throw CombatLogSessionException("${file.name} is not a WoW telemetry session file.")
            }
            if (!header.copyOf(MAGIC.size).contentEquals(MAGIC)) {
                throw CombatLogSessionException("${file.name} is not a WoW telemetry session file.")
            }
            if (header.last().toInt() != VERSION) {
                throw CombatLogSessionException("${file.name} uses unsupported session version ${header.last()}.")
            }
            return stream
        } catch (error: IOException) {
            stream.close()
            throw CombatLogSessionException("Cannot read ${file.name}: ${error.message}", error)
        } catch (error: CombatLogSessionException) {
            stream.close()
            throw error
        }
    }

    private fun frameKindOf(kind: Int, file: File) = when (kind) {
        KIND_BINARY -> FrameKind.BINARY
        KIND_TEXT -> FrameKind.TEXT
        else -> throw CombatLogSessionException("Corrupt frame kind $kind in ${file.name}.")
    }

    private fun DataInputStream.skipFully(count: Int): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            if (skipped <= 0) {
                if (read() < 0) return false
                remaining--
            } else {
                remaining -= skipped
            }
        }
        return true
    }

    companion object {
        const val EXTENSION = ".wowlog"
        private const val VERSION = 1
        private const val KIND_BINARY = 0
        private const val KIND_TEXT = 1
        private const val MAX_FRAME_BYTES = 64 * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024
        private const val FLUSH_INTERVAL_MILLIS = 1_000L
        private val MAGIC = byteArrayOf('W'.code.toByte(), 'O'.code.toByte(), 'W'.code.toByte(), 'L'.code.toByte())
        private val NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
