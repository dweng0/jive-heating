---
language: kotlin
framework: ktor
build_cmd: ./gradlew assemble --console=plain
test_cmd: ./gradlew test --console=plain
birth_date: 2026-10-13
---

You must only write code and tests that meet the features and scenarios of this behaviour driven development document.

System: A home heating service that controls SONOFF TRVZB radiator valves room by room, modelled on the Hive app.
It runs in Docker next to Zigbee2MQTT and Mosquitto, owns every room, schedule, boost and holiday,
commands valves over MQTT, publishes a boiler heat-demand signal, and exposes a REST + WebSocket API
for an Android app. The phone never talks to MQTT or Zigbee.

Glossary (use these words in code):
- Valve: one TRVZB, identified by its Zigbee2MQTT friendly name.
- Room: user-created, holds one or more valves. Control is "grouped" or "independent".
- Zone: the unit that has a mode, target, schedule and boost. A grouped room is one zone.
  In an independent room every valve is its own zone.
- Effective target: the temperature a zone should be heating to right now, after applying
  holiday, boost, mode and schedule.
- Heat demand: whether the boiler should be firing.
- Group: a user-named set of rooms. It holds no settings of its own; changing a group changes each
  of its rooms. A room may be in several groups.
- Sensor: a Zigbee2MQTT device that is not a TRVZB but reports `temperature` (e.g. a Zigbee
  smoke alarm). A room may have one sensor; it then measures the room instead of its valves.

Engineering rules (apply to every scenario):
- Kotlin 2, Ktor 3, JDK 17, Gradle Kotlin DSL, kotlin.test + JUnit 5. Tests live under src/test/kotlin.
- Every test for a scenario carries the marker comment `// BDD: <exact scenario name>`.
- Time comes from an injectable clock; tests never sleep.
- MQTT goes through a `MqttBus` interface. Unit tests use the in-memory fake; only scenarios in
  "Feature: Live MQTT integration" touch a real broker.
