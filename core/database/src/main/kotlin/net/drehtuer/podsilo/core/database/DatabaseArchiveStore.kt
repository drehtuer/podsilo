// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.drehtuer.podsilo.core.model.port.ArchiveContents
import net.drehtuer.podsilo.core.model.port.ArchiveFailure
import net.drehtuer.podsilo.core.model.port.ArchiveOutcome
import net.drehtuer.podsilo.core.model.port.DatabaseArchive
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Bumped only if the *layout of the zip* changes — not when the database schema does. */
private const val ARCHIVE_FORMAT_VERSION = 1

private const val MANIFEST_ENTRY = "podsilo-backup.properties"
private const val DATABASE_ENTRY = "podsilo.db"

private const val KEY_FORMAT = "archiveFormat"
private const val KEY_SCHEMA = "schemaVersion"
private const val KEY_EXPORTED_AT = "exportedAt"

// Row counts, written at export and checked at import. See [verifyAgainstManifest].
private const val KEY_FEEDS = "feeds"
private const val KEY_EPISODES = "episodes"
private const val KEY_LEDGER_ROWS = "ledgerRows"

/** `SQLite format 3\u0000` — the first bytes of every SQLite file. */
private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

/**
 * Insert batch size for a restore. Room binds one statement per row anyway; the chunking is about
 * not holding a second full copy of a large episode table in one list of arguments.
 */
private const val RESTORE_BATCH = 500

/**
 * The [DatabaseArchive] adapter: a zip holding the SQLite file itself plus a small manifest.
 *
 * **Why the raw database file rather than a JSON dump.** The user asked for "just dump the database
 * as zip", and taking that literally buys schema evolution for free: an archive written by an older
 * build opens through Room's own migrations ([PODSILO_MIGRATIONS]), so old backups keep working
 * without a second, hand-maintained serialisation format that would have to be migrated in parallel
 * with the real one. CLAUDE.md §3 — use the thing that already exists.
 *
 * **Why the restore copies rows instead of swapping the file.** Replacing `podsilo.db` underneath a
 * live Room instance means closing and rebuilding the singleton, and every `Flow` the UI is
 * collecting is attached to that instance. Reading the archive with a *second* Room instance and
 * copying its rows into the live one keeps a single database object for the app's lifetime, runs
 * inside one transaction (so a corrupt archive rolls back to exactly what was there before), and
 * lets Room's invalidation tracker do what it always does — the screens update on their own, with
 * no restart.
 */
