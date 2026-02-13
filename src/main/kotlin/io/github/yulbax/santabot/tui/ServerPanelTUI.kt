package io.github.yulbax.santabot.tui

import io.github.yulbax.santabot.bot.BotStartResult
import io.github.yulbax.santabot.bot.SantaBotManager
import io.github.yulbax.santabot.db.SantaDB
import io.github.yulbax.santabot.i18n.StringKey
import io.github.yulbax.santabot.i18n.Strings
import io.github.yulbax.santabot.model.Language
import kotlinx.coroutines.*
import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.InfoCmp
import org.jline.utils.NonBlockingReader
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset

class ServerPanelTUI(private val santaBotManager: SantaBotManager) {

    private sealed class TuiKey {
        data object Timeout : TuiKey()
        data object Up : TuiKey()
        data object Down : TuiKey()
        data object Left : TuiKey()
        data object Right : TuiKey()
        data object Enter : TuiKey()
        data object Escape : TuiKey()
        data object Backspace : TuiKey()
        data class Printable(val char: Char) : TuiKey()
        data object Unknown : TuiKey()
    }

    private enum class MenuItem {
        START_RESTART, STOP, SETTINGS, REFRESH, EXIT
    }

    private enum class SettingsField {
        TOKEN, USERNAME, LANGUAGE, SAVE, CANCEL
    }

    private lateinit var terminal: Terminal
    private lateinit var reader: NonBlockingReader
    private var originalAttributes: Attributes? = null
    private val isRunning = AtomicBoolean(true)
    private var updateJob: Job? = null
    private val logLines = ArrayDeque<String>()
    private val maxLogLines = 1000
    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null
    private val renderLock = Any()

    private var selectedMenuItem = MenuItem.entries.first()
    private val menuItems = mutableListOf<String>()
    private var currentDialog: Dialog? = null

    private val colorReset = "\u001B[0m"
    private val colorBold = "\u001B[1m"
    private val colorGreen = "\u001B[92m"
    private val colorRed = "\u001B[91m"
    private val colorCyan = "\u001B[96m"
    private val colorYellow = "\u001B[93m"
    private val colorMagenta = "\u001B[95m"
    private val colorBlue = "\u001B[94m"
    private val colorWhite = "\u001B[97m"
    private val colorBlack = "\u001B[30m"
    private val colorBgWhite = "\u001B[107m"
    private val colorDim = "\u001B[2m"

    private sealed class Dialog {
        data class Message(val title: String, val message: String, val buttons: List<String>, var selectedButton: Int = 0) : Dialog()
        data class Settings(
            var token: String,
            var username: String,
            var selectedLanguageIndex: Int,
            var focusedField: SettingsField = SettingsField.TOKEN,
            var cursorPos: Int = 0
        ) : Dialog()
    }

    fun run() {
        terminal = TerminalBuilder.builder()
            .system(true)
            .jansi(true)
            .build()

        originalAttributes = terminal.enterRawMode()
        reader = terminal.reader()
        val shutdownHook = Thread({
            restoreTerminal()
        }, "terminal-restore-hook")
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        try {
            terminal.puts(InfoCmp.Capability.cursor_invisible)

            setupLogRedirection()
            updateMenuItems()
            startAutoUpdate()
            render()

            while (isRunning.get()) {
                val key = readKey()
                handleInput(key)
                render()
            }
        } finally {
            updateJob?.cancel()
            restoreSystemStreams()
            restoreTerminal()
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (_: IllegalStateException) {}
        }
    }

    private fun restoreTerminal() {
        try {
            originalAttributes?.let { terminal.attributes = it }
            terminal.puts(InfoCmp.Capability.cursor_normal)
            terminal.writer().println()
            terminal.flush()
            terminal.close()
        } catch (_: Exception) {}
    }

    private fun updateMenuItems() {
        menuItems.clear()
        menuItems.add(Strings.get(StringKey.START_BOT_BUTTON, SantaDB.consoleLanguage))
        menuItems.add(Strings.get(StringKey.STOP_BOT_BUTTON, SantaDB.consoleLanguage))
        menuItems.add(Strings.get(StringKey.SETTINGS_BUTTON, SantaDB.consoleLanguage))
        menuItems.add(Strings.get(StringKey.REFRESH_STATUS_BUTTON, SantaDB.consoleLanguage))
        menuItems.add(Strings.get(StringKey.EXIT_BUTTON, SantaDB.consoleLanguage))
    }

