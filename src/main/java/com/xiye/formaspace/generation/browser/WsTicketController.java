package com.xiye.formaspace.generation.browser;

import com.xiye.formaspace.auth.security.CurrentUser;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ws/tickets")
public class WsTicketController {
    private final WsTicketService tickets;
    private final JwtDecoder decoder;
    private final CurrentUser user;
    public WsTicketController(WsTicketService tickets,JwtDecoder decoder,CurrentUser user){this.tickets=tickets;this.decoder=decoder;this.user=user;}
    @PostMapping
    public ResponseEntity<ApiResponse<WsTicketService.Ticket>> issue(HttpServletRequest request){
        try{
            String token=new DefaultBearerTokenResolver().resolve(request);
            if(token==null)throw new BusinessException(ErrorCode.UNAUTHORIZED);
            Jwt jwt=decoder.decode(token);
            if(!Long.toString(user.id()).equals(jwt.getSubject()))throw new BusinessException(ErrorCode.UNAUTHORIZED);
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                    .body(ApiResponse.success(tickets.issue(user.id(),jwt.getExpiresAt())));
        }catch(JwtException exception){throw new BusinessException(ErrorCode.UNAUTHORIZED);}
    }
}
