package com.lukas.jarvis.vm

import com.lukas.jarvis.data.PinRecord
import com.lukas.jarvis.moment.ActionIntent
import com.lukas.jarvis.moment.CanvasCard
import com.lukas.jarvis.moment.CardAction
import com.lukas.jarvis.moment.CardKind
import org.json.JSONArray
import org.json.JSONObject

/** A pinned card, as it is kept between launches and brought back. */
object PinCodec {

    fun record(card: CanvasCard, now: Long = System.currentTimeMillis()): PinRecord = PinRecord(
        kind = card.kind.name,
        tool = card.tool.orEmpty(),
        title = card.title,
        body = card.body,
        payload = JSONObject()
            .put("cardId", card.id)
            .put("lines", JSONArray(card.lines))
            .put("slots", JSONObject(card.slots as Map<*, *>))
            .apply { card.image?.let { put("image", it) } }
            .apply { card.chart?.let { put("chart", it.toJson()) } }
            .toString(),
        createdAt = now
    )

    fun cardId(record: PinRecord): String =
        runCatching { JSONObject(record.payload).optString("cardId") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "pin:${record.id}"

    /** The card again, with a way forward: ask about it, or let it go back to the shelf. */
    fun card(record: PinRecord): CanvasCard {
        val payload = runCatching { JSONObject(record.payload) }.getOrDefault(JSONObject())
        val id = cardId(record)
        val lines = payload.optJSONArray("lines")?.let { array -> (0 until array.length()).map { array.optString(it) } } ?: emptyList()
        val slots = payload.optJSONObject("slots")?.let { obj -> obj.keys().asSequence().associateWith { obj.optString(it) } } ?: emptyMap()
        return CanvasCard(
            id = id,
            kind = runCatching { CardKind.valueOf(record.kind) }.getOrDefault(CardKind.Answer),
            title = record.title,
            body = record.body,
            tool = record.tool.takeIf { it.isNotBlank() },
            lines = lines,
            slots = slots,
            image = payload.optString("image").takeIf { it.isNotBlank() },
            chart = com.lukas.jarvis.moment.Chart.fromJson(payload.optJSONObject("chart")),
            actions = listOf(
                CardAction("Ask about it", ActionIntent.Say("Tell me more about ${record.title.ifBlank { "that" }}"), primary = true),
                CardAction("Unpin", ActionIntent.Unpin(id))
            ),
            pinned = true,
            at = record.createdAt
        )
    }
}
