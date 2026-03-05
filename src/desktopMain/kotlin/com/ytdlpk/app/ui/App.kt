package com.ytdlpk.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.AlertDialog
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Switch
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.TextButton
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ytdlpk.app.model.AppLanguage
import com.ytdlpk.app.model.AppState
import com.ytdlpk.app.model.FormatEntry
import com.ytdlpk.app.model.FormatKind
import com.ytdlpk.app.model.PlaylistMode
import com.ytdlpk.app.model.ThemeMode
import com.ytdlpk.app.util.formatDuration
import com.ytdlpk.app.util.formatSortScore
import com.ytdlpk.app.util.pickDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.net.URI

data class Palette(
    val bg: Color,
    val panel: Color,
    val panelSoft: Color,
    val accent: Color,
    val textMain: Color,
    val textSub: Color
)

private val darkPalette = Palette(
    bg = Color(0xFF1E1E1E),
    panel = Color(0xFF252525),
    panelSoft = Color(0xFF2B2B2B),
    accent = Color(0xFFB0FF57),
    textMain = Color(0xFFE0E0E0),
    textSub = Color(0xFFBBBBBB)
)

private val lightPalette = Palette(
    bg = Color(0xFFF3F3F3),
    panel = Color(0xFFFFFFFF),
    panelSoft = Color(0xFFECECEC),
    accent = Color(0xFF2E7D32),
    textMain = Color(0xFF222222),
    textSub = Color(0xFF555555)
)

@Composable
fun App(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsState()
    var mainTab by remember { mutableStateOf(0) }
    val s = uiStrings(state.settings.language)
    val isDark = when (state.settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemDarkMode()
    }
    val p = if (isDark) darkPalette else lightPalette

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(p.bg)
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TopBar(state, viewModel, p, s)

                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RightPreviewPanel(
                        state = state,
                        p = p,
                        modifier = Modifier.weight(1f),
                        onPlaylistModeChange = viewModel::onPlaylistMode
                    )

                    Column(modifier = Modifier.weight(1.8f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(p.panel, RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            TabRow(selectedTabIndex = mainTab, backgroundColor = p.panel, contentColor = p.accent) {
                                Tab(selected = mainTab == 0, onClick = { mainTab = 0 }, text = { Text(s.formatsTab) })
                                Tab(selected = mainTab == 1, onClick = { mainTab = 1 }, text = { Text(s.optionsTab) })
                                Tab(selected = mainTab == 2, onClick = { mainTab = 2 }, text = { Text(s.globalTab) })
                            }
                            Spacer(Modifier.height(10.dp))
                            when (mainTab) {
                                0 -> FormatSection(state, viewModel, p, s)
                                1 -> OptionsSection(state, viewModel, p, s)
                                else -> GlobalOptionsSection(state, viewModel, p, s)
                            }
                        }
                    }
                }

                BottomActionBar(state, viewModel, p, s)
                ProgressSection(state, p, s)
                state.lastError?.let { Text("${s.errorPrefix}: $it", color = Color(0xFFFF6B6B)) }
                state.infoMessage?.let { message ->
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissInfoMessage() },
                        title = { Text(s.infoTitle) },
                        text = { Text(message) },
                        confirmButton = {
                            TextButton(onClick = { viewModel.dismissInfoMessage() }) {
                                Text(s.ok)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: AppState, viewModel: AppViewModel, p: Palette, s: UiStrings) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.panel, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = state.url,
            onValueChange = viewModel::onUrlChange,
            label = { Text(s.enterUrl) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = p.textMain,
                backgroundColor = p.panelSoft,
                cursorColor = p.accent,
                focusedBorderColor = p.accent,
                unfocusedBorderColor = p.textSub.copy(alpha = 0.6f),
                focusedLabelColor = p.accent,
                unfocusedLabelColor = p.textSub
            )
        )

        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { readClipboardText()?.let { txt -> viewModel.onUrlChange(txt) } },
            colors = ButtonDefaults.buttonColors(
                backgroundColor = p.panelSoft.copy(alpha = 0.95f),
                contentColor = p.textMain
            )
        ) { Text("📋") }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { viewModel.analyze() },
            enabled = !state.isAnalyzing && state.toolsReady,
            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1E88E5), contentColor = Color.White)
        ) { Text(s.analyze) }
    }
}

