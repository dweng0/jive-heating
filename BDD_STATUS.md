# BDD Status

Checked 322 scenario(s) across 87 test file(s).


## Feature: Service foundation

- [x] Health endpoint reports ok without a token
- [x] API rejects requests without a bearer token
- [x] API rejects requests with the wrong bearer token
- [x] API accepts requests with the correct bearer token
- [x] Service refuses to start without an API token
- [x] OpenAPI document is served

## Feature: Valve discovery

- [x] TRVZB valves are discovered from the bridge device list
- [x] New valves are unassigned
- [x] Valve state is ingested from its state topic
- [x] Partial state messages keep previously known fields
- [x] Valve goes offline
- [x] Valve removed from Zigbee2MQTT is dropped from its room

## Feature: Rooms

- [x] Create a room
- [x] Room names must be unique
- [x] Rename a room
- [x] Assign a valve to a room
- [x] A valve can only be in one room
- [x] Remove a valve from a room
- [x] Delete a room with valves
- [x] Newly assigned valve gets default settings
- [x] A valve reporting the wrong frost protection is put back to 7
- [x] Room list shows temperature, target, mode and heating

## Feature: Modes

- [x] Manual mode holds the target until changed
- [x] Off mode uses frost protection
- [x] Target outside the allowed range is rejected
- [x] Target is rounded to the nearest half degree
- [x] Knob turn in manual mode becomes the new manual target
- [x] Late reports of earlier commands are not knob turns
- [x] A report soon after a command is not a knob turn
- [x] A knob still turning when the service answers ends at its last position
- [x] A knob turned past the maximum sets the maximum
- [x] A valve switched off at its knob turns its zone off
- [x] A knob turned up on a valve whose zone is off turns it on in manual

## Feature: Schedule

- [x] New zones get the default schedule
- [x] Effective target follows the current block
- [x] Block change sends the new target to valves
- [x] A day may have at most six blocks
- [x] A day must start at midnight
- [x] Blocks must be in time order
- [x] Copy one day to other days
- [x] Schedule uses the configured time zone
- [x] Block inside the spring-forward gap starts when the clocks jump
- [x] Block in the repeated autumn hour applies once
- [x] Turning the valve knob overrides until the next schedule block

## Feature: Valve settings

- [x] Toggle open window detection
- [x] Toggle child lock
- [x] Toggle smart temperature control per room
- [x] Low battery is flagged

## Feature: Deployment

- [x] Docker image builds and runs the service
- [x] Example environment file documents every variable
- [x] Broker login comes from the environment
- [x] Broker connection is anonymous when no login is set
- [x] Service refuses a broker password without a username

## Feature: Grouped and independent control

- [x] Grouped room shows the average temperature of online valves
- [x] Grouped room ignores offline valves in its temperature
- [x] Grouped room sends one target to every valve
- [x] Switch grouped to independent copies settings to each valve
- [x] Switch independent to grouped requires a source valve
- [x] Switch independent to grouped takes the source valve's settings
- [x] Independent zones are controlled separately

## Feature: Heat demand

- [x] Zone calls for heat when below target
- [x] Zone stops calling for heat at target
- [x] Hysteresis stops chatter just below target
- [x] A valve reporting running state heat counts as demand
- [x] Offline valves never create demand
- [x] A zone whose valves cannot open does not call for heat
- [x] A late report of the old setpoint does not stop a zone heating
- [x] A valve that goes silent is treated as offline
- [x] A silent valve comes back when it reports again
- [x] Offline valves are reported to Uptime Kuma
- [x] Running state is polled every five minutes
- [x] Any calling zone turns heat demand on
- [x] Demand stays on for the minimum run time
- [x] Demand stays off for the minimum rest time
- [x] Demand is republished on startup
- [x] Heat demand is visible in the API

## Feature: Live updates

- [x] WebSocket requires the bearer token
- [x] Room change is pushed to connected clients
- [x] Valve temperature change is pushed
- [x] Heat demand change is pushed

## Feature: Boost

