# Signed update kits

Settings → System → Updates checks the existing official release catalog. Checks run at startup by default and can be turned off. Newer stable releases may offer a separately signed **Download and install kit** action alongside the existing APK action. An APK from a kit goes through the same signer, package, version, permissions, runtime pause, Android approval, and interrupted-install recovery checks. The head unit retains its application data during an in-place update.

A kit with the same runtime pin as the installed app can update from the head unit. A kit that changes the runtime requires the matching Windows installer, which backs up the deployment before replacing it and offers recovery. The Windows installer version must recognize the runtime carried by the kit. An interrupted Android session is verified before it can resume the prepared deployment. An uncertain install is never submitted again automatically.

## Release assets

Keep publishing the standalone `OpenVoyahControls-vVERSION.apk` so previous updaters can keep using it. A kit adds `OpenVoyahControlsKit-vVERSION.zip` as a second asset on the same canonical repository. The asset must have the catalog SHA-256 digest. Build both consumers with the same `OPEN_VOYAH_KIT_PUBLIC_KEY_HEX`, a lowercase uncompressed 65-byte P-256 public point in hex. Keep the private manifest signing key outside the application and repository. Without a configured public key, installed clients omit kit updates.

The version 1 archive contains four flat, uncompressed entries:

- `manifest.json` with `schema`, `version`, `versionCode`, `compatibility`, and `files`.
- `manifest.sig` with an ASN.1 DER ECDSA P-256/SHA-256 signature over the exact UTF-8 manifest bytes.
- `OpenVoyahControls-vVERSION.apk`, role `controls-apk`, signed with the established Controls APK release certificate.
- `frida-inject`, role `frida-runtime`, with the size and digest pinned by the signed APK.

Each compatibility row includes the exact head unit `fingerprint`, `sdk`, primary `abi`, and `product`. Each file includes its `role`, `name`, byte `size`, and lowercase `sha256`. Device support is limited to listed ARM64 Android 9 or 11 firmware. Being listed in the update kit approves update compatibility only. Existing firmware and operation checks still govern access to OEM commands.
