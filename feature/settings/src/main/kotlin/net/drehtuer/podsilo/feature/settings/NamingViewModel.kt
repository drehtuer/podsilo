// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.naming.DefaultNamingTemplateEngine
import java.time.ZoneId

/**
 * S6 — the naming template editor (`UI.adoc` §9).
 *
 * Contains **zero** sanitisation, truncation or date logic: every preview goes through the
 * already-tested `NamingTemplateEngine.resolve()` (architecture §11). That is the point of the
 * screen — a preview that agrees with the download because it *is* the download's code.
 *
 * Templates commit on change like the rest of settings, but only when valid: an invalid one is shown
 * with its reason and is not persisted, so a half-typed `{titl` never reaches a filename.
 */
class NamingViewModel(
    private val settingsRepository: SettingsRepository,
    private val sampleEpisodes: NamingSampleSource,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val _state = MutableStateFlow(NamingUiState())
    val state: StateFlow<NamingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = settingsRepository.observeNaming().first()
            _state.value =
                _state.value.copy(folderTemplate = stored.folderTemplate, fileTemplate = stored.fileTemplate)
            refreshPreviews(stored)
        }
    }

    fun onEvent(event: NamingEvent) {
        when (event) {
            is NamingEvent.FolderTemplateChanged -> update(folder = event.value)
            is NamingEvent.FileTemplateChanged -> update(file = event.value)
            NamingEvent.ResetToDefault ->
                update(
                    folder = NamingSettings.DEFAULT_FOLDER_TEMPLATE,
                    file = NamingSettings.DEFAULT_FILE_TEMPLATE,
                )
        }
    }

    private fun update(
        folder: String = _state.value.folderTemplate,
        file: String = _state.value.fileTemplate,
    ) {
        _state.value = _state.value.copy(folderTemplate = folder, fileTemplate = file)
        viewModelScope.launch {
            val stored = settingsRepository.observeNaming().first()
            val candidate = stored.copy(folderTemplate = folder, fileTemplate = file)
            val validation = validate(candidate)
            _state.value = _state.value.copy(validation = validation)
            if (validation is NamingUiState.Validation.Valid) {
                settingsRepository.setNaming(candidate)
                refreshPreviews(candidate)
            }
        }
    }

    /**
     * Validation *is* a preview attempt. Rather than reimplementing the engine's rules to predict
     * what it will reject, this asks it — the only definition of "valid template" that cannot drift
     * from what actually names files.
     */
    private fun validate(settings: NamingSettings): NamingUiState.Validation {
        if (settings.fileTemplate.isBlank()) {
            return NamingUiState.Validation.Invalid(NamingField.FILE, "The file template can't be empty.")
        }
        return runCatching { resolve(settings, PreviewCase.RECENT_EPISODE, sample(PreviewCase.RECENT_EPISODE)) }
            .fold(
                onSuccess = { NamingUiState.Validation.Valid },
                onFailure = {
                    NamingUiState.Validation.Invalid(
                        NamingField.FILE,
                        it.message ?: "This template can't be used.",
                    )
                },
            )
    }

    private suspend fun refreshPreviews(settings: NamingSettings) {
        val recent = sampleEpisodes.mostRecent()
        _state.value =
            _state.value.copy(
                previews =
                    PreviewCase.entries.map { case ->
                        val episode = if (case == PreviewCase.RECENT_EPISODE && recent != null) recent else sample(case)
                        NamingPreviewLine(
                            case = case,
                            resolved = runCatching { resolve(settings, case, episode) }.getOrElse { "—" },
                        )
                    },
            )
    }

    private fun resolve(
        settings: NamingSettings,
        case: PreviewCase,
        episode: Episode,
    ): String {
        val engine =
            DefaultNamingTemplateEngine(
                zoneId = zone,
                titleCleanupRules =
                    settings.titleCleanupRules.map {
                        net.drehtuer.podsilo.core.naming
                            .TitleCleanupRule(Regex(it.pattern), it.replacement)
                    },
                transliterate = settings.transliterate,
            )
        val resolved =
            engine.resolve(
                feed = SAMPLE_FEED,
                episode = episode,
                folderTemplate = settings.folderTemplate,
                fileTemplate = settings.fileTemplate,
            )
        // The case is not used in the string — it is the label the UI renders beside it.
        check(case in PreviewCase.entries)
        return "${resolved.folder}/${resolved.fileNameWithoutExtension}.${resolved.extension}"
    }
}

/**
 * The synthetic worst cases (`UI.adoc` §9). Deliberately unpleasant: an over-long title, an
 * episode with no date, and a title full of characters FAT32 rejects. The `MISSING_DATE` preview is
 * *expected* to render `00000000` (`architecture.adoc` §11) — if it ever shows an empty segment, the
 * engine regressed, not this screen.
 */
internal fun sample(case: PreviewCase): Episode =
    when (case) {
        PreviewCase.RECENT_EPISODE ->
            sampleEpisode("Warum Hamburg immer regnet", pubDate = SAMPLE_PUB_DATE)
        PreviewCase.MISSING_DATE ->
            sampleEpisode("Folge ohne Datum", pubDate = null)
        PreviewCase.OVERLONG_TITLE ->
            sampleEpisode("Über ".repeat(OVERLONG_REPEATS) + "Ende", pubDate = SAMPLE_PUB_DATE)
        PreviewCase.ILLEGAL_CHARACTERS ->
            sampleEpisode("Ep 3/4: \"Regen\" <live> | CON.  ", pubDate = SAMPLE_PUB_DATE)
    }

private const val OVERLONG_REPEATS = 80
private const val SAMPLE_PUB_DATE = 1_784_019_600_000L

private fun sampleEpisode(
    title: String,
    pubDate: Long?,
) = Episode(
    episodeKey = "sample",
    feedUrl = SAMPLE_FEED.url,
    guid = "sample",
    enclosureUrl = "https://example.org/sample.mp3",
    title = title,
    description = null,
    pubDate = pubDate,
    durationMs = 2_880_000,
    link = null,
)

private val SAMPLE_FEED =
    Feed(
        url = "https://example.org/feed.xml",
        title = "Der Podcast",
        imageUrl = null,
        firstSeenAt = 0,
        lastRefreshedAt = null,
        httpEtag = null,
        httpLastModified = null,
    )

/**
 * A real episode for the first preview line, so the author sees their own feed rather than only a
 * made-up one. `null` before the first refresh, which is normal and falls back to the sample.
 */
fun interface NamingSampleSource {
    suspend fun mostRecent(): Episode?
}
