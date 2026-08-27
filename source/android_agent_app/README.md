# Android Agent App

APK agent for direct Android-to-hub posting automation.

This project is the phone-native version of the automation stack:

- installed directly on the Android phone
- connected to the hub through the phone's own internet
- no nearby Mac or Windows controller required
- pairing through a link or manual config
- execution through `AccessibilityService`

## What this app does

The app stores:

- hub URL
- runner token
- device label
- account label

Then it can:

- register itself in `mobile_poster_hub`
- run a foreground polling service
- claim jobs from the hub
- launch Instagram / TikTok
- try to execute publish flows through accessibility actions
- report status and logs back to the hub

## Security model

The phone should never get direct access to your Mac mini.

It only receives:

- the public hub URL
- the `runner` token

It does not need:

- SSH
- VNC
- macOS credentials
- the `admin` token

## Pairing model

There are two intended pairing flows:

### 1. Manual setup

The user opens the app and enters:

- hub URL
- runner token
- device label
- account label

### 2. Link-based setup

The app also accepts a deep link like:

`mobileposter://pair?hub_url=https%3A%2F%2Fcontrol.example.com&runner_token=...&device_label=Phone1`

That lets you send a link after the APK is installed.

## Permissions / user actions

The user still has to manually allow:

- notification permission
- accessibility service
- battery optimization exemption if needed

This is normal Android behavior.

## Current state

This repo contains the app scaffold and agent architecture:

- setup screen
- local config storage
- foreground service
- hub client
- accessibility service skeleton
- starter Instagram / TikTok automation helpers

Because this environment does not currently have the Android SDK / JDK installed,
the project was scaffolded but not built into an APK here yet.

## Next practical step

Open this project in Android Studio on a machine with:

- Android Studio
- Android SDK
- JDK 17+

Then build:

- debug APK for testing
- release APK later
