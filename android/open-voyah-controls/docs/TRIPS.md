# Trips and vehicle readings

Open **Settings → Vehicle → Trips and vehicle readings** to start or finish a trip, review the completed journal, or see live readings. **Record trips automatically** starts a trip on a supported Drive or Reverse observation and finishes it on a supported Park observation. Neutral does not finish an active trip. Manual recording is available when the head unit provides no qualifying gear reading.

The existing Controls accessibility service provides the background observation lease. Without that lease, recording observes while Controls is open or while the explicitly started GPS-route service is active. Vehicle readings suspend on screen-off. A current trip and its last saved checkpoint survive a process or device restart. When observations resume, the journal keeps observed duration and marks any gap. Charge and fuel are the values received at the recorded endpoints. Elapsed time and missing endpoint values are never filled in from a clock gap or saved vehicle settings.

The journal keeps up to 100 completed trips. Use **Clear trip history** to remove completed trips, including their GPS routes, from the head unit. The journal and its automatic recording setting are local and excluded from portable settings archives. Optional desktop card selections travel in the existing desktop layout archive.

## What the cards measure

**Settings → Home screen → Desktop information cards** offers Current trip, Charge and fuel, Consumption over the last 2.5 km, Tyre pressure, Odometer, and Suspension displacement. Each card opens the journal. The pressure and displacement cards keep the four corners separate. A dash beside a wheel means that wheel has no reading.

| Reading | Source |
| --- | --- |
| Gear boundaries | The existing exact-APK-pinned Qinggan gear callback, where available. Otherwise Controls uses a readable Android Automotive `GEAR_SELECTION` property. |
| Odometer and trip distance | Android Automotive `PERF_ODOMETER` in kilometres. Trip distance is the difference from the measured start reading. A decreasing odometer invalidates the distance for that trip. |
| Charge and fuel | EV battery level in Wh divided by the current usable capacity where both are available. Fuel millilitres are displayed as litres. Nominal capacity and guessed percentages are excluded. |
| Consumption | Net observed change in battery energy and fuel level. The last-2.5-km card needs continuous odometer and level samples, and fuel refill breaks the fuel window. Usage charts show recorded segments and leave observation gaps open. |
| TPMS and suspension | Android Automotive `TIRE_PRESSURE` in kPa and, where authorized, `VEHICLE_PASSIVE_SUSPENSION_HEIGHT` in mm relative to neutral. This is passive displacement, not the commanded air-suspension level or service mode. |

Android Automotive readings require an Automotive build with the Android car service, an exact readable property configuration and effective Android permission. The user can request vehicle-reading permissions exposed by their system from the live readings panel. The observation-only reader performs no writes. [Android Automotive property contracts](https://developer.android.com/reference/android/car/VehiclePropertyIds)

The existing Qinggan gear channel is covered by its documented OVC firmware pin. VOYAH OS 6.1.0 on the Free 318 in Guest has prior UI compatibility evidence independent of these new cards. It provides no qualification for these new Automotive property readings. Coverage of odometer, charge, fuel, individual tyres and passive suspension on that head unit needs a read-only property inventory, Android grants and readings checked against its factory display. Unsupported or unauthorized fields remain unavailable. [OVC vehicle automation sources](VEHICLE_AUTOMATION.md)

## GPS privacy

GPS is off for every trip until the driver confirms **Record this route** and grants precise location. Capture uses the Android GPS provider in a visible foreground service, with no map, network position provider, upload, diagnostic output, or archive export. Stop recording in Controls or from its notification. The service also stops at trip completion, location denial, its route-point limit, or after six hours. Capture never restarts automatically after process death or reboot. A GPS route can only include the part recorded after consent.

The route graph uses the saved local coordinates and colours segments by the recorded GPS speed. Unavailable speed has its own legend entry. Long breaks are drawn as disconnected segments. The route is independent of vehicle odometer distance.
