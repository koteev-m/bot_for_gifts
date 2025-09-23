package com.example.app.testutil

import com.example.app.telegram.UpdateSink
import com.example.app.telegram.dto.UpdateDto
import com.example.giftsbot.telegram.ChatDto
import com.example.giftsbot.telegram.MessageDto
import com.example.giftsbot.telegram.UserDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class RecordingSink : UpdateSink {
    private val queue = ConcurrentLinkedQueue<UpdateDto>()
    private val calls = AtomicInteger(0)

    override suspend fun enqueue(update: UpdateDto) {
        calls.incrementAndGet()
        queue.add(update)
    }

    fun enqueueCalls(): Int = calls.get()

    fun recordedUpdates(): List<UpdateDto> = queue.toList()

    fun drain(): List<UpdateDto> {
        val drained = mutableListOf<UpdateDto>()
        while (true) {
            val next = queue.poll() ?: break
            drained += next
        }
        return drained
    }

    fun reset() {
        queue.clear()
        calls.set(0)
    }

    fun isEmpty(): Boolean = queue.isEmpty()
}

object JsonSamples {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }

    private const val BASE_TIMESTAMP = 1_700_000_000L

    fun singleUpdate(id: Long): String = json.encodeToString(UpdateDto.serializer(), sampleUpdateDto(id))

    fun batch(vararg ids: Long): String {
        val updates = ids.map(::sampleUpdateDto)
        return json.encodeToString(ListSerializer(UpdateDto.serializer()), updates)
    }

    fun dto(id: Long): UpdateDto = sampleUpdateDto(id)

    fun dtos(vararg ids: Long): List<UpdateDto> = ids.map(::sampleUpdateDto)

    private fun sampleUpdateDto(id: Long): UpdateDto =
        UpdateDto(
            update_id = id,
            message =
                MessageDto(
                    message_id = id * 10,
                    date = BASE_TIMESTAMP + id,
                    chat =
                        ChatDto(
                            id = id * 100,
                            type = "private",
                            username = "test$id",
                        ),
                    from =
                        UserDto(
                            id = id * 1_000,
                            is_bot = false,
                            first_name = "Tester$id",
                            username = "tester$id",
                        ),
                    text = "update-$id",
                ),
        )
}
