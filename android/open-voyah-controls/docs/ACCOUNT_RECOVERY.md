# OEM account-record recovery limits

For **Driver 1/2/3**, see [driver seat and mirror memory](DRIVER_MEMORY.md). This technical page covers the separate OEM account A/B/C integration and saved account-record recovery.

## Saved guest account record

The guest account adapter changes `/private/configs/token/accountInfo` and four observed property values. Its first verified snapshot retains the original record, ownership, permissions, SELinux label and property values. The snapshot is bound to the product, build fingerprint and a digest of the unit serial. Recovery validates it before writing and checks the restored record and values again after the OEM account broadcasts. A pending transaction journal blocks further guest switches; explicit record recovery remains available.

This snapshot covers the record and property values only. `getprop` cannot distinguish a missing property from a present empty value. Seat-provider rows, saved seat/mirror positions, other OEM application data and complete live account state are outside its scope. **Restore saved account record** does not undo a driver-memory recall. The guest profile operation also requires the reviewed OEM handler and Park preflight.

Invalid, incomplete and legacy snapshots are preserved rather than overwritten. Keep original backups from earlier builds; a completion marker alone does not establish a verified snapshot.

## Overseas account interface

On product `f6811`, the observed interface is `com.qinggan.app.login.LoginUtil.chooseUserA/B/C(Context):boolean`. The current probe checks the signatures and a bounded account-file classification without calling the selection methods. Account A/B/C do not identify driver-memory slots 1/2/3.

The login implementation and its downstream writes remain unavailable. The shared account file is outside the login application's data directory; copying that directory, this file or the three memory slots cannot establish complete account recovery. A reviewed restoration contract must cover every affected store, property, provider, event and remote side effect, including any provider write used to capture seat/mirror data.

Overseas switching and restoration remain unavailable. The portable evidence collector and read-only probe are observations, not backups or restoration qualification. An OEM method return or delayed vehicle-data event does not prove a completed transition.

Local fixture results establish the tested transaction behavior only. Exact firmware review and vehicle evidence remain required for OEM account operations.
