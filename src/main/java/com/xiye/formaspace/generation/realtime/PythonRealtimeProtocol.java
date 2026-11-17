package com.xiye.formaspace.generation.realtime;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class PythonRealtimeProtocol {
    private static final Map<String, String> STAGES = Map.ofEntries(
            Map.entry("task_queued", "queued"), Map.entry("task_started", "preprocessing"),
            Map.entry("sampling_step", "sampling"), Map.entry("preview_started", "preview_decoding"),
            Map.entry("preview_ready", "sampling"), Map.entry("preview_failed", "sampling"),
            Map.entry("finalizing", "final_decoding"), Map.entry("task_completed", "completed"), Map.entry("task_failed", "failed"));
    public static String taskPath(String id) { return "/api/v1/tasks/" + UUID.fromString(id); }
    public static void require(boolean condition) throws IOException {
        if (!condition) throw new IOException("Invalid Python realtime protocol message");
    }
    public static long integer(JsonNode node, String name, long minimum, long maximum) throws IOException {
        var value = node.path(name);
        require(value.isIntegralNumber() && value.canConvertToLong());
        long number = value.asLong();
        require(number >= minimum && number <= maximum);
        return number;
    }
    public State snapshot(JsonNode node, String id, int expectedSteps) throws IOException {
        require(id.equals(node.path("taskId").asString()));
        String status = node.path("status").asString();
        require(Set.of("queued", "running", "completed", "failed").contains(status));
        var state = state(node, status, node.path("latestPreview"), node.path("result"), node.path("error"), expectedSteps);
        require(Set.of("queued", "preprocessing", "sampling", "preview_decoding", "final_decoding", "completed", "failed").contains(state.stage()));
        require(!status.equals("completed") || state.stage().equals("completed"));
        require(!status.equals("failed") || state.stage().equals("failed"));
        require(!status.equals("queued") || state.stage().equals("queued"));
        require(!status.equals("running") || Set.of("preprocessing","sampling","preview_decoding","final_decoding").contains(state.stage()));
        validatePaths(state, id);
        return state;
    }
    public Event event(JsonNode node, String id, int expectedSteps) throws IOException {
        require(integer(node, "protocolVersion", 1, 1) == 1 && id.equals(node.path("taskId").asString()));
        try { Instant.parse(node.path("timestamp").asString()); }
        catch (RuntimeException exception) { throw new IOException("Invalid event timestamp"); }
        String type = node.path("type").asString();
        if (type.equals("task_snapshot")) {
            State state = snapshot(node.path("payload"), id, expectedSteps);
            require(integer(node, "sequence", 0, Long.MAX_VALUE) == state.sequence());
            require(integer(node,"previewVersion",0,Integer.MAX_VALUE)==state.previewVersion());
            require(integer(node,"completedSteps",0,expectedSteps)==state.completedSteps());
            require(integer(node,"totalSteps",1,Integer.MAX_VALUE)==state.totalSteps());
            require(node.path("stage").asString().equals(state.stage()));
            return new Event(type, state, node.path("payload"));
        }
        require(STAGES.containsKey(type) && STAGES.get(type).equals(node.path("stage").asString()));
        JsonNode payload = node.path("payload");
        require(payload.isObject());
        if (type.equals("preview_failed")) require(payload.path("fatal").isBoolean());
        String status = type.equals("task_completed") ? "completed" : type.equals("task_failed") ? "failed"
                : type.equals("task_queued") ? "queued" : "running";
        var state = state(node, status, null, type.equals("task_completed") ? payload : null,
                type.equals("task_failed") ? payload : null, expectedSteps);
        validatePaths(state, id);
        if (type.equals("preview_ready")) {
            require(payload.path("positionsPath").asString().equals(taskPath(id) + "/previews/" + state.previewVersion()));
        }
        return new Event(type, state, payload);
    }
    private State state(JsonNode node, String status, JsonNode preview, JsonNode result, JsonNode error, int steps) throws IOException {
        int total = (int) integer(node, "totalSteps", 1, Integer.MAX_VALUE);
        require(total == steps);
        return new State(status, integer(node, "sequence", 0, Long.MAX_VALUE), node.path("stage").asString(),
                (int) integer(node, "completedSteps", 0, total), total,
                (int) integer(node, "previewVersion", 0, Integer.MAX_VALUE), preview, result, error);
    }
    private void validatePaths(State state, String id) throws IOException {
        if (state.preview()!=null && !state.preview().isNull() && !state.preview().isMissingNode()) {
            int version = (int) integer(state.preview(), "version", 1, Integer.MAX_VALUE);
            require(version == state.previewVersion());
            integer(state.preview(), "sourceStep", 1, Math.min(state.totalSteps()-1,state.completedSteps()));
            integer(state.preview(), "pointCount", 1, 5000);
            require(state.preview().path("positionsPath").asString().equals(taskPath(id)+"/previews/"+version));
        } else if (state.preview()!=null) require(state.previewVersion()==0);
        if (state.status().equals("completed")) {
            require(state.result()!=null && state.result().path("modelPath").asString().equals(taskPath(id)+"/result")
                    && state.result().path("format").asString().equals("glb"));
        }
        if (state.status().equals("failed")) require(state.error()!=null && state.error().path("code").isTextual());
    }
    public PointCloud points(JsonNode node, String id, int version, int totalSteps, boolean event) throws IOException {
        if (!event) require(id.equals(node.path("taskId").asString()) && integer(node,"previewVersion",1,Integer.MAX_VALUE)==version);
        int sourceStep = (int) integer(node, "sourceStep", 1, totalSteps-1);
        int count = (int) integer(node, "pointCount", 1, 5000);
        var positions = node.path("positions");
        require(positions.isArray() && positions.size()==count);
        List<List<Double>> points = new ArrayList<>(count);
        for (var position : positions) {
            require(position.isArray() && position.size()==3);
            List<Double> coordinates = new ArrayList<>(3);
            for (var value : position) {
                require(value.isNumber() && Double.isFinite(value.asDouble()));
                coordinates.add(value.asDouble());
            }
            points.add(List.copyOf(coordinates));
        }
        require(version > 0);
        return new PointCloud(version, sourceStep, count, List.copyOf(points));
    }
    public record State(String status, long sequence, String stage, int completedSteps, int totalSteps,
            int previewVersion, JsonNode preview, JsonNode result, JsonNode error) {}
    public record Event(String type, State state, JsonNode payload) {}
    public record PointCloud(int previewVersion, int sourceStep, int pointCount, List<List<Double>> positions) {}
}
