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

The Apollo screen also carries what a report cannot read. **What the factory page showed** keeps the driver's own words: the exact control label, the message the factory page displays, and the account it asks for. That note is marked as entered by the driver in the saved file, is never stored in the app or its settings archive, and never reaches the log. **Copy support details** puts the model, installed firmware and factory settings identity from the latest snapshot on the clipboard, so a support request can quote them without opening the file. A saved report is named with the local date and minute, and records a wall-clock time beside the head unit's elapsed time, because elapsed times restart with the head unit and cannot order a before and after pair on their own. A head unit whose clock is still unset is recorded as such, and its captures cannot be ordered by that clock either.

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

## Answers from available evidence

1. **Which controls changed and how long did the change last?** The owner reported that previously unavailable controls on the factory Apollo page became visible and selectable while the profile indicator still showed Guest. The exact labels and number of affected controls were not recorded. There are no observations for leaving and reopening the page, restarting the head unit, or returning after overnight sleep. That leaves persistence unknown for each control.

2. **Did the factory page accept a choice?** The report confirms that the buttons could be selected. It does not identify a specific setting that the page accepted and retained, such as a switch that remained on after the page was reopened. There is no recorded standby or active indication, reversal, or change in driving behavior. The H97D manual describes gray NOA as enabled and waiting for activation, and blue NOA as active. Neither color was reported for this observation. [FREE 318 H97D owner manual](https://m.dsmp.voyah.com.cn/userguide/h97d/h97D-40.html)

3. **What did the disabled option say?** No exact factory message or redacted screenshot accompanies the observation. An explicit prompt can distinguish a request to sign in, complete training or renew service. A disabled control with no prompt is inconclusive. It can leave account state, equipment support, entitlement and other factory conditions unresolved.

4. **Was the account genuinely in Guest?** Guest was the displayed profile. The internal vehicle-account session and the status presented by the factory account or entitlement page were not recorded. The display label establishes which profile the interface showed. It cannot establish the backend account or subscription state on its own.

5. **Which installed build produced the observation?** No report from the owner's head unit is available. The exact firmware build, factory settings APK version, version code, signer and base APK SHA-256 are therefore unverified. The inspected stock APK was version 1.0, code 1. That is the identity of the offline reference, so it cannot be attributed to the owner's vehicle. The report can supply the Android product and fingerprint and, where visible and readable, package version, version code, signer, base APK hash and split count. The model and firmware version displayed by the vehicle are separate fields to record. [Inspected stock software](VEHICLE_AUTOMATION.md#factory-apollonoa-procedure), [read-only diagnostics](APOLLO_CANDIDATE.md#read-only-diagnostics)

6. **Does the effect survive a restart or reverse when switched off?** There are no before-and-after reports or confirmed outcomes for either case. Static evidence that the app can process a stored preference at startup leaves two possibilities open. An earlier change may have persisted, or the app may have requested it again after startup. A cache comparison shows which sampled request values differ, subject to read access and timing. Matching cache values do not establish persistence in the controller, and a change back does not establish a complete rollback.

7. **Can a supervised repeat answer the controller question?** Head-unit availability for a repeat is unconfirmed. The sequence proposed above can only compare UI availability and request-cache snapshots. It cannot establish controller acceptance even if the change reproduces every time. That would require an OEM-supported, controller-originated observation for the specific function on the same firmware. No such observation is available to the current OVC report. [Request-cache and feedback limits](APOLLO_CANDIDATE.md#read-only-diagnostics)

8. **Should OVC attach the owner's note to a report?** The current shortcuts and report are sufficient to preserve the technical snapshot. An optional owner note could help when the factory presents a reason that the report cannot capture. Keep the exact control label and factory message beside the report, identify them as user-entered, and omit personal data and QR codes. Whether to add this UX remains a product decision.

9. **Should reports be paired, and should model and firmware be copyable?** A pair is useful when two observations already exist and support needs to compare them. Keep both original reports and label each with the local date, displayed account category and what the factory page showed. The report's elapsed times are relative to the current boot and cannot order captures across a restart by themselves. Copyable model and firmware labels can make a support ticket easier to read when those labels come from a verified factory source. The Android product and fingerprint remain useful technical identifiers.

10. **Which control and outcome should lead the work?** The owner has identified the goal as making the otherwise unavailable Apollo controls selectable in Guest. No single label or priority has been specified. The smallest observable outcome matching that request would be one named option becoming selectable and the factory page reliably retaining the selected state through its normal workflow. Controller feedback and assistance operating on the road need separate evidence. No OEM-supported route that provides that Guest outcome while retaining the factory checks has been established. [Factory setup and H97D limits](VEHICLE_AUTOMATION.md#factory-apollonoa-procedure)

Answers 8 and 9 are now built into the Apollo screen: the driver's note saved with the report, the copyable model and firmware identity, the dated report name and the recorded wall-clock time. Answers 1 to 7 and 10 stay open, and they keep the write path closed.
