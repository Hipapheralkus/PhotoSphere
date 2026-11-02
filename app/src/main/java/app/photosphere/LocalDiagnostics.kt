package app.photosphere

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded, private troubleshooting history. No image data, location, device identifiers or uploads. */
object LocalDiagnostics {
    private const val MAX_BYTES = 128 * 1024
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "sphere-diagnostics").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()).apply { allowCoreThreadTimeOut(true) }

    fun record(context: Context, event: String, sessionId: String? = null, fields: Map<String, Any?> = emptyMap()) {
        val app = context.applicationContext
        val time = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val values = fields.entries.take(16).map { it.key.take(48) to it.value }
        worker.execute {
            // Diagnostics must never interfere with a photograph or a completed panorama.
            runCatching {
                val directory = File(app.filesDir, "diagnostics")
                if (!directory.isDirectory && !directory.mkdirs()) return@runCatching
                val current = File(directory, "events.jsonl")
                val previous = File(directory, "events.previous.jsonl")
                val row = JSONObject().put("timeMs", time).put("elapsedMs", elapsed)
                    .put("event", event.take(80))
                    .put("appVersion", app.packageManager.getPackageInfo(app.packageName, 0).versionName)
                sessionId?.let { row.put("sessionId", it.take(128)) }
                values.forEach { (key, value) ->
                    row.put(key, when (value) {
                        null -> JSONObject.NULL
                        is Boolean, is Int, is Long -> value
                        is Double -> if (value.isFinite()) value else JSONObject.NULL
                        is Float -> if (value.isFinite()) value else JSONObject.NULL
                        else -> value.toString().take(192)
                    })
                }
                val line = (row.toString() + "\n").toByteArray(Charsets.UTF_8)
                if (current.length() + line.size > MAX_BYTES) {
                    if (previous.exists() && !previous.delete()) return@runCatching
                    if (current.exists() && !current.renameTo(previous)) return@runCatching
                }
                current.appendBytes(line)
            }
        }
    }
}