    private fun readKey(): TuiKey {
        val c = reader.read(100)
        if (c == -2) return TuiKey.Timeout
        if (c == 27) {
            val c2 = reader.read(50)
            if (c2 == 91 || c2 == 79) {
                return when (reader.read(50)) {
                    65 -> TuiKey.Up
                    66 -> TuiKey.Down
                    67 -> TuiKey.Right
                    68 -> TuiKey.Left
                    else -> TuiKey.Escape
                }
            }
            return TuiKey.Escape
        }
        return when (c) {
            10, 13 -> TuiKey.Enter
            127, 8 -> TuiKey.Backspace
            in 32..126 -> TuiKey.Printable(c.toChar())
            else -> TuiKey.Unknown
        }
    }

    private fun handleInput(key: TuiKey) {
        if (currentDialog != null) {
            handleDialogInput(key)
            return
        }

        val menuEntries = MenuItem.entries
        when (key) {
            TuiKey.Up -> {
                val idx = selectedMenuItem.ordinal
                if (idx > 0) selectedMenuItem = menuEntries[idx - 1]
            }
            TuiKey.Down -> {
                val idx = selectedMenuItem.ordinal
                if (idx < menuEntries.size - 1) selectedMenuItem = menuEntries[idx + 1]
            }
            TuiKey.Enter -> executeMenuItem(selectedMenuItem)
            is TuiKey.Printable -> if (key.char == 'q' || key.char == 'Q') exitApplication()
            else -> {}
        }
    }

    private fun handleDialogInput(key: TuiKey) {
        when (val dialog = currentDialog) {
            is Dialog.Message -> {
                when (key) {
                    TuiKey.Left -> {
                        if (dialog.selectedButton > 0) dialog.selectedButton--
                    }
                    TuiKey.Right -> {
                        if (dialog.selectedButton < dialog.buttons.size - 1) dialog.selectedButton++
                    }
                    TuiKey.Enter -> {
                        val selected = dialog.buttons[dialog.selectedButton]
                        currentDialog = null
                        onDialogResult(dialog, selected)
                    }
                    TuiKey.Escape -> {
                        currentDialog = null
                    }
                    else -> {}
                }
            }
            is Dialog.Settings -> {
                val fields = SettingsField.entries
                when (key) {
                    TuiKey.Up -> {
                        val idx = dialog.focusedField.ordinal
                        if (idx > 0) {
                            dialog.focusedField = fields[idx - 1]
                            dialog.cursorPos = when (dialog.focusedField) {
                                SettingsField.TOKEN -> dialog.token.length
                                SettingsField.USERNAME -> dialog.username.length
                                else -> 0
                            }
                        }
                    }
                    TuiKey.Down -> {
                        val idx = dialog.focusedField.ordinal
                        if (idx < fields.size - 1) {
                            dialog.focusedField = fields[idx + 1]
                            dialog.cursorPos = when (dialog.focusedField) {
                                SettingsField.TOKEN -> dialog.token.length
                                SettingsField.USERNAME -> dialog.username.length
                                else -> 0
                            }
                        }
                    }
                    TuiKey.Left -> {
                        when (dialog.focusedField) {
                            SettingsField.TOKEN, SettingsField.USERNAME -> if (dialog.cursorPos > 0) dialog.cursorPos--
                            SettingsField.LANGUAGE -> if (dialog.selectedLanguageIndex > 0) dialog.selectedLanguageIndex--
                            SettingsField.SAVE -> dialog.focusedField = SettingsField.CANCEL
                            SettingsField.CANCEL -> dialog.focusedField = SettingsField.SAVE
                        }
                    }
                    TuiKey.Right -> {
                        when (dialog.focusedField) {
                            SettingsField.TOKEN -> if (dialog.cursorPos < dialog.token.length) dialog.cursorPos++
                            SettingsField.USERNAME -> if (dialog.cursorPos < dialog.username.length) dialog.cursorPos++
                            SettingsField.LANGUAGE -> if (dialog.selectedLanguageIndex < Language.entries.size - 1) dialog.selectedLanguageIndex++
                            SettingsField.SAVE -> dialog.focusedField = SettingsField.CANCEL
                            SettingsField.CANCEL -> dialog.focusedField = SettingsField.SAVE
                        }
                    }
                    TuiKey.Enter -> {
                        when (dialog.focusedField) {
                            SettingsField.SAVE -> {
                                saveSettings(dialog)
                                currentDialog = null
                            }
                            SettingsField.CANCEL -> {
                                currentDialog = null
                            }
                            else -> {
                                val idx = dialog.focusedField.ordinal
                                if (idx < fields.size - 1) {
                                    dialog.focusedField = fields[idx + 1]
                                    dialog.cursorPos = when (dialog.focusedField) {
                                        SettingsField.TOKEN -> dialog.token.length
                                        SettingsField.USERNAME -> dialog.username.length
                                        else -> 0
                                    }
                                }
                            }
                        }
                    }
                    TuiKey.Escape -> {
                        currentDialog = null
                    }
                    TuiKey.Backspace -> {
                        when (dialog.focusedField) {
                            SettingsField.TOKEN -> if (dialog.cursorPos > 0) {
                                dialog.token = dialog.token.removeRange(dialog.cursorPos - 1, dialog.cursorPos)
                                dialog.cursorPos--
                            }
                            SettingsField.USERNAME -> if (dialog.cursorPos > 0) {
                                dialog.username = dialog.username.removeRange(dialog.cursorPos - 1, dialog.cursorPos)
                                dialog.cursorPos--
                            }
                            else -> {}
                        }
                    }
                    is TuiKey.Printable -> {
                        when (dialog.focusedField) {
                            SettingsField.TOKEN -> {
                                dialog.token = dialog.token.substring(0, dialog.cursorPos) + key.char + dialog.token.substring(dialog.cursorPos)
                                dialog.cursorPos++
                            }
                            SettingsField.USERNAME -> {
                                dialog.username = dialog.username.substring(0, dialog.cursorPos) + key.char + dialog.username.substring(dialog.cursorPos)
                                dialog.cursorPos++
                            }
                            else -> {}
                        }
                    }
                    else -> {}
                }
            }
            null -> {}
        }
    }

