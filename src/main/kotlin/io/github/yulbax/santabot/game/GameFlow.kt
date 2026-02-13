package io.github.yulbax.santabot.game

import io.github.yulbax.santabot.bot.SantaBot
import io.github.yulbax.santabot.db.SantaDB
import io.github.yulbax.santabot.i18n.StringKey
import io.github.yulbax.santabot.i18n.Strings
import io.github.yulbax.santabot.model.*
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException


object GameFlow {
    lateinit var botUsername: String

    private const val PARSE_MODE_HTML = "HTML"

    private fun String.escapeHtml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    fun handleUpdate(bot: SantaBot, update: Update) {
        when {
            update.hasMessage() && update.message.hasText() -> {
                val message = update.message
                if (message.text.startsWith("/start")) {
                    handleStartCommand(bot, message)
                } else {
                    handleTextInput(bot, message)
                }
            }
            update.hasCallbackQuery() -> handleCallbackQuery(bot, update.callbackQuery)
        }
    }

    private fun handleStartCommand(bot: SantaBot, message: Message) {
        val user = message.from ?: return
        val args = message.text.split(" ")
        val deepLinkParam = args.getOrNull(1)

        if (deepLinkParam?.startsWith("join_") == true) {
            handleJoinLink(bot, user, deepLinkParam.removePrefix("join_"))
        } else {
            handleNormalStart(bot, user)
        }
    }

    private fun handleJoinLink(bot: SantaBot, user: User, inviteCode: String) {
        val game = SantaDB.findGameByInviteCode(inviteCode)
        
        if (game == null) {
            bot.sendTextMessage(user.id, user.language.str(StringKey.GAME_NOT_FOUND))
            return
        }

        val (_, isNewPlayer) = SantaDB.registerPlayer(user.id, user.firstName, user.userName, user.languageCode)

        if (isNewPlayer) {
            promptForLanguage(bot, user.id, PostLanguageAction.JoinGame(game.gameId))
        } else {
            handleJoinGame(bot, user, game.gameId)
        }
    }

    private fun handleNormalStart(bot: SantaBot, user: User) {
        val (_, isNewPlayer) = SantaDB.registerPlayer(user.id, user.firstName, user.userName, user.languageCode)

        if (isNewPlayer) {
            promptForLanguage(bot, user.id, PostLanguageAction.ShowWelcome)
        } else {
            val lang = user.language
            bot.sendTextMessage(
                chatId = user.id,
                text = lang.str(StringKey.WELCOME_BACK),
                replyMarkup = getMainMenuKeyboard(lang)
            )
        }
    }

    private fun handleTextInput(bot: SantaBot, message: Message) {
        val user = message.from ?: return
        val text = message.text
        val lang = user.language
        val state = SantaDB.getUserState(user.id)

        if (text == lang.str(StringKey.CANCEL_BUTTON) && state !is UserState.AwaitingAnonymousMessage) {
            handleCancel(bot, user, state, lang)
            return
        }

        when (state) {
            is UserState.AwaitingLanguage -> {}
            is UserState.AwaitingGameName -> handleGameNameInput(bot, user.id, text, lang)
            is UserState.AwaitingStartDate -> handleDateInput(bot, user.id, text, state.gameId, isStartDate = true, lang)
            is UserState.AwaitingEndDate -> handleDateInput(bot, user.id, text, state.gameId, isStartDate = false, lang)
            is UserState.AwaitingPlayerName -> handlePlayerNameInput(bot, user, text, state, lang)
            is UserState.AwaitingAnonymousMessage -> handleAnonymousMessage(bot, user.id, text, state, lang)
            is UserState.AwaitingWishlist -> handleWishlistInput(bot, user, text, state, lang)
            null -> handleMainMenuInput(bot, user, text, lang)
        }
    }

