# AGENTS.md — OwnDroid

## Was ist das?

OwnDroid (`com.bintianqi.owndroid`) ist eine Android-App zur Geräteverwaltung über die `DevicePolicyManager`-API. Die App läuft als **Device Owner** (alternativ Dhizuku oder Work Profile) und bietet u.a.: Apps suspendieren/verstecken, Deinstallations-Sperren, User-Restriktionen, Netzwerk-Verwaltung, Passwort-Policy — und einen **Zeitblocker** (Tageslimits + Zeitfenster mit TOTP-geschütztem App Lock).

- Upstream: https://github.com/BinTianqi/OwnDroid
- Signing: `testkey.jks` (Default im Repo, kein Release-Keystore nötig)

## Tech-Stack

- Kotlin, Jetpack Compose (BOM 2026.08), Material 3
- Navigation 3 (`androidx.navigation3`), kotlinx.serialization
- Manuelles DI, kein Hilt/Koin. Keine Tests (deaktiviert in `app/build.gradle.kts`), Lint deaktiviert
- minSdk 23, compileSdk/targetSdk 37, JDK 21, Gradle 9.5.1 (Wrapper)

## Build (NixOS!)

**Der Host hat KEIN Java/SDK installiert.** Kompilieren läuft NUR über die Nix-Shell:

```bash
nix-shell --run "./gradlew assembleDebug"
# oder mit Flakes:
nix develop --command ./gradlew assembleDebug
```

- Vor jedem Gradle-Aufruf (build, compile, lint): immer mit `nix-shell --run "..."` / `nix develop --command ...` prefixen, sonst „java: command not found".
- Erster `nix-shell`-Aufruf baut das SDK (dauert), danach gecacht.
- SDK liegt read-only im Nix-Store. AGP darf nichts installieren — `android.experimental.sdkComponentAutoInstall=false` in `gradle.properties` muss bleiben. `buildToolsVersion = "37.0.0"` in `app/build.gradle.kts` muss zur Version in `shell.nix` passen.

## Verifizierung nach Code-Änderungen

Nach dem Editieren immer:

```bash
nix-shell --run "./gradlew assembleDebug"
```

APK landet in `app/build/outputs/apk/debug/`.

## Projektstruktur

```
app/src/main/java/com/bintianqi/owndroid/
├── AppContainer.kt         # Manuelles DI: Repos, PrivilegeHelper, ViewModelFactory
├── MyViewModelFactory.kt   # ViewModel-Erzeugung (alle VMs hier registrieren)
├── MyDbHelper.kt           # SQLite (Version erhöhen bei Schema-Änderung!)
├── PrivilegeHelper.kt      # DPM-Zugriff (siehe Patterns)
├── Receiver.kt             # DeviceAdminReceiver
├── MainActivity.kt         # Entry, AppLock-Dialog, NavHost
├── ApiReceiver.kt          # Intent-API für externe Automation
├── feature/<name>/         # Features: je Model + Repository + ViewModel + Screen
│   ├── time_blocker/       # Zeitblocker (Tageslimit, blocked/allowed Zeitfenster, FGS-Service)
│   ├── settings/           # App Lock (Passwort/Biometrie/TOTP), Theme, API-Key
│   └── ...
├── ui/
│   ├── navigation/         # Destination.kt (sealed, @Serializable) + EntryProvider.kt
│   ├── screen/Home.kt      # Home-Liste mit Feature-Einträgen
│   └── Components.kt       # Geteilte Composables (FunctionItem, SwitchItem, MyScaffold)
└── utils/                  # DpmUtils, NotificationUtils, Utils (u.a. String.hash())
```

## Patterns (so arbeitet das Projekt)

- **DPM-Calls** ausschließlich via `PrivilegeHelper.safeDpmCall { dpm.x(dar, ...) }` — niemals direkt
- **Neues Feature:** Ordner in `feature/`, Model + Repository + ViewModel + Screen. Dann: `Destination` ergänzen, `entry<>` in `EntryProvider.kt`, ViewModel in `MyViewModelFactory` registrieren, Eintrag in `ui/screen/Home.kt`
- **DB-Änderung:** `MyDbHelper` Version +1, CREATE in `onCreate`, Migration in `onUpgrade`
- **Navigation:** Kinder-Screens mit `metadata = navParentKey<Parent>()` teilen das Parent-ViewModel (`viewModel()` ohne Factory)
- **App-Auswahl-Ergebnis:** `container.chosenPackage` Channel → mit suspendierendem `receive()` in `LaunchedEffect` empfangen (NICHT `tryReceive()`)
- **Icons:** XML-Drawables in `res/drawable/` + `painterResource()`; `material-icons-core` ist unvollständig (z.B. fehlt `LockOpen`)
- **Strings:** neu in `res/values/strings.xml` (en); es existieren `values-ja`, `values-zh-rCN`
- **Drawables:** kein `?attr/colorControlNormal` (kein AppCompat-Theme)

## Bekannte Fallstricke

- **Concurrency:** Services mit `START_STICKY` → `onStartCommand` kann mehrfach laufen (Job tracken/canceln). Mutable Collections nicht zwischen IO-Coroutine und Main-Thread teilen (`Collections.synchronizedSet`). Shared `Long` → `@Volatile`. Kein DB-Zugriff im `BroadcastReceiver.onReceive` (→ `goAsync()` + Thread)
- **Compose:** teure Calls (PackageManager, `loadIcon()`, AppOps) nie direkt im Composable-Body → `remember(key)` oder StateFlow. Edit-Forms mit async geladenen Daten: Loading-Gate, sonst captured `rememberSaveable` leere Initialwerte
- **Service-State:** erst in DB persistieren, dann DPM-Call ausführen (Crash-Sicherheit)
- **UsageStats:** `queryEvents` einmal batchen, nicht pro App abfragen
