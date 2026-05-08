package org.sarsoft.location.service;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.sarsoft.compatibility.ILogger;
import org.sarsoft.location.ILocationReportPublisher;

/**
 * Handles Yaesu FTM-series TNC2 monitor output, which splits each packet
 * across two lines:
 *   KN6TYR-7>SVUXPP,WIDE1-1,WIDE2-1 [05/07/26 14:51:13] <UI R>:
 *   `2[l [/`_3
 * Reassembles into standard TNC2 format before passing to the parent parser.
 */
public class YaesuSerialThread extends APRSSerialThread {

    // Matches: "CALLSIGN>PATH [date time] <UI...>:"
    private static final Pattern HEADER_RE = Pattern.compile(
        "^(.+?)\\s+\\[\\d{2}/\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\]\\s+<UI[^>]*>:\\s*$"
    );

    private String pendingHeader = null;

    public YaesuSerialThread(InputStream stream, OutputStream outputStream,
                             ILocationReportPublisher reportSink, ILogger logger) {
        super(stream, outputStream, reportSink, logger);
    }

    @Override
    protected void processLine(String line) {
        if (line.isEmpty()) {
            return;
        }
        Matcher m = HEADER_RE.matcher(line);
        if (m.matches()) {
            pendingHeader = m.group(1);
        } else if (pendingHeader != null) {
            // Combine "CALLSIGN>PATH" + ":" + info field — standard TNC2
            super.processLine(pendingHeader + ":" + line);
            pendingHeader = null;
        } else {
            pendingHeader = null;
        }
    }
}
