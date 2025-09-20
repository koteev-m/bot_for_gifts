package com.example.app.telegram

import com.example.app.telegram.dto.UpdateDto
import com.example.giftsbot.telegram.MessageDto as MessageDto

sealed interface IncomingUpdate {
    val updateId: Long

    data class MessageUpdate(
        override val updateId: Long,
        val message: MessageDto,
    ) : IncomingUpdate

    data class RawUpdate(
        override val updateId: Long,
        val dto: UpdateDto,
    ) : IncomingUpdate
}

/** Простая адаптация из UpdateDto в IncomingUpdate. Новые типы дополним позже. */
fun UpdateDto.toIncoming(): IncomingUpdate =
    when {
        message != null -> IncomingUpdate.MessageUpdate(update_id, message)
        else -> IncomingUpdate.RawUpdate(update_id, this)
    }
