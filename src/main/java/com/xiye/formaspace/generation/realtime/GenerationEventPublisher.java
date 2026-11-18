package com.xiye.formaspace.generation.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GenerationEventPublisher {
    private final ApplicationEventPublisher events;
    public void publish(long taskId,String type,long sequence) { publish(taskId,type,sequence,false); }
    public void publish(long taskId,String type,long sequence,boolean fatal) { events.publishEvent(new GenerationEvent(taskId,type,sequence,fatal)); }
    // Internal metadata only. No provider identity, token or 5000-point payload is forwarded or logged.
    public record GenerationEvent(long taskId,String type,long sequence,boolean fatal) {
        public GenerationEvent(long taskId,String type,long sequence){this(taskId,type,sequence,false);}
    }
}
