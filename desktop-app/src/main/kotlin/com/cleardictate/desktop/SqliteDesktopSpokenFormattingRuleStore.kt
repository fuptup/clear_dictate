package com.cleardictate.desktop

import com.cleardictate.domain.SpokenFormattingRule
import com.cleardictate.domain.SpokenFormattingSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Persists user-authored spoken formatting rules locally and exposes an immutable in-memory snapshot to active dictation pipelines.
 */
class SqliteDesktopSpokenFormattingRuleStore private constructor(private val databasePath: Path)
{
    private val mutationMutex = Mutex()

    @Volatile
    private var cachedRules: List<StoredSpokenFormattingRule>

    init
    {
        databasePath.parent?.let(Files::createDirectories)
        DriverManager.getConnection(connectionUrl()).use { connection ->
            connection.createStatement().use { statement -> statement.executeUpdate(CREATE_TABLE) }
        }
        cachedRules = readRulesFromDatabase()
    }

    /**
     * Returns the latest immutable rule snapshot without database I/O on the latency-sensitive dictation path.
     */
    fun currentRules(): List<SpokenFormattingRule>
    {
        return cachedRules.map(StoredSpokenFormattingRule::toDomainRule)
    }

    /**
     * Returns display records in deterministic spoken-phrase order.
     */
    suspend fun readAll(): List<StoredSpokenFormattingRule>
    {
        return withContext(Dispatchers.IO) { readRulesFromDatabase() }.also { rules -> cachedRules = rules }
    }

    /**
     * Inserts a new literal rule or updates the selected rule, then atomically publishes the refreshed snapshot.
     */
    suspend fun save(identifier: Long?, spokenPhrase: String, replacement: String, spacing: SpokenFormattingSpacing, consumesRecognizerPunctuation: Boolean)
    {
        val normalizedPhrase = normalizeSpokenPhrase(spokenPhrase)
        require(normalizedPhrase.isNotEmpty()) { "A spoken formatting phrase cannot be blank." }
        require(replacement.isNotEmpty()) { "A spoken formatting replacement cannot be empty." }
        mutationMutex.withLock {
            withContext(Dispatchers.IO) {
                DriverManager.getConnection(connectionUrl()).use { connection ->
                    if (identifier == null)
                    {
                        connection.prepareStatement(INSERT_RULE).use { statement ->
                            bindRule(statement, normalizedPhrase, replacement, spacing, consumesRecognizerPunctuation)
                            check(statement.executeUpdate() == 1) { "The spoken formatting rule was not stored." }
                        }
                    }
                    else
                    {
                        connection.prepareStatement(UPDATE_RULE).use { statement ->
                            bindRule(statement, normalizedPhrase, replacement, spacing, consumesRecognizerPunctuation)
                            statement.setLong(5, identifier)
                            check(statement.executeUpdate() == 1) { "The spoken formatting rule no longer exists." }
                        }
                    }
                }
                cachedRules = readRulesFromDatabase()
            }
        }
    }

    /**
     * Deletes exactly one selected custom rule and publishes the remaining snapshot.
     */
    suspend fun delete(identifier: Long)
    {
        mutationMutex.withLock {
            withContext(Dispatchers.IO) {
                DriverManager.getConnection(connectionUrl()).use { connection ->
                    connection.prepareStatement(DELETE_RULE).use { statement ->
                        statement.setLong(1, identifier)
                        check(statement.executeUpdate() == 1) { "The spoken formatting rule no longer exists." }
                    }
                }
                cachedRules = readRulesFromDatabase()
            }
        }
    }

    private fun readRulesFromDatabase(): List<StoredSpokenFormattingRule>
    {
        return DriverManager.getConnection(connectionUrl()).use { connection ->
            connection.prepareStatement(SELECT_RULES).use { statement ->
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next())
                        {
                            add(
                                StoredSpokenFormattingRule(
                                    identifier = resultSet.getLong("id"),
                                    spokenPhrase = resultSet.getString("spoken_phrase"),
                                    replacement = resultSet.getString("replacement"),
                                    spacing = SpokenFormattingSpacing.valueOf(resultSet.getString("spacing")),
                                    consumesRecognizerPunctuation = resultSet.getInt("consume_recognizer_punctuation") != 0
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun bindRule(statement: java.sql.PreparedStatement, spokenPhrase: String, replacement: String, spacing: SpokenFormattingSpacing, consumesRecognizerPunctuation: Boolean)
    {
        statement.setString(1, spokenPhrase)
        statement.setString(2, replacement)
        statement.setString(3, spacing.name)
        statement.setInt(4, if (consumesRecognizerPunctuation) 1 else 0)
    }

    private fun normalizeSpokenPhrase(spokenPhrase: String): String
    {
        return spokenPhrase.trim().replace(Regex("""\s+"""), " ")
    }

    private fun connectionUrl(): String
    {
        return "jdbc:sqlite:${databasePath.toAbsolutePath().normalize()}"
    }

    companion object
    {
        private const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS spoken_formatting_rules (
                id INTEGER PRIMARY KEY,
                spoken_phrase TEXT NOT NULL COLLATE NOCASE UNIQUE,
                replacement TEXT NOT NULL,
                spacing TEXT NOT NULL,
                consume_recognizer_punctuation INTEGER NOT NULL
            )
        """
        private const val INSERT_RULE = """
            INSERT INTO spoken_formatting_rules (spoken_phrase, replacement, spacing, consume_recognizer_punctuation)
            VALUES (?, ?, ?, ?)
        """
        private const val UPDATE_RULE = """
            UPDATE spoken_formatting_rules
            SET spoken_phrase = ?, replacement = ?, spacing = ?, consume_recognizer_punctuation = ?
            WHERE id = ?
        """
        private const val DELETE_RULE = "DELETE FROM spoken_formatting_rules WHERE id = ?"
        private const val SELECT_RULES = """
            SELECT id, spoken_phrase, replacement, spacing, consume_recognizer_punctuation
            FROM spoken_formatting_rules
            ORDER BY spoken_phrase COLLATE NOCASE, id
        """

        /**
         * Opens the custom-rule table beside retained dictation history in the existing private application database.
         */
        fun openDefault(): SqliteDesktopSpokenFormattingRuleStore
        {
            val localApplicationData = System.getenv("LOCALAPPDATA")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), "AppData", "Local")
            return SqliteDesktopSpokenFormattingRuleStore(localApplicationData.resolve("ClearDictate").resolve("dictation-history.sqlite"))
        }

        internal fun open(databasePath: Path): SqliteDesktopSpokenFormattingRuleStore
        {
            return SqliteDesktopSpokenFormattingRuleStore(databasePath)
        }
    }
}

/**
 * Represents one editable database row and converts it to the platform-independent formatting rule consumed by the pipeline.
 */
data class StoredSpokenFormattingRule(
    val identifier: Long,
    val spokenPhrase: String,
    val replacement: String,
    val spacing: SpokenFormattingSpacing,
    val consumesRecognizerPunctuation: Boolean
)
{
    fun toDomainRule(): SpokenFormattingRule
    {
        return SpokenFormattingRule(spokenPhrase, replacement, spacing, consumesRecognizerPunctuation)
    }
}
