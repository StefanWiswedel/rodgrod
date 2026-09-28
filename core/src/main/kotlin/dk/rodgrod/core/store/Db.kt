package dk.rodgrod.core.store

typealias Row = Map<String, Any?>

/**
 * The smallest SQLite surface the app needs. Implemented by Android's SQLiteDatabase (app) and sqlite-jdbc (tests),
 * so the SQL in [SqlStore] is exercised by unit tests exactly as it runs on the phone.
 * Integer columns come back as Long, real as Double, text as String, blob as ByteArray.
 */
interface Db {
    fun exec(sql: String, vararg args: Any?)
    fun insert(sql: String, vararg args: Any?): Long
    fun query(sql: String, vararg args: Any?): List<Row>
    fun <T> transaction(block: () -> T): T
}

fun Row.long(k: String): Long = (this[k] as Number).toLong()
fun Row.longOrNull(k: String): Long? = (this[k] as Number?)?.toLong()
fun Row.int(k: String): Int = (this[k] as Number).toInt()
fun Row.intOrNull(k: String): Int? = (this[k] as Number?)?.toInt()
fun Row.double(k: String): Double = (this[k] as Number).toDouble()
fun Row.str(k: String): String = this[k] as String
fun Row.strOrNull(k: String): String? = this[k] as String?
fun Row.bool(k: String): Boolean = (this[k] as Number?)?.toLong() == 1L
