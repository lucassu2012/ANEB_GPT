package com.aneb.probe.ui.research

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aneb.probe.research.*

/** Display existing annotations only. No chat replay or inferred answer/conclusion. */
@Composable
fun ResearchConversationPane(
    conversations: List<ResearchConversation>,
    selected: ResearchConversation?,
    onSelect: (ResearchConversation?) -> Unit,
    busy: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = selected == null, onClick = { onSelect(null) }, enabled = !busy, label = { Text("全部会话 / 未分会话") }) }
            items(conversations) { conversation ->
                FilterChip(selected = selected == conversation, onClick = { onSelect(conversation) }, enabled = !busy,
                    label = { Text("${conversation.appName ?: "App 未知"}\n${conversation.label}", modifier = Modifier.widthIn(max = 260.dp)) })
            }
        }
    }
}

@Composable
fun ResearchConversationTurnContent(turn: ResearchConversationTurn, analysis: ResearchAnalysis?) {
    var contextExpanded by remember(turn.sourceId, turn.attempt.attemptId) { mutableStateOf(false) }
    Text(turn.label, style = MaterialTheme.typography.titleSmall)
    Text("本轮结果 · 原分析，未重算或核验", style = MaterialTheme.typography.labelLarge)
    turn.analysisLines(analysis).forEach { Text(it) }
    TextButton(onClick = { contextExpanded = !contextExpanded }) {
        Text(if (contextExpanded) "收起提示词与上下文" else "展开提示词与上下文")
    }
    if (contextExpanded) {
        Text("本轮提示词（原记录）", style = MaterialTheme.typography.labelLarge)
        SelectionContainer { Text(turn.prompt, style = MaterialTheme.typography.bodyLarge) }
        turn.contextLines.forEach { line -> SelectionContainer { Text(line, style = MaterialTheme.typography.bodySmall) } }
    }
}
