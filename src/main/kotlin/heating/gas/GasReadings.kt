package heating.gas

import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant

/**
 * The smart meter's half-hourly gas, in its own table of the service's SQLite file: one row per half-hour, keyed by
 * the instant it starts. Costs are never stored.
 */
class GasReadings(path: String) {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path").also {
        it.createStatement().use { s -> s.execute("CREATE TABLE IF NOT EXISTS gas_readings (at INTEGER PRIMARY KEY, kwh REAL NOT NULL)") }
    }

    /** Keeps each half-hour's kWh, replacing any already kept for it. */
    @Synchronized
    fun put(readings: Map<Instant, Double>) {
        if (readings.isEmpty()) return
        connection.autoCommit = false
        try {
            connection.prepareStatement("INSERT OR REPLACE INTO gas_readings (at, kwh) VALUES (?, ?)").use { s ->
                readings.forEach { (at, kwh) ->
                    s.setLong(1, at.toEpochMilli())
                    s.setDouble(2, kwh)
                    s.addBatch()
                }
                s.executeBatch()
            }
            connection.commit()
        } finally {
            connection.autoCommit = true
        }
    }

    /** The start of the last half-hour kept; null with none. */
    @Synchronized
    fun last(): Instant? = connection.createStatement().use { s ->
        s.executeQuery("SELECT MAX(at) FROM gas_readings").use { rs -> if (rs.next()) rs.getLong(1).takeUnless { rs.wasNull() }?.let(Instant::ofEpochMilli) else null }
    }

    /** When the meter's readings run to: the end of the last half-hour kept; null with none. */
    fun meteredTo(): Instant? = last()?.plus(HALF_HOUR)

    /** The half-hours starting at or after [from] and before [to], by start. */
    @Synchronized
    fun between(from: Instant, to: Instant): Map<Instant, Double> =
        connection.prepareStatement("SELECT at, kwh FROM gas_readings WHERE at >= ? AND at < ? ORDER BY at").use { s ->
            s.setLong(1, from.toEpochMilli())
            s.setLong(2, to.toEpochMilli())
            s.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(Instant.ofEpochMilli(rs.getLong(1)), rs.getDouble(2)) }
            }
        }

    companion object {
        val HALF_HOUR: Duration = Duration.ofMinutes(30)
    }
}
