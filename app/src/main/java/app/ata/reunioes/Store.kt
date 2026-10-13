package app.ata.reunioes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Ev(val t: Long, val kind: String?, val text: String?)

class Rec(
    val id: String,
    var title: String,
    var local: String,
    var participants: String,
    val created: Long,
    var durationMs: Long,
    var summary: String,
    var incomplete: Boolean,
    val events: MutableList<Ev>
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("title", title)
        o.put("local", local)
        o.put("participants", participants)
        o.put("created", created)
        o.put("durationMs", durationMs)
        o.put("summary", summary)
        o.put("incomplete", incomplete)
        val arr = JSONArray()
        for (e in events) {
            val eo = JSONObject()
            eo.put("t", e.t)
            if (e.kind != null) eo.put("kind", e.kind)
            if (e.text != null) eo.put("text", e.text)
            arr.put(eo)
        }
        o.put("events", arr)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Rec {
            val evs = mutableListOf<Ev>()
            val arr = o.optJSONArray("events")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    evs.add(
                        Ev(
                            e.optLong("t"),
                            if (e.has("kind")) e.getString("kind") else null,
                            if (e.has("text")) e.getString("text") else null
                        )
                    )
                }
            }
            return Rec(
                o.getString("id"), o.optString("title"), o.optString("local"),
                o.optString("participants"), o.optLong("created"), o.optLong("durationMs"),
                o.optString("summary"), o.optBoolean("incomplete"), evs
            )
        }
    }
}

object Store {
    const val BITRATE = 64000

    fun dir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val d = File(base, "gravacoes")
        d.mkdirs()
        return d
    }

    fun audioFile(ctx: Context, id: String) = File(dir(ctx), "$id.aac")
    private fun jsonFile(ctx: Context, id: String) = File(dir(ctx), "$id.json")

    @Synchronized
    fun save(ctx: Context, r: Rec) {
        val tmp = File(dir(ctx), "${r.id}.json.tmp")
        tmp.writeText(r.toJson().toString())
        val dst = jsonFile(ctx, r.id)
        if (!tmp.renameTo(dst)) {
            dst.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun load(ctx: Context, id: String): Rec? = try {
        Rec.fromJson(JSONObject(jsonFile(ctx, id).readText()))
    } catch (e: Exception) {
        null
    }

    fun list(ctx: Context): List<Rec> {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try { Rec.fromJson(JSONObject(f.readText())) } catch (e: Exception) { null }
        }.sortedByDescending { it.created }
    }

    fun delete(ctx: Context, id: String) {
        audioFile(ctx, id).delete()
        jsonFile(ctx, id).delete()
    }

    /** Gravações que ficaram incompletas (app fechado ou celular desligado) são recuperadas. */
    fun recoverOrphans(ctx: Context, activeId: String?) {
        for (r in list(ctx)) {
            if (!r.incomplete || r.id == activeId) continue
            val f = audioFile(ctx, r.id)
            if (!f.exists() || f.length() == 0L) {
                delete(ctx, r.id)
                continue
            }
            r.durationMs = f.length() * 8L * 1000L / BITRATE
            r.incomplete = false
            save(ctx, r)
        }
    }
}
