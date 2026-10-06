package fulguris.settings

import fulguris.SDK_VERSION
import fulguris.TestApplication
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [StorageDump.parse] and the [LevelResult] parsers behind it, the boundary between
 * our page side scripts and the storage panel.
 *
 * Note that `org.json` is Android's, hence Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class StorageDumpTest {

    @Test
    fun `no payload to decode`() {
        assertThat(StorageDump.parse(null)).isNull()
        assertThat(StorageDump.parse("")).isNull()
        assertThat(StorageDump.parse("null")).isNull()

        assertThat(IdbStores.parse(null)).isNull()
        assertThat(IdbRecords.parse("")).isNull()
        assertThat(IdbValue.parse("null")).isNull()
        assertThat(CacheRequests.parse("null")).isNull()
    }

    @Test
    fun `malformed payload is no payload`() {
        assertThat(StorageDump.parse("not json")).isNull()
        assertThat(StorageDump.parse("[1,2,3]")).isNull()

        assertThat(IdbStores.parse("not json")).isNull()
        assertThat(IdbRecords.parse("[1,2,3]")).isNull()
        assertThat(IdbValue.parse("{oops")).isNull()
        assertThat(CacheRequests.parse("{oops")).isNull()
    }

    @Test
    fun `web storages and origin`() {
        val dump = StorageDump.parse(
            """{"origin":"https://example.com","local":{"a":"1","b":"two"},"session":{}}"""
        )

        assertThat(dump).isNotNull
        assertThat(dump!!.origin).isEqualTo("https://example.com")
        assertThat(dump.local).containsExactly(entry("a", "1"), entry("b", "two"))
        assertThat(dump.session).isEmpty()

        // A dump missing the other sections still reads: nothing said about them means unavailable
        assertThat(dump.idbSupported).isFalse
        assertThat(dump.databases).isEmpty()
        assertThat(dump.cachesSupported).isFalse
        assertThat(dump.caches).isEmpty()
    }

    @Test
    fun `indexeddb unsupported`() {
        val dump = StorageDump.parse(
            """{"origin":"","local":{},"session":{},"idb":{"supported":false,"databases":[]}}"""
        )

        assertThat(dump).isNotNull
        assertThat(dump!!.idbSupported).isFalse
        assertThat(dump.databases).isEmpty()
    }

    @Test
    fun `indexeddb and cache names only`() {
        val dump = StorageDump.parse(
            """
            {"origin":"https://example.com","local":{},"session":{},"caches":null,
             "idb":{"supported":true,"databases":["reproDb","other"]},
             "caches":{"supported":true,"names":["reproCache"]}}
            """
        )

        assertThat(dump).isNotNull
        assertThat(dump!!.idbSupported).isTrue
        // Names are all the panel gets: what is inside is read when the user opens one
        assertThat(dump.databases).containsExactly("reproDb", "other")
        assertThat(dump.cachesSupported).isTrue
        assertThat(dump.caches).containsExactly("reproCache")
    }

    @Test
    fun `sections claiming support without content`() {
        val dump = StorageDump.parse(
            """{"origin":"","local":{},"session":{},
                "idb":{"supported":true},"caches":{"supported":true}}"""
        )

        assertThat(dump!!.idbSupported).isTrue
        assertThat(dump.databases).isEmpty()
        assertThat(dump.cachesSupported).isTrue
        assertThat(dump.caches).isEmpty()
    }

    @Test
    fun `stores of a database`() {
        val result = IdbStores.parse(
            """
            {"ok":true,"stores":[
              {"name":"people","keyPath":"id","autoIncrement":false,"count":2},
              {"name":"logs","keyPath":null,"autoIncrement":true,"count":500}]}
            """
        )

        assertThat(result).isInstanceOf(LevelResult.Ready::class.java)
        val stores = (result as LevelResult.Ready<IdbStores>).value.stores
        assertThat(stores).hasSize(2)

        val people = stores[0]
        assertThat(people.name).isEqualTo("people")
        assertThat(people.keyPath).isEqualTo("id")
        assertThat(people.autoIncrement).isFalse
        assertThat(people.count).isEqualTo(2)

        // An absent key path and a null one both mean the key lives in the store's own key space
        val logs = stores[1]
        assertThat(logs.keyPath).isNull()
        assertThat(logs.autoIncrement).isTrue
        assertThat(logs.count).isEqualTo(500)
    }

    @Test
    fun `database with no store`() {
        val result = IdbStores.parse("""{"ok":true,"stores":[]}""")

        assertThat((result as LevelResult.Ready<IdbStores>).value.stores).isEmpty()
    }

    @Test
    fun `records of a store`() {
        val result = IdbRecords.parse(
            """
            {"ok":true,"count":2,"offset":0,"fields":["id","name"],"records":[
              {"key":"1","value":"{\"id\":1,\"name\":\"Ada\"}","cells":{"id":"1","name":"Ada"}},
              {"key":"2","value":"{\"id\":2,\"name\":\"Bob\"}","cells":{"id":"2","name":"Bob"}}]}
            """
        )

        assertThat(result).isInstanceOf(LevelResult.Ready::class.java)
        val records = (result as LevelResult.Ready<IdbRecords>).value
        assertThat(records.count).isEqualTo(2)
        assertThat(records.offset).isEqualTo(0)
        // The fields the page met are the columns our table is given, in the order it met them
        assertThat(records.fields).containsExactly("id", "name")
        assertThat(records.records).hasSize(2)

        val ada = records.records[0]
        assertThat(ada.key).isEqualTo("1")
        // What a page sends of a value is only what a cell shows of it: the editor reads that one
        // value again when the user opens it
        assertThat(ada.value).isEqualTo("""{"id":1,"name":"Ada"}""")
        assertThat(ada.cells).containsExactly(entry("id", "1"), entry("name", "Ada"))
    }

    @Test
    fun `field order follows the records`() {
        val result = IdbRecords.parse(
            """
            {"ok":true,"count":2,"offset":0,"fields":["pad","id"],"records":[
              {"key":"1","cells":{"pad":"x","id":"1"}},
              {"key":"2","cells":{"id":"2"}}]}
            """
        )

        val records = (result as LevelResult.Ready<IdbRecords>).value
        // Our columns come out in the order the page reported, not sorted into one of our own
        assertThat(records.fields).containsExactly("pad", "id")
        // A record with no cell for a field simply leaves that one out, the table shows it empty
        assertThat(records.records[1].cells).doesNotContainKey("pad")
    }

    @Test
    fun `records without cells show their value whole`() {
        // A value the page could not take apart has no fields to head a column with, and a payload
        // written before we had a table has no cells at all: both are shown whole, in the one column
        val result = IdbRecords.parse(
            """
            {"ok":true,"count":2,"records":[
              {"key":"1","value":"42"},
              {"key":"2","value":"[1,2]","fields":["nope"]}]}
            """
        )

        val records = (result as LevelResult.Ready<IdbRecords>).value
        assertThat(records.offset).isEqualTo(0)
        assertThat(records.fields).isEmpty()
        assertThat(records.records[0].cells).isNull()
        assertThat(records.records[0].value).isEqualTo("42")
        assertThat(records.records[1].cells).isNull()
    }

    @Test
    fun `one value read on demand`() {
        val result = IdbValue.parse("""{"ok":true,"size":17,"text":"{\n  \"name\": \"Ada\"\n}"}""")

        assertThat(result).isInstanceOf(LevelResult.Ready::class.java)
        val value = (result as LevelResult.Ready<IdbValue>).value
        assertThat(value.text).isEqualTo("{\n  \"name\": \"Ada\"\n}")
        assertThat(value.size).isEqualTo(17)
        // A value the page can write back is ours to edit
        assertThat(value.lossy).isFalse
    }

    @Test
    fun `a value we cannot write back`() {
        // A date, a blob, a cyclic object: the page hands us a placeholder, writing it back would
        // store the placeholder in place of the value
        val result = IdbValue.parse(
            """{"ok":true,"size":46,"lossy":true,
                "text":"{\"__fulgurisType\":\"Date\",\"value\":\"1970-01-01\"}"}"""
        )

        assertThat((result as LevelResult.Ready<IdbValue>).value.lossy).isTrue
    }

    @Test
    fun `a field with no value`() {
        // A record need not hold every field of the page: an empty text with a zero size is one the
        // editor opens empty, not one it says is too long
        val result = IdbValue.parse("""{"ok":true,"text":""}""")

        val value = (result as LevelResult.Ready<IdbValue>).value
        assertThat(value.text).isEmpty()
        assertThat(value.size).isEqualTo(0)
        assertThat(value.lossy).isFalse
    }

    @Test
    fun `truncated records keep the true count`() {
        val result = IdbRecords.parse(
            """{"ok":true,"count":500,"offset":480,"records":[{"key":"1","value":"1"}]}"""
        )

        val records = (result as LevelResult.Ready<IdbRecords>).value
        assertThat(records.count).isEqualTo(500)
        // Where our page starts is the page's business: the count is the store's, all of it
        assertThat(records.offset).isEqualTo(480)
        assertThat(records.records).hasSize(1)
    }

    @Test
    fun `requests of a cache`() {
        val result = CacheRequests.parse(
            """
            {"ok":true,"count":3,"urls":["https://example.com/a","https://example.com/b"]}
            """
        )

        val requests = (result as LevelResult.Ready<CacheRequests>).value
        assertThat(requests.count).isEqualTo(3)
        assertThat(requests.urls)
            .containsExactly("https://example.com/a", "https://example.com/b")
    }

    @Test
    fun `page reported failure`() {
        // A page refusing to hand over a database says why, and that is what the panel shows
        val result = IdbStores.parse("""{"ok":false,"error":"VersionError: 2"}""")

        assertThat(result).isInstanceOf(LevelResult.Failed::class.java)
        assertThat((result as LevelResult.Failed).message).isEqualTo("VersionError: 2")
    }

    @Test
    fun `page reported failure without a reason`() {
        // Our scripts report an empty message when the browser gave them nothing more to say
        val result = CacheRequests.parse("""{"ok":false,"error":""}""")

        assertThat((result as LevelResult.Failed).message).isEmpty()
    }

    @Test
    fun `failure has the priority over content`() {
        val result = IdbRecords.parse("""{"ok":false,"error":"blocked","count":9,"records":[{"key":"1"}]}""")

        assertThat(result).isInstanceOf(LevelResult.Failed::class.java)
    }

    @Test
    fun `payload without an ok flag is a failure`() {
        // The flag is what tells us the page got to the end of its script
        val result = CacheRequests.parse("""{"count":1,"urls":["https://example.com"]}""")

        assertThat(result).isInstanceOf(LevelResult.Failed::class.java)
        assertThat((result as LevelResult.Failed).message).isEmpty()
    }
}
