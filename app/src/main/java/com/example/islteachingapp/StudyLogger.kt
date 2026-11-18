package com.example.islteachingapp

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Lightweight, offline research logging for the WIP pilot.
 * Stores derived metadata only: no images, video frames, or hand-landmark sequences.
 */
object StudyLogger {
    private const val PREFS = "isl_trace_study"
    private const val KEY_PARTICIPANT = "participant_id"
    private const val KEY_SESSION = "session_id"
    private const val KEY_START_ISO = "session_start_iso"
    private const val KEY_START_EPOCH = "session_start_epoch"
    private const val KEY_FOREGROUND_MS = "foreground_ms"
    private const val KEY_SEGMENT_START = "foreground_segment_start"
    private const val KEY_EXPERIENCE = "experience"
    private const val KEY_HAND = "preferred_hand"
    private val lock = Any()

    data class SessionInfo(val participantId: String, val sessionId: String)

    /** One random pseudonymous ID per app installation; no name or demographic details are collected. */
    fun anonymousParticipantId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString("anonymous_participant_id", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "U-" + UUID.randomUUID().toString().take(8).uppercase(Locale.US)
        prefs.edit().putString("anonymous_participant_id", generated).apply()
        return generated
    }

    fun startSession(context: Context, participantId: String, experience: String): String = synchronized(lock) {
        val app = context.applicationContext
        if (currentSession(app) != null) endSessionInternal(app, "INTERRUPTED", includeOpenSegment = true)

        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        app.getSharedPreferences("isl_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("speed", 0.5f).apply()
        prefs.edit()
            .putString(KEY_PARTICIPANT, participantId.trim())
            .putString(KEY_SESSION, sessionId)
            .putString(KEY_START_ISO, iso(now))
            .putLong(KEY_START_EPOCH, now)
            .putLong(KEY_FOREGROUND_MS, 0L)
            .putLong(KEY_SEGMENT_START, SystemClock.elapsedRealtime())
            .putString(KEY_EXPERIENCE, experience)
            .putString(KEY_HAND, "")
            .apply()

        logInteraction(app, "SESSION_STARTED", "", "")
        sessionId
    }

    /** Close a session left open by an Android process restart; do not count the dead-process gap. */
    fun recoverInterruptedSession(context: Context) = synchronized(lock) {
        val app = context.applicationContext
        if (currentSession(app) != null) {
            endSessionInternal(app, "INTERRUPTED", includeOpenSegment = false)
        }
    }

    fun currentSession(context: Context): SessionInfo? {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val participant = p.getString(KEY_PARTICIPANT, null) ?: return null
        val session = p.getString(KEY_SESSION, null) ?: return null
        return SessionInfo(participant, session)
    }

    fun currentParticipant(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PARTICIPANT, "") ?: ""

    fun setPreferredHand(context: Context, leftHanded: Boolean) {
        val value = if (leftHanded) "LEFT" else "RIGHT"
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HAND, value).apply()
        logInteraction(context, "SIGNING_HAND_SELECTED", "", value)
    }

