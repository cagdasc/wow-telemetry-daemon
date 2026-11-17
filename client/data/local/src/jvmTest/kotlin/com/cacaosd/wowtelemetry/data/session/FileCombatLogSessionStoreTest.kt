package com.cacaosd.wowtelemetry.data.session

import com.cacaosd.wowtelemetry.domain.session.CombatLogSessionException
import com.cacaosd.wowtelemetry.domain.session.FrameKind
import com.cacaosd.wowtelemetry.domain.session.RawFrame
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FileCombatLogSessionStoreTest {
    private val directory = Files.createTempDirectory("sessions").toFile()

    private fun store() = FileCombatLogSessionStore(sessionDirectory = { directory })

    private fun frame(index: Int, kind: FrameKind = FrameKind.BINARY) =
        RawFrame(kind, byteArrayOf(index.toByte(), 7, 9))

    @Test
    fun roundTripsFramesAndSupportsSkipAndLimit() = runBlocking {
        val store = store()
        (0 until 5).forEach { store.append(frame(it, if (it == 3) FrameKind.TEXT else FrameKind.BINARY)) }
        val path = store.recording.value.session!!.path

        val all = store.read(path).toList()
        assertEquals(5, all.size)
        assertEquals(FrameKind.TEXT, all[3].kind)
        assertContentEquals(byteArrayOf(4, 7, 9), all[4].payload)

        val window = store.read(path, skipFrames = 1, maxFrames = 2).toList()
        assertContentEquals(byteArrayOf(1, 7, 9), window[0].payload)
        assertEquals(2, window.size)
        assertEquals(5L, store.open(path).frameCount)
    }

    @Test
    fun ignoresTruncatedTail() = runBlocking {
        val store = store()
        store.append(frame(1))
        store.append(frame(2))
        store.finishRecording()
        val file = directory.listFiles()!!.single()
        file.writeBytes(file.readBytes().dropLast(2).toByteArray())

        assertEquals(1, store.read(file.path).toList().size)
        assertEquals(1L, store.open(file.path).frameCount)
        assertNull(store.recording.value.session)
    }

    @Test
    fun rejectsForeignFiles() {
        val file = File(directory, "other.wowlog").apply { writeText("not a session") }
        assertFailsWith<CombatLogSessionException> { store().open(file.path) }
    }
}