    private fun onDialogResult(dialog: Dialog.Message, result: String) {
        when (dialog.title) {
            Strings.get(StringKey.EXIT_TITLE, SantaDB.consoleLanguage) -> {
                if (result == Strings.get(StringKey.YES_BUTTON, SantaDB.consoleLanguage)) {
                    isRunning.set(false)
                    updateJob?.cancel()
                    santaBotManager.stopBot()
                    SantaDB.saveState()
                }
            }
            Strings.get(StringKey.RESTART_TITLE, SantaDB.consoleLanguage) -> {
                if (result == Strings.get(StringKey.YES_BUTTON, SantaDB.consoleLanguage)) {
                    restartBot()
                }
            }
        }
    }

    private fun saveSettings(dialog: Dialog.Settings) {
        val newToken = dialog.token.trim()
        val newUsername = dialog.username.trim()
        val newConsoleLanguage = Language.entries[dialog.selectedLanguageIndex]

        if (newToken.isNotBlank()) {
            SantaDB.botToken = newToken
        }
        if (newUsername.isNotBlank()) {
            SantaDB.botUsername = newUsername
        }
        SantaDB.consoleLanguage = newConsoleLanguage
        updateMenuItems()

        if (santaBotManager.isBotRunning) {
            showMessageDialog(
                Strings.get(StringKey.RESTART_TITLE, SantaDB.consoleLanguage),
                Strings.get(StringKey.SETTINGS_SAVED_RESTART_PROMPT, SantaDB.consoleLanguage),
                listOf(Strings.get(StringKey.YES_BUTTON, SantaDB.consoleLanguage), Strings.get(StringKey.NO_BUTTON, SantaDB.consoleLanguage))
            )
        } else {
            showMessageDialog(
                Strings.get(StringKey.SAVED_TITLE, SantaDB.consoleLanguage),
                Strings.get(StringKey.SETTINGS_SAVED_SUCCESS, SantaDB.consoleLanguage),
                listOf("OK")
            )
        }
    }

    private fun executeMenuItem(item: MenuItem) {
        when (item) {
            MenuItem.START_RESTART -> {
                if (santaBotManager.isBotRunning) {
                    restartBot()
                } else {
                    startBot()
                }
            }
            MenuItem.STOP -> stopBot()
            MenuItem.SETTINGS -> openSettings()
            MenuItem.REFRESH -> {}
            MenuItem.EXIT -> exitApplication()
        }
    }

    private fun showMessageDialog(title: String, message: String, buttons: List<String>) {
        currentDialog = Dialog.Message(title, message, buttons)
    }

