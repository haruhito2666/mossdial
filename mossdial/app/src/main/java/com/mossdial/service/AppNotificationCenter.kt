package com.mossdial.service

import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The in-app notification centre.
 *
 * Android's notification shade is for things that matter while the app is closed; this is the
 * quieter twin for things that happen while it is open: the server changed phase, the tunnel came
 * up or went down. It keeps the most recent few events in memory, marks itself unread when one
 * arrives, and lets the bell icon on the server tab open the list.
 *
 * The centre is deliberately not persisted. A restart loses the history, which is the same
 * behaviour as the server tab's own counters: what matters is what is happening now, not an audit
 * log of last week.
 */
enum class NoticeKind {
    Server,
    Tunnel,
    Info
}

data class NoticeItem(
    val id: Long,
    val kind: NoticeKind,
    val title: String,
    val body: String,
    val atMillis: Long
)

object NotificationCenter {
    private const val CAPACITY = 50

    private val lock = Any()
    private val items = ArrayDeque<NoticeItem>(CAPACITY)
    private val nextId = AtomicLong(1)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var unreadCount: Int = 0

    fun snapshot(): List<NoticeItem> = synchronized(lock) { items.toList() }

    fun unreadCount(): Int = unreadCount

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun post(kind: NoticeKind, title: String, body: String) {
        val item = NoticeItem(
            id = nextId.getAndIncrement(),
            kind = kind,
            title = title,
            body = body,
            atMillis = System.currentTimeMillis()
        )
        synchronized(lock) {
            items.addFirst(item)
            while (items.size > CAPACITY) {
                items.removeLast()
            }
        }
        unreadCount += 1
        notifyListeners()
    }

    /** Clears the unread marker; called when the user opens the notification page. */
    fun markAllRead() {
        if (unreadCount == 0) return
        unreadCount = 0
        notifyListeners()
    }

    fun clear() {
        synchronized(lock) { items.clear() }
        unreadCount = 0
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { listener -> runCatching { listener() } }
    }
}
