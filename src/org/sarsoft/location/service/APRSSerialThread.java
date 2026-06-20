package org.sarsoft.location.service;

import com.fazecast.jSerialComm.SerialPortIOException;
import com.fazecast.jSerialComm.SerialPortTimeoutException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.lang.StringUtils;
import org.sarsoft.base.geometry.CTPoint;
import org.sarsoft.base.util.Triplet;
import org.sarsoft.compatibility.ILogger;
import org.sarsoft.location.ILocationReportPublisher;
import org.sarsoft.location.LocationReport;

public class APRSSerialThread extends Thread {
    private final OutputStream outputStream;
    protected final ILocationReportPublisher reportSink;
    protected final ILogger logger;
    protected final InputStream stream;

    public APRSSerialThread(InputStream stream, OutputStream outputStream, ILocationReportPublisher reportSink, ILogger logger) {
        this.stream = stream;
        this.outputStream = outputStream;
        this.reportSink = reportSink;
        this.logger = logger;
    }

    @Override
    public void run() {
        try {
            try {
                initDevice();
                processStream();
            } finally {
                try {
                    this.stream.close();
                    this.outputStream.close();
                } catch (IOException e) {
                }
            }
        } catch (SerialPortIOException e2) {
            this.logger.d("Failed to read from serial port: " + e2.getMessage());
            try {
                this.stream.close();
                this.outputStream.close();
            } catch (IOException e3) {
            }
        } catch (IOException e4) {
            e4.printStackTrace();
            try {
                this.stream.close();
                this.outputStream.close();
            } catch (IOException e5) {
            }
        }
    }

    protected void initDevice() throws IOException {
        byte[] bytes = "\r\n\r\n\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        this.outputStream.write(bytes);
    }

    protected void processStream() throws IOException {
        int bytesRead;
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[1024];
        while (true) {
            try {
                bytesRead = this.stream.read(buffer);
            } catch (SerialPortTimeoutException e) {
                continue;
            }
            if (bytesRead != -1) {
                for (int i = 0; i < bytesRead; i++) {
                    byte b = buffer[i];
                    if (b == 10 || b == 13) {
                        processLine(sb.toString());
                        sb = new StringBuilder();
                    } else {
                        sb.append((char) b);
                    }
                }
            } else {
                return;
            }
        }
    }

    protected void processLine(String line) {
        if (StringUtils.isEmpty(line)) {
            return;
        }
        this.logger.i("Serial data: " + line);
        try {
            if (HamTracksParser.isHamTracksPacket(line)) {
                List<LocationReport> reports = HamTracksParser.parsePacket(line);
                for (LocationReport report : reports) {
                    this.reportSink.reportLocation(report);
                }
                return;
            }
            Triplet<String, CTPoint, String> parsed = LocationParser.parseLine(line);
            if (parsed != null) {
                CTPoint ptWithTime = parsed.getSecond().withTime(System.currentTimeMillis());
                LocationReport report = new LocationReport("LOCAL:" + parsed.getFirst(), ptWithTime);
                this.reportSink.reportLocation(report);
            }
        } catch (Exception e) {
            this.logger.i("Error handling message from serial port: " + line);
        }
    }
}
