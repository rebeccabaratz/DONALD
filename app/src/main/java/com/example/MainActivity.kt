package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Space
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTO_START = "AUTO_START"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val autoStart = intent.getBooleanExtra(EXTRA_AUTO_START, false)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen(autoStart = autoStart)
            }
        }
    }

}

@Composable
fun MainScreen(autoStart: Boolean = false) {
    val context = LocalContext.current
    val viewModel: VoiceAgentViewModel = viewModel()

    var recordAudioPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        recordAudioPermissionGranted = isGranted
    }

    LaunchedEffect(Unit) {
        if (!recordAudioPermissionGranted) {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = Color(0xFF12141C)
        ) {
            if (!recordAudioPermissionGranted) {
                PermissionRequiredScreen {
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            } else {
                DashboardScreen(viewModel = viewModel, autoStart = autoStart)
            }
        }
    }
}

@Composable
fun PermissionRequiredScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(Color(0x15FF5252), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Микрофон",
                tint = Color(0xFFFF5252),
                modifier = Modifier.size(48.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Требуется доступ к микрофону",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Без доступа к микрофону Дональд не сможет слышать вас и помогать практиковаться в английском во время езды. Пожалуйста, выдайте разрешение.",
            fontSize = 14.sp,
            color = Color(0xFFABAFB3),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
            modifier = Modifier
                .height(48.dp)
                .testTag("grant_permission_button")
        ) {
            Text("Разрешить доступ", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DashboardScreen(viewModel: VoiceAgentViewModel, autoStart: Boolean = false) {
    val state by viewModel.state.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val threshold by viewModel.threshold.collectAsState()
    val silenceDurationMs by viewModel.silenceDurationMs.collectAsState()
    val selectedVoice by viewModel.selectedVoice.collectAsState()
    val bookIndex by viewModel.bookIndex.collectAsState()
    val transcripts by viewModel.transcripts.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val liveAmp by viewModel.liveAmplitude.collectAsState()
    val customApiKey by viewModel.customApiKey.collectAsState()

    val activeKey = viewModel.getActiveApiKey()
    val isPlaceholderOrEmpty = activeKey.isEmpty()
    var isSettingsExpanded by remember(isPlaceholderOrEmpty) { mutableStateOf(isPlaceholderOrEmpty) }

    val listState = rememberLazyListState()

    // Keep screen on while session is active (car use: screen must not turn off mid-conversation)
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(state) {
        if (state != AgentState.IDLE && state != AgentState.PAUSED) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Close the app when AI calls exit_app() in response to "стоп" voice command
    LaunchedEffect(viewModel) {
        viewModel.closeAppEvent.collect {
            activity?.finishAffinity()
        }
    }

    // Auto-start when launched from widget
    LaunchedEffect(autoStart) {
        if (autoStart) {
            delay(300)
            viewModel.startCycle()
        }
    }

    // Scroll chat logs dynamically downwards
    LaunchedEffect(transcripts.size) {
        if (transcripts.isNotEmpty()) {
            listState.animateScrollToItem(transcripts.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header Info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ДОНАЛЬД • АГЕНТ",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF818CF8),
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Голос: $selectedVoice",
                    fontSize = 12.sp,
                    color = Color(0xFF888D93),
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Mode Label Flag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (mode == AgentMode.BOOK_READING) Color(0x30F59E0B) else Color(0x3010B981)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clickable { viewModel.toggleReadingModeDirectly() }
                ) {
                    Text(
                        text = if (mode == AgentMode.BOOK_READING) "ТОМ СОЙЕР 📖" else "АКТИВНАЯ БЕСЕДА 💬",
                        color = if (mode == AgentMode.BOOK_READING) Color(0xFFF59E0B) else Color(0xFF10B981),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }

            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Large status warning if api keys are defaulted
        if (errorMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0x30EF4444)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = Color(0xFFFCA5A5),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { isSettingsExpanded = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(
                            text = "Открыть настройки ключа ⚙️",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Active Practice Widget when reading Tom Sawyer
        AnimatedVisibility(visible = mode == AgentMode.BOOK_READING) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2130)),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ПОВТОРИТЕ ЗА ДОНАЛЬДОМ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF59E0B),
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Блок ${bookIndex + 1} / ${viewModel.tomSawyerPhrases.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF9EADBA)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = viewModel.tomSawyerPhrases[bookIndex],
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        lineHeight = 26.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = viewModel.tomSawyerTranslations[bookIndex],
                        fontSize = 13.sp,
                        color = Color(0xC0A1A1AA),
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Divider(color = Color(0x1F71717A))

                    Spacer(modifier = Modifier.height(8.dp))

                    // Small helper forward/backward triggers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(
                            onClick = { viewModel.setBookIndex(bookIndex - 1) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x1AFFFFFF)),
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("← Пред.", fontSize = 11.sp, color = Color.White)
                        }

                        Button(
                            onClick = { viewModel.setBookIndex(bookIndex + 1) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x1AFFFFFF)),
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("След. →", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }
        }

        // Live Chat Transcript Box
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF161822))
                .padding(8.dp)
        ) {
            if (transcripts.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Ожидание запуска диалога",
                        fontSize = 14.sp,
                        color = Color(0xFF6B7280),
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Нажмите на круг внизу, чтобы разбудить Дональда.",
                        fontSize = 12.sp,
                        color = Color(0xFF4B5563),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(transcripts, key = { it.id }) { msg ->
                        ChatBubble(message = msg)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Realtime Animated Pulse Waveform
        AmbientWaveform(
            amplitude = liveAmp,
            isListening = state == AgentState.LISTENING,
            isSpeaking = state == AgentState.SPEAKING
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Center Control Area (Actions & Dial Ring Button)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Helper Restart Button
            IconButton(
                onClick = { viewModel.clearTranscripts() },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0x1AFFFFFF), CircleShape)
            ) {
                Text("🧹", fontSize = 18.sp)
            }

            // Big Central Ring Button 
            CenterControlRing(
                state = state,
                onClick = {
                    if (state == AgentState.IDLE || state == AgentState.PAUSED) {
                        viewModel.startCycle()
                    } else {
                        viewModel.stopCycle()
                    }
                }
            )

            // Settings button — moved here from header (header ⚙️ was cut off on small screens)
            IconButton(
                onClick = { isSettingsExpanded = true },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0x1AFFFFFF), CircleShape)
            ) {
                Text("⚙️", fontSize = 18.sp)
            }
        }

        if (isSettingsExpanded) {
            SettingsDialog(
                thresholdValue = threshold,
                onThresholdChange = { viewModel.setThreshold(it) },
                silenceDuration = silenceDurationMs,
                onSilenceChange = { viewModel.setSilenceDuration(it) },
                customApiKey = customApiKey,
                onCustomApiKeyChange = { viewModel.setCustomApiKey(it) },
                activeApiKey = viewModel.getActiveApiKey(),
                selectedVoice = selectedVoice,
                onVoiceChange = { viewModel.selectVoice(it) },
                onReadCostLog = { viewModel.readCostLog() },
                onClearCostLog = { viewModel.clearCostLog() },
                onDismissRequest = { isSettingsExpanded = false }
            )
        }
    }
}

