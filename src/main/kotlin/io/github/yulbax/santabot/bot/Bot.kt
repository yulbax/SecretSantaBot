package io.github.yulbax.santabot.bot

import io.github.yulbax.santabot.db.SantaDB
import io.github.yulbax.santabot.game.GameFlow
import io.github.yulbax.santabot.i18n.StringKey
import io.github.yulbax.santabot.i18n.Strings
import io.github.yulbax.santabot.model.GameStatus
import io.github.yulbax.santabot.model.Language
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.LocalDate

// ============================================================================
// TELEGRAM BOT
// ============================================================================

open class SantaBot(token: String, val username: String) : LongPollingSingleThreadUpdateConsumer {
    private var schedulerJob: Job? = null
    private val telegramClient: TelegramClient = OkHttpTelegramClient(token)
    private val logger = LoggerFactory.getLogger(SantaBot::class.java)

    override fun consume(update: Update) = GameFlow.handleUpdate(this, update)

    fun start() {
        GameFlow.botUsername = username
        startScheduledGameChecker()
    }

    fun stop() { schedulerJob?.cancel() }

    private fun handleTelegramError(e: TelegramApiException, action: String) {
        val message = e.message ?: "Неизвестная ошибка"
        when {
            message.contains("Network is unreachable") || message.contains("SocketException") ->
                logger.warn("⚠️ Сеть недоступна при $action. Проверьте интернет-соединение.")
            message.contains("SocketTimeout") || message.contains("timed out") ->
                logger.warn("⚠️ Таймаут при $action. Сервер Telegram не отвечает.")
            message.contains("Too Many Requests") ->
                logger.warn("⚠️ Слишком много запросов при $action. Telegram ограничил частоту.")
            message.contains("chat not found") || message.contains("bot was blocked") ->
                logger.debug("Пользователь заблокировал бота или чат не найден: $action")
            else ->
                logger.error("Ошибка Telegram API при $action: $message")
        }
    }

    open fun sendTextMessage(
        chatId: Long, text: String, parseMode: String? = null,
        disableWebPagePreview: Boolean = false, disableNotification: Boolean = false,
        replyMarkup: ReplyKeyboard? = null
    ) {
        try {
            telegramClient.execute(SendMessage.builder()
                .chatId(chatId.toString()).text(text)
                .apply {
                    parseMode?.let { parseMode(it) }
                    if (disableWebPagePreview) disableWebPagePreview(true)
                    if (disableNotification) disableNotification(true)
                    replyMarkup?.let { replyMarkup(it) }
                }.build())
        } catch (e: TelegramApiException) { handleTelegramError(e, "отправке сообщения в чат $chatId") }
    }

    open fun deleteMessage(chatId: Long, messageId: Int) {
        try {
            telegramClient.execute(DeleteMessage.builder()
                .chatId(chatId.toString()).messageId(messageId).build())
        } catch (e: TelegramApiException) { handleTelegramError(e, "удалении сообщения $messageId") }
    }

    open fun answerCallbackQuery(callbackQueryId: String, text: String? = null, showAlert: Boolean = false) {
        try {
            telegramClient.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .apply { text?.let { text(it) }; if (showAlert) showAlert(true) }
                .build())
        } catch (e: TelegramApiException) { handleTelegramError(e, "ответе на callback") }
    }

    private fun startScheduledGameChecker() {
        schedulerJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val today = LocalDate.now()
                val allGames = SantaDB.getAllGames()

                allGames.filter { it.status == GameStatus.RECRUITING && it.startDate?.isEqual(today) == true }
                    .forEach { GameFlow.startGame(this@SantaBot, it) }

                allGames.filter { it.status == GameStatus.IN_PROGRESS && it.endDate?.isEqual(today) == true }
                    .forEach { game ->
                        game.status = GameStatus.FINISHED
                        SantaDB.updateGameStatus(game.gameId, GameStatus.FINISHED)
                        game.participants.keys.forEach { userId ->
                            val lang = SantaDB.getPlayer(userId)?.language ?: Language.RU
                            sendTextMessage(userId, Strings.get(StringKey.GAME_END_NOTIFICATION, lang).format(game.name), disableNotification = true)
                        }
                    }
                delay(5_000)
            }
        }
    }
}

// ============================================================================
// BOT MANAGER
// ============================================================================

sealed class BotStartResult {
    object Success : BotStartResult()
    object AlreadyRunning : BotStartResult()
    object EmptyCredentials : BotStartResult()
    data class Error(val message: String) : BotStartResult()
}

class SantaBotManager {
    private var bot: SantaBot? = null
    private var botJob: Job? = null
    private var botsApplication: TelegramBotsLongPollingApplication? = null

    @Volatile
    private var lastError: String? = null

    val isBotRunning: Boolean get() = botJob?.isActive == true

    fun startBot(token: String, username: String): BotStartResult {
        if (isBotRunning) return BotStartResult.AlreadyRunning
        if (token.isBlank() || username.isBlank()) return BotStartResult.EmptyCredentials

        lastError = null
        bot = SantaBot(token, username)
        botsApplication = TelegramBotsLongPollingApplication()

        try {
            botsApplication?.registerBot(token, bot)
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Неизвестная ошибка"
            bot = null
            botsApplication?.close()
            botsApplication = null
            return BotStartResult.Error(errorMsg)
        }

        botJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                bot?.start()
                while (isActive) { delay(1000) }
            } catch (e: Exception) {
                lastError = e.message
                println("Ошибка бота: ${e.message}")
                stopBot()
            }
        }

        return BotStartResult.Success
    }

    fun stopBot() {
        if (!isBotRunning) return
        bot?.stop()
        botsApplication?.close()
        botJob?.cancel()
        bot = null
        botsApplication = null
        botJob = null
    }
}
