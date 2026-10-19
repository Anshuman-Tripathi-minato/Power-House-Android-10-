# Power House — Android 10+

A futuristic battery + Bluetooth control app for Android 10 and newer.

## Open in Android Studio
1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Run on an Android 10+ device/emulator.

## Included
- Live battery level and charging state
- Battery temperature and voltage
- Battery health status (Android health enum)
- Capacity-degradation field with honest `Unavailable` state when Android does not expose design/full capacity to the app
- Charging time estimate when the platform reports it
- Animated battery history chart
- Bluetooth adapter status and adapter rename
- Bluetooth discovery/scan
- Paired-device inventory
- Pair new device via system bonding flow
- Local aliases for remote Bluetooth devices; attempts native alias on API 31+ and gracefully falls back to an app-local alias when the OS denies the privileged operation
- Forget/remove paired device
- Open Bluetooth system settings for device-level connect/disconnect controls
- Orange + neon glass-style UI
- Responsive cards

## Important Android platform limitations
Android's public battery APIs expose battery level, charging state, temperature, voltage and health status, but do not universally expose a laptop-style `full charge capacity / design capacity` value. Therefore this app never fabricates a degradation percentage.

Generic remote Bluetooth device connect/disconnect control is restricted by Android's public APIs and differs by Bluetooth profile. The app provides pairing, forgetting and a system Bluetooth-settings handoff for reliable device-level connection control.

Remote-device battery percentage is not universally exposed by Android's generic Bluetooth API; the UI reports `Unavailable` rather than inventing a value.
