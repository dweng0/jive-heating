package heating.app.ui.rules

import heating.app.data.AreaAlert
import heating.app.data.EnergyArea
import heating.app.data.FakeHeatingApi
import heating.app.data.RuleChip
import heating.app.data.RuleCondition
import heating.app.data.RuleHistoryEntry
import heating.app.data.RuleRequest
import heating.app.data.deleteRuleQuestion
import heating.app.data.filterRuleRows
import heating.app.data.lastSentText
import heating.app.data.ruleRows
import heating.app.data.ruleTopics
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesListTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z")

    private fun request(name: String, topic: String = "z2m/front_door", notify: String = "me", enabled: Boolean = true) = RuleRequest(
        name = name, topic = topic, conditions = listOf(RuleCondition("contact", "=", JsonPrimitive(false))), title = "front_door",
        message = "front_door: contact is false", notify = notify, enabled = enabled,
    )

    private suspend fun house() = FakeHeatingApi().apply {
        createRule(request("Front door opened", notify = "me"))
        createRule(request("Hall button", topic = "z2m/hall", notify = "everyone", enabled = false))
        createRule(request("Boiler noisy", topic = "z2m/boiler"))
        ruleList.replaceAll { if (it.name == "Boiler noisy") it.copy(pausedUntil = "2026-11-07T19:10:00Z", pausedUntilLocal = "19:10") else it }
        ruleList.replaceAll { if (it.name == "Front door opened") it.copy(lastSentAt = "2026-11-07T11:00:00Z") else it }
        areaList += EnergyArea("garage", "Garage")
        areaAlerts["garage"] = AreaAlert(3.0, "everyone", "jane", mine = true)
        historyFor["rule-1"] = listOf(
            RuleHistoryEntry("2026-11-07T11:55:00Z", "z2m/front_door", "{}", "skipped by cooldown", "skipped by cooldown"),
            RuleHistoryEntry("2026-11-07T11:00:00Z", "z2m/front_door", "{}", "sent", "sent to 1 person", 1),
        )
    }

    private suspend fun FakeHeatingApi.rows() = ruleRows(
        rules(),
        energyAreas().map { it to areaAlert(it.id) },
        now,
    )

    // BDD: Rules are managed in the app
    @Test
    fun `each rule is listed with name, topic, who it notifies, when it last sent and a switch, and area notifications with them`() = runTest {
        val rows = house().rows()
        val first = rows.first { it.name == "Front door opened" }
        assertEquals("z2m/front_door", first.topic)
        assertEquals("Me", first.notifies)
        assertEquals("Last sent 1 h ago", first.lastSent)
        assertEquals(true, first.enabled)
        val hall = rows.first { it.name == "Hall button" }
        assertEquals("Everyone", hall.notifies)
        assertEquals("Never sent", hall.lastSent)
        assertEquals(false, hall.enabled)
        // Area notifications are listed with them, with no rule to edit or switch
        val area = rows.first { it.areaId == "garage" }
        assertEquals("Garage still using power", area.name)
        assertEquals("Everyone", area.notifies)
        assertNull(area.rule)
        assertEquals(4, rows.size)
        // Rules made within the 30 days of history kept have never sent; older ones may have before that
        assertEquals("Never sent", lastSentText(null, now, createdAt = "2026-11-01T00:00:00Z"))
        assertEquals("Not sent in the last 30 days", lastSentText(null, now, createdAt = "2026-09-01T00:00:00Z"))
        assertEquals("Not sent in the last 30 days", lastSentText(null, now, createdAt = ""))
        assertEquals("Last sent 3 days ago", lastSentText("2026-11-04T11:00:00Z", now, createdAt = "2026-09-01T00:00:00Z"))
    }

    // BDD: Rules are managed in the app
    @Test
    fun `rules are filtered with chips and a paused rule shows when it resumes`() = runTest {
        val rows = house().rows()
        fun names(vararg chips: RuleChip, topic: String? = null) = filterRuleRows(rows, chips.toSet(), topic).map { it.name }.sorted()
        assertEquals(listOf("Boiler noisy", "Front door opened", "Garage still using power"), names(RuleChip.ON))
        assertEquals(listOf("Hall button"), names(RuleChip.OFF))
        assertEquals(listOf("Boiler noisy"), names(RuleChip.PAUSED))
        assertEquals(listOf("Boiler noisy", "Front door opened"), names(RuleChip.ME))
        assertEquals(listOf("Garage still using power", "Hall button"), names(RuleChip.EVERYONE))
        // Topic picks one of the rules' own topics, like the web: exact, and area notifications (no topic) drop out
        assertEquals(listOf("z2m/boiler", "z2m/front_door", "z2m/hall"), ruleTopics(rows))
        assertEquals(listOf("Hall button"), names(topic = "z2m/hall"))
        assertEquals(emptyList(), names(topic = "hall"))
        assertEquals(listOf("Front door opened"), names(RuleChip.ON, RuleChip.ME, topic = "z2m/front_door"))
        assertEquals(listOf("Front door opened"), names(RuleChip.ON, topic = "z2m/front_door"))
        assertEquals("Paused until 19:10", rows.first { it.name == "Boiler noisy" }.paused)
    }

    // BDD: Rules are managed in the app
    @Test
    fun `switching, resuming and deleting go through the api and deleting asks first`() = runTest {
        val api = house()
        assertEquals("Delete Front door opened?", deleteRuleQuestion("Front door opened"))
        val rule = api.rules().first { it.name == "Hall button" }
        assertTrue(api.updateRule(rule.id, request("Hall button", "z2m/hall", "everyone", enabled = true)).enabled)
        val paused = api.rules().first { it.name == "Boiler noisy" }
        assertEquals("19:10", paused.pausedUntilLocal)
        assertNull(api.resumeRule(paused.id).pausedUntil)
        api.deleteRule(rule.id)
        assertEquals(2, api.rules().size)
        assertEquals(listOf("skipped by cooldown", "sent to 1 person"), api.ruleHistory("rule-1", 10).map { it.text })
        assertEquals(1, api.ruleHistory("rule-1", 1).size)
    }
}
