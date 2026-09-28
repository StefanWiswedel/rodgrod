package dk.rodgrod.app.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteProgram
import dk.rodgrod.core.store.Db
import dk.rodgrod.core.store.Row

/**
 * [Db] on Android's SQLite. Arguments are bound with their real types (not as strings), matching the sqlite-jdbc
 * adapter used by the unit tests. Schema creation/migration lives in the shared SqlStore.
 */
class AndroidDb(context: Context, name: String = "rodgrod.db") : Db {
    private val helper = object : SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) = Unit
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private val db: SQLiteDatabase get() = helper.writableDatabase

    private fun bind(p: SQLiteProgram, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> p.bindNull(idx)
                is Boolean -> p.bindLong(idx, if (a) 1 else 0)
                is Int -> p.bindLong(idx, a.toLong())
                is Long -> p.bindLong(idx, a)
                is Double -> p.bindDouble(idx, a)
                is Float -> p.bindDouble(idx, a.toDouble())
                is String -> p.bindString(idx, a)
                is ByteArray -> p.bindBlob(idx, a)
                else -> throw IllegalArgumentException("Unsupported SQL argument type ${a::class.java}")
            }
        }
    }

    override fun exec(sql: String, vararg args: Any?) {
        db.compileStatement(sql).use { st -> bind(st, args); st.execute() }
    }

    override fun insert(sql: String, vararg args: Any?): Long =
        db.compileStatement(sql).use { st -> bind(st, args); st.executeInsert() }

    override fun query(sql: String, vararg args: Any?): List<Row> {
        val factory = SQLiteDatabase.CursorFactory { _, driver, editTable, query ->
            bind(query, args)
            SQLiteCursor(driver, editTable, query)
        }
        return db.rawQueryWithFactory(factory, sql, null, null).use { c -> readAll(c) }
    }

    private fun readAll(c: Cursor): List<Row> {
        val out = ArrayList<Row>(c.count)
        val names = c.columnNames
        while (c.moveToNext()) {
            val row = HashMap<String, Any?>(names.size)
            for (i in names.indices) {
                row[names[i]] = when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                    else -> c.getString(i)
                }
            }
            out += row
        }
        return out
    }

    override fun <T> transaction(block: () -> T): T {
        val d = db
        d.beginTransaction()
        try {
            val r = block()
            d.setTransactionSuccessful()
            return r
        } finally {
            d.endTransaction()
        }
    }
}
