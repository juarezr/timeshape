package net.iakovlev.timeshape;

import com.esri.core.geometry.*;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

final class Index {

    static final class Entry {
        final ZoneId zoneId;
        final Geometry geometry;

        Entry(ZoneId zoneId, Geometry geometry) {
            this.zoneId = zoneId;
            this.geometry = geometry;
        }
    }

    private final ArrayList<Entry> zoneIds;
    private final QuadTree quadTree;

    private static final int WGS84_WKID = 4326;
    static final SpatialReference spatialReference = SpatialReference.create(WGS84_WKID);

    Index(QuadTree quadTree, ArrayList<Entry> zoneIds) {
        this.quadTree = quadTree;
        this.zoneIds = zoneIds;
    }

    List<ZoneId> getKnownZoneIds() {
        return zoneIds.stream().map(e -> e.zoneId).collect(Collectors.toList());
    }

    List<ZoneId> query(double latitude, double longitude) {
        ArrayList<ZoneId> result = new ArrayList<>(2);
        Point point = new Point(longitude, latitude);
        QuadTree.QuadTreeIterator iterator = quadTree.getIterator(point, 0);
        for (int i = iterator.next(); i >= 0; i = iterator.next()) {
            int element = quadTree.getElement(i);
            Entry entry = zoneIds.get(element);
            if (GeometryEngine.contains(entry.geometry, point, spatialReference)) {
                result.add(entry.zoneId);
            }
        }
        return result;
    }

    List<SameZoneSpan> queryPolyline(double[] line) {

        Polyline polyline = new Polyline();
        ArrayList<Point> points = new ArrayList<>(line.length / 2);
        for (int i = 0; i < line.length - 1; i += 2) {
            Point p = new Point(line[i + 1], line[i]);
            points.add(p);
        }
        polyline.startPath(points.get(0));
        for (int i = 1; i < points.size(); i += 1) {
            polyline.lineTo(points.get(i));
        }
        QuadTree.QuadTreeIterator iterator = quadTree.getIterator(polyline, 0);

        ArrayList<Entry> potentiallyMatchingEntries = new ArrayList<>();

        for (int i = iterator.next(); i >= 0; i = iterator.next()) {
            int element = quadTree.getElement(i);
            Entry entry = zoneIds.get(element);
            potentiallyMatchingEntries.add(entry);
        }

        ArrayList<SameZoneSpan> sameZoneSegments = new ArrayList<>();
        List<Entry> currentEntry = null;
        // 1. find next matching geometry or geometries
        // 2. for every match, increase the index
        // 3. when it doesn't match anymore, save currentSegment to sameZoneSegments and start new one
        // 4. goto 1.
        int index = 0;
        boolean lastWasEmpty = false;
        while (index < points.size()) {
            Point p = points.get(index);
            if (currentEntry == null) {
                currentEntry = potentiallyMatchingEntries
                        .stream()
                        .filter(e -> GeometryEngine.contains(e.geometry, p, spatialReference))
                        .collect(Collectors.toList());
            }
            if (currentEntry.isEmpty()) {
                currentEntry = null;
                lastWasEmpty = true;
                index++;
            } else {
                if (lastWasEmpty) {
                    lastWasEmpty = false;
                    sameZoneSegments.add(SameZoneSpan.fromIndexEntries(Collections.emptyList(), (index - 1) * 2 + 1));
                    continue;
                }
                if (currentEntry.stream().allMatch(e -> GeometryEngine.contains(e.geometry, p, spatialReference))) {
                    if (index == points.size() - 1) {
                        sameZoneSegments.add(SameZoneSpan.fromIndexEntries(currentEntry, index * 2 + 1));
                    }
                    index++;
                } else {
                    sameZoneSegments.add(SameZoneSpan.fromIndexEntries(currentEntry, (index - 1) * 2 + 1));
                    currentEntry = null;
                }
            }
        }

        return sameZoneSegments;
    }

}
