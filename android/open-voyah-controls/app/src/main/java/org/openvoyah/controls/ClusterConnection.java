package org.openvoyah.controls;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Fixed QNX launcher endpoint. A lost reply is never retried as a write. */
final class ClusterConnection implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader input;
    private final long deadline;
    private int received;
    ClusterConnection() throws IOException { this("172.16.104.41",8000,120000); }
    ClusterConnection(String host,int port,int timeout) throws IOException {
        socket=new Socket();
        try {
            socket.connect(new InetSocketAddress(host,port),3000); socket.setSoTimeout(timeout);
            deadline=System.nanoTime()+timeout*1000000L;
            input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            send("service launcher\n"); expectOk();
            send("start /bin/sh /var/cmd.sh\n"); expectOk();
            String token=UUID.randomUUID().toString().replace("-", "");
            send("printf '\\n%s%s\\n' '"+token.substring(0,16)+"' '"+token.substring(16)+"'\n");
            while(!line().equals(token)) { /* Ignore launcher banners and terminal echo. */ }
        } catch(IOException ex) { socket.close(); throw ex; }
    }
    private String line() throws IOException {
        StringBuilder value=new StringBuilder(); int c;
        while((c=input.read())!=-1) {
            if (++received>131072 || System.nanoTime()>deadline) throw new IOException("Cluster response limit exceeded");
            if(c=='\n') return value.toString().replace("\r", "");
            value.append((char)c);
        }
        throw new EOFException("Cluster connection closed; inspect status before another operation");
    }
    private void expectOk() throws IOException {
        while(true) { String text=line().trim().replaceFirst("^(?:<qconn(?:-launcher)?>\\s*)+", ""); if(text.equals("OK") || text.matches("OK [0-9]+")) return;
            if(text.startsWith("ERR") || text.startsWith("Error")) throw new IOException("QNX launcher: " + text); }
    }
    private void send(String text) throws IOException { socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush(); }
    String run(String script) throws IOException {
        String token=UUID.randomUUID().toString().replace("-", "");
        send("(\n"+script+"\n); ovc_rc=$?; printf '\\n%s%s:%s\\n' '"+token.substring(0,16)+"' '"+token.substring(16)+"' \"$ovc_rc\"\n");
        StringBuilder result=new StringBuilder();
        while(true) {
            String text=line();
            if(text.startsWith(token+":")) {
                if(!text.equals(token+":0")) throw new IOException("Cluster operation failed ("+text.substring(33)+"): "+result);
                return result.toString().trim();
            }
            result.append(text).append('\n');
        }
    }
    @Override public void close() throws IOException { socket.close(); }
}
