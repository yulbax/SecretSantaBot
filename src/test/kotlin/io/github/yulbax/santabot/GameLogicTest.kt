package io.github.yulbax.santabot

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import io.github.yulbax.santabot.bot.SantaBot
import io.github.yulbax.santabot.db.SantaDB
import io.github.yulbax.santabot.game.GameFlow
import io.github.yulbax.santabot.i18n.StringKey
import io.github.yulbax.santabot.i18n.Strings
import io.github.yulbax.santabot.model.GameStatus
import io.github.yulbax.santabot.model.Language
import io.github.yulbax.santabot.model.UserState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard
import java.io.File
import java.time.LocalDate

class GameLogicTest {

    private val gson = GsonBuilder()
        .registerTypeAdapter(MaybeInaccessibleMessage::class.java, JsonDeserializer<MaybeInaccessibleMessage> { json, _, _ ->
            Gson().fromJson(json, Message::class.java)
        })
        .create()
        
    private lateinit var mockBot: MockSantaBot

    @BeforeEach
    fun setup() {
        SantaDB.initializeForTests()

        File("santabot_state.json").delete()
        File("santabot_backup.json").delete()
        
        mockBot = MockSantaBot()
        GameFlow.botUsername = "TestBot"
        
        Strings.preloadAll()
    }

    @AfterEach
    fun tearDown() {
        File("santabot_state.json").delete()
        File("santabot_backup.json").delete()
    }

    @Test
    fun `test game creation flow logic`() {
        val creatorId = 1001L
        val gameName = "Test Game 2024"

        val game = SantaDB.createPendingGame(creatorId, gameName)
        assertNotNull(game)
        assertEquals(gameName, game.name)
        assertEquals(creatorId, game.creatorId)
        assertEquals(GameStatus.CREATING, game.status)

        val startDate = LocalDate.now().plusDays(1)
        val endDate = LocalDate.now().plusDays(7)
        game.startDate = startDate
        game.endDate = endDate

        val inviteCode = SantaDB.generateInviteCodeForGame(game)
        assertNotNull(inviteCode)
        assertEquals(GameStatus.RECRUITING, game.status)
    }

    @Test
    fun `test full game flow with interaction`() {
        val creatorId = 123L
        val user2Id = 456L
        val user3Id = 789L

        sendText(creatorId, "creator", "/start")
        assertMessageReceived(creatorId)

        sendCallback(creatorId, "creator", "lang_en")
        assertMessageReceived(creatorId)

        val createBtn = Strings.get(StringKey.CREATE_GAME_BUTTON, Language.EN)
        sendText(creatorId, "creator", createBtn)
        assertMessageReceived(creatorId)

        sendText(creatorId, "creator", "Super Game")
        assertMessageReceived(creatorId)

        val tomorrow = LocalDate.now().plusDays(1)
        val dateStr = "${tomorrow.dayOfMonth}.${tomorrow.monthValue}.${tomorrow.year}"
        sendText(creatorId, "creator", dateStr)
        assertMessageReceived(creatorId)

        val nextWeek = LocalDate.now().plusDays(7)
        val endDateStr = "${nextWeek.dayOfMonth}.${nextWeek.monthValue}.${nextWeek.year}"
        sendText(creatorId, "creator", endDateStr)
        assertMessageReceived(creatorId)

        sendText(creatorId, "creator", "Santa Claus")
        assertMessageReceived(creatorId)

        sendText(creatorId, "creator", "Cookies")
        val doneBtn = Strings.get(StringKey.DONE_BUTTON, Language.EN)
        sendText(creatorId, "creator", doneBtn)
        
        val game = SantaDB.getAllGames().firstOrNull()
        assertNotNull(game, "Game should be created")
        assertEquals("Super Game", game!!.name)
        
        sendText(user2Id, "elf2", "/start join_${game.inviteCode}")
        sendCallback(user2Id, "elf2", "lang_en")
        
        sendText(user2Id, "elf2", "Elf Two")
        sendText(user2Id, "elf2", "Toys")
        sendText(user2Id, "elf2", doneBtn)
        
        sendText(user3Id, "elf3", "/start join_${game.inviteCode}")
        sendCallback(user3Id, "elf3", "lang_en")
        sendText(user3Id, "elf3", "Elf Three")
        sendText(user3Id, "elf3", "Candy")
        sendText(user3Id, "elf3", doneBtn)
        
        sendCallback(creatorId, "creator", "start_now_${game.gameId}")
        
        val updatedGame = SantaDB.findGameById(game.gameId)!!
        assertEquals(GameStatus.IN_PROGRESS, updatedGame.status)

        assertEquals(3, updatedGame.pairings.size)

        val p1Giftee = updatedGame.participants[updatedGame.pairings[creatorId]]!!
        assertLastMessageContains(creatorId, p1Giftee.name)

        sendCallback(creatorId, "creator", "anon_message_${updatedGame.gameId}")
        assertMessageReceived(creatorId)

        val anonMessageText = "Ho ho ho! I am your Santa!"
        sendText(creatorId, "creator", anonMessageText)
        
        val gifteeId = updatedGame.pairings[creatorId]!!
        assertLastMessageContains(gifteeId, anonMessageText)
        
        assertLastMessageContains(creatorId, Strings.get(StringKey.ANONYMOUS_MESSAGE_SENT, Language.EN))
    }

