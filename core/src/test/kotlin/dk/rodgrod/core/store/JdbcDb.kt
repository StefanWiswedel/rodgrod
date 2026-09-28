package dk.rodgrod.core.store

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/** sqlite-jdbc implementation of [Db] for unit tests (mirrors the Android adapter's behaviour). */
class JdbcDb(url: String = "jdbc:sqlite::memory:") : Db {
    private val conn: Connection = DriverManager.getConnection(url)
    private var depth = 0

    private fun bind(sql: String, args: Array<out Any?>, keys: Boolean = false) =
        conn.prepareStatement(sql, if (keys) Statement.RETURN_GENERATED_KEYS else Statement.NO_GENERATED_KEYS).apply {
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> setObject(i + 1, null)
                    is Boolean -> setLong(i + 1, if (a) 1 else 0)
                    is Int -> setLong(i + 1, a.toLong())
                    is Long -> setLong(i + 1, a)
                    is Double -> setDouble(i + 1, a)
                    is Float -> setDouble(i + 1, a.toDouble())
                    is String -> setString(i + 1, a)
                    is ByteArray -> setBytes(i + 1, a)
                    else -> error("unsupported arg ${a::class}")
                }
            }
        }

    override fun exec(sql: String, vararg args: Any?) { bind(sql, args).use { it.executeUpdate() } }

    override fun insert(sql: String, vararg args: Any?): Long = bind(sql, args).use {
        it.executeUpdate()
        conn.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) } }
    }

    override fun query(sql: String, vararg args: Any?): List<Row> = bind(sql, args).use { st ->
        st.executeQuery().use { rs ->
            val md = rs.metaData
            val out = ArrayList<Row>()
            while (rs.next()) {
                val row = HashMap<String, Any?>()
                for (c in 1..md.columnCount) {
                    row[md.getColumnLabel(c)] = when (val v = rs.getObject(c)) {
                        is Int -> v.toLong()
                        is Float -> v.toDouble()
                        else -> v
                    }
                }
                out += row
            }
            out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        if (depth == 0) conn.autoCommit = false
        depth++
        try {
            val r = block()
            depth--
            if (depth == 0) { conn.commit(); conn.autoCommit = true }
            return r
        } catch (e: Throwable) {
            depth--
            if (depth == 0) { conn.rollback(); conn.autoCommit = true }
            throw e
        }
    }
}