class DatabaseArchiveStore(
    private val context: Context,
    private val database: PodsiloDatabase,
    // Defaulted, matching RetrofitNextcloudLoginFlowClient: injectable for tests, no DI qualifier
    // for one call site (CLAUDE.md §8 asks for injectable dispatchers, not a dispatcher module).
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DatabaseArchive {
    override suspend fun exportTo(destinationUri: String): ArchiveOutcome =
        withContext(ioDispatcher) {
            try {
                val contents = currentContents()
                checkpointWal()
                writeArchive(Uri.parse(destinationUri), contents)
                ArchiveOutcome.Exported(contents)
            } catch (e: IOException) {
                ArchiveOutcome.Failed(ArchiveFailure.WRITE_FAILED, e.message)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // SAF throws SecurityException for a revoked grant and IllegalArgumentException for
                // a malformed URI, neither of which is an IOException. A backup that cannot be
                // written must report that, not crash the settings screen.
                ArchiveOutcome.Failed(ArchiveFailure.WRITE_FAILED, e.message)
            }
        }

    override suspend fun importFrom(sourceUri: String): ArchiveOutcome =
        withContext(ioDispatcher) {
            val staged = File.createTempFile("podsilo-restore", ".db", context.cacheDir)
            try {
                when (val extracted = extract(Uri.parse(sourceUri), staged)) {
                    is Extracted.Failure -> ArchiveOutcome.Failed(extracted.reason, extracted.detail)
                    is Extracted.Manifest -> restore(staged, extracted)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                ArchiveOutcome.Failed(ArchiveFailure.UNREADABLE, e.message)
            } finally {
                // Room leaves -wal/-shm beside the staged file; all three are ours to remove.
                staged.parentFile?.listFiles { f -> f.name.startsWith(staged.name) }?.forEach { it.delete() }
            }
        }

    private suspend fun currentContents(): ArchiveContents =
        ArchiveContents(
            feeds = database.feedDao().count(),
            episodes = database.episodeDao().count(),
            ledgerRows = database.episodeLedgerDao().count(),
        )

    /**
     * Folds the write-ahead log back into the main file before it is copied.
     *
     * Room runs in WAL mode, so recent writes — very plausibly the ledger row for the download that
     * just finished — live in `podsilo.db-wal` and not in `podsilo.db`. Zipping the main file alone
     * without this would silently produce a backup that is missing exactly the newest state, which
     * is the state most worth having.
     */
    private fun checkpointWal() {
        database.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { it.moveToFirst() }
    }

    private fun writeArchive(
        destination: Uri,
        contents: ArchiveContents,
    ) {
        val dbFile = context.getDatabasePath(PodsiloDatabase.DATABASE_NAME)
        val output =
            context.contentResolver.openOutputStream(destination)
                ?: throw IOException("the picked document could not be opened for writing")

        output.buffered().use { raw ->
            ZipOutputStream(raw).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifestBytes(contents))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(DATABASE_ENTRY))
                dbFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun manifestBytes(contents: ArchiveContents): ByteArray {
        val properties =
            Properties().apply {
                setProperty(KEY_FORMAT, ARCHIVE_FORMAT_VERSION.toString())
                setProperty(KEY_SCHEMA, currentSchemaVersion().toString())
                setProperty(KEY_EXPORTED_AT, System.currentTimeMillis().toString())
                setProperty(KEY_FEEDS, contents.feeds.toString())
                setProperty(KEY_EPISODES, contents.episodes.toString())
                setProperty(KEY_LEDGER_ROWS, contents.ledgerRows.toString())
            }
        return java.io
            .ByteArrayOutputStream()
            .also { properties.store(it, "Podsilo database backup") }
            .toByteArray()
    }

    private fun currentSchemaVersion(): Int = database.openHelper.readableDatabase.version

    /**
     * Pulls the two entries we know by name out of the zip.
     *
     * Matching entry names **exactly** rather than writing out whatever the archive contains is also
     * what makes this safe against a zip-slip: no entry name ever reaches the filesystem, so a
     * crafted `../../` path has nothing to act on.
     */
    private fun extract(
        source: Uri,
        staged: File,
    ): Extracted {
        val input =
            context.contentResolver.openInputStream(source)
                ?: return Extracted.Failure(ArchiveFailure.UNREADABLE, "the picked file could not be opened")

        val properties = input.use { unzipInto(it, staged) }
        return when {
            properties == null ->
                Extracted.Failure(ArchiveFailure.NOT_AN_ARCHIVE, "no Podsilo manifest in this zip")
            properties.schemaVersion() > currentSchemaVersion() ->
                Extracted.Failure(ArchiveFailure.NEWER_SCHEMA, "archive schema ${properties.schemaVersion()}")
            !staged.looksLikeSqlite() ->
                Extracted.Failure(ArchiveFailure.UNREADABLE, "the database inside the zip is not a SQLite file")
            else -> Extracted.Manifest(properties)
        }
    }

    /**
     * **This check is load-bearing, not paranoia.** `SQLiteOpenHelper`'s default corruption handler
     * *deletes and recreates* a database it cannot open, so handing Room a damaged file does not
     * throw — it silently yields an empty database, and the restore would then faithfully replace
     * the user's real ledger with nothing. Robolectric caught exactly that on the first run of
     * `a corrupt archive leaves the existing data exactly as it was`.
     */
    private fun Properties.schemaVersion(): Int = getProperty(KEY_SCHEMA)?.toIntOrNull() ?: 0

    /**
     * Opens [staged] as a second Room database — which is what runs any missing migrations on an
     * older archive — and copies every table into the live one inside a single transaction.
     */
    private suspend fun restore(
        staged: File,
        manifest: Extracted.Manifest,
    ): ArchiveOutcome {
        val archived =
            Room
                .databaseBuilder(context, PodsiloDatabase::class.java, staged.absolutePath)
                .apply { PODSILO_MIGRATIONS.forEach { addMigrations(it) } }
                .build()

        return try {
            val feeds = archived.feedDao().getAll()
            val episodes = archived.episodeDao().getAll()
            val ledger = archived.episodeLedgerDao().getAll()
            val syncState = archived.syncStateDao().get()
            val log = archived.logDao().getAll()

            val read = ArchiveContents(feeds.size, episodes.size, ledger.size)
            manifest.verify(read)?.let { return@restore it }

            database.withTransaction {
                // Feeds first: deleting them cascades the episodes, so this clears both tables.
                database.feedDao().deleteAll()
                database.episodeLedgerDao().deleteAll()
                database.syncStateDao().deleteAll()
                database.logDao().clear()

                database.feedDao().upsertAll(feeds)
                episodes.chunked(RESTORE_BATCH).forEach { database.episodeDao().insertAll(it) }
                ledger.chunked(RESTORE_BATCH).forEach { database.episodeLedgerDao().upsertAll(it) }
                syncState?.let { database.syncStateDao().upsert(it) }
                log.chunked(RESTORE_BATCH).forEach { database.logDao().insertAll(it) }
            }

            ArchiveOutcome.Imported(read)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Room throws IllegalStateException for an identity-hash mismatch and
            // SQLiteException for a file that is not a database at all. Both mean the same thing to
            // the user, and the manifest already ruled out the one case worth naming separately.
            ArchiveOutcome.Failed(ArchiveFailure.UNREADABLE, "${manifest.describe()}: ${e.message}")
        } finally {
            archived.close()
        }
    }

    private sealed interface Extracted {
        data class Manifest(
            val properties: Properties,
        ) : Extracted {
            fun describe(): String = "archive format ${properties.getProperty(KEY_FORMAT)}"

            /**
             * The second half of the corruption guard, for damage the header check cannot see.
             *
             * A file with an intact header but broken interior pages opens as *some* database, and
             * whatever it happens to contain would then replace the user's real data. Comparing
             * what was read against the counts the export recorded turns that into a clean refusal.
             * Returns `null` when the archive is sound.
             */
            fun verify(read: ArchiveContents): ArchiveOutcome.Failed? {
                val expected =
                    ArchiveContents(
                        feeds = count(KEY_FEEDS),
                        episodes = count(KEY_EPISODES),
                        ledgerRows = count(KEY_LEDGER_ROWS),
                    )
                return if (read == expected) {
                    null
                } else {
                    ArchiveOutcome.Failed(
                        ArchiveFailure.UNREADABLE,
                        "archive says $expected but holds $read",
                    )
                }
            }

            private fun count(key: String): Int = properties.getProperty(key)?.toIntOrNull() ?: -1
        }

        data class Failure(
            val reason: ArchiveFailure,
            val detail: String?,
        ) : Extracted
    }
}

/**
 * Writes the archive's database entry to [staged] and returns its manifest, or `null` when either
 * is missing — which is what "this zip is not one of ours" looks like.
 *
 * Top-level rather than a method so the class stays inside detekt's function budget, and its two
 * entry names are matched **exactly**: no entry name from the archive ever reaches the filesystem,
 * so a crafted `../../` path has nothing to act on.
 */
private fun unzipInto(
    input: java.io.InputStream,
    staged: File,
): Properties? {
    ZipInputStream(input.buffered()).use { zip -> return zip.readKnownEntries(staged) }
}

private fun ZipInputStream.readKnownEntries(staged: File): Properties? {
    val zip = this
    var manifest: Properties? = null
    var sawDatabase = false
    generateSequence { nextEntry }.forEach { entry ->
        when (entry.name) {
            MANIFEST_ENTRY -> manifest = Properties().apply { load(zip) }
            DATABASE_ENTRY -> {
                staged.outputStream().use { copyTo(it) }
                sawDatabase = true
            }
        }
    }
    return manifest.takeIf { sawDatabase }
}

/**
 * **This check is load-bearing, not paranoia.** `SQLiteOpenHelper`'s default corruption handler
 * *deletes and recreates* a database it cannot open, so handing Room a damaged file does not throw —
 * it silently yields an *empty* database, and the restore would then faithfully replace the user's
 * real ledger with nothing. Robolectric caught exactly that on the first run of
 * `a corrupt archive leaves the existing data exactly as it was`.
 */
private fun File.looksLikeSqlite(): Boolean {
    if (length() < SQLITE_MAGIC.size) return false
    val header = ByteArray(SQLITE_MAGIC.size)
    inputStream().use { it.read(header) }
    return header.contentEquals(SQLITE_MAGIC)
}
