package io.github.yulbax.santabot.db

import io.github.yulbax.santabot.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ============================================================================
// TABLES
// ============================================================================

object PlayersTable : Table("players") {
    val odI = long("user_id")
    val name = varchar("name", 100)
    val username = varchar("username", 50).nullable()
    val language = varchar("language", 10).default("ru")
    override val primaryKey = PrimaryKey(odI)
}

object GamesTable : Table("games") {
    val gameId = varchar("game_id", 36)
    val name = varchar("name", 100)
    val inviteCode = varchar("invite_code", 10).nullable()
    val creatorId = long("creator_id").references(PlayersTable.odI)
    val status = varchar("status", 20)
    val startDate = date("start_date").nullable()
    val endDate = date("end_date").nullable()
    override val primaryKey = PrimaryKey(gameId)
}

object GameParticipantsTable : Table("game_participants") {
    val gameId = varchar("game_id", 36).references(GamesTable.gameId)
    val odI = long("user_id").references(PlayersTable.odI)
    val wishlist = text("wishlist").nullable()
    val gifteeId = long("giftee_id").references(PlayersTable.odI).nullable()
    override val primaryKey = PrimaryKey(gameId, odI)
}

object UserStatesTable : Table("user_states") {
    val odI = long("user_id").references(PlayersTable.odI)
    val stateType = varchar("state_type", 50)
    val stateData = text("state_data").nullable()
    override val primaryKey = PrimaryKey(odI)
}

object BotSettingsTable : Table("bot_settings") {
    val key = varchar("key", 50)
    val value = text("value").nullable()
    override val primaryKey = PrimaryKey(key)
}

// ============================================================================
// DATABASE FACTORY
// ============================================================================

object DatabaseFactory {
    private const val DATABASE_FILE = "santabot.db"

    fun init() {
        File("data").mkdirs()
        Database.connect("jdbc:sqlite:data/$DATABASE_FILE", "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(PlayersTable, GamesTable, GameParticipantsTable, UserStatesTable, BotSettingsTable)
        }
    }

    fun initForTests() {
        File("test_santabot.db").delete()
        Database.connect("jdbc:sqlite:test_santabot.db", "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(PlayersTable, GamesTable, GameParticipantsTable, UserStatesTable, BotSettingsTable)
        }
    }

    fun <T> dbQuery(block: () -> T): T = transaction { block() }
}

// ============================================================================
// REPOSITORY
// ============================================================================

object SantaDB {
    var userStates: ConcurrentHashMap<Long, UserState> = ConcurrentHashMap()

    fun initialize() {
        DatabaseFactory.init()
        loadUserStatesFromDb()
    }

    fun initializeForTests() {
        DatabaseFactory.initForTests()
        userStates.clear()
    }

    var botToken: String?
        get() = getSetting("bot_token")
        set(value) = setSetting("bot_token", value)

    var botUsername: String?
        get() = getSetting("bot_username")
        set(value) = setSetting("bot_username", value)

    var consoleLanguage: Language
        get() = Language.fromCode(getSetting("console_language"))
        set(value) = setSetting("console_language", value.code)

    private fun getSetting(key: String): String? = DatabaseFactory.dbQuery {
        BotSettingsTable.select(BotSettingsTable.value)
            .where { BotSettingsTable.key eq key }
            .map { it[BotSettingsTable.value] }
            .firstOrNull()
    }

    private fun setSetting(key: String, value: String?) = DatabaseFactory.dbQuery {
        BotSettingsTable.deleteWhere { BotSettingsTable.key eq key }
        if (value != null) {
            BotSettingsTable.insert {
                it[BotSettingsTable.key] = key
                it[BotSettingsTable.value] = value
            }
        }
    }

    fun registerPlayer(userId: Long, name: String, username: String?, langCode: String?): Pair<Player, Boolean> {
        return DatabaseFactory.dbQuery {
            val existing = PlayersTable.selectAll().where { PlayersTable.odI eq userId }.firstOrNull()
            if (existing != null) {
                PlayersTable.update({ PlayersTable.odI eq userId }) { it[PlayersTable.name] = name }
                rowToPlayer(existing).copy(name = name) to false
            } else {
                val language = Language.fromCode(langCode)
                PlayersTable.insert {
                    it[PlayersTable.odI] = userId
                    it[PlayersTable.name] = name
                    it[PlayersTable.username] = username
                    it[PlayersTable.language] = language.code
                }
                Player(userId, name, username, language) to true
            }
        }
    }

    fun getPlayer(userId: Long): Player? = DatabaseFactory.dbQuery {
        PlayersTable.selectAll().where { PlayersTable.odI eq userId }.map { rowToPlayer(it) }.firstOrNull()
    }

    fun setPlayerLanguage(userId: Long, language: Language) = DatabaseFactory.dbQuery {
        PlayersTable.update({ PlayersTable.odI eq userId }) { it[PlayersTable.language] = language.code }
    }

    private fun rowToPlayer(row: ResultRow) = Player(
        userId = row[PlayersTable.odI],
        name = row[PlayersTable.name],
        username = row[PlayersTable.username],
        language = Language.fromCode(row[PlayersTable.language])
    )

