package com.gohj99.galaxywatchhelper.ui

object Destinations {
    const val HOME = "home"
    const val NFC_MODULE = "nfc_module"
    const val ABOUT = "about"
    const val ADD_CARD = "add_card"
    const val CARD_DETAIL = "card_detail"
    const val CARD_DETAIL_ROUTE = "$CARD_DETAIL/{cardId}"

    fun cardDetail(cardId: String): String = "$CARD_DETAIL/$cardId"
}

enum class UiScreen {
    NfcModule,
    AddCard,
    About,
}
