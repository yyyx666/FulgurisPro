/*
 * The contents of this file are subject to the Common Public Attribution License Version 1.0.
 * (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 * https://github.com/Slion/Fulguris/blob/main/LICENSE.CPAL-1.0.
 * The License is based on the Mozilla Public License Version 1.1, but Sections 14 and 15 have been
 * added to cover use of software over a computer network and provide for limited attribution for
 * the Original Developer. In addition, Exhibit A has been modified to be consistent with Exhibit B.
 *
 * Software distributed under the License is distributed on an "AS IS" basis, WITHOUT WARRANTY OF
 * ANY KIND, either express or implied. See the License for the specific language governing rights
 * and limitations under the License.
 *
 * The Original Code is Fulguris.
 *
 * The Original Developer is the Initial Developer.
 *
 * The Initial Developer of the Original Code is Stéphane Lenclud.
 *
 * All portions of the code written by Stéphane Lenclud are Copyright © 2020 Stéphane Lenclud.
 * All Rights Reserved.
 */

package fulguris.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * What one of our drill-down scripts reported: either its payload decoded, or the page could not
 * produce one and said why.
 *
 * Deliberately not an exception: a page refuses to hand over its storage for ordinary reasons
 * (a database blocked by another tab, a store deleted meanwhile), and the panel shows that message
 * in place of the list rather than pretending the list is empty.
 */
sealed class LevelResult<out T> {
    data class Ready<T>(val value: T) : LevelResult<T>()
    data class Failed(val message: String) : LevelResult<Nothing>()

    companion object {
        /**
         * Decode one of our drill-down payloads, or null when there is nothing to decode.
         *
         * All of them carry an `ok` flag and, when it is false, an `error` message from the page;
         * [build] turns the rest of the object into our model. As in [StorageDump.parse], malformed
         * JSON counts as no payload at all: the page answered something we never wrote.
         */
        fun <T> parse(json: String?, build: (JSONObject) -> T): LevelResult<T>? {
            if (json.isNullOrEmpty() || json == "null") {
                return null
            }

            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                return null
            }

            return if (root.optBoolean("ok")) {
                Ready(build(root))
            } else {
                Failed(root.optString("error"))
            }
        }
    }
}

/**
 * One IndexedDB record as one row of our table: its key, and its value taken apart.
 *
 * [key] is the JSON text of the record's key. [cells] is its value field by field: one entry per top
 * level field, each already shortened for a cell. It is null when the value is not a plain object --
 * a number, an array, a date -- and then [value] carries that whole value, shortened, for the table's
 * single value column instead.
 *
 * Everything here is a preview of what the page holds: neither [value] nor a cell is ever written
 * back. Editing one reads that value from the page again, see `StorageFragment.valueScript`.
 */
data class IdbRecord(
    val key: String,
    val value: String,
    val cells: Map<String, String>?
)

/**
 * One value of one record, read on demand for the editor to open: its JSON text, how long it really
 * is, and whether writing it back would keep it.
 *
 * [size] counts the characters of that JSON, which is what [text] holds the beginning of when the
 * value is too long to edit here, see `StorageFragment.VALUE_EDIT_MAX_CHARS`.
 *
 * [lossy] says that [text] is not the value itself but what our page side serializer could make of
 * it: a `Date`, a `Blob`, a cyclic object and the like come back as tagged placeholders, and writing
 * one of those back would store the placeholder in place of the value. Those are shown, not edited.
 *
 * An empty [text] with a [size] of zero is a value that is not there: a field a record has no value
 * for, which the editor opens empty.
 */
data class IdbValue(
    val text: String,
    val size: Int,
    val lossy: Boolean
) {
    companion object {
        fun parse(json: String?): LevelResult<IdbValue>? = LevelResult.parse(json) { root ->
            IdbValue(
                text = root.optString("text"),
                size = root.optInt("size"),
                lossy = root.optBoolean("lossy")
            )
        }
    }
}

/**
 * One IndexedDB object store, without its records: what the store list needs.
 *
 * [count] is how many records it holds. [keyPath] tells where the key lives: inside the value when
 * set, in the store's own key space otherwise. Writing a record needs to know which, see
 * `StorageFragment.recordScript`.
 */
data class IdbStore(
    val name: String,
    val keyPath: String?,
    val autoIncrement: Boolean,
    val count: Int
)

/**
 * The object stores of one database.
 */
data class IdbStores(val stores: List<IdbStore>) {
    companion object {
        fun parse(json: String?): LevelResult<IdbStores>? = LevelResult.parse(json) { root ->
            IdbStores(stores = root.optJSONArray("stores").toStores())
        }
    }
}

