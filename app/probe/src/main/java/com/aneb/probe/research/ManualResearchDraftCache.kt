package com.aneb.probe.research

import android.content.SharedPreferences

/** App-private unfinished form only. Never appears in record counts or exports as a record. */
class ManualResearchDraftCache(private val preferences: SharedPreferences) {
    fun load(): ManualResearchDraft? {
        val state = preferences.getString("draft", null) ?: return null
        return try { ManualResearchDraft.fromDraftState(state) }
        catch (_: Exception) { throw ResearchImportException("本地草稿无法读取；未覆盖。可返回，或明确丢弃后重新填写。") }
    }

    fun save(draft: ManualResearchDraft) {
        // apply updates process memory immediately; Android serializes the disk write.
        preferences.edit().putString("draft", draft.toDraftState()).apply()
    }

    fun clear() { preferences.edit().remove("draft").apply() }

    /** IO caller; failed validation or storage leaves the draft intact. */
    fun saveRecord(store: ResearchRecordStore): ResearchDocument {
        val draft = load() ?: throw ResearchImportException("没有可保存的手工草稿。")
        val saved = store.save(draft.toRecordBytes())
        clear()
        return saved
    }
}
