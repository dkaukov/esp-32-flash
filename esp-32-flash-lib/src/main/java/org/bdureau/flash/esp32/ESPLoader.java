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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;


public class ESPLoader {

    private final SerialPort comPort;

    private static final int ESP_ROM_BAUD = 115200;
    private static final int FLASH_WRITE_SIZE = 0x400;
    private static final int STUBLOADER_FLASH_WRITE_SIZE = 0x4000;
    private static final int FLASH_SECTOR_SIZE = 0x1000; // Flash sector size, minimum unit of erase.

    private static final int CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000;
    public static final int ESP8266 = 0x8266;
    public static final int ESP32 = 0x32;
    public static final int ESP32S2 = 0x3252;
    public static final int ESP32S3 = 0x3253;
    public static final int ESP32H2 = 0x3282;
    public static final int ESP32C2 = 0x32C2;
    public static final int ESP32C3 = 0x32C3;
    public static final int ESP32C6 = 0x32C6;
    private static final int ESP32_DATAREGVALUE = 0x15122500;
    private static final int ESP8266_DATAREGVALUE = 0x00062000;
    private static final int ESP32S2_DATAREGVALUE = 0x500;

    private static final int BOOTLOADER_FLASH_OFFSET = 0x1000;
    private static final int ESP_IMAGE_MAGIC = 0xe9;

    // Commands supported by ESP8266 ROM bootloader
    private static final byte ESP_FLASH_BEGIN = 0x02;
    private static final byte ESP_FLASH_DATA = 0x03;
    private static final byte ESP_FLASH_END = 0x04;
    private static final byte ESP_MEM_BEGIN = 0x05;
    private static final byte ESP_MEM_END = 0x06;
    private static final int ESP_MEM_DATA = 0x07;
    private static final byte ESP_SYNC = 0x08;
    private static final int ESP_WRITE_REG = 0x09;
    private static final byte ESP_READ_REG = 0x0A;

    // Some commands supported by ESP32 ROM bootloader (or -8266 w/ stub)
    private static final byte ESP_SPI_SET_PARAMS = 0x0B; // 11
    private static final byte ESP_SPI_ATTACH = 0x0D; // 13
    private static final byte ESP_READ_FLASH_SLOW = 0x0E; // 14 // ROM only, much slower than the stub flash read
    private static final byte ESP_CHANGE_BAUDRATE = 0x0F; // 15
    private static final byte ESP_FLASH_DEFL_BEGIN = 0x10; // 16
    private static final byte ESP_FLASH_DEFL_DATA = 0x11; // 17
    private static final byte ESP_FLASH_DEFL_END = 0x12; // 18
    private static final byte ESP_SPI_FLASH_MD5 = 0x13; // 19

    // Commands supported by ESP32-S2/S3/C3/C6 ROM bootloader only
    private static final byte ESP_GET_SECURITY_INFO = 0x14;

    // Some commands supported by stub only
    private static final int ESP_ERASE_FLASH = 0xD0;
    private static final int ESP_ERASE_REGION = 0xD1;
    private static final int ESP_READ_FLASH = 0xD2;
    private static final int ESP_RUN_USER_CODE = 0xD3;

    // Response code(s) sent by ROM
    private static final int ROM_INVALID_RECV_MSG = 0x05;

    // Initial state for the checksum routine
    private static final byte ESP_CHECKSUM_MAGIC = (byte) 0xEF;

    private static final int UART_DATE_REG_ADDR = 0x60000078;

    private static final int USB_RAM_BLOCK = 0x800;
    private static final int ESP_RAM_BLOCK = 0x1800;

    // Timeouts
    private static final int DEFAULT_TIMEOUT = 3000;
    private static final int CHIP_ERASE_TIMEOUT = 120000; // timeout for full chip erase in ms
    private static final int MAX_TIMEOUT = CHIP_ERASE_TIMEOUT * 2; // longest any command can run in ms
    private static final int SYNC_TIMEOUT = 100; // timeout for syncing with bootloader in ms
    private static final int ERASE_REGION_TIMEOUT_PER_MB = 30000; // timeout (per megabyte) for erasing a region in ms
    private static final int MEM_END_ROM_TIMEOUT = 500;
    private static final int MD5_TIMEOUT_PER_MB = 8000;
    private static int chip;

