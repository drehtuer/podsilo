// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val cover = EpisodeArtwork(byteArrayOf(1, 2, 3, 4, 5), "image/jpeg", EpisodeArtwork.Source.PODCAST)

    private fun File.embeddedArtwork() = AudioFileIO.read(this).tag?.firstArtwork

    @Test
    fun `artwork is embedded when the file has none`() {
        // The feature: a file with no cover gets the episode's, or failing that the podcast's.
        val file = copyOfSilenceFixture()
        assertNull("fixture should start with no artwork", file.embeddedArtwork())

        writer.writeTags(file, tagData().copy(artwork = cover))

        assertArrayEquals(cover.bytes, file.embeddedArtwork()?.binaryData)
    }

    @Test
    fun `artwork the publisher already embedded is never replaced`() {
        // The explicit boundary of the request: fill a gap, do not normalise every file. A
        // publisher who shipped per-episode art meant it.
        val file = copyOfSilenceFixture()
        val original = EpisodeArtwork(byteArrayOf(9, 9, 9), "image/png", EpisodeArtwork.Source.EPISODE)
        writer.writeTags(file, tagData().copy(artwork = original))

        writer.writeTags(file, tagData().copy(artwork = cover))

        assertArrayEquals("the original cover was overwritten", original.bytes, file.embeddedArtwork()?.binaryData)
    }

    @Test
    fun `no artwork supplied leaves the file without any`() {
        val file = copyOfSilenceFixture()

        writer.writeTags(file, tagData().copy(artwork = null))

        assertNull(file.embeddedArtwork())
    }

    @Test
    fun `the text fields are still written alongside artwork`() {
        val file = copyOfSilenceFixture()

        val outcome = writer.writeTags(file, tagData().copy(artwork = cover))

        assertTrue(outcome is TagWriteOutcome.Success || outcome is TagWriteOutcome.PartialSuccess)
        assertEquals("Warum Hamburg immer regnet", AudioFileIO.read(file).tag?.getFirst(FieldKey.TITLE))
    }

    private fun tagData() =
        AudioTagData(
            title = "Warum Hamburg immer regnet",
            artist = "Der Podcast",
            album = "Der Podcast",
        )

    @Test
    fun `keeping the file's own artwork is a clean Success, not a reported skip`() {
        // The distinction the flag has to keep: "this file already had a cover" is the intended
        // outcome, not a limitation. Only a container that *cannot* hold artwork is worth flagging.
        val file = copyOfSilenceFixture()
        writer.writeTags(file, tagData().copy(artwork = cover))

        val outcome = writer.writeTags(file, tagData().copy(artwork = cover))

        assertEquals(TagWriteOutcome.Success, outcome)
    }

    @Test
    fun `embedding into a file with no artwork is a clean Success`() {
        val outcome = writer.writeTags(copyOfSilenceFixture(), tagData().copy(artwork = cover))

        assertEquals(TagWriteOutcome.Success, outcome)
    }

    @Test
    fun `supplying no artwork at all is a clean Success, not a skip`() {
        // No cover was asked for, so nothing was skipped — the distinction the flag has to keep.
        val outcome = writer.writeTags(copyOfSilenceFixture(), tagData().copy(artwork = null))

        assertEquals(TagWriteOutcome.Success, outcome)
    }
}