    private fun startBot() {
        val token = SantaDB.botToken
        val username = SantaDB.botUsername

        if (token.isNullOrBlank() || username.isNullOrBlank()) {
            showMessageDialog(
                Strings.get(StringKey.ERROR_TITLE, SantaDB.consoleLanguage),
                Strings.get(StringKey.TOKEN_USERNAME_NOT_CONFIGURED, SantaDB.consoleLanguage),
                listOf("OK")
            )
            return
        }

        when (val result = santaBotManager.startBot(token, username)) {
            is BotStartResult.Success -> showMessageDialog(
                Strings.get(StringKey.SUCCESS_TITLE, SantaDB.consoleLanguage),
                Strings.get(StringKey.BOT_STARTED_SUCCESS, SantaDB.consoleLanguage),
                listOf("OK")
            )
            is BotStartResult.AlreadyRunning -> showMessageDialog(
                Strings.get(StringKey.INFO_TITLE, SantaDB.consoleLanguage),
                Strings.get(StringKey.BOT_ALREADY_RUNNING, SantaDB.consoleLanguage),
                listOf("OK")
            )
            is BotStartResult.EmptyCredentials -> showMessageDialog(
                Strings.get(StringKey.ERROR_TITLE, SantaDB.consoleLanguage),
                Strings.get(StringKey.TOKEN_USERNAME_NOT_CONFIGURED, SantaDB.consoleLanguage),
                listOf("OK")
            )
            is BotStartResult.Error -> showMessageDialog(
                Strings.get(StringKey.ERROR_TITLE, SantaDB.consoleLanguage),
                Strings.get(StringKey.BOT_START_ERROR, SantaDB.consoleLanguage).format(result.message),
                listOf("OK")
            )
        }
    }

    private fun stopBot() {
        if (!santaBotManager.isBotRunning) {
            showMessageDialog(
                Strings.get(StringKey.INFO_TITLE, SantaDB.consoleLanguage),
                Strings.get(StringKey.BOT_ALREADY_STOPPED, SantaDB.consoleLanguage),
                listOf("OK")
            )
            return
        }

        santaBotManager.stopBot()
        showMessageDialog(
            Strings.get(StringKey.BOT_STOPPED_TITLE, SantaDB.consoleLanguage),
            Strings.get(StringKey.BOT_STOPPED_SUCCESS, SantaDB.consoleLanguage),
            listOf("OK")
        )
    }

    private fun restartBot() {
        santaBotManager.stopBot()

        val token = SantaDB.botToken
        val username = SantaDB.botUsername

        if (token != null && username != null) {
            when (val result = santaBotManager.startBot(token, username)) {
                is BotStartResult.Success -> showMessageDialog(
                    Strings.get(StringKey.RESTART_TITLE, SantaDB.consoleLanguage),
                    Strings.get(StringKey.BOT_RESTARTED_SUCCESS, SantaDB.consoleLanguage),
                    listOf("OK")
                )
                is BotStartResult.Error -> showMessageDialog(
                    Strings.get(StringKey.ERROR_TITLE, SantaDB.consoleLanguage),
                    Strings.get(StringKey.BOT_START_ERROR, SantaDB.consoleLanguage).format(result.message),
                    listOf("OK")
                )
                else -> showMessageDialog(
                    Strings.get(StringKey.ERROR_TITLE, SantaDB.consoleLanguage),
                    Strings.get(StringKey.BOT_START_ERROR, SantaDB.consoleLanguage).format("Unexpected state"),
                    listOf("OK")
                )
            }
        }
    }

    private fun openSettings() {
        val currentLangIndex = Language.entries.indexOf(SantaDB.consoleLanguage)
        currentDialog = Dialog.Settings(
            token = SantaDB.botToken ?: "",
            username = SantaDB.botUsername ?: "",
            selectedLanguageIndex = if (currentLangIndex >= 0) currentLangIndex else 0,
            cursorPos = (SantaDB.botToken ?: "").length
        )
    }

    private fun exitApplication() {
        showMessageDialog(
            Strings.get(StringKey.EXIT_TITLE, SantaDB.consoleLanguage),
            Strings.get(StringKey.EXIT_CONFIRMATION, SantaDB.consoleLanguage),
            listOf(Strings.get(StringKey.YES_BUTTON, SantaDB.consoleLanguage), Strings.get(StringKey.NO_BUTTON, SantaDB.consoleLanguage))
        )
    }

