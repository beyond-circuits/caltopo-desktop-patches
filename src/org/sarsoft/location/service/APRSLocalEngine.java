package org.sarsoft.location.service;

import com.fazecast.jSerialComm.SerialPort;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.commons.io.input.TeeInputStream;
import org.sarsoft.base.util.RuntimePropertiesProvider;
import org.sarsoft.base.util.RuntimePropertiesWrapper;
import org.sarsoft.compatibility.ILogger;
import org.sarsoft.compatibility.Log4jLogger;
import org.sarsoft.location.ILocationReportPublisher;

@Singleton
public class APRSLocalEngine {
    public static final String ENABLE_APRS_PROPERTY = "sarsoft.location.aprs.local.enabled";
    private static final String LOG_SERIAL_DATA_PROPERTY = "sarsoft.location.serial.keeplog";
    private final HashMap<String, Thread> threads;
    private final ILocationReportPublisher reportSink;
    private final RuntimePropertiesProvider props;
    private final ILogger logger;

    @Inject
    public APRSLocalEngine(ILocationReportPublisher reportSink) {
        this(reportSink, new RuntimePropertiesWrapper(), Log4jLogger.getLogger(APRSLocalEngine.class));
    }

    public APRSLocalEngine(ILocationReportPublisher reportSink, RuntimePropertiesProvider props, ILogger logger) {
        this.threads = new HashMap<>();
        this.reportSink = reportSink;
        this.props = props;
        this.logger = logger;
    }

    public void start() {
        if (!Boolean.parseBoolean(this.props.getProperty(ENABLE_APRS_PROPERTY))) {
            this.logger.d("sarsoft.location.aprs.local.enabled not enabled.");
            return;
        }
        Thread t = new Thread(() -> {
            this.logger.i("Monitoring local APRS devices");
            while (true) {
                try {
                    portDiscovery();
                } catch (Exception e) {
                    this.logger.e("Unhandled exception", e);
                }
                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException e2) {
                    return;
                }
            }
        });
        t.setName(getClass().getSimpleName());
        t.start();
    }

    private void portDiscovery() {
        Iterator<String> it = this.threads.keySet().iterator();
        while (it.hasNext()) {
            String device = it.next();
            Thread thread = this.threads.get(device);
            if (thread != null && !thread.isAlive()) {
                this.logger.i("serial port " + device + " no longer active; removing from pool");
                SerialPort port = SerialPort.getCommPort(device);
                if (port.isOpen()) {
                    port.closePort();
                }
                it.remove();
            }
        }
        SerialPort[] ports = SerialPort.getCommPorts();
        if (ports == null) {
            ports = new SerialPort[0];
        }
        for (SerialPort port2 : ports) {
            String portName = port2.getSystemPortName();
            if (this.threads.containsKey(portName)) {
                continue;
            }
            String shortPortName = portName.substring(Math.max(0, portName.lastIndexOf('/') + 1));
            String configName = "sarsoft.location.serial." + shortPortName;
            String config = this.props.getProperty(configName);
            if (config == null || config.length() == 0) {
                // Bug fix: skip ports with no explicit configuration rather than
                // defaulting to 9600,8,1,0 and opening every UART on the system.
                this.logger.d("Port " + shortPortName + " not configured; skipping.");
                continue;
            }
            if ("0,0,0,0".equals(config)) {
                this.logger.d("Port " + shortPortName + " disabled by configuration.");
                continue;
            }
            boolean kissMode  = Boolean.parseBoolean(this.props.getProperty(configName + ".kiss"));
            boolean yaesuMode = Boolean.parseBoolean(this.props.getProperty(configName + ".yaesu"));
            this.logger.d("discovered serial port " + shortPortName +
                (kissMode ? " (KISS mode)" : yaesuMode ? " (Yaesu mode)" : ""));
            String[] parts = config.split(",");
            port2.setComPortParameters(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]));
            port2.setComPortTimeouts(1, 1000, 0);
            if (!port2.isOpen()) {
                port2.openPort();
            }
            InputStream stream = maybeSetupLogFile(port2.getInputStream(), shortPortName);
            OutputStream writeStream = port2.getOutputStream();
            APRSSerialThread thread2;
            if (kissMode) {
                thread2 = new KissSerialThread(stream, writeStream, this.reportSink, this.logger);
            } else if (yaesuMode) {
                thread2 = new YaesuSerialThread(stream, writeStream, this.reportSink, this.logger);
            } else {
                thread2 = new APRSSerialThread(stream, writeStream, this.reportSink, this.logger);
            }
            thread2.setName(getClass().getSimpleName() + "-" + portName);
            this.threads.put(portName, thread2);
            thread2.start();
        }
    }

    private InputStream maybeSetupLogFile(InputStream stream, String configName) {
        try {
            if (Boolean.parseBoolean(this.props.getProperty(LOG_SERIAL_DATA_PROPERTY))) {
                String logName = Paths.get(
                    this.props.getAppDataDirName(), "serial." + configName + ".log").toString();
                this.logger.i("Will log to " + logName);
                FileOutputStream logFile = new FileOutputStream(logName, true);
                stream = new TeeInputStream(stream, logFile, true);
            }
        } catch (FileNotFoundException e) {
            this.logger.e("Error opening log file for " + configName, e);
        }
        return stream;
    }
}
