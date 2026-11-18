package com.xiye.formaspace.generation;

import com.xiye.formaspace.common.enums.UserStatus;
import com.xiye.formaspace.generation.browser.*;
import com.xiye.formaspace.generation.realtime.*;
import com.xiye.formaspace.user.entity.UserEntity;
import com.xiye.formaspace.user.mapper.UserMapper;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BrowserWebSocketQueueTests {
    @Test void optedInPreviewSendsFiftyPointPacketsWithoutConsumingBusinessSequenceOrOverflowingQueue()throws Exception{
        BrowserEventStore store=mock(BrowserEventStore.class);GenerationPreviewService previews=mock(GenerationPreviewService.class);UserMapper users=mock(UserMapper.class);
        Clock clock=Clock.systemUTC();when(store.lock(anyLong())).thenReturn(new Object());
        UserEntity user=new UserEntity();user.setId(1L);user.setStatus(UserStatus.ACTIVE);when(users.selectById(1L)).thenReturn(user);
        var metadata=new GenerationPreviewService.PreviewMetadata(1,24,5000,"/api/generation/tasks/7/previews/1");
        var initial=new BrowserEventStore.View(7,1,"PROCESSING",false,"sampling",24,30,null,"CONNECTED",null,null);
        var ready=new BrowserEventStore.View(7,1,"PROCESSING",false,"sampling",24,30,metadata,"CONNECTED",null,null);
        when(store.snapshot(7,1)).thenReturn(new BrowserEventStore.Frame(initial,0,"task_snapshot",clock.instant().toString()));
        when(store.record(7,"preview_ready",28)).thenReturn(new BrowserEventStore.Frame(ready,1,"preview_ready",clock.instant().toString()));
        var positions=IntStream.range(0,5000).mapToObj(index->List.of(index*.0001,index*.0002,0.)).toList();
        when(previews.read(7,1,1)).thenReturn(new GenerationPreviewService.PreviewVO("7",1,24,5000,positions));
        var json=JsonMapper.builder().build();GenerationWebSocketHandler handler=new GenerationWebSocketHandler(store,previews,users,json,clock);
        WebSocketSession streaming=session("streaming",clock),legacy=session("legacy",clock);
        BlockingQueue<String> chunks=new LinkedBlockingQueue<>(),full=new LinkedBlockingQueue<>();
        doAnswer(call->{chunks.add(((TextMessage)call.getArgument(0)).getPayload());return null;}).when(streaming).sendMessage(any());
        doAnswer(call->{full.add(((TextMessage)call.getArgument(0)).getPayload());return null;}).when(legacy).sendMessage(any());
        try{
            handler.afterConnectionEstablished(streaming);handler.afterConnectionEstablished(legacy);
            ReflectionTestUtils.invokeMethod(handler,"handleTextMessage",streaming,new TextMessage("{\"protocolVersion\":1,\"type\":\"subscribe\",\"taskId\":\"7\",\"pointBatches\":true}"));
            ReflectionTestUtils.invokeMethod(handler,"handleTextMessage",legacy,new TextMessage("{\"protocolVersion\":1,\"type\":\"subscribe\",\"taskId\":\"7\"}"));
            assertThat(chunks.poll(5,TimeUnit.SECONDS)).contains("task_snapshot");assertThat(full.poll(5,TimeUnit.SECONDS)).contains("task_snapshot");
            handler.onGenerationEvent(new GenerationEventPublisher.GenerationEvent(7,"preview_ready",28));
            var announcement=json.readTree(chunks.poll(5,TimeUnit.SECONDS));
            assertThat(announcement.path("payload").path("streamed").asBoolean()).isTrue();assertThat(announcement.path("payload").has("positions")).isFalse();
            for(int batch=0;batch<100;batch++){
                var packet=json.readTree(chunks.poll(5,TimeUnit.SECONDS));
                assertThat(packet.path("type").asString()).isEqualTo("point_batch");assertThat(packet.has("sequence")).isFalse();
                assertThat(packet.path("baseSequence").asLong()).isEqualTo(1);
                assertThat(packet.path("payload").path("offset").asInt()).isEqualTo(batch*50);
                assertThat(packet.path("payload").path("positions").size()).isEqualTo(50);
            }
            assertThat(json.readTree(full.poll(5,TimeUnit.SECONDS)).path("payload").path("positions").size()).isEqualTo(5000);
            verify(streaming,never()).close(any());verify(legacy,never()).close(any());
        }finally{handler.shutdown();}
    }
    @Test void blockedConsumerCannotBlockFastConsumerAndOverflowsWith1013()throws Exception{
        BrowserEventStore store=mock(BrowserEventStore.class);GenerationPreviewService previews=mock(GenerationPreviewService.class);UserMapper users=mock(UserMapper.class);
        Clock clock=Clock.systemUTC();Object lock=new Object();when(store.lock(anyLong())).thenReturn(lock);
        UserEntity user=new UserEntity();user.setId(1L);user.setStatus(UserStatus.ACTIVE);when(users.selectById(1L)).thenReturn(user);
        BrowserEventStore.View view=new BrowserEventStore.View(7,1,"PROCESSING",false,"sampling",10,30,null,"CONNECTED",null,null);
        when(store.snapshot(7,1)).thenReturn(new BrowserEventStore.Frame(view,0,"task_snapshot",clock.instant().toString()));
        AtomicLong sequence=new AtomicLong();when(store.record(anyLong(),anyString(),anyLong())).thenAnswer(invocation->new BrowserEventStore.Frame(view,sequence.incrementAndGet(),"sampling_step",clock.instant().toString()));
        GenerationWebSocketHandler handler=new GenerationWebSocketHandler(store,previews,users,JsonMapper.builder().build(),clock);
        WebSocketSession slow=session("slow",clock),fast=session("fast",clock);CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(invocation->{blocked.countDown();release.await(10,TimeUnit.SECONDS);return null;}).when(slow).sendMessage(any());
        try{
            handler.afterConnectionEstablished(slow);handler.afterConnectionEstablished(fast);
            TextMessage subscribe=new TextMessage("{\"protocolVersion\":1,\"type\":\"subscribe\",\"taskId\":\"7\"}");
            ReflectionTestUtils.invokeMethod(handler,"handleTextMessage",slow,subscribe);ReflectionTestUtils.invokeMethod(handler,"handleTextMessage",fast,subscribe);
            assertThat(blocked.await(2,TimeUnit.SECONDS)).isTrue();
            for(int index=0;index<15;index++){handler.onGenerationEvent(new GenerationEventPublisher.GenerationEvent(7,"sampling_step",index));Thread.sleep(5);}
            verify(fast,timeout(2000).atLeast(10)).sendMessage(any());verify(slow,timeout(2000)).close(new CloseStatus(1013));
        }finally{release.countDown();handler.shutdown();}
    }
    private WebSocketSession session(String id,Clock clock){
        WebSocketSession session=mock(WebSocketSession.class);when(session.getId()).thenReturn(id);when(session.getPrincipal()).thenReturn(new WsHandshake.WsPrincipal(1,clock.instant().plusSeconds(60)));return session;
    }
}