@Composable
private fun FormatSection(state: AppState, viewModel: AppViewModel, p: Palette, s: UiStrings) {
    if (state.isAnalyzing) {
        Text(s.loadingFormats, color = p.textSub)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = p.accent,
            backgroundColor = p.panelSoft
        )
        return
    }

    if (state.formats.isEmpty()) {
        Text(s.analyzeForFormats, color = p.textSub)
        return
    }

    val tabs = listOf(FormatKind.VIDEO_AUDIO, FormatKind.VIDEO_ONLY, FormatKind.AUDIO_ONLY)
    TabRow(selectedTabIndex = tabs.indexOf(state.selectedFormatTab).coerceAtLeast(0), backgroundColor = p.panel, contentColor = p.accent) {
        tabs.forEach { tab ->
            Tab(selected = state.selectedFormatTab == tab, onClick = { viewModel.onTabChange(tab) }, text = {
                Text(
                    when (tab) {
                        FormatKind.VIDEO_AUDIO -> s.videoAudio
                        FormatKind.VIDEO_ONLY -> s.videoOnly
                        FormatKind.AUDIO_ONLY -> s.audioOnly
                        FormatKind.UNKNOWN -> "Other"
                    }
                )
            })
        }
    }

    val audioOnlyFormats = state.formats
        .filter { it.kind == FormatKind.AUDIO_ONLY }
        .sortedByDescending { formatSortScore(it) }

    val selectedEntry = state.selectedFormat
    if (
        state.selectedFormatTab == FormatKind.VIDEO_AUDIO &&
        selectedEntry?.kind == FormatKind.VIDEO_ONLY &&
        audioOnlyFormats.isNotEmpty()
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.pairedAudio, color = p.textMain)
            val current = state.selectedAudioOnlyFormat ?: audioOnlyFormats.first()
            SettingDropdown(
                current = current,
                options = audioOnlyFormats,
                onSelect = { viewModel.onAudioOnlyFormatSelected(it.formatId) },
                width = 260.dp,
                p = p,
                label = ::formatSelectionLabel
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    val sortedFormats = state.filteredFormats.sortedByDescending { formatSortScore(it) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.panelSoft.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(24.dp))
            Text("Format", color = p.textSub, modifier = Modifier.width(120.dp))
            Text("Resolution", color = p.textSub, modifier = Modifier.width(150.dp))
            Text("Bitrate", color = p.textSub)
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 36.dp, start = 4.dp, end = 4.dp, bottom = 4.dp)
        ) {
            items(sortedFormats) { entry ->
                val selected = state.selectedFormatId == entry.formatId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) p.panelSoft.copy(alpha = 0.75f) else Color.Transparent,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { viewModel.onFormatSelected(entry.formatId) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (selected) "●" else "○",
                        color = if (selected) p.accent else p.textSub,
                        modifier = Modifier.width(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(entry.ext, color = p.textMain, modifier = Modifier.width(120.dp))
                    Text(entry.resolution ?: "-", color = p.textMain, modifier = Modifier.width(150.dp))
                    Text(formatBitrate(entry), color = p.textMain)
                }
            }
        }
    }
}

@Composable
private fun LoadingInfoRow(label: String, p: Palette) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$label:", color = p.textSub, modifier = Modifier.width(88.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = p.accent,
            backgroundColor = p.panelSoft
        )
    }
}

