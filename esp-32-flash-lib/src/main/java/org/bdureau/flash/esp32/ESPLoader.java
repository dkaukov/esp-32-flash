package org.bdureau.flash.esp32;
/*
 * Java ESPLoader
 *
 * author: boris.dureau@neuf.fr
 * 2022-2024
 *
 *
 *
 * This is a partial Java port of the ESPLoader.py tool
 * Currently it has been tested succesfully with the following chips:
 * ESP32, ESP32S2, ESP32S3, ESP32C3, ESP32H2 and ESP8266
 *
 * This might also work with ESP32C6
 * You might want to adjust the memory size for you chip
 * Note that I have also done an Android version
 */

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.Deflater;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;


public class ESPLoader {

    private final SerialPort comPort;

    private static final int ESP_ROM_BAUD = 115200;
    private static final int FLASH_WRITE_SIZE = 0x4000;
    public static final int MEM_WRITE_SIZE = 0x1800;
    private static final int STUBLOADER_FLASH_WRITE_SIZE = 0x4000;
    private static final int FLASH_SECTOR_SIZE = 0x1000; // Flash sector size, minimum unit of erase.
    private static final int PADDING_ALIGNMENT = 0x400;

    private static final int CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000;
    public static final int ESP8266 = 0x8266;
    public static final int ESP32 = 0x32;
    public static final int ESP32S2 = 0x3252;
    public static final int ESP32S3 = 0x3253;
    public static final int ESP32H2 = 0x3282;
    public static final int ESP32C2 = 0x32C2;
    public static final int ESP32C3 = 0x32C3;
    public static final int ESP32C6 = 0x32C6;

    // Device-specific data register values
    private static final int ESP32_DATAREGVALUE     = 0x15122500;
    private static final int ESP8266_DATAREGVALUE   = 0x00062000;
    private static final int ESP32S2_DATAREGVALUE   = 0x00000500;

    // Flash and image constants
    private static final int BOOTLOADER_FLASH_OFFSET = 0x1000;
    private static final int ESP_IMAGE_MAGIC         = 0xE9;

    // ESP8266 ROM bootloader commands
    private static final byte ESP_FLASH_BEGIN   = (byte) 0x02;
    private static final byte ESP_FLASH_DATA    = (byte) 0x03;
    private static final byte ESP_FLASH_END     = (byte) 0x04;
    private static final byte ESP_MEM_BEGIN     = (byte) 0x05;
    private static final byte ESP_MEM_END       = (byte) 0x06;
    private static final byte ESP_MEM_DATA      = (byte) 0x07;
    private static final byte ESP_SYNC          = (byte) 0x08;
    private static final byte ESP_WRITE_REG     = (byte) 0x09;
    private static final byte ESP_READ_REG      = (byte) 0x0A;

    // ESP32 ROM or ESP8266 with stub
    private static final byte ESP_SPI_SET_PARAMS     = (byte) 0x0B; // 11
    private static final byte ESP_SPI_ATTACH         = (byte) 0x0D; // 13
    private static final byte ESP_READ_FLASH_SLOW    = (byte) 0x0E; // 14 (ROM only)
    private static final byte ESP_CHANGE_BAUDRATE    = (byte) 0x0F; // 15
    private static final byte ESP_FLASH_DEFL_BEGIN   = (byte) 0x10; // 16
    private static final byte ESP_FLASH_DEFL_DATA    = (byte) 0x11; // 17
    private static final byte ESP_FLASH_DEFL_END     = (byte) 0x12; // 18
    private static final byte ESP_SPI_FLASH_MD5      = (byte) 0x13; // 19

    // ESP32-S2/S3/C3/C6 ROM bootloader only
    private static final byte ESP_GET_SECURITY_INFO  = (byte) 0x14;

    // Stub-only commands
    private static final byte ESP_ERASE_FLASH   = (byte) 0xD0;
    private static final byte ESP_ERASE_REGION  = (byte) 0xD1;
    private static final byte ESP_READ_FLASH    = (byte) 0xD2;
    private static final byte ESP_RUN_USER_CODE = (byte) 0xD3;

    // ROM response codes
    private static final byte ROM_INVALID_RECV_MSG   = (byte) 0x05;

    // Checksum initial state
    private static final int ESP_CHECKSUM_MAGIC     = 0xEF;