- [x] Boost a zone
- [x] Boost is mirrored to the valve's timer mode
- [x] Boost ends and the zone reverts
- [x] Cancel a boost early
- [x] Boost duration must be 30 to 180 minutes in 30-minute steps
- [x] Boost a zone that is off
- [x] Boost survives a service restart

## Feature: Holiday

- [x] Holiday sets every heating zone to the holiday temperature
- [x] Holiday leaves zones that are off alone
- [x] Holiday ends at the return time
- [x] Starting a holiday cancels active boosts
- [x] Boost is refused during a holiday
- [x] Holiday temperature defaults to 12
- [x] Holiday can start in the future

## Feature: Persistence and recovery

- [x] Rooms, zones and schedules survive a restart
- [x] Holiday survives a restart
- [x] Valves are resynced on startup
- [x] Boost that expired while down is cleared on startup

## Feature: Live MQTT integration

- [x] End to end against a real Mosquitto broker

## Feature: Whole house

- [x] Set every room to manual at one temperature
- [x] Put every room back on its schedule
- [x] Turn every room off
- [x] Boost every room
- [x] Cancel every boost
- [x] Independent rooms change every zone, and rooms without valves are left alone
- [x] Whole-house changes follow the same rules as one room

## Feature: Users and sign-in

- [x] Setup is required while there are no users
- [x] First admin is created with the service token
- [x] Setup is refused once a user exists
- [x] Setup is refused with the wrong service token
- [x] A user signs in and uses the API with their session token
- [x] Usernames are matched without regard to case
- [x] Wrong password and unknown user get the same answer
- [x] Sign-in is throttled after five failures for one username
- [x] The sign-in lock doubles with each further failure up to an hour
- [x] A successful sign-in clears the failure count
- [x] Sign-in is throttled per address across usernames
- [x] Passwords shorter than ten characters are refused
- [x] Passwords are never stored in plain text
- [x] A tampered or expired session token is rejected
- [x] The service token still works for scripts
- [x] Admin creates, lists and deletes users
- [x] Duplicate usernames are refused
- [x] Members cannot use admin endpoints
- [x] The last admin cannot be deleted or demoted
- [x] Admin resets a password and the user's sessions end
- [x] A user changes their own password
- [x] Sign out everywhere ends every session of that user

## Feature: Room access

- [x] A new member sees no rooms
- [x] An admin chooses the rooms a member can see
- [x] Room access refuses unknown rooms and admin users
- [x] A member controls the rooms they can see
- [x] A room a member cannot see answers as if it does not exist
- [x] Only admins see the whole house
- [x] Only admins create, rename, delete or fit out rooms
- [x] Making a member an admin gives them the whole house
- [x] New rooms are not given to members
- [x] Deleting a room takes it off every member's list
- [x] Members already signed up keep every room when room access arrives
- [x] Room access survives a restart
- [x] Members get history only for rooms they can see
- [x] Live updates only carry rooms a member can see
- [x] Notifications only tell members about rooms they can see
- [x] An admin ticks each member's rooms on the people screen
- [x] A member's app shows only their rooms

## Feature: Web app

- [x] Web sign-in sets an HttpOnly session cookie and returns no token
- [x] The session cookie authenticates reads
- [x] Changes made with the cookie need the web client header
- [x] Changing password on the web keeps that browser signed in
- [x] Web sign-out clears the cookie
- [x] A cookie WebSocket from another origin is refused
- [x] Signed-in people can download the Android app
- [x] The Android app is not served to strangers or when missing
- [x] The web app is served with a strict content security policy

## Feature: Installable web app

- [x] The web app can be installed to the home screen
- [x] The service worker is served from the root and never cached stale
- [x] An installed app with no connection says so
- [x] The service gives browsers its push key
- [x] A browser subscribes to notifications
- [x] Only real push services are accepted as subscriptions
- [x] Browsers get the same notifications as Android phones
- [x] Tapping a notification opens the room
- [x] Subscriptions the push service has dropped are forgotten
- [x] Account turns notifications on for this browser
- [x] iPhone Safari explains how to add the app to the home screen