@Composable
fun SettingsDialog(
    thresholdValue: Int,
    onThresholdChange: (Int) -> Unit,
    silenceDuration: Long,
    onSilenceChange: (Long) -> Unit,
    customApiKey: String,
    onCustomApiKeyChange: (String) -> Unit,
    activeApiKey: String,
    selectedVoice: String,
    onVoiceChange: (String) -> Unit,
    onReadCostLog: () -> String,
    onClearCostLog: () -> Unit,
    onDismissRequest: () -> Unit
) {
    var keyInput by remember(customApiKey) { mutableStateOf(customApiKey) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2130)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 24.dp)
                .testTag("settings_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header row with Close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "НАСТРОЙКИ • OPENAI REALTIME v1 🛠️",
                        fontSize = 13.sp,
                        color = Color(0xFF818CF8),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("❌", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "НАСТРОЙКА ЧУВСТВИТЕЛЬНОСТИ МИКРОФОНА",
                    fontSize = 10.sp,
                    color = Color(0xFFA1A1AA),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Microphone Threshold Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Порог речи (чувствительность):", fontSize = 11.sp, color = Color.White)
                    Text("${thresholdValue}", fontSize = 11.sp, color = Color(0xFF818CF8), fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = thresholdValue.toFloat(),
                    onValueChange = { onThresholdChange(it.toInt()) },
                    valueRange = 500f..8000f,
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color(0xFF818CF8),
                        thumbColor = Color(0xFF818CF8)
                    ),
                    modifier = Modifier.height(32.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Silence duration slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Пауза до отправки (мс):", fontSize = 11.sp, color = Color.White)
                    Text("${silenceDuration}мс", fontSize = 11.sp, color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = silenceDuration.toFloat(),
                    onValueChange = { onSilenceChange(it.toLong()) },
                    valueRange = 1000f..4000f,
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color(0xFFF59E0B),
                        thumbColor = Color(0xFFF59E0B)
                    ),
                    modifier = Modifier.height(32.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = Color(0x1F71717A))
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "ВЫБОР ГОЛОСА OPENAI 🗣️",
                    fontSize = 10.sp,
                    color = Color(0xFFA1A1AA),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                val voices = listOf(
                    "marin" to "Marin (Мужской, рекомендован ⭐) 👨‍💼",
                    "cedar" to "Cedar (Мужской, рекомендован ⭐) 👨",
                    "alloy" to "Alloy (Мужской, нейтральный) 🎙️",
                    "echo" to "Echo (Мужской, глубокий) 👨",
                    "shimmer" to "Shimmer (Женский, нежный) 👩",
                    "ash" to "Ash (Мужской, спокойный) 👱",
                    "coral" to "Coral (Женский, тёплый) 👧",
                    "sage" to "Sage (Нейтральный, чёткий) 🧑",
                    "verse" to "Verse (Мужской, выразительный) 🎵",
                    "ballad" to "Ballad (Мужской, мягкий) 🎶"
                )

                voices.forEach { (voiceId, displayName) ->
                    val isSelected = selectedVoice == voiceId
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0x33F59E0B) else Color(0x0AFFFFFF)
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) Color(0xFFF59E0B) else Color(0x1F71717A)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onVoiceChange(voiceId) }
                            .testTag("voice_item_card_$voiceId"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onVoiceChange(voiceId) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color(0xFFF59E0B),
                                    unselectedColor = Color(0xFFA1A1AA)
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = displayName,
                                fontSize = 11.sp,
                                color = if (isSelected) Color.White else Color(0xFFD1D5DB),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = Color(0x1F71717A))
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "НАСТРОЙКА КЛЮЧА OPENAI API",
                    fontSize = 10.sp,
                    color = Color(0xFFA1A1AA),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Получите ключ на platform.openai.com → API keys. Ключ начинается с sk-...",
                    fontSize = 11.sp,
                    color = Color(0xFFABAFB3),
                    lineHeight = 15.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0x1F818CF8)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                uriHandler.openUri("https://platform.openai.com/api-keys")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "👉 Открыть OpenAI Platform 🌐",
                            fontSize = 11.sp,
                            color = Color(0xFF818CF8),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Spacer(modifier = Modifier.height(12.dp))

                val isCustomActive = customApiKey.isNotEmpty()
                val isFallbackPlaceholder = activeApiKey.isEmpty()
                
                val activeMasked = if (isFallbackPlaceholder) {
                    "не настроен (встроенный шаблон)"
                } else if (activeApiKey.length > 8) {
                    "${activeApiKey.take(4)}...${activeApiKey.takeLast(4)} (длина: ${activeApiKey.length})"
                } else {
                    "не установлен"
                }

                Text(
                    text = if (isCustomActive) {
                        "🟢 Используется ваш сохраненный API-ключ"
                    } else if (isFallbackPlaceholder) {
                        "🔴 API-ключ отсутствует"
                    } else {
                        "🔵 Используется встроенный API-ключ разработчика"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCustomActive) Color(0xFF10B981) else if (isFallbackPlaceholder) Color(0xFFEF4444) else Color(0xFF6366F1)
                )
                Text(
                    text = "Текущий статус: $activeMasked",
                    fontSize = 11.sp,
                    color = Color(0xFF888D93)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    placeholder = { Text("Вставьте ваш OpenAI API-ключ (начиная с sk-...)", fontSize = 12.sp, color = Color.Gray) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("api_key_outlined_text_field"),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF161822),
                        unfocusedContainerColor = Color(0xFF161822),
                        focusedIndicatorColor = Color(0xFF818CF8),
                        unfocusedIndicatorColor = Color(0xFF4B5563)
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onCustomApiKeyChange(keyInput)
                            onDismissRequest()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .testTag("save_api_key_button"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Сохранить", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            keyInput = ""
                            onCustomApiKeyChange("")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x15FF5252)),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .testTag("clear_api_key_button"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Сбросить", fontSize = 12.sp, color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = Color(0x1F71717A))
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "ЛОГ РАСХОДОВ 📊",
                    fontSize = 10.sp,
                    color = Color(0xFFA1A1AA),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Показывает сколько аудио отправлено/получено и сколько токенов промпта потрачено за каждую сессию.",
                    fontSize = 10.sp,
                    color = Color(0xFF6B7280),
                    lineHeight = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                var showCostLog by remember { mutableStateOf(false) }
                var costLogText by remember { mutableStateOf("") }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { costLogText = onReadCostLog(); showCostLog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x1A818CF8)),
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Показать лог", fontSize = 11.sp, color = Color(0xFF818CF8), fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onClearCostLog() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x15FF5252)),
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Очистить", fontSize = 11.sp, color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                    }
                }

                if (showCostLog) {
                    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                    Dialog(onDismissRequest = { showCostLog = false }) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2130)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.9f)
                                .padding(4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp).fillMaxHeight()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Лог расходов", fontSize = 13.sp, color = Color(0xFF818CF8), fontWeight = FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(costLogText))
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Text("📋", fontSize = 14.sp)
                                        }
                                        IconButton(onClick = { showCostLog = false }, modifier = Modifier.size(28.dp)) {
                                            Text("❌", fontSize = 12.sp)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = costLogText,
                                        fontSize = 10.sp,
                                        color = Color(0xFFD1D5DB),
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CenterControlRing(state: AgentState, onClick: () -> Unit) {
    // Generate pulsating halo effects for listening/speaking states
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val colorAccent by animateColorAsState(
        targetValue = when (state) {
            AgentState.LISTENING -> Color(0xFFEF4444) // Bright warning red for active mic
            AgentState.PROCESSING -> Color(0xFF8B5CF6) // Violet for thinking
            AgentState.SPEAKING -> Color(0xFF3B82F6) // Ocean blue for talking 
            AgentState.PAUSED, AgentState.IDLE -> Color(0xFF10B981) // Green play to start
        },
        label = "color"
    )

    val statusLabel = when (state) {
        AgentState.LISTENING -> "СЛУШАЮ"
        AgentState.PROCESSING -> "ДУМАЮ..."
        AgentState.SPEAKING -> "ГОВОРЮ"
        AgentState.PAUSED -> "СТАРТ"
        AgentState.IDLE -> "СТАРТ"
    }

    val iconSymbol = when (state) {
        AgentState.LISTENING -> Icons.Filled.Mic
        AgentState.PROCESSING -> Icons.Filled.Mic
        AgentState.SPEAKING -> Icons.Filled.VolumeUp
        AgentState.PAUSED -> Icons.Filled.Mic
        AgentState.IDLE -> Icons.Filled.Mic
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(110.dp)
            .clickable(onClick = onClick)
            .testTag("center_control_ring")
    ) {
        // Outer pulsating glow
        if (state == AgentState.LISTENING || state == AgentState.SPEAKING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(pulseScale)
                    .background(colorAccent.copy(alpha = 0.2f), CircleShape)
            )
        }

        // Mid circle frame
        Box(
            modifier = Modifier
                .size(90.dp)
                .shadow(elevation = 8.dp, shape = CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(colorAccent, colorAccent.copy(alpha = 0.7f))
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = iconSymbol,
                    contentDescription = statusLabel,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = statusLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
fun AmbientWaveform(
    amplitude: Int,
    isListening: Boolean,
    isSpeaking: Boolean
) {
    val barCount = 15
    val infiniteTransition = rememberInfiniteTransition(label = "wave")

    // Create a list of animating float points to generate beautiful visual noise when active
    val animations = (0 until barCount).map { index ->
        val duration = remember { (600..1200).random() }
        infiniteTransition.animateFloat(
            initialValue = 10f,
            targetValue = 60f,
            animationSpec = infiniteRepeatable(
                animation = tween(duration, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$index"
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(55.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F1015))
            .padding(horizontal = 24.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val widthPerBar = (size.width - (barCount - 1) * 8.dp.toPx()) / barCount
            val baseHeight = size.height
            val midY = baseHeight / 2

            for (i in 0 until barCount) {
                val waveHeight = if (isListening) {
                    // Drive actual amplitude height directly 
                    val normalizedAmp = (amplitude.toFloat() / 200f).coerceIn(4f, 48f)
                    val offsetImpact = sin((i.toFloat() / barCount.toFloat()) * Math.PI).toFloat()
                    normalizedAmp * offsetImpact + 6f
                } else if (isSpeaking) {
                    // Pure generative animated spectrum 
                    animations[i].value
                } else {
                    // Subtle dynamic sleeping beat 
                    4f
                }

                val startX = i * (widthPerBar + 8.dp.toPx())
                val startY = midY - (waveHeight / 2)

                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = if (isListening) {
                            listOf(Color(0xFFEF4444), Color(0xFFF59E0B))
                        } else if (isSpeaking) {
                            listOf(Color(0xFF6366F1), Color(0xFF3B82F6))
                        } else {
                            listOf(Color(0xFF4B5563), Color(0xFF1F2937))
                        }
                    ),
                    topLeft = Offset(startX, startY),
                    size = Size(widthPerBar, waveHeight.coerceAtLeast(4f)),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }
        }
    }
}

@Composable
fun ChatBubble(message: BubbleMessage) {
    val isDonald = message.sender == "Дональд"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isDonald) Arrangement.Start else Arrangement.End
    ) {
        if (isDonald) {
            // Little round avatar indicating Donald is talking
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(0xFF6366F1), CircleShape)
                    .align(Alignment.Top),
                contentAlignment = Alignment.Center
            ) {
                Text("J", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (isDonald) Alignment.Start else Alignment.End
        ) {
            // Message bubble content
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isDonald) 4.dp else 16.dp,
                            bottomEnd = if (isDonald) 16.dp else 4.dp
                        )
                    )
                    .background(
                        if (isDonald) Color(0xFF1F2232) else Color(0xFF6366F1)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message.text,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 19.sp
                )
            }
        }

        if (!isDonald) {
            Spacer(modifier = Modifier.width(8.dp))
            // User Avatar
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(0xFF10B981), CircleShape)
                    .align(Alignment.Top),
                contentAlignment = Alignment.Center
            ) {
                Text("Y", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}
