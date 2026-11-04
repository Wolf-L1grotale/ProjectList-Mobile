package com.projectlist.mobile.data

import java.net.IDN

/**
 * Разбор адреса компьютера с ProjectList.
 *
 * Пользователю достаточно ввести IP или имя хоста. Протокол HTTP и порт 3000
 * подставляются автоматически. Если в адресе явно указаны `https://` или
 * другой порт, они имеют приоритет над значениями по умолчанию.
 */
object ServerAddress {

    const val DEFAULT_SCHEME = "http"
    const val DEFAULT_PORT = 3000

    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")

    data class Address(
        val scheme: String,
        val host: String,
        val port: Int,
        val basePath: String = "",
    ) {
        val origin: String
            get() = buildString {
                append(scheme)
                append("://")
                if (host.contains(':')) {
                    append('[')
                    append(host)
                    append(']')
                } else {
                    append(host)
                }
                append(':')
                append(port)
                if (basePath.isNotEmpty()) {
                    append(basePath)
                }
            }

        override fun toString(): String = origin
    }

    fun parse(
        input: String,
        defaultScheme: String = DEFAULT_SCHEME,
        defaultPort: Int = DEFAULT_PORT,
    ): Address {
        var value = input.trim()
        if (value.isEmpty()) {
            throw IllegalArgumentException("Укажите IP-адрес или URL компьютера")
        }

        var scheme = normalizeScheme(defaultScheme)
        val match = SCHEME.find(value)
        if (match != null) {
            scheme = normalizeScheme(match.groupValues[1])
            value = value.substring(match.range.last + 1)
        } else if (value.contains("://")) {
            throw IllegalArgumentException("Поддерживаются только адреса http:// и https://")
        }

        // Отбрасываем query/fragment: они не нужны для базового адреса API.
        value = value.substringBefore('#').substringBefore('?')

        // Если случайно вставили учётные данные (user:pass@host) — берём хост.
        val at = value.lastIndexOf('@')
        if (at >= 0) {
            value = value.substring(at + 1)
        }

        val slash = value.indexOf('/')
        var authority = if (slash >= 0) value.substring(0, slash) else value
        val rawPath = if (slash >= 0) value.substring(slash) else ""

        authority = authority.trim()
        if (authority.isEmpty()) {
            throw IllegalArgumentException("Укажите IP-адрес или URL компьютера")
        }

        var host: String
        var port = defaultPort

        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) {
                throw IllegalArgumentException("Некорректный IPv6-адрес: не закрыта квадратная скобка")
            }
            host = authority.substring(1, end)
            val rest = authority.substring(end + 1)
            if (rest.isNotEmpty()) {
                if (!rest.startsWith(":")) {
                    throw IllegalArgumentException("Некорректный адрес: $authority")
                }
                port = parsePort(rest.substring(1), defaultPort)
            }
        } else {
            val colons = authority.count { it == ':' }
            if (colons > 1) {
                // IPv6 без скобок. Порт в таком виде не отделяем.
                host = authority
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon >= 0) {
                    host = authority.substring(0, colon)
                    port = parsePort(authority.substring(colon + 1), defaultPort)
                } else {
                    host = authority
                }
            }
        }

        host = host.trim().removeSurrounding("[", "]")
        if (host.isEmpty()) {
            throw IllegalArgumentException("Укажите IP-адрес или URL компьютера")
        }
        if (host.any { it.isWhitespace() } || host.contains('/') || host.contains('\\')) {
            throw IllegalArgumentException("Некорректный адрес компьютера: $host")
        }
        if (host.any { it == '?' || it == '#' }) {
            throw IllegalArgumentException("Некорректный адрес компьютера: $host")
        }

        val asciiHost = if (host.any { it.code > 127 }) {
            runCatching { IDN.toASCII(host) }.getOrElse {
                throw IllegalArgumentException("Некорректное имя компьютера: $host")
            }
        } else {
            host
        }

        return Address(
            scheme = scheme,
            host = asciiHost,
            port = port,
            basePath = normalizePath(rawPath),
        )
    }

    fun normalize(
        input: String,
        defaultScheme: String = DEFAULT_SCHEME,
        defaultPort: Int = DEFAULT_PORT,
    ): String = parse(input, defaultScheme, defaultPort).origin

    /**
     * Берёт хост и путь из введённого адреса, но протокол и порт устанавливает
     * из явно выбранных пользователем значений. Нужно, чтобы переключатели
     * HTTP/HTTPS и поля порта всегда были главнее текста адреса.
     */
    fun withOptions(input: String, scheme: String, port: Int): Address {
        val parsed = parse(input)
        val normalizedScheme = normalizeScheme(scheme)
        if (port !in 1..65535) {
            throw IllegalArgumentException("Порт должен быть в диапазоне 1–65535")
        }
        return parsed.copy(scheme = normalizedScheme, port = port)
    }

    private fun normalizeScheme(raw: String): String {
        val value = raw.trim().lowercase()
        return when (value) {
            "http", "https" -> value
            else -> throw IllegalArgumentException("Поддерживаются только протоколы HTTP и HTTPS")
        }
    }

    private fun parsePort(raw: String, default: Int): Int {
        val value = raw.trim()
        if (value.isEmpty()) return default
        val port = value.toIntOrNull()
            ?: throw IllegalArgumentException("Порт должен быть числом")
        if (port !in 1..65535) {
            throw IllegalArgumentException("Порт должен быть в диапазоне 1–65535")
        }
        return port
    }

    private fun normalizePath(raw: String): String {
        if (raw.isBlank()) return ""
        var path = raw.trim().replace(Regex("/+"), "/")
        if (!path.startsWith('/')) path = "/$path"
        path = path.trimEnd('/')
        // Клиент сам добавляет /api/... — убираем случайный суффикс /api,
        // чтобы не получить /api/api/....
        if (path.endsWith("/api")) {
            path = path.dropLast(4).trimEnd('/')
        }
        return if (path == "/") "" else path
    }
}
