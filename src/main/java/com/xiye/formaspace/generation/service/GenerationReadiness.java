package com.xiye.formaspace.generation.service;

import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.inference.HunyuanLocalInferenceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GenerationReadiness {
    private final GenerationProperties properties;
    private final ObjectProvider<HunyuanLocalInferenceClient> clients;
    private final ObjectProvider<com.xiye.formaspace.generation.realtime.PythonRealtimeClient> realtimeClients;

    public boolean available() {
        return switch (properties.getMode()) {
            case DISABLED -> false;
            case MOCK -> true;
            case PYTHON_REALTIME -> {
                var client=realtimeClients.getIfAvailable();
                yield client!=null && client.ready();
            }
            case HUNYUAN_LOCAL -> {
                var client=clients.getIfAvailable();
                yield client != null && client.readiness().ready();
            }
        };
    }
}