    fun createPendingGame(creatorId: Long, name: String): GameInstance {
        val gameId = UUID.randomUUID().toString()
        DatabaseFactory.dbQuery {
            GamesTable.insert {
                it[GamesTable.gameId] = gameId
                it[GamesTable.name] = name
                it[GamesTable.creatorId] = creatorId
                it[GamesTable.status] = GameStatus.CREATING.name
            }
        }
        return GameInstance(name = name, gameId = gameId, creatorId = creatorId, status = GameStatus.CREATING)
    }

    fun findGameById(gameId: String): GameInstance? = DatabaseFactory.dbQuery {
        GamesTable.selectAll().where { GamesTable.gameId eq gameId }.firstOrNull()?.let { rowToGameInstance(it) }
    }

    fun findGameByInviteCode(inviteCode: String): GameInstance? = DatabaseFactory.dbQuery {
        GamesTable.selectAll().where { GamesTable.inviteCode eq inviteCode }.firstOrNull()?.let { rowToGameInstance(it) }
    }

    fun findGamesForPlayer(userId: Long): List<GameInstance> = DatabaseFactory.dbQuery {
        val gameIds = GameParticipantsTable.select(GameParticipantsTable.gameId)
            .where { GameParticipantsTable.odI eq userId }
            .map { it[GameParticipantsTable.gameId] }
        if (gameIds.isEmpty()) emptyList()
        else GamesTable.selectAll().where { GamesTable.gameId inList gameIds }.map { rowToGameInstance(it) }
    }

    fun getAllGames(): List<GameInstance> = DatabaseFactory.dbQuery {
        GamesTable.selectAll().map { rowToGameInstance(it) }
    }

    data class Stats(val playerCount: Int, val activeGames: Int, val recruitingGames: Int, val finishedGames: Int)

    fun getStats(): Stats = DatabaseFactory.dbQuery {
        val playerCount = PlayersTable.selectAll().count().toInt()
        val statusCounts = GamesTable.select(GamesTable.status, GamesTable.status.count())
            .groupBy(GamesTable.status)
            .associate { it[GamesTable.status] to it[GamesTable.status.count()].toInt() }
        Stats(
            playerCount = playerCount,
            activeGames = statusCounts[GameStatus.IN_PROGRESS.name] ?: 0,
            recruitingGames = statusCounts[GameStatus.RECRUITING.name] ?: 0,
            finishedGames = statusCounts[GameStatus.FINISHED.name] ?: 0
        )
    }

    private fun rowToGameInstance(row: ResultRow): GameInstance {
        val gameId = row[GamesTable.gameId]
        val participantsData = GameParticipantsTable.selectAll()
            .where { GameParticipantsTable.gameId eq gameId }.toList()
        val participantIds = participantsData.map { it[GameParticipantsTable.odI] }

        val playersMap = if (participantIds.isNotEmpty()) {
            PlayersTable.selectAll().where { PlayersTable.odI inList participantIds }
                .associate { it[PlayersTable.odI] to rowToPlayer(it) }.toMutableMap()
        } else mutableMapOf()

        val wishlists = mutableMapOf<Long, String>()
        val pairings = mutableMapOf<Long, Long>()
        participantsData.forEach { pRow ->
            val odI = pRow[GameParticipantsTable.odI]
            pRow[GameParticipantsTable.wishlist]?.let { wishlists[odI] = it }
            pRow[GameParticipantsTable.gifteeId]?.let { pairings[odI] = it }
        }

        return GameInstance(
            name = row[GamesTable.name], gameId = gameId, inviteCode = row[GamesTable.inviteCode],
            creatorId = row[GamesTable.creatorId], status = GameStatus.valueOf(row[GamesTable.status]),
            participants = playersMap, wishlists = wishlists, pairings = pairings,
            startDate = row[GamesTable.startDate], endDate = row[GamesTable.endDate]
        )
    }

    fun generateInviteCodeForGame(game: GameInstance): String {
        val inviteCode = generateInviteCode()
        DatabaseFactory.dbQuery {
            GamesTable.update({ GamesTable.gameId eq game.gameId }) {
                it[GamesTable.inviteCode] = inviteCode
                it[GamesTable.status] = GameStatus.RECRUITING.name
            }
        }
        game.inviteCode = inviteCode
        game.status = GameStatus.RECRUITING
        return inviteCode
    }

    private fun generateInviteCode(length: Int = 6): String {
        val chars = ('A'..'Z') + ('0'..'9')
        var code: String
        do { code = (1..length).map { chars.random() }.joinToString("") } while (findGameByInviteCode(code) != null)
        return code
    }

    fun deleteGame(gameId: String) = DatabaseFactory.dbQuery {
        GameParticipantsTable.deleteWhere { GameParticipantsTable.gameId eq gameId }
        GamesTable.deleteWhere { GamesTable.gameId eq gameId }
    }

    fun addParticipant(gameId: String, player: Player) = DatabaseFactory.dbQuery {
        val exists = GameParticipantsTable.selectAll()
            .where { (GameParticipantsTable.gameId eq gameId) and (GameParticipantsTable.odI eq player.userId) }
            .count() > 0
        if (!exists) {
            GameParticipantsTable.insert {
                it[GameParticipantsTable.gameId] = gameId
                it[GameParticipantsTable.odI] = player.userId
            }
        }
    }

