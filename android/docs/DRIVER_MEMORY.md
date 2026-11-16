# Driver seat and mirror memory

**Driver 1**, **Driver 2** and **Driver 3** recall the vehicle's three saved driver seat and mirror positions. Save or change those positions through the vehicle's own seat/mirror memory controls.

Press your assigned **Voice assistant** button and say the phrase for the saved position:

| Memory slot | English | Russian |
| --- | --- | --- |
| Driver 1 | “driver one” | “водитель один” |
| Driver 2 | “driver two” | “водитель два” |
| Driver 3 | “driver three” | “водитель три” |

The car must be in **P (Park)** at zero speed. Controls checks both immediately before sending the request and refuses it when vehicle-state readings are unavailable or too slow. The exact firmware and each physical command also need review; the shipped approval catalog is empty.

Controls asks the OEM memory system to recall the selected slot. The vehicle controls which seat and mirror positions it applies. A successful request currently confirms service acceptance, not that every position has been reached. Wait for the movement to finish and check the result. An unknown result does not trigger an automatic retry.

The separate **Driver A/B/C** commands concern OEM accounts. They do not select these numbered memory slots. Account-record restoration does not restore or undo seat/mirror movement; its narrower technical limits are described in [OEM account-record recovery limits](ACCOUNT_RECOVERY.md).