    private boolean isStub = false;
    private boolean debug = true;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ESPLoader(SerialPort comPort) {
        this.comPort = comPort;
    }

    private static class CmdRet {
        int retCode;
        byte[] retValue = new byte[2048];
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
    public int sync() {
        int x;
        int response = 0;
        byte[] cmdData = new byte[36];
        cmdData[0] = (byte) (0x07);
        cmdData[1] = (byte) (0x07);
        cmdData[2] = (byte) (0x12);
        cmdData[3] = (byte) (0x20);
        for (x = 4; x < 36; x++) {
            cmdData[x] = (byte) (0x55);
        }
        for (x = 0; x < 7; x++) {
            comPort.flush();
            CmdRet ret = sendCommand(ESP_SYNC, cmdData, 0, 100);
            if (ret.retCode == 1) {
                response = 1;
                break;
            } else {
                System.out.println("sync ret:" + ret.retCode);
            }
        }
        return response;
    }

    private CmdRet sendCommand(byte op, byte[] buffer, int chk, int timeout) {
        byte[] data = new byte[8 + buffer.length];
        data[0] = 0x00;
        data[1] = op;
        data[2] = (byte) ((buffer.length) & 0xFF);
        data[3] = (byte) ((buffer.length >> 8) & 0xFF);
        data[4] = (byte) ((chk & 0xFF));
        data[5] = (byte) ((chk >> 8) & 0xFF);
        data[6] = (byte) ((chk >> 16) & 0xFF);
        data[7] = (byte) ((chk >> 24) & 0xFF);
        System.arraycopy(buffer, 0, data, 8, buffer.length);
        byte[] buf = slipEncode(data);
        comPort.write(buf, buf.length);
        if (debug) {
            System.out.println(printHex(buffer));
        }
        return readSlipResponse(timeout);
    }

