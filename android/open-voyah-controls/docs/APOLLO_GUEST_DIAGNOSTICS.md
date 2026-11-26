# Apollo controls in Guest and factory eligibility

In **Settings → Vehicle → Apollo and driving assistance**, **Open factory assistance** uses the public OEM shortcut when Android offers it. **Open vehicle menu** opens the general vehicle menu. Choose **Driving assistance** there. If Android offers neither route, use the factory panel. The factory software handles equipment detection, the vehicle account, training, service access and driver confirmation. [Factory procedures](VEHICLE_AUTOMATION.md#factory-apollonoa-procedure)

VOYAH documents the driving-assistance page and the TLC prerequisite for NOA. Its equipment sheet lists Apollo L2.9 as an option on the FREE 318 AWD Edition. Equipment therefore needs to be identified on the individual vehicle. VOYAH OS 6.1.0 under Guest is a reported head-unit configuration. Its name does not identify an Android build, the installed ADAS supplier or OEM entitlement. [FREE 318 H97D owner manual](https://m.dsmp.voyah.com.cn/userguide/h97d/h97D-40.html) · [VOYAH FREE 318 AWD Edition equipment sheet](https://www.voyah.com.cn/resources/download/%E5%B2%9A%E5%9B%BEFree%E9%85%8D%E7%BD%AE%E8%A1%A8-318%E5%9B%9B%E9%A9%B1%E4%B8%96%E7%BA%AA%E7%89%88.pdf)

## Read the feature evidence

**Read Apollo diagnostics** checks the installed build, visible factory packages and public menu entry points. It reads cached requests if the system grants provider access. **Save Apollo report** exports a dated snapshot to the selected document. The report records these observations separately for each feature.

| Observation | Source |
| --- | --- |
| Option appears | The item is visible in factory settings on the vehicle. |
| Driver can use it | The factory page resolves account, training, entitlement and equipment requirements. |
| Change was kept | The factory page accepts the driver's change and retains it when reopened. |
| Ready or active | The vehicle provides its OEM status indication or verified controller-originated feedback. |

| Feature | Factory request evidence available to Controls |
| --- | --- |
| Lane change with turn signal (TLC) | `TLC_FUNC_ENABLE` may supply a cached setting request. |
| Traffic light recognition | No verified feature-specific read source. |
| Green light sound | No verified feature-specific read source. |
| Traffic sign recognition (TSR) | No verified feature-specific read source. |
| Speed limit warning (ISA) | `ISA_FUNC_ENABLE_SA` may supply a cached service request. |
| Intelligent speed limit control (ISLC) | `ISLC_FUNC_ENABLE` may supply a cached setting request and `ISLC_FUNC_ENABLE_SA` a cached service request. |

The persisted values are factory requests at capture time. The corresponding UI and report leave the four OEM observations unknown until there is an authorized source for each one. Where the cache is absent, inaccessible or incomplete, the diagnostics show the reason. All four OEM observations remain verifiable in the factory workflow.

**What the factory page showed** can attach the exact label and prerequisite text to the saved report. It is marked as entered by the driver. Leave out personal details and QR codes. The note stays in memory until exported to the selected file. The activity log records bounded outcomes and a field count. **Copy support details** gives the support team the Android build and visible package identity. Include the vehicle model and version displayed by the vehicle separately. [Read-only diagnostics](APOLLO_CANDIDATE.md#read-only-diagnostics)

## Evidence for this build

The owner observed previously unavailable Apollo controls become visible and selectable while a third-party feature was enabled and the displayed profile stayed in Guest. The exact controls and software identities, genuine factory account status, persistence, and controller response were not recorded. The observed option visibility is separate from the requested setting and its state on the road.

A per-feature OEM read or operation requires an exact firmware and signed package match, an observed equipment branch, and an authorized source for the feature. Captures of the factory page can establish the displayed item, the exact prerequisite and the retained position. For automatic status, use an authorized OEM reading that distinguishes acceptance from standby and active states. For a change, verify operation-specific completion and an independent restoration path. [Fixture and evidence boundaries](APOLLO_CANDIDATE.md)

The old experimental factory assistance worker is retired. Upgrading an authorized deployment stops that worker and clears its command and status. The historical controller claim remains available for recovery. Any effect of an earlier controller request must be checked on the vehicle. A rollback requires a verified OEM procedure for that exact build.
