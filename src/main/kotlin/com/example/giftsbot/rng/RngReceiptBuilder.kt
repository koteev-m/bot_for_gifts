package com.example.giftsbot.rng

private const val ROLL_HEX_PREVIEW_LENGTH = 12

fun buildUserReceiptText(
    receipt: RngReceipt,
    resultItemId: String?,
): String {
    val rollHexPreview = receipt.rollHex.take(ROLL_HEX_PREVIEW_LENGTH)
    val rollHexShort =
        if (receipt.rollHex.length > rollHexPreview.length) {
            "$rollHexPreview…"
        } else {
            rollHexPreview
        }

    return buildString {
        appendLine("serverSeedHash: ${receipt.serverSeedHash}")
        appendLine("clientSeed=\"${receipt.clientSeed}\"")
        appendLine("rollHex: $rollHexShort")
        appendLine("ppm: ${receipt.ppm}")
        appendLine("date: ${receipt.date}")
        append("resultItemId: ${resultItemId ?: "-"}")
    }
}

data class RngUserProof(
    val receipt: RngReceipt,
    val resultItemId: String?,
    val verifyUrl: String? = null,
)
