use std::{env, fs, path::PathBuf};

fn main() {
    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }
    let sizes = [16usize, 24, 32, 48, 64, 256];
    let mut icon = vec![0, 0, 1, 0, sizes.len() as u8, 0];
    let mut images = Vec::new();
    let mut offset = 6 + sizes.len() * 16;
    for size in sizes {
        let mask = size.div_ceil(32) * 4 * size;
        let length = 40 + size * size * 4 + mask;
        icon.extend([size as u8, size as u8, 0, 0, 1, 0, 32, 0]);
        icon.extend((length as u32).to_le_bytes());
        icon.extend((offset as u32).to_le_bytes());
        offset += length;
        images.extend(40u32.to_le_bytes());
        images.extend((size as i32).to_le_bytes());
        images.extend((2 * size as i32).to_le_bytes());
        images.extend([1, 0, 32, 0]);
        images.extend([0; 24]);
        for y in (0..size).rev() {
            for x in 0..size {
                let (x, y) = (x * 48 / size, y * 48 / size);
                let accent = ((12..20).contains(&y) && (10..38).contains(&x))
                    || ((28..36).contains(&y) && ((10..22).contains(&x) || (28..38).contains(&x)));
                images.extend(if accent {
                    [0xC5, 0xDF, 0x71, 255]
                } else {
                    [0x23, 0x19, 0x10, 255]
                });
            }
        }
        images.resize(images.len() + mask, 0);
    }
    icon.extend(images);
    let out = PathBuf::from(env::var_os("OUT_DIR").unwrap());
    const LEGACY: &str = "OPEN_VOYAH_LEGACY_CODES_FILE";
    println!("cargo:rerun-if-env-changed={LEGACY}");
    let codes = if let Some(path) = env::var_os(LEGACY) {
        println!("cargo:rerun-if-changed={}", PathBuf::from(&path).display());
        fs::read(path).expect("read the optional local engineering codes")
    } else {
        b"[]".to_vec()
    };
    fs::write(out.join("legacy-codes.json"), codes).unwrap();
    let icon_path = out.join("controls.ico");
    fs::write(&icon_path, icon).unwrap();
    let manifest_path = out.join("controls.manifest");
    fs::write(&manifest_path, r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
  <assemblyIdentity version="1.0.0.0" processorArchitecture="*" name="OpenVoyahControls.Installer" type="win32"/>
  <dependency><dependentAssembly><assemblyIdentity type="win32" name="Microsoft.Windows.Common-Controls" version="6.0.0.0" processorArchitecture="*" publicKeyToken="6595b64144ccf1df" language="*"/></dependentAssembly></dependency>
  <application xmlns="urn:schemas-microsoft-com:asm.v3"><windowsSettings><dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true</dpiAware></windowsSettings></application>
</assembly>"#).unwrap();
    let version = env!("CARGO_PKG_VERSION");
    const RELEASE_CERT: &str = "OPEN_VOYAH_RELEASE_CERT_SHA256";
    println!("cargo:rerun-if-env-changed={RELEASE_CERT}");
    let release_pin = match env::var(RELEASE_CERT) {
        Ok(pin) => {
            assert!(
                pin.len() == 64 && pin.bytes().all(|byte| byte.is_ascii_hexdigit()),
                "{RELEASE_CERT} must be a SHA-256 certificate fingerprint without separators"
            );
            format!(
                "Controls release certificate SHA-256 {}",
                pin.to_ascii_lowercase()
            )
        }
        Err(_) => "Development installer without a Controls release certificate".into(),
    };
    winresource::WindowsResource::new()
        .set("ProductName", "Open Voyah Controls Installer")
        .set("FileDescription", "Open Voyah Controls Installer")
        .set(
            "OriginalFilename",
            &format!("OpenVoyahControlsInstaller-v{version}.exe"),
        )
        .set("ProductVersion", version)
        .set("FileVersion", version)
        .set("Comments", &release_pin)
        .set_manifest_file(manifest_path.to_str().unwrap())
        .set_icon(icon_path.to_str().unwrap())
        .compile()
        .expect("compile Windows application resources");
}
