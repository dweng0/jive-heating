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
            And there is no whole-house control, Groups, Dashboard, Energy tab or boiler state
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

        Scenario: A sensor reading that moved is passed on to the room's valves
            Given sensor "Landing Smoke" is in room "Bedroom 3" with valve "Bedroom 3 TRV"
            And the valve was last sent 20.9
            When the sensor reports 21.5
            Then "Bedroom 3 TRV" is sent {"external_temperature_input":21.5}

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

        Scenario: A sensor's silence is timed from when Zigbee2MQTT last heard it
            Given room "Bedroom 3" with sensor "Landing Smoke"
            When the sensor's state arrives with "last_seen" 61 minutes ago, as a retained message does after a restart
            Then "Landing Smoke" is offline on the next tick
            And the same message arriving again does not bring it back
            But a message with a newer "last_seen" does

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
            Then GET /api/me/notifications returns those settings, with "boilerOverride" on because it was not sent
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

        Scenario: Prices can follow the Bright tariff
            Given BRIGHT_USERNAME and BRIGHT_PASSWORD are set and Bright's tariff is gas 6.906p per kWh and 27.324p a day,
                electricity 24.04p per kWh and 41.19p a day
            When an admin turns on "Sync prices with Bright" in the Energy prices section on the web app or the Android app
            Then PUT /api/settings/energy has syncPricesWithBright true and the four prices become Bright's at once
            And they are fetched again every day, and a price that changes is logged in the activity feed
            And while it is on the price fields say "From Bright" and cannot be edited; turning it off keeps the last prices to edit
            And a fetch that fails keeps the prices as they are and is tried again the next day
            And without BRIGHT_USERNAME and BRIGHT_PASSWORD the switch is not offered, and syncPricesWithBright true gets 400
            And currency, boilerAverageKw, electricityMeter and dailyKwhTarget are never changed by it

        Scenario: Money is shown in the household's currency
            Given energy settings have currency "GBP" (the default), or an admin sets another such as "EUR"
            When any cost is shown on the web app or the Android app (Energy tab, devices, rooms, plugs)
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
            When it reports 1200 W for five minutes
            Then history records 0.1 kWh and an average of 1200 W for that five minutes
            And its energy total is not used: some clamps' totals jump up and down, which undercounts the house

        Scenario: The house meter's use is counted from its power
            Given "Main Clamp" is the house meter
            When it reports 600 W, then 1800 W two and a half minutes later, while its energy total goes 0.56, 0.25, 0.17
            Then the five minutes record 0.1 kWh: each power reading holds until the next one
            And a gap of 30 minutes or more with no reading is unknown, never counted at the last power
            And after a restart the house is counted again from the first reading, without a jump
            And samples recorded before this change keep their kWh: past costs are not recalculated

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
            Given member "sam" is signed in without energy access
            When "sam" sends GET /api/dashboard
            Then the response status is 403

        Scenario: The Energy tab shows energy use and cost
            Given admin "jane" opens the Energy tab on the web app or the Android app
            Then below the live dial and its cards it shows gas, electricity, standing charges and total for today, week or month
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

        Scenario: A plug whose energy total dips a little is not taken as reset
            Given "Washing Machine" is monitored and reads 30.86 kWh
            When it reads 30.75 kWh five minutes later (its total stepped back, it was not reset)
            Then 0 kWh is recorded for those five minutes, never 30.75 kWh
            And its use counts on from 30.86, so reading 30.95 next records 0.09 kWh
            And a total that drops by more than 1 kWh, or to under half of what it was, is still a reset and counts on from the new total

        Scenario: A plug whose energy total jumps up is not taken as use
            Given "Tumble Dryer" is monitored and reads 34.38 kWh
            When it reads 56.27 kWh five minutes later (more than a plug can pass: 4 kW for five minutes is 0.33 kWh)
            Then 0 kWh is recorded for those five minutes
            And its use counts on from 56.27, so reading 56.40 next records 0.13 kWh
            And a plug coming back after being offline still catches up on its use however long it was away

        Scenario: The energy dashboard lists monitored devices without double counting
            Given "Fridge Plug" used 1.2 kWh today and the main meter recorded 8.0 kWh
            When an admin sends GET /api/dashboard?period=today
            Then "devices" lists "Fridge Plug" with 1.2 kWh and 32p
            And electricityKwh is still 8.0 and totalCost does not add the device again

        Scenario: The Energy tab shows each monitored device
            Given "Fridge Plug" and "Garage Plug" are monitored
            When admin "jane" opens the Energy tab on the web app or the Android app
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

        Scenario: A plug that leaves Zigbee2MQTT is no longer monitored
            Given "Fridge Plug" is monitored and in "Kitchen"
            When it is renamed to "Tumble Dryer" in Zigbee2MQTT, or unpaired
            Then "Fridge Plug" is no longer monitored, in no room, and not on the Energy tab
            And energy already recorded for "Fridge Plug" is kept
            And "Tumble Dryer" waits to be set up like any new plug
            And a plug that left while the service was down is dropped when the device list next arrives

        Scenario: Removing a room or a plug leaves no stray room use
            Given "Fridge Plug" is in "Kitchen"
            When "Kitchen" is deleted, or "Fridge Plug" leaves Zigbee2MQTT
            Then the plug is no longer in any room, and "Kitchen" no longer lists it
            And energy already recorded for the plug is kept

        Scenario: The Energy tab shows each room's electricity
            Given "Fridge Plug" is in "Kitchen" and "Lamp Plug" is in "Lounge"
            When admin "jane" opens the Energy tab on the web app or the Android app
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
            When admin "jane" opens "Bedroom 1" settings or the Energy tab
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
            When admin "jane" opens "Bedroom 1" settings or the Energy tab
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

    Feature: Live power
        As the household admin
        I want to see how much electricity the house is using right now
        So that I can tell at a glance what is switched on

        Live power is for admins and people with energy access, and has its own WebSocket channel, /ws/power, apart from the
        change events on /ws: clamp readings arrive every few seconds and only the open Energy tab needs them.
        The dial, the room cards and the "Everything else" card are on the web app and the Android app.
        Gas has no whole-house reading, so it is not shown.

        Scenario: The house power streams live
            Given the house meter reports 1200 W
            When admin "jane" opens the live power channel at /ws/power
            Then the first message is the house watts, 1200, and when it was read
            And every later house meter reading is sent as a new message

        Scenario: The live power channel needs energy access
            When a member without energy access, or a caller who is not signed in, connects to /ws/power
            Then the connection is refused

        Scenario: A stale house reading is not shown as live
            Given the house meter has not reported for 2 minutes
            Then the live power message has watts null
            And the dial reads "No reading" and the room cards show no live watts

        Scenario: The dial moves smoothly
            Given the dial shows 500 W
            When a reading of 3000 W arrives
            Then the needle sweeps to 3000 W over about a second instead of jumping
            And it does not swing past 3000 W first

        Scenario: The dial colours follow the household's own use
            Given the last 7 days of house readings (the five-minute average power of each sample)
            Then green runs up to the typical overnight load (the median from 00:00 to 05:00)
            And red starts at the 90th percentile of all readings
            And amber is between
            And with under 24 hours of readings the dial is one neutral colour

        Scenario: Room cards show live and daily use
            Given "Lounge" has 2 monitored plugs
            When admin "jane" views the live dial
            Then the "Lounge" card shows its live watts, today's kWh, today's cost and its share of the house load
            And a room with no monitored plugs has no card

        Scenario: Everything else is the unattributed remainder
            Given the house uses 2000 W and the rooms and unassigned monitored devices account for 1200 W
            When admin "jane" views the live dial
            Then an "Everything else" card shows 800 W
            And it never shows below 0 W
            And it is not shown while the house reading is stale

        Scenario: Energy has its own tab
            When admin "jane" opens the web app or the Android app
            Then there is an Energy tab beside the Dashboard
            And it shows the dials first, then the history chart, the room and area cards and the energy totals for today, week or month
            And the Dashboard shows only heating
            And members without energy access have no Energy tab

        Scenario: The dial's scale fits the household's biggest appliances
            Given the oven, shower or tumble dryer can take the house to 6 or 7 kW
            Then the dial runs from 0 to 8 kW, marked at 0, 0.5, 1, 2, 4, 6 and 8 kW
            And the needle moves along a square-root scale: 500 W is a quarter of the way, 2 kW half, 8 kW the end
            And a reading above 8 kW extends the scale in whole 2 kW steps instead of pinning the needle

        Scenario: The dial colours come only from recorded power
            Given house samples recorded before their average power was kept
            Then they are not used for the dial's colours
            And the dial is one neutral colour until a day of samples with average power is recorded

    Feature: Valves that miss commands
        As a household member
        I want a setpoint I choose to reach the valve even over a weak Zigbee link
        So that a room never heats, or stays hot, on a target I already changed

        Battery valves sleep and Zigbee2MQTT drops a write when the valve is not listening, so a
        command can be lost without any error reaching the service. The service compares what a
        valve reports with what it was last told and sends it again. Re-sends are quiet: they
        log nothing and add nothing to the feed.

        Scenario: A setpoint the valve did not take is sent again
            Given zone "Utility" has manual target 22.0 and its valve was sent 22.0
            And the valve still reports setpoint 21.0 and has reported nothing new
            When the tick runs
            Then the service sends "Utility TRV" a setpoint of 22.0 again after 10 seconds
            And then again after a further 20 seconds, 1 minute, 3 minutes and 5 minutes, and every 5 minutes after that
            And it stops sending as soon as the valve reports 22.0
            And a new change from a person starts the waits again from 10 seconds

        Scenario: A valve told to turn off that keeps heating is told again
            Given zone "Utility" is off and its valve was sent system_mode off
            And the valve still reports system_mode "heat" and has reported nothing new
            When the tick runs
            Then the service sends "Utility TRV" system_mode off again, with the same waits

        Scenario: A knob turn is not mistaken for a lost command
            Given zone "Utility" has manual target 22.0 and its valve was sent 22.0
            When the valve reports setpoint 19.0 more than a minute after the command, a value the service never sent
            Then it is handled as a knob turn, as before
            And the service does not send 22.0 again

        Scenario: A valve that is offline is not sent commands again
            Given zone "Utility" has manual target 22.0 and its valve reports setpoint 21.0
            And the valve is offline
            When the tick runs
            Then nothing is sent to "Utility TRV"
            And when the valve comes back online the service sends it 22.0 at once

        Scenario: A room sensor reading is passed to the valves only when it matters
            Given room "Utility" uses an external temperature sensor
            When the sensor reports 20.8, then 20.9, then 21.0
            Then the valves are sent 20.8 only
            And a reading 0.5 degrees or more from the last one sent is sent at once
            And the last reading is sent again after 10 minutes, as before

        Scenario: A valve that keeps missing commands is flagged in the feed
            Given zone "Utility" has manual target 22.0 and its valve keeps reporting 21.0
            When the service has sent 22.0 again three times without the valve taking it
            Then the feed lists an "unresponsive" event with room "Utility" and value "unresponsive"
            And further re-sends add nothing to the feed, and no push notification is sent

        Scenario: A valve that takes the command again is marked responding
            Given the feed says the valve in "Utility" is unresponsive
            When the valve reports the target it was sent
            Then the feed lists an "unresponsive" event with room "Utility" and value "responding"

    Feature: Boiler override
        As the household admin
        I want to force the boiler off from the app
        So that I can stop the heating at once, whatever the rooms are asking for

        An override is admin only. While it is on, no zone calls for heat, every valve is sent off, the
        published heat demand is false (so the thermostat drops to its lowest setting) and the zones'
        own modes, targets and schedules are kept exactly as they were. It stays on, across restarts, until an
        admin turns it back on. Anyone signed in can read whether it is on; only an admin can change it.

        Scenario: An admin turns the boiler off
            Given zone "Lounge" is on manual target 21.0 with a boost running, and the boiler is heating
            When admin "jane" turns the boiler off
            Then heat demand is published as false at once, without waiting for the minimum run time
            And "Lounge TRV" is sent system_mode off, and so is every other valve
            And the boost is cancelled but the zone keeps mode manual and target 21.0
            And the action log has one line "boiler turned off by jane"

        Scenario: Nothing heats while the boiler is off
            Given the boiler is turned off
            When a room is turned up, boosted or has its knob turned
            Then the change is saved
            And no zone calls for heat, the valves are sent off again, and heat demand stays false

        Scenario: The override survives a restart
            Given the boiler is turned off by admin "jane"
            When the service restarts
            Then the boiler is still off, with "jane" as who turned it off

        Scenario: An admin turns the boiler back on
            Given the boiler is turned off and zone "Lounge" is below its target
            When admin "jane" turns the boiler back on
            Then every zone is sent its own target again from its schedule or mode
            And heat demand is true again once the boiler's minimum rest time has passed
            And the boost cancelled earlier does not come back

        Scenario: Only admins can turn the boiler off or on
            When member "john" or a caller with no token tries to turn the boiler off or on
            Then it is refused with 403 or 401 and nothing changes
            But any signed-in user can read whether the override is on, and who set it

        Scenario: The feed and the notifications tell everyone
            When admin "jane" turns the boiler off
            Then the feed lists an "override" event with actor "Jane" and value "off"
            And everyone with the app except "jane", who wants it, is sent "Boiler turned off by admin"
            And when the boiler is turned back on, the feed lists value "on" and they are sent "Boiler back on"
            And members are told only if they can see at least one room

        Scenario: Each person can switch the boiler notification off
            Given "john" turned off "Boiler turned off or on" in his notification settings
            When admin "jane" turns the boiler off
            Then "john" is sent nothing, but the feed still has the event

        Scenario: The apps show that the boiler is forced off
            When the boiler is turned off
            Then every signed-in app shows a notice that the system will not heat while the admin override is on
            And the admin sees a button to turn the boiler back on, and the boiler chip shows "Off (admin)"
            And turning the boiler off asks "Are you sure?" first

    Feature: Room energy dashboard
        As the household admin
        I want to open a room from the Energy tab and see its electricity in detail
        So that I can see what each plug in it uses and costs, now and at any time in the past

        A room's dashboard is on both the web app and the Android app, and is admin only like the rest of the
        Energy tab. "Everything else" has the same dashboard. A room's use is "of which" the house meter's, and its
        cost is use at the electricity price alone: standing charges are never split onto rooms. All costs use
        today's price, like the energy dashboard.

        Scenario: A plug's average power is recorded every five minutes
            Given "Fridge Plug" is monitored
            When it reports 60 W, then 120 W two and a half minutes later
            Then the five-minute sample records an average of 90 W beside its kWh: each power reading holds until the next one
            And a sample while the plug has no reading has no power, never 0 W
            And samples recorded before this change have no power, and keep their kWh

        Scenario: A room's use is read over time
            Given "Fridge Plug" and "Kettle Plug" are in "Kitchen", and electricity costs 25p per kWh
            When an admin sends GET /api/rooms/kitchen/plugs/usage?window=24h
            Then "room" and each entry of "plugs" list points of watts, kWh and cost, one per five minutes, oldest first
            And 7d has one point per hour and 30d one point per day (from the household's midnight), watts being the average over the point
            And the room's points are the sum of its plugs at the time, in the room the plug was in when it was recorded
            And a member gets 403, and an unknown room 404

        Scenario: A room's use is read further back
            Given samples are kept for 365 days
            When an admin sends GET /api/rooms/kitchen/plugs/usage?window=24h&to=2026-10-20T12:00:00Z
            Then the points are the 24 hours up to that time
            And "earliest" says when the room's first sample was, so an app knows how far back it can go
            And a "to" in the future is treated as now, and an unreadable "to" or window gets 400

        Scenario: Missing readings are not shown as no use
            Given "Fridge Plug" in "Kitchen" had no reading from 14:20 to 15:00
            When an admin reads the room's use over that time
            Then the plug's points from 14:20 to 15:00 have null watts, kWh and cost, never 0
            And the room's points then hold only what was recorded and are marked incomplete
            And a plug's catch-up after being offline stays in the five minutes it came back, as the energy dashboard counts it

        Scenario: Everything else is read over time
            Given the house meter recorded 1000 W and monitored plugs 300 W over five minutes
            When an admin sends GET /api/energy/else/usage?window=24h
            Then the point has 700 W with its kWh and cost, in the same shape as a room's without "plugs"
            And it is house minus all monitored plugs, in rooms or not, and never below 0
            And it takes "to" like a room's, is marked incomplete when the house or any plug is, is null when the house has no reading, and a member gets 403

        Scenario: A room card opens the room's energy dashboard
            Given admin "jane" is on the Energy tab, on the web app or the Android app
            When they click or tap the "Utility" card
            Then "Utility" opens as its own screen, with a way back to the Energy tab where they left it
            And on the web app it has its own address, /energy/rooms/utility, that can be reloaded or bookmarked
            And a member sent to that address goes to the Rooms screen

        Scenario: The room dashboard leads with what it is using and costing
            Given "Kitchen" is using 42 W now, and has used 1.6 kWh today at 25p per kWh
            When admin "jane" opens its energy dashboard, on the web app or the Android app
            Then cards at the top show "Now" 42 W, "Today" 1.6 kWh and "Cost today" £0.40
            And "Now" moves with the live readings, without reloading
            And "Now" shows "No reading" when no plug in the room is reporting, never its last power
            And incomplete figures show so far with the warning triangle used in a room's Plugs section

        Scenario: The room dashboard charts each plug in a carousel
            Given "Kitchen" has "Fridge Plug" and "Kettle Plug"
            When admin "jane" opens its energy dashboard, on the web app or the Android app
            Then a carousel shows a line chart of the whole room first, then one for each plug by name
            And they move between them by swiping or with arrows, with dots showing where they are
            And a Power, Use and Cost choice draws watts, kWh or pounds, and stays as they move between charts
            And a room with one plug shows its chart alone, with no room chart repeating it

        Scenario: A chart scrolls back through time
            Given a chart in the carousel is showing the last 24 hours
            When admin "jane" drags or swipes along the chart
            Then it moves back through time, loading earlier readings as they go, and stops at "earliest"
            And 24h, 7d and 30d choose how much it shows, keeping the time they are looking at in view
            And a "Now" button returns to the latest readings, which then keep arriving
            And swiping on the chart moves the chart, not the carousel, and the other charts follow to the same time

        Scenario: A chart says what it shows at any point
            Given a chart in the carousel
            When admin "jane" hovers over it, or touches and holds it
            Then it marks that point and shows its time, watts, kWh and cost
            And a point with no reading says "No reading", and an incomplete one "Some readings missing"
            And the line breaks where there is no reading, instead of joining across or dropping to 0

        Scenario: A chart with nothing to draw says so
            Given "Fridge Plug" has no samples in the time being shown, or only samples from before power was kept while Power is chosen
            When admin "jane" looks at its chart
            Then it says "Nothing recorded for this time" instead of drawing an empty line at 0
            And they can still scroll, and choose another range

        Scenario: A table compares the plugs in the room
            Given "Kitchen" has "Fridge Plug" using 40 W with 1.2 kWh and £0.30 today, and "Kettle Plug" using 2 W with 0.4 kWh and £0.10
            When admin "jane" opens its energy dashboard, on the web app or the Android app
            Then a table lists each plug with its state, power now, share of the room's use today, use today and cost today
            And it starts sorted by cost today, highest first
            And clicking or tapping a column heading sorts by it, again reverses it, and the heading shows which way
            And the sort stays as live readings arrive, and on a phone the table scrolls sideways with the plug's name kept in view
            And selecting a plug moves the carousel to its chart

        Scenario: The table says when a plug's figures cannot be trusted
            Given "TV Plug" in "Bedroom 1" went offline at 14:20
            When admin "jane" opens the room's energy dashboard
            Then its state is "Offline" and its power "Offline", never its last power, and it sorts below every plug with a reading of power
            And its use and cost today show so far with the warning triangle, saying what a room's Plugs section says
            And a plug whose energy total has stopped going up shows the same triangle and words as there

        Scenario: A plug moved out of the room still adds up
            Given "Kettle Plug" used 0.5 kWh in "Kitchen" this morning, then moved to "Lounge"
            When admin "jane" opens the energy dashboard of "Kitchen"
            Then the cards still count the 0.5 kWh, and the table has a row "Moved out" with 0.5 kWh and its cost, and no power
            And the room's chart still includes it, and the carousel has no chart for "Kettle Plug" in "Kitchen"
            And the energy dashboard of "Lounge" counts only what it used there

        Scenario: A room with no plugs left has nothing to chart
            Given every plug has been moved out of "Utility"
            When admin "jane" opens /energy/rooms/utility
            Then it shows what the room used today, the room's chart for what was recorded there, and says "No plugs in this room"
            And an unknown room says "Room not found", with a way back to the Energy tab

        Scenario: The room dashboard says it only counts what is measured
            Given "Kitchen" has "Fridge Plug" and "Kettle Plug", and its oven is not on a plug or clamp
            When admin "jane" opens the room's energy dashboard, on the web app or the Android app
            Then an info icon beside the room's name says the figures are only what its registered plugs and meters measured
            And that anything not on a plug or clamp in the room, such as an oven, is not counted here and falls in "Everything else"
            And it is a small icon that opens the note on click or tap, not a banner over the cards

        Scenario: Everything else has its own dashboard
            Given the house is using 855 W and plugs account for 189 W
            When admin "jane" opens the "Everything else" card, on the web app or the Android app
            Then cards show "Now" 666 W, "Today" and "Cost today", and on the web app it has the address /energy/else
            And one chart scrolls back through time with the same Power, Use and Cost choice, with no carousel
            And there is no table, and it says this is what no plug accounts for
            And with no house reading "Now" says "No reading", and its figures are marked incomplete

        Scenario: Android dashboards pass being driven end to end
            Given the web app and service for this feature are deployed, and the Android screens exist but are uncommitted
            When RoomEnergyScreen and EverythingElseEnergyScreen are composed with what the service would send, and driven by touch
            Then every scenario of this feature that names the Android app holds there: cards, carousel, dragging back, touch and hold, table, moved out, no plugs, room not found
            And dragging a chart moves the chart and not the carousel, and the back arrow returns to the Energy tab where it was left
            And RoomEnergyScreenTest passes under Robolectric, or on a device if one is plugged in
            And then the Android work is committed and pushed, and the APK is built for the user

    Feature: Energy areas
        As the household admin
        I want to measure electricity that does not belong to a heated room
        So that I can see what all the lights, or the garage and office, use and cost

        An energy area is a named thing with meters and no valves, such as "Lights" or "Garage/Office". It is not a
        room: it has no heating, and appears only on the Energy tab, for admins. An area's whole-area meter is a clamp
        that measures all of it. Its use is "of which" the house meter's, and its cost is use at the electricity price
        alone: standing charges are never split onto areas. Lights are a whole-house figure, never split across rooms.

        Scenario: An admin creates an energy area
            Given admin "jane" is on the Energy tab, on the web app or the Android app
            When they choose "Add area" and enter "Garage/Office"
            Then POST /api/energy/areas is sent and GET /api/energy/areas lists it with id "garage-office"
            And it can be renamed or deleted, and a name already used by another area gets 409
            And a member gets 403

        Scenario: An energy area stays out of heating
            Given energy area "Garage/Office" exists
            When anyone opens the Rooms screen, schedules, holiday settings or the boiler views
            Then "Garage/Office" is not listed, and GET /api/rooms does not include it
            And it is not offered as a place to put a valve or a sensor, and never asks for heat

        Scenario: A clamp measures a whole area
            Given meter "Garage Clamp" is waiting to be set up and energy area "Garage/Office" exists
            When admin "jane" chooses "Measure an energy area", then "Garage/Office"
            Then PUT /api/energy/areas/garage-office/whole-meter/Garage Clamp is sent and it leaves "Waiting to be set up"
            And GET /api/energy/areas shows it as the area's whole-area meter
            And a meter that reports power but no energy can be chosen; one that reports neither gets 422
            And the house meter cannot be chosen (409), and a meter measures one area at most (409)
            And choosing another meter replaces it, and each five-minute record keeps the area it was measuring when recorded

        Scenario: A whole-area meter is recorded from its power
            Given "Garage Clamp" measures "Garage/Office"
            When it reports 1200 W for five minutes, and its energy total jumps up and down
            Then history records 0.1 kWh and an average of 1200 W for that five minutes
            And each power reading holds until the next one, as for the house meter
            And minutes with no reading are marked missing, never 0 kWh

        Scenario: A plug is put in an energy area
            Given "Desk Plug" is monitored and energy area "Garage/Office" exists
            When admin "jane" chooses "Garage/Office" for it
            Then PUT /api/energy/areas/garage-office/meters/Desk Plug is sent and GET /api/meters shows it in that area
            And a plug is in one room or one area at most: choosing an area takes it out of its room
            And use already recorded stays where it was recorded

        Scenario: An area with no clamp is the sum of its plugs
            Given "Desk Plug" used 0.2 kWh and "Heater Plug" used 0.3 kWh today, both in "Garage/Office", which has no whole-area meter
            When an admin sends GET /api/dashboard?period=today
            Then "areas" lists "Garage/Office" with 0.5 kWh and its cost

        Scenario: An area's plugs are part of its clamp's figure
            Given "Garage Clamp" recorded 1.0 kWh today in "Garage/Office" and "Desk Plug" in that area used 0.2 kWh
            When an admin sends GET /api/dashboard?period=today
            Then "areas" lists "Garage/Office" with 1.0 kWh, not 1.2 kWh, with "Desk Plug" 0.2 kWh and a rest of 0.8 kWh
            And if the plugs add up to more than the clamp the rest is 0, never negative

        Scenario: Everything else counts an area once
            Given the house meter recorded 8.0 kWh today, "Garage Clamp" 1.0 kWh, "Desk Plug" behind it 0.2 kWh, "Lights Clamp" 0.5 kWh and "Fridge Plug" in "Kitchen" 1.2 kWh
            When an admin reads everything else today, from the dashboard, its usage over time or its live card
            Then it is 5.3 kWh: the house minus each whole-area meter, and minus only those plugs not behind one
            And electricityKwh is still 8.0, and totalCost does not add areas again
            And "devices" does not list whole-area meters, and "rooms" does not list areas

        Scenario: The Energy tab shows areas below the rooms
            Given "Lights" is using 60 W and has used 0.5 kWh today
            When admin "jane" opens the Energy tab, on the web app or the Android app
            Then an "Other areas" section below the room cards and above "Everything else" has a card for "Lights"
            And the card shows power now, use and cost for the chosen period, with live readings like a room card
            And with no areas, the section is replaced by the "Add area" action alone
            And members do not see areas

        Scenario: An area card opens its energy dashboard
            Given "Garage/Office" has "Garage Clamp" and "Desk Plug"
            When admin "jane" opens its card, on the web app or the Android app
            Then it opens the same dashboard as a room: cards, carousel, scrolling back through time, table and the Power, Use and Cost choice
            And on the web app it has the address /energy/areas/garage-office, and a member sent there goes to the Rooms screen
            And GET /api/energy/areas/garage-office/usage takes "window" and "to", with the same shape as a room's
            And the carousel starts with the whole area, then "Desk Plug", and the table has a "Rest of Garage/Office" row with no chart
            And an area measured by a clamp alone shows one chart and no table

        Scenario: Lights are not split across rooms
            Given "Lights Clamp" measures "Lights" and "Lamp Plug" is in "Lounge"
            When admin "jane" opens the dashboards of "Lights" and "Lounge"
            Then "Lounge" counts only "Lamp Plug", with no share of "Lights"
            And "Lights" has no per-room figures

        Scenario: Two areas that share a circuit say so
            Given "Lights Clamp" also measures the garage and office lights, which "Garage Clamp" measures too
            When admin "jane" chooses "Shares a circuit with" "Garage/Office" in the settings of "Lights"
            Then PUT /api/energy/areas/lights/overlaps/garage-office is sent, and DELETE clears it
            And the figures do not change: the shared use cannot be measured, so it is not guessed
            And the dashboards of both areas say "Some use is also counted in" the other
            And "Everything else" says it reads low by whatever "Lights" and "Garage/Office" both measure

        Scenario: A clamp with no reading leaves the area incomplete
            Given "Garage Clamp" had no reading from 14:20 to 15:00, while "Desk Plug" kept reporting
            When an admin reads the use of "Garage/Office" over that time
            Then the area's points are marked incomplete, hold only what the clamp recorded, and do not use the plug's figures instead
            And "Now" says "No reading", never its last power
            And "Everything else" is marked incomplete for that time

        Scenario: A clamp that leaves Zigbee2MQTT leaves its area
            Given "Garage Clamp" measures "Garage/Office"
            When it is renamed or unpaired in Zigbee2MQTT
            Then the area has no whole-area meter, and the energy settings carry the notice "Garage Clamp was removed from Zigbee2MQTT"
            And its recorded use is kept and still counted in the area for when it was recorded

        Scenario: Deleting an area keeps what was recorded
            Given "Garage/Office" has "Garage Clamp" and "Desk Plug", with use recorded
            When admin "jane" deletes the area and confirms
            Then "Garage Clamp" is waiting to be set up again, "Desk Plug" stays monitored in no room, and any "Shares a circuit with" is cleared
            And recorded samples are kept, and the house figure does not change
            And from then on "Everything else" no longer subtracts "Garage Clamp", while past periods still do

    Feature: Two-clamp meters
        As the household admin
        I want each clamp of a two-clamp meter to be a meter of its own
        So that one device can measure the whole house on one clamp and all the lights on the other

        Some meters, such as the Tuya PJ-1203A, have two clamps, A and B, on one Zigbee device. Zigbee2MQTT reports
        them as power_a, energy_a, power_b and energy_b (and power_ab, their difference). Each clamp is a meter named
        after the device and its letter, such as "Mains and lights A", and can be given any job a meter can have.

        Scenario: A two-clamp meter is discovered as two meters
            Given the bridge device list contains "Mains and lights", which exposes power_a, energy_a, power_b and energy_b but no power
            When a client sends GET /api/meters
            Then it lists "Mains and lights A" and "Mains and lights B", each with device "Mains and lights" and its clamp "A" or "B"
            And "Mains and lights" itself is not listed, and a one-clamp meter has device equal to its name and clamp null

        Scenario: Each clamp's readings come from the device's state
            Given "Mains and lights" is discovered
            When "zigbee2mqtt/Mains and lights" publishes {"power_a":535.2,"energy_a":0.04,"power_b":25.5,"energy_b":0,"power_ab":509.7}
            Then GET /api/meters shows "Mains and lights A" at 535.2 W and 0.04 kWh, and "Mains and lights B" at 25.5 W and 0 kWh
            And power_ab is not used
            And a message carrying only clamp A's readings leaves clamp B's as they were

        Scenario: A clamp fitted the wrong way round is still counted as use
            Given "Mains and lights" reports {"power_b":25.5,"energy_flow_b":"producing"}, or {"power_b":-25.5}
            When clamp B is recorded for five minutes
            Then it counts 25.5 W of use, never negative
            And GET /api/meters shows "Mains and lights B" with reversed true
            And the app says "This clamp reads backwards. Its use is still counted."

        Scenario: Each clamp does its own job
            Given "Mains and lights A" is the house meter and "Mains and lights B" measures energy area "Lights"
            When A reports 600 W and B reports 60 W for five minutes
            Then history records 0.05 kWh for the house and 0.005 kWh for "Lights"
            And "Everything else" is the house minus "Lights", as for any whole-area meter

        Scenario: Both clamps go offline or leave together
            Given "Mains and lights A" is the house meter and "Mains and lights B" measures "Lights"
            When Zigbee2MQTT says "Mains and lights" is offline
            Then both clamps are shown offline, and their minutes are missing, not 0 kWh
            When "Mains and lights" is unpaired
            Then electricityMeter is null and "Lights" has no whole-area meter, with the notice "Mains and lights was removed from Zigbee2MQTT" once

    Feature: Energy management
        As the household admin
        I want one page where I set up everything that measures electricity
        So that I can add, change or remove the house meter, areas, clamps and plugs without hunting through the app

        Scenario: Admins have an energy management page
            Given admin "jane" is on the Energy tab of the web app
            When they choose "Manage"
            Then /energy/manage opens with sections "Main meter", "Daily target", "Areas", "Monitored devices" and "Waiting to be set up"
            And each meter shows its label, power now, and whether it is offline or stale
            And a member sent to /energy/manage goes to the Rooms screen, and is never shown "Manage"

        Scenario: An admin names a meter
            Given "Mains and lights A" is discovered
            When admin "jane" chooses "Rename" on it and enters "Mains"
            Then PUT /api/meters/Mains and lights A/label is sent with {"label":"Mains"}
            And GET /api/meters shows label "Mains", and the label is used wherever the meter is shown
            And an empty label goes back to the Zigbee2MQTT name, a label over 40 characters gets 400, and a member gets 403
            And the label is kept after a restart, and dropped when the meter leaves Zigbee2MQTT

        Scenario: An admin changes or removes the main meter from the page
            Given "CCU Clamp" is the house meter and "Mains and lights A" is waiting to be set up
            When admin "jane" chooses "Change" under "Main meter" and picks "Mains and lights A"
            Then PUT /api/meters/Mains and lights A/house is sent, and "CCU Clamp" is waiting to be set up again
            And "Stop using" sends DELETE /api/meters/Mains and lights A/house after a confirmation
            And only meters that report power and are not measuring something else are offered

        Scenario: An admin sets up areas from the page
            Given admin "jane" is on /energy/manage
            Then under "Areas" they can add, rename and delete an area (deleting asks to confirm)
            And choose, replace or remove its whole-area meter, add or remove its plugs, and set "Shares a circuit with"
            And each area shows its whole-area meter's power now, or "Sum of its plugs" when it has none

        Scenario: A meter is given a new job in one step
            Given "CCU Clamp" is the house meter
            When admin "jane" chooses "Change job" on it and picks "Monitor in a room", then "Kitchen", and confirms
            Then it stops being the house meter and is monitored in "Kitchen", in one request: PUT /api/meters/CCU Clamp/job with {"job":"monitored","room":"kitchen"}
            And the jobs are "house", "area" (with an area id), "monitored" (with an optional room or area id) and "none"
            And a job the meter cannot do gets 422 and changes nothing, and a member gets 403

        Scenario: A meter's new job does not rewrite what it recorded
            Given "CCU Clamp" recorded 5.0 kWh as the house meter this morning, then became monitored in "Kitchen"
            When an admin reads today's dashboard
            Then the house's electricityKwh still holds the morning's 5.0 kWh, and "Kitchen" counts only what was recorded after the change

        Scenario: An admin stops monitoring a device
            Given "Desk Plug" is monitored in "Garage/Office"
            When admin "jane" chooses "Stop monitoring" on /energy/manage and confirms
            Then DELETE /api/meters/Desk Plug/monitored is sent and it is waiting to be set up again
            And its recorded use is kept and still counted for when it was recorded

    Feature: Daily energy target
        As the household admin
        I want to set how much electricity we aim to use each day
        So that I can see during the day whether we are on target

        The target is for the house meter's electricity. Expected use so far is the target times the share of the day
        gone, so at 12:00 half the target is expected. The delta is use so far minus expected use so far: below zero is
        under target.

        Scenario: An admin sets a daily target
            When admin "jane" sends PUT /api/settings/energy with dailyKwhTarget 12.0
            Then GET /api/settings/energy has dailyKwhTarget 12.0, kept after a restart, and null (the default) means no target
            And a target of 0 or less, or over 1000, gets 400, and a member gets 403
            And on /energy/manage "Daily target" shows "12.0 kWh a day" with its cost at today's price, such as "£3.24"

        Scenario: Today's dashboard says whether we are on target
            Given dailyKwhTarget is 12.0 and the house meter has recorded 5.0 kWh by 12:00
            When an admin sends GET /api/dashboard?period=today at 12:00
            Then "target" has kwh 12.0, usedKwh 5.0, expectedKwh 6.0, deltaKwh -1.0, deltaCost (-1.0 kWh at the electricity price), onTarget true and projectedKwh 10.0
            And with 7.0 kWh used it has deltaKwh 1.0 and onTarget false
            And projectedKwh is use so far divided by the share of the day gone, and null in the first 30 minutes

        Scenario: No target or no house meter means no target figures
            Given no daily target is set, or no house meter is chosen
            When an admin sends GET /api/dashboard?period=today
            Then "target" is null

        Scenario: Missing readings are said, not guessed
            Given dailyKwhTarget is 12.0 and the house meter had no reading from 09:00 to 10:00
            When an admin sends GET /api/dashboard?period=today
            Then "target" has incomplete true and usedKwh counts only what was recorded

        Scenario: Days in a week or month are compared with the target
            Given dailyKwhTarget is 12.0 and the house used 10.0 kWh on Monday and 14.0 kWh on Tuesday
            When an admin sends GET /api/dashboard?period=week
            Then Monday has targetDeltaKwh -2.0 and Tuesday targetDeltaKwh 2.0, and "target" sums the days: kwh 84.0 over 7 days
            And today's day is compared with the share of today gone, as in period today

        Scenario: The Energy tab shows the delta for today
            Given dailyKwhTarget is 12.0, and at 12:00 the house has used 5.0 kWh
            When admin "jane" opens the Energy tab on the web app with Today chosen
            Then a target card says "1.0 kWh under pace" in green, with "5.0 of 12.0 kWh, 7.0 kWh left today, on track for 10.0 kWh"
            And a bar shows use so far against a mark for where use should be by now
            And over pace it says "1.0 kWh over pace" in amber (ahead of where use should be by now, not over the day's target), and with readings missing it adds "Some readings are missing"
            And once the day's target is used up it says how far past it, such as "0.4 kWh past the target"
            And for week and month each day's bar has a target line, and days over target are marked
            And with no target set it offers "Set a daily target", which goes to /energy/manage

    Feature: Who is signed in
        As the household admin
        I want to see who is signed in, on what, and when they were last active
        So that I can tell whether an old phone is still signed in and sign it out

        Each sign-in starts a session: one device signed in once. Its token carries the session's id. Sessions from
        before this feature, whose tokens carry no id, keep working and are shown as "Earlier sign-in".

        Scenario: A sign-in starts a session the admin can see
            Given "sam" signs in on the web app in Chrome on Android
            When admin "jane" sends GET /api/admin/users/{sam's id}/sessions
            Then it lists one session with an id, device "Chrome on Android", signedInAt, lastSeen and signedOutAt null
            And the Android app's sessions say "Android app"
            And no token, password or address is in the response

        Scenario: Each person shows whether they are active
            Given "sam" last used the app 2 minutes ago and "kim" last used it 3 days ago and then signed out
            When admin "jane" sends GET /api/admin/users
            Then "sam" has activeNow true, lastSeen 2 minutes ago and sessions 1
            And "kim" has activeNow false, lastSeen 3 days ago and sessions 0
            And activeNow means a request in the last 5 minutes, and sessions counts sessions not signed out or expired

        Scenario: Last seen is kept without writing on every request
            Given "sam" makes 100 requests in one minute
            Then the session's lastSeen is updated at most once a minute, and survives a restart

        Scenario: Signing out ends only that session
            Given "sam" is signed in on two devices
            When "sam" signs out on one of them
            Then that session has signedOutAt set and its token returns 401, and the other device keeps working

        Scenario: An admin signs a device out
            Given "sam" is signed in on two devices
            When admin "jane" chooses "Sign out" on one of "sam"'s sessions
            Then DELETE /api/admin/users/{sam's id}/sessions/{session id} is sent and that token returns 401
            And "Sign out everywhere" on "sam" sends DELETE /api/admin/users/{sam's id}/sessions and every token of "sam" returns 401
            And a member gets 403

        Scenario: Ended sessions are listed until they are old
            Given "sam" signed out 10 days ago and a token of "sam" expired yesterday
            When admin "jane" lists "sam"'s sessions
            Then both are listed as signed out, the expired one at its expiry
            And sessions that ended more than 30 days ago are no longer listed

        Scenario: Signing in and out is in the activity feed
            When "sam" signs in on Chrome on Android, signs out, or is signed out by "jane"
            Then the activity feed shows "sam signed in on Chrome on Android", "sam signed out" or "jane signed sam out"
            And wrong passwords are not in the feed

        Scenario: The People page shows who is signed in
            Given admin "jane" opens People on the web app
            Then each person shows "Active now", "Last seen 3 hours ago" or "Not signed in"
            And opening a person lists their sessions with device, signed in, last seen and a "Sign out" button
            And the session "jane" is using says "This device" and has no "Sign out" button

    Feature: Energy access
        As the household admin
        I want to let chosen people see the Energy tab
        So that everyone in the house can watch what we use without being able to change how it is measured

        Admins always have energy access. A member has it only once an admin gives it, and starts without. Energy access
        is read only: the dials, the history chart and the totals with their costs. Choosing meters, prices, the daily
        target, areas and plugs stays admin only, and so do the room, area and "Everything else" energy pages. A member
        with energy access sees every room's and area's use on the Energy tab, even rooms they cannot otherwise see.

        Scenario: An admin gives a member energy access
            Given member "sam" is signed in
            When admin "jane" sends PUT /api/admin/users/{sam's id}/energy with energy true
            Then GET /api/admin/users shows "sam" with energy true, kept after a restart, and every admin with energy true
            And GET /api/auth/me for "sam" has energy true, and sending energy false takes it away again
            And setting energy on an admin returns 409, because admins always have it, and a member sending it gets 403

        Scenario: Energy access lets a member read the Energy tab and nothing more
            Given member "sam" has energy access
            Then "sam" gets 200 from GET /api/dashboard, GET /api/power, GET /api/energy/history and GET /api/energy/totals, and can open /ws/power
            And "sam" gets 403 from GET /api/meters, GET /api/settings/energy, PUT /api/settings/energy and GET /api/energy/areas
            And a member without energy access gets 403 from all of them and /ws/power refuses them

        Scenario: The Energy tab shows for people with energy access
            Given member "sam" has energy access
            When "sam" opens the web app or the Android app
            Then there is an Energy tab with the dials, the history chart and the totals with their costs
            And it has no "Manage" button, the room and area cards do not open their energy pages, and "Set a daily target" is not offered
            And on People an admin turns "Energy" on or off for each member

    Feature: Energy dials
        As someone with energy access
        I want the Energy tab to read like a car's dashboard
        So that I can see at a glance what the house is using, what is using most, how the day is going and its peak

        Four gauges in a row: today's peak on the far left, the whole house, the busiest room or area, and the daily
        target as a fuel gauge on the far right. On a phone, web or Android, only the whole-house dial shows, full width,
        with the counters under it: four gauges bunch up on a small screen. They replace the single live dial and its sparkline. The room, area and "Everything else" cards stay
        below the history chart.

        Scenario: The house dial shows the whole house now
            Given the house meter reports 2400 W
            When someone with energy access opens the Energy tab
            Then the house dial reads "2.40 kW" and is labelled "Whole house"
            And it keeps the live dial's scale, colours and smooth sweep

        Scenario: The second dial follows the busiest room or area
            Given "Kitchen" uses 900 W, "Utility" 300 W and energy area "Lights" 150 W
            When someone with energy access opens the Energy tab
            Then the second dial reads "900 W" and is labelled "Kitchen"
            And "Everything else" and devices in no room or area are never on it
            And it uses the house dial's scale, so the two needles compare
            And with no room or area reporting it reads "No rooms reporting"

        Scenario: The second dial does not flicker between close rooms
            Given the second dial shows "Kitchen" at 900 W
            When "Utility" rises to 950 W
            Then the dial stays on "Kitchen", because "Utility" is less than 10% ahead
            And when "Utility" is at least 10% and 50 W ahead of "Kitchen" for 30 seconds the dial moves to "Utility"
            And if "Kitchen" stops reporting the dial moves at once to whatever is busiest

        Scenario: The fuel gauge drains towards the daily target
            Given dailyKwhTarget is 12.0 and at 12:00 the house has used 5.0 kWh
            When someone with energy access opens the Energy tab
            Then the fuel gauge's needle is 7/12 of the way from E to F, with "7.0 kWh left" and under it "of 12.0 kWh · 1.0 under pace", the pace in green
            And a tick marks where the needle should be by now, 6/12 from E
            And above 80% of the target used its low fuel light is on, and past the target the needle rests on E with "1.0 kWh over" and "of 12.0 kWh"
            And today's cost is not repeated there: the usage counter shows it
            And with no target it reads "No daily target", and an admin is offered "Set a daily target"

        Scenario: Today's peak is the highest five-minute power since midnight
            Given the house meter recorded five-minute samples today, the highest an average of 6200 W at 18:05
            When someone with energy access sends GET /api/power
            Then todayPeak has watts 6200 and at 18:05
            And a sample at 00:00 counts in the day before, so just after midnight todayPeak is null
            And the peak gauge reads "6.20 kW" with "at 18:05" on the house dial's scale

        Scenario: Today's peak says what was using the power
            Given at the peak "Kitchen" used 3500 W, area "Lights" 400 W and the house 6200 W
            When someone with energy access sends GET /api/power
            Then todayPeak.parts lists "Kitchen" 3500 W, "Lights" 400 W and "Everything else" 2300 W, largest first
            And a room counts its plugs in it at the time, an area its clamp when recorded or else its plugs, and a plug behind a clamp only once
            And on the web app hovering or tapping the peak gauge shows the parts with their watts and shares, and on Android tapping it does

        Scenario: The trip counter ticks up with the house's use
            Given the house has used 8.20 kWh today and its average power over the last 5 minutes is 1200 W
            When someone with energy access watches the dashboard for a minute
            Then the counter labelled "Usage today", like a car's trip meter, rolls up from "8.200" kWh by about 0.020 kWh,
                three decimals so it is seen to move, with today's cost under it
            And a "£" switch beside its label swaps it to "Cost today" in pounds to five decimals, such as "£2.21400", rolling
                up at the price per kWh, and "kWh" swaps back; the choice is remembered
            And its wheels are near square-cornered, in a plain monospaced face
            And it never rolls back: when today's recorded figure arrives it carries on from whichever is higher
            And it starts again from 0 at midnight

        Scenario: Energy totals say which household day they are for
            Given the household's timezone is Europe/London and it is 23:58 on 10 November there
            When someone with energy access sends GET /api/energy/totals
            Then it has day "2026-11-10" and dayEndsAt "2026-11-11T00:00:00Z", the next midnight in the household's timezone
            And at is the service's time it was worked out, so a device whose own clock is wrong can tell how long is left
            And at 00:00:30 it has day "2026-11-11" and today 0 kWh, whatever timezone the server's own clock is set to
            And on a clock-change day dayEndsAt is still the next local midnight, 23 or 25 hours after the one before

        Scenario: The usage counter starts again at the household's midnight
            Given the dashboard has been open all evening and the usage counter reads "19.412" kWh at 23:59
            When the household's midnight passes
            Then the counter reads "0.000" kWh and rolls on from there, without waiting for the next poll or a refresh
            And a recorded figure for the new day is taken even though it is lower than the counter's last figure
            And yesterday's figure is never carried into the new day: totals for a day the counter has left are ignored
            And the odometer's Day, Week, Month and Year do not count yesterday's roll a second time
            And the day is the household's, from the totals' day and dayEndsAt, not the phone's or browser's own clock or timezone

        Scenario: The dashboard reloads its day figures at the household's midnight
            Given the dashboard is open at 23:59, on the web app or on Android, such as a wall-mounted tablet left on overnight
            When the household's midnight passes
            Then within a minute it fetches the totals, today's dashboard and today's peak again
            And the usage counter, today's peak, the fuel gauge, today vs yesterday and the odometer all show the new day without a refresh
            And a device whose clock is wrong or set to another timezone still turns over at the household's midnight
            And a device that slept or lost its connection over midnight fetches them again as soon as it is back

        Scenario: The odometer totals the day, week, month or year
            Given the house has used 8.2 kWh today, 51.0 kWh since Monday, 190.0 kWh since the 1st and 2100.0 kWh since 1 January
            When someone with energy access sends GET /api/energy/totals
            Then it has today, week, month and year, each with kwh and cost at today's price, and the electricity price per kWh
            And the dashboard's usage counter shows any one of them (see "The usage counter shows the day, week, month or year")

        Scenario: The usage counter shows the day, week, month or year
            Given the house has used 8.2 kWh today, 51.0 kWh since Monday, 190.0 kWh since the 1st and 2100.0 kWh since 1 January
            When someone with energy access taps the usage counter on the web app or the Android app
            Then "Usage today" becomes "Usage this week" showing 51.0 kWh, and further taps give "Usage this month", "Usage this year"
                and "Usage today" again
            And whichever it shows rolls on at the house's power like today's, with its cost under it, or in pounds with the "£" switch
            And the choice is remembered, and the period starts again from 0 at the household's midnight, Monday, the 1st or 1 January
            And there is no separate odometer: its place on the dashboard is left free for what comes next, such as solar
            And "Today vs yesterday" still compares today, whichever period the counter shows

        Scenario: Today is compared with yesterday at the same time
            Given at 18:00 the house has used 8.2 kWh today and had used 7.0 kWh by 18:00 yesterday
            When someone with energy access sends GET /api/energy/totals
            Then yesterdaySoFar has kwh 7.0, and the dashboard says "▲ 1.2 kWh vs yesterday" in amber
            And less than yesterday says "▼ 0.5 kWh vs yesterday" in green, and with no samples yesterday nothing is said

    Feature: Energy history chart
        As someone with energy access
        I want a chart of what the house used under the dials that I can drag back in time
        So that I can compare today with any day, week, month or year before it

        The chart is the house meter's use. Its points line up with the calendar in the household's timezone: five
        minutes over a day, an hour over a week, a day over a month and a month over a year. Costs are at today's prices,
        like the dashboard. While the chart shows the present it moves on with each five-minute sample.

        Scenario: The house's use over a day, week, month or year
            Given the house meter has recorded samples for the last two years
            When someone with energy access sends GET /api/energy/history?range=month
            Then it has currency, from, to, earliest and 30 points, one per day ending today, each with at, kwh, cost, watts and incomplete
            And range day has a point per five minutes over 24 hours, week a point per hour over 7 days and year a point per month over 12 months
            And a point for a whole day or month has targetKwh (the daily target times its days), null for five minutes or an hour or with no target
            And to=<time> ends the range at that time instead of now, a time in the future means now, and any other range gets 400

        Scenario: The chart can be dragged back in time
            Given someone with energy access has the chart on Week
            When they drag it to the right
            Then it shows earlier hours, loading more as it needs them, and stops at the earliest sample
            And it cannot be dragged past now, and "Now" brings it back to the present
            And Day, Week, Month and Year change the range, keeping the time at its right edge

        Scenario: The chart says what each point used and cost
            When someone with energy access hovers over or taps a point on the chart
            Then it shows its time, kWh, cost and, for a day or month, the target
            And day and week are a line of power, month and year are bars of kWh with a target line, and incomplete points are marked

    Feature: Energy flow
        As someone with energy access
        I want a diagram of where the house's energy went
        So that I can see which rooms, areas and devices use the most

        The flow runs from sources (electricity from the house meter, gas from the smart meter or its estimate, and
        later solar) through places (rooms, energy areas and heating) to devices (plugs). Band widths are kWh.
        Costs are at today's prices, like the dashboard.

        Scenario: Where the house's energy went
            Given today the house meter recorded 11.8 kWh, room "Kitchen" 3.0 kWh from plugs "Kettle" 1.2 kWh and "Oven" 1.8 kWh,
                area "Lights" 0.9 kWh from its clamp, and gas was 22.1 kWh
            When someone with energy access sends GET /api/energy/flow?period=today
            Then it has currency, from, to and nodes, each with id, name, kind (source, place or device), kwh and cost
            And links, each with from, to and kwh: "Electricity" to "Kitchen" 3.0, "Lights" 0.9 and "Everything else" 7.9,
                "Kitchen" to "Kettle" 1.2 and "Oven" 1.8, and "Gas" to "Heating" 22.1
            And a plug in no room or area is a place of its own, a plug behind an area's clamp counts once, and a room or area
                using nothing is left out
            And period is today, yesterday, week or month like the dashboard, and anything else gets 400

        Scenario: Heating's gas is split between the rooms that called for it
            Given today the boiler burned 20.0 kWh of gas, while "Lounge" was below its target for 3 of those hours and
                "Bedroom" for 1
            When someone with energy access sends GET /api/energy/flow?period=today
            Then "Heating" links to "Lounge" 15.0 kWh and "Bedroom" 5.0 kWh, its gas shared by each room's hours calling for heat
            And those links have estimated true, as the meter does not know which room used the gas
            And burning time when no room was below its target goes to "Heating, no room calling"

        Scenario: The Energy tab shows where the energy went
            Given someone with energy access opens the Energy tab on the web app or the Android app
            Then a "Where it went" card shows the flow as a Sankey diagram: sources on the left, places in the middle,
                devices on the right, each labelled with its name and kWh, bands as wide as their kWh
            And hovering or tapping a node or band shows its kWh, cost and share of its source, and estimated bands are hatched
            And Today, Yesterday, Week and Month choose the period, and the choice is remembered
            And with no house meter chosen the card says "Choose a house meter to see where electricity goes" and still shows gas

    Feature: Gas meter
        As the household admin
        I want the dashboard's gas to be what the smart meter recorded
        So that the heating's cost is real, not an estimate from boiler hours

        Gas comes half-hourly from the smart meter through the Bright app's Glowmarkt API, signed in with
        BRIGHT_USERNAME and BRIGHT_PASSWORD. It arrives one to two days late. Without those settings, and for the time
        the meter has not reported yet, gas stays the estimate: boiler hours times the boiler's burn rate, the meter's
        measuredBoilerKw where there is one, else boilerAverageKw.

        Scenario: Gas readings are fetched from the smart meter
            Given BRIGHT_USERNAME and BRIGHT_PASSWORD are set and the meter has half-hourly gas readings from 31 October to the half-hour starting 23:30 on 5 November
            When the service starts, and every 30 minutes after
            Then it keeps each half-hour's gas kWh, from the first reading on, fetching only half-hours it does not have yet
            And a fetch that fails changes nothing and is tried again 30 minutes later
            And without BRIGHT_USERNAME and BRIGHT_PASSWORD nothing is fetched

        Scenario: The dashboard's gas is the meter's where it has reported
            Given the meter recorded 52.4 kWh of gas on 2 November and has reported up to the end of 5 November
            When an admin sends GET /api/dashboard?period=week on 7 November
            Then 2 November's gasKwh is 52.4 with gasEstimatedKwh 0, costed at the gas price
            And 6 and 7 November's gas is boiler hours times the boiler's burn rate, all of it gasEstimatedKwh
            And a day the meter reported only part of is the meter's kWh plus the estimate for the rest of it
            And gasMeteredTo is the end of the last half-hour reported, and null with no meter readings

        Scenario: The Energy tab says where gas comes from
            Given the meter has reported gas up to the end of 5 November
            When admin "jane" opens the Energy tab on the web app or the Android app
            Then under "Use and cost" it says "Gas is from the smart meter up to 6 Nov 00:00, estimated from the boiler's hours after."
            And the gas figure has a chip saying "Estimated" when all of it is estimated and "Part estimated" when some is
            And in the bar per day the estimated part of gas is hatched, with "Gas, estimated from boiler hours" in the key
            And over a week each bar is named, such as "Sun 1" with "meter" or "estimate" under it, and has its total above it
            And with no meter readings it says "Gas is estimated from the boiler's hours."

        Scenario: The car dashboard shows the gas meter's latest day
            Given the meter has reported up to the end of 5 November, 68.0 kWh that day and 63.8 kWh on 4 November
            When someone with energy access sends GET /api/energy/totals
            Then gas has date "2026-11-05", kwh 68.0, cost at the gas price and previousKwh 63.8
            And gas is null until the meter has reported a whole day, and previousKwh is null with no readings the day before
            And next to the usage counter on the web app and the Android app a counter labelled "GAS · THU 5 NOV" shows "068.0"
                kWh on wheels with its cost and "▲ 4.2 kWh vs Wed", or "▼" when less

        Scenario: A day of gas, half-hour by half-hour
            Given the meter has reported up to the end of 5 November
            When someone with energy access sends GET /api/energy/gas
            Then it has date "2026-11-05" (the latest whole day), kwh, cost at the gas price, currency and halfHours, each with at and kwh
            And previous and next are the nearest days either side with readings, null at either end
            And date=2026-11-04 gives that day, a day with no readings gets 404, and with no readings at all it is 404
            And on the Energy tab of the web app and the Android app a "Gas" card, right under the dials, charts the half-hours as bars, with
                the day's kWh, cost and busiest half-hour, and buttons for the day before and the day after

        Scenario: The boiler's average burn rate from the meter
            Given over the 7 days up to the latest whole day the boiler burned for 30 hours and the meter recorded 363 kWh
            When someone with energy access sends GET /api/energy/gas
            Then measuredBoilerKw is 12.1 (the meter's kWh over the boiler's hours on those days), null with under 5 hours
            And boilerAverageKw is the setting the estimate falls back on
            And on the Gas card admins see "Boiler average from the meter: 12.1 kW, used for estimates"

        Scenario: The gas estimate uses the boiler's burn rate from the meter
            Given measuredBoilerKw is 9.6 over the 7 days up to the meter's latest whole day and boilerAverageKw is 10.1
            When an admin sends GET /api/dashboard?period=today after the boiler has burned for 2.2 hours today
            Then today's gasKwh is 21.12, 2.2 hours times 9.6 kW, all of it gasEstimatedKwh, with gasBurnRateKw 9.6 and
                gasBurnRateFrom "meter"
            And with measuredBoilerKw null (no meter, or under 5 boiler hours in those days) it is boilerAverageKw, from "setting"
            And as the meter reports each half-hour its estimate is replaced by the meter's kWh, so a day's gas ends as the meter's
            And measuredBoilerKw moves on with each whole day the meter reports
            And under "Use and cost" it says "Gas is from the smart meter up to 10 Nov 01:30, estimated after from the boiler's
                hours at 9.6 kW (the meter's last 7 days)."

    Feature: Area notifications
        As the household admin
        I want to be told when an energy area such as "Lights" is still using power after a few hours
        So that someone can turn off what was left on

        An area is using power while its live reading (its clamp, else the sum of its plugs) is over 5 W. Each area has
        at most one notification, which goes either to the admin who set it up or to everyone with energy access, on
        their phones and browsers.

        Scenario: An admin sets up a notification for an area
            Given admins "jane" and "john" and energy area "Lights"
            When "jane" sends PUT /api/energy/areas/lights/alert with hours 1.5 and notify "me"
            Then GET /api/energy/areas/lights/alert returns hours 1.5, notify "me" and by "jane", for "jane" and for "john"
            And notify "everyone" sends it to everyone with energy access instead, and a later PUT replaces it
            And DELETE /api/energy/areas/lights/alert turns it off (hours, notify and by null), and it is kept over a restart
            And hours must be from 0.5 to 24 in steps of 0.5 and notify "me" or "everyone" (else 400), an unknown area is 404 and members get 403
            And deleting the area forgets its notification

        Scenario: An area still using power after the hours notifies
            Given "jane" set "Lights" to notify "me" after 1 hour
            When "Lights" uses over 5 W from 18:00 without a break
            Then at 19:00 "jane" gets "Lights still on" with "Lights has been using power for 1 h, now 85 W", and nobody else
            And with notify "everyone", "jane", "john" and member "sam" with energy access get it, and a member without energy access does not
            And no more is sent until "Lights" has dropped to 5 W or under and come back on
            And a reading that is missing does not count as a break, but dropping to 5 W or under starts the time again

        Scenario: An admin sets up an area notification in the app
            Given admin "jane" opens the "Lights" area on the web app, or its row under Areas on the Android app
            When she chooses "Set up notification", enters 2 hours, chooses "Just me" or "Everyone" and saves
            Then PUT /api/energy/areas/lights/alert is sent with hours 2 and notify "me" or "everyone"
            And it shows "Notifies you after 2 h" (or "Notifies everyone after 2 h", or "Notifies jane after 2 h" to others)
            And "Turn off" sends DELETE /api/energy/areas/lights/alert

    Feature: Connection setup
        As the household admin
        I want to point the app at my own MQTT broker and Zigbee2MQTT from the app, and test each step
        So that anyone with a broker and Zigbee2MQTT can run it without editing environment variables

        Connection settings are the broker (host, port, TLS, username, password) and the Zigbee2MQTT base topic. They are
        kept in the database. Environment variables (MQTT_URL, MQTT_USERNAME, MQTT_PASSWORD, Z2M_BASE_TOPIC) only fill
        them in the first time the service starts with an empty database, so an existing install carries straight over.
        Setup follows the first admin (Feature: Users and sign-in) and is for admins only.

        Scenario: A new install with no broker asks for connection setup
            Given there are no connection settings and MQTT_URL is not set
            When admin "jane" signs in and sends GET /api/setup/status
            Then it returns {"broker":"missing","zigbee2mqtt":"missing","complete":false}
            And the web app and Android app open the setup steps (Broker, Zigbee2MQTT, Test notification) instead of the Rooms screen
            And heating, energy and rules stay off until the broker is connected, with nothing published

        Scenario: An existing install is filled in from the environment
            Given an empty database and MQTT_URL "tcp://mosquitto:1883", MQTT_USERNAME "heating", MQTT_PASSWORD "secret" and Z2M_BASE_TOPIC "zigbee2mqtt"
            When the service starts
            Then the connection settings are host "mosquitto", port 1883, TLS off, username "heating" and base topic "zigbee2mqtt"
            And GET /api/setup/status returns complete true, and no setup steps are shown
            And once settings are saved, a later restart with different environment values keeps the saved settings

        Scenario: An admin tests a broker before saving it
            Given admin "jane" is on the Broker step
            When she sends POST /api/setup/broker/test with host "mosquitto", port 1883, TLS off, username "heating" and password "secret"
            Then the service connects with those details, subscribes, disconnects, and returns {"ok":true}
            And nothing is saved and the running connection is not changed

        Scenario: A broker test explains why it failed
            Given admin "jane" is on the Broker step
            When the broker test cannot complete
            Then it returns 200 with ok false and one reason:
                | cause                          | reason            | message                                              |
                | name does not resolve          | unknown_host      | Can't find "mosquito". Check the host name.          |
                | nothing listening on the port  | refused           | Nothing answered on mosquitto:1884.                  |
                | no answer within 5 seconds     | timeout           | mosquitto:1883 didn't answer within 5 seconds.       |
                | broker rejects the login       | not_authorized    | The broker turned down that username and password.   |
                | TLS handshake fails            | tls               | Couldn't make a secure connection: <detail>.         |
            And the step shows that message beside the field it points to, and keeps what was typed
            And host must be filled and port from 1 to 65535 (else 400, with nothing tried)

        Scenario: Saving a broker reconnects without a restart
            Given the service is connected to broker "mosquitto"
            When admin "jane" sends PUT /api/setup/broker with host "mqtt.local", port 8883, TLS on and a login that tests ok
            Then the service disconnects from "mosquitto", connects to "mqtt.local" and subscribes to every topic it had
            And valves, meters and rules carry on with no restart, and the change is in the activity log as "jane changed the broker"
            And a broker that fails its test is not saved (422 with the test's reason) and the old connection keeps running

        Scenario: Broker passwords are never sent back
            Given a saved broker password "secret"
            When an admin sends GET /api/setup/broker
            Then it returns host, port, TLS, username and passwordSet true, and never the password
            And a PUT with no password keeps the saved one, and password "" clears it
            And the password is stored encrypted with HEATING_SECRET_KEY, and the service refuses to start if saved secrets exist and the key is missing or wrong
            And members get 403 on every /api/setup route

        Scenario: Zigbee2MQTT is found on the broker
            Given the broker is saved and Zigbee2MQTT publishes "z2m/bridge/info" (retained) with version "2.1.0"
            When admin "jane" sends GET /api/setup/zigbee2mqtt/discover
            Then within 5 seconds it returns [{"baseTopic":"z2m","version":"2.1.0","devices":14}]
            And the Zigbee2MQTT step offers "z2m (version 2.1.0, 14 devices)" to choose, or a base topic typed by hand
            And PUT /api/setup/zigbee2mqtt with baseTopic "z2m" saves it and valves and meters resubscribe under "z2m/"

        Scenario: Zigbee2MQTT is not found on the broker
            Given the broker is saved and no "+/bridge/info" message arrives within 5 seconds
            When admin "jane" sends GET /api/setup/zigbee2mqtt/discover
            Then it returns []
            And the step says "No Zigbee2MQTT found on this broker. Check it's running and connected to the same broker, or type its base topic."
            And a typed base topic is saved only once "<base>/bridge/state" reads "online", else 422 with "Zigbee2MQTT at <base> is offline"
            And two bridges found are both offered, and none is chosen for her

        Scenario: Setup ends with a test notification
            Given the broker and Zigbee2MQTT are saved
            When admin "jane" chooses "Send test notification" on the last step
            Then her phones and browsers get "Test notification" with "Notifications from your home are working"
            And if she has no phone or browser registered, the step says so and offers to turn on notifications here
            And "Finish" marks setup complete and opens the Rooms screen, and setup can be done again from Settings > Connection

        Scenario: The broker connection drops after setup
            Given setup is complete and the broker goes away
            When the connection is lost
            Then the service retries with backoff (1, 2, 4 seconds and so on, never more than 60) and logs each try
            And GET /api/setup/status returns broker "disconnected" with the time it dropped and the last reason
            And the web and Android apps show a banner "Lost connection to your broker since 18:04" linking to Settings > Connection
            And when it reconnects it resubscribes, the banner goes, and nothing published while it was down is replayed

    Feature: Event log
        As the household admin
        I want every MQTT message the service sees kept in a log for a while
        So that I can find the events I care about and build notifications from them

        An event is one MQTT message: when it arrived, its topic, its payload and whether it was retained. The service
        subscribes to "#" and writes each event to the event log in the database, with nothing else to install. Payloads
        that are JSON are kept as JSON; anything else is kept as text. Events are kept for 7 days by default.

        Scenario: Every message on the broker is logged
            Given the broker is connected
            When "z2m/front_door" publishes {"contact":false,"battery":91} and "garage/temp" publishes "18.5"
            Then the event log has both, each with arrival time, topic, payload and retained flag
            And "z2m/front_door" is kept as JSON and "garage/temp" as text "18.5"
            And retained messages delivered on connect are logged once, flagged retained

        Scenario: Event logging keeps up with a busy broker
            Given the broker publishes 200 messages a second for a minute
            When the service logs them
            Then events are written in batches off the MQTT thread, and valves, meters and rules are not slowed
            And if the write queue goes over 10,000 events the oldest waiting are dropped and the dropped count is shown in the log's status
            And payloads over 64 KB are kept cut to 64 KB and flagged truncated

        Scenario: Old events are cleared out
            Given the event log keeps 7 days
            When an event is older than 7 days, or the log is over its size limit of 500 MB
            Then the oldest events are deleted, once an hour
            And an admin can set the days from 1 to 90 with PUT /api/events/settings (else 400), and members get 403

        Scenario: Topics can be left out of the event log
            Given admin "jane" adds "z2m/bridge/logging" and "homeassistant/#" to the excluded topics
            When those topics publish
            Then they are not logged, and rules on those topics still run
            And the settings show each excluded topic with how many messages a minute it was sending

        Scenario: Events are searched through the API
            Given the event log has events from the last 7 days
            When an admin sends GET /api/events with topic "z2m/+", from and to times, text "contact", field "contact", sort "at desc" and limit 100
            Then it returns matching events newest first, with a cursor for the next page, and the total matched
            And topic accepts MQTT wildcards, text matches in topic or payload, and field matches events whose JSON has that field
            And GET /api/events/topics returns each topic seen, with its message count, last seen time and last payload
            And members get 403

    Feature: Event explorer
        As the household admin
        I want a searchable table of events, with chips to filter and sort it
        So that I can quickly find the messages a device sends and pick the ones I want to be told about

        Scenario: An admin opens the event explorer
            Given admin "jane" opens Events on the web app, or Settings > Events on the Android app
            Then she sees a table of the newest events: time, topic, a short view of the payload, and a "retained" tag
            And new events appear at the top as they arrive, unless she has scrolled down or paused it with "Pause"
            And members do not see Events

        Scenario: Events are filtered with chips
            Given admin "jane" is on Events
            When she adds chips "Topic: z2m/front_door", "Field: contact" and "Last 24 h"
            Then the table shows only events matching all three, and the count of matches
            And chips can be Topic (wildcards allowed), Device (a Zigbee2MQTT friendly name), Field, Field value (e.g. "contact = false"), Text, Time range and Retained
            And each chip can be removed on its own, "Clear" removes them all, and the chips are kept in the page address so a search can be shared or bookmarked
            And typing in the search box offers matching topics, devices and fields as chips to add

        Scenario: Events are sorted and paged
            Given admin "jane" is on Events with 3,000 matching events
            When she sorts by topic, then by time
            Then the table sorts by that column, up or down, and loads the next 100 as she scrolls
            And sorting or filtering never loads the whole log at once

        Scenario: An event's payload is shown in full
            Given admin "jane" opens an event from "z2m/front_door"
            Then she sees its full payload, laid out as JSON with each field's path (such as "contact" or "update.state")
            And each field has "Filter by this" (adds a Field value chip) and "Notify me when…" (opens the rule builder, Feature: Rule builder)
            And "Show this topic" filters to that topic, and "Copy" copies the payload

        Scenario: Topics are browsed as a tree
            Given admin "jane" chooses the Topics view on Events
            Then she sees topics as a tree split on "/" (z2m > front_door), each with message count, last seen and last payload
            And choosing a topic filters the table to it
            And topics with no message for 7 days are greyed

    Feature: Notification rules
        As the household admin
        I want rules that send a notification when an MQTT message matches what I choose
        So that I am told when the front door opens, a leak is found or a button is pressed

        A rule has a name, a topic (MQTT wildcards allowed), conditions on the payload (all must match), a trigger
        ("changes to" or "is"), a title and message, who it notifies ("me" or "everyone"), a cooldown, optional quiet hours
        and an on/off switch. A condition is a field path, an operator (=, ≠, >, <, ≥, ≤, contains, exists) and a value.
        "Changes to" fires only when the conditions start to match for that topic, so a device that resends its whole
        state does not notify again. Rules are for admins; members only receive what is sent to "everyone".

        Scenario: An admin creates a rule
            Given admin "jane"
            When she sends POST /api/rules with name "Front door opened", topic "z2m/front_door", condition contact = false, trigger "changes to", title "Front door", message "Front door opened", notify "everyone" and cooldown 60 seconds
            Then it returns 201 with the rule, its id, enabled true and by "jane"
            And GET /api/rules lists it, PUT /api/rules/{id} replaces it, DELETE removes it, and rules are kept over a restart
            And name, topic and title must be filled, the topic a valid MQTT filter, cooldown 0 to 86400 seconds and notify "me" or "everyone" (else 400 naming the field)
            And members get 403 and an unknown rule is 404

        Scenario: A rule fires when a message changes to match
            Given rule "Front door opened" on "z2m/front_door" when contact changes to false
            When "z2m/front_door" publishes contact true, then false, then false again with a new battery reading
            Then one notification is sent, on the first false
            And publishing true then false again sends another, once the cooldown has passed

        Scenario: An "is" rule fires on every matching message
            Given rule "Button pressed" on "z2m/hall_button" when action is "single"
            When "z2m/hall_button" publishes action "single" twice, 2 minutes apart
            Then two notifications are sent
            And a message within the cooldown does not send, and is counted as skipped by cooldown

        Scenario: A wildcard rule tracks each topic on its own
            Given rule "A door opened" on "z2m/+" when contact changes to false
            When "z2m/front_door" and then "z2m/back_door" both change to contact false
            Then each sends its own notification, and the cooldown is kept per topic
            And a topic with no "contact" field never matches

        Scenario: The message fills in from the event
            Given a rule with title "{{topic.last}}" and message "{{topic.last}} opened, battery {{payload.battery}}%"
            When "z2m/front_door" publishes {"contact":false,"battery":91}
            Then the notification reads "front_door" with "front_door opened, battery 91%"
            And a Zigbee2MQTT topic also offers {{device}} (the friendly name), and a missing field fills in as "?"
            And the API refuses a template with an unknown placeholder (400)

        Scenario: Quiet hours hold back a rule
            Given rule "Front door opened" has quiet hours 23:00 to 07:00
            When the front door opens at 02:00
            Then no notification is sent, and the event is noted as skipped by quiet hours in the rule's history
            And the rule can be set to "send when quiet hours end" instead, which sends one summary at 07:00 ("Front door opened 3 times overnight")

        Scenario: A noisy rule is held back
            Given rule "Motion" fires 30 times in 10 minutes
            When it would fire again
            Then the service stops sending it for an hour, sends the rule's owner "Motion paused" with "It fired 30 times in 10 minutes", and notes it in the rule's history
            And the rule shows "Paused until 19:10" with "Resume now"

        Scenario: A payload that is not JSON can still match
            Given rule "Garage cold" on "garage/temp" with condition value < 5
            When "garage/temp" publishes "4.5"
            Then the rule matches, with "value" meaning the whole payload, as a number when it reads as one
            And a JSON rule on a text payload never matches and logs no error

        Scenario: An admin tests a rule against recent events
            Given rule "Front door opened" and 7 days of events on "z2m/front_door"
            When "jane" sends POST /api/rules/test with the rule
            Then it returns the last 20 events on that topic, each marked "would send", "conditions not met: contact is true" or "no change"
            And the notification each would send, with its title and message filled in
            And "Send me a test" sends the filled-in notification to "jane" only

        Scenario: A rule's history shows what it did
            Given rule "Front door opened" has run for a week
            When an admin sends GET /api/rules/{id}/history
            Then it lists each match, newest first: time, topic, payload, and what happened (sent to 3 people, skipped by cooldown, skipped by quiet hours, paused)
            And each sent notification links to its delivery (Feature: Notification deliveries)

    Feature: Rule builder
        As the household admin
        I want to make a rule by picking an event and the fields I care about
        So that I never have to type a topic or a field path

        Scenario: A rule is built from an event
            Given admin "jane" opens an event from "z2m/front_door" with {"contact":false,"battery":91} and chooses "Notify me when…" on "contact"
            Then the rule builder opens with topic "z2m/front_door", condition contact = false and trigger "changes to"
            And name "Front door contact false", title "front_door" and message "front_door: contact is false", all editable
            And it shows the last 5 events on that topic, each marked whether it would have sent

        Scenario: Conditions are mapped from the payload's fields
            Given admin "jane" is in the rule builder for "z2m/front_door"
            When she adds a condition
            Then she picks a field from those seen on that topic (contact, battery, linkquality…), each showing its recent values
            And the operators offered fit the field: true/false fields get "is true"/"is false", numbers get = ≠ > < ≥ ≤, text gets = ≠ contains, and every field gets "exists"
            And the value offers the values seen (such as "single", "double", "hold" for action), or can be typed

        Scenario: The topic is widened to similar devices
            Given admin "jane" is in the rule builder for "z2m/front_door"
            When she chooses "Any device like this"
            Then the topic becomes "z2m/+", and it lists which topics that matches and which of them have a "contact" field
            And she can instead pick devices from a list, which makes one rule per device

        Scenario: Rules are suggested for a Zigbee2MQTT device
            Given "z2m/bridge/devices" lists "front_door" exposing contact and battery, and "hall_leak" exposing water_leak
            When admin "jane" opens a device under Events, or chooses "Suggested" in Notifications
            Then she is offered "front_door opened" (contact changes to false), "front_door battery low" (battery < 15) and "hall_leak leak detected" (water_leak changes to true)
            And occupancy, action (one per action value) and tamper are suggested the same way
            And choosing one opens the rule builder filled in, and devices that already have that rule show "Set up"

        Scenario: Rules are managed in the app
            Given admin "jane" opens Notifications on the web app, or Settings > Notifications on the Android app
            Then she sees each rule with its name, topic, who it notifies, when it last sent, and an on/off switch
            And area notifications (Feature: Area notifications) are listed with them
            And she can filter rules with chips (On, Off, Paused, Notifies me, Notifies everyone, Topic) and open one to edit, test, see its history or delete it
            And deleting asks "Delete Front door opened?" first

    Feature: Notification deliveries
        As the household admin
        I want every notification kept in a queue until it is delivered, with failures retried and kept aside
        So that a push service outage never loses a notification, and I can see what went wrong

        A delivery is one notification for one person. It is queued in the database, then sent to each of their phones
        and browsers. A delivery that fails is retried up to 3 times with backoff (30 seconds, 2 minutes, 10 minutes),
        then it goes to failed deliveries, where it stays until an admin retries or clears it. This applies to rule
        notifications, area notifications and heating alerts alike.

        Scenario: A notification is queued then delivered
            Given rule "Front door opened" notifies "everyone", and "jane" and "sam" each have a phone
            When it fires
            Then two deliveries are queued, one each, and sent within 5 seconds
            And each is marked sent with the time, and which phones and browsers took it
            And deliveries queued before a restart are sent after it

        Scenario: A failed delivery is retried with backoff
            Given the push service fails for "jane"'s phone
            When her delivery is sent
            Then it is tried again after 30 seconds, 2 minutes and 10 minutes
            And if a try succeeds it is marked sent with the number of tries
            And a phone the push service says is no longer registered is removed and not retried, and her other devices still get it

        Scenario: A delivery that keeps failing goes to failed deliveries
            Given "jane"'s delivery has failed 4 times
            Then it is moved to failed deliveries with each try's time and error
            And admins see "1 failed notification" on Settings > Notifications, with a list showing who, what, when and why
            And "Retry" queues it again, "Retry all" queues every failed one, and "Clear" removes it
            And a notification older than 24 hours is retried with "(sent late)" after its title

        Scenario: Deliveries are searched in the app
            Given admin "jane" opens Settings > Notifications > Deliveries
            Then she sees a table of deliveries newest first: time, rule, person, status (queued, sent, retrying, failed) and tries
            And she can filter with chips (Status, Rule, Person, Time range) and sort by any column, the same as Events
            And opening one shows the event that caused it
            And deliveries are kept 30 days, and members get 403 on GET /api/deliveries

# Deferred (not to be built yet)
# - Mirror schedules to the valve weekly_schedule as a fallback when the service is down
# - Open-window alerts (TRVZB exposes the setting, not a state; needs inference)
# - Valve temperature calibration offsets (revisit if rooms without a sensor read badly)
# - Group schedules
# - Several sensors averaged in one room
# - Home-screen widget
# - Frigate cameras: camera tab, snapshot notifications, presets (separate piece of work)
# - Live camera video, with its own setup
# - Prices that change over time (the dashboard uses the current prices for the whole period)
# - Room electricity beside room heating hours (tag a plug as a heater: portable heaters, towel rails)
# - Valve not responding: a state on the zone view beside the feed event (needs an openapi change)
# - Energy management, daily target and who is signed in on the Android app
# - A notification when the day ends over the daily target