    private fun handleCancel(bot: SantaBot, user: User, state: UserState?, lang: Language) {
        if (state == null) return

        val gameIdToDelete = when (state) {
            is UserState.AwaitingStartDate -> state.gameId
            is UserState.AwaitingEndDate -> state.gameId
            is UserState.AwaitingPlayerName -> if (state.isCreator) state.gameId else null
            is UserState.AwaitingWishlist -> if (state.isCreator) state.gameId else null
            else -> null
        }

        when (state) {
            is UserState.AwaitingPlayerName -> if (!state.isCreator) {
                SantaDB.findGameById(state.gameId)?.participants?.remove(user.id)
            }
            is UserState.AwaitingWishlist -> if (!state.isCreator) {
                val game = SantaDB.findGameById(state.gameId)
                game?.participants?.remove(user.id)
                game?.wishlists?.remove(user.id)
            }
            else -> {}
        }

        if (gameIdToDelete != null) {
            SantaDB.deleteGame(gameIdToDelete)
        }

        SantaDB.setUserState(user.id, null)
        
        val messageKey = if (state is UserState.AwaitingLanguage) StringKey.WELCOME_BACK else StringKey.GAME_CREATION_CANCELLED
        
        bot.sendTextMessage(
            user.id, 
            lang.str(messageKey), 
            replyMarkup = getMainMenuKeyboard(lang)
        )
    }

    private fun handleMainMenuInput(bot: SantaBot, user: User, text: String, lang: Language) {
        when (text) {
            lang.str(StringKey.CREATE_GAME_BUTTON) -> promptForGameName(bot, user.id, lang)
            lang.str(StringKey.ACTIVE_GAMES_BUTTON) -> showActiveGames(bot, user.id, lang)
            lang.str(StringKey.CHANGE_LANGUAGE_BUTTON) -> promptForLanguage(bot, user.id, PostLanguageAction.ShowWelcome)
        }
    }

    private fun handleWishlistInput(bot: SantaBot, user: User, text: String, state: UserState.AwaitingWishlist, lang: Language) {
        if (text == lang.str(StringKey.DONE_BUTTON)) {
            finishWishlistInput(bot, user.id, state, lang)
        } else {
            val game = SantaDB.findGameById(state.gameId) ?: return
            val currentWishlist = game.wishlists[user.id] ?: ""
            val sanitizedText = text.escapeHtml()

            val newWishlist = if (currentWishlist.isEmpty()) sanitizedText else "$currentWishlist\n$sanitizedText"

            if (newWishlist.length > 2000) {
                bot.sendTextMessage(user.id, lang.str(StringKey.WISHLIST_TOO_LONG_ERROR))
                return
            }

            game.wishlists[user.id] = newWishlist
        }
    }

    private fun handleCallbackQuery(bot: SantaBot, callbackQuery: CallbackQuery) {
        val data = callbackQuery.data
        val user = callbackQuery.from
        val lang = user.language
        val messageId = callbackQuery.message?.messageId

        when {
            data.startsWith("lang_") -> handleLanguageSelection(bot, user, data.removePrefix("lang_"), messageId)
            data.startsWith("view_game_") -> {
                showGameDetails(bot, user.id, data.removePrefix("view_game_"), lang, messageId)
                bot.answerCallbackQuery(callbackQuery.id)
            }
            data.startsWith("edit_wishlist_") -> {
                promptForWishlist(bot, user.id, data.removePrefix("edit_wishlist_"), isCreator = false, lang)
                bot.answerCallbackQuery(callbackQuery.id)
            }
            data.startsWith("leave_game_") -> handleLeaveGame(bot, callbackQuery, data.removePrefix("leave_game_"), lang)
            data.startsWith("delete_game_") -> handleDeleteGame(bot, callbackQuery, data.removePrefix("delete_game_"), lang)
            data.startsWith("start_now_") -> handleStartGameNow(bot, callbackQuery, data.removePrefix("start_now_"), lang)
            data.startsWith("anon_message_") -> {
                val gameId = data.removePrefix("anon_message_")
                SantaDB.setUserState(user.id, UserState.AwaitingAnonymousMessage(gameId))
                bot.sendTextMessage(user.id, lang.str(StringKey.PROMPT_ANONYMOUS_MESSAGE))
                bot.answerCallbackQuery(callbackQuery.id)
            }
        }
    }

