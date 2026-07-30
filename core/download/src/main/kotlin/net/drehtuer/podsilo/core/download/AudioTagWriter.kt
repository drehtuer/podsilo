// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File

/**
 * The tag values CLAUDE.md section 6 specifies: title (cleaned episode title), artist/album (podcast
 * title), [year] and [trackNumber] derived from the episode's date, a fixed [genre], and the episode
 * description in [comment].
 */
data class AudioTagData(
    val title: String,
    val artist: String,
    val album: String,
    val year: String? = null,
    val genre: String = "Podcast",
    val trackNumber: String? = null,
    val comment: String? = null,
)

sealed interface TagWriteOutcome {
    data object Success : TagWriteOutcome

    /** One or more fields weren't representable in this file's tag format -- still a usable delivery. */
    data class PartialSuccess(
        val skippedFields: List<FieldKey>,
    ) : TagWriteOutcome

    /** The file couldn't be read as a supported audio container, or the tag write itself failed. */
    data class Failure(
        val reason: String,
    ) : TagWriteOutcome
}

/**
 * Rewrites audio tags on [file] in place, best-effort -- CLAUDE.md section 6: a tag-write failure
 * must never lose a successful download. Uses jaudiotagger (the Android-compatible Adonai/Kaned1as
 * fork, not the stale upstream artifact -- see `docs/decisions/0006`).
 *
 * Needs a real [File]: tagging libraries can't write through a SAF `OutputStream`, which is why the
 * download pipeline tags in the app cache before copying into the user's SAF folder
 * (`docs/architecture.md` section 11).
 */
class AudioTagWriter {
    fun writeTags(
        file: File,
        data: AudioTagData,
    ): TagWriteOutcome {
        val audioFile =
            try {
                AudioFileIO.read(file)
            } catch (
                @Suppress("TooGenericExceptionCaught") unreadable: Exception,
            ) {
                val reason = unreadable.message ?: "file is not a supported audio container"
                return TagWriteOutcome.Failure(reason)
            }

        val skipped = writeFields(audioFile.tagOrCreateAndSetDefault, data)

        return try {
            audioFile.commit()
            if (skipped.isEmpty()) TagWriteOutcome.Success else TagWriteOutcome.PartialSuccess(skipped)
        } catch (
            @Suppress("TooGenericExceptionCaught") uncommittable: Exception,
        ) {
            TagWriteOutcome.Failure(uncommittable.message ?: "failed to write tags to file")
        }
    }
}

/** Returns the [FieldKey]s that this file's tag format wouldn't accept, best-effort per field. */
private fun writeFields(
    tag: Tag,
    data: AudioTagData,
): List<FieldKey> =
    buildList {
        if (!tag.trySetField(FieldKey.TITLE, data.title)) add(FieldKey.TITLE)
        if (!tag.trySetField(FieldKey.ARTIST, data.artist)) add(FieldKey.ARTIST)
        if (!tag.trySetField(FieldKey.ALBUM, data.album)) add(FieldKey.ALBUM)
        if (!tag.trySetField(FieldKey.GENRE, data.genre)) add(FieldKey.GENRE)
        data.year?.let { if (!tag.trySetField(FieldKey.YEAR, it)) add(FieldKey.YEAR) }
        data.trackNumber?.let { if (!tag.trySetField(FieldKey.TRACK, it)) add(FieldKey.TRACK) }
        data.comment?.let { if (!tag.trySetField(FieldKey.COMMENT, it)) add(FieldKey.COMMENT) }
    }

private fun Tag.trySetField(
    key: FieldKey,
    value: String,
): Boolean =
    try {
        setField(key, value)
        true
    } catch (
        @Suppress("TooGenericExceptionCaught", "SwallowedException") unsupportedField: Exception,
    ) {
        // Best-effort per field, by design (CLAUDE.md section 6): some tag formats don't support
        // every field. The caller surfaces the skipped keys via PartialSuccess rather than losing
        // the whole write over one field this container can't hold.
        false
    }
