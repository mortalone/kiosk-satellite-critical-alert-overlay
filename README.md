# Kiosk Satellite Critical Alert Overlay Plugin

A high-visibility critical alert overlay plugin for [Kiosk Satellite](https://github.com/jens-maus/kiosk-satellite).

## Features
- **Full Screen Overlay**: Appears across Kiosk Satellite screensavers, Fotoo, and Lovelace dashboards.
- **Pulsing Visual Warning**: Glowing red card with golden warning border and large headline.
- **Generic Home Assistant Trigger**: Monitors any HA entity (defaults to `binary_sensor.ev_uonsket_dyr_ladning_aktiv`).
- **Snooze / Dismiss**: Easily snooze the alert for 15 minutes directly from the touchscreen.

## Installation in Kiosk Satellite
1. Open Kiosk Satellite Settings -> **Plugins** -> **Install from GitHub**.
2. Enter repository URL: `https://github.com/mortalone/kiosk-satellite-critical-alert-overlay`
3. Tap **Install** and enable the plugin.