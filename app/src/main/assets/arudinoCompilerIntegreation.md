# Adding an on-device Arduino compiler to an Android app

Working reference for `com.kgjr.aurdinoexperiment`, written after getting it
running end to end on 23 Aug 2026. A sketch is typed on the phone, compiled on
the phone by a real `avr-gcc`, and flashed to an Arduino Uno over USB OTG.
No PC involved at runtime.

This document is meant to be enough on its own. If you hand it to an AI and say
"do this again for board X", it should not have to rediscover anything. The
**Bugs hit** section near the end is the most valuable part — every one of those
cost real time.

---

## 1. What actually got built

```
sketch.ino  (typed in the app)
   │
   │  SketchPreprocessor.java     prepend #include <Arduino.h>, synthesise
   ▼                              forward declarations, emit #line directives
sketch.cpp
   │  libavr_cc1plus.so           C++ front end + AVR code generator
   ▼
sketch.s
   │  libavr_as.so                assembler
   ▼
sketch.o
   │  libavr_ld.so   + core.a     link against the PREBUILT Arduino core
   ▼                + libc/libm/libgcc
sketch.elf
   │  libavr_objcopy.so -O ihex
   ▼
sketch.hex
   │  Stk500Programmer.java       pulse DTR, sync, page-write, verify
   ▼
ATmega328P flash
```

### Measured results

| | |
|---|---|
| Blink sketch on device | **1048 bytes** flash, compiled in **189 ms** |
| Same source, desktop GCC 7.3 reference | 1058 bytes (newer GCC is slightly tighter) |
| Counter/LED sketch, desktop reference | 4620 bytes flash, 218 bytes RAM |
| Board | genuine Arduino Uno R3, VID `0x2341` PID `0x0043`, `CdcAcmSerialDriver` |

### Two decisions that carry the whole design

**The Arduino core ships precompiled as `core.a`.** Built once on a desktop and
bundled in assets. Compiling the ~40 core files on the phone would take minutes.
On-device work is one translation unit plus a link — hence 189 ms.

**The `avr-gcc` driver is never invoked.** Each stage is executed directly. The
driver would try to exec helpers by their real names (`cc1plus`, `as`, `ld`),
and on Android those files must be named `libavr_*.so`, so the driver could
never find them. Driving the stages by hand sidesteps this completely.

---

## 2. The Android constraints

These are the non-negotiable rules. Every one of them caused a failure before
being understood.

### 2.1 You may only execute from `nativeLibraryDir`

Since API 29 an app can only `execve()` files inside its `nativeLibraryDir`.
Anything unzipped into `filesDir` or `cacheDir` is mounted `noexec` and fails
with `EACCES` no matter what you `chmod`.

The only files the installer places there with the exec bit set are those
matching `lib*.so` inside `jniLibs/<abi>/`. So the toolchain executables are
renamed:

```
app/src/main/jniLibs/arm64-v8a/
    libavr_cc1plus.so     the real cc1plus ELF, just renamed   (24 MB)
    libavr_cc1.so         C front end - NOT USED, deletable    (22 MB)
    libavr_as.so          GNU as                               (1.1 MB)
    libavr_ld.so          GNU ld                               (1.4 MB)
    libavr_objcopy.so     objcopy                              (1.0 MB)
    libavr_size.so        size - NOT USED, deletable           (0.8 MB)
```

They are ordinary aarch64 executables. Only the filename changes.

Data files (headers, `.a` archives, crt objects, linker scripts) are never
executed, so they live happily in `filesDir` — unpacked there from
`assets/avr-sdk.zip` on first launch.

### 2.2 Binaries must be PIE

Android has refused to load non-PIE executables since 5.0. GCC's configure
picks `--no-pie` if left alone, and binutils needs the flag passed by hand.
A non-PIE binary installs cleanly and then fails to exec, so the build script
verifies `ET_DYN` on every output.

### 2.3 Gradle, not the manifest

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/arm64-v8a/libavr_*.so"
        }
    }
    androidResources { noCompress += "zip" }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.7.3")
}
```

`settings.gradle.kts` needs JitPack:

```kotlin
maven { url = uri("https://jitpack.io") }
```

| Setting | Why |
|---|---|
| `useLegacyPackaging = true` | Unpacks the binaries into `nativeLibraryDir` with the exec bit at install time. Without it they stay compressed in the APK and there is no real file to exec. |
| `keepDebugSymbols` | The AVR binaries are executables, not shared objects; the strip task chokes on them. (`doNotStrip` on AGP 7.x.) |
| `abiFilters` | Ship only the ABI you built. Adding `armeabi-v7a` without 32-bit binaries yields an APK that installs on an old phone and fails at exec time. |
| `noCompress "zip"` | `avr-sdk.zip` is already deflated. |

**Do NOT put `android:extractNativeLibs="true"` in AndroidManifest.xml.** That
was the old way; AGP 8+ fails the build on sight of it and tells you to use
`useLegacyPackaging`, which writes the attribute into the merged manifest for
you.

The manifest needs only the activity registration:

```xml
<activity
    android:name=".ide.EditorActivity"
    android:exported="false"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:windowSoftInputMode="adjustResize" />
```

### 2.4 API level

Everything is safe down to minSdk 21. Deliberately avoided:
`Process.waitFor(long, TimeUnit)` and `Process.destroyForcibly()` — both API 26+.
`ProcessRunner` polls `exitValue()` with backoff instead.

---

## 3. The exact command lines

Derived from `avr-g++ -v` on a desktop build, then verified to produce a
**byte-identical** `.hex`. If you change a flag, re-verify against a desktop
build — this is the check that catches subtle breakage.

Given `SDK = filesDir/avr-sdk` and `LIB = nativeLibraryDir`:

```bash
# 1. compile
$LIB/libavr_cc1plus.so -quiet \
  -I $SDK/include/arduino -I $SDK/include/variant \
  -isystem $SDK/include/gcc -isystem $SDK/include/gcc-fixed \
  -isystem $SDK/include/avr \
  -D __AVR_ATmega328P__ -D __AVR_DEVICE_NAME__=atmega328p \
  -D F_CPU=16000000L -D ARDUINO=10806 -D ARDUINO_AVR_UNO -D ARDUINO_ARCH_AVR \
  sketch.cpp \
  -mn-flash=1 -mno-skip-bug -mmcu=avr5 \
  -g -Os -w -std=gnu++11 -fpermissive -fno-exceptions \
  -ffunction-sections -fdata-sections -fno-threadsafe-statics \
  -fno-rtti -fno-enforce-eh-specs \
  -o sketch.s