- All configuration comes from environment variables. Never commit tokens, hostnames, IPs or domains.
- Temperatures are in °C, in 0.5 steps. User targets are 5.0–30.0.
- Zigbee2MQTT topics: state `zigbee2mqtt/<valve>`, commands `zigbee2mqtt/<valve>/set`,
  reads `zigbee2mqtt/<valve>/get`, availability `zigbee2mqtt/<valve>/availability`,
  device list `zigbee2mqtt/bridge/devices`. Base topic configurable (`Z2M_BASE_TOPIC`).

    Feature: Service foundation
        As the household
        I want a running service with a health check and a token-protected API
        So that the app can safely talk to it from anywhere

        Scenario: Health endpoint reports ok without a token
            Given the service is running
            When a client sends GET /health with no Authorization header
            Then the response status is 200
            And the body is {"status":"ok"}

        Scenario: API rejects requests without a bearer token
            Given the service is configured with HEATING_API_TOKEN "test-token"
            When a client sends GET /api/rooms with no Authorization header
            Then the response status is 401

        Scenario: API rejects requests with the wrong bearer token
            Given the service is configured with HEATING_API_TOKEN "test-token"
            When a client sends GET /api/rooms with "Authorization: Bearer nope"
            Then the response status is 401

        Scenario: API accepts requests with the correct bearer token
            Given the service is configured with HEATING_API_TOKEN "test-token"
            When a client sends GET /api/rooms with "Authorization: Bearer test-token"
            Then the response status is 200
            And the body is an empty JSON array

        Scenario: Service refuses to start without an API token
            Given HEATING_API_TOKEN is not set
            When the service starts
            Then startup fails with the message "HEATING_API_TOKEN must be set"

        Scenario: OpenAPI document is served
            Given the service is running
            When a client sends GET /openapi.json with the correct token
            Then the response status is 200
            And the document lists the paths /api/rooms and /api/valves


    Feature: Valve discovery
        As the household
        I want valves paired in Zigbee2MQTT to show up in the app
        So that I can put them into rooms without touching Zigbee pairing

        Scenario: TRVZB valves are discovered from the bridge device list
            Given the bridge device list contains a device "Lounge TRV 1" with model "TRVZB"
            And the bridge device list contains a device "Hall Sensor" with model "SNZB-02"
            When the service processes the bridge device list
            Then GET /api/valves lists "Lounge TRV 1"
            And GET /api/valves does not list "Hall Sensor"

        Scenario: New valves are unassigned
            Given a discovered valve "Lounge TRV 1" that is in no room
            When a client sends GET /api/valves?unassigned=true
            Then the response lists "Lounge TRV 1" with room null

        Scenario: Valve state is ingested from its state topic
            Given a discovered valve "Lounge TRV 1"
            When the message {"local_temperature":19.5,"occupied_heating_setpoint":20,"running_state":"heat","battery":80} arrives on "zigbee2mqtt/Lounge TRV 1"
            Then the valve's current temperature is 19.5
            And its reported setpoint is 20.0
            And its running state is "heat"
            And its battery is 80

        Scenario: Partial state messages keep previously known fields
            Given valve "Lounge TRV 1" has current temperature 19.5 and battery 80
            When the message {"local_temperature":20.0} arrives on "zigbee2mqtt/Lounge TRV 1"
            Then the valve's current temperature is 20.0
            And its battery is still 80

        Scenario: Valve goes offline
            Given a discovered valve "Lounge TRV 1" that is online
            When the message {"state":"offline"} arrives on "zigbee2mqtt/Lounge TRV 1/availability"
            Then the valve is marked offline

        Scenario: Valve removed from Zigbee2MQTT is dropped from its room
            Given valve "Lounge TRV 1" is in room "Lounge"
            When a bridge device list arrives that no longer contains "Lounge TRV 1"
            Then room "Lounge" no longer contains "Lounge TRV 1"
            And the room carries the notice "Lounge TRV 1 was removed from Zigbee2MQTT"


    Feature: Rooms
        As the household
        I want to create rooms and put one or more valves in each
        So that heating matches the way we use the house

        Scenario: Create a room
            When a client sends POST /api/rooms with {"name":"Lounge"}
            Then the response status is 201
            And the room "Lounge" exists with control "grouped", mode "schedule" and no valves

        Scenario: Room names must be unique
            Given a room "Lounge" exists
            When a client sends POST /api/rooms with {"name":"lounge"}
            Then the response status is 409

        Scenario: Rename a room
            Given a room "Lounge" exists
            When a client sends PATCH /api/rooms/{id} with {"name":"Living Room"}
            Then the room is named "Living Room"

        Scenario: Assign a valve to a room
            Given a room "Lounge" exists
            And an unassigned valve "Lounge TRV 1"
            When a client sends PUT /api/rooms/{id}/valves/Lounge TRV 1
            Then room "Lounge" contains "Lounge TRV 1"
            And "Lounge TRV 1" is no longer unassigned

        Scenario: A valve can only be in one room
            Given valve "Lounge TRV 1" is in room "Lounge"
            And a room "Kitchen" exists
            When a client sends PUT /api/rooms/{kitchen}/valves/Lounge TRV 1
            Then the response status is 409

        Scenario: Remove a valve from a room
            Given valve "Lounge TRV 1" is in room "Lounge"
            When a client sends DELETE /api/rooms/{id}/valves/Lounge TRV 1
            Then "Lounge TRV 1" is unassigned
            And "Lounge TRV 1" is sent {"system_mode":"off"}

        Scenario: Delete a room with valves
            Given room "Lounge" contains valves "Lounge TRV 1" and "Lounge TRV 2"
            When a client sends DELETE /api/rooms/{id}
            Then the room no longer exists
            And both valves are unassigned
            And both valves are sent {"system_mode":"off"}

        Scenario: Newly assigned valve gets default settings
            Given a room "Lounge" exists
            When valve "Lounge TRV 1" is assigned to it
            Then "Lounge TRV 1" is sent {"open_window":"ON","frost_protection_temperature":7,"smart_temperature_control":true,"child_lock":"UNLOCK"}

        Scenario: A valve reporting the wrong frost protection is put back to 7
            Given valve "Lounge TRV 1" is in room "Lounge"
            When it reports frost_protection_temperature 29.5
            Then "Lounge TRV 1" is sent {"frost_protection_temperature":7}
            And further reports of 29.5 before it changes send nothing more
            And a valve in no room reporting 29.5 is sent nothing

        Scenario: Room list shows temperature, target, mode and heating
            Given room "Lounge" in manual mode at 21.0 with valve "Lounge TRV 1" at 19.5 running "heat"
            When a client sends GET /api/rooms
            Then "Lounge" shows current 19.5, target 21.0, mode "manual" and heating true


    Feature: Modes
        As the household
        I want schedule, manual and off modes like Hive
        So that a room can follow the plan, hold a temperature, or stay off

        Scenario: Manual mode holds the target until changed
            Given zone "Lounge" in manual mode at 21.0
            When the clock advances 3 days
            Then the effective target is still 21.0

        Scenario: Off mode uses frost protection
            When zone "Lounge" is set to mode "off"
            Then its valves are sent {"system_mode":"off"}
            And the zone does not call for heat

        Scenario: Target outside the allowed range is rejected
            When a client sets zone "Lounge" to manual 35.0
            Then the response status is 400
            And the error says "target must be between 5.0 and 30.0"

        Scenario: Target is rounded to the nearest half degree
            When a client sets zone "Lounge" to manual 20.3
            Then the effective target is 20.5


        Scenario: Knob turn in manual mode becomes the new manual target
            Given zone "Lounge" in manual mode at 20.0
            When valve "Lounge TRV 1" reports occupied_heating_setpoint 22.0 that the service did not send
            Then the zone is in manual mode at 22.0

        Scenario: Late reports of earlier commands are not knob turns
            Given zone "Lounge" was switched off and then to manual 20.0 a moment later
            When valve "Lounge TRV 1" reports occupied_heating_setpoint 7.0, then 20.0, then 7.0
            Then the zone stays in manual mode at 20.0
            And no further command is sent to the valve

        Scenario: A report soon after a command is not a knob turn
            Given the service sent "Lounge TRV 1" a setpoint of 20.0 less than a minute ago
            When the valve reports occupied_heating_setpoint 18.0
            Then the zone keeps its target
            But a report of 18.0 two minutes after the command is a knob turn

        Scenario: A knob still turning when the service answers ends at its last position
            Given zone "Lounge" in manual mode at 9.5
            When valve "Lounge TRV 1" reports occupied_heating_setpoint 26.5 that the service did not send
            And a few seconds later it reports 28.0
            Then once a minute has passed since the service's command the zone is in manual mode at 28.0

        Scenario: A knob turned past the maximum sets the maximum
            Given zone "Lounge" in manual mode at 20.0
            When valve "Lounge TRV 1" reports occupied_heating_setpoint 35.0 that the service did not send
            Then the zone is in manual mode at 30.0

        Scenario: A valve switched off at its knob turns its zone off
            Given zone "Lounge" in manual mode at 20.0
            When valve "Lounge TRV 1" reports system_mode "off" that the service did not send
            Then the zone is off
            And "Lounge" does not call for heat

        Scenario: A knob turned up on a valve whose zone is off turns it on in manual
            Given zone "Lounge" is off
            When valve "Lounge TRV 1" reports system_mode "heat" and occupied_heating_setpoint 22.0 that the service did not send
            Then the zone is in manual mode at 22.0
            And a knob report of 7.0 or below leaves an off zone off

    Feature: Schedule
        As the household
        I want a weekly schedule per room with up to six blocks a day
        So that the house is warm when we are in and cool when we are not

        Scenario: New zones get the default schedule
            When a room "Lounge" is created
            Then every day of its schedule is 00:00 16.0, 06:30 20.0, 08:30 16.0, 16:30 20.0, 22:30 16.0

        Scenario: Effective target follows the current block
            Given zone "Lounge" in schedule mode with Monday 00:00 16.0, 06:30 20.0, 08:30 16.0
            When the local time is Monday 07:15
            Then the effective target is 20.0

        Scenario: Block change sends the new target to valves
            Given zone "Lounge" in schedule mode with Monday 00:00 16.0, 06:30 20.0
            And the local time is Monday 06:29
            When the clock advances 1 minute
            Then the valves are sent {"system_mode":"heat","occupied_heating_setpoint":20.0}

        Scenario: A day may have at most six blocks
            When a client saves a Monday schedule with 7 blocks for zone "Lounge"
            Then the response status is 400
            And the error says "a day may have at most 6 blocks"

        Scenario: A day must start at midnight
            When a client saves a Monday schedule whose first block starts at 06:00
            Then the response status is 400
            And the error says "first block must start at 00:00"

        Scenario: Blocks must be in time order
            When a client saves a Monday schedule with blocks 00:00, 09:00, 08:00
            Then the response status is 400
            And the error says "blocks must be in increasing time order"

        Scenario: Copy one day to other days
            Given zone "Lounge" has a Monday schedule of 00:00 16.0, 07:00 21.0
            When a client copies Monday to Tuesday, Wednesday and Thursday
            Then Tuesday, Wednesday and Thursday are 00:00 16.0, 07:00 21.0
            And Friday is unchanged

        Scenario: Schedule uses the configured time zone
            Given HEATING_TIMEZONE is "Europe/London"
            And zone "Lounge" in schedule mode with Monday 00:00 16.0, 07:00 20.0
            When the UTC time is Monday 06:30 during British Summer Time
            Then the effective target is 20.0

        Scenario: Block inside the spring-forward gap starts when the clocks jump
            Given HEATING_TIMEZONE is "Europe/London"
            And zone "Lounge" has a block at 01:30 20.0 on the Sunday clocks go forward
            When the local time jumps from 00:59 to 02:00
            Then the effective target is 20.0

        Scenario: Block in the repeated autumn hour applies once
            Given HEATING_TIMEZONE is "Europe/London"
            And zone "Lounge" has blocks 00:00 16.0 and 01:30 20.0 on the Sunday clocks go back
            When the local time passes 01:30 for the second time
            Then the valves were sent the 20.0 target exactly once

        Scenario: Turning the valve knob overrides until the next schedule block
            Given zone "Lounge" in schedule mode where the 16:30 block is 20.0 and the 22:30 block is 16.0
            And the time is 18:00
            When valve "Lounge TRV 1" reports occupied_heating_setpoint 23.0 that the service did not send
            Then the zone's effective target is 23.0 with override "until 22:30"
            And at 22:30 the effective target returns to 16.0

    Feature: Valve settings
        As the household
        I want to change valve settings from the app
        So that I never have to fiddle with the valve itself

        Scenario: Toggle open window detection
            When a client sets open window detection off for valve "Lounge TRV 1"
            Then "Lounge TRV 1" is sent {"open_window":"OFF"}

        Scenario: Toggle child lock
            When a client turns child lock on for valve "Lounge TRV 1"
            Then "Lounge TRV 1" is sent {"child_lock":"LOCK"}

        Scenario: Toggle smart temperature control per room
            Given grouped room "Lounge" with valves "A" and "B"
            When a client turns smart temperature control off for "Lounge"
            Then "A" and "B" are sent {"smart_temperature_control":false}

        Scenario: Low battery is flagged
            When valve "Lounge TRV 1" reports battery 15
            Then GET /api/valves shows "Lounge TRV 1" with lowBattery true


    Feature: Deployment
        As the developer
        I want the service packaged for a home server
        So that it runs beside Zigbee2MQTT and Mosquitto

        Scenario: Docker image builds and runs the service
            Given the Dockerfile in the repository root
            When the image is built
            Then it runs the service on port 8080 as a non-root user

        Scenario: Example environment file documents every variable
            Then .env.example lists HEATING_API_TOKEN, MQTT_URL, Z2M_BASE_TOPIC, HEATING_TIMEZONE and HEATING_DB_PATH
            And it contains no real secrets

        Scenario: Broker login comes from the environment
            Given MQTT_USERNAME is "heating" and MQTT_PASSWORD is "broker-pass"
            When the service connects to the MQTT broker
            Then it presents that username and password

        Scenario: Broker connection is anonymous when no login is set
            Given neither MQTT_USERNAME nor MQTT_PASSWORD is set
            When the service connects to the MQTT broker
            Then it presents no username or password

        Scenario: Service refuses a broker password without a username
            Given MQTT_PASSWORD is set but MQTT_USERNAME is not
            Then the service refuses to start with "MQTT_PASSWORD needs MQTT_USERNAME"

    Feature: Grouped and independent control
        As the household
        I want a room with two radiators to act as one, or each radiator on its own
        So that big rooms and odd rooms both work

        Scenario: Grouped room shows the average temperature of online valves
            Given grouped room "Lounge" with valves at 19.5 and 21.0
            Then the room's current temperature is 20.3

        Scenario: Grouped room ignores offline valves in its temperature
            Given grouped room "Lounge" with valve "A" at 19.0 online and valve "B" at 22.0 offline
            Then the room's current temperature is 19.0

        Scenario: Grouped room sends one target to every valve
            Given grouped room "Lounge" with valves "A" and "B"
            When the room target is set to 21.0 in manual mode
            Then "A" and "B" are both sent {"system_mode":"heat","occupied_heating_setpoint":21.0}

        Scenario: Switch grouped to independent copies settings to each valve
            Given grouped room "Lounge" in manual mode at 21.0 with valves "A" and "B"
            When a client sends PATCH /api/rooms/{id} with {"control":"independent"}
            Then zone "A" is in manual mode at 21.0
            And zone "B" is in manual mode at 21.0
            And both zones have the room's schedule

        Scenario: Switch independent to grouped requires a source valve
            Given independent room "Lounge" with valves "A" and "B"
            When a client sends PATCH /api/rooms/{id} with {"control":"grouped"}
            Then the response status is 400
            And the error says "sourceValve is required"

        Scenario: Switch independent to grouped takes the source valve's settings
            Given independent room "Lounge" where zone "A" is manual 21.0 and zone "B" is off
            When a client sends PATCH /api/rooms/{id} with {"control":"grouped","sourceValve":"B"}
            Then the room is grouped and in mode "off"

        Scenario: Independent zones are controlled separately
            Given independent room "Lounge" with valves "A" and "B"
            When zone "A" is set to manual 22.0
            Then "A" is sent {"system_mode":"heat","occupied_heating_setpoint":22.0}
            And nothing is sent to "B"


    Feature: Heat demand
        As the household
        I want the boiler to fire only when a room needs heat
        So that radiators get hot water without wasting gas

        Scenario: Zone calls for heat when below target
            Given zone "Lounge" with effective target 21.0 and current temperature 20.0
            Then "Lounge" calls for heat

        Scenario: Zone stops calling for heat at target
            Given zone "Lounge" is calling for heat with effective target 21.0
            When its current temperature reaches 21.0
            Then "Lounge" does not call for heat

        Scenario: Hysteresis stops chatter just below target
            Given zone "Lounge" is not calling for heat with effective target 21.0
            When its current temperature drops to 20.8
            Then "Lounge" does not call for heat
            And when it drops to 20.5 "Lounge" calls for heat

        Scenario: A valve reporting running state heat counts as demand
            Given zone "Lounge" at target with valve "Lounge TRV 1" reporting running_state "heat"
            Then "Lounge" calls for heat

        Scenario: Offline valves never create demand
            Given valve "Lounge TRV 1" is offline and its last temperature was 15.0 with target 21.0
            Then "Lounge" does not call for heat

        Scenario: A zone whose valves cannot open does not call for heat
            Given zone "Lounge" with effective target 26.5 and current temperature 21.8
            And valve "Lounge TRV 1" reports its own setpoint 7.0 and running_state "idle"
            Then "Lounge" does not call for heat
            When the valve reports its own setpoint 26.5
            Then "Lounge" calls for heat

        Scenario: A late report of the old setpoint does not stop a zone heating
            Given zone "Lounge" at 18.0 is set to manual 21.0 and its valve confirms 21.0
            When the valve then reports its old setpoint 14.5 within a minute of the command
            Then "Lounge" keeps calling for heat
            But if the valve still reports 14.5 a minute after the command, "Lounge" stops calling

        Scenario: A valve that goes silent is treated as offline
            Given zone "Lounge" at target 21.0 with valve "Lounge TRV 1" reporting 15.0 and running_state "heat"
            When the clock advances 30 minutes and the valve sends nothing
            Then "Lounge TRV 1" is offline
            And "Lounge" does not call for heat

        Scenario: A silent valve comes back when it reports again
            Given valve "Lounge TRV 1" has been marked offline for being silent
            When the valve reports temperature 15.0
            Then "Lounge TRV 1" is online
            And "Lounge" calls for heat

        Scenario: Offline valves are reported to Uptime Kuma
            Given a Kuma push URL is configured and valves "A" and "B" are online
            When the clock advances 1 minute
            Then the push URL receives status "up"
            When valve "B" goes offline and the clock advances 1 minute
            Then the push URL receives status "down" with message "offline: B"

        Scenario: Running state is polled every five minutes
            Given valves "A" and "B" are online
            When the clock advances 5 minutes
            Then "zigbee2mqtt/A/get" and "zigbee2mqtt/B/get" are sent {"running_state":""}

        Scenario: Any calling zone turns heat demand on
            Given heat demand is off and has been off for 10 minutes
            When zone "Lounge" starts calling for heat
            Then {"demand":true} is published retained to "heating/demand"

        Scenario: Demand stays on for the minimum run time
            Given heat demand turned on 2 minutes ago
            When no zone calls for heat
            Then heat demand stays on
            And it turns off 4 minutes after it turned on

        Scenario: Demand stays off for the minimum rest time
            Given heat demand turned off 2 minutes ago
            When zone "Lounge" starts calling for heat
            Then heat demand stays off
            And it turns on 5 minutes after it turned off

        Scenario: Demand is republished on startup
            Given the service restarts while no zone calls for heat
            Then {"demand":false} is published retained to "heating/demand"

        Scenario: Heat demand is visible in the API
            Given heat demand is on
            When a client sends GET /api/status
            Then the body includes "heatDemand": true


    Feature: Live updates
        As the household
        I want both our phones to update straight away
        So that we never fight over stale screens

        Scenario: WebSocket requires the bearer token
            When a client opens /ws without the token
            Then the connection is refused with 401

        Scenario: Room change is pushed to connected clients
            Given two clients connected to /ws
            When a client sets "Lounge" to manual 21.0
            Then both clients receive a "room.updated" event for "Lounge" with target 21.0

        Scenario: Valve temperature change is pushed
            Given a client connected to /ws
            When valve "Lounge TRV 1" reports local_temperature 20.0
            Then the client receives a "room.updated" event for "Lounge" with current 20.0

        Scenario: Heat demand change is pushed
            Given a client connected to /ws
            When heat demand turns on
            Then the client receives a "status.updated" event with heatDemand true


    Feature: Boost
        As the household
        I want to boost a room for a while
        So that it warms up fast and then goes back to normal

        Scenario: Boost a zone
            Given zone "Lounge" in schedule mode with effective target 16.0
            When a client boosts "Lounge" to 22.0 for 60 minutes
            Then the effective target is 22.0
            And the zone shows boost ending 60 minutes from now

        Scenario: Boost is mirrored to the valve's timer mode
            When a client boosts "Lounge" to 22.0 for 60 minutes
            Then valve "Lounge TRV 1" is sent {"temporary_mode_select":"timer","timer_mode_target_temp":22.0,"temporary_mode_duration":60}

        Scenario: Boost ends and the zone reverts
            Given zone "Lounge" in schedule mode boosted to 22.0 for 30 minutes
            When the clock advances 30 minutes
            Then the boost is cleared
            And the effective target returns to the schedule's 16.0

        Scenario: Cancel a boost early
            Given zone "Lounge" boosted to 22.0 with 40 minutes left
            When a client cancels the boost
            Then the effective target returns to the zone's mode target
            And the valves are sent the mode target

        Scenario Outline: Boost duration must be 30 to 180 minutes in 30-minute steps
            When a client boosts "Lounge" for <minutes> minutes
            Then the response status is <status>

            Examples:
                | minutes | status |
                | 30      | 200    |
                | 180     | 200    |
                | 0       | 400    |
                | 45      | 400    |
                | 210     | 400    |

        Scenario: Boost a zone that is off
            Given zone "Lounge" in mode "off"
            When a client boosts "Lounge" to 21.0 for 30 minutes
            Then the effective target is 21.0
            And when the boost ends the zone is off again

        Scenario: Boost survives a service restart
            Given zone "Lounge" boosted to 22.0 with 40 minutes left
            When the service restarts
            Then the zone is still boosted with 40 minutes left


    Feature: Holiday
        As the household
        I want every room kept low while we are away
        So that we do not heat an empty house

        Scenario: Holiday sets every heating zone to the holiday temperature
            Given zones "Lounge" in schedule mode and "Kitchen" in manual mode
            When a client starts a holiday now until 2026-12-28T15:00 at 12.0
            Then the effective target of "Lounge" and "Kitchen" is 12.0

        Scenario: Holiday leaves zones that are off alone
            Given zone "Spare" in mode "off"
            When a holiday starts
            Then "Spare" stays off

        Scenario: Holiday ends at the return time
            Given a holiday until 2026-12-28T15:00
            When the local time reaches 2026-12-28T15:00
            Then the holiday is cleared
            And every zone returns to its mode target

        Scenario: Starting a holiday cancels active boosts
            Given zone "Lounge" is boosted
            When a holiday starts
            Then "Lounge" is no longer boosted

        Scenario: Boost is refused during a holiday
            Given a holiday is active
            When a client boosts "Lounge"
            Then the response status is 409
            And the error says "cannot boost during a holiday"

        Scenario: Holiday temperature defaults to 12
            When a client starts a holiday without a temperature
            Then the holiday temperature is 12.0

        Scenario: Holiday can start in the future
            Given the time is 2026-12-20T09:00
            When a client starts a holiday from 2026-12-21T06:00 until 2026-12-28T15:00
            Then no zone changes until 2026-12-21T06:00
            And at 2026-12-21T06:00 every heating zone's effective target is 12.0


    Feature: Persistence and recovery
        As the household
        I want nothing lost when the home server restarts
        So that the heating carries on as we left it

        Scenario: Rooms, zones and schedules survive a restart
            Given room "Lounge" with valve "Lounge TRV 1" and a custom Monday schedule
            When the service restarts with the same HEATING_DB_PATH
            Then room "Lounge", its valve and its schedule are unchanged

        Scenario: Holiday survives a restart
            Given a holiday until 2026-12-28T15:00
            When the service restarts
            Then the holiday is still active

        Scenario: Valves are resynced on startup
            Given zone "Lounge" in manual mode at 21.0 with valve "Lounge TRV 1"
            When the service starts
            Then "Lounge TRV 1" is sent {"system_mode":"heat","occupied_heating_setpoint":21.0}

        Scenario: Boost that expired while down is cleared on startup
            Given zone "Lounge" was boosted until 10:00
            When the service starts at 10:30
            Then the zone is not boosted


    Feature: Live MQTT integration
        As the developer
        I want one end-to-end check against a real broker
        So that the fake MQTT bus is not lying

        Scenario: End to end against a real Mosquitto broker
            Given a Mosquitto broker started by Testcontainers
            And the service connected to it
            When "zigbee2mqtt/bridge/devices" publishes a TRVZB "Test TRV" and the valve is assigned to room "Test"
            And room "Test" is set to manual 21.0
            Then "zigbee2mqtt/Test TRV/set" receives {"system_mode":"heat","occupied_heating_setpoint":21.0}


    Feature: Whole house
        As the household
        I want to change every room at once
        So that heating the whole house, or turning it all down, is one tap instead of one per room

        Scenario: Set every room to manual at one temperature
            Given rooms "Lounge" (schedule) and "Bedroom" (off), each with a valve
            When a client posts /api/house {"mode":"manual","target":21.0}
            Then both rooms are in manual mode at 21.0
            And each valve is sent 21.0

        Scenario: Put every room back on its schedule
            Given "Lounge" is manual at 23.0 and "Bedroom" is boosted
            When a client posts /api/house {"mode":"schedule"}
            Then both rooms are in schedule mode and the boost is cancelled

        Scenario: Turn every room off
            When a client posts /api/house {"mode":"off"}
            Then every room is off and no zone calls for heat

        Scenario: Boost every room
            When a client posts /api/house {"boost":60,"target":22.0}
            Then every zone shows a boost to 22.0 ending 60 minutes from now
            And every valve is sent timer mode for 60 minutes

        Scenario: Cancel every boost
            Given "Lounge" and "Bedroom" are boosted
            When a client sends DELETE /api/house/boost
            Then no zone has a boost

        Scenario: Independent rooms change every zone, and rooms without valves are left alone
            Given independent room "Kitchen" with valves "K1" and "K2", and room "Loft" with no valves
            When a client posts /api/house {"mode":"manual","target":20.0}
            Then zones "K1" and "K2" are manual at 20.0
            And "Loft" is unchanged

        Scenario: Whole-house changes follow the same rules as one room
            When a client posts /api/house {"mode":"manual","target":40.0}
            Then the response status is 400 and no room changed
            And during a holiday {"boost":60} is refused with 409

    Feature: Users and sign-in
        As the household admin
        I want people to sign in with their own username and password, and to manage who has access
        So that the app works from anywhere without sharing the service token

        Scenario: Setup is required while there are no users
            Given there are no users
            When a client sends GET /api/auth/status with no Authorization header
            Then the body is {"setupRequired":true}

        Scenario: First admin is created with the service token
            Given there are no users
            When a client posts /api/auth/setup with the service token, username "jane" and password "correct horse"
            Then the response status is 201 with a session token and user "jane" who is an admin
            And GET /api/auth/status returns {"setupRequired":false}

        Scenario: Setup is refused once a user exists
            Given user "jane" exists
            When a client posts /api/auth/setup with the service token, username "eve" and password "correct horse"
            Then the response status is 409

        Scenario: Setup is refused with the wrong service token
            Given there are no users
            When a client posts /api/auth/setup with service token "nope"
            Then the response status is 401

        Scenario: A user signs in and uses the API with their session token
            Given user "sam" with password "correct horse"
            When a client posts /api/auth/login with username "sam" and password "correct horse"
            Then the response has a session token
            And GET /api/rooms with that token as a bearer token returns 200
            And GET /api/auth/me returns username "sam"

        Scenario: Usernames are matched without regard to case
            Given user "Sam" with password "correct horse"
            When a client signs in as "sam" with password "correct horse"
            Then the sign-in succeeds

        Scenario: Wrong password and unknown user get the same answer
            Given user "sam" with password "correct horse"
            When a client signs in as "sam" with password "wrong horse"
            And a client signs in as "nobody" with password "wrong horse"
            Then both responses are 401 with {"error":"wrong username or password"}

        Scenario: Sign-in is throttled after five failures for one username
            Given user "sam" with password "correct horse"
            When a client fails to sign in as "sam" 5 times
            And then signs in as "sam" with the correct password
            Then the response status is 429 with a Retry-After of 60 seconds

        Scenario: The sign-in lock doubles with each further failure up to an hour
            Given "sam" is locked after 5 failures
            When the lock passes and the client fails again, repeatedly
            Then the locks are 60, 120, 240 seconds and so on, never more than 3600

        Scenario: A successful sign-in clears the failure count
            Given user "sam" has failed to sign in 4 times
            When "sam" signs in with the correct password
            And then fails 4 more times
            Then the next sign-in with the correct password succeeds

        Scenario: Sign-in is throttled per address across usernames
            Given one address fails to sign in 20 times using different usernames
            When it tries to sign in as an existing user with the correct password
            Then the response status is 429

        Scenario: Passwords shorter than ten characters are refused
            When an admin creates user "kid" with password "short"
            Then the response status is 400

        Scenario: Passwords are never stored in plain text
            Given user "sam" with password "correct horse"
            Then the store does not contain "correct horse"

        Scenario: A tampered or expired session token is rejected
            Given "sam" holds a session token
            When the token is altered, or the clock passes 180 days
            Then GET /api/rooms with it returns 401

        Scenario: The service token still works for scripts
            Given user "jane" exists
            When a client sends GET /api/rooms with the service token
            Then the response status is 200

        Scenario: Admin creates, lists and deletes users
            Given admin "jane" is signed in
            When "jane" posts /api/admin/users with username "sam", password "correct horse" and admin false
            Then the response status is 201
            And GET /api/admin/users lists "jane" and "sam" without any password hashes
            When "jane" deletes "sam"
            Then "sam" can no longer sign in and their existing session tokens return 401

        Scenario: Duplicate usernames are refused
            Given user "sam" exists
            When an admin creates user "SAM"
            Then the response status is 409

        Scenario: Members cannot use admin endpoints
            Given member "sam" is signed in
            When "sam" sends GET /api/admin/users
            Then the response status is 403

        Scenario: The last admin cannot be deleted or demoted
            Given "jane" is the only admin
            When "jane" deletes themselves or removes their own admin flag
            Then the response status is 409

        Scenario: Admin resets a password and the user's sessions end
            Given "sam" holds a session token
            When admin "jane" sets a new password for "sam"
            Then "sam"'s old session token returns 401
            And "sam" can sign in with the new password

        Scenario: A user changes their own password
            Given "sam" is signed in with password "correct horse"
            When "sam" posts /api/auth/password with current "correct horse" and new "battery staple"
            Then the response status is 200 with a new session token
            And "sam" can sign in with "battery staple"
            And "sam"'s other session tokens return 401
            And a wrong current password gives 401

        Scenario: Sign out everywhere ends every session of that user
            Given "sam" holds two session tokens
            When "sam" posts /api/auth/logout-all with one of them
            Then both tokens return 401

    Feature: Room access
        As the household admin
        I want to choose which rooms each person can see
        So that a guest or a child only gets their own room, and only admins see the whole house

        Admins see and control every room. A member sees and controls only the rooms an admin has
        given them, and starts with none. A room a member cannot see answers 404, as if it did not
        exist. The service token counts as an admin.

        Scenario: A new member sees no rooms
            Given rooms "Lounge" and "Bedroom" exist
            And admin "jane" creates member "sam"
            When "sam" sends GET /api/rooms
            Then the response status is 200 with an empty list
            And GET /api/rooms for "jane" lists "Lounge" and "Bedroom"

        Scenario: An admin chooses the rooms a member can see
            Given rooms "Lounge" and "Bedroom" exist and member "sam" has none
            When admin "jane" sends PUT /api/admin/users/sam/rooms with the id of "Bedroom"
            Then GET /api/rooms for "sam" lists only "Bedroom"
            And GET /api/admin/users shows "sam" with rooms ["Bedroom"] and "jane" with every room
            And sending an empty list takes every room away from "sam" again

        Scenario: Room access refuses unknown rooms and admin users
            When admin "jane" sets the rooms of "sam" to a room id that does not exist
            Then the response status is 400
            And setting the rooms of an admin returns 409, because admins see every room
            And a member sending PUT /api/admin/users/sam/rooms gets 403

        Scenario: A member controls the rooms they can see
            Given member "sam" can see "Bedroom"
            When "sam" sets "Bedroom" to manual 21.0, boosts it and cancels the boost
            Then each request succeeds and "Bedroom" changes

        Scenario: A room a member cannot see answers as if it does not exist
            Given member "sam" can see "Bedroom" but not "Lounge"
            When "sam" sends GET, PATCH or a boost to "Lounge"
            Then every response status is 404
            And "Lounge" is unchanged

        Scenario: Only admins see the whole house
            Given member "sam" can see "Bedroom"
            When "sam" uses /api/house, /api/groups, /api/dashboard, GET /api/status, /api/valves or /api/sensors
            Then every response status is 403

        Scenario: Only admins create, rename, delete or fit out rooms
            Given member "sam" can see "Bedroom"
            When "sam" creates a room, renames or deletes "Bedroom", or assigns a valve or sensor to it
            Then every response status is 403

        Scenario: Making a member an admin gives them the whole house
            Given member "sam" can see "Bedroom"
            When admin "jane" makes "sam" an admin
            Then GET /api/rooms for "sam" lists every room
            And when "jane" makes "sam" a member again "sam" sees only "Bedroom"

        Scenario: New rooms are not given to members
            Given member "sam" can see "Bedroom"
            When admin "jane" creates room "Study"
            Then "sam" does not see "Study" until an admin gives it to them

        Scenario: Deleting a room takes it off every member's list
            Given member "sam" can see "Bedroom" and "Study"
            When admin "jane" deletes "Study"
            Then "sam" can see only "Bedroom"
            And recreating a room named "Study" does not give it back to "sam"

        Scenario: Members already signed up keep every room when room access arrives
            Given a store from before room access with member "john" and rooms "Lounge" and "Bedroom"
            When the service starts
            Then "john" can see "Lounge" and "Bedroom"
            And members created afterwards start with no rooms

        Scenario: Room access survives a restart
            Given member "sam" can see "Bedroom"
            When the service restarts
            Then "sam" still sees only "Bedroom"

        Scenario: Members get history only for rooms they can see
            Given history for "Lounge" and "Bedroom" and member "sam" can see "Bedroom"
            When "sam" sends GET /api/history
            Then it holds only "Bedroom" and no whole-house line
            And asking for "Lounge" by id returns 404

        Scenario: Live updates only carry rooms a member can see
            Given member "sam" can see "Bedroom" and is connected to /ws
            When "Lounge" and "Bedroom" both change
            Then "sam" receives "room.updated" for "Bedroom" only
            And "sam" receives no "status.updated" events
            And when an admin gives "sam" "Lounge", "sam" receives "access.updated" and its "Lounge" updates from then on

        Scenario: Notifications only tell members about rooms they can see
            Given "jane" is an admin, member "sam" can see "Bedroom" and both have a registered phone
            When "john" turns the heating on in "Lounge"
            Then "jane" gets "John turned the heating on" and "sam" gets nothing
            And when "john" boosts "Lounge" and "Bedroom" together, "sam" gets one notification naming "Bedroom" only
            And "Heating finished" follows the same rule

        Scenario: An admin ticks each member's rooms on the people screen
            Given admin "jane" opens People on the web app or the Android app
            When "jane" opens member "sam"
            Then every room is listed with a tick box, ticked for the rooms "sam" can see
            And changing a tick saves straight away
            And admins are shown as "Sees every room" with no tick boxes

        Scenario: A member's app shows only their rooms
            Given member "sam" can see "Bedroom"
            When "sam" opens the web app or the Android app
            Then the Rooms screen shows only "Bedroom"
            And there is no whole-house control, Groups, Dashboard or boiler state
            And a member with no rooms sees "No rooms yet. Ask an admin to give you access."

    Feature: Web app
        As someone at a computer
        I want the heating in a browser, signed in with the same account as the phone
        So that I can manage rooms and people without the app, and no script on the page can steal my session

        Scenario: Web sign-in sets an HttpOnly session cookie and returns no token
            Given user "sam" with password "correct horse"
            When a client posts /api/auth/login with username "sam", password "correct horse" and web true
            Then the response sets cookie "__Host-heat" that is HttpOnly, Secure, SameSite=Strict with Path=/
            And the body's token is empty

        Scenario: The session cookie authenticates reads
            Given "sam" signed in on the web
            When the browser sends GET /api/rooms with only the cookie
            Then the response status is 200

        Scenario: Changes made with the cookie need the web client header
            Given "sam" signed in on the web
            When the browser posts /api/rooms with only the cookie
            Then the response status is 401
            When it posts again with header "X-Heat-Client: web"
            Then the response status is 201

        Scenario: Changing password on the web keeps that browser signed in
            Given "sam" signed in on the web
            When the browser posts /api/auth/password with the cookie and the web client header
            Then the response sets a new "__Host-heat" cookie and the body's token is empty
            And the new cookie authenticates reads while the old one does not

        Scenario: Web sign-out clears the cookie
            When a client posts /api/auth/logout
            Then the response clears cookie "__Host-heat"

        Scenario: A cookie WebSocket from another origin is refused
            Given "sam" signed in on the web
            When a WebSocket to /ws carries the cookie and Origin "https://evil.example"
            Then the connection is refused
            And with an Origin matching the service's host it is accepted

        Scenario: Signed-in people can download the Android app
            Given the service is configured with an ANDROID_APK file
            When a signed-in client sends GET /api/app
            Then the body says the Android app is available with its size
            When it sends GET /api/app/android
            Then it receives the file as "heating.apk" with type application/vnd.android.package-archive

        Scenario: The Android app is not served to strangers or when missing
            When a client without a session sends GET /api/app/android
            Then the response status is 401
            And without an ANDROID_APK file a signed-in client gets {"android":null} and a 404 for the download

        Scenario: The web app is served with a strict content security policy
            Given the service is configured with a WEB_DIR containing index.html
            When a browser loads / or a page path such as /rooms/abc
            Then it receives index.html with a Content-Security-Policy of default-src 'self'
            And unknown /api paths still answer 404, not the page

    Feature: Installable web app
        As someone with an iPhone
        I want to add the heating to my home screen and get the same notifications as the Android app
        So that the household does not need a separate iPhone app

        Notifications reach browsers through standard Web Push (VAPID, RFC 8030/8291/8292), which iPhones
        support from iOS 16.4 once the site is added to the home screen. The service needs WEBPUSH_CONTACT
        (a mailto: or https: address the push services can reach the owner at); without it, web push is
        off and everything else works. Android phones keep using Firebase.

        Scenario: The web app can be installed to the home screen
            Given the service is configured with a WEB_DIR
            When a browser that is not signed in loads /manifest.webmanifest
            Then it gets name "Heating", start_url "/", display "standalone" and PNG icons of 192 and 512 pixels
            And index.html links the manifest, a 180 pixel apple-touch-icon and a theme colour
            And the page still has a Content-Security-Policy of default-src 'self'

        Scenario: The service worker is served from the root and never cached stale
            When a browser loads /sw.js
            Then it receives JavaScript with Cache-Control no-cache
            And it is registered with scope "/"
            And it does not cache /api responses

        Scenario: An installed app with no connection says so
            Given the app is installed and was opened before
            When it is opened with no connection to the service
            Then it shows "Can't reach the heating" with a Try again button instead of a browser error page

        Scenario: The service gives browsers its push key
            Given WEBPUSH_CONTACT is set
            When a signed-in client sends GET /api/me/webpush
            Then the body has webPush true and the VAPID public key
            And the key pair is made on first start and is the same after a restart
            And without WEBPUSH_CONTACT the body is {"webPush":false}

        Scenario: A browser subscribes to notifications
            Given "john" is signed in on her iPhone's home screen app
            When the app sends PUT /api/me/webpush with its push endpoint and keys
            Then notifications for "john" go to that subscription
            And sending DELETE /api/me/webpush with that endpoint, or signing out on that device, stops them

        Scenario: Only real push services are accepted as subscriptions
            When a signed-in client subscribes with an endpoint that is not https, or whose host is not
                web.push.apple.com, fcm.googleapis.com, updates.push.services.mozilla.com or a notify.windows.com host
            Then the response status is 400
            And the service never sends a request to that address

        Scenario: Browsers get the same notifications as Android phones
            Given "jane" has an Android phone and "john" has a subscribed iPhone
            When "jane" turns the heating on in "Utility Room"
            Then "john"'s iPhone gets "Jane turned the heating on" with "Utility Room, heating to 22°"
            And the message is encrypted for that subscription and signed with the service's VAPID key
            And each person's notification switches and room access apply the same as on Android

        Scenario: Tapping a notification opens the room
            Given "john" got "Jane turned the heating on" for "Utility Room"
            When she taps it
            Then the app opens on "Utility Room", or the Rooms screen when it named several rooms

        Scenario: Subscriptions the push service has dropped are forgotten
            Given "john" has a subscription that the push service answers with 404 or 410
            When a notification is sent
            Then that subscription is removed and not used again
            And an unreachable push service does not stop the heating

        Scenario: Account turns notifications on for this browser
            Given web push is on and "john" is signed in on the web app
            When she opens Account
            Then she sees "Notifications on this device" with "Someone turns the heating on" and "Heating finished"
            And turning it on asks the browser for permission only then, and subscribes if allowed
            And if permission was refused it says to allow notifications in the phone's settings

        Scenario: iPhone Safari explains how to add the app to the home screen
            Given web push is on and someone opens the web app in Safari on an iPhone, not from the home screen
            When they open Account
            Then instead of the notification switch it says to tap Share, then "Add to Home Screen", to get notifications
            And this hint is not shown in the home screen app or on other devices

    Feature: Groups
        As the household
        I want to put rooms in named groups such as "Upstairs" or "Kids"
        So that I can turn several rooms on, off, up or boost them with one tap

        Scenario: New groups hold the chosen rooms
            Given rooms "Bedroom 3" and "Master Bedroom" exist
            When a client sends POST /api/groups with {"name":"Upstairs","rooms":[bedroom3, master]}
            Then the response status is 201
            And the group "Upstairs" exists with rooms "Bedroom 3" and "Master Bedroom"

        Scenario: Groups refuse duplicate names and unknown rooms
            Given a group "Upstairs" exists
            When a client sends POST /api/groups with {"name":"upstairs","rooms":[]}
            Then the response status is 409
            And a group naming an unknown room id is refused with 400

        Scenario: A room can be in more than one group
            Given room "Bedroom 3" is in group "Upstairs"
            When a client creates group "Kids" with room "Bedroom 3"
            Then "Bedroom 3" is in both "Upstairs" and "Kids"

        Scenario: Rename a group and change its rooms
            Given group "Upstairs" with room "Bedroom 3"
            When a client sends PATCH /api/groups/{id} with {"name":"Top floor","rooms":[bedroom3, master]}
            Then the group is named "Top floor" and holds "Bedroom 3" and "Master Bedroom"

        Scenario: Deleting a group leaves its rooms alone
            Given group "Upstairs" with room "Bedroom 3" in manual mode at 21.0
            When a client sends DELETE /api/groups/{id}
            Then the group no longer exists
            And "Bedroom 3" still exists in manual mode at 21.0

        Scenario: Deleting a room removes it from its groups
            Given group "Upstairs" with rooms "Bedroom 3" and "Master Bedroom"
            When room "Bedroom 3" is deleted
            Then "Upstairs" holds only "Master Bedroom"

        Scenario: Groups list their average temperature, heating, mode and target
            Given group "Upstairs" with "Bedroom 3" manual 21.0 at 19.0 heating and "Master Bedroom" manual 21.0 at 20.0 not heating
            When a client sends GET /api/groups
            Then "Upstairs" shows current 19.5, target 21.0, mode "manual" and heating false
            And "Upstairs" lists "Bedroom 3" as its only heating room

        Scenario: A group lights up only when every room in it heats
            Given group "Kids" with "Bedroom 2", "Bedroom 3" and "Bedroom 1"
            And only "Bedroom 1" is heating
            When a client sends GET /api/groups
            Then "Kids" shows heating false and lists "Bedroom 1" as its only heating room
            When "Bedroom 2" and "Bedroom 3" are heating too
            Then "Kids" shows heating true and lists all three as heating rooms
            And the rooms screen colours a group card as heating only when the group shows heating true
            And the group card marks each heating room in its room list with a flame

        Scenario: Group shows mixed when its rooms disagree
            Given group "Upstairs" with "Bedroom 3" manual 21.0 and "Master Bedroom" off
            When a client sends GET /api/groups
            Then "Upstairs" shows mode "mixed" and target null

        Scenario: Groups set every member room to manual at one temperature
            Given group "Upstairs" with rooms "Bedroom 3" (schedule) and "Master Bedroom" (off), and room "Lounge" not in it
            When a client posts /api/groups/{id}/control {"mode":"manual","target":21.0}
            Then "Bedroom 3" and "Master Bedroom" are in manual mode at 21.0 and their valves are sent 21.0
            And "Lounge" is unchanged

        Scenario: Turn a group off and back to schedule
            Given group "Upstairs" with rooms "Bedroom 3" and "Master Bedroom"
            When a client posts /api/groups/{id}/control {"mode":"off"}
            Then both rooms are off
            When a client posts /api/groups/{id}/control {"mode":"schedule"}
            Then both rooms are in schedule mode

        Scenario: Groups boost every member room and cancel together
            Given group "Upstairs" with rooms "Bedroom 3" and "Master Bedroom", and room "Lounge" not in it
            When a client posts /api/groups/{id}/control {"boost":60,"target":22.0}
            Then every zone in "Bedroom 3" and "Master Bedroom" shows a boost to 22.0 ending 60 minutes from now
            And "Lounge" has no boost
            When a client sends DELETE /api/groups/{id}/boost
            Then no zone in the group has a boost

        Scenario: Group changes follow the same rules as whole house
            Given group "Upstairs" with independent room "Bedroom 3" (valves "M1" and "M2") and room "Loft" with no valves
            When a client posts /api/groups/{id}/control {"mode":"manual","target":20.0}
            Then zones "M1" and "M2" are manual at 20.0 and "Loft" is unchanged
            And {"mode":"manual","target":40.0} is refused with 400 and no room changed
            And during a holiday {"boost":60} is refused with 409

        Scenario: Groups survive a restart
            Given group "Upstairs" with rooms "Bedroom 3" and "Master Bedroom"
            When the service restarts
            Then group "Upstairs" still holds "Bedroom 3" and "Master Bedroom"

        Scenario: Group change is pushed to connected clients
            Given a client connected to /ws
            When group "Upstairs" is created or one of its rooms changes
            Then the client receives a "groups.updated" event with the group list


    Feature: Room sensors
        As the household
        I want a room to be measured by a separate temperature sensor instead of its valves
        So that a valve warmed by its own radiator does not stop heating before the room is warm

        Scenario: Temperature sensors are discovered from the bridge device list
            Given the bridge device list contains a TRVZB "Bedroom 3 TRV" and a smoke alarm "Landing Smoke" that exposes temperature
            When a client sends GET /api/sensors
            Then it lists "Landing Smoke" and not "Bedroom 3 TRV"
            And devices that expose no temperature are not listed

        Scenario: Sensor readings are ingested from its state topic
            Given sensor "Landing Smoke" is discovered
            When "zigbee2mqtt/Landing Smoke" publishes {"temperature":20.9,"battery":90}
            Then GET /api/sensors shows "Landing Smoke" at 20.9, battery 90, online and unassigned

        Scenario: Unassigned sensors are listed the same way as unassigned valves
            Given sensor "Landing Smoke" is unassigned and sensor "Hall Smoke" is in room "Hall"
            When a client sends GET /api/sensors?unassigned=true
            Then it lists "Landing Smoke" only

        Scenario: Sensors assigned to a room switch its valves to external
            Given room "Bedroom 3" with valve "Bedroom 3 TRV" and sensor "Landing Smoke" at 20.9
            When a client sends PUT /api/rooms/{id}/sensor/Landing Smoke
            Then room "Bedroom 3" shows sensor "Landing Smoke" and temperature source "sensor"
            And "Bedroom 3 TRV" is sent {"temperature_sensor_select":"external","external_temperature_input":20.9}

        Scenario: A sensor can only be in one room
            Given sensor "Landing Smoke" is in room "Bedroom 3"
            When a client sends PUT /api/rooms/{lounge}/sensor/Landing Smoke
            Then the response status is 409

        Scenario: Adding a second sensor to a room replaces the first
            Given room "Bedroom 3" has sensor "Landing Smoke"
            When a client sends PUT /api/rooms/{id}/sensor/Bedroom 3 Smoke
            Then room "Bedroom 3" shows sensor "Bedroom 3 Smoke"
            And "Landing Smoke" is unassigned

        Scenario: A room with a sensor shows the sensor's temperature
            Given room "Bedroom 3" with valve "Bedroom 3 TRV" reporting 23.0 and sensor "Landing Smoke" reporting 20.9
            When a client sends GET /api/rooms
            Then "Bedroom 3" shows current 20.9

        Scenario: Heat demand uses the sensor's temperature
            Given room "Bedroom 3" in manual mode at 21.0 with valve "Bedroom 3 TRV" reporting 23.0 and sensor "Landing Smoke" reporting 20.4
            Then "Bedroom 3" calls for heat
            When the sensor reports 21.0
            Then "Bedroom 3" does not call for heat

        Scenario: Independent rooms use the sensor for every zone
            Given independent room "Kitchen" with valves "K1" and "K2" both at manual 21.0, and sensor "Kitchen Smoke" at 20.0
            Then zones "K1" and "K2" both call for heat
            And both valves are sent {"temperature_sensor_select":"external","external_temperature_input":20.0}

        Scenario: Each sensor reading is passed on to the room's valves
            Given sensor "Landing Smoke" is in room "Bedroom 3" with valve "Bedroom 3 TRV"
            When the sensor reports 21.3
            Then "Bedroom 3 TRV" is sent {"external_temperature_input":21.3}

        Scenario: The sensor reading is re-sent to the valves every ten minutes
            Given sensor "Landing Smoke" in room "Bedroom 3" last reported 20.9
            When the clock advances 10 minutes with no new report
            Then "Bedroom 3 TRV" is sent {"external_temperature_input":20.9} again

        Scenario: A valve added to a room with a sensor is switched to it
            Given room "Bedroom 3" has sensor "Landing Smoke" at 20.9
            When valve "Bedroom 3 TRV 2" is assigned to the room
            Then "Bedroom 3 TRV 2" is sent {"temperature_sensor_select":"external","external_temperature_input":20.9}

        Scenario: Unassigned valves return to their internal sensor
            Given room "Bedroom 3" with sensor "Landing Smoke" and valve "Bedroom 3 TRV"
            When the valve is removed from the room
            Then "Bedroom 3 TRV" is sent {"temperature_sensor_select":"internal"}

        Scenario: Removing the sensor from a room returns it to its valves
            Given room "Bedroom 3" with sensor "Landing Smoke" and valve "Bedroom 3 TRV" reporting 23.0
            When a client sends DELETE /api/rooms/{id}/sensor/Landing Smoke
            Then "Bedroom 3 TRV" is sent {"temperature_sensor_select":"internal"}
            And room "Bedroom 3" shows current 23.0 and temperature source "valves"

        Scenario: A silent sensor falls back to the valves
            Given room "Bedroom 3" with sensor "Landing Smoke" and valve "Bedroom 3 TRV" reporting 23.0
            When the clock advances 60 minutes and the sensor sends nothing
            Then "Landing Smoke" is offline
            And "Bedroom 3 TRV" is sent {"temperature_sensor_select":"internal"}
            And room "Bedroom 3" shows current 23.0, temperature source "valves" and a notice that its sensor is offline

        Scenario: An offline sensor falls back to the valves
            Given room "Bedroom 3" with sensor "Landing Smoke"
            When "zigbee2mqtt/Landing Smoke/availability" publishes {"state":"offline"}
            Then "Bedroom 3 TRV" is sent {"temperature_sensor_select":"internal"}
            And room "Bedroom 3" shows temperature source "valves"

        Scenario: A sensor that reports again takes over again
            Given room "Bedroom 3" has fallen back to its valves because "Landing Smoke" went silent
            When the sensor reports 20.5
            Then "Bedroom 3 TRV" is sent {"temperature_sensor_select":"external","external_temperature_input":20.5}
            And room "Bedroom 3" shows current 20.5 and temperature source "sensor"

        Scenario: Sensors that leave Zigbee2MQTT are dropped from their room
            Given sensor "Landing Smoke" is in room "Bedroom 3"
            When the bridge device list no longer contains "Landing Smoke"
            Then room "Bedroom 3" has no sensor
            And "Bedroom 3 TRV" is sent {"temperature_sensor_select":"internal"}

        Scenario: Room sensors survive a restart and are resent to valves
            Given sensor "Landing Smoke" is in room "Bedroom 3" with valve "Bedroom 3 TRV"
            When the service restarts and the sensor reports 20.9
            Then room "Bedroom 3" still has sensor "Landing Smoke"
            And "Bedroom 3 TRV" is sent {"temperature_sensor_select":"external","external_temperature_input":20.9}

        Scenario: Offline room sensors are reported to Uptime Kuma
            Given a Kuma push URL is configured, valves "A" online, sensor "Landing Smoke" in room "Bedroom 3" and unassigned sensor "Spare Smoke"
            When "Landing Smoke" goes offline and the clock advances 1 minute
            Then the push URL receives status "down" with message "offline: Landing Smoke"
            And an offline sensor that is in no room is not reported

        Scenario: Sensor temperature change is pushed to connected clients
            Given a client connected to /ws and sensor "Landing Smoke" in room "Bedroom 3"
            When the sensor reports 21.2
            Then the client receives a room change for "Bedroom 3" showing current 21.2

    Feature: Boiler state
        As the household
        I want to see what the boiler is doing and why
        So that a boiler that keeps running, or will not start, is explained instead of puzzling

        Scenario: Boiler state reads heating while a zone calls for heat
            Given zone "Lounge" is calling for heat and heat demand is on
            When a client sends GET /api/status
            Then "boiler" is {"state":"heating","until":null}

        Scenario: Boiler is cycling while it finishes its minimum run
            Given heat demand turned on 1 minute ago
            When no zone calls for heat
            Then GET /api/status shows "boiler" state "cycling" until 4 minutes after it turned on
            And when that time passes the state is "idle"

        Scenario: Boiler is resting while it waits out its minimum rest
            Given heat demand turned off 2 minutes ago
            When zone "Lounge" starts calling for heat
            Then GET /api/status shows "boiler" state "resting" until 5 minutes after it turned off
            And when that time passes the state is "heating"

        Scenario: Boiler is idle when nothing calls for heat
            Given no zone has called for heat for an hour
            When a client sends GET /api/status
            Then "boiler" is {"state":"idle","until":null}

        Scenario: Boiler state changes are pushed to connected clients
            Given a client connected to /ws
            When the boiler goes from heating to cycling and then to idle
            Then the client receives a status.updated event for each state with its "boiler"

        Scenario: Minimum run and rest times come from configuration
            Given BOILER_MIN_RUN_MINUTES=6 and BOILER_MIN_REST_MINUTES=3
            When heat demand turns on and no zone calls a minute later
            Then heat demand turns off 6 minutes after it turned on
            And without those variables the minimum run is 4 minutes and the minimum rest 5 minutes

    Feature: Heating history
        As the household
        I want the service to remember room temperatures and what the boiler was doing
        So that the dashboard can show how the house heats over a day, a week or a month

        Scenario: Room temperatures and targets are recorded every five minutes
            Given room "Lounge" at 19.5 with effective target 21.0
            When the clock advances 5 minutes
            Then a history point for "Lounge" is stored with current 19.5 and target 21.0
            And another is stored 5 minutes later, not on every tick
            And a room with no temperature yet is skipped

        Scenario: Boiler states are recorded as they change
            Given the boiler goes heating at 06:30, cycling at 07:10 and idle at 07:14
            When a client sends GET /api/history?range=24h
            Then "boiler" lists heating 06:30-07:10, cycling 07:10-07:14 and idle from 07:14 with "to" null

        Scenario: A boiler state left open by a restart ends when the service last recorded
            Given the boiler went heating at 08:00 and the service last recorded at 08:20
            When the service restarts at 09:00
            Then the heating state ends at 08:20, not at 09:00
            And a new state starts at 09:00

        Scenario: History older than a year is deleted
            Given history points and boiler states from 400 days ago and from yesterday
            When the daily clean-up runs
            Then only yesterday's remain

        Scenario: History for the last 24 hours, 7 days or 30 days is served
            Given history points for "Lounge" every 5 minutes for 30 days
            When a client sends GET /api/history?range=24h
            Then "rooms" has "Lounge" with its points in time order, each with at, current and target
            And range=7d averages them into 30-minute points and range=30d into 2-hour points

        Scenario: The whole-house line is the median of the rooms
            Given "Lounge" at 18.0, "Kitchen" at 19.0 and "Bedroom" at 23.0 at the same time
            When a client sends GET /api/history?range=24h
            Then "house" has a point at that time with current 19.0

        Scenario: Unknown history ranges are refused
            When a client sends GET /api/history?range=decade
            Then the response status is 400

        Scenario: A failed history write does not stop the heating
            Given the history storage is broken
            When the clock advances 5 minutes and a zone calls for heat
            Then heat demand still turns on

        Scenario: History survives a restart
            Given history points and boiler states are stored
            When the service restarts
            Then GET /api/history returns the same data


    Feature: Dashboard
        As the household
        I want a chart of every room's temperature with the boiler's state behind it
        So that we can see how the house heats and when the boiler ran

        Scenario: The dashboard charts each room, the house median and the boiler
            Given history for "Lounge" and "Bedroom" and a boiler heating period
            When the household opens the Dashboard
            Then it shows a line for each room, a bold whole-house line and the heating period shaded behind them
            And they can switch between 24 hours, 7 days and 30 days

        Scenario: The dashboard says when there is no history yet
            Given no history has been recorded
            When the household opens the Dashboard
            Then it says history appears within five minutes instead of an empty chart

        Scenario: A room's page shows its temperature and target over time
            Given history for "Lounge" with current 19.5 and target 21.0
            When the household opens the "Lounge" page and scrolls below its controls
            Then it shows a chart with a line for the room's temperature and a line for its target
            And they can switch between 24 hours, 7 days and 30 days
            And with no history yet it says history appears within five minutes instead of an empty chart


    Feature: App version
        As the household
        I want to see which version of the app I am running
        So that I know whether an update has reached my phone

        Scenario: The account screen shows the app version
            When the household opens Account in the Android app
            Then it shows the app's version name, e.g. "Version 0.7.0"


    Feature: Notifications
        As the household
        I want a phone notification when someone else turns the heating on, and when it has finished
        So that nobody runs the boiler without the others knowing

        Push goes through Firebase Cloud Messaging (free). The service sends with a Firebase service
        account (FCM_SERVICE_ACCOUNT_FILE); without one it sends nothing and heating is unaffected.
        A "user action" is a change made in the app (it has a name) or a knob turn (it has none).
        Schedules, holidays ending and boosts running out are not user actions.

        Scenario: A phone registers for notifications
            Given "john" is signed in on the Android app
            When the app sends PUT /api/me/devices/{token}
            Then notifications for "john" go to that token
            And DELETE /api/me/devices/{token} (or signing out) stops them

        Scenario: Someone turning the heating on notifies everyone else
            Given users "jane" and "john" each with a registered phone
            And "Utility Room" at 18.0 is not calling for heat
            When "john" sets "Utility Room" to manual 22.0 and it starts calling for heat
            Then "jane" gets "John turned the heating on" with "Utility Room, heating to 22°"
            And "john" gets nothing

        Scenario: A knob turn that turns the heating on notifies everyone
            Given users "jane" and "john" each with a registered phone
            When the knob on "Bedroom 2 TRV" is turned up and "Bedroom 2" starts calling for heat
            Then both get "Thermostat turned up" with "Bedroom 2 thermostat was turned up to 26.5°"
            And a knob turned up in a room already warm enough sends nothing

        Scenario: One action that starts several rooms sends one notification
            When "jane" boosts the whole house and four rooms start calling for heat
            Then "john" gets one notification naming "4 rooms"

        Scenario: Heating finished after a user action notifies everyone
            Given "john" turned the heating on in "Utility Room"
            When "Utility Room" reaches 22.0 and stops calling for heat
            Then "jane" and "john" get "Heating finished" with "Utility Room is at 22°"
            And a room turned off before it got there sends no "Heating finished"

        Scenario: Schedule heating sends no notifications
            When a schedule block starts and "Lounge" starts calling for heat, then reaches its target
            Then nobody gets a notification

        Scenario: Each person can turn each notification off
            When "jane" sends PUT /api/me/notifications {"heatingOn":false,"heatingFinished":true}
            Then GET /api/me/notifications returns those settings
            And "jane" gets "Heating finished" notifications but not "turned the heating on" ones
            And both are on for a new user

        Scenario: Phones the push service no longer knows are forgotten
            Given "jane" has a registered token that Firebase reports as unregistered
            When a notification is sent
            Then that token is removed and not used again

        Scenario: A failed notification does not stop the heating
            Given the push service is unreachable
            When "john" turns the heating on
            Then heat demand still turns on

        Scenario: The Android app asks to notify and has a switch for each notification
            Given the household is signed in on Android 13 or newer
            Then the app asks for permission to send notifications once
            And Account shows "Someone turns the heating on" and "Heating finished" switches

    Feature: Energy settings
        As the household admin
        I want to set our gas and electricity prices
        So that the energy dashboard shows what the heating and the house really cost

        Scenario: Energy settings start with default prices
            When an admin sends GET /api/settings/energy on a new install
            Then the body has gasPencePerKwh 11.0, gasStandingPencePerDay 30.0, boilerAverageKw 12.0,
                electricityPencePerKwh 27.0, electricityStandingPencePerDay 50.0 and electricityMeter null

        Scenario: An admin changes the energy prices
            When an admin sends PUT /api/settings/energy with gasPencePerKwh 6.5 and electricityPencePerKwh 24.5
            Then GET /api/settings/energy returns those prices and the other settings unchanged
            And they are the same after a restart

        Scenario: Energy settings refuse impossible values
            When an admin sends a negative price, a boiler rate of 0 or over 100 kW, or a meter that is not discovered
            Then the response status is 400 and nothing changes

        Scenario: Only admins see or change energy settings
            Given member "sam" is signed in
            When "sam" sends GET or PUT /api/settings/energy
            Then the response status is 403

        Scenario: An admin edits prices in the app
            Given admin "jane" opens Account on the web app or the Android app
            Then an Energy prices section shows each price with its unit, such as "27.0p per kWh"
            And saving a change sends PUT /api/settings/energy and shows the new prices
            And members do not see the section

        Scenario: Money is shown in the household's currency
            Given energy settings have currency "GBP" (the default), or an admin sets another such as "EUR"
            When any cost is shown on the web app or the Android app (Dashboard, devices, rooms, plugs)
            Then it is the currency symbol and two decimals, such as "£1.25" or "£0.11", never "11p"
            And prices are still entered per kWh in the currency's minor unit, such as "27.0p per kWh" or "27.0c per kWh"
            And totals are worked out before rounding, so a total may differ from its parts by £0.01
            And an unknown currency code is refused (400)

        Scenario: An admin chooses the household's currency
            Given the household's currency is "GBP"
            When admin "jane" edits the energy prices on the Account page and chooses "€ Euro (EUR)" for Currency
            Then PUT /api/settings/energy is sent with currency "EUR" and the card shows the prices in cents, such as "27.0c per kWh"
            And the choices are "£ Pound (GBP)", "€ Euro (EUR)" and "$ US dollar (USD)", plus the current currency if it is another
            And the prices are not converted: while another currency is chosen the form says "Prices are not converted. Check them for the new currency."
            And the card shows the currency when not editing, such as "Currency: € Euro (EUR)"

    Feature: Boost settings
        As the household admin
        I want to set the temperature a boost starts at
        So that everyone's boost suits our house, while each person can still change it

        The default replaces the fixed 22.0 everywhere it was used. Boost length is not a setting.

        Scenario: New installs boost to 22 by default
            When an admin sends GET /api/settings/boost on a new install
            Then the body has defaultTarget 22.0

        Scenario: An admin's chosen boost temperature survives a restart
            When an admin sends PUT /api/settings/boost with defaultTarget 20.5
            Then GET /api/settings/boost returns defaultTarget 20.5
            And it is the same after a restart

        Scenario: Impossible boost defaults are refused
            When an admin sends defaultTarget 35.0, 4.5 or 20.3
            Then the response status is 400 and nothing changes

        Scenario: Non-admins read the boost default but cannot change it
            Given member "sam" is signed in
            When "sam" sends GET /api/settings/boost
            Then the response status is 200
            And when "sam" sends PUT /api/settings/boost the response status is 403

        Scenario: A boost without a target uses the household default
            Given the default boost temperature is 20.0
            When a client boosts "Lounge" for 60 minutes without a target
            Then the effective target is 20.0

        Scenario: The apps start a boost at the household default
            Given the default boost temperature is 20.0
            When a user boosts a room, a group or the whole house on the web app or the Android app
            Then the boost temperature starts where 22.0 used to: the default itself, or the higher of the default
                and the room's target where the app already did that (the Android app still adds 2 to the target)
            And the user can still change it before boosting

        Scenario: An admin sets the default boost temperature in the app
            Given admin "jane" opens Account on the web app or the Android app
            Then a Boost section shows "Default boost temperature 22.0°C"
            And saving a change sends PUT /api/settings/boost and shows the new value
            And members do not see the section

    Feature: Electricity meters
        As the household admin
        I want an electricity clamp paired in Zigbee2MQTT to show up in the app like a new valve or sensor
        So that I can start measuring the house's electricity without editing any config

        Scenario: Electricity meters are discovered from the bridge device list
            Given the bridge device list contains "Main Clamp" that exposes energy and power, a TRVZB "Bedroom 3 TRV" and a sensor "Landing Smoke"
            When a client sends GET /api/meters
            Then it lists "Main Clamp" only

        Scenario: Meter readings are ingested from its state topic
            Given meter "Main Clamp" is discovered
            When "zigbee2mqtt/Main Clamp" publishes {"energy":1523.4,"power":412}
            Then GET /api/meters shows "Main Clamp" with energy 1523.4 kWh, power 412 W, online and not in use

        Scenario: New meters are listed as unassigned
            Given meter "Main Clamp" is not the house meter and meter "Garage Plug" is
            When a client sends GET /api/meters?unassigned=true
            Then it lists "Main Clamp" only

        Scenario: An admin chooses the house electricity meter
            Given meter "Main Clamp" is discovered
            When an admin sends PUT /api/meters/Main Clamp/house
            Then GET /api/settings/energy has electricityMeter "Main Clamp" and GET /api/meters shows it in use
            And choosing another meter replaces it, and DELETE /api/meters/Main Clamp/house clears it
            And a member gets 403

        Scenario: The house meter is recorded every five minutes
            Given "Main Clamp" is the house meter
            When it reports energy 1523.4, then 1523.9 five minutes later
            Then history records 0.5 kWh for that five minutes
            And when its counter drops to 0.2 (a reset), the next reading counts on from 0.2 instead of going negative

        Scenario: A meter that stops reporting is shown as stale, not as zero use
            Given "Main Clamp" is the house meter and last reported 40 minutes ago
            Then GET /api/meters shows it as stale with the time of its last reading
            And the dashboard marks those minutes as missing, not as 0 kWh

        Scenario: Unpairing the house meter clears it
            Given "Main Clamp" is the house meter
            When a bridge device list arrives without "Main Clamp"
            Then electricityMeter is null
            And the energy settings carry the notice "Main Clamp was removed from Zigbee2MQTT"

        Scenario: New meters are offered in the app like new valves and sensors
            Given meter "Main Clamp" is discovered and not the house meter
            When admin "jane" opens the web app or the Android app
            Then it is shown with the new valves and sensors waiting to be set up, with its power now
            And choosing "Use for the house" makes it the house meter
            And members are not shown it

        Scenario: The main meter is labelled as measuring the whole house
            Given meter "Main Clamp" is waiting to be set up
            When admin "jane" opens the web app or the Android app
            Then its button reads "Main meter (whole house)" with the hint "Measures all electricity coming in"
            And once chosen, the meter shows "Main meter" wherever meters are listed

        Scenario: An admin ignores a device that needs no setting up
            Given plug "Garage Plug" is waiting to be set up
            When admin "jane" chooses "Ignore" on it
            Then PUT /api/devices/Garage Plug/ignored is sent and it leaves "Waiting to be set up"
            And GET /api/meters?unassigned=true and GET /api/valves?unassigned=true no longer list ignored devices
            And it stays ignored after a restart
            And a member gets 403

        Scenario: Ignored devices can be brought back
            Given "Garage Plug" is ignored
            When admin "jane" opens Account, then "Ignored devices", and chooses "Show again" on it
            Then DELETE /api/devices/Garage Plug/ignored is sent and it is waiting to be set up again
            And a device that leaves Zigbee2MQTT is taken off the ignored list

    Feature: Energy dashboard
        As the household admin
        I want to see what the heating and the house have used and cost
        So that I can tell whether a schedule change saves money

        Gas is an estimate (boiler hours times boilerAverageKw) until real meter data replaces it.
        The dashboard uses today's prices for the whole period.

        Scenario: The energy dashboard totals use and cost for a period
            Given the boiler was heating or cycling for 3 hours today and the house meter recorded 8.0 kWh
            When an admin sends GET /api/dashboard?period=today
            Then the body has boilerHours 3.0, gasKwh 36.0, gasCost 396p, electricityKwh 8.0, electricityCost 216p and totalCost
            And period week and month cover the last 7 and 30 days with a "days" list for charts

        Scenario: Changing a price recalculates past costs
            Given the house meter recorded 8.0 kWh today at 27.0p per kWh
            When an admin changes electricityPencePerKwh to 24.5
            Then GET /api/dashboard?period=today shows electricityCost 196p, and week and month are recalculated the same way
            And the recorded kWh are unchanged

        Scenario: Standing charges are shown on their own line
            When an admin sends GET /api/dashboard?period=week
            Then standing charges are 7 days of gas and electricity standing charge, shown apart from use
            And totalCost includes them

        Scenario: Days with gaps in history are marked incomplete
            Given the service was down for two hours yesterday
            When an admin sends GET /api/dashboard?period=week
            Then yesterday is marked incomplete

        Scenario: Gas-only totals when no meter is chosen
            Given no house meter is chosen
            When an admin sends GET /api/dashboard?period=today
            Then electricityKwh and electricityCost are null and totalCost counts gas only

        Scenario: Only admins see the energy dashboard
            Given member "sam" is signed in
            When "sam" sends GET /api/dashboard
            Then the response status is 403

        Scenario: The Dashboard shows energy use and cost under the temperature chart
            Given admin "jane" opens the Dashboard on the web app or the Android app
            Then below the temperature chart it shows gas, electricity, standing charges and total for today, week or month
            And a bar per day for week and month, with incomplete days marked
            And without a house meter it says "Choose an electricity meter to see electricity use"

    Feature: Activity feed
        As the household
        I want a short history of what changed in the heating and why
        So that "why is the boiler on?" and "who turned that up?" are answered without asking anyone

        The service keeps the last 500 events. An event is structured, not a sentence, so each client words it
        for its screen: {id, at, kind, actor, room, value, reason, rooms}. "actor" is the person's display name,
        or null when nobody did it (a knob turn, a schedule, the boiler).
        Kinds: setpoint (a person set a room, group or the house to a target), mode (a person set Off, Auto or
        Manual), boost, holiday, knob (the valve's own dial was turned), schedule (a block changed
        a room's target), boiler (heating, cycling, resting, idle), offline (a valve, sensor or monitored plug lost or regained contact; plug events also carry "device").
        Reasons on boiler events: "calling" (rooms named in "rooms" call for heat), "reached-temp" (the room that
        finished is in "rooms"), "min-run" (finishing its minimum run), "min-rest" (waiting out its rest), "no-demand".

        Scenario: A person changing a room is recorded with their name
            Given "john" is signed in
            When "john" sets "Utility Room" to manual 21.0
            Then GET /api/activity lists a "setpoint" event with actor "John", room "Utility Room" and value 21.0
            And it is the newest event

        Scenario: Changing Off, Auto, boost or holiday is recorded
            When "jane" turns "Lounge" off, boosts "Bedroom" to 23.0 for an hour and starts a holiday
            Then the feed lists a "mode" event, a "boost" event with value 23.0 and a "holiday" event, each with actor "Jane"

        Scenario: A change to a group or the whole house is one event
            When "jane" sets the group "Downstairs" of three rooms to manual 20.0
            Then the feed gains one "setpoint" event with room "Downstairs" and rooms listing the three rooms
            And not one event per room

        Scenario: A knob turn is recorded with no actor
            When the knob on "Bedroom 2 TRV" is turned to 22.0 and the service accepts it
            Then the feed lists a "knob" event with actor null, room "Bedroom 2" and value 22.0
            And a late report of an earlier command is not recorded

        Scenario: Schedule blocks starting and ending are recorded
            When a schedule block starts "Lounge" heating to 21.0 and a later block lowers it to 16.0
            Then the feed lists two "schedule" events for "Lounge" with actor null, the first with value 21.0 and the second with value 16.0

        Scenario: The boiler going to heating says which rooms called for it
            Given "Lounge" and "Utility Room" are below target
            When heat demand turns on
            Then the feed lists a "boiler" event with value "heating", reason "calling" and rooms "Lounge" and "Utility Room"

        Scenario: The boiler cycling down says which room reached its temperature
            Given the boiler is heating for "Utility Room" alone
            When "Utility Room" reaches its target
            Then the feed lists a "boiler" event with value "cycling", reason "reached-temp" and rooms "Utility Room"
            And when the minimum run passes it lists "idle" with reason "no-demand"

        Scenario: The boiler resting and restarting are explained
            Given heat demand turned off 2 minutes ago
            When "Lounge" starts calling for heat
            Then the feed lists a "boiler" event with value "resting", reason "min-rest" and rooms "Lounge"
            And when the rest passes it lists "heating" with reason "calling"

        Scenario: A valve or sensor losing contact is recorded once
            When valve "Lounge TRV 1" goes offline and later comes back
            Then the feed lists an "offline" event for "Lounge" with value "offline" and then one with value "online"
            And repeated offline reports add nothing

        Scenario: Events with nothing new are not recorded
            When "jane" sets "Lounge" to manual 21.0 when it is already manual 21.0
            Then the feed gains no event

        Scenario: Rapid boiler changes collapse into one line
            Given the boiler went heating, cycling, resting and heating again within 20 minutes
            When a client sends GET /api/activity
            Then the boiler events in that stretch are returned as one "boiler" event with value "cycled" and a "count" of 2
            And events outside the stretch are returned as they were

        Scenario: The feed is newest first and limited
            Given 80 events are stored
            When a client sends GET /api/activity?limit=50
            Then 50 events are returned, newest first
            And ?before=<id> returns the next older events
            And a limit above 200 or below 1 is refused with 400

        Scenario: Only the newest 500 events are kept
            Given 500 events are stored
            When another event is recorded
            Then the oldest is deleted and paging through GET /api/activity returns 500 events in total

        Scenario: The feed survives a restart
            Given events are stored
            When the service restarts
            Then GET /api/activity returns the same events

        Scenario: New events are pushed to connected clients
            Given a client connected to /ws
            When "john" sets "Utility Room" to manual 21.0
            Then the client receives an "activity.added" event carrying that event

        Scenario: Signed-out clients cannot read the feed
            When a client sends GET /api/activity without signing in
            Then the response status is 401

        Scenario: Members see only the rooms they can access
            Given member "sam" can access "Lounge" only
            When "sam" sends GET /api/activity
            Then room events for other rooms are left out
            And boiler events are included with other rooms removed from "rooms"
            And "sam" receives no "activity.added" push for other rooms

        Scenario: A failed activity write does not stop the heating
            Given the activity storage is broken
            When "john" turns on "Utility Room" and it calls for heat
            Then heat demand still turns on

        Scenario: Schedules and automatic changes are never attributed to a person
            When a boost runs out and a holiday ends
            Then the feed lists them with actor null

        Scenario: The boiler chip opens the activity tray on the web app
            Given a signed-in person on the web app
            When they click or tap the boiler chip
            Then a tray opens with the boiler's state, its explanation and countdown at the top
            And below it the newest 50 events, each with an icon, a line of text and how long ago it happened
            And "Load older" fetches the next page
            And Escape or tapping outside closes it

        Scenario: Activity lines are worded in full on wide screens and briefly on phones
            Given the web app at 1024px wide and again at 360px wide
            When the tray shows "john" setting "Utility Room" to 21.0
            Then wide shows "John turned the Utility Room thermostat to 21°"
            And narrow shows "John · Utility 21°" without wrapping to a second line
            And a boiler event shows "Boiler is now heating" wide and "Boiler heating" narrow
            And a reason shows "Utility Room has reached temperature, boiler cycling down" wide and "Utility at temp" narrow

        Scenario: Long room names are shortened to fit on phones
            Given a room named "Master Bedroom Ensuite"
            When the tray at 360px wide shows an event for it
            Then the name is shortened to fit one line with an ellipsis, and its full name is its tooltip or accessible label

        Scenario: The tray updates live while open
            Given the tray is open
            When "john" sets "Utility Room" to manual 21.0
            Then the new line appears at the top without a reload
            And events more than a minute old show "5m ago", "2h ago" and "yesterday"

        Scenario: An empty feed says so
            Given no events are stored
            When the tray opens
            Then it says "Nothing has changed yet"

        Scenario: The feed unavailable does not hide the boiler state
            Given GET /api/activity fails
            When the tray opens
            Then the boiler state and explanation still show, and the list says "Couldn't load activity" with a Retry button

        Scenario: The Android boiler chip opens the activity tray
            Given a signed-in person on the Android app
            When they tap the boiler chip
            Then a bottom sheet opens with the boiler's state at the top and the newest events below
            And lines use the brief wording on a phone, and the full wording on a tablet-width screen
            And dragging down or tapping outside closes it

        Scenario: The Android tray can be read with a screen reader
            Given TalkBack is on
            When the tray is open
            Then each event is read in full wording, with its time, even where the brief wording is shown

    Feature: Device monitoring
        As the household admin
        I want to see what single appliances on smart plugs use and cost
        So that I can spot the fridge or the garage eating electricity

        A monitored device is any meter other than the main meter. Its use is part of the main meter's
        total, so it is shown as "of which", never added to the total cost.

        Scenario: An admin monitors a plug
            Given plug "Fridge Plug" is waiting to be set up
            When admin "jane" chooses "Monitor this device"
            Then PUT /api/meters/Fridge Plug/monitored is sent and it leaves "Waiting to be set up"
            And GET /api/meters shows it as monitored
            And DELETE /api/meters/Fridge Plug/monitored stops monitoring it
            And the main meter cannot also be monitored (409)
            And a device that reports no energy counter (on/off only, or power without energy) cannot be monitored (422, "This device does not report energy")

        Scenario: Monitored devices are recorded every five minutes
            Given "Fridge Plug" is monitored
            When it reports energy 1027.88, then 1027.98 five minutes later
            Then 0.1 kWh is recorded for "Fridge Plug" for that five minutes
            And counter resets and stale readings are handled the same way as the main meter

        Scenario: The energy dashboard lists monitored devices without double counting
            Given "Fridge Plug" used 1.2 kWh today and the main meter recorded 8.0 kWh
            When an admin sends GET /api/dashboard?period=today
            Then "devices" lists "Fridge Plug" with 1.2 kWh and 32p
            And electricityKwh is still 8.0 and totalCost does not add the device again

        Scenario: The Dashboard shows each monitored device
            Given "Fridge Plug" and "Garage Plug" are monitored
            When admin "jane" opens the Dashboard on the web app or the Android app
            Then under electricity it shows "of which" with each device's use and cost for the chosen period
            And a device with incomplete readings shows its cost so far with the warning triangle used in a room's Plugs section

    Feature: Room energy
        As the household admin
        I want to put a monitored plug in a room
        So that I can see how much electricity each room uses

        A plug in a room is still a monitored device: its use is part of the main meter's total, so a
        room's use is shown as "of which", never added to the total cost. A room can hold any number of plugs; a plug is in at most one room.
        A plug that is not a room's own (a shared kettle, an EV charger) is simply left in no room.
        Use is counted in the room the plug was in when it was recorded. Room energy is admin only.

        Scenario: An admin puts a monitored plug in a room
            Given "Fridge Plug" is monitored and room "Kitchen" exists
            When admin "jane" chooses "Kitchen" for it
            Then PUT /api/rooms/kitchen/meters/Fridge Plug is sent and GET /api/meters shows it with room "kitchen"
            And DELETE /api/rooms/kitchen/meters/Fridge Plug takes it out of the room, and it stays monitored
            And putting a second plug in "Kitchen" keeps the first: a room holds any number of plugs
            And putting it in another room moves it
            And a plug that is not monitored cannot be put in a room (409)
            And a member gets 403

        Scenario: Only plugs in no room are listed on the Rooms screen
            Given "Fridge Plug" is monitored in "Kitchen" and "Garage Plug" is monitored in no room
            When admin "jane" opens the Rooms screen
            Then the "Monitored devices" card lists "Garage Plug" with its room picker, and not "Fridge Plug"
                (a plug in a room is managed from that room's settings, so it takes no space on the Rooms screen)
            And choosing a room for "Garage Plug" takes it off the card
            And with every monitored plug in a room the card is not shown
            And taking a plug out of its room in the room's settings lists it on the card again

        Scenario: An admin stops monitoring a plug from the Rooms screen
            Given "Garage Plug" is monitored in no room, so the "Monitored devices" card lists it
            When admin "jane" chooses "Disconnect" on its row (a button beside the room picker, not hidden in the menu)
            Then DELETE /api/meters/Garage Plug/monitored and PUT /api/devices/Garage Plug/ignored are sent
            And it leaves the card and is not offered in "Waiting to be set up"; it is listed with the ignored devices, where it can be brought back
            And its use recorded so far stays in the energy history

        Scenario: A room's energy is the sum of its plugs
            Given "Fridge Plug" used 1.2 kWh and "Kettle Plug" used 0.3 kWh today, both in "Kitchen", and "Lamp Plug" used 0.1 kWh in "Lounge"
            When an admin sends GET /api/dashboard?period=today
            Then "rooms" lists "Kitchen" with 1.5 kWh and 40p, and "Lounge" with 0.1 kWh and 3p
            And "devices" still lists each plug, and electricityKwh and totalCost do not add the rooms again
            And a monitored plug in no room is listed under devices only

        Scenario: Moving a plug does not move its past use
            Given "Kettle Plug" was in "Kitchen" yesterday and used 0.5 kWh there
            When admin "jane" moves it to "Lounge" today and it uses 0.2 kWh
            Then GET /api/dashboard?period=yesterday lists 0.5 kWh under "Kitchen" and nothing for it under "Lounge"
            And GET /api/dashboard?period=today lists 0.2 kWh under "Lounge"
            And each five-minute record keeps the room the plug was in when it was recorded

        Scenario: The energy dashboard shows everything else
            Given the main meter recorded 8.0 kWh today and monitored plugs used 1.6 kWh, in rooms or not
            When an admin sends GET /api/dashboard?period=today
            Then "everythingElse" is 6.4 kWh with its cost
            And it is main meter minus all monitored plugs, not only those in rooms
            And it is marked incomplete if the main meter or any monitored plug is incomplete, instead of guessing
            And if plugs add up to more than the main meter it is 0, never negative

        Scenario: A room with an incomplete plug reading is marked incomplete
            Given "Fridge Plug" in "Kitchen" stopped reporting for 40 minutes today
            When an admin sends GET /api/dashboard?period=today
            Then "Kitchen" is marked incomplete, with the missing minutes, instead of counting them as 0 kWh

        Scenario: Removing a room or a plug leaves no stray room use
            Given "Fridge Plug" is in "Kitchen"
            When "Kitchen" is deleted, or "Fridge Plug" leaves Zigbee2MQTT
            Then the plug is no longer in any room, and "Kitchen" no longer lists it
            And energy already recorded for the plug is kept

        Scenario: The Dashboard shows each room's electricity
            Given "Fridge Plug" is in "Kitchen" and "Lamp Plug" is in "Lounge"
            When admin "jane" opens the Dashboard on the web app or the Android app
            Then under electricity it shows "of which" per room with its use and cost for the chosen period
            And monitored plugs in no room are grouped under "Not in a room"
            And "Everything else" is shown last, for use no plug accounts for
            And a room with incomplete readings shows its cost so far with the warning triangle used in a room's Plugs section
            And members do not see rooms, devices or everything else

        Scenario: A plug is put in a room from the room's settings
            Given "Fridge Plug" is monitored and in no room
            When admin "jane" opens "Kitchen" settings on the web app or the Android app
            Then there is a "Plugs" section listing the room's plugs with their power now
            And each plug shows its power now and its cost today, such as "85 W now · £0.11 today" at 27.0p per kWh
            And cost is use times electricityPencePerKwh from Energy prices; standing charge is never split onto plugs
            And the section shows the room's cost today above its plugs, such as "Today £1.25"
            And a plug that went offline today still shows its cost so far, with a warning triangle beside it
            And tapping the triangle says "Was offline 14:20 to 15:00. Some use could not be counted."
            And the room total gets the same triangle, saying which plugs were affected
            And the triangle is read by a screen reader as "Cost incomplete" and the message is reachable without a tap
            And "Add plug" offers monitored plugs, and choosing one puts it in the room
            And a plug can be taken out again, and members do not see the section

        Scenario: A plug is put in a room straight after monitoring it
            Given plug "Kettle Plug" is waiting to be set up
            When admin "jane" chooses "Monitor this device" on the web app or the Android app
            Then a room picker follows, listing the rooms, with "Not now" to skip it
            And choosing "Kitchen" sends PUT /api/meters/Kettle Plug/monitored, then PUT /api/rooms/kitchen/meters/Kettle Plug
            And choosing "Not now" leaves it monitored and in no room
            And if putting it in the room fails it stays monitored and the error is shown

        Scenario: A plug that is offline now says so
            Given "TV Plug" in "Bedroom 1" went offline at 14:20, by Zigbee2MQTT availability or 30 minutes of silence
            When admin "jane" opens "Bedroom 1" settings or the Dashboard
            Then its power shows "Offline", never its last power
            And its cost so far today shows with the warning triangle
            And tapping the triangle says "Offline since 14:20. Cost will update when it's back."

        Scenario: A plug that comes back catches up on its use
            Given "TV Plug" read 12.40 kWh at 14:20, then went offline
            When it comes back at 16:00 reading 12.90 kWh
            Then 0.5 kWh is recorded in the five minutes it came back, and the cost includes it
            And if it came back the same day the triangle goes, because nothing is missing
            And if it came back reading lower than 12.40 (its total was reset) the use while offline is lost,
                the new reading counts on from there, and the triangle stays with "Was offline 14:20 to 16:00. Some use could not be counted."
            And if it was offline past midnight the catch-up counts on the day it came back, and both days keep the triangle

        Scenario: A plug whose energy total stops going up is flagged
            Given "TV Plug" reports enough power to have used at least 0.05 kWh while its energy total did not change
                (the total moves in 0.01 kWh steps, so a 5 W lamp may rightly show no change for hours)
            When admin "jane" opens "Bedroom 1" settings or the Dashboard
            Then it shows the warning triangle, saying "Using power but its energy total isn't going up. Cost may be too low."
            And the triangle goes once the total goes up again

        Scenario: A room's electricity is charted over time
            Given "Fridge Plug" and "Lamp Plug" were in "Kitchen" all day, using 80 W and 20 W, and "Lamp Plug" had no reading from 14:20 to 15:00
            When admin "jane" opens "Kitchen"
            Then an "Electricity" card under the History card shows the room's use as bars over the last 24 hours,
                from GET /api/rooms/kitchen/plugs/history?range=24h: one bar per hour, 0.1 kWh each
            And 7d and 30d show one bar per day (from the household's midnight), with the same range picker as the heating history
            And an hour or day when a plug in the room had no reading for some of it is marked incomplete (a faded bar, "Some readings missing"),
                its kWh only what was recorded; one with no reading at all has no bar, never 0 kWh
            And use counts in the room the plug was in when it was recorded
            And the card is not shown for a room with no plug use in the range, and a member gets 403 (the card is admin only)

        Scenario: A plug losing contact is in the activity feed
            When "TV Plug" in "Bedroom 1" goes offline and later comes back
            Then the feed lists an "offline" event with room "Bedroom 1", device "TV Plug" and value "offline", then one with value "online"
            And a plug in no room has room null
            And repeated offline reports add nothing, and no push notification is sent
            And members never see plug events, whichever rooms they can access

# Deferred (not to be built yet)
# - Mirror schedules to the valve weekly_schedule as a fallback when the service is down
# - Open-window alerts (TRVZB exposes the setting, not a state; needs inference)
# - Valve temperature calibration offsets (revisit if rooms without a sensor read badly)
# - Group schedules
# - Several sensors averaged in one room
# - Home-screen widget
# - Gas from the smart meter (Octopus API or Glow/Hildebrand), to replace the boiler-rate estimate
# - Prices that change over time (the dashboard uses the current prices for the whole period)
# - Room electricity beside room heating hours (tag a plug as a heater: portable heaters, towel rails)
