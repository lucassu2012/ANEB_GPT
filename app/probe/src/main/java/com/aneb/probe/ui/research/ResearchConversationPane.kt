package com.aneb.probe.ui.research

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
        Text("会话逐轮阅读", style = MaterialTheme.typography.titleMedium)
        Text("只按原标注分组，不是全文聊天回放。缺少有效会话或轮次的记录保留在“未分会话”；同轮次保留输入先后。", style = MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = selected == null, onClick = { onSelect(null) }, enabled = !busy, label = { Text("全部会话 / 未分会话") }) }
            items(conversations) { conversation ->
                FilterChip(selected = selected == conversation, onClick = { onSelect(conversation) }, enabled = !busy,
                    label = { Text("${conversation.appName ?: "App 未知"}\n${conversation.label}", modifier = Modifier.widthIn(max = 260.dp)) })
            }
        }
        Text("当前：${selected?.label ?: "全部会话 / 未分会话"} · ${conversations.turnsForSelection(selected).size} 条记录", style = MaterialTheme.typography.labelLarge)
        Text("下方逐轮分析来自所选副本；上方整批统计及原文导出不随会话筛选改变。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ResearchConversationTurnContent(turn: ResearchConversationTurn, analysis: ResearchAnalysis?) {
    Text(turn.label, style = MaterialTheme.typography.titleSmall)
    Text("本轮提示词（原记录）", style = MaterialTheme.typography.labelLarge)
    SelectionContainer { Text(turn.prompt, style = MaterialTheme.typography.bodyLarge) }
    turn.contextLines.forEach { line -> SelectionContainer { Text(line, style = MaterialTheme.typography.bodySmall) } }
    Text("以下为所选分析副本的本轮结果，未在本机重算或核验。", style = MaterialTheme.typography.labelLarge)
    turn.analysisLines(analysis).forEach { Text(it) }
}
