# KipAutomate 0.1 (Android)

An offline Android UI automation app with user-editable JSON scripts, Android AccessibilityService gesture dispatch, local logs, manual launch, `call_active` and `app_open` triggers. No Internet permission, no analytics, no cloud.

## Build

JDK 17+, Android SDK 35, Android Gradle Plugin 8.7.3 and Gradle 8.10.2:

```sh
gradle :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Install and permissions

1. Install the APK and open KipAutomate.
2. In Settings > Accessibility > Installed services, enable **KipAutomate**. Some sideloaded Android builds require selecting **Allow restricted settings** for the app before the service can be enabled.
3. For phone triggers, grant **Phone** permission.
4. Paste, validate and save your JSON scripts.
5. Change `enabled` from `false` to `true` only for scripts you wish to activate. Call tests on `333` should be performed when the call UI is open and unlocked.

## Script format

```json
[
  {
    "name": "Manual smoke test",
    "enabled": true,
    "trigger": {"type": "manual"},
    "actions": [{"type":"log", "message":"it works"}]
  }
]
```

Triggers: `manual`, `call_active`, `app_open` (`trigger.package` required).

Conditions: `conditions.package` for foreground app, `conditions.unlocked` boolean.

Actions: `wait` (`ms`), `tap` (`x`, `y` screen pixels), `tap_text` (`text`), `wait_text` (`text`, `timeoutMs`), `log` (`message`).

**Note:** Android's `OFFHOOK` phone state can mean outgoing dialing or an answered call; it does not reliably distinguish both. `wait_text` can be used to wait for a number or UI state. Callers' phone numbers are not read or stored by this app.

**Note:** The action log indicates gesture completion by Android, not that a third-party app started recording. No in-call audio is captured or stored by KipAutomate itself.

## Privacy

No network access, no ads, no external storage. Logs and scripts are stored in the app's private data directory. Accessibility access can read visible UI content. Do not run scripts from untrusted sources without inspecting them. Local recording laws may require consent from everyone involved.

## Known limitations

- First experimental build; no guarantee of UI gesture support on every handset or protected screen.
- Scripts run sequentially; overlapping triggers are skipped while a script is active.
- The demo `Samsung запись — тест 333` is **disabled by default** and requires user opt-in.
- Automatic actions generally require a live, unlocked call screen.
- `app_open` depends on Accessibility events. The service must be enabled to monitor calls and app launches.
- Use stable signing keys for updates. GitHub Actions' debug APK is signed with a per-run debug key and may need an uninstall for future upgrades.
