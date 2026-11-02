package com.dylenpoki.dyltun

import java.util.Collections

class HttpHeaders {
    private val fields = mutableListOf<Pair<String, String>>()

    operator fun get(name: String): String? = fields.firstOrNull { it.first.equals(name, true) }?.second

    operator fun set(name: String, value: String) {
        validate(name, value)
        val first = fields.indexOfFirst { it.first.equals(name, true) }
        fields.removeAll { it.first.equals(name, true) }
        if (first < 0) fields += name to value else fields.add(first, name to value)
    }

    fun add(name: String, value: String) {
        validate(name, value)
        fields += name to value
    }

    fun getAll(name: String): List<String> = Collections.unmodifiableList(
        fields.filter { it.first.equals(name, true) }.map { it.second }
    )

    fun remove(name: String) {
        fields.removeAll { it.first.equals(name, true) }
    }

    fun entries(): List<Pair<String, String>> = Collections.unmodifiableList(ArrayList(fields))
    fun copy(): HttpHeaders = of(*fields.toTypedArray())

    companion object {
        fun of(vararg entries: Pair<String, String>): HttpHeaders = HttpHeaders().apply {
            entries.forEach { (name, value) -> add(name, value) }
        }

        private fun validate(name: String, value: String) {
            require(isHttpToken(name)) { "Invalid HTTP header name" }
            require(value.all { it == '\t' || it.code in 32..126 || it.code in 128..255 }) {
                "Invalid HTTP header value"
            }
        }
    }
}

internal fun isHttpToken(value: String): Boolean = value.isNotEmpty() && value.all {
    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~"
}
