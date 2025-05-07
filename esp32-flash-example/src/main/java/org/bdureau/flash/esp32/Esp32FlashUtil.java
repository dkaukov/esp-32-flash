package org.bdureau.flash.esp32;

import static org.bdureau.flash.esp32.ESPLoader.ESP_ROM_BAUD;
import static org.bdureau.flash.esp32.ESPLoader.ESP_ROM_BAUD_HIGH;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

import com.fazecast.jSerialComm.SerialPort;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class Esp32FlashUtil {

    static SerialPort comPort;
    private static final boolean DEBUG = false;

    public static byte[] readResource(String resourcePath) {
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new FileNotFoundException("Resource not found: " + resourcePath);
            }
            return inputStream.readAllBytes();
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read resource: " + resourcePath, ex);
        }
    }

    /*
     * The following is a quick and dirty port to Java of the ESPTool from Espressif
     * Systems This works only for the ESP32, ESP32S3, ESP32C3 and ESP8266 but can be modified to flash the
     * firmware of other ESP chips
     * author: Boris du Reau date: 03/10/2022
     * email: boris.dureau@neuf.fr
     * github: https://github.com/bdureau
     * My main routine is just to test the ESP32 firmware flash. You will need to modify it
     * to flash your firmware The objective of the port is to give people a good
     * start. If you leave it as it is it will detect your chip and flash the blink program to your board
     *
     * This have been successfully tested with the following chips ESP32, ESP32S3, ESP32C3 and ESP8266
     * Some of the boards that I have tested are : LILY GO, WROOM modules
     * If you need some other chips or ESP32 board let me know and I will try to add them
     */
    @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
    public static void main(String[] args) {
        boolean syncSuccess = false;
        // get the first port available, you might want to change that
        comPort = SerialPort.getCommPorts()[6];
        System.out.println("Connected to: \"" + comPort.getDescriptivePortName() + "\"");
        // initialize at 115200 bauds
        comPort.setBaudRate(ESP_ROM_BAUD);
        comPort.openPort();
        comPort.flushIOBuffers();
        ESPLoader espLoader = getEspLoader();
        // let's put the chip in boot mode
        espLoader.enterBootLoader();
        if (DEBUG) {
            System.out.println("Done with bootloader");
        }
        comPort.flushIOBuffers();
        delayMs(50);
        syncSuccess = espLoader.sync();
        if (syncSuccess) {
            System.out.println("Sync Success!!!");
            delayMs(100);
            comPort.flushIOBuffers();
            // let's detect the chip
            // should work with an ESP32, ESP32S3, ESP32C3, ESP8266 because this is what the program is for
            Esp32ChipId chip = espLoader.detectChip();
            System.out.println("Chip is " + chip.getReadableName());

            // now that we have initialised the chip we can change the baud rate to 460800
            // first we tell the chip the new baud rate
            // not that we do not do it for ESP8266
            if (chip != Esp32ChipId.ESP8266) {
                System.out.println("Changing baud-rate to " + ESP_ROM_BAUD_HIGH);
                espLoader.changeBaudRate(ESP_ROM_BAUD_HIGH);
                comPort.setBaudRate(ESP_ROM_BAUD_HIGH);
            }
            espLoader.loadStub();
            espLoader.init();
            // Those are the files you want to flush
            // I am providing tests with the blink program
            // They might not work for you depending of your LED wiring
            // you will need to change the test firmware file path
            // the test firmwares are in the resources directories
            if (chip == Esp32ChipId.ESP32) {
                byte[] file2 = readResource("ESP32/ESP32Blink.ino.bootloader.bin");
                espLoader.writeFlash("boot", file2, 0x1000);
                byte[] file4 = readResource("ESP32/ESP32Blink.ino.partitions.bin");
                espLoader.writeFlash("ptable", file4, 0x8000);
                byte[] file1 = readResource("ESP32/boot_app0.bin");
                espLoader.writeFlash("bootApp", file1, 0xe000);
                byte[] file3 = readResource("ESP32/ESP32Blink.ino.bin");
                espLoader.writeFlash("fw", file3, 0x10000, true);
            }
            if (chip == Esp32ChipId.ESP32C2) {
                byte[] file1 = readResource("ESP32C2/boot_app0.bin");
                espLoader.writeFlash("bootApp", file1, 0xe000);
                byte[] file2 = readResource("ESP32C2/ESP32C2Blink.ino.bootloader.bin");
                espLoader.writeFlash("boot", file2, 0x0000);
                byte[] file3 = readResource("ESP32C2/ESP32C2Blink.ino.bin");
                espLoader.writeFlash("fw", file3, 0x10000, true);
                byte[] file4 = readResource("ESP32C2/ESP32C2Blink.ino.partitions.bin");
                espLoader.writeFlash("ptable", file4, 0x8000);
            }
            if (chip == Esp32ChipId.ESP32C3) {
                byte[] file1 = readResource("ESP32C3/boot_app0.bin");
                espLoader.writeFlash("bootApp", file1, 0xe000);
                byte[] file2 = readResource("ESP32C3/ESP32C3Blink.ino.bootloader.bin");
                espLoader.writeFlash("boot", file2, 0x0000);
                byte[] file3 = readResource("ESP32C3/ESP32C3Blink.ino.bin");
                espLoader.writeFlash("fw", file3, 0x10000, true);
                byte[] file4 = readResource("ESP32C3/ESP32C3Blink.ino.partitions.bin");
                espLoader.writeFlash("ptable", file4, 0x8000);
            }
            if (chip == Esp32ChipId.ESP32C6) {
                byte[] file1 = readResource("ESP32C6/boot_app0.bin");
                espLoader.writeFlash("bootApp", file1, 0xe000);
                byte[] file2 = readResource("ESP32C6/ESP32C6Blink.ino.bootloader.bin");
                espLoader.writeFlash("boot", file2, 0x0000);
                byte[] file3 = readResource("ESP32C6/ESP32C6Blink.ino.bin");
                espLoader.writeFlash("fw", file3, 0x10000, true);
                byte[] file4 = readResource("ESP32C6/ESP32C6Blink.ino.partitions.bin");
                espLoader.writeFlash("ptable", file4, 0x8000);
            }
            if (chip == Esp32ChipId.ESP32S2) {
                byte[] file1 = readResource("ESP32S2/boot_app0.bin");
                espLoader.writeFlash("bootApp", file1, 0xe000);
                byte[] file2 = readResource("ESP32S2/ESP32S2Blink.ino.bootloader.bin");
                espLoader.writeFlash("boot", file2, 0x1000);
                byte[] file3 = readResource("ESP32S2/ESP32S2Blink.ino.bin");
                espLoader.writeFlash("fw", file3, 0x10000, true);
                byte[] file4 = readResource("ESP32S2/ESP32S2Blink.ino.partitions.bin");
                espLoader.writeFlash("ptable", file4, 0x8000);
            }
            if (chip == Esp32ChipId.ESP32S3) {
                byte[] file1 = readResource("ESP32S3/boot_app0.bin");
                espLoader.writeFlash("bootApp", file1, 0xe000);
                byte[] file2 = readResource("ESP32S3/ESP32S3Blink.ino.bootloader.bin");
                espLoader.writeFlash("boot", file2, 0x0000);
                byte[] file3 = readResource("ESP32S3/ESP32S3Blink.ino.bin");
                espLoader.writeFlash("fw", file3, 0x10000, true);
                byte[] file4 = readResource("ESP32S3/ESP32S3Blink.ino.partitions.bin");
                espLoader.writeFlash("ptable", file4, 0x8000);
            }
            if (chip == Esp32ChipId.ESP8266) {
                //Flash uncompress file
                byte[] file1 = readResource("ESP8266/ESP8266Blink.ino.bin");
                espLoader.writeFlash("fw", file1, 0x0000, true);
            }
            if (chip == Esp32ChipId.ESP32H2) {
                byte[] file1 = readResource("ESP32H2/boot_app0.bin");
                espLoader.writeFlash("bootApp", file1, 0xe000);
                byte[] file2 = readResource("ESP32H2/ESP32H2Blink.ino.bootloader.bin");
                espLoader.writeFlash("boot", file2, 0x0000);
                byte[] file3 = readResource("ESP32H2/ESP32H2Blink.ino.bin");
                espLoader.writeFlash("fw", file3, 0x10000, true);
                byte[] file4 = readResource("ESP32H2/ESP32H2Blink.ino.partitions.bin");
                espLoader.writeFlash("ptable", file4, 0x8000);
            }
            espLoader.flash_finish(); // not sure it is usefull
            // we have finish flashing lets reset the board so that the program can start
            espLoader.reset();
            System.out.println("done ");
        }
        // closing serial port
        comPort.closePort();
    }

    private static void delayMs(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static ESPLoader getEspLoader() {
        ESPLoader res = new ESPLoader(new org.bdureau.flash.esp32.SerialPort() {
            @Override
            public void flush() {
                comPort.flushIOBuffers();
            }

            @Override
            public int read(byte[] buffer, int length) {
                return comPort.readBytes(buffer, length);
            }

            @Override
            public void write(byte[] buffer, int length) {
                comPort.writeBytes(buffer, length);
            }

            @Override
            public void setControlLines(boolean dtr, boolean rts) {
                if (dtr) {
                    comPort.setDTR();
                }
                if (rts) {
                    comPort.setRTS();
                }
                if (!dtr) {
                    comPort.clearDTR();
                }
                if (!rts) {
                    comPort.clearRTS();
                }

            }
        });
        res.setDebug(DEBUG);
        return res;
    }

}