@Composable
private fun OptionsSection(state: AppState, viewModel: AppViewModel, p: Palette, s: UiStrings) {
    val settings = state.settings
    val audioFormatOptions = listOf("mp3", "m4a", "opus", "wav", "flac")
    val mergeFormatOptions = listOf("mp4", "mkv", "webm", "mov")

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(s.saveLocation, color = p.textMain)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = settings.outputDirectory,
                onValueChange = { viewModel.onSettingsChange(settings.copy(outputDirectory = it)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = p.textMain,
                    backgroundColor = p.panelSoft,
                    cursorColor = p.accent,
                    focusedBorderColor = p.accent,
                    unfocusedBorderColor = p.textSub.copy(alpha = 0.6f)
                )
            )
            Button(
                onClick = { pickDirectory(settings.outputDirectory)?.let { viewModel.onSettingsChange(settings.copy(outputDirectory = it)) } },
                colors = ButtonDefaults.buttonColors(backgroundColor = p.panelSoft, contentColor = p.textMain)
            ) { Text(s.browse) }
        }

        OutlinedTextField(
            value = settings.fileNameTemplate,
            onValueChange = { viewModel.onSettingsChange(settings.copy(fileNameTemplate = it)) },
            label = { Text(s.filenameTemplate) },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = p.textMain,
                backgroundColor = p.panelSoft,
                cursorColor = p.accent,
                focusedBorderColor = p.accent,
                unfocusedBorderColor = p.textSub.copy(alpha = 0.6f),
                focusedLabelColor = p.accent,
                unfocusedLabelColor = p.textSub
            )
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = settings.extractAudio, onCheckedChange = { viewModel.onSettingsChange(settings.copy(extractAudio = it)) })
            Text(s.extractAudio, color = p.textMain)
            SettingDropdown(current = settings.audioFormat, options = audioFormatOptions, onSelect = { viewModel.onSettingsChange(settings.copy(audioFormat = it)) }, width = 140.dp, p = p)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = settings.includeAutoSubs, onCheckedChange = { viewModel.onSettingsChange(settings.copy(includeAutoSubs = it)) })
            Text(s.includeAutoSubs, color = p.textMain)
            OutlinedTextField(
                value = settings.subLang,
                onValueChange = { viewModel.onSettingsChange(settings.copy(subLang = it)) },
                label = { Text("sub-lang") },
                modifier = Modifier.width(140.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = p.textMain,
                    backgroundColor = p.panelSoft,
                    cursorColor = p.accent,
                    focusedBorderColor = p.accent,
                    unfocusedBorderColor = p.textSub.copy(alpha = 0.6f),
                    focusedLabelColor = p.accent,
                    unfocusedLabelColor = p.textSub
                )
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.mergeOutputFormat, color = p.textMain)
            SettingDropdown(current = settings.mergeOutputFormat, options = mergeFormatOptions, onSelect = { viewModel.onSettingsChange(settings.copy(mergeOutputFormat = it)) }, width = 160.dp, p = p)
        }
    }
}

