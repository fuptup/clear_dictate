package com.cleardictate.desktop

import com.cleardictate.desktop.inference.CapturedAudio
import com.cleardictate.domain.TranscriptFallbackReason
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
                statement.executeUpdate(CREATE_PROCESSING_OUTCOME_TABLE)
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
                    connection.autoCommit = false
                    try
                    {
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
                        val identifier = connection.createStatement().use { statement ->
                            statement.executeQuery(SELECT_LAST_IDENTIFIER).use { resultSet ->
                                check(resultSet.next()) { "The stored dictation identifier was unavailable." }
                                resultSet.getLong(1)
                            }
                        }
                        connection.prepareStatement(INSERT_PROCESSING_OUTCOME).use { statement ->
                            statement.setLong(1, identifier)
                            statement.setInt(2, if (result.polishingOutcome.usedDeterministicFallback) 1 else 0)
                            statement.setString(3, result.polishingOutcome.fallbackReason.name)
                            check(statement.executeUpdate() == 1) { "Dictation processing outcome was not stored." }
                        }
                        connection.commit()
                    }
                    catch (throwable: Throwable)
                    {
                        connection.rollback()
                        throw throwable
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
                                        polishingOutcome = resultSet.getString("fallback_reason")?.let { fallbackReason ->
                                            DesktopPolishingOutcome(
                                                usedDeterministicFallback = resultSet.getInt("used_deterministic_fallback") != 0,
                                                fallbackReason = TranscriptFallbackReason.valueOf(fallbackReason)
                                            )
                                        },
                                        audioDurationMilliseconds = WavAudioCodec.readDurationMilliseconds(resultSet.getBytes("wav_header")),
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
        private const val CREATE_PROCESSING_OUTCOME_TABLE = """
            CREATE TABLE IF NOT EXISTS dictation_processing_outcome (
                dictation_id INTEGER PRIMARY KEY,
                used_deterministic_fallback INTEGER NOT NULL CHECK (used_deterministic_fallback IN (0, 1)),
                fallback_reason TEXT NOT NULL,
                FOREIGN KEY (dictation_id) REFERENCES dictation_history(id) ON DELETE CASCADE
            )
        """
        private const val INSERT_ENTRY = """
            INSERT INTO dictation_history (
                recorded_at_utc, wav_audio, raw_transcript, polished_transcript,
                queue_milliseconds, recognition_milliseconds, rewriting_milliseconds, total_milliseconds
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """
        private const val INSERT_PROCESSING_OUTCOME = """
            INSERT INTO dictation_processing_outcome (dictation_id, used_deterministic_fallback, fallback_reason)
            VALUES (?, ?, ?)
        """
        private const val SELECT_LAST_IDENTIFIER = "SELECT last_insert_rowid()"
        private const val SELECT_ENTRIES = """
            SELECT history.id, history.recorded_at_utc, history.raw_transcript, history.polished_transcript,
                   correction.corrected_transcript, correction.corrected_at_utc,
                   outcome.used_deterministic_fallback, outcome.fallback_reason,
                   substr(history.wav_audio, 1, 44) AS wav_header,
                   history.queue_milliseconds, history.recognition_milliseconds, history.rewriting_milliseconds, history.total_milliseconds
            FROM dictation_history AS history
            LEFT JOIN dictation_corrections AS correction ON correction.dictation_id = history.id
            LEFT JOIN dictation_processing_outcome AS outcome ON outcome.dictation_id = history.id
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
    val polishingOutcome: DesktopPolishingOutcome?,
    val audioDurationMilliseconds: Long,
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

    /**
     * Reads duration from the fixed PCM WAV header, allowing history summaries to avoid loading the retained audio payload.
     */
    fun readDurationMilliseconds(wavHeader: ByteArray): Long
    {
        require(wavHeader.size == WAV_HEADER_BYTE_COUNT) { "Stored WAV header has an invalid size." }
        require(wavHeader.copyOfRange(0, 4).contentEquals("RIFF".encodeToByteArray())) { "Stored audio is not a RIFF WAV file." }
        require(wavHeader.copyOfRange(8, 12).contentEquals("WAVE".encodeToByteArray())) { "Stored audio is not a WAVE file." }
        val header = ByteBuffer.wrap(wavHeader).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = header.getInt(24)
        val blockAlignment = header.getShort(32).toInt() and 0xFFFF
        val audioByteCount = header.getInt(40).toLong() and 0xFFFF_FFFFL
        require(sampleRate > 0 && blockAlignment > 0) { "Stored WAV timing metadata is invalid." }
        val bytesPerSecond = Math.multiplyExact(sampleRate.toLong(), blockAlignment.toLong())
        return Math.multiplyExact(audioByteCount, MILLISECONDS_PER_SECOND) / bytesPerSecond
    }

    private fun Float.toPcm16(): Short
    {
        if (this <= -1.0F)
        {
            return Short.MIN_VALUE
        }
        return (coerceIn(-1.0F, 1.0F) * Short.MAX_VALUE).roundToInt().toShort()
    }

    private const val MILLISECONDS_PER_SECOND = 1_000L
}