    @Test
    fun `test simultaneous interactions`() {
        val userA = 100L
        val userB = 200L
        
        sendText(userA, "A", "/start")
        sendText(userB, "B", "/start")
        
        sendCallback(userA, "A", "lang_en")
        sendCallback(userB, "B", "lang_en")
        
        assertMessageReceived(userA)
        assertMessageReceived(userB)
        
        sendText(userA, "A", Strings.get(StringKey.CREATE_GAME_BUTTON, Language.EN))
        sendText(userB, "B", Strings.get(StringKey.ACTIVE_GAMES_BUTTON, Language.EN))
        
        assertTrue(SantaDB.getUserState(userA) is UserState.AwaitingGameName)
        assertNull(SantaDB.getUserState(userB))
    }

    @Test
    fun `test leave game logic`() {
        val creatorId = 111L
        val user2Id = 222L
        
        val game = SantaDB.createPendingGame(creatorId, "Leave Test Game")
        SantaDB.updateGameDates(game.gameId, LocalDate.now().plusDays(1), LocalDate.now().plusDays(2))
        SantaDB.generateInviteCodeForGame(game)
        
        val (creator, _) = SantaDB.registerPlayer(creatorId, "Creator", "creator", "en")
        SantaDB.addParticipant(game.gameId, creator)

        val (user2, _) = SantaDB.registerPlayer(user2Id, "User2", "user2", "en")
        SantaDB.addParticipant(game.gameId, user2)

        var loadedGame = SantaDB.findGameById(game.gameId)!!
        assertEquals(2, loadedGame.participants.size)

        sendCallback(user2Id, "user2", "leave_game_${game.gameId}")
        
        loadedGame = SantaDB.findGameById(game.gameId)!!
        assertEquals(1, loadedGame.participants.size)
        assertFalse(loadedGame.participants.containsKey(user2Id))
    }

    @Test
    fun `test delete game logic`() {
        val creatorId = 333L
        val user2Id = 444L
        
        val game = SantaDB.createPendingGame(creatorId, "Delete Test Game")
        SantaDB.updateGameDates(game.gameId, LocalDate.now().plusDays(1), LocalDate.now().plusDays(2))
        SantaDB.generateInviteCodeForGame(game)
        
        val (creator, _) = SantaDB.registerPlayer(creatorId, "Creator", "creator", "en")
        SantaDB.addParticipant(game.gameId, creator)

        val (user2, _) = SantaDB.registerPlayer(user2Id, "User2", "user2", "en")
        SantaDB.addParticipant(game.gameId, user2)

        sendCallback(creatorId, "creator", "delete_game_${game.gameId}")
        
        assertNull(SantaDB.findGameById(game.gameId))
        
        assertLastMessageContains(user2Id, game.name)
    }

    @Test
    fun `test invalid date input`() {
        val userId = 555L
        sendText(userId, "user", "/start")
        sendCallback(userId, "user", "lang_en")
        
        sendText(userId, "user", Strings.get(StringKey.CREATE_GAME_BUTTON, Language.EN))
        sendText(userId, "user", "Invalid Date Game")
        
        val pastDate = LocalDate.now().minusDays(1)
        val dateStr = "${pastDate.dayOfMonth}.${pastDate.monthValue}.${pastDate.year}"
        sendText(userId, "user", dateStr)
        
        assertLastMessageContains(userId, Strings.get(StringKey.DATE_IN_PAST_ERROR, Language.EN))
        assertTrue(SantaDB.getUserState(userId) is UserState.AwaitingStartDate)
    }

