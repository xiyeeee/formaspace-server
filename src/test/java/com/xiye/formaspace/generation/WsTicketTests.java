package com.xiye.formaspace.generation;

import com.xiye.formaspace.generation.browser.WsTicketService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WsTicketTests {
    private static class MutableClock extends Clock {
        private Instant now=Instant.parse("2026-11-17T00:00:00Z");
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
    @Test void randomOneUseTicketExpiresAndDoesNotExtendJwt(){
        MutableClock clock=new MutableClock();WsTicketService service=new WsTicketService(clock);
        WsTicketService.Ticket ticket=service.issue(7,clock.instant().plusSeconds(100));
        assertThat(Base64.getUrlDecoder().decode(ticket.ticket())).hasSize(32);
        assertThat(service.consume(ticket.ticket()).userId()).isEqualTo(7);assertThat(service.consume(ticket.ticket())).isNull();
        WsTicketService.Ticket expired=service.issue(7,clock.instant().plusSeconds(100));clock.now=clock.now.plusSeconds(30);
        assertThat(service.consume(expired.ticket())).isNull();
        WsTicketService.Ticket shortJwt=service.issue(7,clock.instant().plusSeconds(1));clock.now=clock.now.plusSeconds(2);
        assertThat(service.consume(shortJwt.ticket())).isNull();assertThat(service.consume("not-a-ticket")).isNull();
    }
    @Test void concurrentConsumptionHasExactlyOneWinner()throws Exception{
        MutableClock clock=new MutableClock();WsTicketService service=new WsTicketService(clock);
        String ticket=service.issue(7,clock.instant().plusSeconds(100)).ticket();ExecutorService pool=Executors.newFixedThreadPool(8);
        try{
            List<Callable<Boolean>> jobs=new ArrayList<>();for(int i=0;i<32;i++)jobs.add(()->service.consume(ticket)!=null);
            int winners=0;for(Future<Boolean> result:pool.invokeAll(jobs))if(result.get())winners++;
            assertThat(winners).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void unusedTicketsHavePerUserCapacityAndExpiredTicketsAreReclaimed(){
        MutableClock clock=new MutableClock();WsTicketService service=new WsTicketService(clock);
        for(int i=0;i<8;i++)service.issue(7,clock.instant().plusSeconds(100));
        assertThatThrownBy(()->service.issue(7,clock.instant().plusSeconds(100))).isInstanceOf(com.xiye.formaspace.common.exception.BusinessException.class);
        clock.now=clock.now.plusSeconds(31);assertThat(service.issue(7,clock.instant().plusSeconds(100))).isNotNull();
    }
}