    private fun handleLanguageSelection(bot: SantaBot, user: User, langCode: String, messageId: Int?) {
        val selectedLang = Language.fromCode(langCode)
        SantaDB.setPlayerLanguage(user.id, selectedLang)

        val state = SantaDB.getUserState(user.id)
        SantaDB.setUserState(user.id, null)
        if (messageId != null) bot.deleteMessage(user.id, messageId)

        if (state is UserState.AwaitingLanguage) {
            when (state.nextAction) {
                is PostLanguageAction.ShowWelcome -> sendWelcomeMessage(bot, user.id, selectedLang)
                is PostLanguageAction.JoinGame -> handleJoinGame(bot, user, state.nextAction.gameId)
            }
        } else {
            bot.sendTextMessage(
                chatId = user.id,
                text = selectedLang.str(StringKey.LANGUAGE_CHANGED),
                replyMarkup = getMainMenuKeyboard(selectedLang)
            )
        }
    }

    private fun promptForLanguage(bot: SantaBot, userId: Long, nextAction: PostLanguageAction) {
        SantaDB.setUserState(userId, UserState.AwaitingLanguage(nextAction))
        val currentLang = userId.language
        
        bot.sendTextMessage(
            chatId = userId,
            text = currentLang.str(StringKey.CANCEL_BUTTON),
            replyMarkup = getCancelKeyboard(currentLang)
        )

        val buttons = Language.entries.map { lang ->
            inlineButton(lang.nativeName, "lang_${lang.code}")
        }
        
        bot.sendTextMessage(
            chatId = userId,
            text = "Please select your language / Пожалуйста, выберите язык:",
            replyMarkup = inlineKeyboard(buttons, columns = 2)
        )
    }

    private fun promptForGameName(bot: SantaBot, userId: Long, lang: Language) {
        SantaDB.setUserState(userId, UserState.AwaitingGameName)
        bot.sendTextMessage(
            userId,
            lang.str(StringKey.PROMPT_GAME_NAME),
            replyMarkup = getCancelKeyboard(lang)
        )
    }

    private fun handleGameNameInput(bot: SantaBot, userId: Long, text: String, lang: Language) {
        val gameName = text.trim()

        if (gameName.isBlank()) {
            bot.sendTextMessage(userId, lang.str(StringKey.GAME_NAME_EMPTY_ERROR), replyMarkup = getCancelKeyboard(lang))
            return
        }

        if (gameName.length > 100) {
            bot.sendTextMessage(userId, lang.str(StringKey.GAME_NAME_TOO_LONG_ERROR), replyMarkup = getCancelKeyboard(lang))
            return
        }

        val game = SantaDB.createPendingGame(userId, gameName.escapeHtml())
        SantaDB.setUserState(userId, UserState.AwaitingStartDate(game.gameId))
        bot.sendTextMessage(
            userId,
            lang.str(StringKey.PROMPT_START_DATE),
            replyMarkup = getCancelKeyboard(lang)
        )
    }

    private fun handleDateInput(bot: SantaBot, userId: Long, text: String, gameId: String, isStartDate: Boolean, lang: Language) {
        val game = SantaDB.findGameById(gameId) ?: return

        try {
            val dateParts = text.split(".").map { it.toInt() }
            if (dateParts.size != 3) throw DateTimeParseException("Invalid parts", text, 0)
            val date = LocalDate.of(dateParts[2], dateParts[1], dateParts[0])

            if (isStartDate) {
                if (!date.isAfter(LocalDate.now())) {
                    bot.sendTextMessage(userId, lang.str(StringKey.DATE_IN_PAST_ERROR), replyMarkup = getCancelKeyboard(lang))
                    return
                }
                if (date.isAfter(LocalDate.now().plusWeeks(1))) {
                    bot.sendTextMessage(userId, lang.str(StringKey.START_DATE_TOO_FAR_ERROR), replyMarkup = getCancelKeyboard(lang))
                    return
                }
                game.startDate = date
                SantaDB.updateGameDates(gameId, date, null)
                SantaDB.setUserState(userId, UserState.AwaitingEndDate(game.gameId))
                bot.sendTextMessage(userId, lang.str(StringKey.PROMPT_END_DATE), replyMarkup = getCancelKeyboard(lang))
            } else {
                if (!date.isAfter(game.startDate)) {
                    bot.sendTextMessage(userId, lang.str(StringKey.END_DATE_ERROR), replyMarkup = getCancelKeyboard(lang))
                    return
                }
                if (date.isAfter(game.startDate!!.plusMonths(3))) {
                    bot.sendTextMessage(userId, lang.str(StringKey.END_DATE_TOO_FAR_ERROR), replyMarkup = getCancelKeyboard(lang))
                    return
                }
                game.endDate = date
                SantaDB.updateGameDates(gameId, game.startDate, date)
                SantaDB.setUserState(userId, UserState.AwaitingPlayerName(game.gameId, isCreator = true))
                bot.sendTextMessage(userId, lang.str(StringKey.PROMPT_CREATOR_NAME), replyMarkup = getCancelKeyboard(lang))
            }
        } catch (_: Exception) {
            bot.sendTextMessage(userId, lang.str(StringKey.DATE_FORMAT_ERROR), replyMarkup = getCancelKeyboard(lang))
        }
    }

