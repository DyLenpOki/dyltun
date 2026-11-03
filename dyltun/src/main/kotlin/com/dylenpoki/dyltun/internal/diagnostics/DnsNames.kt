package com.dylenpoki.dyltun.internal.diagnostics

import com.dylenpoki.dyltun.internal.stack.MonotonicClock
import java.util.Locale

/** Bounded, passive UDP DNS correlation. Malformed/unrelated traffic never changes forwarding. */
internal class DnsNames(private val clock: MonotonicClock = MonotonicClock.SYSTEM) {
    private data class Question(val name: String, val type: Int)
    private data class Pending(val question: Question, val expires: Long)
    private data class Record(val owner: String, val type: Int, val ttl: Long, val value: String)
    private val pending = linkedMapOf<Pair<Long, Int>, Pending>()
    private val names = linkedMapOf<Pair<String, String>, Long>()

    @Synchronized fun query(connectionId: Long, bytes: ByteArray) = safely {
        expire()
        val input = Message(bytes)
        require(input.flags and 0xf800 == 0 && input.questions == 1)
        val question = input.question()
        require(question.type == 1 && question.name.isNotEmpty())
        pending[connectionId to input.id] = Pending(question, clock.now() + 10_000_000_000L)
        while (pending.size > 128) pending.remove(pending.keys.first())
    }

    @Synchronized fun response(connectionId: Long, bytes: ByteArray) = safely {
        expire()
        val input = Message(bytes)
        require(input.flags and 0xfa0f == 0x8000 && input.questions == 1 && input.answers <= 64)
        val question = input.question()
        val key = connectionId to input.id
        require(pending[key]?.question == question)
        val records = List(input.answers) { input.record() }
        pending.remove(key)
        var owner = question.name
        var ttl = 3600L
        val visited = mutableSetOf<String>()
        repeat(8) {
            require(visited.add(owner))
            val addresses = records.filter { it.owner == owner && it.type == 1 }
            if (addresses.isNotEmpty()) {
                for (record in addresses) {
                    val lifetime = minOf(ttl, record.ttl)
                    val mapping = record.value to question.name
                    if (lifetime > 0) names[mapping] = clock.now() + lifetime * 1_000_000_000L else names.remove(mapping)
                }
                while (names.size > 256) names.remove(names.keys.first())
                return@safely
            }
            val alias = records.firstOrNull { it.owner == owner && it.type == 5 } ?: return@safely
            ttl = minOf(ttl, alias.ttl)
            owner = alias.value
        }
    }

    @Synchronized fun candidates(ip: String): List<String> {
        expire()
        return names.keys.asSequence().filter { it.first == ip }.map { it.second }.take(4).toList()
    }

    @Synchronized fun clear() { pending.clear(); names.clear() }

    private fun expire() {
        val now = clock.now()
        pending.entries.removeAll { it.value.expires <= now }
        names.entries.removeAll { it.value <= now }
    }

    private inline fun safely(block: () -> Unit) { try { block() } catch (_: IllegalArgumentException) { } }

    private class Message(private val bytes: ByteArray) {
        var position = 12
        val id: Int
        val flags: Int
        val questions: Int
        val answers: Int
        init {
            require(bytes.size in 12..65535)
            id = word(0); flags = word(2); questions = word(4); answers = word(6)
        }

        fun question(): Question {
            val name = name()
            val type = readWord()
            require(readWord() == 1)
            return Question(name, type)
        }

        fun record(): Record {
            val owner = name()
            val type = readWord()
            val klass = readWord()
            val ttl = (readWord().toLong() shl 16) or readWord().toLong()
            val size = readWord()
            val end = position + size
            require(end <= bytes.size)
            val value = when {
                klass == 1 && type == 1 && size == 4 ->
                    (position until end).joinToString(".") { (bytes[it].toInt() and 255).toString() }
                klass == 1 && type == 5 -> name().also { require(position == end) }
                else -> ""
            }
            position = end
            return Record(owner, if (value.isEmpty()) 0 else type, ttl, value)
        }

        private fun name(): String {
            var cursor = position
            var end = -1
            val visited = mutableSetOf<Int>()
            val labels = mutableListOf<String>()
            var size = 0
            repeat(128) {
                require(cursor in bytes.indices && visited.add(cursor))
                val count = bytes[cursor++].toInt() and 255
                if (count == 0) {
                    position = if (end == -1) cursor else end
                    return labels.joinToString(".").lowercase(Locale.ROOT)
                }
                if (count and 0xc0 == 0xc0) {
                    require(cursor < bytes.size)
                    if (end == -1) end = cursor + 1
                    cursor = ((count and 63) shl 8) or (bytes[cursor].toInt() and 255)
                } else {
                    require(count in 1..63 && cursor + count <= bytes.size)
                    val label = bytes.copyOfRange(cursor, cursor + count).toString(Charsets.US_ASCII)
                    require(label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' })
                    labels += label
                    size += count + 1
                    require(size <= 254)
                    cursor += count
                }
            }
            throw IllegalArgumentException("DNS name exceeded traversal limit")
        }

        private fun readWord(): Int = word(position).also { position += 2 }
        private fun word(offset: Int): Int {
            require(offset >= 0 && offset + 2 <= bytes.size)
            return ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
        }
    }
}
