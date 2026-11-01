package com.ddw.kuangren.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class OfflineQueue(context: Context) {

    private val file = File(context.filesDir, "offline_queue.json")
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }
    private val lock = Any()

    val capacity = 100

    fun all(): List<QueueItem> = synchronized(lock) { readLocked() }

    fun size(): Int = synchronized(lock) { readLocked().size }

    fun enqueue(item: QueueItem) {
        synchronized(lock) {
            val list = readLocked().toMutableList()
            if (list.size >= capacity) list.removeAt(0)
            list.add(item)
            writeLocked(list)
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            writeLocked(readLocked().filterNot { it.id == id })
        }
    }

    fun clear() {
        synchronized(lock) { writeLocked(emptyList()) }
    }

    private fun readLocked(): List<QueueItem> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(QueueItem.serializer()), file.readText())
        }.getOrDefault(emptyList())
    }

    private fun writeLocked(items: List<QueueItem>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(ListSerializer(QueueItem.serializer()), items))
        }
    }
}