    // Misc hardware addresses
    private static final int UART_DATE_REG_ADDR = 0x60000078;

    // RAM block sizes
    private static final int USB_RAM_BLOCK = 0x0800;
    private static final int ESP_RAM_BLOCK = MEM_WRITE_SIZE;

    // Timeouts (in milliseconds)
    private static final int DEFAULT_TIMEOUT              = 3000;
    private static final int SHORT_CMD_TIMEOUT            = 100;
    private static final int SYNC_TIMEOUT                 = 100;
    private static final int MEM_END_ROM_TIMEOUT          = 500;

    private static final int CHIP_ERASE_TIMEOUT           = 120_000;  // Full chip erase
    private static final int MAX_TIMEOUT                  = CHIP_ERASE_TIMEOUT * 2; // Longest possible command

    private static final int ERASE_REGION_TIMEOUT_PER_MB  = 30_000;   // Per MB region erase
    private static final int MD5_TIMEOUT_PER_MB           = 8000;     // Per MB MD5 computation

    private int chip;
    private boolean isStub = false;
    private boolean debug = true;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ESPLoader(SerialPort comPort) {
        this.comPort = comPort;
    }

    private static class CmdReply {
        private final boolean success;
        private final  byte[] reply;
        public CmdReply(boolean success, byte[] reply) {
            this.success = success;
            this.reply = reply;
        }
        public boolean isSuccess() {
            return success;
        }
        public byte[] getReply() {
            return reply;
        }
    }

    public boolean isStub() {
        return isStub;
    }

