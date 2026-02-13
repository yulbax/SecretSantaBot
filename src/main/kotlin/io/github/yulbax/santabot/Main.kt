package io.github.yulbax.santabot

import io.github.yulbax.santabot.bot.SantaBotManager
import io.github.yulbax.santabot.db.SantaDB
import io.github.yulbax.santabot.i18n.Strings
import io.github.yulbax.santabot.tui.ServerPanelTUI

fun main() {
    SantaDB.initialize()
    Strings.preloadAll()
    val santaBotManager = SantaBotManager()
    ServerPanelTUI(santaBotManager).run()
    kotlin.system.exitProcess(0)
}
