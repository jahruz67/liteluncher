<img src="assets/icon.svg" width="64" height="64" alt="Lite Luncher">

# Lite Luncher

A native Android home replacement written in Kotlin and Jetpack Compose. The interface pairs a wallpaper-backed workspace, charcoal surfaces, soft green accents, a large typographic clock, and a floating edge dock.

## Directional workspace

| Position | Screen | From Home |
| --- | --- | --- |
| Center | Home: clock, apps, folders, pinned shortcuts | Home button |
| Above | Search: apps, contacts, shortcuts, settings, calculator and web | Swipe down |
| Right | Applications: alphabetical grid/list with an A–Z index | Swipe left |
| Left | Desktop: native Android widget canvas | Swipe right |
| Below | Favorites: usage ranking, pinned apps and contacts | Swipe up |

Swiping toward Home returns from an adjacent screen. Infinite navigation wraps between enabled screens on the same axis. The bottom navigation and workspace title provide direct access to screens and the visual screen manager. Disable screens, apply cross/horizontal/vertical presets, and choose one- or two-finger navigation in Settings. Gestures operate on unclaimed touch sequences; scrollable app lists and native widget controls retain their normal touch behavior. Swipe on the header or use two fingers to navigate around scrollable content.

Long-press empty Home space for wallpaper, widgets, settings and layout management. Double-tap Home or pinch to invoke the configured gesture. Long-press an app for app information, label/icon editing, home/dock/favorite pinning, hidden-app selection and native shortcuts. The plus button beside an app shortcut pins it to Home. Long-press a pinned shortcut to remove its Home entry.

## Implemented features

- Floating left/right dock with alignment, icon scaling, folder stacks, scrolling, idle auto-hide, scroll visibility and full-edge presentation. Tap its edge handle to reveal it after hiding.
- Persistent Home, dock and favorite ordering; folder creation, membership editing, previews and direct fan-out from the dock. Manage layout uses move-up/down controls.
- Integrated date/clock with 12/24-hour formatting, font choices, sizing and validated hex colors.
- Search history, voice recognition through the installed Android recognizer, safe inline arithmetic, contacts and native shortcuts. Google, Bing, DuckDuckGo, Yahoo, Wikipedia, Baidu, Ecosia, Startpage and custom URL templates are supported.
- Real `AppWidgetHost` hosting: Android bind/configure flows, rounded clipping, non-overlapping grid placement, move/resize controls, refresh and removal. Hold a widget or use its accessibility edit action to manage it.
- Launcher, dock and search themes; Light/Dark/System/Advanced mode; wallpaper dim/parallax; appfilter-based icon packs; custom per-app images; shape masks; system bar controls; notification dots/counts; gesture mappings to screens, apps and selectable native shortcuts.
- Serialized DataStore preference writes and versioned JSON backup/import through Android's document picker. Import validates size, nesting, schema, values and URIs before replacing state.
- Real launcher app enumeration and package/profile updates, profile-specific app identities and badges, Android Home selection, and user-controlled contacts/usage/notification permissions.

## Project setup

Open the root directory in Android Studio. The project includes the Gradle 8.11.1 wrapper and pins the distribution checksum. It targets Android SDK 35, requires JDK 17, and supports Android 8.0 / API 26 and newer. Install the Android SDK 35 platform and configure the SDK path with Android Studio or an untracked `local.properties`.

The project uses Android Gradle Plugin 8.9.1, Kotlin/Compose compiler plugin 2.1.20 and Compose BOM 2025.04.01. No SDK paths, release signing keys or credentials are checked in.

No builds, tests, compilation, lint, dependency sync or emulator runs were performed, as requested. The source has received manual integration review; runtime and release readiness remain unverified.

## GitHub APK releases

`.github/workflows/release.yml` builds, signs and verifies a release APK, then publishes it as `lite-luncher-vX.Y.Z.apk` on a GitHub Release. It uses JDK 17 and Android SDK 35. Push a tag such as `v1.0.0`, or run **Build and release APK** from the Actions tab with a release tag. For a manual run, select the branch/ref to build; a new tag points to that run's commit. If the tag already exists, select that tag as the run's ref so the APK matches its source.

Before the first release, add these repository secrets under **Settings > Secrets and variables > Actions**:

- `ANDROID_SIGNING_KEYSTORE_BASE64`: the Base64-encoded release keystore.
- `ANDROID_SIGNING_ALIAS`: the private key alias in that keystore.
- `ANDROID_SIGNING_STORE_PASSWORD`: the keystore password.
- `ANDROID_SIGNING_KEY_PASSWORD`: the private key password.

Use a dedicated Lite Luncher signing key and keep a secure backup. Reuse the same key for every release so installed copies can update. If you already distribute this application, use its existing signing key.

To encode an existing keystore in PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\to\lite-luncher-release.jks')) | Set-Clipboard
```

Paste the result into `ANDROID_SIGNING_KEYSTORE_BASE64`. The workflow restores the keystore only in the runner's temporary directory and removes it after the run. Missing secrets stop publication.

The tag without `v` becomes Android's `versionName`. The workflow run number plus one becomes `versionCode`; reruns retain that code. Publish new versions with new runs, and avoid rerunning older releases for installed updates. Local builds default to version `1.0.0` / code `1`; Gradle properties `releaseVersionName` and `releaseVersionCode` override these values. Tags containing a hyphen create GitHub prereleases.

## Android integration details

Set Lite Luncher as the default home app using Settings → Default home app. Android grants shortcut-host access to the selected launcher. Native shortcuts are queried and pinned through `LauncherApps`; unavailable or revoked shortcuts fail gracefully.

Contact access is requested only from an explicit permission action. Usage access is optional: without it, Favorites uses this launcher's own launch counts. Notification badges require enabling the notification listener in Android settings. No accessibility service, call permission, broad package permission or direct network permission is requested. Web searches open in an installed browser; calculator and app searches run locally.

App enumeration supports profiles Android exposes to the launcher, excluding locked or quiet profiles. Android private-space integration is not included. Widgets, contacts and system usage statistics use the current user profile. Icon packs support explicit `appfilter.xml` component mappings with system-icon fallback.

Widget IDs belong to the Android device and are excluded from portable backups; import retains widgets already on this device. Provider data, Android permissions, shortcut availability, custom image document grants, app installation and profile serials are not transferable through launcher JSON. Add widgets and reselect unavailable apps/images when migrating to another device. Android providers control how quickly refresh requests update a widget.

## Source layout

```text
app/src/main/java/dev/liteluncher/
  MainActivity.kt             Activity-result, permission and document flows
  LauncherViewModel.kt        Retained repositories
  data/                      Serializable model, ordered persistence, search engine
  platform/                  Android apps, shortcuts, profiles, widgets and badges
  ui/                        Workspace, gestures, dock, screens and layout editor
  settings/                  Settings hierarchy and visual directional screen manager
```

Android contracts follow the [widget host documentation](https://developer.android.com/develop/ui/views/appwidgets/host) and [LauncherApps API](https://developer.android.com/reference/android/content/pm/LauncherApps).
