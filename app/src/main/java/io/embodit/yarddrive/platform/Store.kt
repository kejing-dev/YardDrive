package io.embodit.yarddrive.platform

import android.content.Context
import io.embodit.yarddrive.domain.Gear
import io.embodit.yarddrive.domain.Mode
import io.embodit.yarddrive.domain.Outcome
import io.embodit.yarddrive.domain.RunRecord
import io.embodit.yarddrive.domain.Sample
import io.embodit.yarddrive.domain.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local persistence (design persistence): settings, demos seen, lessons passed, the last 300 runs and the last 20 replay
 * trails (10 Hz). No account, no cloud.
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("yard_drive", Context.MODE_PRIVATE)

    fun hasLang(): Boolean = prefs.contains("settings")

    var settings: Settings
        get() {
            val o = runCatching { JSONObject(prefs.getString("settings", "{}") ?: "{}") }.getOrDefault(JSONObject())
            val d = Settings()
            return Settings(
                zh = o.optBoolean("zh", d.zh),
                seat = o.optInt("seat", d.seat),
                // v0.1.6 slowed the default down; a saved 0.8 from an older version is that old default, not a choice
                speed = if (o.optInt("settingsV", 1) < 2 && o.optDouble("speed", d.speed) == 0.8) d.speed else o.optDouble("speed", d.speed),
                gap = o.optDouble("gap", d.gap),
                hints = o.optBoolean("hints", d.hints),
                topView = o.optBoolean("topView", d.topView),
                poles = o.optBoolean("poles", d.poles),
                comfort = o.optBoolean("comfort", d.comfort),
                mirrorOut = o.optInt("mirrorOut", d.mirrorOut),
                mirrorPitch = o.optInt("mirrorPitch", d.mirrorPitch),
                strict = o.optBoolean("strict", d.strict),
                gazeDot = o.optBoolean("gazeDot", d.gazeDot),
                master = o.optInt("master", d.master),
                carVol = o.optInt("carVol", d.carVol),
                cueVol = o.optInt("cueVol", d.cueVol),
                muted = o.optBoolean("muted", d.muted),
            )
        }
        set(s) {
            val o = JSONObject()
                .put("zh", s.zh).put("seat", s.seat).put("speed", s.speed).put("gap", s.gap).put("hints", s.hints).put("topView", s.topView)
                .put("poles", s.poles).put("comfort", s.comfort).put("mirrorOut", s.mirrorOut).put("mirrorPitch", s.mirrorPitch).put("strict", s.strict)
                .put("settingsV", 2).put("gazeDot", s.gazeDot).put("master", s.master).put("carVol", s.carVol).put("cueVol", s.cueVol).put("muted", s.muted)
            prefs.edit().putString("settings", o.toString()).apply()
        }

    var demosSeen: Set<Int>
        get() = ints("demos")
        set(v) = putInts("demos", v)

    var lessonsPassed: Set<Int>
        get() = ints("passed")
        set(v) = putInts("passed", v)

    var eyeAsked: Boolean
        get() = prefs.getBoolean("eyeAsked", false)
        set(v) = prefs.edit().putBoolean("eyeAsked", v).apply()

    /** How many times the eye-tracking dialog was really answered "don't allow" (R9c: after two the system stops showing it). */
    var eyeDenied: Int
        get() = prefs.getInt("eyeDenied", 0)
        set(v) = prefs.edit().putInt("eyeDenied", v).apply()

    private fun ints(k: String) = (prefs.getString(k, "") ?: "").split(',').mapNotNull { it.toIntOrNull() }.toSet()

    private fun putInts(k: String, v: Set<Int>) = prefs.edit().putString(k, v.sorted().joinToString(",")).apply()

    var records: List<RunRecord>
        get() {
            val a = runCatching { JSONArray(prefs.getString("records", "[]") ?: "[]") }.getOrDefault(JSONArray())
            return (0 until a.length()).mapNotNull { i -> runCatching { record(a.getJSONObject(i)) }.getOrNull() }
        }
        set(v) {
            val a = JSONArray()
            v.takeLast(MAX_RECORDS).forEach { a.put(json(it)) }
            prefs.edit().putString("records", a.toString()).apply()
        }

    private fun json(r: RunRecord): JSONObject {
        val hits = JSONArray()
        r.hits.forEach { (lp, cm) -> hits.put(JSONArray().put(lp).put(cm)) }
        val looks = JSONArray()
        r.looks.forEach { looks.put(it) }
        return JSONObject().put("at", r.at).put("mode", r.mode.name).put("lesson", r.lesson).put("seed", r.seed).put("diff", r.difficulty)
            .put("outcome", r.outcome.name).put("passed", r.passed).put("score", r.score).put("stops", r.stops).put("time", r.timeS)
            .put("hits", hits).put("gapR", r.gapR ?: JSONObject.NULL).put("gapL", r.gapL ?: JSONObject.NULL).put("nose", r.nose ?: JSONObject.NULL).put("looks", looks)
    }

    private fun record(o: JSONObject): RunRecord {
        val h = o.getJSONArray("hits")
        val l = o.optJSONArray("looks") ?: JSONArray()
        return RunRecord(
            o.getLong("at"), Mode.valueOf(o.getString("mode")), o.optInt("lesson"), o.optInt("seed"), o.optInt("diff"),
            Outcome.valueOf(o.getString("outcome")), o.getBoolean("passed"), o.getInt("score"), o.getInt("stops"), o.getDouble("time"),
            (0 until h.length()).map { h.getJSONArray(it).let { p -> p.getString(0) to p.getInt(1) } },
            if (o.isNull("gapR")) null else o.optDouble("gapR"), if (o.isNull("gapL")) null else o.optDouble("gapL"), if (o.isNull("nose")) null else o.optDouble("nose"),
            IntArray(l.length()) { l.getInt(it) },
        )
    }

    /** The last 20 replay trails, newest last: (x, z, psi, wheel, gear, seg, look) at 10 Hz. */
    fun saveTrail(trail: List<Sample>) {
        val all = runCatching { JSONArray(prefs.getString("trails", "[]") ?: "[]") }.getOrDefault(JSONArray())
        val a = JSONArray()
        for (s in trail) a.put(JSONArray().put(round(s.t)).put(round(s.x)).put(round(s.z)).put(round(s.psi, 4)).put(round(s.wheel, 1)).put(if (s.gear == Gear.R) 0 else 1).put(s.seg).put(s.look))
        val kept = JSONArray()
        val start = maxOf(0, all.length() - (MAX_TRAILS - 1))
        for (i in start until all.length()) kept.put(all.get(i))
        kept.put(a)
        prefs.edit().putString("trails", kept.toString()).apply()
    }

    private fun round(v: Double, k: Int = 3): Double = Math.round(v * Math.pow(10.0, k.toDouble())) / Math.pow(10.0, k.toDouble())

    fun clearScores() {
        prefs.edit().remove("records").remove("trails").remove("passed").apply()
    }

    companion object {
        const val MAX_RECORDS = 300
        const val MAX_TRAILS = 20
    }
}
