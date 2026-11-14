# Live page prototype: notes

Working notes for `docs/prototypes/live-page.html`, the standalone prototype of the planned **Live** energy tab. Nothing here is wired to the service yet; readings and weather are simulated.

Branch: `live-page-prototype` (local). Preview: open the HTML file in a browser.

## Decisions already made

- **Live is a new tab**, not a replacement for the Energy page.
- **Two views**: a 3D model of the house and a flat flow diagram, switched with buttons under the picture. The 3D view is drag to turn, pinch or scroll to zoom, with a very slow auto-turn when left alone.
- **Data contract**: `/ws/power` gains `flows: [{from, to, watts}]` plus `batteryPct`. The service owns the sign convention; clients only draw edges.
- **Colours**: solar green, battery blue, grid orange, home white. Stale/stopped readings are red.
- **Update model**: a reading every 10 s, eased over about 1.5 s. When readings stop the flows fade and the stage desaturates.
- **In-app updates** for the Android app (check and download a new APK from the service) are wanted, separate from this page.

## The house, as modelled

Coordinates are metres: x = east, z = south, y = up.

- **Orientation**: the front of the house faces **east** (street), the back faces **west** (garden). The solar panels are on **south-facing** slopes. Sun tracking must use this.
- Semi-detached bungalow. The neighbour's half is not drawn; the party wall is a plain brick gable on the north side.
- **Panels, 15 total** (Aiko 495 W, 7.425 kWp):
  - Kitchen (rear wing) roof, south slope: 6 portrait in a 3 x 2 block at the garden end, then 2 landscape along the top row.
  - Garage roof, south slope: 7 portrait in one row.
  - Drawn larger than true scale so they fill the roof.
- **Battery**: GoodWe tower (2 x 8.3 kWh, 16 kWh usable) with the 8 kW inverter, inside the front garage space. The office is the back half of the garage. The garage's south wall is cut away so the tower is visible.
- **Back garden**: lawn, patio, conservatory, hipped bedroom extension with French doors, 6 ft fence panels on three sides.
- **Front**: Scottish pebble garden with a tarmac path to the white front door, brick paver edging, hedge and red-stained fence on the neighbour side, brick street wall with red fence panels and a drive opening of about 4.9 m, brick wall with black railings on the garage side with a pebble strip, street light on the pavement to the right as you face the house.
- **Grid**: a pole and overhead wire to a meter box on the kitchen gable. This is a placeholder; how the supply really arrives is unknown.

## What is in the rendering

Three.js r128 (UMD build plus `examples/js` add-ons from a CDN), all textures drawn in code.

- Physical sky; sun position driven by a single day-to-night value (midday, golden hour, night with stars and moonlight).
- Weather: clear, passing cloud (drifting cloud shadows on every surface), overcast, rain (streaks, haze, wet reflective surfaces).
- HDR pipeline: 4x multisampled half-float target, bloom on values above white, ACES tone curve, light colour grade, vignette, grain.
- Reflection map re-baked from the sky when time or weather changes.
- Brick and tile textures at 512 px with normal maps and large-scale weathering.
- Windows: curtains or blinds by day, warm glow and light spill at night. Street light comes on at dusk.
- Panels take a green hue with output; battery tower and garage glow blue with charge level; flow lines pulse in the source colour.
- Phones get lighter settings (smaller shadow map, no multisampling, fewer rain streaks).
- Roof tiles: each tile gets its own shade, with dirt streaks running down the slope, moss patches and lichen specks, all generated in the shader from world position so no two areas match.
- Effects panel under the picture: switches for shadows, glow, cloud shadows, moss and weathering, vignette and grain, and 3D grass.
- 3D grass: about 60,000 instanced tufts of five blades over the whole back garden (12,000 lighter tufts on phones). Blades sway in a wind that picks up with cloud and rain, take cloud shadows, show mowing stripes and patchy colour, grow longer along the fences, and keep off the patio, gravel yard, garage and anything standing on the lawn. They receive shadows but don't cast them.
- `#cam=yaw,elevation,zoom` on the URL fixes the camera for comparing changes (e.g. `#cam=-1.2,0.5,0.55` looks at the lawn from the garden end).

## To wire to real data

- **Weather from an API.** The weather state (cloud cover, rain) should come from a real source, not buttons. Home Assistant already has a weather entity; the service could pass through cloud cover %, precipitation and condition. Needs a decision on source and on how it reaches the client (extra fields on `/ws/power`, or a separate endpoint polled every few minutes).
- **Real sun position.** Replace the day-to-night value with the sun's actual azimuth and elevation for the house's latitude, longitude and the current time (Home Assistant's `sun.sun` exposes both, or compute client-side). The model's axes already match the real orientation, so shadows and panel lighting would be true to life.
- **Live flows** from `/ws/power` once the edges contract is built, and battery % from the inverter.
- **Before the inverter is installed** the page should show grid to home only, with solar and battery marked as not connected (the "Today, no inverter yet" example).

## Up for discussion

- **Power flow lines**: current paths run along the ground and through the garage. Routes, thickness, how much they should stand out by day, and whether the dots or the pulse should carry the motion are all open.
- **Grass**: first pass is in (see above). Open: blade height and colour, whether the front gets any, and whether blades should cast shadows (costly).
- **Roof variety**: a first pass is in (see below). Open: how much moss, whether it should favour the north slopes and shaded areas, and the same treatment for brick, fences and paving.
- **Effect toggles**: a first pass is in (see below). Open: toggles for the remaining effects as they are added, a single quality setting for phones, and remembering the choice.
- **Missing post-processing** against the reference chain: ambient occlusion, screen-space reflections on wet surfaces, depth of field, sharpening, temporal anti-aliasing (multisampling is used instead).
- **Surroundings**: beyond the garden is plain ground fading to haze. Options are neighbouring roofs as simple shapes, hedges and trees, or keep it empty so the house stays the focus.
- **Phone performance**: untested on a real device. May need a quality setting.
- **Android**: the 3D view would most likely be this same scene in a WebView. Not decided.
- **Grid supply**: overhead or underground, and where the meter is.
- **Floorplan**: would correct room and extension sizes.

## Not started

- BDD scenarios for the Live page and for in-app updates (the project is BDD-first; scenarios go in `BDD.md` before code).
- The service-side edges contract.
- A release signing key for the Android app, needed for in-app updates.
