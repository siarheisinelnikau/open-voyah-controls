"""Download the pinned official ARM64 injector; never connect to a vehicle."""
import hashlib
import json
import lzma
from pathlib import Path
import urllib.request

VERSION = "17.18.0"
SHA256 = "a72de74276d914f6769b8b85f8dd287cbafa4527c42ae1c8dd87b0d23d261391"
URL = f"https://github.com/frida/frida/releases/download/{VERSION}/frida-inject-{VERSION}-android-arm64.xz"

def main():
    output = Path(__file__).resolve().parents[1] / "dist" / "runtime"
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f"frida-inject-{VERSION}-android-arm64.xz"
    if not archive.exists():
        with urllib.request.urlopen(URL, timeout=60) as response:
            archive.write_bytes(response.read())
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise ValueError("Official release checksum mismatch")
    binary = lzma.decompress(archive.read_bytes())
    if binary[:5] != b"\x7fELF\x02" or int.from_bytes(binary[18:20], "little") != 183:
        raise ValueError("Expected ARM64 ELF")
    name = f"open-voyah-controls-frida-{VERSION}"
    (output / name).write_bytes(binary)
    report = dict(version=VERSION, url=URL, archive_sha256=SHA256,
                  file=name, binary_sha256=hashlib.sha256(binary).hexdigest(), size=len(binary))
    (output.parent / "FRIDA_RUNTIME.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))

if __name__ == "__main__":
    main()
