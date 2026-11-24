package app.voyahmusic.shared.lan;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MusicWireTest {
    @Test
    public void pairsOnlyWithLocalAddressesOnTheActualPrefix() {
        assertNull(MusicWire.address("8.8.8.8"));
        assertNull(MusicWire.address("192.168.1.256"));
        assertNull(MusicWire.address("localhost"));
        assertNull(MusicWire.address("127.0.0.1"));
        assertTrue(MusicWire.sameSubnet(MusicWire.address(" 192.168.2.130 "),
                MusicWire.address("192.168.2.254"), 25));
        assertFalse(MusicWire.sameSubnet(MusicWire.address("192.168.2.130"),
                MusicWire.address("192.168.2.62"), 25));
    }

    @Test
    public void preservesUnicodeNamesAndRejectsPathsAndNonAudioExtensions() {
        assertTrue(MusicWire.validName("Әндер — Музыка.FLAC"));
        assertFalse(MusicWire.validName("../escape.mp3"));
        assertFalse(MusicWire.validName("nested\\escape.mp3"));
        assertFalse(MusicWire.validName("payload.apk"));
        assertFalse(MusicWire.validName("bad\nname.mp3"));
    }

    @Test
    public void preservesZeroPaddedCodeAndDecodesStatuses() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        MusicWire.request(out, MusicWire.UPLOAD, "00001234");
        MusicWire.Status.DUPLICATE.write(out);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(MusicWire.UPLOAD, MusicWire.command(in));
        assertEquals("00001234", in.readUTF());
        assertEquals(MusicWire.Status.DUPLICATE, MusicWire.Status.read(in));
    }

    @Test
    public void derivesFixedWidthManualCodeFromPinnedIdentity() {
        String identity = "00000000002a" + "0".repeat(52);
        assertTrue(MusicWire.fingerprint(identity));
        assertEquals("00000042", MusicWire.pairingCode(identity));
        assertFalse(MusicWire.fingerprint("00000042"));
    }

    @Test
    public void folderNamesAreDisplayNamesRatherThanPaths() {
        assertTrue(MusicWire.validFolder("Дорога — Қазақша"));
        assertFalse(MusicWire.validFolder(".."));
        assertFalse(MusicWire.validFolder("root/album"));
        assertFalse(MusicWire.validFolder("   "));
    }
}
