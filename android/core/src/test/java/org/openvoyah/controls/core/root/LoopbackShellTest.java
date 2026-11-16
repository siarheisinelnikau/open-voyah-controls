package org.openvoyah.controls.core.root;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

public class LoopbackShellTest {
    private static final String KEY = "91".repeat(32), SERVER = "ab".repeat(16);
    private interface Peer { void run(Socket socket) throws Exception; }
    private interface Client { void run(int port) throws Exception; }
    private void exchange(Peer task, Client client) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) { socket.setSoTimeout(5000); task.run(socket); }
                catch (Throwable error) { failure.set(error); }
            });
            peer.start();
            try { client.run(server.getLocalPort()); }
            finally { peer.join(6000); }
            assertFalse("Peer must finish",peer.isAlive());
            if (failure.get()!=null) throw new AssertionError(failure.get());
        }
    }
    private static BufferedReader reader(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }
    private static void send(Socket socket,String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
    }
    private static void authenticate(Socket socket, BufferedReader input) throws Exception {
        send(socket,"OVC_ACCESS_1\r\n");
        String nonce=input.readLine(); assertTrue(nonce.matches("[a-f0-9]{32}")); assertNotEquals(KEY,nonce);
        send(socket,"OVC_SERVER " + SERVER + " " + RootChannelAuth.server(KEY,nonce) + "\r\n");
        String proof=input.readLine(); assertEquals(RootChannelAuth.client(KEY,nonce,SERVER),proof); assertNotEquals(KEY,proof);
        send(socket,"OVC_AUTHENTICATED\r\n");
    }
    private static String handshake(Socket socket,BufferedReader input) throws Exception {
        String first=input.readLine();
        var match=Pattern.compile("'(OVC_[0-9a-f]+)' '([0-9a-f]+)'").matcher(first);
        assertTrue(match.find()); String marker=match.group(1)+match.group(2);
        socket.setSoTimeout(80);
        assertThrows(SocketTimeoutException.class,input::read);
        socket.setSoTimeout(5000);
        send(socket,first+"\r\n"+marker+"_START\r\n"); return marker;
    }
    private static String command(BufferedReader input) throws Exception {
        StringBuilder body=new StringBuilder(); String line;
        while ((line=input.readLine())!=null) {
            if (line.contains("_END:%s")) break;
            body.append(line).append('\n');
        }
        return body.toString();
    }
    @Test public void authenticatesBeforeSendingLargeMultilineCommand() throws Exception {
        String script="cat <<'END'\n"+("A".repeat(768)+"\n").repeat(420)+"END\n";
        exchange(socket -> {
            BufferedReader input=reader(socket); authenticate(socket,input);
            String marker=handshake(socket,input);
            assertEquals("(\n"+script+"\n",command(input));
            socket.setSoTimeout(80);
            assertThrows("The shell must stay open until completion is received", SocketTimeoutException.class, input::read);
            socket.setSoTimeout(5000);
            send(socket,"Готово\r\n"+marker+"_END:0\r\n");
            assertNull(input.readLine());
        }, port -> assertEquals("Готово",new LoopbackShell(port,KEY).execute(script)));
    }
    @Test public void fakeServerNeverReceivesAKeyOrCommand() throws Exception {
        exchange(socket -> {
            BufferedReader input=reader(socket); send(socket,"OVC_ACCESS_1\n");
            String nonce=input.readLine(); assertEquals(32,nonce.length());
            send(socket,"OVC_SERVER "+SERVER+" "+"0".repeat(64)+"\n");
            assertNull(input.readLine());
        }, port -> assertThrows(IOException.class,()->new LoopbackShell(port,KEY).execute("sensitive operation")));
    }
    @Test public void shellFailureKeepsItsStatusAndDispatchesOnlyOnce() throws Exception {
        exchange(socket -> {
            BufferedReader input=reader(socket); String marker=handshake(socket,input);
            assertEquals("(\nexit 78\n",command(input));
            send(socket,marker+"_END:78\n");
        }, port -> assertEquals(78,assertThrows(LoopbackShell.Failed.class,()->new LoopbackShell(port,null).execute("exit 78")).status));
    }
    @Test public void incompleteResponseDoesNotRetryAnAction() throws Exception {
        exchange(socket -> {
            BufferedReader input=reader(socket); handshake(socket,input); command(input);
            send(socket,"accepted but no completion\n");
        },port -> assertThrows(IOException.class,()->new LoopbackShell(port,null).execute("one action")));
    }
    @Test public void rejectsOversizedRequestBeforeConnecting() {
        assertThrows(IOException.class,()->new LoopbackShell(1,KEY).execute("x".repeat(2*1024*1024+1)));
    }
    @Test public void challengeProofsHaveFixedIdentityAndDirection() {
        String nonce="cd".repeat(16);
        assertNotEquals(RootChannelAuth.server(KEY,nonce),RootChannelAuth.client(KEY,nonce,SERVER));
        assertFalse(RootChannelAuth.matches(RootChannelAuth.server(KEY,nonce),RootChannelAuth.server(KEY,"ef".repeat(16))));
        assertThrows(IllegalArgumentException.class,()->RootChannelAuth.client(KEY,nonce,SERVER+"00"));
    }
}
