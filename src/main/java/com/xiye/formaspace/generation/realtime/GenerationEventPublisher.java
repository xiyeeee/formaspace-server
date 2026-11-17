package com.xiye.formaspace.generation.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GenerationEventPublisher {
    private final ApplicationEventPublisher events;
    public void publish(long taskId,String type,long sequence) { events.publishEvent(new GenerationEvent(taskId,type,sequence)); }
    // Internal metadata only. No provider identity, token or 5000-point payload is forwarded or logged.
    public record GenerationEvent(long taskId,String type,long sequence) {}
}