    public void setStub(boolean stub) {
        isStub = stub;
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    /*
     * This will initialise the chip
     */
    public boolean sync() {
        byte[] cmdData = new byte[36];
        cmdData[0] = cmdData[1] = (byte) 0x07;
        cmdData[2] = (byte) 0x12;
        cmdData[3] = (byte) 0x20;
        java.util.Arrays.fill(cmdData, 5, 36, (byte) 0x55);
        for (int x = 0; x < 7; x++) {
            comPort.flush();
            CmdReply ret = sendCommand(ESP_SYNC, cmdData, 0, SYNC_TIMEOUT);
            if (ret.isSuccess()) {
                return true;
            } else {
                if (debug) {
                    System.err.println("sync timeout.");
                }
                delayMS(50);
            }
        }
        System.err.println("Sync failure after 7 attempts.");
        return false;
    }

    private CmdReply sendCommand(byte op, byte[] payload, int checksum, int timeout) {
        ByteBuffer buf = ByteBuffer.allocate(8 + payload.length);
        buf.order(ByteOrder.LITTLE_ENDIAN);      // ESP32 protocol uses little-endian
        buf.put((byte) 0x00);                    // Direction flag
        buf.put(op);                             // Operation
        buf.putShort((short) payload.length);    // Length (2 bytes)
        buf.putInt(checksum);                    // Checksum (4 bytes)
        buf.put(payload);                        // Payload
        byte[] encoded = slipEncode(buf.array());
        if (debug) {
            System.out.printf("****: op: 0x%02X, len: %d, payload: %s%n", op, payload.length,  printHex2(payload));
            System.out.println(">>>>: " + encoded.length + ": " + printHex(encoded));
        }
        comPort.write(encoded, encoded.length);
        return readSlipResponse(timeout);
    }

    private CmdReply readSlipResponse(int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ByteBuffer buffer = ByteBuffer.allocate(2048);
        boolean inFrame = false;
        while (System.currentTimeMillis() < deadline) {
            byte[] readBuf = new byte[1];
            int numRead = comPort.read(readBuf, 1);
            if (numRead <= 0) {
                continue;
            }
            byte b = readBuf[0];
            if (b == (byte) 0xC0) {
                if (inFrame) {
                    // End of SLIP frame
                    int length = buffer.position();
                    byte[] frame = new byte[length];
                    buffer.flip();
                    buffer.get(frame);
                    if (debug) {
                        System.out.println("<<<<: " + frame.length + ": " + printHex(frame));
                    }
                    return new CmdReply(true, slipDecode(frame));
                } else {
                    buffer.clear();
                    inFrame = true;
                }
            } else if (inFrame) {
                if (buffer.hasRemaining()) {
                    buffer.put(b);
                }
            }
        }
        return new CmdReply(false, new byte[0]);
    }

    private static void delayMS(int timeout) {
        try {
            Thread.sleep(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /*
     * This will do a SLIP encode
     */
    private byte[] slipEncode(byte[] buffer) {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(0xC0); // Start of SLIP frame
        for (byte b : buffer) {
            if (b == (byte) 0xC0) {
                encoded.write(0xDB);
                encoded.write(0xDC);
            } else if (b == (byte) 0xDB) {
                encoded.write(0xDB);
                encoded.write(0xDD);
            } else {
                encoded.write(b);
            }
        }
        encoded.write(0xC0); // End of SLIP frame
        return encoded.toByteArray();
    }

    private byte[] slipDecode(byte[] buffer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean inEscape = false;
        for (byte b : buffer) {
            if (inEscape) {
                if (b == (byte) 0xDC) {
                    out.write(0xC0);
                } else if (b == (byte) 0xDD) {
                    out.write(0xDB);
                } else {
                    // Invalid escape, write as-is or handle error
                    out.write(b);
                }
                inEscape = false;
            } else if (b == (byte) 0xDB) {
                inEscape = true;
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    /*
     * This does a reset in order to run the prog after flash
     */
    public void reset() {
        comPort.setControlLines(false, false);
        delayMS(100);
        comPort.setControlLines(false, true);
        delayMS(100);
        comPort.setControlLines(false, false);
    }

    /*
     * enter bootloader mode
     */
    public void enterBootLoader() {
        // reset bootloader
        comPort.setControlLines(true, false);
        delayMS(100);
        comPort.setControlLines(false, true);
        delayMS(100);
        comPort.setControlLines(true, false);
    }

    /*
     * @name flash_defl_block Send one compressed block of data to program into SPI
     * Flash memory
     */
    private CmdReply flash_defl_block(byte[] data, int seq, int timeout) {
        CmdReply retVal;
        byte[] pkt = _appendArray(_int_to_bytearray(data.length), _int_to_bytearray(seq));
        pkt = _appendArray(pkt, _int_to_bytearray(0));
        pkt = _appendArray(pkt, _int_to_bytearray(0)); // not sure
        pkt = _appendArray(pkt, data);
        retVal = sendCommand(ESP_FLASH_DEFL_DATA, pkt, _checksum(data), timeout);
        return retVal;
    }

    private CmdReply flash_block(byte[] data, int seq, int timeout) {
        CmdReply retVal;
        byte[] pkt = _appendArray(_int_to_bytearray(data.length), _int_to_bytearray(seq));
        pkt = _appendArray(pkt, _int_to_bytearray(0));
        pkt = _appendArray(pkt, _int_to_bytearray(0)); // not sure
        pkt = _appendArray(pkt, data);
        retVal = sendCommand(ESP_FLASH_DATA, pkt, _checksum(data), timeout);
        return retVal;
    }

    private CmdReply esp_spi_flash_md5(int offset, int size, int timeout) {
        // Prepare payload: offset (4 bytes LE), size (4 bytes LE)
        byte[] pkt = _appendArray(_int_to_bytearray(offset), _int_to_bytearray(size));
        pkt = _appendArray(pkt, _int_to_bytearray(0));
        pkt = _appendArray(pkt, _int_to_bytearray(0));
        // No checksum needed for MD5 — just send the command with payload
        CmdReply retVal = sendCommand(ESP_SPI_FLASH_MD5, pkt, 0, timeout);
        // Expect retVal.data to contain 16-byte MD5 digest if successful
        if (!retVal.isSuccess()) {
            System.err.println("Invalid or missing MD5 response.");
            return null;
        }
        return retVal;
    }

    public void init() {
        int flashSize = 4 * 1024 * 1024;
        if (!isStub) {
            System.out.println("No stub...");
            // byte pkt[] = _int_to_bytearray(0); // 4 or 8 zero's? not sure
            byte[] pkt = _appendArray(_int_to_bytearray(0), _int_to_bytearray(0));
            System.out.println("Enabling default SPI flash mode...");
            CmdReply res = sendCommand(ESP_SPI_ATTACH, pkt, 0, SHORT_CMD_TIMEOUT);
            if (!res.isSuccess()) {
                System.err.println("Failed to execute ESP_SPI_ATTACH");
            }
        }
        // We are hardcoding 4MB flash for an ESP32
        System.out.println("Configuring flash size...");
        byte[] pkt2 = _appendArray(_int_to_bytearray(0), _int_to_bytearray(flashSize));
        pkt2 = _appendArray(pkt2, _int_to_bytearray(/* 0x10000 */ 64 * 1024));
        pkt2 = _appendArray(pkt2, _int_to_bytearray(/* 4096 */ 4 * 1024));
        pkt2 = _appendArray(pkt2, _int_to_bytearray(256));
        pkt2 = _appendArray(pkt2, _int_to_bytearray(0xFFFF));
        CmdReply res = sendCommand(ESP_SPI_SET_PARAMS, pkt2, 0, SHORT_CMD_TIMEOUT);
        if (!res.isSuccess()) {
            System.err.println("Failed to execute ESP_SPI_SET_PARAMS");
        }
    }

    /*
     * @name flashData Program a full, uncompressed binary file into SPI Flash at a
     * given offset. If an ESP32 and md5 string is passed in, will also verify
     * memory. ESP8266 does not have checksum memory verification in ROM
     */
    public void flashCompressedData(byte[] binaryData, int offset, int part) {
        System.out.println("\nWriting data with fileSize: " + binaryData.length);
        int size = binaryData.length;
        byte[] image = compressBytes(binaryData);
        int blocks = flash_defl_begin(size, image.length, offset);
        int seq = 0;
        int position = 0;
        long t1 = System.currentTimeMillis();
        while (position < image.length) {
            double percentage = Math.floor((double) (100 * (seq + 1)) / blocks);
            System.out.println("percentage: " + percentage);
            int chunkSize = Math.min(FLASH_WRITE_SIZE, image.length - position);
            byte[] block = _subArray(image, position, chunkSize);
            int ERASE_WRITE_TIMEOUT_PER_MB = 40;
            int block_timeout = timeout_per_mb(ERASE_WRITE_TIMEOUT_PER_MB, chunkSize);
            CmdReply retVal = flash_defl_block(block, seq, block_timeout);
            if (!retVal.isSuccess()) {
                System.out.println("Retry because of timeout.");
                flash_defl_block(block, seq, block_timeout);
            }
            seq++;
            position += chunkSize;
        }
        checkMd5(binaryData, offset);
        long t2 = System.currentTimeMillis();
        System.out.printf("Wrote %d bytes (%d compressed) at 0x%08X in %.2f seconds (effective %.2f kbit/s)...%n", size, image.length, offset, (t2 - t1) / 1000.0, (size / 1024 * 8) / ((t2 - t1) / 1000.0));
    }

    private void checkMd5(byte[] data, int offset) {
        // Check MD5 checksum
        CmdReply res = esp_spi_flash_md5(offset, data.length, DEFAULT_TIMEOUT);
        if (res == null || !res.isSuccess() || res.getReply().length < 16) {
            //System.err.println("MD5 checksum failed.");
            return;
        }
        // Compare with expected MD5 checksum
        if (!Arrays.equals(_subArray(res.getReply(), 8, 16), md5(data))) {
            System.err.println("MD5 checksum mismatch.");
        } else {
            System.out.println("Hash of data verified.");
        }
    }


    public void flashData(byte[] binaryData, int offset, int part) {
        int size = binaryData.length;
        System.out.println("\nWriting data with filesize: " + size);
        int blocks = flash_begin(size, offset);
        int seq = 0;
        int position = 0;
        long t1 = System.currentTimeMillis();
        while (binaryData.length - position > 0) {
            double percentage = Math.floor((double) (100 * (seq + 1)) / blocks);
            System.out.println("percentage: " + percentage);
            byte[] block;
            if (binaryData.length - position >= FLASH_WRITE_SIZE) {
                block = _subArray(binaryData, position, FLASH_WRITE_SIZE);
            } else {
                // Pad the last block to FLASH_WRITE_SIZE with 0xFF
                int remaining = binaryData.length - position;
                block = new byte[FLASH_WRITE_SIZE];
                System.arraycopy(binaryData, position, block, 0, remaining);
            }
            int ERASE_WRITE_TIMEOUT_PER_MB = 40;
            int block_timeout = timeout_per_mb(ERASE_WRITE_TIMEOUT_PER_MB, FLASH_WRITE_SIZE);
            CmdReply retVal;
            // not using the block timeout yet need to modify the senCommand to have a
            // proper timeout
            retVal = flash_block(block, seq, block_timeout);
            if (!retVal.isSuccess()) {
                System.out.println("Retry because of timeout.");
                flash_block(block, seq, block_timeout);
            }
            seq += 1;
            position += FLASH_WRITE_SIZE;
        }
        checkMd5(binaryData, offset);
        long t2 = System.currentTimeMillis();
        System.out.printf("Wrote %d bytes at 0x%08X in %.2f seconds (effective %.2f kbit/s)...%n", size, offset, (t2 - t1) / 1000.0, (size / 1024 * 8) / ((t2 - t1) / 1000.0));
    }

    private int flash_defl_begin(int size, int compressedSize, int offset) {
        int num_blocks = (int) Math.floor((double) (compressedSize + FLASH_WRITE_SIZE - 1) / (double) FLASH_WRITE_SIZE);
        int erase_blocks = (int) Math.floor((double) (size + FLASH_WRITE_SIZE - 1) / (double) FLASH_WRITE_SIZE);
        // Start time
        long t1 = System.currentTimeMillis();
        int write_size, timeout;
        if (isStub) {
            // using a stub (will use it in the future)
            write_size = size;
            timeout = DEFAULT_TIMEOUT;
        } else {
            // no stub
            write_size = erase_blocks * FLASH_WRITE_SIZE;
            timeout = timeout_per_mb(ERASE_REGION_TIMEOUT_PER_MB, write_size);
        }
        System.out.println("Compressed " + size + " bytes to " + compressedSize + "...");
        byte[] pkt = _appendArray(_int_to_bytearray(write_size), _int_to_bytearray(num_blocks));
        pkt = _appendArray(pkt, _int_to_bytearray(FLASH_WRITE_SIZE));
        pkt = _appendArray(pkt, _int_to_bytearray(offset));
        // ESP32S3, ESP32C3, ESP32S2, ESP32C6,ESP32H2
        if (chip == ESP32S3 || chip == ESP32C2 || chip == ESP32C3 || chip == ESP32C6 || chip == ESP32S2 || chip == ESP32H2) {
            pkt = _appendArray(pkt, _int_to_bytearray(0));
        }
        sendCommand(ESP_FLASH_DEFL_BEGIN, pkt, 0, timeout);
        // end time
        long t2 = System.currentTimeMillis();
        if (size != 0 && !isStub) {
            System.out.println("Took " + ((t2 - t1) / 1000) + "." + ((t2 - t1) % 1000) + "s to erase flash block");
        }
        return num_blocks;
    }

    private int flash_begin(int size, int offset) {
        int num_blocks = (int) Math.floor((double) (size + FLASH_WRITE_SIZE - 1) / (double) FLASH_WRITE_SIZE);
        int erase_blocks = (int) Math.floor((double) (size + FLASH_WRITE_SIZE - 1) / (double) FLASH_WRITE_SIZE);
        // Start time
        long t1 = System.currentTimeMillis();
        int write_size, timeout;
        if (isStub) {
            // using a stub (will use it in the future)
            write_size = size;
            timeout = DEFAULT_TIMEOUT;
        } else {
            // no stub
            write_size = erase_blocks * FLASH_WRITE_SIZE;
            timeout = timeout_per_mb(ERASE_REGION_TIMEOUT_PER_MB, write_size);
        }
        //System.out.println("Compressed " + size + " bytes to " + compsize + "...");
        byte[] pkt = _appendArray(_int_to_bytearray(write_size), _int_to_bytearray(num_blocks));
        pkt = _appendArray(pkt, _int_to_bytearray(FLASH_WRITE_SIZE));
        pkt = _appendArray(pkt, _int_to_bytearray(offset));
        // ESP32S3, ESP32C3, ESP32S2, ESP32C6,ESP32H2
        if (chip == ESP32S3 || chip == ESP32C2 || chip == ESP32C3 || chip == ESP32C6 || chip == ESP32S2 || chip == ESP32H2) {
            pkt = _appendArray(pkt, _int_to_bytearray(0));
        }
        sendCommand(ESP_FLASH_BEGIN, pkt, 0, timeout);
        long t2 = System.currentTimeMillis();
        if (size != 0 && !isStub) {
            System.out.println("Took " + ((t2 - t1) / 1000) + "." + ((t2 - t1) % 1000) + "s to erase flash block");
        }
        return num_blocks;
    }

    public boolean loadStub(byte[] text, int textAdr, byte[] data, int dataAdr, int entryPoint) {
        // 1. Load .TEXT
        System.out.println("Loading .text");
        if (!loadToRam(text, textAdr, MEM_WRITE_SIZE)) {
            System.err.println("Failed to load TEXT.");
            return false;
        }
        // 1. Load .DATA
        System.out.println("Loading .data");
        if (!loadToRam(data, dataAdr, MEM_WRITE_SIZE)) {
            System.err.println("Failed to load DATA.");
            return false;
        }
        System.out.println("Running stub...");
        // 3. End memory transfer
        byte[] endPayload = _appendArray(_int_to_bytearray(0), _int_to_bytearray(entryPoint));
        CmdReply endReply = sendCommand(ESP_MEM_END, endPayload, 0, DEFAULT_TIMEOUT);
        if (!endReply.isSuccess()) {
            System.err.println("Failed to complete memory transfer.");
            return false;
        }
        System.out.println("Stub loaded successfully.");
        return true;
    }

    private boolean loadToRam(byte[] data, int addr, int blockSize) {
        int numBlocks = (data.length + blockSize - 1) / blockSize;
        // 1. Begin memory transfer
        byte[] pkt = _appendArray(_int_to_bytearray(data.length), _int_to_bytearray(numBlocks));
        pkt = _appendArray(pkt, _int_to_bytearray(blockSize));
        pkt = _appendArray(pkt, _int_to_bytearray(addr));
        CmdReply beginReply = sendCommand(ESP_MEM_BEGIN, pkt, 0, DEFAULT_TIMEOUT);
        if (!beginReply.isSuccess()) {
            System.err.println("Failed to start memory transfer.");
            return false;
        }
        // 2. Send each block
        for (int seq = 0; seq < numBlocks; seq++) {
            int offset = seq * blockSize;
            int actualBlockSize = Math.min(blockSize, data.length - offset);
            byte[] block = Arrays.copyOfRange(data, offset, offset + actualBlockSize);
            byte[] dataPayload = _appendArray(_int_to_bytearray(actualBlockSize), _int_to_bytearray(seq));
            dataPayload = _appendArray(dataPayload, _int_to_bytearray(0));
            dataPayload = _appendArray(dataPayload, _int_to_bytearray(0));
            dataPayload = _appendArray(dataPayload,  block);
            CmdReply dataReply = sendCommand(ESP_MEM_DATA, dataPayload, _checksum(block), DEFAULT_TIMEOUT);
            if (!dataReply.isSuccess()) {
                System.err.printf("Failed to write memory block %d/%d%n", seq + 1, numBlocks);
                return false;
            }
        }
        return true;
    }

    private static byte[] readResource(String resourcePath) {
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new FileNotFoundException("Resource not found: " + resourcePath);
            }
            return inputStream.readAllBytes();
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read resource: " + resourcePath, ex);
        }
    }

    public boolean loadStub() {
        JsonObject json = Json.parse(new String(readResource("stubs/1/esp32.json"))).asObject();
        int entry = json.getInt("entry", 0);
        int text_start = json.getInt("text_start", 0);
        int data_start = json.getInt("data_start", 0);
        byte[] text = Base64.getDecoder().decode(json.getString("text", ""));
        byte[] data = Base64.getDecoder().decode(json.getString("data", ""));
        isStub = loadStub(text, text_start, data, data_start, entry);
        return isStub;
    }

    /*
     * Send a command to the chip to find out what type it is
     */
    public int detectChip() {
        int chipMagicValue = readRegister(CHIP_DETECT_MAGIC_REG_ADDR);
        if (chipMagicValue == 0xfff0c101) {
            chip = ESP8266;
        }
        if (chipMagicValue == 0x00f01d83) {
            chip = ESP32;
        }
        if (chipMagicValue == 0x000007c6) {
            chip = ESP32S2;
        }
        if (chipMagicValue == 0x9) {
            chip = ESP32S3;
        }
        if ((chipMagicValue == 0x6f51306f) || chipMagicValue == 2084675695) {
            chip = ESP32C2;
        }
        if (chipMagicValue == 0x6921506f || chipMagicValue == 0x1b31506f) {
            chip = ESP32C3;
        }
        if (chipMagicValue == 0x0da1806f || chipMagicValue == 752910447) {
            chip = ESP32C6;
        }
        if (chipMagicValue == 0xca26cc22 || chipMagicValue == 0xd7b73e80/*-675856768*/) {
            chip = ESP32H2;
        }
        if (debug) {
            System.out.println("chipMagicValue" + chipMagicValue);
        }
        return chip;
    }

    ////////////////////////////////////////////////
    // Some utility functions
    /// /////////////////////////////////////////////

    /*
     * Just usefull for debuging to check what I am sending or receiving
     */
    private String printHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < bytes.length; i++) {
            sb.append(String.format("0x%02x", bytes[i]));
            if (i < bytes.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private String printHex2(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (byte aByte : bytes) {
            sb.append(String.format("%02x", aByte));
        }
        sb.append("]");
        return sb.toString();
    }

    /*
     * This takes 2 arrays as params and return a concatenate array
     */
    private byte[] _appendArray(byte[] arr1, byte[] arr2) {
        byte[] c = new byte[arr1.length + arr2.length];
        System.arraycopy(arr1, 0, c, 0, arr1.length);
        System.arraycopy(arr2, 0, c, arr1.length, arr2.length);
        return c;
    }

    /*
     * get part of an array
     */
    private byte[] _subArray(byte[] arr1, int pos, int length) {
        byte[] c = new byte[length];
        System.arraycopy(arr1, pos, c, 0, length);
        return c;
    }

    /*
     * Calculate the checksum. Still need to make sure that it works
     */
    private int _checksum(byte[] data) {
        int chk = ESP_CHECKSUM_MAGIC;
        for (byte b : data) {
            chk ^= (b & 0xFF);
        }
        return chk;
    }

    private int readRegister(int reg) {
        CmdReply ret = sendCommand(ESP_READ_REG, _int_to_bytearray(reg), 0, SHORT_CMD_TIMEOUT);
        byte[] reply = ret.getReply();
        return ((reply[7] & 0xFF) << 24) | ((reply[6] & 0xFF) << 16) | ((reply[5] & 0xFF) << 8) | (reply[4] & 0xFF);
    }

    private int timeout_per_mb(int seconds_per_mb, int size_bytes) {
        int result = (int) (seconds_per_mb * ((double) size_bytes / (double) 1000000));
        return Math.max(result, DEFAULT_TIMEOUT);
    }

    private byte[] _int_to_bytearray(int i) {
        return new byte[]{(byte) (i & 0xff), (byte) ((i >> 8) & 0xff), (byte) ((i >> 16) & 0xff), (byte) ((i >> 24) & 0xff)};
    }

    /**
     * Compress a byte array using ZLIB compression
     *
     * @param uncompressedData byte array of uncompressed data
     * @return byte array of compressed data
     */
    public byte[] compressBytes(byte[] uncompressedData) {
        // Create the compressor with the highest level of compression
        Deflater compressor = new Deflater();
        compressor.setLevel(Deflater.BEST_COMPRESSION);
        // compressor.setLevel(Deflater.SYNC_FLUSH);
        // Give the compressor the data to compress
        compressor.setInput(uncompressedData);
        compressor.finish();
        // Create an expandable byte array to hold the compressed data.
        // You cannot use an array that's the same size as the orginal because
        // there is no guarantee that the compressed data will be smaller than
        // the uncompressed data.
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(uncompressedData.length)) {
            // Compress the data
            byte[] buf = new byte[1024];
            while (!compressor.finished()) {
                int count = compressor.deflate(buf);
                bos.write(buf, 0, count);
            }
            // Get the compressed data
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void flash_finish() {
        byte[] temp = new byte[2];
        temp[0] = (byte) (0x3C);
        temp[1] = (byte) (0x49);
        byte[] pkt = _appendArray(temp, _int_to_bytearray(1));
        sendCommand(ESP_FLASH_END, pkt, 0, SHORT_CMD_TIMEOUT);
        delayMS(200);
    }

    public void changeBaudRate(int baudRate) {
        byte[] pkt = _appendArray(_int_to_bytearray(baudRate), _int_to_bytearray(0));
        sendCommand(ESP_CHANGE_BAUDRATE, pkt, 0, SHORT_CMD_TIMEOUT);
    }

    public byte[] md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }
}