    private fun handleJoinGame(bot: SantaBot, user: User, gameId: String) {
        val lang = user.language
        val game = SantaDB.findGameById(gameId)
        
        when {
            game == null -> {
                bot.sendTextMessage(user.id, lang.str(StringKey.GAME_NOT_FOUND), replyMarkup = getMainMenuKeyboard(lang))
            }
            game.status != GameStatus.RECRUITING -> {
                bot.sendTextMessage(user.id, lang.str(StringKey.GAME_ALREADY_STARTED)
                    .format(game.name), replyMarkup = getMainMenuKeyboard(lang))
            }
            game.participants.containsKey(user.id) -> {
                bot.sendTextMessage(user.id, lang.str(StringKey.ALREADY_IN_GAME)
                    .format(game.name))
                showGameDetails(bot, user.id, gameId, lang)
            }
            else -> {
                SantaDB.setUserState(user.id, UserState.AwaitingPlayerName(game.gameId))
                bot.sendTextMessage(user.id, lang.str(StringKey.WELCOME_TO_GAME)
                    .format(game.name), replyMarkup = getCancelKeyboard(lang))
            }
        }
    }

    private fun handleLeaveGame(bot: SantaBot, callbackQuery: CallbackQuery, gameId: String, lang: Language) {
        val user = callbackQuery.from
        val game = SantaDB.findGameById(gameId) ?: return

        game.participants.remove(user.id)
        game.wishlists.remove(user.id)
        SantaDB.removeParticipant(gameId, user.id)

        bot.answerCallbackQuery(callbackQuery.id, text = lang.str(StringKey.YOU_LEFT_GAME).format(game.name))
        callbackQuery.message?.let { bot.deleteMessage(user.id, it.messageId) }
        bot.sendTextMessage(chatId = user.id, text = lang.str(StringKey.YOU_LEFT_GAME_SUCCESS).format(game.name))
    }

    private fun handleDeleteGame(bot: SantaBot, callbackQuery: CallbackQuery, gameId: String, lang: Language) {
        val user = callbackQuery.from
        val game = SantaDB.findGameById(gameId) ?: return

        if (game.creatorId != user.id) {
            bot.answerCallbackQuery(callbackQuery.id, text = lang.str(StringKey.ONLY_CREATOR_CAN_DELETE), showAlert = true)
            return
        }

        game.participants.keys.forEach { participantId ->
            if (participantId != user.id) {
                val pLang = participantId.language
                bot.sendTextMessage(participantId, pLang.str(StringKey.GAME_CANCELLED_NOTIFICATION).format(game.name))
            }
        }

        SantaDB.deleteGame(gameId)
        bot.answerCallbackQuery(callbackQuery.id, text = lang.str(StringKey.GAME_DELETED).format(game.name))
        callbackQuery.message?.let { bot.deleteMessage(user.id, it.messageId) }
        bot.sendTextMessage(chatId = user.id, text = lang.str(StringKey.GAME_DELETED_SUCCESS).format(game.name))
    }

    private fun handleStartGameNow(bot: SantaBot, callbackQuery: CallbackQuery, gameId: String, lang: Language) {
        val user = callbackQuery.from
        val game = SantaDB.findGameById(gameId) ?: return

        if (game.creatorId != user.id) return

        if (game.participants.size < 3) {
            bot.answerCallbackQuery(callbackQuery.id, text = lang.str(StringKey.NOT_ENOUGH_PLAYERS_ERROR), showAlert = true)
            return
        }

        startGame(bot, game)
        bot.answerCallbackQuery(callbackQuery.id, text = lang.str(StringKey.GAME_STARTED_SUCCESS).format(game.name))
        showGameDetails(bot, user.id, gameId, lang, callbackQuery.message?.messageId)
    }