## Feature: Groups

- [x] New groups hold the chosen rooms
- [x] Groups refuse duplicate names and unknown rooms
- [x] A room can be in more than one group
- [x] Rename a group and change its rooms
- [x] Deleting a group leaves its rooms alone
- [x] Deleting a room removes it from its groups
- [x] Groups list their average temperature, heating, mode and target
- [x] A group lights up only when every room in it heats
- [x] Group shows mixed when its rooms disagree
- [x] Groups set every member room to manual at one temperature
- [x] Turn a group off and back to schedule
- [x] Groups boost every member room and cancel together
- [x] Group changes follow the same rules as whole house
- [x] Groups survive a restart
- [x] Group change is pushed to connected clients

## Feature: Room sensors

- [x] Temperature sensors are discovered from the bridge device list
- [x] Sensor readings are ingested from its state topic
- [x] Unassigned sensors are listed the same way as unassigned valves
- [x] Sensors assigned to a room switch its valves to external
- [x] A sensor can only be in one room
- [x] Adding a second sensor to a room replaces the first
- [x] A room with a sensor shows the sensor's temperature
- [x] Heat demand uses the sensor's temperature
- [x] Independent rooms use the sensor for every zone
- [x] Each sensor reading is passed on to the room's valves
- [x] The sensor reading is re-sent to the valves every ten minutes
- [x] A valve added to a room with a sensor is switched to it
- [x] Unassigned valves return to their internal sensor
- [x] Removing the sensor from a room returns it to its valves
- [x] A silent sensor falls back to the valves
- [x] An offline sensor falls back to the valves
- [x] A sensor that reports again takes over again
- [x] Sensors that leave Zigbee2MQTT are dropped from their room
- [x] Room sensors survive a restart and are resent to valves
- [x] Offline room sensors are reported to Uptime Kuma
- [x] Sensor temperature change is pushed to connected clients

## Feature: Boiler state

- [x] Boiler state reads heating while a zone calls for heat
- [x] Boiler is cycling while it finishes its minimum run
- [x] Boiler is resting while it waits out its minimum rest
- [x] Boiler is idle when nothing calls for heat
- [x] Boiler state changes are pushed to connected clients
- [x] Minimum run and rest times come from configuration

## Feature: Heating history

- [x] Room temperatures and targets are recorded every five minutes
- [x] Boiler states are recorded as they change
- [x] A boiler state left open by a restart ends when the service last recorded
- [x] History older than a year is deleted
- [x] History for the last 24 hours, 7 days or 30 days is served
- [x] The whole-house line is the median of the rooms
- [x] Unknown history ranges are refused
- [x] A failed history write does not stop the heating
- [x] History survives a restart

## Feature: Dashboard

- [x] The dashboard charts each room, the house median and the boiler
- [x] The dashboard says when there is no history yet
- [x] A room's page shows its temperature and target over time

## Feature: App version

- [x] The account screen shows the app version

## Feature: Notifications

- [x] A phone registers for notifications
- [x] Someone turning the heating on notifies everyone else
- [x] A knob turn that turns the heating on notifies everyone
- [x] One action that starts several rooms sends one notification
- [x] Heating finished after a user action notifies everyone
- [x] Schedule heating sends no notifications
- [x] Each person can turn each notification off
- [x] Phones the push service no longer knows are forgotten
- [x] A failed notification does not stop the heating
- [x] The Android app asks to notify and has a switch for each notification

## Feature: Energy settings

- [x] Energy settings start with default prices
- [x] An admin changes the energy prices
- [x] Energy settings refuse impossible values
- [x] Only admins see or change energy settings
- [x] An admin edits prices in the app
- [x] Money is shown in the household's currency
- [x] An admin chooses the household's currency

## Feature: Boost settings

- [x] New installs boost to 22 by default
- [x] An admin's chosen boost temperature survives a restart
- [x] Impossible boost defaults are refused
- [x] Non-admins read the boost default but cannot change it
- [x] A boost without a target uses the household default
- [x] The apps start a boost at the household default
- [x] An admin sets the default boost temperature in the app

