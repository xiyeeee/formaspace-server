package com.xiye.formaspace.generation;

import com.xiye.formaspace.generation.config.*;
import com.xiye.formaspace.generation.enums.GenerationMode;
import com.xiye.formaspace.generation.inference.ModelInferenceClient;
import com.xiye.formaspace.generation.realtime.*;
import com.xiye.formaspace.testsupport.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.*;

class PythonRealtimeProtocolTests {
    private PythonRealtimeFixture server;
    private GenerationProperties config;
    private PythonRealtimeClient client;
    private final PythonRealtimeProtocol protocol=new PythonRealtimeProtocol();
    private final String id=UUID.randomUUID().toString();
    @BeforeEach void setup() throws Exception {
        server=new PythonRealtimeFixture();config=new GenerationProperties();config.setMode(GenerationMode.PYTHON_REALTIME);
        var properties=config.getPythonRealtime();properties.setBaseUrl(server.httpUrl());properties.setWsBaseUrl(server.wsUrl());
        properties.setToken(PythonRealtimeFixture.TOKEN);properties.setConnectTimeout(Duration.ofSeconds(2));
        client=new PythonRealtimeClient(config,server.json,protocol);
    }
    @AfterEach void cleanup() {client.close();server.close();}
    private void submit() throws Exception {
        var p=new PythonTaskParameters(1234,30,5,256,true,24,128,5000,4000);
        client.submitTask(id,server.json.writeValueAsString(p),new ModelInferenceClient.SourceImage(1,"image/png",()->new ByteArrayInputStream(MediaFixtures.png())));
    }
    @Test void authenticatesMultipartAndReplaysExactIdentityAndParameters() throws Exception {
        assertThat(client.ready()).isTrue();submit();submit();
        assertThat(server.taskId).isEqualTo(id);assertThat(server.submissions.get()).isEqualTo(2);
        assertThat(server.httpAuthorized).isTrue();
        assertThat(server.multipart).contains("name=\"taskId\"","name=\"image\"","name=\"parameters\"");
        var p=server.json.readTree(server.parameters);
        assertThat(p.properties()).hasSize(9);assertThat(p.path("previewStep").asInt()).isEqualTo(24);
        assertThat(p.has("removeBackground")).isFalse();assertThat(p.has("texture")).isFalse();
        assertThat(protocol.snapshot(client.snapshot(id),id,30).status()).isEqualTo("queued");
    }
    @Test void handlesConflictCapacityAndRejectsUntrustedResponsePaths() throws Exception {
        server.submissionStatus.set(429);
        assertThatThrownBy(this::submit).isInstanceOfSatisfying(PythonRealtimeClient.RemoteHttpException.class,error->{
            assertThat(error.status()).isEqualTo(429);assertThat(error.retryAfter()).isEqualTo(1);
        });
        server.submissionStatus.set(409);
        assertThatThrownBy(this::submit).isInstanceOfSatisfying(PythonRealtimeClient.RemoteHttpException.class,error->assertThat(error.status()).isEqualTo(409));
        server.submissionStatus.set(202);server.externalPaths=true;
        assertThatThrownBy(this::submit).isInstanceOf(IOException.class);
    }
    @Test void reassemblesRealWebSocketFragmentsIncluding5000PointMessageAndAuthenticatesHandshake() throws Exception {
        submit();var messages=new ArrayBlockingQueue<String>(4);var disconnected=new AtomicBoolean();
        server.onConnect=socket->{
            var state=server.state("running",1,24,1);var payload=new HashMap<String,Object>(server.preview());
            payload.put("positionsPath",server.path()+"/previews/1");
            PythonRealtimeFixture.send(socket,server.envelope("preview_ready",state,payload),true);
        };
        var socket=client.connect(id,new PythonSocketListener(2097152,(ws,text)->{boolean accepted=messages.offer(text);ws.request(1);return accepted;},disconnected));
        try {
            String first=messages.poll(5,TimeUnit.SECONDS),second=messages.poll(5,TimeUnit.SECONDS);
            assertThat(first).isNotNull();assertThat(second).isNotNull();
            assertThat(protocol.event(server.json.readTree(first),id,30).type()).isEqualTo("task_snapshot");
            var event=protocol.event(server.json.readTree(second),id,30);
            assertThat(protocol.points(event.payload(),id,1,30,true).positions()).hasSize(5000);
            assertThat(server.wsAuthorized).isTrue();
        } finally {socket.abort();}
    }
    @Test void rejectsOversizedSocketTextWithoutEnqueuingIt() throws Exception {
        submit();var disconnected=new AtomicBoolean();var messages=new ArrayBlockingQueue<String>(1);
        var socket=client.connect(id,new PythonSocketListener(64,(ws,text)->messages.offer(text),disconnected));
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!disconnected.get() && System.nanoTime()<deadline) Thread.sleep(10);
            assertThat(disconnected).isTrue();assertThat(messages).isEmpty();
        } finally {socket.abort();}
    }
    @Test void validatesCoordinatesCountsIdsAndStageWithoutNormalizingTheCloud() throws Exception {
        submit();JsonNode preview=server.json.valueToTree(server.preview());
        var cloud=protocol.points(preview,id,1,30,false);
        assertThat(cloud.positions().getFirst()).containsExactly(0.1,0.2,0.3);
        assertThatThrownBy(()->protocol.points(preview,UUID.randomUUID().toString(),1,30,false)).isInstanceOf(IOException.class);
        var invalid=new HashMap<String,Object>(server.preview());invalid.put("pointCount",2);
        assertThatThrownBy(()->protocol.points(server.json.valueToTree(invalid),id,1,30,false)).isInstanceOf(IOException.class);
        invalid.put("pointCount",1);invalid.put("positions",List.of(List.of(1,2)));
        assertThatThrownBy(()->protocol.points(server.json.valueToTree(invalid),id,1,30,false)).isInstanceOf(IOException.class);
        assertThat(protocol.snapshot(server.state("running",30,30,0),id,30).status()).isEqualTo("running");
        assertThat(protocol.snapshot(server.state("running",30,30,0),id,30).stage()).isEqualTo("final_decoding");
    }
    @Test void boundsModelDownloadAndTreatsResultNotReadyAsRetryableHttp409() throws Exception {
        submit();server.resultStatus.set(409);
        assertThatThrownBy(()->client.openResult(id)).isInstanceOfSatisfying(PythonRealtimeClient.RemoteHttpException.class,error->assertThat(error.status()).isEqualTo(409));
        server.resultStatus.set(302);assertThatThrownBy(()->client.openResult(id)).isInstanceOf(PythonRealtimeClient.RemoteHttpException.class);
        server.resultStatus.set(200);config.getPythonRealtime().setMaximumDownloadBytes(10);
        assertThatThrownBy(()->client.openResult(id)).isInstanceOf(IOException.class);
    }
    @Test void requiresServiceTokenAndRejectsInvalidPreviewCombinationBeforeSubmission() {
        config.getPythonRealtime().setToken("");
        assertThatThrownBy(()->new PythonRealtimeClient(config,server.json,protocol)).isInstanceOf(IllegalStateException.class);
        config.getPythonRealtime().setToken(PythonRealtimeFixture.TOKEN);config.getPythonRealtime().setSteps(24);
        assertThatThrownBy(()->new PythonRealtimeClient(config,server.json,protocol)).isInstanceOf(IllegalStateException.class);
    }
    @Test void fullBusinessQueueDisconnectsInsteadOfAccumulatingMessages() {
        var socket=org.mockito.Mockito.mock(java.net.http.WebSocket.class);var disconnected=new AtomicBoolean();
        var listener=new PythonSocketListener(1024,(ws,text)->false,disconnected);
        listener.onText(socket,"{}",true);
        assertThat(disconnected).isTrue();org.mockito.Mockito.verify(socket).abort();
    }
}
