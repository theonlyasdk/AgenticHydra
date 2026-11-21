package app.agentichydra

import org.json.JSONObject

/** The standard reason phrase for an HTTP status, used when the server sends no usable detail. */
internal fun httpStatusReason(code: Int): String =
    when (code) {
        400 -> "Bad request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not found"
        408 -> "Request timeout"
        413 -> "Payload too large"
        429 -> "Too many requests"
        500 -> "Internal server error"
        502 -> "Bad gateway"
        503 -> "Service unavailable"
        504 -> "Gateway timeout"
        else -> if (code in 500..599) "Server error" else "Request failed"
    }

/** A plain-language explanation of why an HTTP request failed, so the user knows what went wrong. */
internal fun httpStatusExplanation(code: Int): String =
    when (code) {
        400 -> "The server couldn't understand the request."
        401 -> "Authentication is required, or the API key was rejected."
        403 -> "The server refused access; the key may lack permission for this resource."
        404 -> "The requested URL or model doesn't exist on the server."
        408 -> "The server timed out waiting for the request to complete."
        413 -> "The request is too large for the server to accept."
        429 -> "Too many requests were sent; the provider is rate limiting this key."
        500 -> "The server hit an internal error while handling the request."
        502 -> "A gateway or proxy got an invalid response from the upstream server, usually a temporary provider outage."
        503 -> "The service is temporarily unavailable, often due to overload or maintenance."
        504 -> "A gateway or proxy timed out waiting for the upstream server to respond."
        else ->
            if (code in 500..599) {
                "The server failed to handle the request."
            } else {
                "The server rejected the request."
            }
    }

/** Pulls the server's own error words out of a JSON or HTML/text error body, if any are usable. */
internal fun httpErrorDetail(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val trimmed = body.trim()
    val json = runCatching { JSONObject(trimmed) }.getOrNull()
    if (json != null) {
        val error = json.optJSONObject("error")
        val message =
            error?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optString("error").takeIf { it.isNotBlank() }
                ?: error?.optString("type")?.takeIf { it.isNotBlank() }
        if (message != null) return message.take(300)
    }
    val text =
        trimmed
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    return text.take(300).takeIf { it.isNotBlank() }
}

/** Builds a user-facing failure: the subject, the server's own words (or status), and why it happened. */
internal fun describeHttpFailure(
    subject: String,
    code: Int,
    body: String?,
): String {
    val headline = httpErrorDetail(body) ?: httpStatusReason(code)
    return "$subject (HTTP $code): $headline. ${httpStatusExplanation(code)}"
}