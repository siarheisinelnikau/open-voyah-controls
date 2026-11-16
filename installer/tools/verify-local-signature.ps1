[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $Path,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')][string] $CertificateSha256
)
$ErrorActionPreference = 'Stop'
$file = (Resolve-Path -LiteralPath $Path).Path
Add-Type -AssemblyName System.Security
if ($PSVersionTable.PSEdition -eq 'Core') { Add-Type -AssemblyName System.Security.Cryptography.Pkcs }
if (-not ('ControlsLocalSignature' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.Pkcs;
public static class ControlsLocalSignature {
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
    struct FileInfo {
        public uint size;
        [MarshalAs(UnmanagedType.LPWStr)] public string path;
        public IntPtr file, subject;
    }
    [StructLayout(LayoutKind.Sequential)]
    struct TrustData {
        public uint size;
        public IntPtr policy, sip;
        public uint ui, revocation, choice;
        public IntPtr file;
        public uint action;
        public IntPtr state, url;
        public uint flags, context;
        public IntPtr signatures;
    }
    [DllImport("wintrust.dll", ExactSpelling=true)]
    static extern int WinVerifyTrust(IntPtr hwnd, ref Guid action, ref TrustData data);
    public static string Verify(string path) {
        // Check the embedded CMS signature independently of the machine's trust store.
        byte[] bytes = File.ReadAllBytes(path);
        using (var reader = new BinaryReader(new MemoryStream(bytes))) {
            if (reader.ReadUInt16() != 0x5a4d) throw new InvalidDataException("Not a PE file.");
            reader.BaseStream.Position = 0x3c;
            uint pe = reader.ReadUInt32();
            reader.BaseStream.Position = pe;
            if (reader.ReadUInt32() != 0x4550) throw new InvalidDataException("Invalid PE header.");
            reader.BaseStream.Position = pe + 24;
            ushort magic = reader.ReadUInt16();
            if (magic != 0x10b && magic != 0x20b) throw new InvalidDataException("Unsupported PE format.");
            reader.BaseStream.Position = pe + 24 + (magic == 0x20b ? 112 : 96) + 32;
            uint offset = reader.ReadUInt32(), size = reader.ReadUInt32();
            if (offset == 0 || size < 8 || (ulong)offset + size != (ulong)bytes.Length)
                throw new InvalidDataException("Missing or ambiguous embedded signature.");
            reader.BaseStream.Position = offset;
            uint length = reader.ReadUInt32();
            if (reader.ReadUInt16() != 0x200 || reader.ReadUInt16() != 2 || length < 8 || length > size
                || ((length + 7) & ~7u) != size)
                throw new InvalidDataException("Unsupported certificate table.");
            var cms = new SignedCms();
            cms.Decode(reader.ReadBytes(checked((int)length - 8)));
            if (cms.SignerInfos.Count != 1) throw new InvalidDataException("Expected one signer.");
            cms.CheckSignature(true);
            var signer = cms.SignerInfos[0];
            if (signer.DigestAlgorithm.Value != "2.16.840.1.101.3.4.2.1")
                throw new CryptographicException("SHA-256 signature required.");
            var cert = signer.Certificate;
            if (cert == null || DateTime.Now < cert.NotBefore || DateTime.Now > cert.NotAfter)
                throw new CryptographicException("Signer certificate is absent or expired.");
            // Windows SIP verifies the PE digest, including sections and overlay, against the signed data.
            var info = new FileInfo { size=(uint)Marshal.SizeOf(typeof(FileInfo)), path=path };
            IntPtr infoPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(FileInfo)));
            Marshal.StructureToPtr(info, infoPtr, false);
            var trust = new TrustData { size=(uint)Marshal.SizeOf(typeof(TrustData)), ui=2,
                choice=1, file=infoPtr, action=1, flags=0x200 | 0x1000 | 0x10 | 0x2000 };
            var policy = new Guid("00AAC56B-CD44-11d0-8CC2-00C04FC295EE");
            try {
                int result = WinVerifyTrust(new IntPtr(-1), ref policy, ref trust);
                if (result != 0) throw new CryptographicException("PE digest verification failed: 0x" + result.ToString("X8"));
            } finally {
                trust.action=2;
                WinVerifyTrust(new IntPtr(-1), ref policy, ref trust);
                Marshal.DestroyStructure(infoPtr, typeof(FileInfo));
                Marshal.FreeHGlobal(infoPtr);
            }
            using (var hash = SHA256.Create()) {
                return BitConverter.ToString(hash.ComputeHash(cert.RawData)).Replace("-", "").ToLowerInvariant();
            }
        }
    }
}
'@ -ReferencedAssemblies @(
    [Security.Cryptography.Pkcs.SignedCms].Assembly.Location,
    [Security.Cryptography.X509Certificates.X509Certificate2].Assembly.Location
)
}
$actual = [ControlsLocalSignature]::Verify($file)
if ($actual -ne $CertificateSha256.ToLowerInvariant()) { throw 'The local Windows signer does not match the expected certificate.' }
Write-Output "Verified local Authenticode signature and PE digest: $actual"
