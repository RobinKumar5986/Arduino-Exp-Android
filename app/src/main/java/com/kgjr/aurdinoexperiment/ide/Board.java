package com.kgjr.aurdinoexperiment.ide;

/**
 * A board profile. Values mirror boards.txt / platform.txt from the
 * Arduino AVR core, so adding another AVR board is mostly filling this in.
 */
public final class Board {

    public final String id;
    public final String displayName;

    // --- compile ---
    public final String mcu;            // -D__AVR_ATmega328P__ / device-specs name
    public final String gccArch;        // -mmcu passed to cc1plus and as (avr5, avr4, ...)
    public final long   fCpu;
    public final String variant;        // include/variant subdir in the SDK
    public final String crtObject;      // crtatmega328p.o
    public final String deviceLib;      // -latmega328p
    public final int    arduinoVersion; // -DARDUINO=
    public final String boardDefine;    // -DARDUINO_AVR_UNO
    public final String archDefine;     // -DARDUINO_ARCH_AVR
    public final String dataSection;    // -Tdata

    // --- limits, for the size report ---
    public final int flashBytes;        // usable flash (total minus bootloader)
    public final int ramBytes;

    // --- upload ---
    public final int    uploadBaud;
    public final int    flashPageBytes; // SPM page size
    public final byte[] signature;      // device signature bytes

    private Board(String id, String displayName, String mcu, String gccArch, long fCpu,
                  String variant, String crtObject, String deviceLib, int arduinoVersion,
                  String boardDefine, String archDefine, String dataSection,
                  int flashBytes, int ramBytes, int uploadBaud, int flashPageBytes,
                  byte[] signature) {
        this.id = id;
        this.displayName = displayName;
        this.mcu = mcu;
        this.gccArch = gccArch;
        this.fCpu = fCpu;
        this.variant = variant;
        this.crtObject = crtObject;
        this.deviceLib = deviceLib;
        this.arduinoVersion = arduinoVersion;
        this.boardDefine = boardDefine;
        this.archDefine = archDefine;
        this.dataSection = dataSection;
        this.flashBytes = flashBytes;
        this.ramBytes = ramBytes;
        this.uploadBaud = uploadBaud;
        this.flashPageBytes = flashPageBytes;
        this.signature = signature;
    }

    /** Arduino Uno R3 - ATmega328P @ 16 MHz with the optiboot bootloader. */
    public static final Board UNO = new Board(
            "uno", "Arduino Uno",
            "atmega328p", "avr5", 16000000L,
            "standard", "crtatmega328p.o", "atmega328p",
            10806, "ARDUINO_AVR_UNO", "ARDUINO_ARCH_AVR", "0x800100",
            32256, 2048,
            115200, 128,
            new byte[]{(byte) 0x1E, (byte) 0x95, (byte) 0x0F});

    // To add e.g. the Nano you also need its variant folder ("eightanaloginputs")
    // added to the SDK zip, and uploadBaud 57600 for the old bootloader.

    /**
     * The linker script ld would have picked for itself.
     *
     * The ".xn" variant is what the avr emulation selects for a non-demand-paged
     * link, which is every AVR link. We have to name it explicitly because ld
     * cannot find its own ldscripts directory when it runs from
     * nativeLibraryDir under a different filename.
     */
    public String linkerScript() {
        return gccArch + ".xn";
    }

    public String mcuMacro() {
        // atmega328p -> __AVR_ATmega328P__ . boards.txt spells the mcu lowercase,
        // the macro uses the datasheet's mixed case, so keep it explicit.
        if ("atmega328p".equals(mcu)) return "__AVR_ATmega328P__";
        throw new IllegalStateException("add the macro name for mcu " + mcu);
    }
}