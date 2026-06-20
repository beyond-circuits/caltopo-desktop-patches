package org.sarsoft.location.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.sarsoft.base.geometry.CTPoint;
import org.sarsoft.compatibility.ILogger;
import org.sarsoft.location.ILocationReportPublisher;
import org.sarsoft.location.LocationReport;

/**
 * Reads NMEA 0183 sentences from a GPS receiver and publishes position reports.
 * Parses $GPRMC / $GNRMC sentences; skips void (no-fix) sentences.
 */
public class GPSSerialThread extends APRSSerialThread {

    private final String deviceId;

    public GPSSerialThread(InputStream stream, OutputStream outputStream,
                           ILocationReportPublisher reportSink, ILogger logger,
                           String name) {
        super(stream, outputStream, reportSink, logger);
        this.deviceId = "LOCAL:" + name;
    }

    @Override
    protected void initDevice() throws IOException {
        // GPS receivers stream NMEA continuously; no TNC wake-up sequence needed.
    }

    @Override
    protected void processLine(String line) {
        if (line.isEmpty()) return;
        this.logger.i("GPS data: " + line);

        if (!line.startsWith("$GPRMC,") && !line.startsWith("$GNRMC,")) return;

        // Strip checksum suffix before splitting
        int star = line.indexOf('*');
        String body = (star >= 0) ? line.substring(0, star) : line;
        String[] f = body.split(",", -1);

        // $GNRMC,time,status,lat,N/S,lon,E/W,speed,course,date,...
        if (f.length < 7) return;
        if (!"A".equals(f[2])) return;  // V = void / no fix yet

        try {
            double lat = nmeaToDecimal(f[3], f[4]);
            double lon = nmeaToDecimal(f[5], f[6]);
            CTPoint pt = new CTPoint(lon, lat).withTime(System.currentTimeMillis());
            this.reportSink.reportLocation(new LocationReport(deviceId, pt));
        } catch (Exception e) {
            this.logger.i("GPS: error handling sentence: " + line);
        }
    }

    // Converts NMEA DDDMM.MMMM + hemisphere to decimal degrees.
    private static double nmeaToDecimal(String value, String hemisphere) {
        double raw = Double.parseDouble(value);
        int degrees = (int) (raw / 100);
        double minutes = raw - degrees * 100;
        double decimal = degrees + minutes / 60.0;
        if ("S".equals(hemisphere) || "W".equals(hemisphere)) decimal = -decimal;
        return decimal;
    }
}
