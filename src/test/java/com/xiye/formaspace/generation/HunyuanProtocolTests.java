package com.xiye.formaspace.generation;

import com.sun.net.httpserver.HttpServer;
import com.xiye.formaspace.common.exception.ErrorCode;
import com.xiye.formaspace.file.config.FileStorageProperties;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.enums.*;
import com.xiye.formaspace.generation.inference.*;
import com.xiye.formaspace.generation.inference.ModelInferenceClient.*;
import com.xiye.formaspace.generation.parameter.ResolvedGenerationParameters;
import com.xiye.formaspace.testsupport.MediaFixtures;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

/** Small loopback protocol checks only; never loads weights or starts the real model. */
class HunyuanProtocolTests {
    private HttpServer server;
    private final JsonMapper json=JsonMapper.builder().build();
    private final AtomicReference<String> event=new AtomicReference<>();
    private final AtomicReference<String> submitted=new AtomicReference<>();
    private final AtomicReference<String> instance=new AtomicReference<>("123");
    private GenerationProperties settings;
    private FileStorageProperties storage;
    private HunyuanLocalInferenceClient client;
    private boolean uploaded;
    private boolean redirect;
    private final String names="caption,image,mv_image_front,mv_image_back,mv_image_left,mv_image_right,steps,guidance_scale,seed,octree_resolution,check_box_rembg,num_chunks,randomize_seed";

    @BeforeEach
    void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();
            String body=""; String type="application/json"; int status=200;
            if(path.equals("/config")) {
                body="{\"version\":\"6.32.0\",\"api_prefix\":\"/gradio_api\",\"app_id\":"+instance.get()+",\"components\":[{\"id\":1,\"props\":{\"image_mode\":\"RGBA\"}},{\"id\":2,\"props\":{\"value\":\"Activated Model - tencent/Hunyuan3D-2mini/hunyuan3d-dit-v2-mini\"}}],\"dependencies\":[{\"api_name\":\"shape_generation\",\"inputs\":[0,1]}]}";
            } else if(path.equals("/gradio_api/info")) {
                var parameters=Arrays.stream(names.split(",")).map(name->Map.of("parameter_name",name)).toList();
                body=json.writeValueAsString(Map.of("named_endpoints",Map.of("/shape_generation",Map.of("parameters",parameters,"returns",List.of(Map.of("component","File"))))));
            } else if(path.equals("/gradio_api/upload")) {
                String multipart=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.ISO_8859_1);
                uploaded=multipart.contains("name=\"files\"") && multipart.contains("source-1.png");
                body="[\"/tmp/upload.png\"]";
            } else if(path.equals("/gradio_api/call/shape_generation")) {
                submitted.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                body="{\"event_id\":\"event_1\"}";
            } else if(path.equals("/gradio_api/call/shape_generation/event_1")) {
                type="text/event-stream";body=event.get();
            } else if(path.startsWith("/gradio_api/file=")) {
                if(redirect) { status=302;exchange.getResponseHeaders().add("Location","http://untrusted.invalid/model.glb"); }
                else {
                    byte[] bytes=MediaFixtures.glb();exchange.getResponseHeaders().set("Content-Type","model/gltf-binary");
                    exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;
                }
            } else {status=404;}
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type);
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        settings=new GenerationProperties();settings.setMode(GenerationMode.HUNYUAN_LOCAL);
        settings.getHunyuanLocal().setBaseUrl(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
        settings.getHunyuanLocal().setReadTimeout(Duration.ofSeconds(2));
        storage=new FileStorageProperties();
        client=new HunyuanLocalInferenceClient(settings,storage,json,Clock.systemUTC());
        event.set("event: heartbeat\ndata: null\n\nevent: complete\ndata: [{\"__type__\":\"update\",\"value\":{\"path\":\"/tmp/generated.glb\",\"url\":\"http://untrusted.invalid/ignored.glb\"}},\"<html>ignored</html>\",{},42]\n\n");
    }

    @AfterEach void stop() {server.stop(0);}

    private SubmissionResult submit() throws Exception {
        var parameters=new ResolvedGenerationParameters(1,"candidate",GenerationQuality.STANDARD,false,true,42,5,5,128,1000,"GLB");
        return client.submit(new Submission(9,parameters,new SourceImage(1,"image/png",()->new ByteArrayInputStream(MediaFixtures.png())),Instant.now().plusSeconds(20)));
    }

    @Test
    void uploadsBytesMapsExactParametersConsumesCompleteSseAndDownloadsOnlyConfiguredOrigin() throws Exception {
        assertThat(client.readiness().ready()).isTrue();
        var remote=submit();assertThat(uploaded).isTrue();
        var data=json.readTree(submitted.get()).get("data");
        assertThat(data.size()).isEqualTo(13);
        assertThat(data.get(6).asInt()).isEqualTo(5);
        assertThat(data.get(8).asLong()).isEqualTo(42);
        assertThat(data.get(11).asInt()).isEqualTo(1000);
        assertThat(data.get(12).asBoolean()).isFalse();
        assertThat(data.get(1).path("meta").path("_type").asString()).isEqualTo("gradio.FileData");
        var result=client.query(remote.remoteReference());assertThat(result.state()).isEqualTo(QueryState.SUCCEEDED);
        try(var model=client.openResult(result.resultReference())) {assertThat(model.readAllBytes()).isEqualTo(MediaFixtures.glb());}
    }

    @ParameterizedTest
    @ValueSource(strings={"error","html","disconnect","restart","oversize","redirect"})
    void rejectsImportantFailurePathsWithoutRealModel(String scenario) throws Exception {
        var remote=submit();
        switch(scenario) {
            case "error" -> {event.set("event: error\ndata: {\"error\":\"internal details\"}\n\n");assertThat(client.query(remote.remoteReference()).failure()).isEqualTo(ErrorCode.INFERENCE_FAILED);}
            case "html" -> {event.set("event: complete\ndata: [\"<html>not a model</html>\"]\n\n");assertThatThrownBy(()->client.query(remote.remoteReference())).isInstanceOf(InferenceClientException.class);}
            case "disconnect" -> {event.set("event: heartbeat\ndata: null\n\n");assertThatThrownBy(()->client.query(remote.remoteReference())).isInstanceOf(IOException.class);}
            case "restart" -> {
                instance.set("456");client=new HunyuanLocalInferenceClient(settings,storage,json,Clock.systemUTC());
                assertThatThrownBy(()->client.query(remote.remoteReference())).isInstanceOfSatisfying(InferenceClientException.class,error->assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INFERENCE_REFERENCE_LOST));
            }
            case "oversize" -> {
                storage.setMaxModelBytes(8);var result=client.query(remote.remoteReference());
                assertThatThrownBy(()->client.openResult(result.resultReference())).isInstanceOf(InferenceClientException.class);
            }
            case "redirect" -> {
                redirect=true;var result=client.query(remote.remoteReference());
                assertThatThrownBy(()->client.openResult(result.resultReference())).isInstanceOf(IOException.class);
            }
        }
    }
}
