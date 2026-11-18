package com.xiye.formaspace.generation.browser;

import com.xiye.formaspace.common.enums.UserStatus;
import com.xiye.formaspace.config.CorsProperties;
import com.xiye.formaspace.user.entity.UserEntity;
import com.xiye.formaspace.user.mapper.UserMapper;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class WsHandshake implements HandshakeInterceptor {
    public static final String PRINCIPAL=WsHandshake.class.getName()+".principal";
    private final WsTicketService tickets;private final UserMapper users;private final CorsProperties cors;private final Environment environment;
    public WsHandshake(WsTicketService tickets,UserMapper users,CorsProperties cors,Environment environment){this.tickets=tickets;this.users=users;this.cors=cors;this.environment=environment;}
    public List<String> patterns(){return environment.matchesProfiles("local")&&!environment.matchesProfiles("prod")
            ?List.of("http://localhost:[*]","https://localhost:[*]","http://127.0.0.1:[*]","https://127.0.0.1:[*]"):List.of();}
    public List<String> origins(){return cors.getAllowedOrigins();}
    public boolean allows(String origin){
        if(origin==null)return false;
        try{URI uri=URI.create(origin);if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !uri.getPath().isEmpty())return false;}
        catch(RuntimeException exception){return false;}
        CorsConfiguration configuration=new CorsConfiguration();configuration.setAllowedOrigins(origins());configuration.setAllowedOriginPatterns(patterns());
        return configuration.checkOrigin(origin)!=null;
    }
    @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes){
        if(!allows(request.getHeaders().getOrigin())){response.setStatusCode(HttpStatus.FORBIDDEN);return false;}
        if(!(request instanceof ServletServerHttpRequest servlet)){response.setStatusCode(HttpStatus.UNAUTHORIZED);return false;}
        Object raw=servlet.getServletRequest().getAttribute(WsQuerySanitizer.TICKET);
        servlet.getServletRequest().removeAttribute(WsQuerySanitizer.TICKET);
        WsTicketService.Claims claims=tickets.consume(raw instanceof String value?value:null);
        if(claims==null){response.setStatusCode(HttpStatus.UNAUTHORIZED);return false;}
        try{
            UserEntity user=users.selectById(claims.userId());
            if(user==null || user.getStatus()!=UserStatus.ACTIVE){response.setStatusCode(HttpStatus.UNAUTHORIZED);return false;}
        }catch(RuntimeException exception){response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);return false;}
        attributes.put(PRINCIPAL,new WsPrincipal(claims.userId(),claims.jwtExpiry()));return true;
    }
    @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception exception){}
    public record WsPrincipal(long userId,Instant jwtExpiry) implements Principal {
        @Override public String getName(){return Long.toString(userId);}
    }
}