    fun startGame(bot: SantaBot, game: GameInstance) {
        val participantIds = game.participants.keys.toList()
        val gameName = game.name

        game.start()

        if (game.status == GameStatus.FINISHED) {
            SantaDB.updateGameStatus(game.gameId, GameStatus.FINISHED)

            participantIds.forEach { userId ->
                val lang = userId.language
                bot.sendTextMessage(
                    chatId = userId,
                    text = lang.str(StringKey.GAME_FAILED_NOT_ENOUGH_PLAYERS).format(gameName)
                )
            }
            SantaDB.deleteGame(game.gameId)
            return
        }

        if (game.status == GameStatus.IN_PROGRESS) {
            SantaDB.savePairings(game.gameId, game.pairings)
            SantaDB.updateGameStatus(game.gameId, GameStatus.IN_PROGRESS)

            game.pairings.forEach { (giverId, receiverId) ->
                val receiver = game.participants[receiverId] ?: return@forEach
                val lang = giverId.language

                val receiverLink = if (receiver.username != null) {
                    "<a href=\"https://t.me/${receiver.username}\">${receiver.name}</a>"
                } else {
                    "<a href=\"tg://user?id=${receiver.userId}\">${receiver.name}</a>"
                }

                val wishlistText = game.wishlists[receiverId]?.split('\n')?.joinToString("\n - ", prefix = "- ") ?: lang.str(StringKey.NOT_SET)

                val keyboard = inlineKeyboard(
                    inlineRow(inlineButton(lang.str(StringKey.ANONYMOUS_MESSAGE_BUTTON), "anon_message_${game.gameId}"))
                )

                bot.sendTextMessage(
                    giverId,
                    lang.str(StringKey.GAME_START_NOTIFICATION).format(game.name, receiverLink, wishlistText),
                    parseMode = PARSE_MODE_HTML,
                    disableWebPagePreview = true,
                    replyMarkup = keyboard
                )
            }
        }
    }

    private fun handlePlayerNameInput(bot: SantaBot, user: User, playerName: String, state: UserState.AwaitingPlayerName, lang: Language) {
        if (playerName.startsWith("/start")) return

        val trimmedName = playerName.trim()

        if (trimmedName.isBlank()) {
            bot.sendTextMessage(user.id, lang.str(StringKey.PLAYER_NAME_EMPTY_ERROR), replyMarkup = getCancelKeyboard(lang))
            return
        }

        if (trimmedName.length > 50) {
            bot.sendTextMessage(user.id, lang.str(StringKey.PLAYER_NAME_TOO_LONG_ERROR), replyMarkup = getCancelKeyboard(lang))
            return
        }

        val (player, _) = SantaDB.registerPlayer(user.id, trimmedName.escapeHtml(), user.userName, lang.code)
        val game = SantaDB.findGameById(state.gameId) ?: return
        game.addParticipant(player)
        SantaDB.addParticipant(state.gameId, player)

        promptForWishlist(bot, user.id, state.gameId, state.isCreator, lang)
    }

    private fun promptForWishlist(bot: SantaBot, userId: Long, gameId: String, isCreator: Boolean, lang: Language) {
        SantaDB.setUserState(userId, UserState.AwaitingWishlist(gameId, isCreator))
        SantaDB.updateWishlist(gameId, userId, null)
        SantaDB.findGameById(gameId)?.wishlists?.remove(userId)

        bot.sendTextMessage(
            chatId = userId,
            text = lang.str(StringKey.PROMPT_WISHLIST),
            replyMarkup = replyKeyboard(
                replyRow(replyButton(lang.str(StringKey.DONE_BUTTON))),
                replyRow(replyButton(lang.str(StringKey.CANCEL_BUTTON)))
            )
        )
    }

