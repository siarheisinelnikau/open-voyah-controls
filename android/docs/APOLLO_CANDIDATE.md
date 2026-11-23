# Apollo activation candidate

An independently authored activation candidate is prepared for off-device review. Its executable adapter accepts only the isolated fixture package `org.openvoyah.controls.fixture.apollo`. It rejects the factory settings package. No production entry imports the candidate, no native production adapter is supplied, and the shipped approval list is empty. An APK install, fixture result or saved preference cannot activate the vehicle.

In **Settings → Vehicle → Apollo and driving assistance**, **Open factory assistance** requests the manufacturer's driving-assistance page when Android offers it. **Open vehicle menu** lets the driver navigate there manually when the general menu is available. Controls offers these same shortcuts in the guest profile. The factory app determines which features the current account can use and presents any account, training or service requirements. **Read Apollo diagnostics** collects a snapshot on the installed head unit. **Save Apollo report** exports that snapshot to an Android document chosen by the driver. [Factory workflow and H97D limits](VEHICLE_AUTOMATION.md#factory-apollonoa-procedure)

## Reviewed mechanism

The original settings APK exposes these candidate interface signatures:

| OEM interface | Role and effect |
| --- | --- |
| `BaiduProviderUtil.doQuerySubscribeInfo(Context):String` | Reads subscription data through the original Baidu state provider. A temporary interception can substitute that return value inside the fixture. |
| `DriveAssistanceAdasStatusManager.instance(Context)` | Factory singleton. Its construction and initialization have side effects; the metadata probe never calls it. |
| `sendAdasSubStatusToADCU(boolean):void` | Sends a broad assistance/parking enable bundle. An accepted call is not vehicle telemetry. |
| `asyncQueryAdasSubData():void` | Schedules a factory subscription refresh. Its later processing can also send the controller bundle. |

The fixture trial snapshots its original state, records an intent in the fixture journal, temporarily substitutes the subscription response, dispatches the status and refresh once, detaches its own interception, restores the original per-flag/store snapshot, verifies the restoration and then closes the journal. It leaves genuine learning, the account and the factory TLC confirmation untouched. It performs no model predicate overrides, provider writes, SQLite trigger installation, automatic READY replay or Pilot-button input.

`sendAdasSubStatusToADCU` affects 18 named fields: RPA, HPP, GLC, ISLC, TLC, NOA, ELK and ESA enable fields, plus APA/RPA/HAVP/ACC/ICA/PLC/HANP/ISA/ISLC/TLA service-availability fields. Their exact names are listed in `ApolloCandidatePlan.AFFECTED_FLAGS`. A blanket call with `false` is not a rollback: it would replace different original states with a single disabled value. Preserve and independently restore every original value.

The inspected stock APK is identified in the [vehicle automation guide](VEHICLE_AUTOMATION.md). It is source evidence, not a reviewed target firmware. The Baidu equipment branch also depends on actual `ASC` and `SDB` online-configuration readings. Do not change these values or infer them from the vehicle's display name.

## Admission contract

`ApolloCandidatePolicy` requires all of the following before it can prepare a review plan:

- A unique reviewed operation matching the complete fingerprint, SDK, ABI, product, and signed settings/CAN/provider APK identities.
- The exact method signatures and reviewed controller effects for that operation.
- Actual compatible Baidu equipment classification, with the reviewed `ASC`/`SDB` results.
- Genuine factory account, completed learning and TLC confirmation.
- A complete, verified off-device backup bound to that exact identity, including all five backup scopes and all 18 original controller fields.
- Independently usable and tested restoration, including after app failure.
- Qualified Park and zero-speed observations no more than 500 ms old, in the same active session.
- Explicit confirmation bound to the operation, firmware, session and backup digest, no more than 15 seconds old.
- No unresolved preceding attempt.

The policy has no transport or write methods. A `Prepared` result is only a review plan. Approval, confirmation, backup evidence and controller observations are not settings and must not be restored from a settings archive.

## Read-only diagnostics

