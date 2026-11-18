package com.xiye.formaspace.generation.browser;

import com.xiye.formaspace.common.exception.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class WsTicketService {
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Claims> tickets=new HashMap<>();
    public WsTicketService(Clock clock){this.clock=clock;}
    public synchronized Ticket issue(long userId,Instant jwtExpiry){
        prune();
        if(jwtExpiry==null || !jwtExpiry.isAfter(clock.instant()))throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if(tickets.size()>=10000 || tickets.values().stream().filter(value->value.userId()==userId).count()>=8)
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String ticket=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(hash(ticket),new Claims(userId,clock.instant().plusSeconds(30),jwtExpiry));
        return new Ticket(ticket,30,"/ws/generation");
    }
    public synchronized Claims consume(String ticket){
        prune();
        if(ticket==null || !ticket.matches("[A-Za-z0-9_-]{43}"))return null;
        Claims claims=tickets.remove(hash(ticket));
        return claims!=null && claims.jwtExpiry().isAfter(clock.instant())?claims:null;
    }
    private void prune(){Instant now=clock.instant();tickets.values().removeIf(value->!value.expiresAt().isAfter(now) || !value.jwtExpiry().isAfter(now));}
    private String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException exception){throw new IllegalStateException(exception);}
    }
    public record Ticket(String ticket,int expiresIn,String webSocketPath){}
    public record Claims(long userId,Instant expiresAt,Instant jwtExpiry){}
}