    fun removeParticipant(gameId: String, userId: Long) = DatabaseFactory.dbQuery {
        GameParticipantsTable.deleteWhere {
            (GameParticipantsTable.gameId eq gameId) and (GameParticipantsTable.odI eq userId)
        }
    }

    fun updateWishlist(gameId: String, userId: Long, wishlist: String?) = DatabaseFactory.dbQuery {
        GameParticipantsTable.update({
            (GameParticipantsTable.gameId eq gameId) and (GameParticipantsTable.odI eq userId)
        }) { it[GameParticipantsTable.wishlist] = wishlist }
    }

    fun updateGameDates(gameId: String, startDate: LocalDate?, endDate: LocalDate?) = DatabaseFactory.dbQuery {
        GamesTable.update({ GamesTable.gameId eq gameId }) {
            if (startDate != null) it[GamesTable.startDate] = startDate
            if (endDate != null) it[GamesTable.endDate] = endDate
        }
    }

    fun updateGameStatus(gameId: String, status: GameStatus) = DatabaseFactory.dbQuery {
        GamesTable.update({ GamesTable.gameId eq gameId }) { it[GamesTable.status] = status.name }
    }

    fun savePairings(gameId: String, pairings: Map<Long, Long>) = DatabaseFactory.dbQuery {
        pairings.forEach { (giverId, receiverId) ->
            GameParticipantsTable.update({
                (GameParticipantsTable.gameId eq gameId) and (GameParticipantsTable.odI eq giverId)
            }) { it[GameParticipantsTable.gifteeId] = receiverId }
        }
    }

    private fun loadUserStatesFromDb() = DatabaseFactory.dbQuery {
        UserStatesTable.selectAll().forEach { row ->
            deserializeUserState(row[UserStatesTable.stateType], row[UserStatesTable.stateData])
                ?.let { userStates[row[UserStatesTable.odI]] = it }
        }
    }

    fun setUserState(userId: Long, state: UserState?) {
        if (state == null) {
            userStates.remove(userId)
            DatabaseFactory.dbQuery { UserStatesTable.deleteWhere { UserStatesTable.odI eq userId } }
        } else {
            userStates[userId] = state
            val (stateType, stateData) = serializeUserState(state)
            DatabaseFactory.dbQuery {
                UserStatesTable.deleteWhere { UserStatesTable.odI eq userId }
                UserStatesTable.insert {
                    it[UserStatesTable.odI] = userId
                    it[UserStatesTable.stateType] = stateType
                    it[UserStatesTable.stateData] = stateData
                }
            }
        }
    }

    fun getUserState(userId: Long): UserState? = userStates[userId]

    private fun serializeUserState(state: UserState): Pair<String, String?> = when (state) {
        is UserState.AwaitingLanguage -> "AwaitingLanguage" to when (state.nextAction) {
            is PostLanguageAction.ShowWelcome -> "ShowWelcome"
            is PostLanguageAction.JoinGame -> "JoinGame:${state.nextAction.gameId}"
        }
        is UserState.AwaitingGameName -> "AwaitingGameName" to null
        is UserState.AwaitingStartDate -> "AwaitingStartDate" to state.gameId
        is UserState.AwaitingEndDate -> "AwaitingEndDate" to state.gameId
        is UserState.AwaitingPlayerName -> "AwaitingPlayerName" to "${state.gameId}|${state.isCreator}"
        is UserState.AwaitingWishlist -> "AwaitingWishlist" to "${state.gameId}|${state.isCreator}"
        is UserState.AwaitingAnonymousMessage -> "AwaitingAnonymousMessage" to state.gameId
    }

    private fun deserializeUserState(stateType: String, stateData: String?): UserState? {
        return try {
            when (stateType) {
                "AwaitingLanguage" -> UserState.AwaitingLanguage(when {
                    stateData == "ShowWelcome" -> PostLanguageAction.ShowWelcome
                    stateData?.startsWith("JoinGame:") == true -> PostLanguageAction.JoinGame(stateData.removePrefix("JoinGame:"))
                    else -> PostLanguageAction.ShowWelcome
                })
                "AwaitingGameName" -> UserState.AwaitingGameName
                "AwaitingStartDate" -> UserState.AwaitingStartDate(stateData ?: return null)
                "AwaitingEndDate" -> UserState.AwaitingEndDate(stateData ?: return null)
                "AwaitingPlayerName" -> stateData?.split("|")?.let {
                    UserState.AwaitingPlayerName(it[0], it.getOrNull(1)?.toBoolean() ?: false)
                }
                "AwaitingWishlist" -> stateData?.split("|")?.let {
                    UserState.AwaitingWishlist(it[0], it.getOrNull(1)?.toBoolean() ?: false)
                }
                "AwaitingAnonymousMessage" -> UserState.AwaitingAnonymousMessage(stateData ?: return null)
                else -> null
            }
        } catch (_: Exception) { null }
    }
    fun saveState() {}
}
