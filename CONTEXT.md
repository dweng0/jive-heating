# CONTEXT — home-heating

Domain glossary and code conventions. Use these words for names in code.

## Glossary

| Term | Meaning | Code |
|---|---|---|
| Valve | One SONOFF TRVZB, keyed by its Zigbee2MQTT friendly name | `valves/Valve.kt`, `ValveRegistry` |
| Room | User-created, holds 1..n valves, control grouped or independent | `rooms/Room.kt`, `RoomService` |
| Zone | Unit with mode/target/schedule/boost. Grouped room = 1 zone; independent room = 1 zone per valve | `Room.zones()`, `Zone` |
| Zone settings | Everything a zone is told to do (mode, manual target, later schedule, boost, override) | `ZoneSettings` |
| Effective target | Temperature a zone should heat to now. Holiday > boost > knob override > mode | `EffectiveTarget.of` |
| Week schedule | Per-zone `WeekSchedule` (monday..sunday → `DaySchedule(blocks)`), `Block(start "HH:mm", target)` | `rooms/Schedule.kt`, `ZoneSettings.schedule` |
| Zone control | Set a zone's mode/target and push it to its valves | `ZoneControl.setMode`, `ZoneControl.apply`, `RoomService.updateZone` |
| Heat demand | Whether the boiler should fire; published retained to `heating/demand` | (Feature: Heat demand) |
| Tick | Periodic work: schedule blocks, boost expiry, demand, polling. Called every 5 s in prod; tests call it by hand | `HeatingApp.tick()` |

## Where things go

```
src/main/kotlin/heating/
  Config.kt          env config
  HeatingApp.kt      wiring, ApiException, heatingJson, now(), tick()
  Changes.kt         ChangeNotifier: Change events for live updates
  mqtt/              MqttBus interface, InMemoryMqttBus (tests), PahoMqttBus (prod), Z2mTopics
  valves/            Valve, ValveRegistry (MQTT in), ValveCommander (MQTT out)
  rooms/             Room, Zone, ZoneSettings, EffectiveTarget, RoomService, RoomRepository
  store/             Store + SqliteStore (JSON documents by key)
  api/               Ktor module, routes, views (RoomView, ValveView)
src/test/kotlin/heating/
  testing/           TestHeating (whole service in-memory), MutableClock
  <Feature>Test.kt   one test class per BDD feature
```

New feature code goes in its own file or package (e.g. `rooms/Schedule.kt`, `heating/demand/`,
`api/BoostRoutes.kt`) so parallel agents rarely edit the same file.

## Conventions

- **The API contract is `src/main/resources/openapi.json`.** The Android app is built against it in
  parallel. Paths, request bodies and response fields must match it exactly. Do not edit it; if a
  scenario seems to need a change, implement the closest contract-compatible behaviour and note it
  in the journal. `api/Views.kt` mirrors its schemas.

- **Valve commands** only through `ValveCommander` (`heatTo`, `off`, `send`, `read`). Never publish to `/set` elsewhere.
- **Room mutations** only through `RoomService.update(id) { room -> room.copy(...) }`. It persists and emits `Change.RoomChanged`.
- **New zone behaviour**: add a field with a default to `ZoneSettings`, then teach `EffectiveTarget.of` about it.
  Defaults keep old stored JSON loading.
- **New PATCH fields**: add optional fields to `PatchRoom` in `api/RoomRoutes.kt`.
- **Errors**: throw `ApiException.badRequest/notFound/conflict("message")`. Response is `{"error":"message"}`.
- **Time**: `heating.now()` (configured time zone) from the injected clock. Never `LocalDateTime.now()`.
- **Periodic behaviour** (schedule block change, boost end, demand, polling): add a `Periodic` to
  `heating.periodic` (do not edit `tick()` itself). `ZoneControl.applyAll()` re-pushes every zone.
  Tests move `t.clock.advance(Duration.ofMinutes(n))` then call `t.app.tick()`.
- **Temperatures**: `Double`, °C, user targets 5.0–30.0, rounded to 0.5.
- **Action log**: every action gets one INFO line on `actionLog` (`ActionLog.kt`, logger `heating.actions`),
  e.g. `boost Bedroom 3 60 min to 22.0${Actor.by()}`. Routes wrap the (non-suspending) control call in
  `call.acting { ... }` so `Actor.by()` reads " by <username>". Never log tokens, cookies or passwords;
  pass client-typed text through `untrusted()`. Periodic re-sends log nothing; log only on change.
- **JSON** to MQTT: build with `buildJsonObject`; compare in tests against `t.sentTo(valve)`.

## Writing a test

**Assert the literal values from the scenario.** If the scenario says "00:00 16.0, 06:30 20.0",
the test contains `Block("00:00", 16.0)` and `Block("06:30", 20.0)`. Never compare against a
constant defined in production code (that test can never fail). If the scenario says "3 days",
advance the clock 3 days. Prefer driving the HTTP API (`t.api { }`) when the scenario talks about
a client or the API.

Reuse what exists before adding a class: `ZoneControl` for mode/target changes and pushing to
valves, `RoomService.update`/`updateZone` for room changes, `EffectiveTarget` for targets.
Do not create files a scenario does not need.

```kotlin
// BDD: <exact scenario name from BDD.md>
@Test
fun `scenario name in words`() {
    val t = TestHeating()
    t.discover("Lounge TRV 1")                       // TRVZB appears in Zigbee2MQTT
    val room = t.app.rooms.create("Lounge")
    t.app.rooms.assignValve(room.id, "Lounge TRV 1")
    t.valveState("Lounge TRV 1", """{"local_temperature":19.5}""")
    t.api { client ->                                // HTTP with the bearer token
        val rooms = client.get("/api/rooms").body<List<RoomView>>()
        assertEquals(19.5, rooms.single().current)
    }
    assertEquals(expectedJson, t.sentTo("Lounge TRV 1").last())
}
```

`t.restart()` rebuilds the service on the same database, bus and clock.
Run one class: `./gradlew test --tests 'heating.RoomsTest'`.
