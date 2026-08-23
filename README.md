# Arduino Experiment for Android

A complete C++ toolchain that lives inside an Android phone. Type an Arduino sketch on your
phone, hit Compile, and a real `avr-gcc` pipeline builds it and flashes it onto an Arduino Uno
over USB. No laptop, no cloud build, no PC anywhere in the loop.

**Project page → https://robinkumar5986.github.io/Arduino-Exp-Android/**

---

## What it does

The phone runs the actual GNU toolchain — `cc1plus`, `as`, `ld`, and `objcopy`, cross-compiled
as aarch64 Android binaries — and talks the STK500v1 protocol to the board's bootloader over
USB OTG. It is the real thing, not a remote compiler with a mobile front end.

| | |
|---|---|
| Blink sketch | **1,048 bytes** of flash, compiled in **189 ms** |
| Desktop reference | 1,058 bytes (GCC 7.3, same source) |
| Board tested | Arduino Uno R3 (VID 2341 / PID 0043) |
| Install size | ~35–45 MB |

## How a build runs

```
sketch.ino ──▶ sketch.cpp ──▶ sketch.s ──▶ sketch.o ──▶ sketch.elf ──▶ sketch.hex ──▶ chip
           preprocess     cc1plus       as          ld            objcopy        STK500v1
```

Tapping **Compile** walks this chain of files:

```
EditorActivity          you tap Compile
  └─ ArduinoCompiler    orchestrates 5 stages, in order
       ├─ SketchPreprocessor    .ino → .cpp, adds forward declarations + #line
       ├─ ProcessRunner         runs each native binary, 4×
       ├─ AvrSdk / Toolchain    locate headers, core.a, and the 4 executables
       └─ IntelHexSize          counts flash bytes for the size report
```

Tapping **Upload** continues into:

```
Uploader                find the device, get permission, open the port
  ├─ IntelHex           hex text → raw flash bytes
  ├─ UsbSerialLink      the only class that touches USB
  └─ Stk500Programmer   reset → sync → program 128-byte pages → done
```

## The two decisions that make it fast

**The Arduino core ships precompiled.** About 40 core source files are built once on a desktop
into `core.a` and bundled in `avr-sdk.zip`. On-device, the phone only compiles the one file you
actually typed, then links against that archive. That is why a build takes 189 ms instead of
minutes.

**The `avr-gcc` driver never runs.** Normally `avr-g++` quietly delegates to `cc1plus`, `as`,
and `ld` by their real names — but Android forces the binaries to be renamed, so that lookup
would fail. Each stage is invoked directly instead, with flag lists lifted from `avr-g++ -v`
and verified to produce a byte-identical hex.

## Three Android constraints worth knowing

These are OS limits, not design choices, and every one had to be solved before any of the above
could run.

1. **Execution only from `nativeLibraryDir`.** Since Android 10, `execve()` only works inside
   the app's own native-library directory, and the installer only puts files there if they are
   named `lib*.so`. So the whole toolchain ships renamed — ordinary executables wearing a
   shared-library costume.
2. **Binaries must be PIE.** Android has refused non-PIE executables since 5.0, so `-fPIE`
   and `-pie` are forced globally through the cross-build.
3. **A renamed linker forgets where it lives.** GNU `ld` finds its linker scripts relative to
   its own install path, which is meaningless as `libavr_ld.so`. The scripts ship inside the
   SDK zip and one is named explicitly with `-T` on every link.

## Project layout

```
ide/                    compiling the sketch
  EditorActivity        the screen: editor, buttons, build log
  ArduinoCompiler       runs the 5 build stages in order
  SketchPreprocessor    the only file that parses your C++
  AvrSdk                unzips and locates the SDK data files
  Toolchain             locates the 4 executables
  Board                 one chip's profile — the only Uno-specific file
  ProcessRunner         spawns one process, drains its output
  BuildResult           pass/fail outcome
  IntelHexSize          counts flash bytes

flash/                  getting it onto the chip
  IntelHex              Intel HEX parser, no Android APIs — host-testable
  SerialLink            the narrow serial contract
  UsbSerialLink         the real USB transport
  Stk500Programmer      STK500v1, what avrdude speaks
  Uploader              find device, request permission, flash
```

## Adding another board

`Board.java` is the only file that knows about the Uno specifically. Everything else reads
whichever `Board` instance it is handed.

Same chip (a classic Nano) is one new `Board` constant with a different upload baud — 57600
instead of 115200. A different MCU also needs its own variant headers, crt object, and a
`core.a` rebuilt with the right `F_CPU`, all added into `avr-sdk.zip`. The compiler binaries
themselves never change.

## Toolchain build

| Component | Version |
|---|---|
| Android NDK | r27c · API 24 |
| binutils | 2.42 |
| GCC | 14.2.0 |
| avr-libc | 2.2.1 |
| GMP / MPFR / MPC | 6.3.0 / 4.2.1 / 1.3.1 |
| Arduino AVR core | 1.8.6 |

Built on Ubuntu, running on a phone.

## Requirements

An Android phone with USB OTG support, an Arduino Uno R3, and a USB OTG adapter. The app asks
for USB permission the first time you upload.

## Limitations

Only the ATmega328P is wired up today. Libraries that are not compiled into `core.a` — SPI,
Wire, Servo — will fail at link time with an undefined reference; the build log points this out
when it happens. The preprocessor deliberately skips templates and multi-line function
signatures, matching the real Arduino IDE's own documented limitation.

---

Built by [@robinkumar5986](https://github.com/robinkumar5986) · `com.kgjr.aurdinoexperiment`