    private fun finishWishlistInput(bot: SantaBot, userId: Long, state: UserState.AwaitingWishlist, lang: Language) {
        SantaDB.setUserState(userId, null)

        if (state.isCreator) {
            val game = SantaDB.findGameById(state.gameId) ?: return
            val inviteCode = SantaDB.generateInviteCodeForGame(game)
            val joinLink = "https://t.me/$botUsername?start=join_$inviteCode"
            bot.sendTextMessage(
                userId,
                lang.str(StringKey.CREATOR_IN_GAME).format(game.name, joinLink),
                replyMarkup = getMainMenuKeyboard(lang)
            )
        } else {
            bot.sendTextMessage(
                chatId = userId,
                text = lang.str(StringKey.PLAYER_IN_GAME),
                replyMarkup = getMainMenuKeyboard(lang)
            )
        }
    }

    private fun handleAnonymousMessage(bot: SantaBot, userId: Long, text: String, state: UserState.AwaitingAnonymousMessage, lang: Language) {
        val game = SantaDB.findGameById(state.gameId) ?: run {
            SantaDB.setUserState(userId, null)
            bot.sendTextMessage(userId, lang.str(StringKey.GAME_NOT_FOUND), replyMarkup = getMainMenuKeyboard(lang))
            return
        }

        val receiverId = game.pairings[userId] ?: run {
            SantaDB.setUserState(userId, null)
            return
        }

        if (text.length > 1000) {
            bot.sendTextMessage(userId, lang.str(StringKey.MESSAGE_TOO_LONG_ERROR))
            return
        }

        val sanitizedText = text.escapeHtml()

        val receiverLang = receiverId.language
        val header = receiverLang.str(StringKey.ANONYMOUS_MESSAGE_HEADER).format(game.name)

        bot.sendTextMessage(
            chatId = receiverId,
            text = "$header\n\n<i>$sanitizedText</i>",
            parseMode = PARSE_MODE_HTML
        )

        SantaDB.setUserState(userId, null)
        bot.sendTextMessage(
            chatId = userId,
            text = lang.str(StringKey.ANONYMOUS_MESSAGE_SENT),
            replyMarkup = getMainMenuKeyboard(lang)
        )
    }

    private fun showActiveGames(bot: SantaBot, userId: Long, lang: Language) {
        val games = SantaDB.findGamesForPlayer(userId).filter { it.status != GameStatus.FINISHED }

        if (games.isEmpty()) {
            bot.sendTextMessage(userId, lang.str(StringKey.NO_ACTIVE_GAMES))
            return
        }

        val buttons = games.map { game ->
            inlineButton(game.name, "view_game_${game.gameId}")
        }

        bot.sendTextMessage(
            chatId = userId,
            text = lang.str(StringKey.SELECT_GAME_DETAILS),
            replyMarkup = inlineKeyboard(buttons, columns = 1)
        )
    }

    private fun showGameDetails(bot: SantaBot, userId: Long, gameId: String, lang: Language, messageIdToDelete: Int? = null) {
        val game = SantaDB.findGameById(gameId) ?: return

        val participantsText = game.participants.values.joinToString("\n") { player ->
            val link = if (player.username != null) "<a href=\"https://t.me/${player.username}\">${player.name}</a>" else "<a href=\"tg://user?id=${player.userId}\">${player.name}</a>"
            " - $link"
        }

        val statusKey = when(game.status) {
            GameStatus.CREATING -> StringKey.STATUS_CREATING
            GameStatus.RECRUITING -> StringKey.STATUS_RECRUITING
            GameStatus.IN_PROGRESS -> StringKey.STATUS_IN_PROGRESS
            GameStatus.FINISHED -> StringKey.STATUS_FINISHED
        }

        var text = "<b>${lang.str(StringKey.GAME_DETAILS_TITLE)} ${game.name}</b>\n\n" +
                "<b>${lang.str(StringKey.STATUS_LABEL)}</b> ${lang.str(statusKey)}\n" +
                "<b>${lang.str(StringKey.START_DATE_LABEL)}</b> ${game.startDate?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) ?: lang.str(StringKey.NOT_SET)}\n" +
                "<b>${lang.str(StringKey.END_DATE_LABEL)}</b> ${game.endDate?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) ?: lang.str(StringKey.NOT_SET)}\n\n" +
                "<b>${lang.str(StringKey.PARTICIPANTS_LABEL)} (${game.participants.size})</b>\n$participantsText"

        if (game.status == GameStatus.IN_PROGRESS) {
            val receiverId = game.pairings[userId]
            receiverId?.let { game.participants[it] }?.let {
                text += "\n\n🎁 <b>${lang.str(StringKey.YOUR_GIFTEE)}</b> ${it.name}"
            }
        }

        val buttons = mutableListOf<InlineKeyboardButton>()
        if (game.status == GameStatus.RECRUITING) {
            val wishlistButtonText = if (game.wishlists.containsKey(userId)) lang.str(StringKey.EDIT_WISHLIST_BUTTON) else lang.str(StringKey.ADD_WISHLIST_BUTTON)
            buttons.add(inlineButton(wishlistButtonText, "edit_wishlist_${game.gameId}"))

            if (userId == game.creatorId) {
                buttons.add(inlineButton(lang.str(StringKey.START_GAME_NOW_BUTTON), "start_now_${game.gameId}"))
                buttons.add(inlineButton(lang.str(StringKey.DELETE_GAME_BUTTON), "delete_game_${game.gameId}"))
            } else {
                buttons.add(inlineButton(lang.str(StringKey.LEAVE_GAME_BUTTON), "leave_game_${game.gameId}"))
            }
        } else if (game.status == GameStatus.IN_PROGRESS) {
            buttons.add(inlineButton(lang.str(StringKey.ANONYMOUS_MESSAGE_BUTTON), "anon_message_${game.gameId}"))
        }

        val keyboard = if (buttons.isNotEmpty()) inlineKeyboard(buttons, columns = 1) else null

        messageIdToDelete?.let { bot.deleteMessage(userId, it) }

        bot.sendTextMessage(
            chatId = userId,
            text = text,
            parseMode = PARSE_MODE_HTML,
            replyMarkup = keyboard,
            disableWebPagePreview = true
        )
    }

