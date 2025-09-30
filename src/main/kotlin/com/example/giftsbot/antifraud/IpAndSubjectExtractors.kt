package com.example.giftsbot.antifraud

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.util.AttributeKey

private val subjectIdAttribute = AttributeKey<Long>("subject.id")

fun extractClientIp(
    call: ApplicationCall,
    trustProxy: Boolean,
): String {
    if (trustProxy) {
        val forwardedHeader = call.request.header("X-Forwarded-For")
        val forwardedIp =
            forwardedHeader
                ?.split(',')
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        if (forwardedIp != null) {
            return forwardedIp
        }
    }
    return call.request.origin.remoteHost
}

fun extractSubjectId(call: ApplicationCall): Long? {
    if (call.attributes.contains(subjectIdAttribute)) {
        return call.attributes[subjectIdAttribute]
    }
    val headerValue = call.request.header("X-Subject-Id")?.trim()
    return headerValue?.takeIf { it.isNotEmpty() }?.toLongOrNull()
}
