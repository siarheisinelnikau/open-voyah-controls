# Adaptive Controls window brightness

On a standard Android 10 or newer device with an illuminance sensor, open **Settings → Routines and automation → Controls window brightness**. Enable automatic adjustment and choose a low-light and bright-light level. The curve interpolates on a logarithmic scale between 10 and 2,000 lux. A five-point hysteresis band and settling interval reduce flicker from small changes.

The Android window override applies to the focused Controls window on the default display. Controls releases it after a system brightness change, when the window loses focus, when the sensor loses its sample or when the Android thermal status is severe. The system keeps its normal settings. The app excludes Automotive builds and identified VOYAH head units, including devices with the factory launcher. A head unit integration needs confirmed OEM brightness and dimmer feedback, manual override and thermal behavior, and permission on that exact firmware.

The ambient sensor adapter is shared with other Controls workflows that observe light. It unregisters when unused or the screen is off. A new interactive period waits for an event from its new subscription.
