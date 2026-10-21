package heating.store

import java.sql.Connection
import java.sql.DriverManager

/** Durable key/value storage of JSON documents. */
interface Store {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** SQLite-backed [Store]. One table, one row per document. */
class SqliteStore(path: String) : Store {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path").also {
        it.createStatement().use { s ->
            s.execute("CREATE TABLE IF NOT EXISTS documents (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }
    }

    @Synchronized
    override fun get(key: String): String? =
        connection.prepareStatement("SELECT value FROM documents WHERE key = ?").use { s ->
            s.setString(1, key)
            s.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    @Synchronized
    override fun put(key: String, value: String) {
        connection.prepareStatement(
            "INSERT INTO documents (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        ).use { s ->
            s.setString(1, key)
            s.setString(2, value)
            s.executeUpdate()
        }
    }

    fun close() = connection.close()
}