    private fun getMainMenuKeyboard(lang: Language): ReplyKeyboardMarkup {
        return replyKeyboard(
            replyRow(
                replyButton(lang.str(StringKey.CREATE_GAME_BUTTON)),
                replyButton(lang.str(StringKey.ACTIVE_GAMES_BUTTON))
            ),
            replyRow(
                replyButton(lang.str(StringKey.CHANGE_LANGUAGE_BUTTON))
            )
        )
    }

    private fun getCancelKeyboard(lang: Language): ReplyKeyboardMarkup {
        return replyKeyboard(replyRow(replyButton(lang.str(StringKey.CANCEL_BUTTON))), oneTime = true)
    }

    private fun sendWelcomeMessage(bot: SantaBot, userId: Long, lang: Language) {
        val welcomeText = """
        <b>${lang.str(StringKey.WELCOME_TITLE)}</b> 🎅

        ${lang.str(StringKey.WELCOME_BODY)}
        """.trimIndent()
        bot.sendTextMessage(
            chatId = userId,
            text = welcomeText,
            parseMode = PARSE_MODE_HTML,
            replyMarkup = getMainMenuKeyboard(lang)
        )
    }

    // --- Helpers ---
    
    private inline val User.language get() = SantaDB.getPlayer(id)?.language ?: Language.EN
    private inline val Long.language get() = SantaDB.getPlayer(this)?.language ?: Language.EN
    private fun Language.str(key: StringKey) = Strings.get(key, this)
    
    private fun inlineButton(text: String, data: String) = InlineKeyboardButton.builder().text(text).callbackData(data).build()
    private fun inlineRow(vararg buttons: InlineKeyboardButton) = InlineKeyboardRow(buttons.toList())
    private fun inlineKeyboard(vararg rows: InlineKeyboardRow) = InlineKeyboardMarkup.builder().keyboard(rows.toList()).build()
    private fun inlineKeyboard(buttons: List<InlineKeyboardButton>, columns: Int): InlineKeyboardMarkup {
        val rows = buttons.chunked(columns).map { InlineKeyboardRow(it) }
        return InlineKeyboardMarkup.builder().keyboard(rows.toList()).build()
    }
    
    private fun replyButton(text: String) = KeyboardButton(text)
    private fun replyRow(vararg buttons: KeyboardButton) = KeyboardRow(buttons.toList())
    private fun replyKeyboard(vararg rows: KeyboardRow, oneTime: Boolean = false) =
        ReplyKeyboardMarkup.builder()
            .keyboard(rows.toList())
            .resizeKeyboard(true)
            .oneTimeKeyboard(oneTime)
            .build()
}
