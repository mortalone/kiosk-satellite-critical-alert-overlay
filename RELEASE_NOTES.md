# Critical Alert Overlay v1.0.0

High-visibility critical alert overlay for Kiosk Satellite wall tablets and Raspberry Pi displays.

### Features
- **Prominent Full-screen Modal Scrim**: 87% black background with centered deep crimson red alert card and bright golden warning border.
- **Pulsing Animation**: Smooth hardware-accelerated scale/pulse animation to ensure critical alerts (peak electricity tariffs, appliance running in restricted hours, unprompted EV charging) cannot be overlooked from a distance.
- **Configurable HA Trigger Entity**: Default monitors `binary_sensor.ev_uonsket_dyr_ladning_aktiv`. Can monitor any binary sensor or alarm entity.
- **Snooze / Dismiss**: Configurable snooze duration (default 15 minutes) to temporarily dismiss the overlay while dealing with the issue.
- **Auto-Reset**: Returning the entity to safe/off automatically clears the overlay and resets the snooze timer.
