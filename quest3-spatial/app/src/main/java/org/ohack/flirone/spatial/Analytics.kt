package org.ohack.flirone.spatial

import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Feature-usage analytics — LOCAL-ONLY by design.
 *
 * Every button/feature funnels through event(); counts persist to
 * filesDir/analytics.json and are readable at the debug /stats endpoint.
 * Nothing leaves the device, so the store privacy policy's "does not
 * collect" declaration stays true.
 *
 * Meta backend hookup (when wanted): Horizon Platform SDK "In-App Analytics"
 * (verified 2026-07-16, developers.meta.com/horizon/documentation/android-apps/
 * ps-in-app-analytics). Requirements before it can send anything:
 *   1. App ID from the Developer Dashboard + approved Data Use Checkup
 *      (until DUC approval it only works for test users), Store-distributed
 *      build (or a dev-org account on a sideload).
 *   2. gradle: mavenCentral() + com.meta.horizon.platform.sdk:core-kotlin:0.2.2
 *   3. init: HorizonServiceConnection.connect(APP_ID, context)
 *   4. horizon.platform.inappanalytics.InAppAnalytics (suspend fns):
 *      openSegment/closeSegment("app_session") = sessionStart/sessionEnd here,
 *      createEventCounter/incrementEventCounter(name) = event() here.
 *   5. STORE.md privacy policy must switch off the "does not collect" path.
 * Wire it inside event()/sessionStart()/sessionEnd() — call sites stay put.
 */
object Analytics {
    private val counters = LinkedHashMap<String, Long>()
    private var sessions = 0L
    private var sessionSecs = 0L
    private var firstSeenMs = 0L
    private var sessionStartMs = 0L
    private var file: File? = null
    private var log: (String) -> Unit = {}
    private var flushQueued = false
    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "analytics").apply { isDaemon = true }
    }

    @Synchronized
    fun init(f: File, logFn: (String) -> Unit) {
        if (file != null) return   // singleTask relaunch: keep in-memory state
        file = f
        log = logFn
        try {
            if (f.exists()) {
                val j = JSONObject(f.readText())
                sessions = j.optLong("sessions")
                sessionSecs = j.optLong("sessionSecs")
                firstSeenMs = j.optLong("firstSeen")
                j.optJSONObject("counters")?.let { c ->
                    for (k in c.keys()) counters[k] = c.getLong(k)
                }
            }
        } catch (e: Exception) { logFn("analytics load: $e") }
        if (firstSeenMs == 0L) firstSeenMs = System.currentTimeMillis()
    }

    /** Count one use of a feature. Safe from any thread. */
    @Synchronized
    fun event(name: String) {
        val n = (counters[name] ?: 0L) + 1L
        counters[name] = n
        log("📊 $name=$n")
        scheduleFlush()
    }

    @Synchronized
    fun sessionStart() {
        if (sessionStartMs != 0L) return
        sessionStartMs = System.currentTimeMillis()
        sessions++
        scheduleFlush()
    }

    @Synchronized
    fun sessionEnd() {
        if (sessionStartMs == 0L) return
        sessionSecs += (System.currentTimeMillis() - sessionStartMs) / 1000
        sessionStartMs = 0L
        io.execute(::flush)   // pause = last reliable moment to persist
    }

    @Synchronized
    fun statsJson(): String = JSONObject().apply {
        put("sessions", sessions)
        put("sessionSecs", sessionSecs +
            if (sessionStartMs != 0L) (System.currentTimeMillis() - sessionStartMs) / 1000 else 0)
        put("firstSeen", firstSeenMs)
        put("counters", JSONObject(counters as Map<String, Any>))
    }.toString(2)

    @Synchronized
    private fun scheduleFlush() {
        if (flushQueued) return
        flushQueued = true
        io.schedule(::flush, 10, TimeUnit.SECONDS)
    }

    private fun flush() {
        val f: File
        val json: String
        synchronized(this) {
            flushQueued = false
            f = file ?: return
            json = statsJson()
        }
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json)
            tmp.renameTo(f)
        } catch (e: Exception) { log("analytics flush: $e") }
    }
}
