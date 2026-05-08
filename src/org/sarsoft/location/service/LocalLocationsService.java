package org.sarsoft.location.service;

import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import java.util.Collections;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.batik.svggen.SVGSyntax;
import org.sarsoft.base.geometry.CTBoundingBox;
import org.sarsoft.base.util.RuntimeProperties;
import org.sarsoft.base.util.RuntimePropertiesProvider;
import org.sarsoft.base.util.RuntimePropertiesWrapper;
import org.sarsoft.compatibility.IJSONObject;
import org.sarsoft.compatibility.ILogger;
import org.sarsoft.compatibility.Log4jLogger;
import org.sarsoft.geodata.realtime.RealtimeDataManager;
import org.sarsoft.location.ILocationReportStream;
import org.sarsoft.location.LocationReport;

@Singleton
public class LocalLocationsService {
    private static final String SERVICE_NAME = LocalLocationsService.class.getSimpleName();
    public static final String LOCAL_LOCATION_COLOR_PROPNAME = "org.sarsoft.location.local.color";
    public static final String LOCAL_LOCATION_NAMESPACE = "LocalLocation";
    private final ILocationReportStream reportSource;
    private final RealtimeDataManager realtimeData;
    Disposable subscription = null;
    private final RuntimePropertiesProvider runtimeProps = new RuntimePropertiesWrapper();
    private final ILogger logger = Log4jLogger.getLogger(LocalLocationsService.class);

    @Inject
    public LocalLocationsService(ILocationReportStream reportSource, RealtimeDataManager realtimeData) {
        this.reportSource = reportSource;
        this.realtimeData = realtimeData;
    }

    public void start() {
        this.logger.d("Starting...");
        this.subscription = this.reportSource.subscribe(SERVICE_NAME)
            .observeOn(Schedulers.computation())
            .subscribe(this::onReport, this::onReportError);
    }

    void onReport(LocationReport report) {
        this.logger.i("Local location report " + report);
        IJSONObject props = RuntimeProperties.getJSONProvider().getJSONObject();
        props.putAll(report.getProperties());
        props.put("device", report.getDeviceId());
        props.put("title", report.getCallSign());
        props.put("stroke", SVGSyntax.SIGN_POUND + this.runtimeProps.getProperty(LOCAL_LOCATION_COLOR_PROPNAME, "ff0000"));
        this.realtimeData.update(LOCAL_LOCATION_NAMESPACE, Collections.singletonList(
            new LocationReport(report.getDeviceId(), report.getLocation(), props)), true);
    }

    private void onReportError(Throwable err) {
        this.logger.e("Failure while listening to locations: ", err);
    }

    public IJSONObject getJSONSince(CTBoundingBox bbox, Long since) {
        return this.realtimeData.getJSONSince(LOCAL_LOCATION_NAMESPACE, bbox, since.longValue());
    }
}
