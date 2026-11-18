package com.xiye.formaspace.generation;

import com.xiye.formaspace.generation.realtime.PointCloudPrintPlan;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PointCloudPrintPlanTests {
    @Test void traversalPreservesEveryCoordinateAndDuplicateWhileBuildingBottomUp() {
        var original = List.of(List.of(1., 1., 1.), List.of(2., 0., 1.), List.of(1., 0., 1.), List.of(1., 0., 1.));
        var ordered = PointCloudPrintPlan.order(original);
        assertThat(ordered).containsExactlyInAnyOrderElementsOf(original);
        assertThat(ordered.getFirst().get(1)).isZero();
        assertThat(ordered.getLast().get(1)).isEqualTo(1.);
        assertThat(original.getFirst()).containsExactly(1., 1., 1.);
    }

    @Test void fiftyPointBatchesHaveStableOffsetsAndIncludeTheLastPartialBatch() {
        var points = IntStream.range(0, 123).mapToObj(index -> List.of(index * 1., 0., 0.)).toList();
        var batches = PointCloudPrintPlan.batches(points);
        assertThat(batches.stream().map(PointCloudPrintPlan.Batch::offset)).containsExactly(0, 50, 100);
        assertThat(batches.stream().map(batch -> batch.positions().size())).containsExactly(50, 50, 23);
        assertThat(batches.stream().flatMap(batch -> batch.positions().stream()).toList()).isEqualTo(points);
        assertThat(PointCloudPrintPlan.bounds(points).maximum()).containsExactly(122., 0., 0.);
    }
}
