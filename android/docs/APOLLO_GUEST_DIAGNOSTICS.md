# Apollo controls in Guest and factory eligibility

On the owner's vehicle, previously unavailable Apollo controls in the factory settings became visible and selectable after the third-party Apollo feature was enabled. The displayed profile stayed in Guest. The observation establishes the state of the controls on that vehicle.

## How the third-party implementation handles Apollo

- **The switch records a preference in the third-party app.** The app saves the selected state and can start its own Apollo handler on applicable vehicle variants. The code also contains a separate path that can process the preference after startup. The switch position therefore records what the app was asked to do.
- **The companion component changes what the factory settings process sees.** It can provide a substitute answer when the factory page checks subscription status. It can also change the outcome of some checks used to select a vehicle equipment branch. The page can then present a different set of options while the profile indicator still displays Guest.
- **The reviewed code can request changes beyond the screen.** It can request a factory status refresh and send assistance-state requests to the driving-assistance controller. Another path can change persisted state. What happens next depends on the installed software and the controller. The factory page, saved requests, and controller response are separate observations.

The recovered code establishes these behaviors for the reviewed build. The owner's observation establishes that the buttons became selectable in Guest. The exact path taken on that head unit, the results of the remaining factory checks, and the controller's response are unverified.

## What OVC can show

In Open Voyah Controls, search for **Apollo** and open **Apollo and driving assistance**. **Open factory assistance** opens the factory page if the head unit exposes that shortcut. **Open vehicle menu** provides a route to the same section through the factory menu. The factory software handles equipment detection, account requirements, training, entitlement and confirmations. The FREE 318 owner manual documents **Vehicle → Driving assistance** and identifies TLC as a prerequisite for NOA. [FREE 318 H97D owner manual](https://m.dsmp.voyah.com.cn/userguide/h97d/h97D-40.html)

**Read Apollo diagnostics** can identify the installed firmware and visible factory packages, the available menu entry points, and cached requests when the system grants read access. **Save Apollo report** exports that snapshot. The cache can retain requests the controller never applied and carries no information about who initiated them. Eligibility, training, and controller acceptance remain outside that report's scope. [Controls diagnostics](APOLLO_CANDIDATE.md#read-only-diagnostics)

## What to check for factory setup

1. Use the factory **Driving assistance** page and note the exact message shown for the disabled option. Record whether it asks for a vehicle account, training, service renewal, or a different prerequisite. Keep account data and QR codes out of any shared capture.
2. If the message is missing or unclear, save the Apollo report and record the installed firmware version and vehicle model. Those details let VOYAH support identify the applicable factory procedure.
3. Ask VOYAH support whether that firmware supports the requested Apollo controls under Guest or requires a vehicle account with specific permissions. Ask for the official steps for the installed build. That answer is the evidence needed to evaluate an additional OEM-supported path in OVC. [Factory workflow and H97D limits](VEHICLE_AUTOMATION.md#factory-apollonoa-procedure)

## Answers needed before OVC changes anything

Each planned change is gated on a specific answer. While a question stays unanswered the screen keeps its current read-only shortcuts and report.

**From the vehicle**

1. Which control names on the factory **Driving assistance** page changed from unavailable to selectable? Quote the labels exactly as the page shows them, and say whether each one kept its new state after leaving the page, after a normal restart, and after the vehicle has stood overnight.
2. Did the page accept a selection, or did the row only become tappable? Report what the indicator did: gray standby, blue active, unchanged, or a return to the previous state. Say whether any driving behavior changed.
3. What exact text does the page show for an option that stays unavailable? A capture with the account data and QR codes hidden is enough to tell a vehicle-account requirement from training, service renewal or absent equipment.
4. Does the page report Guest while a vehicle account is signed in, or is the vehicle genuinely in Guest? Name the account state the page itself reports rather than the profile indicator alone.
5. Which installed software produced the observation? Save the Apollo report and confirm the factory settings version, code and base APK hash it records, together with the vehicle model and the firmware version shown by the vehicle. [Read-only diagnostics](APOLLO_CANDIDATE.md#read-only-diagnostics)
6. Does the third-party state survive a restart, and does the factory page return to its earlier availability when that state is switched off? A report saved before and after each change shows whether the cached factory requests move with it; a changed cache still says nothing about the controller.
7. Is the head unit available for one supervised repeat: report, enable, report, restart, report? That sequence is the cheapest way to separate a saved request from an accepted controller state.

**About what OVC should offer**

8. Should the Apollo screen stay with its current shortcuts and report, or also keep the owner's note about the exact factory message next to a saved report so a support request carries the prerequisite in the owner's own words?
9. Should a report be saved as a before and after pair for VOYAH support, and should the screen show the vehicle model and installed firmware version as copyable text? The saved report names the product and firmware fingerprint today, which is harder to quote in a support ticket.
10. Which single Apollo control matters to the owner, and what is the smallest outcome they would accept: a factory page they can operate, a controller state OVC can read back, or the assistance working on the road? The answer decides whether the work stays in diagnostics or needs a qualified write path. [Admission contract](APOLLO_CANDIDATE.md#admission-contract)
