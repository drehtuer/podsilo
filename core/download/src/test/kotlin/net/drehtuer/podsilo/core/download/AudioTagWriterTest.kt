// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AudioTagWriterTest {
    private val writer = AudioTagWriter()

    private fun copyOfSilenceFixture(): File {
        val bytes =
            javaClass.classLoader
                ?.getResourceAsStream("audio/silence.mp3")
                ?.use { it.readBytes() }
        requireNotNull(bytes) { "missing fixture: audio/silence.mp3" }

        val tempFile = Files.createTempFile("podsilo-tag-test", ".mp3").toFile()
        tempFile.deleteOnExit()
        tempFile.writeBytes(bytes)
        return tempFile
    }

    @Test
    fun `writes all specified fields and reports Success`() {
        val file = copyOfSilenceFixture()
        val data =
            AudioTagData(
                title = "Warum Hamburg immer regnet",
                artist = "Der Podcast",
                album = "Der Podcast",
                year = "2026",
                trackNumber = "20260714",
                comment = "Eine Folge über das Wetter.",
            )

        val outcome = writer.writeTags(file, data)

        assertEquals(TagWriteOutcome.Success, outcome)

        val readBack = AudioFileIO.read(file).tag
        assertEquals("Warum Hamburg immer regnet", readBack.getFirst(FieldKey.TITLE))
        assertEquals("Der Podcast", readBack.getFirst(FieldKey.ARTIST))
        assertEquals("Der Podcast", readBack.getFirst(FieldKey.ALBUM))
        assertEquals("2026", readBack.getFirst(FieldKey.YEAR))
        assertEquals("Podcast", readBack.getFirst(FieldKey.GENRE))
        assertEquals("20260714", readBack.getFirst(FieldKey.TRACK))
        assertEquals("Eine Folge über das Wetter.", readBack.getFirst(FieldKey.COMMENT))
    }

    @Test
    fun `genre defaults to Podcast when not overridden`() {
        val file = copyOfSilenceFixture()
        val data = AudioTagData(title = "Title", artist = "Artist", album = "Album")

        writer.writeTags(file, data)

        assertEquals("Podcast", AudioFileIO.read(file).tag.getFirst(FieldKey.GENRE))
    }

    @Test
    fun `optional fields left null are simply not written, not blanked`() {
        val file = copyOfSilenceFixture()
        val data = AudioTagData(title = "Title", artist = "Artist", album = "Album")

        writer.writeTags(file, data)

        val tag = AudioFileIO.read(file).tag
        assertTrue(tag.getFirst(FieldKey.YEAR).isNullOrEmpty())
        assertTrue(tag.getFirst(FieldKey.TRACK).isNullOrEmpty())
        assertTrue(tag.getFirst(FieldKey.COMMENT).isNullOrEmpty())
    }

    @Test
    fun `an unreadable file yields Failure, not an exception`() {
        val notAudio = Files.createTempFile("podsilo-tag-test", ".mp3").toFile()
        notAudio.deleteOnExit()
        notAudio.writeBytes("this is not an mp3 file".toByteArray())

        val outcome = writer.writeTags(notAudio, AudioTagData(title = "T", artist = "A", album = "B"))

        assertTrue(outcome is TagWriteOutcome.Failure)
    }

    @Test
    fun `a nonexistent file yields Failure, not an exception`() {
        val missing = File("/nonexistent/path/does-not-exist.mp3")

        val outcome = writer.writeTags(missing, AudioTagData(title = "T", artist = "A", album = "B"))

        assertTrue(outcome is TagWriteOutcome.Failure)
    }
}