    @Test
    fun `test date too far in future`() {
        val userId = 666L
        sendText(userId, "user", "/start")
        sendCallback(userId, "user", "lang_en")

        sendText(userId, "user", Strings.get(StringKey.CREATE_GAME_BUTTON, Language.EN))
        sendText(userId, "user", "Future Game")

        val farStartDate = LocalDate.now().plusWeeks(2)
        val startDateStr = "${farStartDate.dayOfMonth}.${farStartDate.monthValue}.${farStartDate.year}"
        sendText(userId, "user", startDateStr)

        assertLastMessageContains(userId, Strings.get(StringKey.START_DATE_TOO_FAR_ERROR, Language.EN))
        assertTrue(SantaDB.getUserState(userId) is UserState.AwaitingStartDate)

        val validStartDate = LocalDate.now().plusDays(1)
        val validStartDateStr = "${validStartDate.dayOfMonth}.${validStartDate.monthValue}.${validStartDate.year}"
        sendText(userId, "user", validStartDateStr)

        val farEndDate = validStartDate.plusMonths(4)
        val endDateStr = "${farEndDate.dayOfMonth}.${farEndDate.monthValue}.${farEndDate.year}"
        sendText(userId, "user", endDateStr)

        assertLastMessageContains(userId, Strings.get(StringKey.END_DATE_TOO_FAR_ERROR, Language.EN))
        assertTrue(SantaDB.getUserState(userId) is UserState.AwaitingEndDate)
    }

    private fun sendText(userId: Long, username: String, text: String) {
        val update = makeMessageUpdate(userId, username, text)
        GameFlow.handleUpdate(mockBot, update)
    }

    private fun sendCallback(userId: Long, username: String, data: String) {
        val update = makeCallbackUpdate(userId, username, data)
        GameFlow.handleUpdate(mockBot, update)
    }

    private fun assertMessageReceived(userId: Long) {
        val last = mockBot.sentMessages.lastOrNull { it.chatId == userId }
        assertNotNull(last, "No message sent to $userId")
    }

    private fun assertLastMessageContains(userId: Long, substring: String) {
        val last = mockBot.sentMessages.lastOrNull { it.chatId == userId }
        assertNotNull(last, "No message sent to $userId")
        assertTrue(last!!.text.contains(substring, ignoreCase = true), "Expected '$substring' in '${last.text}'")
    }

    private fun makeMessageUpdate(userId: Long, username: String, text: String): Update {
        val json = """
            {
                "message": {
                    "messageId": ${System.currentTimeMillis().toInt()},
                    "from": {
                        "id": $userId,
                        "isBot": false,
                        "firstName": "$username",
                        "userName": "$username",
                        "languageCode": "en"
                    },
                    "chat": {
                        "id": $userId,
                        "type": "private"
                    },
                    "date": ${System.currentTimeMillis() / 1000},
                    "text": "$text"
                }
            }
        """
        return gson.fromJson(json, Update::class.java)
    }

    private fun makeCallbackUpdate(userId: Long, username: String, data: String): Update {
        val json = """
            {
                "callbackQuery": {
                    "id": "cb_${System.currentTimeMillis()}",
                    "from": {
                        "id": $userId,
                        "isBot": false,
                        "firstName": "$username",
                        "userName": "$username",
                        "languageCode": "en"
                    },
                    "message": {
                        "messageId": 123,
                        "chat": {
                            "id": $userId,
                            "type": "private"
                        },
                        "date": ${System.currentTimeMillis() / 1000},
                        "text": "Previous message"
                    },
                    "data": "$data"
                }
            }
        """
        return gson.fromJson(json, Update::class.java)
    }

    class MockSantaBot : SantaBot("mock_token", "TestBot") {
        val sentMessages = mutableListOf<SentMessage>()

        data class SentMessage(val chatId: Long, val text: String)

        override fun sendTextMessage(chatId: Long, text: String, parseMode: String?, disableWebPagePreview: Boolean, disableNotification: Boolean, replyMarkup: ReplyKeyboard?) {
            sentMessages.add(SentMessage(chatId, text))
        }
        
        override fun deleteMessage(chatId: Long, messageId: Int) {
            // no-op
        }
        
        override fun answerCallbackQuery(callbackQueryId: String, text: String?, showAlert: Boolean) {
            // no-op
        }
    }
}
