# Account recovery boundaries

Account A/B/C selection and numeric seat-memory commands are separate operations. A successful method return or a delayed vehicle-data event does not establish a completed account transition.

## Overseas accounts

On product `f6811`, the observed interface is `com.qinggan.app.login.LoginUtil.chooseUserA/B/C(Context):boolean`. The current agent checks these signatures and observes a bounded account-file classification. It does not call the selection methods.

The implementation of the login methods and their downstream writes is unavailable. A copy of the login package directory, the shared account file, or the three seat slots cannot establish a complete backup. The account file at `/private/configs/token/accountInfo` is outside the login application's data directory. Account settings can also involve OEM providers and other processes.

Before implementing selection and restoration, a reviewed contract must identify every affected file, database and journal, property, provider row, event and remote side effect. It must define how to obtain a consistent snapshot, preserve ownership and SELinux labels, detect interrupted transitions, and verify restoration of both persistent and live state. A provider write made to capture seat memory is itself a mutation and needs recovery coverage.

No overseas account-write approval is shipped. The portable firmware evidence collector and the read-only Frida probe supply complementary observations; neither is an account backup or a qualification of account restoration.

## Guest account record

The separate guest protocol changes one shared account file and four observed property values. Its recovery snapshot covers those exact surfaces. `getprop` does not distinguish a missing property from a present empty value, so this is a value snapshot. Seat-provider contents, other OEM application data and complete live account state are outside that snapshot. The existing profile refresh still requires the reviewed OEM handler and Park preflight.

Recovery validates the entire snapshot before changing the account file or properties. Invalid or incomplete snapshots remain available for inspection rather than being overwritten. Keep original backups from earlier builds: a legacy completion marker alone is insufficient evidence for a verified recovery snapshot.

All account operations remain subject to exact firmware and package review. Local fixture success establishes the tested transaction behavior; it does not establish the complete OEM account contract or physical behavior on a vehicle.
