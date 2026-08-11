package com.cleardictate.desktop

import com.cleardictate.desktop.inference.CapturedAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Stores successful local and phone dictations in one private SQLite database owned by this Windows profile.
 * Audio is retained as a standard mono PCM WAV BLOB so it can later be inspected or used as training data without a parallel file layout.
 */
class SqliteDesktopDictationHistory private constructor(private val databasePath: Path) : DesktopDictationHistory
{
    init
    {
        databasePath.parent?.let(Files::createDirectories)
        DriverManager.getConnection(connectionUrl()).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(CREATE_HISTORY_TABLE)
                statement.executeUpdate(CREATE_CORRECTIONS_TABLE)
            }
        }
    }

    /**
     * Writes all output that belongs to a successfully completed utterance in one SQLite transaction.
     */
    override suspend fun record(recordedAt: Instant, capturedAudio: CapturedAudio, result: DesktopDictationResult)
    {
        val wavAudio = WavAudioCodec.encodeMonoPcm16(capturedAudio)
        try
        {
            withContext(Dispatchers.IO) {
                DriverManager.getConnection(connectionUrl()).use { connection ->
                    connection.prepareStatement(INSERT_ENTRY).use { statement ->
                        statement.setString(1, recordedAt.toString())
                        statement.setBytes(2, wavAudio)
                        statement.setString(3, result.rawTranscript)
                        statement.setString(4, result.polishedTranscript)
                        statement.setLong(5, result.timing.queueMilliseconds)
                        statement.setLong(6, result.timing.recognitionMilliseconds)
                        statement.setLong(7, result.timing.rewritingMilliseconds)
                        statement.setLong(8, result.timing.totalMilliseconds)
                        check(statement.executeUpdate() == 1) { "Dictation history entry was not stored." }
                    }
                }
            }
        }
        finally
        {
            wavAudio.fill(0)
        }
    }

    /**
     * Reads lightweight retained-entry summaries newest first for history browsing without loading audio BLOBs.
     */
    suspend fun readSummaries(): List<StoredDictationSummary>
    {
        return withContext(Dispatchers.IO) {
            DriverManager.getConnection(connectionUrl()).use { connection ->
                connection.prepareStatement(SELECT_ENTRIES).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next())
                            {
                                add(
                                    StoredDictationSummary(
                                        identifier = resultSet.getLong("id"),
                                        recordedAt = Instant.parse(resultSet.getString("recorded_at_utc")),
                                        rawTranscript = resultSet.getString("raw_transcript"),
                                        polishedTranscript = resultSet.getString("polished_transcript"),
                                        correctedTranscript = resultSet.getString("corrected_transcript"),
                                        correctedAt = resultSet.getString("corrected_at_utc")?.let(Instant::parse),
                                        timing = DesktopDictationTiming(
                                            queueMilliseconds = resultSet.getLong("queue_milliseconds"),
                                            recognitionMilliseconds = resultSet.getLong("recognition_milliseconds"),
                                            rewritingMilliseconds = resultSet.getLong("rewriting_milliseconds"),
                                            totalMilliseconds = resultSet.getLong("total_milliseconds")
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Stores or replaces the human-reviewed target for one retained dictation without altering either model output.
     */
    suspend fun saveCorrection(identifier: Long, correctedTranscript: String, correctedAt: Instant = Instant.now())
    {
        val normalizedCorrection = correctedTranscript.trim()
        require(normalizedCorrection.isNotEmpty()) { "A corrected transcript cannot be empty." }
        withContext(Dispatchers.IO) {
            DriverManager.getConnection(connectionUrl()).use { connection ->
                connection.prepareStatement(UPSERT_CORRECTION).use { statement ->
                    statement.setLong(1, identifier)
                    statement.setString(2, normalizedCorrection)
                    statement.setString(3, correctedAt.toString())
                    statement.setLong(4, identifier)
                    check(statement.executeUpdate() == 1) { "The dictation correction was not stored." }
                }
            }
        }
    }

    /**
     * Loads one WAV only when the user asks to hear that record, avoiding retention of every recording in the history window's memory.
     */
    suspend fun readWavAudio(identifier: Long): ByteArray?
    {
        return withContext(Dispatchers.IO) {
            DriverManager.getConnection(connectionUrl()).use { connection ->
                connection.prepareStatement(SELECT_AUDIO).use { statement ->
                    statement.setLong(1, identifier)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) resultSet.getBytes("wav_audio") else null
                    }
                }
            }
        }
    }

    /**
     * Resolves a stable, user-private database location without relying on the current working directory.
     */
    private fun connectionUrl(): String
    {
        return "jdbc:sqlite:${databasePath.toAbsolutePath().normalize()}"
    }

    companion object
    {
        private const val CREATE_HISTORY_TABLE = """
            CREATE TABLE IF NOT EXISTS dictation_history (
                id INTEGER PRIMARY KEY,
                recorded_at_utc TEXT NOT NULL,
                wav_audio BLOB NOT NULL,
                raw_transcript TEXT NOT NULL,
                polished_transcript TEXT NOT NULL,
                queue_milliseconds INTEGER NOT NULL,
                recognition_milliseconds INTEGER NOT NULL,
                rewriting_milliseconds INTEGER NOT NULL,
                total_milliseconds INTEGER NOT NULL
            )
        """
        private const val CREATE_CORRECTIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS dictation_corrections (
                dictation_id INTEGER PRIMARY KEY,
                corrected_transcript TEXT NOT NULL,
                corrected_at_utc TEXT NOT NULL,
                FOREIGN KEY (dictation_id) REFERENCES dictation_history(id) ON DELETE CASCADE
            )
        """
        private const val INSERT_ENTRY = """
            INSERT INTO dictation_history (
                recorded_at_utc, wav_audio, raw_transcript, polished_transcript,
                queue_milliseconds, recognition_milliseconds, rewriting_milliseconds, total_milliseconds
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """
        private const val SELECT_ENTRIES = """
            SELECT history.id, history.recorded_at_utc, history.raw_transcript, history.polished_transcript,
                   correction.corrected_transcript, correction.corrected_at_utc,
                   history.queue_milliseconds, history.recognition_milliseconds, history.rewriting_milliseconds, history.total_milliseconds
            FROM dictation_history AS history
            LEFT JOIN dictation_corrections AS correction ON correction.dictation_id = history.id
            ORDER BY history.recorded_at_utc DESC, history.id DESC
        """
        private const val SELECT_AUDIO = "SELECT wav_audio FROM dictation_history WHERE id = ?"
        private const val UPSERT_CORRECTION = """
            INSERT INTO dictation_corrections (dictation_id, corrected_transcript, corrected_at_utc)
            SELECT ?, ?, ?
            WHERE EXISTS (SELECT 1 FROM dictation_history WHERE id = ?)
            ON CONFLICT(dictation_id) DO UPDATE SET
                corrected_transcript = excluded.corrected_transcript,
                corrected_at_utc = excluded.corrected_at_utc
        """

        /**
         * Opens the history database in LocalAppData, keeping dictated material outside the source tree and portable build output.
         */
        fun openDefault(): SqliteDesktopDictationHistory
        {
            val localApplicationData = System.getenv("LOCALAPPDATA")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), "AppData", "Local")
            return SqliteDesktopDictationHistory(localApplicationData.resolve("ClearDictate").resolve("dictation-history.sqlite"))
        }

        internal fun open(databasePath: Path): SqliteDesktopDictationHistory
        {
            return SqliteDesktopDictationHistory(databasePath)
        }
    }
}

/**
 * Represents the displayable fields for one retained dictation without its potentially large audio payload.
 */
data class StoredDictationSummary(
    val identifier: Long,
    val recordedAt: Instant,
    val rawTranscript: String,
    val polishedTranscript: String,
    val correctedTranscript: String?,
    val correctedAt: Instant?,
    val timing: DesktopDictationTiming
)

/**
 * Encodes captured floating-point microphone samples as a portable mono PCM16 WAV file for SQLite storage.
 */
private object WavAudioCodec
{
    private const val WAV_HEADER_BYTE_COUNT = 44
    private const val PCM_FORMAT = 1
    private const val MONO_CHANNEL_COUNT = 1
    private const val PCM16_BITS_PER_SAMPLE = 16
    private const val PCM16_BYTES_PER_SAMPLE = 2

    fun encodeMonoPcm16(capturedAudio: CapturedAudio): ByteArray
    {
        val pcmByteCount = Math.multiplyExact(capturedAudio.samples.size, PCM16_BYTES_PER_SAMPLE)
        val wavAudio = ByteBuffer.allocate(Math.addExact(WAV_HEADER_BYTE_COUNT, pcmByteCount)).order(ByteOrder.LITTLE_ENDIAN)
        wavAudio.put("RIFF".encodeToByteArray())
        wavAudio.putInt(36 + pcmByteCount)
        wavAudio.put("WAVE".encodeToByteArray())
        wavAudio.put("fmt ".encodeToByteArray())
        wavAudio.putInt(16)
        wavAudio.putShort(PCM_FORMAT.toShort())
        wavAudio.putShort(MONO_CHANNEL_COUNT.toShort())
        wavAudio.putInt(capturedAudio.sampleRate)
        wavAudio.putInt(Math.multiplyExact(capturedAudio.sampleRate, PCM16_BYTES_PER_SAMPLE))
        wavAudio.putShort(PCM16_BYTES_PER_SAMPLE.toShort())
        wavAudio.putShort(PCM16_BITS_PER_SAMPLE.toShort())
        wavAudio.put("data".encodeToByteArray())
        wavAudio.putInt(pcmByteCount)
        capturedAudio.samples.forEach { sample -> wavAudio.putShort(sample.toPcm16()) }
        return wavAudio.array()
    }

    private fun Float.toPcm16(): Short
    {
        if (this <= -1.0F)
        {
            return Short.MIN_VALUE
        }
        return (coerceIn(-1.0F, 1.0F) * Short.MAX_VALUE).roundToInt().toShort()
    }
}
