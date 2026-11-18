package com.xiye.formaspace.generation.browser;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Remove query credentials before framework request/session logging and WebSocket URI construction. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+5)
public class WsQuerySanitizer extends OncePerRequestFilter {
    static final String TICKET=WsQuerySanitizer.class.getName()+".ticket";
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !(request.getContextPath()+"/ws/generation").equals(request.getRequestURI());}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        Map<String,String[]> parameters=request.getParameterMap();
        if(parameters.size()==1 && parameters.containsKey("ticket") && parameters.get("ticket").length==1)
            request.setAttribute(TICKET,parameters.get("ticket")[0]);
        HttpServletRequestWrapper sanitized=new HttpServletRequestWrapper(request){
            @Override public String getQueryString(){return null;}
            @Override public String getParameter(String name){return null;}
            @Override public String[] getParameterValues(String name){return null;}
            @Override public Map<String,String[]> getParameterMap(){return Map.of();}
            @Override public Enumeration<String> getParameterNames(){return Collections.emptyEnumeration();}
        };
        chain.doFilter(sanitized,response);
    }
}
