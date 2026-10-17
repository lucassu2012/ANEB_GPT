package com.aneb.probe.ui.research

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aneb.probe.research.ManualResearchMediaStore
import com.aneb.probe.research.ResearchAttempt
import com.aneb.probe.research.text
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Explicit per-attempt attachment, separate from research metrics and original JSON export. */
@Composable
internal fun ManualResearchMediaAttachment(
    sourceId: String,
    attempt: ResearchAttempt,
    store: ManualResearchMediaStore,
    playbackOwner: ManualResearchMediaPlaybackOwner,
    enabled: Boolean,
) {
    key(sourceId, attempt.attemptId) {
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val scope = rememberCoroutineScope()
        val target = sourceId to attempt.attemptId
        val evidence = attempt.raw["evidence"] as? JsonObject
        val declaredSha = evidence?.text("sha256")
        val hasSha = declaredSha?.matches(Regex("[0-9a-fA-F]{64}")) == true
        val declaredBytes = evidence?.get("bytes")?.takeUnless { it == JsonNull }?.toString()
        var choosingTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
        var selectedUri by remember { mutableStateOf<Uri?>(null) }
        var busy by remember { mutableStateOf(false) }
        var savedAvailable by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf("正在检查本机副本…") }
        var playbackRequest by remember { mutableStateOf<ManualResearchMediaPlaybackOwner.Request?>(null) }
        val selection = playbackOwner.selection?.takeIf { it.request.sourceId == sourceId && it.request.attemptId == attempt.attemptId }

        LaunchedEffect(store, sourceId, attempt.attemptId, hasSha) {
            if (!hasSha) return@LaunchedEffect
            busy = true
            try {
                withContext(Dispatchers.IO) { store.open(target.first, target.second) }
                savedAvailable = true
                notice = "已发现本机原片；点击回看时会重新读取并核对 SHA-256。"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                savedAvailable = false
                notice = "尚无可用的已核对本机原片；缺失或不匹配的副本不显示为成功。"
            } finally { busy = false }
        }
        DisposableEffect(playbackOwner) {
            onDispose { playbackRequest?.let(playbackOwner::release) }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val requested = choosingTarget
            choosingTarget = null
            playbackOwner.stop()
            if (uri == null) {
                selectedUri = null
                notice = "已取消选择；没有复制或保存原片。"
            } else if (requested != target) {
                selectedUri = null
                notice = "原文或尝试已切换；未关联所选原片，请重新选择。"
            } else {
                selectedUri = uri
                notice = "已选择文件；确认前不会读取、复制或保存内容。"
            }
        }

        HorizontalDivider()
        Text("本条人工记录 · 本机原片", style = MaterialTheme.typography.titleSmall)
        if (!hasSha) {
            Text("原记录未声明完整媒体 SHA-256，不能核对或关联原片；记录仍可阅读，不补造哈希。", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(notice, style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(enabled = enabled && !busy, onClick = {
                playbackOwner.stop()
                selectedUri = null
                choosingTarget = target
                picker.launch(arrayOf("video/mp4"))
            }) { Text("选择本条对应原始 MP4") }
            if (savedAvailable) Button(enabled = enabled && !busy, onClick = {
                val request = playbackOwner.request(target.first, target.second)
                playbackRequest = request
                busy = true
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) { store.open(target.first, target.second) }
                        val activated = playbackOwner.activate(request, file, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                        notice = if (activated) "本机文件重新核对匹配；这不证明观察真实性、完整覆盖或计时准确。"
                            else "播放目标已切换或页面已进入后台；未启动回看，请重新明确点击。"
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        playbackOwner.release(request)
                        savedAvailable = false
                        notice = manualMediaFailure(e)
                    } finally { busy = false }
                }
            }) { Text("重新核对并回看本机原片") }
            selection?.let { selected ->
                TextButton(onClick = { playbackOwner.release(selected.request) }) { Text("停止并收起回看") }
                key(selected.request) { ManualResearchPlayback(selected.file) }
            }
        }

        selectedUri?.takeIf { hasSha }?.let { uri ->
            AlertDialog(
                onDismissRequest = { if (!busy) { selectedUri = null; notice = "已取消确认；没有复制或保存原片。" } },
                title = { Text("确认仅在本机保存原片？") },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionContainer { Text("原文 SHA-256：$sourceId\n尝试：${attempt.attemptId}\n声明媒体 SHA-256：$declaredSha", style = MaterialTheme.typography.bodySmall) }
                        Text(if (declaredBytes == null) "原记录未声明字节数；按完整 SHA-256 匹配。" else "原记录声明 bytes：$declaredBytes；同时核对字节数。")
                        Text("确认后才读取所选文件，流式核对并复制到 App 私有目录，单件最多 64 MiB。不会按桌面路径找文件，不上传、不分享，也不修改原 JSON、分析或结论卡。")
                        Text("文件匹配仅是字节关联，不证明实际观察、完整内容覆盖或网络性能。播放器进度不用于计算研究指标。")
                    }
                },
                confirmButton = {
                    TextButton(enabled = enabled && !busy, onClick = {
                        selectedUri = null
                        busy = true
                        playbackOwner.stop()
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        store.save(target.first, target.second, input)
                                    } ?: throw IOException("Selected media unavailable")
                                }
                                savedAvailable = true
                                notice = "本条原片已复制并核对完整 SHA-256，可离线重开；未自动播放，原始记录未改。"
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { notice = manualMediaFailure(e) }
                            finally { busy = false }
                        }
                    }) { Text("确认复制到本机") }
                },
                dismissButton = { TextButton(enabled = !busy, onClick = {
                    selectedUri = null
                    notice = "已取消确认；没有复制或保存原片。"
                }) { Text("取消 · 不保存") } },
            )
        }
    }
}

private fun manualMediaFailure(error: Exception): String = when (error) {
    is SecurityException -> "所选 MP4 读取权限不可用；请重新选择并授权本次读取。没有显示旧录像。"
    is IllegalArgumentException -> "原片未能匹配：请核对完整 SHA-256、已声明字节数及 64 MiB 上限；记录和旧副本保留，没有显示旧录像。"
    is IOException -> "原片读取或本机保存失败；请核对文件可读性、读取权限及可用存储。记录和旧副本保留。"
    else -> "本机原片操作未完成；请重新选择对应 MP4，核对声明、权限、64 MiB 上限和存储。没有显示旧录像。"
}

@Composable
private fun ManualResearchPlayback(file: File) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var session by remember { mutableStateOf<EngineeringCapturePlaybackSession?>(null) }
    var notice by remember { mutableStateOf("正在准备本机原片；尚未报告首画面。") }
    DisposableEffect(lifecycle, file) {
        onDispose { session?.close(); session = null }
    }
    Text("明确点击后的本机回看；播放器首画面、结尾与研究完整性分别看待，不按播放进度重算指标。", style = MaterialTheme.typography.bodySmall)
    Text(notice, style = MaterialTheme.typography.bodySmall)
    AndroidView(modifier = Modifier.fillMaxWidth().height(320.dp), factory = { context ->
        VideoView(context).also { view ->
            session = EngineeringCapturePlaybackSession(view, file, lifecycle) { notice = it }
        }
    })
}