`ApolloCandidateEvidenceProbe` records the public menu components, firmware identity, current package signers, base APK size and SHA-256, split count and provider declarations. The bounded streaming hash accepts the inspected 413,825,468-byte settings APK. It checks the file and package identity again after reading and distinguishes missing, changed and oversized files.

The general vehicle report collects metadata only. **Read Apollo diagnostics** also queries the 18 named fields individually from the enabled, exported system provider that owns the OEM settings authority. Android enforces its effective read permissions and any applicable URI grants on each query. The report records the signing identities of the provider owner and the CAN app separately when visible. One worker handles a check, with a five-second deadline for the cache and cancellation when the driver leaves. The report records integer values, missing or ambiguous rows, read errors and the provider identity. It omits raw account, learning and subscription content. The capture covers a sequence of reads over the recorded interval.

In the inspected H97C CAN implementation, the individual subscription-field path saves its requested value to QGSettings before the controller transport returns. The separate bundle path schedules a CAN-service task and immediately returns. The cache can therefore contain a previous request or a request the controller never applied. The report identifies these fields as `factory_request_cache`, keeps `controller_feedback` and `usable_as_rollback` false, and records the missing controller originals and restoration evidence.

For equipment classification on H97D, record a fresh factory initialization on the parked vehicle. The inspected settings process logs the raw `ASC = <integer>` and `SDB = <integer>` under the `VehicleConfigHelper` tag while populating its equipment cache. Bind both entries to the same signed process, firmware, time window and selected factory branch. The fragment's later SDB display trace can already have replaced the raw value with zero on other equipment branches. If the cache was populated before observation, leave the raw result unknown.

The exported `apollo_candidate` section identifies the separate fixture candidate, keeps production activation unavailable and lists the outstanding evidence. The saved file contains diagnostic observations. The operator needs an independently tested off-device backup for any firmware or controller change.

## Remaining restoration and timing evidence

The production adapter remains absent until all of these are established on the exact installed H97D firmware:

1. Identify the actual provider owner and all affected databases, sidecar/journal files, preferences, account/configuration records and downstream caches. Back up their complete contents and metadata outside the vehicle before any write. No complete persisted-store inventory or restoration procedure has been verified yet.
2. Establish which of the 18 controller fields persist in the ADCU or other modules, how to observe each original state, and the independent original-interface restoration procedure. Returning a boolean or restarting the factory app does not establish restoration.
3. On the inspected settings APK, `asyncQueryAdasSubData` starts a thread that reads the provider, replaces a pending Handler message of the same type and posts the result. The Handler updates local state and hands the bundle to another asynchronous task in the CAN service. Repeated factory refreshes can overlap this work. Qualification needs an operation-specific completion signal, original per-field controller feedback and evidence that a later delivery cannot invalidate restoration. The public entry and saved request values provide none of those guarantees.
4. Verify an owned interception's exact installation/removal contract and the final state after process loss, transport loss, normal sleep/wake and restoration. An ambiguous dispatch is never replayed.

The fixture rejects an asynchronous/thenable operation and leaves its journal unresolved even if its immediate snapshot was restored. Real OEM methods can return `void` while scheduling later work; this check is not a native timing detector. Native completion and restoration require separate evidence.

## Verification scope

`ApolloCandidatePolicyTest` covers the closed default, exact identity/operation/method matching, complete rollback evidence, fresh state and confirmation, genuine setup and unresolved attempts. `apollo-candidate.test.cjs` exercises the isolated fixture transaction and restoration. `ApolloApkFingerprintTest` covers streaming above the previous 64 MiB limit. `ApolloCandidateEvidenceProbeTest` exercises an absent OEM provider, exact-key cache reads, missing and ambiguous values, cancellation and document export on the selected emulator. `VehicleWorkflowsTest` exercises the read action from the production Compose screen.

These checks establish candidate behavior and software boundaries. They do not establish Apollo eligibility, actual activation, real CAN effects, exact firmware compatibility or independent restoration on a vehicle.