@Composable
private fun GlobalOptionsSection(state: AppState, viewModel: AppViewModel, p: Palette, s: UiStrings) {
    val settings = state.settings
    val languageOptions = listOf(AppLanguage.ENGLISH, AppLanguage.JAPANESE, AppLanguage.KOREAN)
    val themeOptions = listOf(ThemeMode.DARK, ThemeMode.LIGHT)

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(s.toolVersions, color = p.textMain, fontWeight = FontWeight.SemiBold)
        Text("yt-dlp: ${state.ytDlpVersion}", color = p.textSub)
        Text("ffmpeg: ${state.ffmpegVersion}", color = p.textSub)
        Text("latest yt-dlp: ${state.latestYtDlpVersion}", color = p.textSub)
        Text("latest ffmpeg: ${state.latestFfmpegVersion}", color = p.textSub)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.checkLatestToolVersions() }, enabled = !state.checkingLatestTools,
                colors = ButtonDefaults.buttonColors(backgroundColor = p.panelSoft, contentColor = p.textMain)) {
                Text(if (state.checkingLatestTools) s.checking else s.checkLatest)
            }
            Button(onClick = { viewModel.updateYtDlp() }, colors = ButtonDefaults.buttonColors(backgroundColor = p.panelSoft, contentColor = p.textMain)) {
                Text(s.updateYtdlp)
            }
            Button(onClick = { viewModel.updateFfmpeg() }, colors = ButtonDefaults.buttonColors(backgroundColor = p.panelSoft, contentColor = p.textMain)) {
                Text(s.updateFfmpeg)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.themeSystem, color = p.textMain)
            Switch(
                checked = settings.themeMode == ThemeMode.SYSTEM,
                onCheckedChange = { checked ->
                    viewModel.onSettingsChange(settings.copy(themeMode = if (checked) ThemeMode.SYSTEM else ThemeMode.DARK))
                }
            )
            if (settings.themeMode != ThemeMode.SYSTEM) {
                SettingDropdown(
                    current = settings.themeMode,
                    options = themeOptions,
                    label = ::themeModeLabel,
                    onSelect = { viewModel.onSettingsChange(settings.copy(themeMode = it)) },
                    width = 120.dp,
                    p = p
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.language, color = p.textMain)
            SettingDropdown(
                current = settings.language,
                label = ::languageLabel,
                options = languageOptions,
                onSelect = { viewModel.onSettingsChange(settings.copy(language = it)) },
                width = 140.dp,
                p = p
            )
        }
    }
}

@Composable
private fun <T> SettingDropdown(
    current: T,
    options: List<T>,
    onSelect: (T) -> Unit,
    width: Dp,
    p: Palette,
    label: (T) -> String = { it.toString() }
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(
            onClick = { expanded = true },
            modifier = Modifier.width(width).height(40.dp),
            colors = ButtonDefaults.buttonColors(backgroundColor = p.panelSoft, contentColor = p.textMain)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label(current))
                Spacer(Modifier.weight(1f))
                Text("▼", color = p.textSub)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.width(width).background(p.panel)) {
            options.forEach { option ->
                DropdownMenuItem(onClick = {
                    onSelect(option)
                    expanded = false
                }) {
                    Text(label(option), color = p.textMain)
                }
            }
        }
    }
}

@Composable
private fun RightPreviewPanel(
    state: AppState,
    p: Palette,
    modifier: Modifier = Modifier,
    onPlaylistModeChange: (PlaylistMode) -> Unit
) {
    val s = uiStrings(state.settings.language)
    val image = rememberThumbnailImage(state.metadata?.thumbnailUrl)
    val m = state.metadata

    Column(
        modifier = Modifier.then(modifier).fillMaxSize().background(p.panel, RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(320.dp)
                .height(200.dp)
                .align(Alignment.CenterHorizontally)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (state.isAnalyzing) {
                CircularProgressIndicator(color = p.accent)
            } else if (image != null) {
                Image(bitmap = image, contentDescription = "thumbnail", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Text(s.mediaPreview, color = p.textSub)
            }
        }

        if (state.isAnalyzing) {
            LoadingInfoRow(s.title, p)
            LoadingInfoRow(s.channel, p)
            LoadingInfoRow(s.duration, p)
        } else {
            Text("${s.title}: ${m?.title ?: "-"}", color = p.textMain)
            Text("${s.channel}: ${m?.uploader ?: "-"}", color = p.textSub)
            Text("${s.duration}: ${formatDuration(m?.durationSeconds)}", color = p.textSub)

            if (m?.isPlaylist == true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.playlist, color = p.textMain)
                    Spacer(Modifier.width(8.dp))
                    RadioButton(
                        selected = state.playlistMode == PlaylistMode.PLAYLIST,
                        onClick = { onPlaylistModeChange(PlaylistMode.PLAYLIST) }
                    )
                    Text(s.playlistAll, color = p.textSub)
                    Spacer(Modifier.width(8.dp))
                    RadioButton(
                        selected = state.playlistMode == PlaylistMode.SINGLE,
                        onClick = { onPlaylistModeChange(PlaylistMode.SINGLE) }
                    )
                    Text(s.singleOnly, color = p.textSub)
                }
            }
        }
    }
}

