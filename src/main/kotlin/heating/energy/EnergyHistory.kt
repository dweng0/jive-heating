package heating.energy

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.time.Instant

/**
 * One five-minute sample of the house electricity meter. There is one every five minutes while the
 * service runs, so a gap between samples is time the service was down.
 *
 * [meter] is the house meter at the time (null: none chosen), [counter] its running kWh total (null:
 * no reading, e.g. it was stale) and [kwh] what the house used since the previous sample (null: not
 * known, which the dashboard shows as missing rather than as 0 kWh). For a monitored device's sample, [room] is
 * the id of the room the plug was in when it was recorded (null: no room), so moving a plug never moves its past use.
 */
data class MeterSample(val at: Instant, val meter: String?, val counter: Double?, val kwh: Double?, val room: String? = null)

/**
 * Meter samples, in their own table of the service's SQLite file next to the room history. Monitored devices
 * (smart plugs) have their samples in a table of their own, so the house meter's stay one per five minutes.
 * Costs are never stored.
 */
class EnergyHistory(path: String) {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path").also {
        it.createStatement().use { s ->
            s.execute("CREATE TABLE IF NOT EXISTS meter_samples (at INTEGER NOT NULL, meter TEXT, counter REAL, kwh REAL)")
            s.execute("CREATE INDEX IF NOT EXISTS meter_samples_at ON meter_samples (at)")
            s.execute("CREATE TABLE IF NOT EXISTS device_samples (at INTEGER NOT NULL, meter TEXT NOT NULL, counter REAL, kwh REAL)")
            s.execute("CREATE INDEX IF NOT EXISTS device_samples_meter_at ON device_samples (meter, at)")
        }
        val hasRoom = it.createStatement().use { s ->
            s.executeQuery("PRAGMA table_info(device_samples)").use { rs -> generateSequence { if (rs.next()) rs.getString("name") else null }.any { name -> name == "room" } }
        }
        if (!hasRoom) it.createStatement().use { s -> s.execute("ALTER TABLE device_samples ADD COLUMN room TEXT") }
    }

    @Synchronized
    fun add(sample: MeterSample) {
        connection.prepareStatement("INSERT INTO meter_samples (at, meter, counter, kwh) VALUES (?, ?, ?, ?)").use { s ->
            s.setLong(1, sample.at.toEpochMilli())
            if (sample.meter == null) s.setNull(2, Types.VARCHAR) else s.setString(2, sample.meter)
            s.setNullableDouble(3, sample.counter)
            s.setNullableDouble(4, sample.kwh)
            s.executeUpdate()
        }
    }

    /** Samples with [from] <= at <= [to], oldest first. */
    @Synchronized
    fun samples(from: Instant, to: Instant): List<MeterSample> =
        connection.prepareStatement("SELECT at, meter, counter, kwh FROM meter_samples WHERE at >= ? AND at <= ? ORDER BY at").use { s ->
            s.setLong(1, from.toEpochMilli())
            s.setLong(2, to.toEpochMilli())
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(MeterSample(Instant.ofEpochMilli(rs.getLong(1)), rs.getString(2), rs.getNullableDouble(3), rs.getNullableDouble(4)))
                    }
                }
            }
        }

    /**
     * The last counter recorded for [meter], if it has been the house meter ever since (so the use
     * since then is its own); null when there is none to count on from.
     */
    @Synchronized
    fun lastCounter(meter: String): Double? {
        val (at, counter) = connection.prepareStatement(
            "SELECT at, counter FROM meter_samples WHERE meter = ? AND counter IS NOT NULL ORDER BY at DESC LIMIT 1",
        ).use { s ->
            s.setString(1, meter)
            s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getDouble(2) else return null }
        }
        val interrupted = connection.prepareStatement(
            "SELECT 1 FROM meter_samples WHERE at > ? AND (meter IS NULL OR meter <> ?) LIMIT 1",
        ).use { s ->
            s.setLong(1, at)
            s.setString(2, meter)
            s.executeQuery().use { it.next() }
        }
        return counter.takeUnless { interrupted }
    }

    /** Adds a five-minute sample of a monitored device; [MeterSample.meter] is the device, [MeterSample.room] its room then. */
    @Synchronized
    fun addDevice(sample: MeterSample) {
        connection.prepareStatement("INSERT INTO device_samples (at, meter, counter, kwh, room) VALUES (?, ?, ?, ?, ?)").use { s ->
            s.setLong(1, sample.at.toEpochMilli())
            s.setString(2, sample.meter)
            s.setNullableDouble(3, sample.counter)
            s.setNullableDouble(4, sample.kwh)
            if (sample.room == null) s.setNull(5, Types.VARCHAR) else s.setString(5, sample.room)
            s.executeUpdate()
        }
    }

    /** Samples of the monitored device [meter] with [from] <= at <= [to], oldest first. */
    @Synchronized
    fun deviceSamples(meter: String, from: Instant, to: Instant): List<MeterSample> =
        connection.prepareStatement("SELECT at, counter, kwh, room FROM device_samples WHERE meter = ? AND at >= ? AND at <= ? ORDER BY at").use { s ->
            s.setString(1, meter)
            s.setLong(2, from.toEpochMilli())
            s.setLong(3, to.toEpochMilli())
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(MeterSample(Instant.ofEpochMilli(rs.getLong(1)), meter, rs.getNullableDouble(2), rs.getNullableDouble(3), rs.getString(4)))
                    }
                }
            }
        }

    /** Samples of every monitored device recorded in [room] with [from] < at <= [to], oldest first. */
    @Synchronized
    fun roomSamples(room: String, from: Instant, to: Instant): List<MeterSample> =
        connection.prepareStatement("SELECT at, meter, counter, kwh FROM device_samples WHERE room = ? AND at > ? AND at <= ? ORDER BY at").use { s ->
            s.setString(1, room)
            s.setLong(2, from.toEpochMilli())
            s.setLong(3, to.toEpochMilli())
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(MeterSample(Instant.ofEpochMilli(rs.getLong(1)), rs.getString(2), rs.getNullableDouble(3), rs.getNullableDouble(4), room))
                    }
                }
            }
        }

    /** The last counter recorded for the monitored device [meter]; null when there is none. */
    @Synchronized
    fun lastDeviceCounter(meter: String): Double? =
        connection.prepareStatement(
            "SELECT counter FROM device_samples WHERE meter = ? AND counter IS NOT NULL ORDER BY at DESC LIMIT 1",
        ).use { s ->
            s.setString(1, meter)
            s.executeQuery().use { rs -> if (rs.next()) rs.getDouble(1) else null }
        }

    /**
     * When the monitored device [meter] went stale: the time of the first of the stale samples since its last reading,
     * null when its last sample had a reading (or it has none).
     */
    @Synchronized
    fun deviceStaleSince(meter: String): Instant? =
        connection.prepareStatement(
            """SELECT MIN(at) FROM device_samples WHERE meter = ? AND counter IS NULL
               AND at > COALESCE((SELECT MAX(at) FROM device_samples WHERE meter = ? AND counter IS NOT NULL), -1)""",
        ).use { s ->
            s.setString(1, meter)
            s.setString(2, meter)
            s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1).takeUnless { rs.wasNull() }?.let(Instant::ofEpochMilli) else null }
        }

    /**
     * Marks the stale samples of [meter] from [from] up to (not including) [to] as covered by a catch-up: their kWh
     * becomes 0 (the use is in the catch-up sample), so they no longer count as missing. Their counter stays null,
     * so they still say the plug had no reading.
     */
    @Synchronized
    fun coverDeviceStale(meter: String, from: Instant, to: Instant) {
        connection.prepareStatement("UPDATE device_samples SET kwh = 0 WHERE meter = ? AND at >= ? AND at < ? AND kwh IS NULL").use { s ->
            s.setString(1, meter)
            s.setLong(2, from.toEpochMilli())
            s.setLong(3, to.toEpochMilli())
            s.executeUpdate()
        }
    }

    /** Deletes samples older than [cutoff]. */
    @Synchronized
    fun deleteBefore(cutoff: Instant) {
        for (table in listOf("meter_samples", "device_samples")) {
            connection.prepareStatement("DELETE FROM $table WHERE at < ?").use { s ->
                s.setLong(1, cutoff.toEpochMilli())
                s.executeUpdate()
            }
        }
    }
}

private fun java.sql.PreparedStatement.setNullableDouble(i: Int, v: Double?) =
    if (v == null) setNull(i, Types.REAL) else setDouble(i, v)

private fun java.sql.ResultSet.getNullableDouble(i: Int): Double? = getDouble(i).takeUnless { wasNull() }