/**
 * One page of one object store's records: the records we read, how many the store really holds, and
 * which fields those records have.
 *
 * [count] is the store's true count, not the size of [records]: we only ever ask the page for one
 * page at a time, so the two differ on any store holding more than a page. That count is what the
 * paging buttons are enabled from.
 *
 * [offset] is where our page starts in the store, so the dialog can say which records it is showing.
 *
 * [fields] is the union of the top level fields of the records on this page, in the order we first
 * met them: it gives the table its columns and their order. It is empty when no record here is a
 * plain object, in which case the table falls back to a single value column, see [IdbRecord.cells].
 */
data class IdbRecords(
    val count: Int,
    val offset: Int,
    val fields: List<String>,
    val records: List<IdbRecord>
) {
    companion object {
        fun parse(json: String?): LevelResult<IdbRecords>? = LevelResult.parse(json) { root ->
            IdbRecords(
                count = root.optInt("count"),
                offset = root.optInt("offset"),
                fields = root.optJSONArray("fields").toStrings(),
                records = root.optJSONArray("records").toRecords()
            )
        }
    }
}

/**
 * The requests one CacheStorage cache holds: the ones we read, and the cache's true count.
 */
data class CacheRequests(val count: Int, val urls: List<String>) {
    companion object {
        fun parse(json: String?): LevelResult<CacheRequests>? = LevelResult.parse(json) { root ->
            CacheRequests(
                count = root.optInt("count"),
                urls = root.optJSONArray("urls").toStrings()
            )
        }
    }
}

/**
 * Everything the panel itself shows about the current page: both web storages in full, then the
 * IndexedDB databases and CacheStorage caches by name only.
 *
 * Names are all the panel gets: opening a database or a cache to count what is inside is what made
 * it slow on pages holding a lot of data, so that now happens only once the user drills into that
 * database or cache. See `StorageFragment` for the scripts behind each level.
 *
 * An empty [databases] with [idbSupported] false means the page cannot be inspected that way, which
 * is not the same as a page holding no database at all.
 */
data class StorageDump(
    val origin: String,
    val local: Map<String, String>,
    val session: Map<String, String>,
    val idbSupported: Boolean,
    val databases: List<String>,
    val cachesSupported: Boolean,
    val caches: List<String>
) {
    companion object {

        /**
         * Decode the JSON payload our page side panel script produced, or null when there is none to
         * decode.
         *
         * [json] is the inner JSON string: `evaluateJavascript` wraps what the page returned in JSON,
         * so the caller has already unwrapped it once. Anything malformed is treated as no dump
         * rather than an error, the panel then says storage is unavailable.
         */
        fun parse(json: String?): StorageDump? {
            if (json.isNullOrEmpty() || json == "null") {
                return null
            }

            return try {
                val root = JSONObject(json)
                val idb = root.optJSONObject("idb")
                val caches = root.optJSONObject("caches")
                StorageDump(
                    origin = root.optString("origin"),
                    local = root.optJSONObject("local").toStringMap(),
                    session = root.optJSONObject("session").toStringMap(),
                    idbSupported = idb?.optBoolean("supported") ?: false,
                    databases = idb?.optJSONArray("databases").toStrings(),
                    cachesSupported = caches?.optBoolean("supported") ?: false,
                    caches = caches?.optJSONArray("names").toStrings()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * A storage as a map of its items, an inaccessible one just yields an empty map.
 */
private fun JSONObject?.toStringMap(): Map<String, String> {
    val json = this ?: return emptyMap()
    val items = LinkedHashMap<String, String>()
    json.keys().forEach { key -> items[key] = json.optString(key) }
    return items
}

/**
 * The names of one of our page side arrays, as text.
 */
private fun JSONArray?.toStrings(): List<String> {
    val array = this ?: return emptyList()
    return (0 until array.length()).map { array.optString(it) }
}

private fun JSONArray?.toStores(): List<IdbStore> {
    val array = this ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val store = array.optJSONObject(index) ?: return@mapNotNull null
        IdbStore(
            name = store.optString("name"),
            // An absent keyPath and a null one both mean the same thing for us
            keyPath = store.optString("keyPath").takeIf { it.isNotEmpty() && it != "null" },
            autoIncrement = store.optBoolean("autoIncrement"),
            count = store.optInt("count")
        )
    }
}

private fun JSONArray?.toRecords(): List<IdbRecord> {
    val array = this ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val record = array.optJSONObject(index) ?: return@mapNotNull null
        IdbRecord(
            key = record.optString("key"),
            // What a page sends of a value is what a cell can show of it: the editor reads the one
            // value it opens from the page again
            value = record.optString("value"),
            // Absent and null both mean the value is not a plain object, as does the missing
            // "cells" of a payload written before we had a table to fill
            cells = record.optJSONObject("cells").toCellMap()
        )
    }
}

/**
 * One record's fields as text, or null when the page did not take that record apart.
 */
private fun JSONObject?.toCellMap(): Map<String, String>? {
    val json = this ?: return null
    val cells = LinkedHashMap<String, String>()
    json.keys().forEach { field -> cells[field] = json.optString(field) }
    return cells
}
