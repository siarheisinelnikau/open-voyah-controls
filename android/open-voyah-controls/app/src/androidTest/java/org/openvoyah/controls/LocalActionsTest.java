package org.openvoyah.controls;

import android.Manifest;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.test.AndroidTestCase;
import org.openvoyah.controls.core.Action;
import org.openvoyah.controls.core.voice.VoiceCommandUseCase;
import org.openvoyah.controls.data.settings.VoiceSettings;
import org.openvoyah.controls.platform.ui.ActionExecutor;

public final class LocalActionsTest extends AndroidTestCase {
    public void testAppMenuAndSettingsAvailabilityDoesNotNeedTransport() {
        ActionExecutor executor = new ActionExecutor(getContext());
        for (Action.Kind kind : new Action.Kind[]{Action.Kind.CONTROLS_APPS,
                Action.Kind.CONTROLS_SETTINGS, Action.Kind.SYSTEM_SETTINGS, Action.Kind.START_VOICE}) {
            assertTrue(kind.name(), executor.canExecute(new Action(kind, "", kind.name())));
        }
        assertFalse(executor.canExecute(Action.NONE));
    }

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
        assertNull(direct.targetActivity);
        assertTrue(direct.exported);
        assertEquals(Manifest.permission.WRITE_SECURE_SETTINGS, direct.permission);
        assertEquals(PackageManager.PERMISSION_DENIED,
            getContext().checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS));
        ActivityInfo setup = packages.getActivityInfo(new ComponentName(getContext(), SetupActivity.class), 0);
        assertNull(setup.targetActivity);
        assertTrue(setup.exported);
        assertEquals(Manifest.permission.WRITE_SECURE_SETTINGS, setup.permission);
    }
}
