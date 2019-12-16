package net.iakovlev.timeshape;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Class {@link TimeZoneEngine} is used to lookup the instance of
 * {@link java.time.ZoneId} based on latitude and longitude.
 */
public final class TimeZoneEngine {

    private final Index index;

    private TimeZoneEngine(Index index) {
        this.index = index;
    }

    /**
     * Queries the {@link TimeZoneEngine} for a {@link java.time.ZoneId}
     * based on geo coordinates.
     *
     * @param latitude  latitude part of query
     * @param longitude longitude part of query
     * @return List of all zones at given geo coordinate. Normally it's just
     * one zone, but for several places in the world there might be more.
     */
    public List<ZoneId> queryAll(double latitude, double longitude) {
        return index.query(latitude, longitude);
    }

    /**
     * Queries the {@link TimeZoneEngine} for a {@link java.time.ZoneId}
     * based on geo coordinates.
     *
     * @param latitude  latitude part of query
     * @param longitude longitude part of query
     * @return {@code Optional<ZoneId>#of(ZoneId)} if input corresponds
     * to some zone, or {@link Optional#empty()} otherwise.
     */
    public Optional<ZoneId> query(double latitude, double longitude) {
        final List<ZoneId> result = index.query(latitude, longitude);
        return result.size() > 0 ? Optional.of(result.get(0)) : Optional.empty();
    }

    /**
     * Queries the {@link TimeZoneEngine} for a {@link java.time.ZoneId}
     * based on sequence of geo coordinates
     *
     * @param points array of doubles representing the sequence of geo coordinates
     *               Must have the following shape: <code>{lat_1, lon_1, lat_2, lon_2, ..., lat_N, lon_N}</code>
     * @return Sequence of {@link SameZoneSpan}, where {@link SameZoneSpan#getEndIndex()} represents the last index
     * in the {@param points} array, which belong to the value of {@link SameZoneSpan#getZoneIds()}
     * E.g. for {@param points} == <code>{lat_1, lon_1, lat_2, lon_2, lat_3, lon_3}</code>, that is, a polyline of
     * 3 points: point_1, point_2, and point_3, and presuming point_1 belongs to Etc/GMT+1, point_2 belongs to Etc/GMT+2,
     * and point_3 belongs to Etc/Gmt+3, the result will be:
     * <code>{SameZoneSpan(Etc/Gmt+1, 1), SameZoneSpan(Etc/Gmt+2, 3), SameZoneSpan(Etc/Gmt+3, 5)}</code>
     */
    public List<SameZoneSpan> queryPolyline(double[] points) {
        return index.queryPolyline(points);
    }

    /**
     * Returns all the time zones that can be looked up.
     *
     * @return all the time zones that can be looked up.
     */
    public List<ZoneId> getKnownZoneIds() {
        return index.getKnownZoneIds();
    }

    /**
     * Creates a new instance of {@link TimeZoneEngine} and initializes it.
     * This is a blocking long running operation.
     *
     * @return an initialized instance of {@link TimeZoneEngine}
     */
    public static TimeZoneEngine initialize(boolean accelerateGeometry) {

        final Index shapes = IndexLoader.buildFrom(accelerateGeometry);
        return new TimeZoneEngine(shapes);
    }

    /**
     * Creates a new instance of {@link TimeZoneEngine} and initializes it.
     * This is a blocking long running operation.
     *
     * @return an initialized instance of {@link TimeZoneEngine}
     */
    public static TimeZoneEngine initialize() {

        final Index shapes = IndexLoader.buildFrom(false);
        return new TimeZoneEngine(shapes);
    }

    /**
     * Creates a new instance of {@link TimeZoneEngine} and initializes it.
     * This is a blocking long running operation.
     * <p>
     * Example invocation:
     * <p>
     * {{{
     * try (InputStream resourceAsStream = new FileInputStream("./core/target/resource_managed/main/data.tar.zstd");
     * TarArchiveInputStream f = new TarArchiveInputStream(new ZstdCompressorInputStream(resourceAsStream))) {
     * return TimeZoneEngine.initialize(f);
     * } catch (NullPointerException | IOException e) {
     * throw new RuntimeException(e);
     * }
     * }}}
     *
     * @return an initialized instance of {@link TimeZoneEngine}
     */
    public static TimeZoneEngine initialize(TarArchiveInputStream shapeInputStream) {

        final Index shapes = IndexLoader.buildFrom(shapeInputStream, false);
        return new TimeZoneEngine(shapes);
    }

    /**
     * Creates a new instance of {@link TimeZoneEngine} and initializes it.
     * This is a blocking long running operation.
     *
     * @return an initialized instance of {@link TimeZoneEngine}
     */
    public static TimeZoneEngine initialize(final double minLat, final double minLon,
                                            final double maxLat, final double maxLon,
                                            final boolean accelerateGeometry) {

        final Index shapes = IndexLoader.buildFrom(minLat, minLon, maxLat, maxLon, accelerateGeometry);
        return new TimeZoneEngine(shapes);
    }

    /**
     * Creates a new instance of {@link TimeZoneEngine} and initializes it from a given TarArchiveInputStream.
     * This is a blocking long running operation. The InputStream resource must be managed by the caller.
     * <p>
     * Example invocation:
     * {{{
     * try (InputStream resourceAsStream = new FileInputStream("./core/target/resource_managed/main/data.tar.zstd");
     * TarArchiveInputStream f = new TarArchiveInputStream(new ZstdCompressorInputStream(resourceAsStream))) {
     * return TimeZoneEngine.initialize(47.0599, 4.8237, 55.3300, 15.2486, true, f);
     * } catch (NullPointerException | IOException e) {
     * throw new RuntimeException(e);
     * }
     * }}}
     *
     * @return an initialized instance of {@link TimeZoneEngine}
     */
    public static TimeZoneEngine initialize(final double minLat, final double minLon,
                                            final double maxLat, final double maxLon,
                                            final boolean accelerateGeometry,
                                            final TarArchiveInputStream shapeInputStream) {

        final Index shapes = IndexLoader.buildFrom(shapeInputStream, minLat, minLon, maxLat, maxLon, accelerateGeometry);
        return new TimeZoneEngine(shapes);
    }
}