@Composable
private fun BottomActionBar(state: AppState, viewModel: AppViewModel, p: Palette, s: UiStrings) {
    Row(
        modifier = Modifier.fillMaxWidth().background(p.panel, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        Button(
            onClick = { viewModel.cancelDownload() },
            enabled = state.isDownloading,
            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF8E2A2A), contentColor = Color.White)
        ) { Text(s.cancel) }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { viewModel.download() },
            enabled = !state.isDownloading &&
                !state.isAnalyzing &&
                state.toolsReady &&
                state.metadata != null &&
                state.formats.isNotEmpty() &&
                state.selectedFormatId != null,
            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4CAF50), contentColor = Color.White)
        ) { Text(s.download) }
    }
}

@Composable
private fun ProgressSection(state: AppState, p: Palette, s: UiStrings) {
    val waitingDownloadProgress = state.isDownloading && state.progress.percent == null
    val animateStatusText = state.isAnalyzing || waitingDownloadProgress
    var dots by remember(animateStatusText) { mutableStateOf(".") }
    androidx.compose.runtime.LaunchedEffect(animateStatusText) {
        if (!animateStatusText) {
            dots = "."
            return@LaunchedEffect
        }
        while (true) {
            dots = when (dots) {
                "." -> ".."
                ".." -> "..."
                else -> "."
            }
            delay(350)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().background(p.panel, RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(s.progress, color = p.textMain, fontWeight = FontWeight.SemiBold)
        if (state.isAnalyzing) {
            Text("${s.analyzingFromUrl}$dots", color = p.textSub)
        } else if (waitingDownloadProgress) {
            Text("${s.preparingDownload}$dots", color = p.textSub)
        } else state.progress.percent?.let {
            LinearProgressIndicator(
                progress = (it / 100.0).toFloat(),
                modifier = Modifier.fillMaxWidth(),
                color = p.accent,
                backgroundColor = Color(0xFF3A3A3A)
            )
            Text("${"%.1f".format(it)}% | speed: ${state.progress.speed ?: "-"} | ETA: ${state.progress.eta ?: "-"}", color = p.textSub)
        } ?: Text(s.noActiveProgress, color = p.textSub)
    }
}

private fun readClipboardText(): String? {
    return runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()
}

private fun formatBitrate(entry: FormatEntry): String {
    return entry.tbrKbps?.let { "${it.toInt()} kbps" }
        ?: entry.abrKbps?.let { "${it.toInt()} kbps" }
        ?: "-"
}

private fun formatSelectionLabel(entry: FormatEntry): String {
    val bitrate = formatBitrate(entry)
    return "${entry.ext} | $bitrate"
}

@Composable
private fun rememberThumbnailImage(url: String?): ImageBitmap? {
    val imageState = produceState<ImageBitmap?>(initialValue = null, key1 = url) {
        value = null
        if (url.isNullOrBlank()) return@produceState
        value = runCatching {
            withContext(Dispatchers.IO) {
                URI.create(url).toURL().openStream().use { input ->
                    SkiaImage.makeFromEncoded(input.readBytes()).toComposeImageBitmap()
                }
            }
        }.getOrNull()
    }
    return imageState.value
}

@Composable
private fun isSystemDarkMode(): Boolean = isSystemInDarkTheme()

private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.ENGLISH -> "English"
    AppLanguage.JAPANESE -> "日本語"
    AppLanguage.KOREAN -> "한국어"
}

private fun themeModeLabel(themeMode: ThemeMode): String = when (themeMode) {
    ThemeMode.SYSTEM -> "SYSTEM"
    ThemeMode.DARK -> "DARK"
    ThemeMode.LIGHT -> "LIGHT"
}
