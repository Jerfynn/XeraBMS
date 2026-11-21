# XeraBMS

Android app for monitoring and configuring lithium battery BMS boards over Bluetooth LE.
XERA Robotics Pvt. Ltd.

## Supported BMS

| Family | Detected by | Live data | Switches | Limits |
|---|---|---|---|---|
| JBD / Xiaoxiang (Overkill, LiTime, Redodo and other rebrands) | service `FF00` | yes | charge, discharge | read and write |
| JBD on an `FFE0` Bluetooth module | service `FFE0` | yes | charge, discharge | read and write |
| Daly (address `0x80`, plus `0x40` fallback) | service `FFF0`, names `DL-…` | yes | charge, discharge | read only |
| JK / Jikong, JK02 protocol (24-cell and 32-cell layouts) | service `FFE0`, names `JK-…` | yes | charge, discharge, balancer | read and write |
| Anything else | – | raw data console | – | – |

On connect the app tries every protocol whose GATT service is present (name hints first) and keeps
the first one that answers with a valid frame. You can also force a protocol in the connect dialog.

If nothing answers, the app stays connected in **explorer mode**. It subscribes to every notify
characteristic and shows raw TX/RX bytes in the data console, where you can send your own hex.
This is how to bring up an unsupported BMS (for example a "Webber" battery): capture what it sends,
then add a protocol class as described below. Many branded batteries use a JBD, Daly or JK board
inside, so auto-detection often works without new code.

## Features

- Dashboard with SOC gauge, voltage, current, power, remaining capacity, time to full or empty
- Cell grid with spread, highest and lowest cell, balancing markers, cell resistance (JK)
- Charts for voltage, current, SOC, cell spread and temperature (5 min to full session)
- Charge, discharge and balancer switches with confirmation
- Protection limits screen behind a PIN (default `1234`), with range checks before writing
- Alarm history, CSV logging with share, background monitoring notification with alarm alerts
- Auto reconnect, data watchdog, demo battery for trying the app without hardware

## Build

### GitHub Actions (no setup needed)

Push the repo to GitHub. The workflow in `.github/workflows/android.yml`:

1. runs the protocol unit tests,
2. builds a debug APK and a release APK,
3. uploads both as the `XeraBMS-apk` artifact on the run page.

Push a tag such as `v1.0.0` and the APKs are also attached to a GitHub release.

### Android Studio

Open the folder, let Gradle sync, then **Build > Build APK(s)**. Requires JDK 17 or newer.
Android Studio Ladybug (2024.2) or newer is recommended.

### Command line

```bash
./gradlew testDebugUnitTest   # protocol parser tests, no phone needed
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

## Release signing

Without a keystore, the release APK is signed with the debug key so it still installs.
For a proper release key:

```bash
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias xerabms
base64 -w0 release.jks > release.jks.b64
```

Add these repository secrets in GitHub (Settings > Secrets and variables > Actions):

| Secret | Value |
|---|---|
| `XERA_KEYSTORE_BASE64` | contents of `release.jks.b64` |
| `XERA_KEYSTORE_PASSWORD` | keystore password |
| `XERA_KEY_ALIAS` | `xerabms` |
| `XERA_KEY_PASSWORD` | key password |

For local release builds, put `release.jks` in `keystore/` and export the three password variables.
`keystore/*.jks` is git-ignored.

## Install

Copy the APK to the phone and open it (allow "install unknown apps" for your file manager), or:

```bash
adb install -r XeraBMS-<n>.apk
```

Debug builds install as `com.xera.bms.debug`, so they sit alongside the release build.

Android 8.0 (API 26) or newer with Bluetooth LE. Android 12+ asks for Nearby devices permission.
Android 11 and older need Location turned on to scan.

## Project layout

```
app/src/main/java/com/xera/bms/
├── core/                  Pure Kotlin, no Android imports, unit-tested on the JVM
│   ├── Bytes.kt           Byte helpers, hex parsing, frame buffer
│   ├── BmsModel.kt        BmsSnapshot, BmsParameter, Command, BmsProtocol interface
│   ├── ProtocolRegistry.kt  Protocol list, auto-detect ordering, demo battery
│   └── protocols/         JbdProtocol, DalyProtocol, JkProtocol
├── ble/                   BleScanner, BleConnection (serialized GATT ops, MTU, chunked writes)
├── data/                  BmsSession (connect, detect, poll, publish), settings, CSV logger
├── service/               Foreground service for background monitoring
└── ui/                    Compose screens: scan, dashboard, cells, charts, limits, console, logs, settings
```

`BmsSession` runs all protocol work on one background thread, so protocol classes never see
concurrent calls.

## Adding a new BMS protocol

1. Create `core/protocols/MyBmsProtocol.kt` extending `BaseBmsProtocol`:

```kotlin
class MyBmsProtocol : BaseBmsProtocol() {
    override val id = "mybms"
    override val name = "My BMS"
    override val serviceUuid = uuid16(0xFFE0)
    override val notifyUuid = uuid16(0xFFE1)
    override val writeUuid = uuid16(0xFFE1)
    override val capabilities = setOf<Capability>()
    override val parameters = emptyList<BmsParameter>()

    override fun initCommands() = listOf(Command(bytesOf(0x01, 0x03 /* ... */), 300, "Read status"))
    override fun pollCommands(nowMs: Long) = initCommands()

    override fun feed(data: ByteArray, nowMs: Long): Int {
        buffer.append(data)
        // Find complete frames in buffer.peek(), validate the checksum, decode into
        // state = state.copy(voltage = ..., current = ..., cellVoltages = ...),
        // drop consumed bytes, and return how many valid frames were decoded.
        return 0
    }
}
```

2. Add one entry to `ProtocolRegistry.entries` with its Bluetooth name hints.
3. Add a test in `app/src/test/.../ProtocolTest.kt` using frames captured in the data console.

`feed()` must return a count greater than 0 only for frames that pass validation. Auto-detection
relies on that to tell protocols apart.

## Protocol notes

- **JBD**: `DD A5 <reg> 00 <chk> 77` reads, `DD 5A ...` writes. The checksum is `0x10000 - sum`
  over status, length and data, big-endian. Limits are EEPROM registers accessed in factory mode
  (`DD 5A 00 02 56 78 FF 30 77`); saving exits with `28 28`.
- **Daly**: 13-byte `A5` frames, checksum is the low byte of the sum. Cell voltages (0x95) and
  temperatures (0x96) arrive as several numbered frames. Newer Daly boards that only speak the
  Modbus-style protocol are not covered yet. Use the console to check.
- **JK**: 20-byte requests `AA 55 90 EB ...`, 300-byte responses `55 AA EB 90 ...`. Cell info streams
  continuously after `0x96`. Settings registers map from the settings frame as
  `register = (offset - 6) / 4 + 1`. The 24-cell or 32-cell layout is chosen by checking which
  total-voltage field matches the sum of cell voltages.

## Safety

Changing protection limits or switching MOSFETs affects a real battery. Turning off discharge cuts
power to the load immediately. Check limits against your cell datasheet and test on a bench pack
first.
