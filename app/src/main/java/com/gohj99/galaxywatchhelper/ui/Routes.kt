package com.gohj99.galaxywatchhelper.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation.NavController

object Destinations {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val FRAMEWORK_INSTALLER = "framework_installer"
    const val WALLET_REDIRECTION = "wallet_redirection"
    const val WALLET_APP_SELECTION = "wallet_app_selection"
    const val WALLET_ACTIVITY_SELECTION = "wallet_activity_selection"
    const val WALLET_ACTIVITY_SELECTION_ROUTE = "$WALLET_ACTIVITY_SELECTION/{packageName}"
    const val NFC_MODULE = "nfc_module"
    const val ABOUT = "about"
    const val ADD_CARD = "add_card"
    const val CARD_DETAIL = "card_detail"
    const val CARD_DETAIL_ROUTE = "$CARD_DETAIL/{cardId}"

    fun cardDetail(cardId: String): String = "$CARD_DETAIL/$cardId"

    fun walletActivitySelection(packageName: String): String = "$WALLET_ACTIVITY_SELECTION/${android.net.Uri.encode(packageName)}"
}

/** NavController 注入点，由 [MainActivity] 在 NavHost 外提供。 */
val LocalNavController = compositionLocalOf<NavController?> { null }
