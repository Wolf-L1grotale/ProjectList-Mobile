package com.projectlist.mobile.data

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class ApiException(
    message: String,
    val status: Int = 0,
    val code: String? = null,
    val retryAfterSec: Int? = null,
) : IOException(message)

fun friendlyError(t: Throwable): String = when (t) {
    is ApiException -> t.message ?: "Ошибка сервера"
    is UnknownHostException -> "Сервер не найден. Проверьте адрес и подключение к сети."
    is ConnectException -> "Не удалось подключиться к серверу. Убедитесь, что ProjectList запущен и доступен по сети."
    is SocketTimeoutException -> "Сервер не ответил вовремя. Повторите попытку."
    is SSLException -> "Ошибка защищённого соединения."
    is IOException -> t.message?.takeIf { it.isNotBlank() } ?: "Ошибка сети"
    else -> t.message?.takeIf { it.isNotBlank() } ?: "Неизвестная ошибка"
}
