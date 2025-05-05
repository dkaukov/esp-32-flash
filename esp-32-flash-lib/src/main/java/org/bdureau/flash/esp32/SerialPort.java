package org.bdureau.flash.esp32;

public interface SerialPort {
    void flush();
    int read(byte[] buffer, int length);
    void write(byte[] buffer, int length);
    void setControlLines(boolean dtr, boolean rts);
}
