package com.xiye.formaspace.generation.realtime;

import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

/** Callback threads only assemble bounded text and enqueue work; business processing requests the next message. */
public class PythonSocketListener implements WebSocket.Listener {
    private final int maximumBytes;
    private final BiPredicate<WebSocket,String> sink;
    private final StringBuilder fragments=new StringBuilder();
    private int bytes;
    private final AtomicBoolean disconnected;
    public PythonSocketListener(int maximumBytes,BiPredicate<WebSocket,String> sink,AtomicBoolean disconnected) {
        this.maximumBytes=maximumBytes;this.sink=sink;this.disconnected=disconnected;
    }
    @Override public void onOpen(WebSocket socket) { if (disconnected.get()) socket.abort();else socket.request(1); }
    @Override public synchronized CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last) {
        bytes+=data.toString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes>maximumBytes || disconnected.get()) { disconnected.set(true);socket.abort();return CompletableFuture.completedFuture(null); }
        fragments.append(data);
        if (last) {
            String text=fragments.toString();fragments.setLength(0);bytes=0;
            if (text.getBytes(StandardCharsets.UTF_8).length>maximumBytes || !sink.test(socket,text)) { disconnected.set(true);socket.abort(); }
        } else socket.request(1);
        return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<?> onBinary(WebSocket socket,java.nio.ByteBuffer data,boolean last) {
        disconnected.set(true);socket.abort();return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<?> onClose(WebSocket socket,int code,String reason) {
        disconnected.set(true);return CompletableFuture.completedFuture(null);
    }
    @Override public void onError(WebSocket socket,Throwable error) { disconnected.set(true); }
}
