package net.iakovlev.timeshape;

import com.esri.core.geometry.*;
import net.iakovlev.timeshape.proto.Geojson;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class IndexLoader {

    // region Properties

    private ArrayList<Index.Entry> zoneIds;
    private QuadTree quadTree;

    private Envelope boundaries;
    private boolean accelerateGeometry;
    private int current = 0;

    private final List<String> unknownZones = new ArrayList<>();
    private final OperatorContains operatorContains = (OperatorContains) OperatorFactoryLocal
            .getInstance().getOperator(Operator.Type.Contains);

    private static final Logger log = LoggerFactory.getLogger(IndexLoader.class);

    // endregion Properties

    // region Index Building

    private IndexLoader(final Envelope boundaries, boolean withAccelerateGeometry) {

        log.info("Initialized a new index with time zones");

        this.boundaries = boundaries;
        this.accelerateGeometry = withAccelerateGeometry;

        final Envelope2D boundariesEnvelope = new Envelope2D();
        boundaries.queryEnvelope2D(boundariesEnvelope);

        this.quadTree = new QuadTree(boundariesEnvelope, 8);
        this.zoneIds = new ArrayList<>();
    }

    public static Index buildFrom(final double minLat, final double minLon,
                                  final double maxLat, final double maxLon,
                                  final boolean accelerateGeometry) {

        try (InputStream resourceAsStream = TimeZoneEngine.class.getResourceAsStream("/data.tar.zstd");

             TarArchiveInputStream shapeInputStream = new TarArchiveInputStream(new ZstdCompressorInputStream(resourceAsStream))) {

            return buildFrom(shapeInputStream, minLat, minLon, maxLat, maxLon, accelerateGeometry);
        } catch (NullPointerException | IOException e) {
            log.error("Unable to read resource file", e);
            throw new RuntimeException(e);
        }
    }

    public static Index buildFrom(final TarArchiveInputStream shapeInputStream,
                                  final double minLat, final double minLon,
                                  final double maxLat, final double maxLon,
                                  final boolean accelerateGeometry) {

        validateCoordinates(minLat, minLon, maxLat, maxLon);
        try {
            TarArchiveEntry entry = shapeInputStream.getNextTarEntry();
            if (entry == null) {
                throw new RuntimeException("Data entry is not found in file");
            }
            final Envelope boundaries = new Envelope(minLon, minLat, maxLon, maxLat);
            final IndexLoader loader = new IndexLoader(boundaries, accelerateGeometry);

            while (entry != null) {
                final Geojson.Feature feature = parseGeojsonFrom(shapeInputStream, entry);
                if (feature == null) {
                    throw new RuntimeException("Error reading data entry from file at entry: " + entry.getName());
                }
                loader.appendZone(feature);
                entry = shapeInputStream.getNextTarEntry();
            }
            loader.logUnknownZones();

            return new Index(loader.quadTree, loader.zoneIds);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void appendZone(final Geojson.Feature feature) {

        final String zoneIdName = feature.getProperties(0).getValueString();
        try {
            final ZoneId zoneId = ZoneId.of(zoneIdName);
            final List<Polygon> polygons = convertToPolygons(feature);
            for (Polygon polygon : polygons) {
                log.debug("Adding polygon #{} from zone {} to index", this.current, zoneIdName);
                appendPolygon(polygon, zoneId);
            }
        } catch (Exception ex) {
            this.unknownZones.add(zoneIdName);
        }
    }

    private void appendPolygon(final Polygon polygon, final ZoneId zoneId) {

        if (this.accelerateGeometry) {
            this.operatorContains.accelerateGeometry(polygon, Index.spatialReference, Geometry.GeometryAccelerationDegree.enumMild);
        }
        final boolean found = GeometryEngine.contains(boundaries, polygon, Index.spatialReference);
        if (found) {

            final Envelope2D env = new Envelope2D();
            polygon.queryEnvelope2D(env);
            this.quadTree.insert(current, env);
            final Index.Entry zoneEntry = new Index.Entry(zoneId, polygon);
            this.zoneIds.add(current, zoneEntry);
            this.current += 1;
        } else {
            log.debug("Not adding zone {} to index because it's out of provided boundaries", zoneId);
        }
    }

    // endregion Index Building

    // region Validation

    private final static double MIN_LAT = -90;
    private final static double MIN_LON = -180;
    private final static double MAX_LAT = 90;
    private final static double MAX_LON = 180;

    private static void validateCoordinates(final double minLat, final double minLon, final double maxLat, final double maxLon) {

        final List<String> errors = new ArrayList<>();
        if (minLat < MIN_LAT || minLat > MAX_LAT) {
            errors.add(String.format(Locale.ROOT, "minimum latitude %f is out of range: must be -90 <= latitude <= 90;", minLat));
        }
        if (maxLat < MIN_LAT || maxLat > MAX_LAT) {
            errors.add(String.format(Locale.ROOT, "maximum latitude %f is out of range: must be -90 <= latitude <= 90;", maxLat));
        }
        if (minLon < MIN_LON || minLon > MAX_LON) {
            errors.add(String.format(Locale.ROOT, "minimum longitude %f is out of range: must be -180 <= longitude <= 180;", minLon));
        }
        if (maxLon < MIN_LON || maxLon > MAX_LON) {
            errors.add(String.format(Locale.ROOT, "maximum longitude %f is out of range: must be -180 <= longitude <= 180;", maxLon));
        }
        if (minLat > maxLat) {
            errors.add(String.format(Locale.ROOT, "maximum latitude %f is less than minimum latitude %f;", maxLat, minLat));
        }
        if (minLon > maxLon) {
            errors.add(String.format(Locale.ROOT, "maximum longitude %f is less than minimum longitude %f;", maxLon, minLon));
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", errors));
        }
        log.info("Initializing with bounding box: {}, {}, {}, {}", minLat, minLon, maxLat, maxLon);
    }

    // endregion Validation

    // region Helpers

    private static Geojson.Feature parseGeojsonFrom(final TarArchiveInputStream shapeInputStream,
                                                    final TarArchiveEntry entry) throws IOException {

        log.debug("Processing archive entry {}", entry.getName());

        final byte[] buffer = new byte[(int) entry.getSize()];
        final int read = shapeInputStream.read(buffer);
        if (read < 1)
            return null;
        return Geojson.Feature.parseFrom(buffer);
    }

    private static List<Polygon> convertToPolygons(Geojson.Feature feature) {
        final List<Polygon> result = new ArrayList<>();

        if (feature.getGeometry().hasPolygon()) {
            final Polygon polygon = buildPoly(feature.getGeometry().getPolygon());
            result.add(polygon);
        } else if (feature.getGeometry().hasMultiPolygon()) {
            final Geojson.MultiPolygon multiPolygonProto = feature.getGeometry().getMultiPolygon();
            final List<Geojson.Polygon> coordinatesList = multiPolygonProto.getCoordinatesList();
            for (Geojson.Polygon geoPoly : coordinatesList) {
                final Polygon polygon = buildPoly(geoPoly);
                result.add(polygon);
            }
        } else {
            throw new RuntimeException("Unknown geometry type");
        }
        return result;
    }

    private static Polygon buildPoly(Geojson.Polygon from) {
        final Polygon poly = new Polygon();

        final List<Geojson.LineString> coordinatesList = from.getCoordinatesList();
        for (Geojson.LineString lineString : coordinatesList) {
            final List<Geojson.Position> coords = lineString.getCoordinatesList();
            poly.startPath(coords.get(0).getLon(), coords.get(0).getLat());
            final List<Geojson.Position> positions = coords.subList(1, coords.size());
            for (Geojson.Position p : positions) {
                poly.lineTo(p.getLon(), p.getLat());
            }
        }
        return poly;
    }

    void logUnknownZones() {
        if (unknownZones.size() != 0) {
            String allUnknownZones = String.join(", ", unknownZones);
            log.error(
                    "Some of the zone ids were not recognized by the Java runtime and will be ignored. " +
                            "The most probable reason for this is outdated Java runtime version. " +
                            "The following zones were not recognized: " + allUnknownZones);
        }
    }

    // endregion Helpers
}

