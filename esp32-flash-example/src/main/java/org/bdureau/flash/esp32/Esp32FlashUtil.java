package org.bdureau.flash.esp32;


import static org.bdureau.flash.esp32.ESPLoader.ESP32;
import static org.bdureau.flash.esp32.ESPLoader.ESP32C2;
import static org.bdureau.flash.esp32.ESPLoader.ESP32C3;
import static org.bdureau.flash.esp32.ESPLoader.ESP32C6;
import static org.bdureau.flash.esp32.ESPLoader.ESP32H2;
import static org.bdureau.flash.esp32.ESPLoader.ESP32S2;
import static org.bdureau.flash.esp32.ESPLoader.ESP32S3;
import static org.bdureau.flash.esp32.ESPLoader.ESP8266;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

import com.fazecast.jSerialComm.SerialPort;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class Esp32FlashUtil {

    static SerialPort comPort;
    private static final boolean DEBUG = false;
    private static final int ESP_ROM_BAUD = 115200;

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
            int chip = espLoader.detectChip();
            if (chip == ESP32) {
                System.out.println("chip is ESP32");
            }
            if (chip == ESP32S2) {
                System.out.println("chip is ESP32S2");
            }
            if (chip == ESP32S3) {
                System.out.println("chip is ESP32S3");
            }
            if (chip == ESP32C3) {
                System.out.println("chip is ESP32C3");
            }
            if (chip == ESP8266) {
                System.out.println("chip is ESP8266");
            }
            if (chip == ESP32C6) {
                System.out.println("chip is ESP32C6");
            }
            if (chip == ESP32H2) {
                System.out.println("chip is ESP32H2");
            }
            System.out.println(chip);

            // now that we have initialised the chip we can change the baud rate to 460800
            // first we tell the chip the new baud rate
            // not that we do not do it for ESP8266
            if (chip != ESP8266) {
                System.out.println("Changing baudrate to 460800");
                espLoader.changeBaudRate(460800);
                // second we change the com port baud rate
                comPort.setBaudRate(460800);
                // let's wait
                //delayMs(50);
            }
            espLoader.init();
            // Those are the files you want to flush
            // I am providing tests with the blink program
            // They might not work for you depending of your LED wiring
            // you will need to change the test firmware file path
            // the test firmwares are in the resources directories
            if (chip == ESP32) {
                byte[] file1 = readResource("ESP32/boot_app0.bin");
                espLoader.flashData(file1, 0xe000, 0);
                byte[] file2 = readResource("ESP32/ESP32Blink.ino.bootloader.bin");
                espLoader.flashData(file2, 0x1000, 0);
                byte[] file3 = readResource("ESP32/ESP32Blink.ino.bin");
                espLoader.flashCompressedData(file3, 0x10000, 0);
                byte[] file4 = readResource("ESP32/ESP32Blink.ino.partitions.bin");
                espLoader.flashData(file4, 0x8000, 0);
            }
            if (chip == ESP32C2) {
                // waiting for the Arduino core to be ready to test it
                byte[] file1 = readResource("ESP32C2/boot_app0.bin");
                espLoader.flashCompressedData(file1, 0xe000, 0);
                byte[] file2 = readResource("ESP32C2/ESP32C2Blink.ino.bootloader.bin");
                espLoader.flashCompressedData(file2, 0x0000, 0);
                byte[] file3 = readResource("ESP32C2/ESP32C2Blink.ino.bin");
                espLoader.flashCompressedData(file3, 0x10000, 0);
                byte[] file4 = readResource("ESP32C2/ESP32C2Blink.ino.partitions.bin");
                espLoader.flashCompressedData(file4, 0x8000, 0);
            }

            if (chip == ESP32C3) {
                byte[] file1 = readResource("ESP32C3/boot_app0.bin");
                espLoader.flashCompressedData(file1, 0xe000, 0);
                byte[] file2 = readResource("ESP32C3/ESP32C3Blink.ino.bootloader.bin");
                espLoader.flashCompressedData(file2, 0x0000, 0);
                byte[] file3 = readResource("ESP32C3/ESP32C3Blink.ino.bin");
                espLoader.flashCompressedData(file3, 0x10000, 0);
                byte[] file4 = readResource("ESP32C3/ESP32C3Blink.ino.partitions.bin");
                espLoader.flashCompressedData(file4, 0x8000, 0);
            }
            if (chip == ESP32C6) {
                byte[] file1 = readResource("ESP32C6/boot_app0.bin");
                espLoader.flashCompressedData(file1, 0xe000, 0);
                byte[] file2 = readResource("ESP32C6/ESP32C6Blink.ino.bootloader.bin");
                espLoader.flashCompressedData(file2, 0x0000, 0);
                byte[] file3 = readResource("ESP32C6/ESP32C6Blink.ino.bin");
                espLoader.flashCompressedData(file3, 0x10000, 0);
                byte[] file4 = readResource("ESP32C6/ESP32C6Blink.ino.partitions.bin");
                espLoader.flashCompressedData(file4, 0x8000, 0);
            }
            if (chip == ESP32S2) {
                byte[] file1 = readResource("ESP32S2/boot_app0.bin");
                espLoader.flashCompressedData(file1, 0xe000, 0);
                byte[] file2 = readResource("ESP32S2/ESP32S2Blink.ino.bootloader.bin");
                espLoader.flashCompressedData(file2, 0x1000, 0);
                byte[] file3 = readResource("ESP32S2/ESP32S2Blink.ino.bin");
                espLoader.flashCompressedData(file3, 0x10000, 0);
                byte[] file4 = readResource("ESP32S2/ESP32S2Blink.ino.partitions.bin");
                espLoader.flashCompressedData(file4, 0x8000, 0);
            }
            if (chip == ESP32S3) {
                byte[] file1 = readResource("ESP32S3/boot_app0.bin");
                espLoader.flashCompressedData(file1, 0xe000, 0);
                byte[] file2 = readResource("ESP32S3/ESP32S3Blink.ino.bootloader.bin");
                espLoader.flashCompressedData(file2, 0x0000, 0);
                byte[] file3 = readResource("ESP32S3/ESP32S3Blink.ino.bin");
                espLoader.flashCompressedData(file3, 0x10000, 0);
                byte[] file4 = readResource("ESP32S3/ESP32S3Blink.ino.partitions.bin");
                espLoader.flashCompressedData(file4, 0x8000, 0);
            }
            if (chip == ESP8266) {
                //Flash uncompress file
                byte[] file1 = readResource("ESP8266/ESP8266Blink.ino.bin");
                espLoader.flashData(file1, 0x0000, 0);
            }
            if (chip == ESP32H2) {
                byte[] file1 = readResource("ESP32H2/boot_app0.bin");
                espLoader.flashCompressedData(file1, 0xe000, 0);
                byte[] file2 = readResource("ESP32H2/ESP32H2Blink.ino.bootloader.bin");
                espLoader.flashCompressedData(file2, 0x0000, 0);
                byte[] file3 = readResource("ESP32H2/ESP32H2Blink.ino.bin");
                espLoader.flashCompressedData(file3, 0x10000, 0);
                byte[] file4 = readResource("ESP32H2/ESP32H2Blink.ino.partitions.bin");
                espLoader.flashCompressedData(file4, 0x8000, 0);
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
