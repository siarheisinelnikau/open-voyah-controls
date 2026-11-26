"""Build a locally signed Controls update kit from already signed release artifacts."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path


MIB = 1024 * 1024
STABLE = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
SHA256 = re.compile(r"[a-f0-9]{64}")
FINGERPRINT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/+,\-]{0,511}")
PRODUCT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/+\-]{0,127}")
P256_SPKI_PREFIX = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030107034200")
RUNTIME_MANIFEST = "assets/production/FRIDA_RUNTIME.json"


def run(*args, timeout=90):
    completed = subprocess.run(args, capture_output=True, check=True, timeout=timeout)
    return completed.stdout


def no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Repeated JSON field {key!r}.")
        result[key] = value
    return result


def input_size(path, maximum):
    size = path.stat().st_size
    if not path.is_file() or not 0 < size <= maximum:
        raise ValueError(f"{path.name} must be a nonempty file of at most {maximum} bytes.")
    return size


def compatibility(path):
    input_size(path, 64 * 1024)
    rows = json.loads(path.read_bytes(), object_pairs_hook=no_duplicate_keys)
    if not isinstance(rows, list) or not 1 <= len(rows) <= 64:
        raise ValueError("Supply one to 64 exact firmware identities.")
    identities = set()
    for row in rows:
        if (
            not isinstance(row, dict)
            or set(row) != {"fingerprint", "sdk", "abi", "product"}
            or not isinstance(row["fingerprint"], str)
            or not FINGERPRINT.fullmatch(row["fingerprint"])
            or not isinstance(row["product"], str)
            or not PRODUCT.fullmatch(row["product"])
            or type(row["sdk"]) is not int
            or row["sdk"] not in (28, 30)
            or row["abi"] != "arm64-v8a"
        ):
            raise ValueError("The compatibility list contains an invalid firmware identity.")
        identity = (row["fingerprint"], row["sdk"], row["abi"], row["product"])
        if identity in identities:
            raise ValueError("The compatibility list repeats a firmware identity.")
        identities.add(identity)
    return rows


def apk_metadata(apk, tools, java, release_cert, rows):
    if not SHA256.fullmatch(release_cert):
        raise ValueError("Provide the lower-case SHA-256 of the pinned Controls release certificate.")
    executable = "aapt2.exe" if os.name == "nt" else "aapt2"
    badging = "\n".join(run(tools / executable, "dump", "badging", apk).decode("utf-8").splitlines())
    package = next((line for line in badging.splitlines() if line.startswith("package: ")), "")
    fields = dict(re.findall(r"(\w+)='([^']*)'", package))
    version = fields.get("versionName", "")
    code = fields.get("versionCode", "")
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'([0-9]+)'$", badging, flags=re.M)
    if (
        fields.get("name") != "org.openvoyah.controls"
        or fields.get("split")
        or not STABLE.fullmatch(version)
        or len(version) > 64
        or not code.isascii()
        or not code.isdecimal()
        or not 1 <= int(code) <= 2147483647
        or not minimum
        or not 1 <= int(minimum[1]) <= min(row["sdk"] for row in rows)
        or any(line.lstrip().startswith("application-debuggable") for line in badging.splitlines())
    ):
        raise ValueError("Choose a compatible, standalone, non-debuggable stable Controls APK.")
    for sdk in sorted({row["sdk"] for row in rows}):
        certs = "\n".join(run(
            java, "-jar", tools / "lib/apksigner.jar", "verify",
            "--min-sdk-version", str(sdk), "--max-sdk-version", str(sdk), "--print-certs", apk,
        ).decode("utf-8").splitlines())
        signers = re.findall(r"^Signer #(\d+) certificate SHA-256 digest: ([a-fA-F0-9]{64})$", certs, re.M)
        if len(signers) != 1 or signers[0][0] != "1" or signers[0][1].lower() != release_cert:
            raise ValueError(f"The APK signer for API {sdk} differs from the Controls release certificate.")
    return version, int(code)


def payload(path, role, name, maximum):
    size = input_size(path, maximum)
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"role": role, "name": name, "size": size, "sha256": digest}


def check_runtime(apk, runtime):
    with zipfile.ZipFile(apk) as archive:
        pins = [entry for entry in archive.infolist() if entry.filename == RUNTIME_MANIFEST]
        if len(pins) != 1 or not 0 < pins[0].file_size <= 8192:
            raise ValueError("The APK must include one bounded Frida runtime manifest.")
        with archive.open(pins[0]) as stream:
            pin = json.loads(stream.read(8193), object_pairs_hook=no_duplicate_keys)
    if not isinstance(pin, dict):
        raise ValueError("The APK injector manifest must be a JSON object.")
    version = pin.get("version", "")
    if (
        pin.get("binary_sha256") != runtime["sha256"]
        or pin.get("size") != runtime["size"]
        or not isinstance(version, str)
        or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+\-]{0,19}", version)
        or pin.get("file") != f"open-voyah-controls-frida-{version}"
    ):
        raise ValueError("The injector size and SHA-256 must match the signed APK's runtime manifest.")


def build(args):
    rows = compatibility(args.compatibility)
    input_size(args.apk, 512 * MIB)
    version, code = apk_metadata(args.apk, args.build_tools, args.java, args.release_cert_sha256, rows)
    apk = payload(args.apk, "controls-apk", f"OpenVoyahControls-v{version}.apk", 512 * MIB)
    runtime = payload(args.runtime, "frida-runtime", "frida-inject", 128 * MIB)
    check_runtime(args.apk, runtime)
    args.out.mkdir(parents=True, exist_ok=True)
    output = args.out / f"OpenVoyahControlsKit-v{version}.zip"
    if output.exists():
        raise FileExistsError(output)
    with tempfile.TemporaryDirectory(prefix=".ovc-kit-", dir=args.out) as temporary:
        temporary = Path(temporary)
        manifest = temporary / "manifest.json"
        signature = temporary / "manifest.sig"
        archive_path = temporary / output.name
        manifest.write_bytes((json.dumps({
            "schema": 1,
            "version": version,
            "versionCode": code,
            "compatibility": rows,
            "files": [apk, runtime],
        }, ensure_ascii=True, separators=(",", ":")) + "\n").encode("ascii"))
        public = run(args.openssl, "pkey", "-in", args.key, "-pubout", "-outform", "DER")
        if not public.startswith(P256_SPKI_PREFIX) or len(public) != len(P256_SPKI_PREFIX) + 65:
            raise ValueError("The kit needs an uncompressed P-256 signing key.")
        public_hex = public[len(P256_SPKI_PREFIX):].hex()
        if not public_hex.startswith("04") or public_hex != args.public_key_hex:
            raise ValueError("The signing key differs from the public key pinned into the consumers.")
        run(args.openssl, "dgst", "-sha256", "-sign", args.key, "-out", signature, manifest)
        if not 8 <= signature.stat().st_size <= 72:
            raise ValueError("The tool did not produce a P-256 ASN.1 DER signature.")
        public_der = temporary / "public.der"
        public_der.write_bytes(public)
        public_pem = temporary / "public.pem"
        public_pem.write_bytes(run(
            args.openssl, "pkey", "-pubin", "-inform", "DER", "-outform", "PEM", "-in", public_der,
        ))
        run(args.openssl, "dgst", "-sha256", "-verify", public_pem, "-signature", signature, manifest)
        with zipfile.ZipFile(archive_path, "x", compression=zipfile.ZIP_STORED, allowZip64=False) as archive:
            archive.write(manifest, manifest.name)
            archive.write(signature, signature.name)
            archive.write(args.apk, apk["name"])
            archive.write(args.runtime, runtime["name"])
        input_size(archive_path, 641 * MIB)
        with zipfile.ZipFile(archive_path) as archive:
            for item in (apk, runtime):
                with archive.open(item["name"]) as stream:
                    if hashlib.file_digest(stream, "sha256").hexdigest() != item["sha256"]:
                        raise ValueError(f"{item['name']} changed during packaging.")
        # Publish only the completed archive. A concurrent invocation may not replace this path.
        if os.name == "nt":
            archive_path.rename(output)  # Windows rename refuses an existing destination.
        else:
            os.link(archive_path, output)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("apk", "runtime", "compatibility", "key", "build-tools", "out"):
        parser.add_argument(f"--{option}", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--openssl", default="openssl")
    parser.add_argument("--release-cert-sha256", required=True)
    parser.add_argument("--public-key-hex", required=True)
    try:
        print(build(parser.parse_args()))
    except (OSError, ValueError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
