package com.xiye.formaspace.config;

import com.xiye.formaspace.generation.browser.*;
import java.security.Principal;
import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

@Configuration
@EnableWebSocket
public class BrowserWebSocketConfig implements WebSocketConfigurer {
    private final GenerationWebSocketHandler handler;private final WsHandshake handshake;
    public BrowserWebSocketConfig(GenerationWebSocketHandler handler,WsHandshake handshake){this.handler=handler;this.handshake=handshake;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){
        registry.addHandler(handler,"/ws/generation").addInterceptors(handshake)
                .setAllowedOrigins(handshake.origins().toArray(String[]::new)).setAllowedOriginPatterns(handshake.patterns().toArray(String[]::new))
                .setHandshakeHandler(new DefaultHandshakeHandler(){
                    @Override protected Principal determineUser(ServerHttpRequest request,WebSocketHandler handler,Map<String,Object> attributes){return (Principal)attributes.get(WsHandshake.PRINCIPAL);}
                });
    }
}
