package com.example.app.telegram

import com.example.app.telegram.dto.UpdateDto

fun interface UpdateSink {
    suspend fun enqueue(update: UpdateDto)
}