# 2. assemble
$LIB/libavr_as.so -mmcu=avr5 -mno-skip-bug -o sketch.o sketch.s

# 3. link   (-T is required: ld cannot find its own ldscripts on Android)
$LIB/libavr_ld.so -mavr5 \
  -T $SDK/lib/ldscripts/avr5.xn \
  -Tdata 0x800100 \
  -o sketch.elf \
  $SDK/lib/avr5/crtatmega328p.o \
  -L$SDK/lib/gcc-avr5 -L$SDK/lib/avr5 \
  --gc-sections sketch.o $SDK/lib/core.a -lm \
  --start-group -lgcc -lm -lc -latmega328p --end-group

# 4. hex
$LIB/libavr_objcopy.so -O ihex -R .eeprom sketch.elf sketch.hex
```

Notes worth keeping:

- `-mmcu=avr5` is the **architecture**, not the device. The device is conveyed
  by `-D__AVR_ATmega328P__` and the crt/lib choice.
- `-Tdata 0x800100` is SRAM start (0x100) in ld's AVR data-space offset form.
- `avr5.xn` is the script ld would pick itself: the `.xn` variant is for a
  non-demand-paged link, which is every AVR link.
- Linking is done with `ld` directly, not `collect2` — no LTO plugin involved,
  which is why `core.a` must be built **without** `-flto`.

---

## 4. Layout of `assets/avr-sdk.zip`

Unpacks to `filesDir/avr-sdk` on first launch (~2.9 MB extracted; the zip is
~1.35 MB with avr-libc 2.2.1).

```
VERSION                 e.g. "14.2.0-2.42-2.2.1-r2"  (last field = layout rev)
include/arduino/        Arduino core headers (Arduino.h, HardwareSerial.h, ...)
include/variant/        variants/standard (pins_arduino.h)
include/gcc/            GCC's own headers (stddef.h, stdint.h, ...)
include/gcc-fixed/      GCC include-fixed
include/avr/            avr-libc headers (avr/io.h, util/delay.h, ...)
lib/core.a              precompiled Arduino core, Uno @ 16 MHz, NO LTO
lib/avr5/               crtatmega328p.o, libc.a, libm.a, libatmega328p.a
lib/gcc-avr5/           libgcc.a
lib/ldscripts/          avr5.x*  - ld cannot locate these itself
hex/blink.hex           prebuilt example, lets you test upload before the
                        toolchain binaries exist
