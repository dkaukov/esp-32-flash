package org.bdureau.flash.esp32;

import java.util.Set;

public enum Esp32ChipId {
    ESP8266(0x8266, null, "ESP8266", Set.of(0xfff0c101)),
    ESP32(0x32, "stubs/1/esp32.json", "ESP32", Set.of(0x00f01d83)),
    ESP32S2(0x3252, "stubs/1/esp32s2.json", "ESP32-S2", Set.of(0x000007c6)),
    ESP32S3(0x3253, "stubs/1/esp32s3.json", "ESP32-S3", Set.of(0x9)),
    ESP32H2(0x3282, "stubs/1/esp32h2.json", "ESP32-H2", Set.of(0xca26cc22, 0xd7b73e80)),
    ESP32C2(0x32C2, null, "ESP32-C2", Set.of(0x6f51306f, 2084675695)),
    ESP32C3(0x32C3, "stubs/1/esp32c3.json", "ESP32-C3", Set.of(0x6921506f, 0x1b31506f)),
    ESP32C6(0x32C6, "stubs/1/esp32c6.json", "ESP32-C6", Set.of(0x0da1806f, 752910447));

    private final int id;
    private final String stubName;
    private final String readableName;
    private final Set<Integer> magicValues;

    Esp32ChipId(int id, String stubName, String readableName, Set<Integer> magicValues) {
        this.id = id;
        this.stubName = stubName;
        this.readableName = readableName;
        this.magicValues = magicValues;
    }

    public int getId() {
        return id;
    }

    public String getStubName() {
        return stubName;
    }

    public String getReadableName() {
        return readableName;
    }

    public boolean hasStub() {
        return stubName != null;
    }

    public static Esp32ChipId fromId(int id) {
        for (Esp32ChipId chip : values()) {
            if (chip.id == id) {
                return chip;
            }
        }
        throw new IllegalArgumentException("Unknown ESP chip ID: 0x" + Integer.toHexString(id));
    }

    public static Esp32ChipId fromMagicValue(int magic) {
        for (Esp32ChipId chip : values()) {
            if (chip.magicValues.contains(magic)) {
                return chip;
            }
        }
        throw new IllegalArgumentException("Unknown ESP magic value: 0x" + Integer.toHexString(magic));
    }

    @Override
    public String toString() {
        return readableName + " (0x" + Integer.toHexString(id).toUpperCase() + ")";
    }
}
