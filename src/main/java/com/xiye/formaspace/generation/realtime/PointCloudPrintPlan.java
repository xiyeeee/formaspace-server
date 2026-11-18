package com.xiye.formaspace.generation.realtime;

import java.util.*;

/** A display traversal of real points; coordinates and point multiplicity are preserved. */
public final class PointCloudPrintPlan {
    public static final int BATCH_SIZE = 50;
    private PointCloudPrintPlan() {}

    public static Bounds bounds(List<List<Double>> positions) {
        double[] minimum = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] maximum = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (var point : positions) {
            for (int axis = 0; axis < 3; axis++) {
                minimum[axis] = Math.min(minimum[axis], point.get(axis));
                maximum[axis] = Math.max(maximum[axis], point.get(axis));
            }
        }
        return new Bounds(Arrays.stream(minimum).boxed().toList(), Arrays.stream(maximum).boxed().toList());
    }

    private static int band(double coordinate, double minimum, double maximum, int count) {
        if (maximum <= minimum) return 0;
        return Math.min(count - 1, Math.max(0, (int) ((coordinate - minimum) / (maximum - minimum) * count)));
    }

    public static List<List<Double>> order(List<List<Double>> positions) {
        Bounds bounds = bounds(positions);
        var comparator = Comparator.<List<Double>>comparingInt(point -> band(point.get(1), bounds.minimum().get(1), bounds.maximum().get(1), 48))
                .thenComparingInt(point -> band(point.get(2), bounds.minimum().get(2), bounds.maximum().get(2), 24))
                .thenComparingDouble(point -> {
                    int row = band(point.get(2), bounds.minimum().get(2), bounds.maximum().get(2), 24);
                    return point.getFirst() * (row % 2 == 0 ? 1 : -1);
                }).thenComparingDouble(point -> point.get(1)).thenComparingDouble(point -> point.get(2));
        return positions.stream().sorted(comparator).toList();
    }

    public static List<Batch> batches(List<List<Double>> positions) {
        var result = new ArrayList<Batch>();
        for (int offset = 0; offset < positions.size(); offset += BATCH_SIZE) {
            result.add(new Batch(offset, List.copyOf(positions.subList(offset, Math.min(offset + BATCH_SIZE, positions.size())))));
        }
        return List.copyOf(result);
    }

    public record Bounds(List<Double> minimum, List<Double> maximum) {}
    public record Batch(int offset, List<List<Double>> positions) {}
}