```

**The zip and the binaries are a matched set.** The script regenerates the zip
from the toolchain it just built so GCC's headers, `libgcc.a`, avr-libc and
`core.a` all agree. Never mix a zip from one GCC major version with binaries
from another.

`AvrSdk.isInstalled()` probes an actual file from each part rather than
trusting `VERSION` — see bug 11.

---

## 5. Java file inventory

```
ide/
  EditorActivity.java      UI: code editor, Compile / Upload, build log
  ArduinoCompiler.java     the 4-stage pipeline above
  SketchPreprocessor.java  .ino -> .cpp (Arduino.h, prototypes, #line)
  AvrSdk.java              unpacks assets/avr-sdk.zip, hands out paths
  Toolchain.java           locates the libavr_*.so executables
  Board.java               board profile: mcu, flags, baud, page size, script
  ProcessRunner.java       exec + capture + timeout (API-21 safe)
  BuildResult.java
  IntelHexSize.java
flash/
  Stk500Programmer.java    optiboot / STK500v1
  IntelHex.java            Intel HEX reader, checksum-verified
  SerialLink.java          port abstraction (4 methods)
  UsbSerialLink.java       usb-serial-for-android binding
  Uploader.java            device discovery + USB permission
```

### Adding a new board

Fill in a `Board` constant, then make sure the SDK zip carries that board's
variant headers, its `crt*.o` / `lib*.a`, and a `core.a` built with its `F_CPU`
and defines. A different architecture (`avr4`, `avr51`) also needs the matching
multilib directory from avr-libc and its own `ldscripts` entry.
`Board.mcuMacro()` throws on an unknown mcu rather than guessing — extend it
deliberately.

### Adding a library (SPI, Wire, Servo, EEPROM)

Only the Arduino core is in `core.a`. To add a library: compile it into
`core.a` in the build script, add its header directory to the SDK zip, and add
an `-I` for it in `ArduinoCompiler.cc1plusCommand`. Symptom if you forget:
`undefined reference` at link time.

---

## 6. STK500v1 upload protocol

What `avrdude -c arduino -p m328p` does. Every command is
`<opcode> [args] 0x20`; the bootloader answers `0x14 <payload> 0x10`.

```
pulse DTR/RTS       setDTR(false); setRTS(false); sleep 250ms;
                    setDTR(true);  setRTS(true);  sleep 50ms; purge
GET_SYNC   0x30     retry until it answers 0x14 0x10
READ_SIGN  0x75     expect 0x1E 0x95 0x0F for ATmega328P
ENTER_PROGMODE 0x50
  per 128-byte page:
    LOAD_ADDRESS 0x55  lo, hi     <- WORD address = byteAddress >> 1
    PROG_PAGE    0x64  sizeHi, sizeLo, 'F', <data>
  optional verify:
    LOAD_ADDRESS then READ_PAGE 0x74 sizeHi, sizeLo, 'F'
LEAVE_PROGMODE 0x51
```

Uno R3: **115200 baud**, 128-byte pages. An old-bootloader Nano is 57600.

Two things that are easy to get wrong:

- **Addresses are words, not bytes.** `LOAD_ADDRESS` takes `byteAddr >> 1`.
- **Never read one byte at a time from USB.** See bug 12.

---

## 7. Building the toolchain

Ubuntu 22.04/24.04 host, no Docker needed. ~60–90 min, ~12 GB scratch.

```bash
cd toolchain
chmod +x build-native.sh build-avr-android.sh
nohup ./build-native.sh > build.log 2>&1 &
tail -f build.log
```

Output:

```
out/jniLibs/arm64-v8a/libavr_*.so   ->  app/src/main/jniLibs/arm64-v8a/
out/avr-sdk.zip                     ->  app/src/main/assets/
```

Success looks like:

```
==> checking the binaries are self-contained aarch64 executables
  all good
==> done
```

**Never skip that check.** It verifies every binary is aarch64, PIE, and needs
nothing beyond bionic (`libc.so`, `libm.so`, `libdl.so`, `liblog.so`). A binary
needing `libc++_shared.so` or `libmpfr.so.6` will not load on the phone.

### Why GCC is built twice

It is a Canadian cross: `build=x86_64-linux`, `host=aarch64-android`,
`target=avr`.

- **Stage 1** — a normal x86-hosted `avr-gcc`. Required because the AVR target
  libraries (`libgcc.a`, avr-libc) must be produced by a compiler this machine
  can actually run.
- **Stage 2** — the same GCC source rebuilt to run on ARM64. This is what ships.

Stage 2 builds **only** `cc1plus` and `cc1`, copied out of the build tree by
hand. See bugs 5 and 6.

### Versions used

| Component | Version |
|---|---|
| Android NDK | r27c, API 24 |
| binutils | 2.42 |
| GCC | 14.2.0 |
| avr-libc | 2.2.1 |
| GMP / MPFR / MPC | 6.3.0 / 4.2.1 / 1.3.1 |
| Arduino AVR core | 1.8.6 (Ubuntu `arduino-core-avr`) |

The build is **resumable** — each stage is guarded on its output existing. If
a step fails, fix it and re-run; it picks up where it stopped. Changing
`configure` flags requires deleting that stage's build dir first, because
configure caches.

---

## 8. Bugs hit, and the fixes

The real content of this document. In the order they appeared.

### Build-script bugs

**1. `say()` wrote to stdout.** `fetch()` returns its path via stdout, so the
progress banner got captured into the variable and `unzip` received
`"==> downloading ...\n/path/to.zip"` as a filename. **Fix:** progress messages
go to stderr (`>&2`). Affects every function whose result is captured with
`$(...)`.

**2. Missing host maths libraries.** Stage-1 GCC is an ordinary x86 build and
needs `libgmp-dev libmpfr-dev libmpc-dev` (plus `zlib1g-dev libzstd-dev`) on the
host. **Fix:** added to the package list.

**3. zstd.** GCC 13+ probes for libzstd for LTO compression; stage 2 would go
looking for an *Android* libzstd. **Fix:** `--without-zstd`.

**4. `lto-plugin` fails to build.** It is a host *shared library*, and building
one for Android through libtool fails. **Fix:** `--disable-lto --disable-plugin`.
We never use LTO — `core.a` is built without it and the app calls `ld` directly.

**5. Canadian cross tries to build target libs with the ARM compiler.** Plain
`make` builds `libgcc.a` using the compiler it just produced — an ARM64 binary
the x86 machine cannot execute. **Fix:** build only `cc1plus`/`cc1` and copy
them out manually. Stage 1 already produced the target libraries.

**6. `improper alignment for relocation R_AARCH64_LDST64_ABS_LO12_NC`.** Without
PIE, clang addresses globals as `adrp` + `ldr [x,:lo12:sym]`, which lld rejects
unless the symbol is 8-byte aligned. **Fix:** `--enable-host-pie`. Under PIE
those loads go through the GOT, whose entries are aligned by construction.
This was required anyway — Android will not load non-PIE executables.

**7. binutils built non-PIE.** It has no `--enable-host-pie` switch. **Fix:**
`-fPIE` in `CFLAGS`/`CXXFLAGS` and `-pie` in `LDFLAGS`, globally.

### Android build bugs

**8. `android:extractNativeLibs is set to "true" in AndroidManifest.xml`.** AGP
8+ rejects the manifest attribute outright. **Fix:** remove it; use
`packaging { jniLibs { useLegacyPackaging = true } }` only.

**9. API 26+ calls.** `Process.waitFor(long, TimeUnit)` and `destroyForcibly()`.
**Fix:** poll `exitValue()` with backoff.

### Runtime bugs

**10. `cannot open linker script file ldscripts/avr5.xn`.** GNU ld resolves
`ldscripts/` relative to where its binary was installed; as `libavr_ld.so` in
`nativeLibraryDir` that lookup finds nothing. **Fix:** ship `lib/ldscripts/` in
the SDK zip and pass `-T <abs path>/avr5.xn`. Verified byte-identical to
letting ld choose.

**11. Stale SDK never re-extracted.** `AvrSdk` compared a `VERSION` string; when
the zip contents changed but the toolchain versions did not, it silently skipped
unpacking and the failure surfaced later as a missing file. **Fix:**
`isInstalled()` probes an actual file from each part of the tree. Also bump the
`-rN` layout revision in `VERSION` when contents change.

**12. Upload timed out at sync.** `readByte()` called `port.read()` with a
**1-byte** buffer. USB bulk endpoints deliver whole packets and
usb-serial-for-android needs a destination at least the endpoint max packet size
(64 bytes on CDC-ACM), so optiboot's `0x14 0x10` reply was mangled. **Fix:** read
into a 512-byte buffer and hand out bytes from it. *A simulated bootloader did
not catch this — it returned one byte per call. Only real hardware exposed it.*

### Environment gotchas

**13. `set -e` killed the script on an unrelated apt failure.** A pre-existing
broken package (`v4l2loopback-dkms`) made `apt-get install` exit non-zero even
though all wanted packages installed. **Fix:** remove the broken package, re-run.

**14. `nohup ... &` detaches stdin, so `sudo` cannot prompt.** Typed characters
echo to the terminal and may end up in shell history. **Fix:** run the
`apt-get install` in the foreground first, then background the build.

**15. Spaces in the working directory path.** `configure`/`make` handle them
badly. Keep the toolchain directory space-free.

---

## 9. Size

| | Download | On device |
|---|---|---|
| App itself (AppCompat + constraintlayout) | ~4 MB | ~10 MB |
| `avr-sdk.zip` | 1.35 MB | 1.35 MB + ~2.9 MB unpacked |
| Toolchain binaries | ~12–18 MB | ~50 MB |
| **Total** | **~18–25 MB** | **~65 MB** |

Binaries sit DEFLATE-compressed in the APK *and* get extracted uncompressed
into `nativeLibraryDir`, so both copies cost disk.

**Easy win:** delete `libavr_cc1.so` (22 MB, the C front end) and
`libavr_size.so` (0.8 MB). Nothing calls either — `ArduinoCompiler` only uses
`cc1plus`, and the size report is computed from the hex in Java.
`Toolchain.isComplete()` does not require them. That takes you to roughly
**~10–14 MB download, ~35–45 MB installed**.

For scale: ArduinoDroid is ~200 MB, but it bundles several ABIs, many board
families and the full Arduino library set.

---

## 10. Verification

Host-side, no device or Android SDK needed:

```bash
cd tests && ./run.sh
```

Typechecks every source file against stubbed Android classes and runs the
STK500 programmer against a simulated optiboot bootloader — page addressing,
page splitting, readback verify, retry on dropped sync, wrong-signature refusal,
timeout on a silent board.

**The simulation is not sufficient on its own** — bug 12 passed every simulated
test and still failed on hardware. Always finish on the real board.

The strongest correctness check for the compiler is comparing output against a
desktop build of the same sketch:

```bash
arduino-cli compile -b arduino:avr:uno --output-dir /tmp/ref sketch/
cmp /tmp/ref/sketch.ino.hex phone-produced.hex
```

Sizes should match to the byte when the toolchain versions match.

---

## 11. Troubleshooting

| Symptom | Cause |
|---|---|
| `error=13, Permission denied` on exec | `useLegacyPackaging = true` missing |
| `error=2, No such file or directory` | Same, or wrong ABI folder |
| Toast "AVR toolchain missing" | No `jniLibs/arm64-v8a/` — toolchain not built or not copied |
| "AVR SDK is out of date" | `assets/avr-sdk.zip` predates `lib/ldscripts/`; re-run the toolchain build |
| `cannot open linker script file` | Missing `-T`, or `ldscripts/` not in the zip |
| `undefined reference to 'SPI'` etc. | Library not compiled into `core.a` |
| "Could not sync with the bootloader" | Serial monitor still holding the port; OTG cable not passing DTR; insufficient current (board's ON LED must be lit); wrong baud |
| No USB serial device found | OTG adapter, or phone lacks USB host |
| Prototype not generated | Scanner skips templates, multi-line signatures and nested template return types. Declare by hand — the Arduino IDE has the same limitation. |

---

## Appendix A — `toolchain/build-native.sh`

Host wrapper: checks/install packages, sets paths, calls the real script.

```bash
#!/usr/bin/env bash
#
# Run the AVR-for-Android toolchain build directly on an Ubuntu host.
# No Docker needed - the Dockerfile's base image is Ubuntu anyway, so on
# 22.04 / 24.04 this does exactly the same thing with one less moving part.
#
#   ./build-native.sh
#
# Output (same as the Docker route):
#   ./out/jniLibs/arm64-v8a/libavr_*.so   -> app/src/main/jniLibs/arm64-v8a/
#   ./out/avr-sdk.zip                     -> app/src/main/assets/
#
# Scratch space goes in ~/avr-android-build (about 12 GB at peak; delete it
# afterwards). Everything is resumable: the script skips stages whose output
# already exists, so if a step fails you can fix it and re-run without
# starting over.

set -euo pipefail
cd "$(dirname "$0")"

echo "== checking build dependencies"

PACKAGES=(
    build-essential bison flex texinfo curl ca-certificates
    unzip zip xz-utils file python3 gawk libtool automake autoconf
    pkg-config git patchelf
    # Stage-1 GCC is an ordinary x86_64 build, so it needs these on the host.
    # (The Android-hosted copies in stage 2 are cross-built separately.)
    libgmp-dev libmpfr-dev libmpc-dev zlib1g-dev libzstd-dev
    gcc-avr avr-libc binutils-avr arduino-core-avr
)

MISSING=()
for p in "${PACKAGES[@]}"; do
    dpkg -s "$p" >/dev/null 2>&1 || MISSING+=("$p")
done

if [ ${#MISSING[@]} -gt 0 ]; then
    echo "   need to install: ${MISSING[*]}"
    echo
    sudo apt-get update
    sudo apt-get install -y --no-install-recommends "${MISSING[@]}"
else
    echo "   all present"
fi

# arduino-core-avr lives in Ubuntu's universe component. If it did not install,
# the core.a step later would fail with a confusing missing-path error, so
# check for it up front.
if [ ! -d /usr/share/arduino/hardware/arduino/avr/cores/arduino ]; then
    echo
    echo "ERROR: the Arduino AVR core sources are not where expected."
    echo "       Enable the 'universe' component and install arduino-core-avr:"
    echo
    echo "         sudo add-apt-repository universe"
    echo "         sudo apt-get update"
    echo "         sudo apt-get install arduino-core-avr"
    exit 1
fi

export BUILD_ROOT="${BUILD_ROOT:-$HOME/avr-android-build}"
export OUT="${OUT:-$PWD/out}"

FREE_GB=$(df -BG --output=avail "$(dirname "$BUILD_ROOT")" | tail -1 | tr -dc '0-9')
if [ "$FREE_GB" -lt 15 ]; then
    echo
    echo "WARNING: only ${FREE_GB} GB free on $(dirname "$BUILD_ROOT")."
    echo "         The build needs roughly 12 GB at peak."
    read -r -p "         Continue anyway? [y/N] " reply
    [[ "$reply" =~ ^[Yy]$ ]] || exit 1
fi

echo
echo "   scratch : $BUILD_ROOT"
echo "   output  : $OUT"
echo "   jobs    : ${JOBS:-$(nproc)}"
echo
echo "This takes 40-90 minutes. GCC is built twice (once natively to produce"
echo "the AVR target libraries, once to run on the phone) - that is expected,"
echo "not a loop."
echo

exec ./build-avr-android.sh
```

---

## Appendix B — `toolchain/build-avr-android.sh`

The actual build. Runs standalone or inside the Docker image.

```bash
#!/usr/bin/env bash
#
# Build avr-gcc for Android/aarch64 and package it for the app.
#
# Runs inside the Docker image built from the neighbouring Dockerfile.
# Everything lands in /out.
#
# What comes out:
#   /out/jniLibs/arm64-v8a/libavr_cc1plus.so   the C++ front end + code generator
#   /out/jniLibs/arm64-v8a/libavr_cc1.so       the C front end (for .c libraries)
#   /out/jniLibs/arm64-v8a/libavr_as.so        GNU assembler
#   /out/jniLibs/arm64-v8a/libavr_ld.so        GNU linker
#   /out/jniLibs/arm64-v8a/libavr_objcopy.so   ELF -> Intel HEX
#   /out/jniLibs/arm64-v8a/libavr_size.so      size report (optional)
#   /out/avr-sdk.zip                           headers + libs + prebuilt core.a
#
# The .so suffix is a packaging trick, not a claim that these are libraries.
# Since Android 10 an app may only execve() files inside its nativeLibraryDir,
# and only files matching lib*.so are extracted there. So the executables get
# renamed. Nothing else about them changes.

set -euo pipefail

# ---------------------------------------------------------------- versions --
NDK_VERSION="${NDK_VERSION:-r27c}"
BINUTILS_VERSION="${BINUTILS_VERSION:-2.42}"
GCC_VERSION="${GCC_VERSION:-14.2.0}"
AVRLIBC_VERSION="${AVRLIBC_VERSION:-2.2.1}"
GMP_VERSION="${GMP_VERSION:-6.3.0}"
MPFR_VERSION="${MPFR_VERSION:-4.2.1}"
MPC_VERSION="${MPC_VERSION:-1.3.1}"

ANDROID_API="${ANDROID_API:-24}"
ANDROID_HOST="aarch64-linux-android"
JOBS="${JOBS:-$(nproc)}"

# Paths. Defaults suit the Docker image; build-native.sh overrides them so the
# same script can run straight on an Ubuntu host with no container.
BUILD_ROOT="${BUILD_ROOT:-/build}"
OUT="${OUT:-/out}"

SRC="$BUILD_ROOT/src"
WORK="$BUILD_ROOT/work"
DEPS="$BUILD_ROOT/deps"       # gmp/mpfr/mpc built for the phone
NATIVE="$BUILD_ROOT/native"   # x86_64-hosted avr toolchain (stage 1)
STAGE="$BUILD_ROOT/stage"     # the phone-hosted toolchain, pre-packaging

mkdir -p "$SRC" "$WORK" "$DEPS" "$NATIVE" "$STAGE" "$OUT"

# NOTE: progress goes to STDERR, not stdout. fetch() and unpack() return their
# result path on stdout via command substitution, so anything else printed there
# would be captured into the caller's variable.
say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*" >&2; }

# ET_DYN means PIE. Android will not run an ET_EXEC binary at all.
is_pie() { [ -f "$1" ] && "$READELF" -h "$1" 2>/dev/null | grep -q "DYN ("; }

fetch() {
  local url="$1" file="${SRC}/$(basename "$1")"
  if [ ! -f "$file" ]; then
    say "downloading $(basename "$url")"
    curl -fsSL --retry 3 -o "$file.part" "$url"
    mv "$file.part" "$file"
  fi
  echo "$file"
}

unpack() {
  local tarball="$1" expect="$2"
  if [ ! -d "${SRC}/${expect}" ]; then
    say "unpacking $(basename "$tarball")"
    tar -C "$SRC" -xf "$tarball"
  fi
  echo "${SRC}/${expect}"
}

# ---------------------------------------------------------------------- NDK --
say "Android NDK ${NDK_VERSION}"
NDK_ZIP=$(fetch "https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip")
NDK_DIR="${SRC}/android-ndk-${NDK_VERSION}"
if [ ! -d "$NDK_DIR" ]; then
  [ -f "$NDK_ZIP" ] || { echo "NDK archive missing: $NDK_ZIP" >&2; exit 1; }
  say "unpacking the NDK (about 700 MB, takes a minute)"
  unzip -q "$NDK_ZIP" -d "$SRC"
fi

TOOLCHAIN="${NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64"
export CC="${TOOLCHAIN}/bin/${ANDROID_HOST}${ANDROID_API}-clang"
export CXX="${TOOLCHAIN}/bin/${ANDROID_HOST}${ANDROID_API}-clang++"
export AR="${TOOLCHAIN}/bin/llvm-ar"
export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
export STRIP="${TOOLCHAIN}/bin/llvm-strip"
export READELF="${TOOLCHAIN}/bin/llvm-readelf"

[ -x "$CC" ] || { echo "NDK clang not found at $CC"; exit 1; }

# -static-libstdc++  so the binaries do not need libc++_shared.so sitting next
#                    to them in nativeLibraryDir.
# -fPIE / -pie        Android has refused to load non-PIE executables since 5.0.
#                     GCC has --enable-host-pie for this; binutils does not, so
#                     it gets the flags here.
export CXXFLAGS="-O2 -static-libstdc++ -fPIE"
export CFLAGS="-O2 -fPIE"
export LDFLAGS="-static-libstdc++ -pie"

# ------------------------------------------------------- sources -----------
BINUTILS_SRC=$(unpack "$(fetch "https://ftp.gnu.org/gnu/binutils/binutils-${BINUTILS_VERSION}.tar.xz")" "binutils-${BINUTILS_VERSION}")
GCC_SRC=$(unpack      "$(fetch "https://ftp.gnu.org/gnu/gcc/gcc-${GCC_VERSION}/gcc-${GCC_VERSION}.tar.xz")" "gcc-${GCC_VERSION}")
GMP_SRC=$(unpack      "$(fetch "https://ftp.gnu.org/gnu/gmp/gmp-${GMP_VERSION}.tar.xz")" "gmp-${GMP_VERSION}")
MPFR_SRC=$(unpack     "$(fetch "https://ftp.gnu.org/gnu/mpfr/mpfr-${MPFR_VERSION}.tar.xz")" "mpfr-${MPFR_VERSION}")
MPC_SRC=$(unpack      "$(fetch "https://ftp.gnu.org/gnu/mpc/mpc-${MPC_VERSION}.tar.gz")" "mpc-${MPC_VERSION}")
AVRLIBC_SRC=$(unpack  "$(fetch "https://github.com/avrdudes/avr-libc/releases/download/avr-libc-${AVRLIBC_VERSION//./_}-release/avr-libc-${AVRLIBC_VERSION}.tar.bz2")" "avr-libc-${AVRLIBC_VERSION}")

# =============================================================================
# STAGE 1 - a normal x86_64-hosted avr-gcc.
#
# A Canadian cross cannot run the compiler it is building, so the AVR target
# libraries (libgcc.a, avr-libc) have to be produced by a build-machine
# compiler of the same version. That is what this stage is for.
# =============================================================================
say "stage 1: native avr-binutils ${BINUTILS_VERSION}"
mkdir -p "${WORK}/native-binutils" && cd "${WORK}/native-binutils"
if [ ! -x "${NATIVE}/bin/avr-as" ]; then
  env -u CC -u CXX -u AR -u RANLIB -u STRIP -u CFLAGS -u CXXFLAGS -u LDFLAGS \
    "${BINUTILS_SRC}/configure" \
      --prefix="$NATIVE" --target=avr \
      --disable-nls --disable-werror --disable-gdb --disable-sim --disable-doc
  env -u CC -u CXX -u AR -u RANLIB -u STRIP -u CFLAGS -u CXXFLAGS -u LDFLAGS make -j"$JOBS"
  env -u CC -u CXX -u AR -u RANLIB -u STRIP -u CFLAGS -u CXXFLAGS -u LDFLAGS make install
fi
export PATH="${NATIVE}/bin:$PATH"

say "stage 1: native avr-gcc ${GCC_VERSION}"
mkdir -p "${WORK}/native-gcc" && cd "${WORK}/native-gcc"
if [ ! -x "${NATIVE}/bin/avr-gcc" ]; then
  env -u CC -u CXX -u AR -u RANLIB -u STRIP -u CFLAGS -u CXXFLAGS -u LDFLAGS \
    "${GCC_SRC}/configure" \
      --prefix="$NATIVE" --target=avr \
      --enable-languages=c,c++ \
      --with-newlib --disable-nls --disable-libssp --disable-libada \
      --disable-shared --disable-threads --disable-libgomp --disable-libstdcxx \
      --with-dwarf2 --disable-doc --without-zstd
  env -u CC -u CXX -u AR -u RANLIB -u STRIP -u CFLAGS -u CXXFLAGS -u LDFLAGS make -j"$JOBS"
  env -u CC -u CXX -u AR -u RANLIB -u STRIP -u CFLAGS -u CXXFLAGS -u LDFLAGS make install
fi

say "stage 1: avr-libc ${AVRLIBC_VERSION}"
mkdir -p "${WORK}/avrlibc" && cd "${WORK}/avrlibc"
if [ ! -f "${NATIVE}/avr/lib/avr5/libc.a" ]; then
  env -u CC -u CXX -u AR -u RANLIB -u STRIP -u CFLAGS -u CXXFLAGS -u LDFLAGS \
    "${AVRLIBC_SRC}/configure" --prefix="$NATIVE" --host=avr --build="$(${AVRLIBC_SRC}/config.guess)"
  env -u CC -u CXX -u AR -u RANLIB -u STRIP -u CFLAGS -u CXXFLAGS -u LDFLAGS make -j"$JOBS"
  env -u CC -u CXX -u AR -u RANLIB -u STRIP -u CFLAGS -u CXXFLAGS -u LDFLAGS make install
fi

# =============================================================================
# STAGE 2 - the same toolchain, rebuilt to run on the phone.
# =============================================================================
say "stage 2: gmp / mpfr / mpc for ${ANDROID_HOST}"
build_dep() {
  local src="$1" name="$2"; shift 2
  mkdir -p "${WORK}/${name}" && cd "${WORK}/${name}"
  [ -f "${DEPS}/lib/lib${name}.a" ] && return 0
  "${src}/configure" --host="$ANDROID_HOST" --prefix="$DEPS" \
      --enable-static --disable-shared "$@"
  make -j"$JOBS"
  make install
}
build_dep "$GMP_SRC"  gmp
build_dep "$MPFR_SRC" mpfr --with-gmp="$DEPS"
build_dep "$MPC_SRC"  mpc  --with-gmp="$DEPS" --with-mpfr="$DEPS"

say "stage 2: avr-binutils for ${ANDROID_HOST}"
if is_pie "${STAGE}/avr/bin/as" || is_pie "${STAGE}/bin/avr-as"; then
  say "stage 2 binutils already built as PIE, skipping"
else
  # A previous run may have produced non-PIE binaries. configure caches the
  # old flags, so the build tree has to go for -fPIE/-pie to take effect.
  rm -rf "${WORK}/android-binutils"
  mkdir -p "${WORK}/android-binutils" && cd "${WORK}/android-binutils"
  "${BINUTILS_SRC}/configure" \
      --prefix="$STAGE" \
      --host="$ANDROID_HOST" \
      --target=avr \
      --disable-nls --disable-werror --disable-gdb --disable-sim --disable-doc \
      --disable-shared --enable-static
  make -j"$JOBS"
  make install
fi

say "stage 2: avr-gcc for ${ANDROID_HOST}"
GCC_LIBEXEC="${STAGE}/libexec/gcc/avr/${GCC_VERSION}"
if [ ! -f "${GCC_LIBEXEC}/cc1plus" ]; then
  mkdir -p "${WORK}/android-gcc" && cd "${WORK}/android-gcc"

  # Two things are deliberately switched off here:
  #
  #   --enable-host-pie
  #       Two reasons, both hard requirements:
  #         a) Android has refused to load non-PIE executables since 5.0, so
  #            a --no-pie build would produce binaries the phone rejects.
  #         b) Without PIE, clang addresses globals as adrp + ldr [x,:lo12:sym],
  #            which lld rejects unless the symbol is 8-byte aligned:
  #              "improper alignment for relocation R_AARCH64_LDST64_ABS_LO12_NC"
  #            Under PIE those loads go through the GOT, whose entries are
  #            aligned by construction, and the error disappears.
  #       GCC's own configure picks --no-pie here if left to itself.
  #
  #   --disable-lto / --disable-plugin
  #       lto-plugin is a HOST shared library that ld dlopens for link-time
  #       optimization. Building a shared lib for Android through libtool
  #       fails, and we have no use for it: core.a is built without -flto and
  #       the app drives ld directly with no plugin.
  #
  # CC_FOR_TARGET & friends point at the stage-1 compiler, so anything
  # target-side that does get built uses a compiler this machine can run.
  "${GCC_SRC}/configure" \
      --prefix="$STAGE" \
      --build="$(${GCC_SRC}/config.guess)" \
      --host="$ANDROID_HOST" \
      --target=avr \
      --enable-languages=c,c++ \
      --with-newlib --disable-nls --disable-libssp --disable-libada \
      --disable-shared --disable-threads --disable-libgomp --disable-libstdcxx \
      --with-dwarf2 --disable-doc --without-zstd \
      --disable-lto --disable-plugin \
      --enable-host-pie \
      --with-gmp="$DEPS" --with-mpfr="$DEPS" --with-mpc="$DEPS" \
      CC_FOR_TARGET="${NATIVE}/bin/avr-gcc" \
      CXX_FOR_TARGET="${NATIVE}/bin/avr-g++" \
      AR_FOR_TARGET="${NATIVE}/bin/avr-ar" \
      RANLIB_FOR_TARGET="${NATIVE}/bin/avr-ranlib" \
      AS_FOR_TARGET="${NATIVE}/bin/avr-as" \
      LD_FOR_TARGET="${NATIVE}/bin/avr-ld" \
      NM_FOR_TARGET="${NATIVE}/bin/avr-nm"

  # We build ONLY cc1plus and cc1, and copy them out by hand.
  #
  # Two separate reasons:
  #
  #   1. Plain `make` would build the AVR target libraries using the compiler
  #      it just produced - an ARM64 binary this x86 machine cannot execute.
  #      libgcc.a and avr-libc already came from stage 1; that is what stage 1
  #      is for.
  #
  #   2. `make all-gcc` additionally links gcov-tool, collect2 and the gcc
  #      driver, and those links fail under the NDK's lld with
  #        "improper alignment for relocation R_AARCH64_LDST64_ABS_LO12_NC"
  #      lld enforces 8-byte alignment for 64-bit loads where GNU ld quietly
  #      tolerates 4. We ship none of those three binaries - ArduinoCompiler
  #      invokes cc1plus, as, ld and objcopy directly - so the simplest fix is
  #      not to build them.
  #
  # The first make is expected to report errors; it gets libiberty, libcpp,
  # libdecnumber, libbacktrace and the generator programs built. The second
  # asks for the two targets we care about.
  make -j"$JOBS" all-gcc || say "all-gcc reported errors (expected) - building cc1plus directly"
  make -j"$JOBS" -C gcc cc1plus || true
  make -j"$JOBS" -C gcc cc1     || true

  mkdir -p "$GCC_LIBEXEC"
  for b in cc1plus cc1; do
    if [ -f "${WORK}/android-gcc/gcc/$b" ]; then
      cp "${WORK}/android-gcc/gcc/$b" "${GCC_LIBEXEC}/$b"
      say "got $b"
    fi
  done

  if [ ! -f "${GCC_LIBEXEC}/cc1plus" ]; then
    echo >&2
    echo "cc1plus did not build. Search the log for the first 'error:' after" >&2
    echo "the 'stage 2: avr-gcc' banner - if it is the same lld relocation" >&2
    echo "complaint, cc1plus itself is affected and we need a different" >&2
    echo "approach rather than just skipping binaries." >&2
    exit 1
  fi
else
  say "stage 2 avr-gcc already built, skipping"
fi

# =============================================================================
# PACKAGE
# =============================================================================
say "packaging jniLibs"
JNI="${OUT}/jniLibs/arm64-v8a"
rm -rf "$JNI"; mkdir -p "$JNI"

copy_bin() {
  local from="$1" to="$2"
  if [ ! -f "$from" ]; then
    echo "  MISSING: $from"
    return 1
  fi
  cp "$from" "${JNI}/${to}"
  "$STRIP" --strip-unneeded "${JNI}/${to}" 2>/dev/null || true
  chmod 755 "${JNI}/${to}"
  printf '  %-24s %8s KB\n' "$to" "$(( $(stat -c%s "${JNI}/${to}") / 1024 ))"
}

copy_bin "${GCC_LIBEXEC}/cc1plus"    libavr_cc1plus.so
copy_bin "${GCC_LIBEXEC}/cc1"        libavr_cc1.so   || true
copy_bin "${STAGE}/avr/bin/as"       libavr_as.so     || copy_bin "${STAGE}/bin/avr-as"      libavr_as.so
copy_bin "${STAGE}/avr/bin/ld"       libavr_ld.so     || copy_bin "${STAGE}/bin/avr-ld"      libavr_ld.so
copy_bin "${STAGE}/bin/avr-objcopy"  libavr_objcopy.so
copy_bin "${STAGE}/bin/avr-size"     libavr_size.so   || true

say "checking the binaries are self-contained aarch64 executables"
FAILED=0
for f in "${JNI}"/*.so; do
  file "$f" | grep -q "ARM aarch64" || { echo "  !! $(basename "$f") is not aarch64"; FAILED=1; }
  is_pie "$f" \
      || { echo "  !! $(basename "$f") is not PIE - Android will refuse to run it"; FAILED=1; }
  NEEDED=$("$READELF" -d "$f" 2>/dev/null | grep NEEDED | awk '{print $NF}' | tr -d '[]' || true)
  for lib in $NEEDED; do
    case "$lib" in
      libc.so|libm.so|libdl.so|liblog.so) ;;
      *) echo "  !! $(basename "$f") needs $lib - it will not load on the phone"; FAILED=1 ;;
    esac
  done
done
[ "$FAILED" -eq 0 ] && echo "  all good" || echo "  ^ fix the above before shipping"

say "packaging avr-sdk.zip"
SDK="${WORK}/sdk"
ARDUINO=/usr/share/arduino/hardware/arduino/avr
rm -rf "$SDK"
mkdir -p "$SDK"/include/{arduino,variant,gcc,gcc-fixed,avr} "$SDK"/lib/{avr5,gcc-avr5} "$SDK"/hex

cp "$ARDUINO"/cores/arduino/*.h      "$SDK/include/arduino/"
cp "$ARDUINO"/variants/standard/*.h  "$SDK/include/variant/"
cp -r "${NATIVE}/lib/gcc/avr/${GCC_VERSION}/include/."       "$SDK/include/gcc/"
cp -r "${NATIVE}/lib/gcc/avr/${GCC_VERSION}/include-fixed/." "$SDK/include/gcc-fixed/" 2>/dev/null || true
cp -r "${NATIVE}/avr/include/."      "$SDK/include/avr/"
cp "${NATIVE}/avr/lib/avr5/crtatmega328p.o" \
   "${NATIVE}/avr/lib/avr5/libc.a" \
   "${NATIVE}/avr/lib/avr5/libm.a" \
   "${NATIVE}/avr/lib/avr5/libatmega328p.a" "$SDK/lib/avr5/"
cp "${NATIVE}/lib/gcc/avr/${GCC_VERSION}/avr5/libgcc.a" "$SDK/lib/gcc-avr5/"

# GNU ld finds its linker scripts relative to where its binary was installed.
# On the phone it lives in nativeLibraryDir as libavr_ld.so, so that lookup
# finds nothing and it fails with
#   "cannot open linker script file ldscripts/avr5.xn"
# Ship the scripts and let ArduinoCompiler pass the right one with -T.
mkdir -p "$SDK/lib/ldscripts"
cp "${NATIVE}"/avr/lib/ldscripts/avr5.* "$SDK/lib/ldscripts/"

# Trim the ~500 per-device iom*.h headers down to what an ATmega328P pulls in.
# Saves about 18 MB of on-device storage. Add back whatever your other boards need.
find "$SDK/include/avr/avr" -maxdepth 1 -name 'io*.h' \
     ! -name 'io.h' ! -name 'iom328p.h' ! -name 'iom328pb.h' ! -name 'iomx8.h' \
     ! -name 'iocanxx.h' -delete

say "precompiling the Arduino core (core.a)"
CORE_BUILD="${WORK}/core"; rm -rf "$CORE_BUILD"; mkdir -p "$CORE_BUILD"
DEFS="-DF_CPU=16000000L -DARDUINO=10806 -DARDUINO_AVR_UNO -DARDUINO_ARCH_AVR"
INC="-I$ARDUINO/cores/arduino -I$ARDUINO/variants/standard"
COMMON="-c -g -Os -w -ffunction-sections -fdata-sections -mmcu=atmega328p $DEFS $INC"
for f in "$ARDUINO"/cores/arduino/*.c;   do "${NATIVE}/bin/avr-gcc" $COMMON -std=gnu11 "$f" -o "$CORE_BUILD/$(basename "$f").o"; done
for f in "$ARDUINO"/cores/arduino/*.cpp; do "${NATIVE}/bin/avr-g++" $COMMON -std=gnu++11 -fpermissive -fno-exceptions -fno-threadsafe-statics "$f" -o "$CORE_BUILD/$(basename "$f").o"; done
for f in "$ARDUINO"/cores/arduino/*.S;   do "${NATIVE}/bin/avr-gcc" -c -g -x assembler-with-cpp -mmcu=atmega328p $DEFS $INC "$f" -o "$CORE_BUILD/$(basename "$f").o"; done
"${NATIVE}/bin/avr-ar" rcs "$SDK/lib/core.a" "$CORE_BUILD"/*.o

say "building the reference blink.hex"
cat > "${WORK}/blink.cpp" <<'SKETCH'
#include <Arduino.h>
#define LED_PIN LED_BUILTIN
void setup() { pinMode(LED_PIN, OUTPUT); }
void loop() { digitalWrite(LED_PIN, HIGH); delay(500); digitalWrite(LED_PIN, LOW); delay(500); }
SKETCH
"${NATIVE}/bin/avr-g++" $COMMON -std=gnu++11 -fpermissive -fno-exceptions -fno-threadsafe-statics \
    "${WORK}/blink.cpp" -o "${WORK}/blink.o"
"${NATIVE}/bin/avr-gcc" -w -Os -Wl,--gc-sections -mmcu=atmega328p -o "${WORK}/blink.elf" \
    "${WORK}/blink.o" "$SDK/lib/core.a" -lm
"${NATIVE}/bin/avr-objcopy" -O ihex -R .eeprom "${WORK}/blink.elf" "$SDK/hex/blink.hex"

# The trailing revision is the SDK LAYOUT version - bump it whenever the
# contents change without a toolchain version change, so the app knows to
# re-extract. r2 added lib/ldscripts/.
echo "${GCC_VERSION}-${BINUTILS_VERSION}-${AVRLIBC_VERSION}-r2" > "$SDK/VERSION"

rm -f "${OUT}/avr-sdk.zip"
(cd "$SDK" && zip -qr "${OUT}/avr-sdk.zip" .)

say "done"
echo
echo "  cp -r ${OUT}/jniLibs/arm64-v8a  app/src/main/jniLibs/"
echo "  cp    ${OUT}/avr-sdk.zip        app/src/main/assets/"
echo
echo "Bump AvrSdk's cached VERSION by rebuilding the app; it re-extracts"
echo "automatically when the string inside the zip changes."
ls -la "$OUT" "$JNI"
```