    private fun startAutoUpdate() {
        updateJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRunning.get()) {
                delay(2000)
                if (isRunning.get() && currentDialog == null) {
                    render()
                }
            }
        }
    }

    private fun render() = synchronized(renderLock) {
        val width = terminal.width.coerceAtLeast(80)
        val height = terminal.height.coerceAtLeast(24)

        val sb = StringBuilder()

        sb.append("\u001B[2J\u001B[H")

        val title = "=== SECRET SANTA BOT ==="
        val titleLine = centerText(title, width)
        sb.append(colorGreen).append(colorBold).append(titleLine).append(colorReset).append("\n")
        sb.append(drawHorizontalLine(width)).append("\n")

        val stats = SantaDB.getStats()
        val runtime = Runtime.getRuntime()
        val threadBean = ManagementFactory.getThreadMXBean()

        val totalMemory = runtime.totalMemory() / (1024 * 1024)
        val freeMemory = runtime.freeMemory() / (1024 * 1024)
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        val usedMemory = totalMemory - freeMemory
        val memoryUsagePercent = (usedMemory.toDouble() / maxMemory * 100).toInt()

        val memoryColor = when {
            memoryUsagePercent > 80 -> colorRed
            memoryUsagePercent > 50 -> colorYellow
            else -> colorGreen
        }

        val statusColor = if (santaBotManager.isBotRunning) colorGreen else colorRed
        val statusText = if (santaBotManager.isBotRunning)
            Strings.get(StringKey.BOT_STATUS_RUNNING, SantaDB.consoleLanguage)
        else
            Strings.get(StringKey.BOT_STATUS_STOPPED, SantaDB.consoleLanguage)

        val halfWidth = (width - 3) / 2

        val statusLabel = Strings.get(StringKey.BOT_STATUS_LABEL, SantaDB.consoleLanguage).trim()
        val playersLabel = Strings.get(StringKey.TOTAL_PLAYERS_LABEL, SantaDB.consoleLanguage).trim()
        val activeGamesLabel = Strings.get(StringKey.ACTIVE_GAMES_LABEL, SantaDB.consoleLanguage).trim()
        val recruitingLabel = Strings.get(StringKey.RECRUITING_GAMES_LABEL, SantaDB.consoleLanguage).trim()
        val finishedLabel = Strings.get(StringKey.FINISHED_GAMES_LABEL, SantaDB.consoleLanguage).trim()

        val threadsLabel = Strings.get(StringKey.ACTIVE_THREADS_LABEL, SantaDB.consoleLanguage).trim()
        val totalMemLabel = Strings.get(StringKey.TOTAL_MEMORY_LABEL, SantaDB.consoleLanguage).trim()
        val usedMemLabel = Strings.get(StringKey.USED_MEMORY_LABEL, SantaDB.consoleLanguage).trim()
        val freeMemLabel = Strings.get(StringKey.FREE_MEMORY_LABEL, SantaDB.consoleLanguage).trim()
        val maxMemLabel = Strings.get(StringKey.MAX_MEMORY_LABEL, SantaDB.consoleLanguage).trim()

        val leftLabels = listOf(statusLabel, playersLabel, activeGamesLabel, recruitingLabel, finishedLabel)
        val rightLabels = listOf(threadsLabel, totalMemLabel, usedMemLabel, freeMemLabel, maxMemLabel)
        val maxLeftLabelLen = leftLabels.maxOf { it.length }
        val maxRightLabelLen = rightLabels.maxOf { it.length }

        fun formatLine(label: String, value: String, labelColor: String, valueColor: String, maxLabelLen: Int): String {
            val paddedLabel = label.padEnd(maxLabelLen)
            return "$labelColor$paddedLabel$colorReset $valueColor$value$colorReset"
        }

        val leftLines = listOf(
            formatLine(statusLabel, statusText, colorCyan, statusColor, maxLeftLabelLen),
            formatLine(playersLabel, stats.playerCount.toString(), colorCyan, colorWhite, maxLeftLabelLen),
            formatLine(activeGamesLabel, stats.activeGames.toString(), colorCyan, colorGreen, maxLeftLabelLen),
            formatLine(recruitingLabel, stats.recruitingGames.toString(), colorCyan, colorBlue, maxLeftLabelLen),
            formatLine(finishedLabel, stats.finishedGames.toString(), colorCyan, colorMagenta, maxLeftLabelLen)
        )

        val rightLines = listOf(
            formatLine(threadsLabel, threadBean.threadCount.toString(), colorYellow, colorWhite, maxRightLabelLen),
            formatLine(totalMemLabel, "$totalMemory MB", colorYellow, colorWhite, maxRightLabelLen),
            formatLine(usedMemLabel, "$usedMemory MB", colorYellow, memoryColor, maxRightLabelLen),
            formatLine(freeMemLabel, "$freeMemory MB", colorYellow, colorWhite, maxRightLabelLen),
            formatLine(maxMemLabel, "$maxMemory MB", colorYellow, colorWhite, maxRightLabelLen)
        )

        val leftTitle = Strings.get(StringKey.BOT_STATUS_BORDER_TITLE, SantaDB.consoleLanguage)
        val rightTitle = Strings.get(StringKey.SYSTEM_INFO_BORDER_TITLE, SantaDB.consoleLanguage)

        sb.append("┌").append("─".repeat(halfWidth - 2)).append("┐ ┌").append("─".repeat(halfWidth - 2)).append("┐\n")
        sb.append("│ ").append(colorBold).append(leftTitle.trim().padEnd(halfWidth - 4)).append(colorReset)
        sb.append(" │ │ ").append(colorBold).append(rightTitle.trim().padEnd(halfWidth - 4)).append(colorReset).append(" │\n")
        sb.append("├").append("─".repeat(halfWidth - 2)).append("┤ ├").append("─".repeat(halfWidth - 2)).append("┤\n")

        val maxLines = maxOf(leftLines.size, rightLines.size)
        for (i in 0 until maxLines) {
            val leftLine = leftLines.getOrNull(i) ?: ""
            val rightLine = rightLines.getOrNull(i) ?: ""
            sb.append("│ ").append(padVisual(leftLine, halfWidth - 4)).append(" │ │ ").append(padVisual(rightLine, halfWidth - 4)).append(" │\n")
        }

        sb.append("└").append("─".repeat(halfWidth - 2)).append("┘ └").append("─".repeat(halfWidth - 2)).append("┘\n")

        sb.append("\n")
        for ((index, item) in menuItems.withIndex()) {
            val isSelected = index == selectedMenuItem.ordinal
            if (isSelected) {
                sb.append(colorBgWhite).append(colorBlack).append(" > $item ").append(colorReset)
            } else {
                sb.append("   $item ")
            }
            sb.append("\n")
        }

        sb.append("\n").append(drawHorizontalLine(width)).append("\n")
        sb.append(colorDim).append("Logs:").append(colorReset).append("\n")

        val logHeight = height - 20 // Примерно оставляем место для логов
        val visibleLogs = synchronized(this) { logLines.takeLast(logHeight.coerceAtLeast(5)) }
        for (line in visibleLogs) {
            val displayLine = if (line.startsWith("[ERR]")) {
                "$colorRed$line$colorReset"
            } else {
                line
            }
            sb.append(displayLine.take(width)).append("\n")
        }

        sb.append("\n").append(colorDim)
        sb.append(Strings.get(StringKey.NAV_HINT_MAIN, SantaDB.consoleLanguage))
        sb.append(colorReset)

        if (currentDialog != null) {
            sb.append(renderDialog(width, height))
        }

        terminal.writer().print(sb.toString())
        terminal.writer().flush()
    }

    private fun renderDialog(screenWidth: Int, screenHeight: Int): String {
        val sb = StringBuilder()

        when (val dialog = currentDialog) {
            is Dialog.Message -> {
                val dialogWidth = (screenWidth * 0.6).toInt().coerceAtLeast(40)
                val contentWidth = dialogWidth - 4 // ширина содержимого между "│ " и " │"

                val messageLines = mutableListOf<String>()
                for (paragraph in dialog.message.split("\n")) {
                    if (paragraph.isEmpty()) {
                        messageLines.add("")
                    } else {
                        messageLines.addAll(wrapText(paragraph, contentWidth))
                    }
                }

                val dialogHeight = messageLines.size + 5

                val startX = (screenWidth - dialogWidth) / 2
                val startY = (screenHeight - dialogHeight) / 2

                sb.append("\u001B[${startY};${startX}H")

                sb.append("┌").append("─".repeat(dialogWidth - 2)).append("┐")
                sb.append("\u001B[${startY + 1};${startX}H")

                val titleContent = "$colorBold${dialog.title}$colorReset"
                sb.append("│ ").append(padVisual(titleContent, contentWidth)).append(" │")
                sb.append("\u001B[${startY + 2};${startX}H")
                sb.append("├").append("─".repeat(dialogWidth - 2)).append("┤")

                for ((idx, line) in messageLines.withIndex()) {
                    sb.append("\u001B[${startY + 3 + idx};${startX}H")
                    sb.append("│ ").append(line.padEnd(contentWidth)).append(" │")
                }

                val btnRowY = startY + 3 + messageLines.size
                sb.append("\u001B[${btnRowY};${startX}H")
                val buttonsStr = StringBuilder()
                for ((idx, btn) in dialog.buttons.withIndex()) {
                    if (idx == dialog.selectedButton) {
                        buttonsStr.append("$colorBgWhite$colorBlack [ $btn ] $colorReset")
                    } else {
                        buttonsStr.append(" [ $btn ] ")
                    }
                    if (idx < dialog.buttons.size - 1) buttonsStr.append(" ")
                }
                val buttonsContent = buttonsStr.toString()
                val buttonsVisualLen = stripAnsi(buttonsContent).length
                val leftPad = (contentWidth - buttonsVisualLen) / 2
                val rightPad = contentWidth - buttonsVisualLen - leftPad
                sb.append("│ ").append(" ".repeat(leftPad.coerceAtLeast(0)))
                sb.append(buttonsContent)
                sb.append(" ".repeat(rightPad.coerceAtLeast(0))).append(" │")

                sb.append("\u001B[${btnRowY + 1};${startX}H")
                sb.append("└").append("─".repeat(dialogWidth - 2)).append("┘")
            }
            is Dialog.Settings -> {
                val dialogWidth = 70
                val contentWidth = dialogWidth - 4
                val dialogHeight = 12

                val startX = (screenWidth - dialogWidth) / 2
                val startY = (screenHeight - dialogHeight) / 2

                val tokenLabel = Strings.get(StringKey.BOT_TOKEN_LABEL, SantaDB.consoleLanguage).trim()
                val usernameLabel = Strings.get(StringKey.BOT_USERNAME_LABEL, SantaDB.consoleLanguage).trim()
                val langLabel = Strings.get(StringKey.CONSOLE_LANGUAGE_LABEL, SantaDB.consoleLanguage).trim()
                val maxLabelLen = maxOf(tokenLabel.length, usernameLabel.length, langLabel.length)
                val fieldWidth = contentWidth - maxLabelLen - 3 // -3 для ": " и пробела

                sb.append("\u001B[${startY};${startX}H")
                sb.append("┌").append("─".repeat(dialogWidth - 2)).append("┐")

                sb.append("\u001B[${startY + 1};${startX}H")
                val settingsTitle = Strings.get(StringKey.SETTINGS_WINDOW_TITLE, SantaDB.consoleLanguage)
                val titleContent = "$colorBold$settingsTitle$colorReset"
                sb.append("│ ").append(padVisual(titleContent, contentWidth)).append(" │")

                sb.append("\u001B[${startY + 2};${startX}H")
                sb.append("├").append("─".repeat(dialogWidth - 2)).append("┤")

                sb.append("\u001B[${startY + 3};${startX}H")
                val tokenFieldStyle = if (dialog.focusedField == SettingsField.TOKEN) "$colorBgWhite$colorBlack" else ""
                val tokenValue = if (dialog.token.length > fieldWidth) dialog.token.takeLast(fieldWidth) else dialog.token
                val tokenLine = "${tokenLabel.padEnd(maxLabelLen)} $tokenFieldStyle${tokenValue.padEnd(fieldWidth)}$colorReset"
                sb.append("│ ").append(padVisual(tokenLine, contentWidth)).append(" │")

                sb.append("\u001B[${startY + 4};${startX}H")
                val usernameFieldStyle = if (dialog.focusedField == SettingsField.USERNAME) "$colorBgWhite$colorBlack" else ""
                val usernameValue = if (dialog.username.length > fieldWidth) dialog.username.takeLast(fieldWidth) else dialog.username
                val usernameLine = "${usernameLabel.padEnd(maxLabelLen)} $usernameFieldStyle${usernameValue.padEnd(fieldWidth)}$colorReset"
                sb.append("│ ").append(padVisual(usernameLine, contentWidth)).append(" │")

                sb.append("\u001B[${startY + 5};${startX}H")
                val langFieldStyle = if (dialog.focusedField == SettingsField.LANGUAGE) "$colorBgWhite$colorBlack" else ""
                val langName = Language.entries[dialog.selectedLanguageIndex].nativeName.substring(4).trimStart()
                val langValue = "< ${langName.padEnd(fieldWidth - 4)} >"
                val langLine = "${langLabel.padEnd(maxLabelLen)} $langFieldStyle$langValue$colorReset"
                sb.append("│ ").append(padVisual(langLine, contentWidth)).append(" │")

                sb.append("\u001B[${startY + 6};${startX}H")
                sb.append("│ ").append(" ".repeat(contentWidth)).append(" │")

                sb.append("\u001B[${startY + 7};${startX}H")
                val saveLabel = Strings.get(StringKey.SAVE_BUTTON, SantaDB.consoleLanguage)
                val cancelLabel = Strings.get(StringKey.CANCEL_BUTTON, SantaDB.consoleLanguage)
                val saveStyle = if (dialog.focusedField == SettingsField.SAVE) "$colorBgWhite$colorBlack" else ""
                val cancelStyle = if (dialog.focusedField == SettingsField.CANCEL) "$colorBgWhite$colorBlack" else ""
                val buttonsContent = "$saveStyle [ $saveLabel ] $colorReset   $cancelStyle [ $cancelLabel ] $colorReset"
                val buttonsVisualLen = stripAnsi(buttonsContent).length
                val leftPad = (contentWidth - buttonsVisualLen) / 2
                sb.append("│ ").append(" ".repeat(leftPad.coerceAtLeast(0)))
                sb.append("$saveStyle [ $saveLabel ] $colorReset   $cancelStyle [ $cancelLabel ] $colorReset")
                sb.append(" ".repeat((contentWidth - leftPad - buttonsVisualLen).coerceAtLeast(0))).append(" │")

                sb.append("\u001B[${startY + 8};${startX}H")
                sb.append("│ ").append(" ".repeat(contentWidth)).append(" │")

                sb.append("\u001B[${startY + 9};${startX}H")
                val helpText = "$colorDim${Strings.get(StringKey.NAV_HINT_SETTINGS, SantaDB.consoleLanguage)}$colorReset"
                sb.append("│ ").append(padVisual(helpText, contentWidth)).append(" │")

                sb.append("\u001B[${startY + 10};${startX}H")
                sb.append("└").append("─".repeat(dialogWidth - 2)).append("┘")
            }
            null -> {}
        }

        return sb.toString()
    }

    private fun wrapText(text: String, maxWidth: Int): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            if (currentLine.length + word.length + 1 > maxWidth) {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                    currentLine = StringBuilder()
                }
            }
            if (currentLine.isNotEmpty()) {
                currentLine.append(" ")
            }
            currentLine.append(word)
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
        return lines
    }

    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\u001B\\[[0-9;]*m"), "")
    }

    private fun padVisual(text: String, width: Int): String {
        val visibleLength = stripAnsi(text).length
        val padding = (width - visibleLength).coerceAtLeast(0)
        return text + " ".repeat(padding)
    }

    private fun centerText(text: String, width: Int): String {
        val padding = (width - text.length) / 2
        return " ".repeat(padding.coerceAtLeast(0)) + text
    }

    private fun drawHorizontalLine(width: Int): String {
        return "─".repeat(width)
    }

    private fun setupLogRedirection() {
        try {
            originalOut = System.out
            originalErr = System.err

            val utf8 = Charset.forName("UTF-8")

            fun createCapturingStream(isError: Boolean): OutputStream {
                return object : OutputStream() {
                    private val byteBuffer = java.io.ByteArrayOutputStream()

                    override fun write(b: Int) {
                        if (b == '\n'.code) {
                            flushLine()
                        } else if (b != '\r'.code) {
                            byteBuffer.write(b)
                        }
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        var start = off
                        for (i in off until off + len) {
                            if (b[i] == '\n'.code.toByte()) {
                                if (i > start) {
                                    byteBuffer.write(b, start, i - start)
                                }
                                flushLine()
                                start = i + 1
                            } else if (b[i] == '\r'.code.toByte()) {
                                if (i > start) {
                                    byteBuffer.write(b, start, i - start)
                                }
                                start = i + 1
                            }
                        }
                        if (start < off + len) {
                            byteBuffer.write(b, start, off + len - start)
                        }
                    }

                    private fun flushLine() {
                        val text = byteBuffer.toString(utf8)
                        byteBuffer.reset()
                        if (text.isNotEmpty()) appendLogLine(text, isError)
                    }

                    override fun flush() {
                        if (byteBuffer.size() > 0) {
                            flushLine()
                        }
                    }
                }
            }

            System.setOut(PrintStream(createCapturingStream(false), true, utf8))
            System.setErr(PrintStream(createCapturingStream(true), true, utf8))
        } catch (e: Throwable) {
            originalErr?.println("Error setting up log redirection: ${e.message}")
            e.printStackTrace(originalErr)
        }
    }

    private fun restoreSystemStreams() {
        try {
            originalOut?.let { System.setOut(it) }
            originalErr?.let { System.setErr(it) }
        } catch (e: Throwable) {
            originalErr?.println("Error restoring system streams: ${e.message}")
            e.printStackTrace(originalErr)
        }
    }

    @Synchronized
    private fun appendLogLine(rawLine: String, isError: Boolean) {
        val line = if (isError) "[ERR] $rawLine" else rawLine
        if (logLines.size >= maxLogLines) {
            repeat(logLines.size - maxLogLines + 1) {
                if (logLines.isNotEmpty()) logLines.removeFirst()
            }
        }
        logLines.addLast(line)
    }
}