## Feature: Electricity meters

- [x] Electricity meters are discovered from the bridge device list
- [x] Meter readings are ingested from its state topic
- [x] New meters are listed as unassigned
- [x] An admin chooses the house electricity meter
- [x] The house meter is recorded every five minutes
- [x] The house meter's use is counted from its power
- [x] A meter that stops reporting is shown as stale, not as zero use
- [x] Unpairing the house meter clears it
- [x] New meters are offered in the app like new valves and sensors
- [x] The main meter is labelled as measuring the whole house
- [x] An admin ignores a device that needs no setting up
- [x] Ignored devices can be brought back

## Feature: Energy dashboard

- [x] The energy dashboard totals use and cost for a period
- [x] Changing a price recalculates past costs
- [x] Standing charges are shown on their own line
- [x] Days with gaps in history are marked incomplete
- [x] Gas-only totals when no meter is chosen
- [x] Only admins see the energy dashboard
- [x] The Energy tab shows energy use and cost

## Feature: Activity feed

- [x] A person changing a room is recorded with their name
- [x] Changing Off, Auto, boost or holiday is recorded
- [x] A change to a group or the whole house is one event
- [x] A knob turn is recorded with no actor
- [x] Schedule blocks starting and ending are recorded
- [x] The boiler going to heating says which rooms called for it
- [x] The boiler cycling down says which room reached its temperature
- [x] The boiler resting and restarting are explained
- [x] A valve or sensor losing contact is recorded once
- [x] Events with nothing new are not recorded
- [x] Rapid boiler changes collapse into one line
- [x] The feed is newest first and limited
- [x] Only the newest 500 events are kept
- [x] The feed survives a restart
- [x] New events are pushed to connected clients
- [x] Signed-out clients cannot read the feed
- [x] Members see only the rooms they can access
- [x] A failed activity write does not stop the heating
- [x] Schedules and automatic changes are never attributed to a person
- [x] The boiler chip opens the activity tray on the web app
- [x] Activity lines are worded in full on wide screens and briefly on phones
- [x] Long room names are shortened to fit on phones
- [x] The tray updates live while open
- [x] An empty feed says so
- [x] The feed unavailable does not hide the boiler state
- [x] The Android boiler chip opens the activity tray
- [x] The Android tray can be read with a screen reader

## Feature: Device monitoring

- [x] An admin monitors a plug
- [x] Monitored devices are recorded every five minutes
- [x] The energy dashboard lists monitored devices without double counting
- [x] The Energy tab shows each monitored device

## Feature: Room energy

- [x] An admin puts a monitored plug in a room
- [x] Only plugs in no room are listed on the Rooms screen
- [x] An admin stops monitoring a plug from the Rooms screen
- [x] A room's energy is the sum of its plugs
- [x] Moving a plug does not move its past use
- [x] The energy dashboard shows everything else
- [x] A room with an incomplete plug reading is marked incomplete
- [x] A plug that leaves Zigbee2MQTT is no longer monitored
- [x] Removing a room or a plug leaves no stray room use
- [x] The Energy tab shows each room's electricity
- [x] A plug is put in a room from the room's settings
- [x] A plug is put in a room straight after monitoring it
- [x] A plug that is offline now says so
- [x] A plug that comes back catches up on its use
- [x] A plug whose energy total stops going up is flagged
- [x] A room's electricity is charted over time
- [x] A plug losing contact is in the activity feed

## Feature: Live power

- [x] The house power streams live
- [x] The live power channel is admin only
- [x] A stale house reading is not shown as live
- [x] The dial moves smoothly
- [x] The dial colours follow the household's own use
- [x] The dial shows today's use
- [x] Room cards show live and daily use
- [x] Everything else is the unattributed remainder
- [x] Energy has its own tab
- [x] The dial's scale fits the household's biggest appliances
- [x] The dial colours come only from recorded power

---
**322/322 scenarios covered.**
