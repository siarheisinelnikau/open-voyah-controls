package org.openvoyah.controls;

import android.Manifest;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.test.AndroidTestCase;
import org.openvoyah.controls.core.voice.VoiceCommandUseCase;
import org.openvoyah.controls.data.settings.VoiceSettings;

public final class LocalActionsTest extends AndroidTestCase {
    public void testWeatherFinishesWithoutActivityOrTransport() throws Exception {
        VoiceSettings settings = new VoiceSettings(getContext());
        VoiceCommandUseCase commands = new VoiceCommandUseCase(id -> {
            fail("Weather must not query local or privileged actions");
            return false;
        }, id -> { throw new AssertionError("Weather is not a contact"); });
        var catalog = settings.withContacts();
        for (String id : new String[]{"Weather", "WeatherTomorrow"}) {
            var command = catalog.byId(id);
            var request = commands.prepare(command, command.title());
            assertEquals(VoiceCommandUseCase.Destination.OFFLINE_WEATHER, request.destination());
        }
    }

    public void testExternalMicrophoneEntryRequiresPrivilegedCaller() throws Exception {
        var packages = getContext().getPackageManager();
        ActivityInfo direct = packages.getActivityInfo(new ComponentName(getContext(), QuickVoiceActivity.class), 0);
        ActivityInfo alias = packages.getActivityInfo(
            new ComponentName(getContext(), "org.openvoyah.controls.PanelVoiceActivity"), 0);
        assertFalse(direct.exported);
        assertTrue(alias.exported);
        assertEquals(Manifest.permission.WRITE_SECURE_SETTINGS, alias.permission);
    }
}
