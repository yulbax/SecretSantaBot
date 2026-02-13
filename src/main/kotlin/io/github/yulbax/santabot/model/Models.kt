package io.github.yulbax.santabot.model

import java.time.LocalDate
import java.util.UUID

// ============================================================================
// ENUMS
// ============================================================================

enum class GameStatus {
    CREATING,
    RECRUITING,
    IN_PROGRESS,
    FINISHED
}

enum class Language(val code: String, val nativeName: String) {
    RU("ru", "\uD83C\uDDF7\uD83C\uDDFA Русский"),
    EN("en", "\uD83C\uDDEC\uD83C\uDDE7 English"),
    CS("cs", "\uD83C\uDDE8\uD83C\uDDFF Čeština"),
    UK("uk", "\uD83C\uDDFA\uD83C\uDDE6 Українська"),
    UZ("uz", "\uD83C\uDDFA\uD83C\uDDFF O'zbekcha"),
    KK("kk", "\uD83C\uDDF0\uD83C\uDDFF Қазақша");

    companion object {
        fun fromCode(code: String?): Language = entries.find { it.code == code } ?: EN
    }
}

// ============================================================================
// DATA CLASSES
// ============================================================================

data class Player(
    val userId: Long,
    var name: String,
    val username: String? = null,
    var language: Language = Language.RU
)

data class GameInstance(
    val name: String,
    val gameId: String = UUID.randomUUID().toString(),
    var inviteCode: String? = null,
    val creatorId: Long,
    var status: GameStatus = GameStatus.CREATING,
    val participants: MutableMap<Long, Player> = mutableMapOf(),
    val wishlists: MutableMap<Long, String> = mutableMapOf(),
    val pairings: MutableMap<Long, Long> = mutableMapOf(),
    var startDate: LocalDate? = null,
    var endDate: LocalDate? = null
) {
    fun addParticipant(player: Player) {
        participants[player.userId] = player
    }

    fun start() {
        if (participants.size < 3) {
            status = GameStatus.FINISHED
            return
        }
        val ids = participants.keys.toList()
        val shuffled = generateDerangement(ids)
        ids.indices.forEach { i -> pairings[ids[i]] = shuffled[i] }
        status = GameStatus.IN_PROGRESS
    }

    private fun <T> generateDerangement(list: List<T>): List<T> {
        require(list.size >= 2) { "List must have at least 2 elements" }
        var result: MutableList<T>
        var attempts = 0
        do {
            result = list.shuffled().toMutableList()
            attempts++
            for (i in result.indices) {
                if (result[i] == list[i]) {
                    val swapIdx = if (i == result.lastIndex) 0 else i + 1
                    result[i] = result[swapIdx].also { result[swapIdx] = result[i] }
                }
            }
        } while (list.indices.any { result[it] == list[it] } && attempts < 100)
        return result
    }
}

// ============================================================================
// USER STATE (FSM)
// ============================================================================

sealed class UserState {
    data class AwaitingLanguage(val nextAction: PostLanguageAction) : UserState()
    data object AwaitingGameName : UserState()
    data class AwaitingStartDate(val gameId: String) : UserState()
    data class AwaitingEndDate(val gameId: String) : UserState()
    data class AwaitingPlayerName(val gameId: String, val isCreator: Boolean = false) : UserState()
    data class AwaitingWishlist(val gameId: String, val isCreator: Boolean = false) : UserState()
    data class AwaitingAnonymousMessage(val gameId: String) : UserState()
}

sealed class PostLanguageAction {
    data object ShowWelcome : PostLanguageAction()
    data class JoinGame(val gameId: String) : PostLanguageAction()
}