    private CmdRet readSlipResponse(int timeoutMs) {
        CmdRet result = new CmdRet();
        result.retCode = -1;
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
                    result.retValue = slipDecode(frame);
                    result.retCode = 1;
                    return result;
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
        return result;
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
    private CmdRet flash_defl_block(byte[] data, int seq, int timeout) {
        CmdRet retVal;
        byte[] pkt = _appendArray(_int_to_bytearray(data.length), _int_to_bytearray(seq));
        pkt = _appendArray(pkt, _int_to_bytearray(0));
        pkt = _appendArray(pkt, _int_to_bytearray(0)); // not sure
        pkt = _appendArray(pkt, data);
        retVal = sendCommand(ESP_FLASH_DEFL_DATA, pkt, _checksum(data), timeout);
        return retVal;
    }

    private CmdRet flash_block(byte[] data, int seq, int timeout) {
        CmdRet retVal;
        byte[] pkt = _appendArray(_int_to_bytearray(data.length), _int_to_bytearray(seq));
        pkt = _appendArray(pkt, _int_to_bytearray(0));
        pkt = _appendArray(pkt, _int_to_bytearray(0)); // not sure
        pkt = _appendArray(pkt, data);
        retVal = sendCommand(ESP_FLASH_DATA, pkt, _checksum(data), timeout);
        return retVal;
    }

    public void init() {
        int flashSize = 4 * 1024 * 1024;
        if (!isStub) {
            System.out.println("No stub...");
            // byte pkt[] = _int_to_bytearray(0); // 4 or 8 zero's? not sure
            byte[] pkt = _appendArray(_int_to_bytearray(0), _int_to_bytearray(0));
            System.out.println("Enabling default SPI flash mode...");
            sendCommand(ESP_SPI_ATTACH, pkt, 0, 100);
        }
        // We are hardcoding 4MB flash for an ESP32
        System.out.println("Configuring flash size...");
        byte[] pkt2 = _appendArray(_int_to_bytearray(0), _int_to_bytearray(flashSize));
        pkt2 = _appendArray(pkt2, _int_to_bytearray(/* 0x10000 */ 64 * 1024));
        pkt2 = _appendArray(pkt2, _int_to_bytearray(/* 4096 */ 4 * 1024));
        pkt2 = _appendArray(pkt2, _int_to_bytearray(256));
        pkt2 = _appendArray(pkt2, _int_to_bytearray(0xFFFF));
        sendCommand(ESP_SPI_SET_PARAMS, pkt2, 0, 100);
    }

    /*
     * @name flashData Program a full, uncompressed binary file into SPI Flash at a
     * given offset. If an ESP32 and md5 string is passed in, will also verify
     * memory. ESP8266 does not have checksum memory verification in ROM
     */
    public void flashCompressedData(byte[] binaryData, int offset, int part) {
        int size = binaryData.length;
        System.out.println("\nWriting data with fileSize: " + size);
        byte[] image = compressBytes(binaryData);
        int blocks = flash_defl_begin(size, image.length, offset);
        int seq = 0;
        int position = 0;
        long t1 = System.currentTimeMillis();
        while (image.length - position > 0) {
            double percentage = Math.floor((double) (100 * (seq + 1)) / blocks);
            System.out.println("percentage: " + percentage);
            byte[] block;
            if (image.length - position >= FLASH_WRITE_SIZE) {
                block = _subArray(image, position, FLASH_WRITE_SIZE);
            } else {
                // Pad the last block
                block = _subArray(image, position, image.length - position);
            }
            int ERASE_WRITE_TIMEOUT_PER_MB = 40;
            int block_timeout = timeout_per_mb(ERASE_WRITE_TIMEOUT_PER_MB, FLASH_WRITE_SIZE);
            CmdRet retVal;
            // not using the block timeout yet need to modify the senCommand to have a
            // proper timeout
            retVal = flash_defl_block(block, seq, block_timeout);
            if (retVal.retCode == -1) {
                System.out.println("Retry because Ret code:" + retVal.retCode);
                System.out.println(printHex(retVal.retValue));
                retVal = flash_defl_block(block, seq, block_timeout);
            }
            seq += 1;
            position += FLASH_WRITE_SIZE;
            //System.out.println("Ret code:" + retVal.retCode);
            //System.out.println("Ret code:" + retVal.retValue.toString());
            if (debug) {
                System.out.println(printHex(retVal.retValue));
            }
        }
        long t2 = System.currentTimeMillis();
        System.out.println("Took " + (t2 - t1) + "ms to write " + size + " bytes");
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
                // Pad the last block
                block = _subArray(binaryData, position, binaryData.length - position);
            }
            int ERASE_WRITE_TIMEOUT_PER_MB = 40;
            int block_timeout = timeout_per_mb(ERASE_WRITE_TIMEOUT_PER_MB, FLASH_WRITE_SIZE);
            CmdRet retVal;
            // not using the block timeout yet need to modify the senCommand to have a
            // proper timeout
            retVal = flash_block(block, seq, block_timeout);
            if (retVal.retCode == -1) {
                System.out.println("Retry because Ret code:" + retVal.retCode);
                System.out.println(printHex(retVal.retValue));
                retVal = flash_block(block, seq, block_timeout);
            }
            seq += 1;
            position += FLASH_WRITE_SIZE;
            //System.out.println("Ret code:" + retVal.retCode);
            //System.out.println("Ret code:" + retVal.retValue.toString());
            if (debug) {
                System.out.println(printHex(retVal.retValue));
            }
        }
        long t2 = System.currentTimeMillis();
        System.out.println("Took " + (t2 - t1) + "ms to write " + size + " bytes");
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
            timeout = 3000;
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
        CmdRet res = sendCommand(ESP_FLASH_DEFL_BEGIN, pkt, 0, timeout);
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
            timeout = 3000;
        } else {
            // no stub
            write_size = erase_blocks * FLASH_WRITE_SIZE;
            timeout = timeout_per_mb(ERASE_REGION_TIMEOUT_PER_MB, write_size) * 10;
        }
        //System.out.println("Compressed " + size + " bytes to " + compsize + "...");
        byte[] pkt = _appendArray(_int_to_bytearray(write_size), _int_to_bytearray(num_blocks));
        pkt = _appendArray(pkt, _int_to_bytearray(FLASH_WRITE_SIZE));
        pkt = _appendArray(pkt, _int_to_bytearray(offset));
        // ESP32S3, ESP32C3, ESP32S2, ESP32C6,ESP32H2
        if (chip == ESP32S3 || chip == ESP32C2 || chip == ESP32C3 || chip == ESP32C6 || chip == ESP32S2 || chip == ESP32H2) {
            pkt = _appendArray(pkt, _int_to_bytearray(0));
        }
        CmdRet res = sendCommand(ESP_FLASH_BEGIN, pkt, 0, timeout);
        // end time
        long t2 = System.currentTimeMillis();
        if (size != 0 && !isStub) {
            System.out.println("Took " + ((t2 - t1) / 1000) + "." + ((t2 - t1) % 1000) + "s to erase flash block");
        }
        return num_blocks;
    }


    /*
     * Send a command to the chip to find out what type it is
     */
    public int detectChip() {
        int chipMagicValue = readRegister(CHIP_DETECT_MAGIC_REG_ADDR);
        int ret = 0;
        if (chipMagicValue == 0xfff0c101) {
            ret = ESP8266;
        }
        if (chipMagicValue == 0x00f01d83) {
            ret = ESP32;
        }
        if (chipMagicValue == 0x000007c6) {
            ret = ESP32S2;
        }
        if (chipMagicValue == 0x9) {
            ret = ESP32S3;
        }
        if ((chipMagicValue == 0x6f51306f) || chipMagicValue == 2084675695) {
            ret = ESP32C2;
        }
        if (chipMagicValue == 0x6921506f || chipMagicValue == 0x1b31506f) {
            ret = ESP32C3;
        }
        if (chipMagicValue == 0x0da1806f || chipMagicValue == 752910447) {
            ret = ESP32C6;
        }
        if (chipMagicValue == 0xca26cc22 || chipMagicValue == 0xd7b73e80/*-675856768*/) {
            ret = ESP32H2;
        }
        if (debug) {
            System.out.println("chipMagicValue" + chipMagicValue);
        }
        return ret;
    }

    ////////////////////////////////////////////////
    // Some utility functions
    /// /////////////////////////////////////////////

    /*
     * Just usefull for debuging to check what I am sending or receiving
     */
    private String printHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("[ ");
        for (byte b : bytes) {
            sb.append(String.format("0x%02X ", b));
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
        for (byte datum : data) {
            chk ^= datum;
        }
        return chk;
    }

    private int read_reg(int addr, int timeout) {
        CmdRet val;
        byte[] pkt = _int_to_bytearray(addr);
        val = sendCommand(ESP_READ_REG, pkt, 0, timeout);
        return val.retValue[0];
    }

    private int readRegister(int reg) {
        int retVal;
        CmdRet ret;
        byte[] packet = _int_to_bytearray(reg);
        ret = sendCommand(ESP_READ_REG, packet, 0, 100);
        byte[] subArray = new byte[4];
        subArray[3] = ret.retValue[4];
        subArray[2] = ret.retValue[5];
        subArray[1] = ret.retValue[6];
        subArray[0] = ret.retValue[7];
        retVal = ((subArray[0] & 0xFF) << 24) | ((subArray[1] & 0xFF) << 16) | ((subArray[2] & 0xFF) << 8) | (subArray[3] & 0xFF);
        return retVal;
    }

    private int timeout_per_mb(int seconds_per_mb, int size_bytes) {
        int result = (int) (seconds_per_mb * ((double) size_bytes / (double) 1000000));
        return Math.max(result, 3000);
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
        sendCommand(ESP_FLASH_END, pkt, 0, 100);
    }

    public void changeBaudRate(int baudRate) {
        byte[] pkt = _appendArray(_int_to_bytearray(baudRate), _int_to_bytearray(0));
        sendCommand(ESP_CHANGE_BAUDRATE, pkt, 0, 100);
    }
}