    /** Called by Application.ActivityLifecycleCallbacks to count only foreground time. */
    fun appForegroundChanged(context: Context, foreground: Boolean) = synchronized(lock) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY_SESSION, null) == null) return@synchronized
        val now = SystemClock.elapsedRealtime()
        val segment = p.getLong(KEY_SEGMENT_START, 0L)
        if (foreground && segment == 0L) {
            p.edit().putLong(KEY_SEGMENT_START, now).apply()
        } else if (!foreground && segment != 0L) {
            val accumulated = p.getLong(KEY_FOREGROUND_MS, 0L) + (now - segment).coerceAtLeast(0L)
            p.edit().putLong(KEY_FOREGROUND_MS, accumulated).putLong(KEY_SEGMENT_START, 0L).apply()
        }
    }

    fun endSession(context: Context, status: String = "COMPLETED") = synchronized(lock) {
        endSessionInternal(context.applicationContext, status, includeOpenSegment = true)
    }

    private fun endSessionInternal(context: Context, status: String, includeOpenSegment: Boolean) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sessionId = p.getString(KEY_SESSION, null) ?: return
        val participantId = p.getString(KEY_PARTICIPANT, "") ?: ""
        val startIso = p.getString(KEY_START_ISO, "") ?: ""
        var foregroundMs = p.getLong(KEY_FOREGROUND_MS, 0L)
        val segment = p.getLong(KEY_SEGMENT_START, 0L)
        if (includeOpenSegment && segment != 0L) {
            foregroundMs += (SystemClock.elapsedRealtime() - segment).coerceAtLeast(0L)
        }
        val endEpoch = System.currentTimeMillis()
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
        appendCsv(
            context, "sessions.csv",
            "session_id,participant_id,experience,preferred_hand,start_time_utc,end_time_utc,foreground_duration_seconds,device_model,android_version,app_version,status",
            listOf(
                sessionId, participantId, p.getString(KEY_EXPERIENCE, "") ?: "",
                p.getString(KEY_HAND, "") ?: "", startIso, iso(endEpoch),
                String.format(Locale.US, "%.3f", foregroundMs / 1000.0),
                Build.MODEL ?: "unknown", Build.VERSION.RELEASE ?: "unknown", appVersion, status
            )
        )
        logInteraction(context, "SESSION_ENDED", "", status)
        p.edit()
            .remove(KEY_PARTICIPANT).remove(KEY_SESSION).remove(KEY_START_ISO)
            .remove(KEY_START_EPOCH).remove(KEY_FOREGROUND_MS).remove(KEY_SEGMENT_START)
            .remove(KEY_EXPERIENCE).remove(KEY_HAND).apply()
    }

    fun logAttempt(
        context: Context,
        taskId: String,
        word: String,
        attemptNumber: Int,
        durationMs: Long,
        endReason: String,
        passed: Boolean,
        stars: Int,
        pathOk: Boolean,
        shapeOk: Boolean,
        sideOk: Boolean,
        movedEnough: Boolean,
        orderOk: Boolean,
        seen: Float,
        pathError: Float,
        pathAllowed: Float,
        reverseError: Float,
        shapeError: Float,
        sideShare: Float,
        classifierStatus: String,
        modelTop: String?,
        targetProbability: Float?,
        confidence: Float?,
        margin: Float?,
        feedbackCode: String,
        orientation: String,
        guideSpeed: Float,
        referenceReplays: Int,
        leftHanded: Boolean
    ) = synchronized(lock) {
        val session = currentSession(context) ?: return@synchronized
        appendCsv(
            context, "attempts.csv",
            "attempt_id,task_id,participant_id,session_id,sign,attempt_number,timestamp_utc,duration_seconds,end_reason,system_verdict,trace_stars,path_ok,shape_ok,side_ok,movement_valid,order_ok,seen_share,path_error,path_allowed,reverse_error,shape_error,side_share,classifier_status,model_top,target_probability,confidence,margin,feedback_code,orientation,guide_speed,reference_replays,preferred_hand",
            listOf(
                UUID.randomUUID().toString(), taskId, session.participantId, session.sessionId, word,
                attemptNumber.toString(), iso(System.currentTimeMillis()),
                String.format(Locale.US, "%.3f", durationMs.coerceAtLeast(0L) / 1000.0), endReason,
                if (passed) "ACCEPTED" else "RETRY", stars.toString(), pathOk.toString(), shapeOk.toString(),
                sideOk.toString(), (movedEnough && orderOk && seen >= TraceScorer.MIN_SEEN).toString(),
                orderOk.toString(), number(seen), number(pathError), number(pathAllowed), number(reverseError),
                number(shapeError), number(sideShare), classifierStatus, modelTop ?: "",
                nullableNumber(targetProbability), nullableNumber(confidence), nullableNumber(margin),
                feedbackCode, orientation, number(guideSpeed), referenceReplays.toString(),
                if (leftHanded) "LEFT" else "RIGHT"
            )
        )
    }

    fun logInteraction(
        context: Context, event: String, word: String = "", value: String = "", taskId: String = ""
    ) = synchronized(lock) {
        val session = currentSession(context) ?: return@synchronized
        appendCsv(
            context, "interactions.csv",
            "event_id,participant_id,session_id,task_id,sign,event,timestamp_utc,value",
            listOf(UUID.randomUUID().toString(), session.participantId, session.sessionId, taskId, word, event,
                iso(System.currentTimeMillis()), value)
        )
    }

    /** True when session, attempt, or interaction metadata exists for researcher export. */
    fun hasResearchData(context: Context): Boolean {
        val dir = dataDirectory(context)
        return listOf("sessions.csv", "attempts.csv", "interactions.csv")
            .any { name -> File(dir, name).let { it.isFile && it.length() > 0L } }
    }

    /** Writes a ZIP to a Storage Access Framework URI selected by the researcher. */
    fun exportZip(context: Context, destination: android.net.Uri) {
        synchronized(lock) {
            val out = context.contentResolver.openOutputStream(destination)
                ?: throw IllegalStateException("Could not open the selected export location")
            ZipOutputStream(out.buffered()).use { zip ->
                val dir = dataDirectory(context)
                val names = listOf("sessions.csv", "attempts.csv", "interactions.csv")
                for (name in names) {
                    val file = File(dir, name)
                    if (file.isFile) {
                        zip.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                // Preserve the exact vocabulary-to-media mapping used for this export.
                for (assetName in listOf("signs.csv", "labels.txt")) {
                    zip.putNextEntry(ZipEntry(assetName))
                    context.assets.open(assetName).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                val guide = """ISL Trace research export
Metadata only. No camera images, videos, or hand-landmark sequences are included.
Each app installation is assigned a random pseudonymous ID. No names, consent responses, or demographic details are collected.
All timestamps are UTC. Durations are seconds unless the column says otherwise.
The archive includes signs.csv and labels.txt to identify the vocabulary and classifier labels.
CSV files:
- sessions.csv: anonymous participant ID, start/end, foreground duration, device/OS and completion status.
- attempts.csv: one row per completed trace attempt, including technique stars, technique checks, classifier status and model outputs. The legacy system_verdict column records the combined pass/retry fallback; use classifier_status as the authoritative recognition result.
- interactions.csv: reference, retry, playback-speed, orientation and session events.
""".trimIndent()
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write(guide.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun appendCsv(context: Context, name: String, header: String, values: List<String>) {
        val file = File(dataDirectory(context), name)
        file.parentFile?.mkdirs()
        val needsHeader = !file.exists() || file.length() == 0L
        FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
            if (needsHeader) writer.appendLine(header)
            writer.appendLine(csv(values))
        }
    }

    private fun dataDirectory(context: Context) = File(context.applicationContext.filesDir, "research_data")

    private fun csv(values: List<String>): String = values.joinToString(",") { value ->
        "\"${value.replace("\"", "\"\"")}\""
    }

    private fun number(value: Float): String = if (value.isFinite()) String.format(Locale.US, "%.6f", value) else ""
    private fun nullableNumber(value: Float?): String = value?.let { number(it) } ?: ""

    private fun iso(epochMs: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMs))
}
