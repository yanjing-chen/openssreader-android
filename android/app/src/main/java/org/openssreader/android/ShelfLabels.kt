package org.openssreader.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Sidebar labels are virtual collections; removing a label never deletes a book. */
data class ShelfLabel(val id: String, val title: String, val bookIds: Set<String>)

class ShelfLabels(context: Context) {
    private val prefs = context.getSharedPreferences("shelf-labels-v1", Context.MODE_PRIVATE)

    fun readLabels(): List<ShelfLabel> = runCatching {
        val arr = JSONArray(prefs.getString("labels", "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val members = o.optJSONArray("bookIds") ?: JSONArray()
            ShelfLabel(o.getString("id"), o.getString("title"),
                (0 until members.length()).map { members.getString(it) }.toSet())
        }
    }.getOrDefault(emptyList())

    fun readFavorites(): Set<String> = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()

    fun saveLabels(labels: List<ShelfLabel>) {
        val arr = JSONArray()
        labels.forEach { label ->
            arr.put(JSONObject().apply {
                put("id", label.id)
                put("title", label.title)
                put("bookIds", JSONArray().apply { label.bookIds.sorted().forEach { put(it) } })
            })
        }
        check(prefs.edit().putString("labels", arr.toString()).commit()) { "标签保存失败" }
    }

    fun saveFavorites(ids: Set<String>) {
        check(prefs.edit().putStringSet("favorites", ids.toSet()).commit()) { "收藏保存失败" }
    }

    companion object {
        fun validateTitle(text: String, current: List<ShelfLabel>, except: String? = null): String {
            val name = text.trim()
            require(name.isNotEmpty() && name.length <= 40 && name.none { Character.isISOControl(it) }) {
                "标签名称需要 1–40 个有效字符"
            }
            require(current.none { it.id != except && it.title.equals(name, true) }) { "标签已存在" }
            return name
        }
        fun newLabel(title: String) = ShelfLabel(UUID.randomUUID().toString(), title, emptySet())
    }
}
