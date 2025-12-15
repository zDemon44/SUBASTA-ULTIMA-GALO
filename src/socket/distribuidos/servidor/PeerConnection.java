package socket.distribuidos.servidor;

import socket.distribuidos.common.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class PeerConnection {
    public final int remoteId;
    public final Socket socket;
    public final ObjectOutputStream out;
    public final ObjectInputStream in;
    public volatile boolean alive = true;
    private final NodeServer owner;

    public PeerConnection(int remoteId, Socket socket, ObjectOutputStream out, ObjectInputStream in, NodeServer owner) {
        this.remoteId = remoteId;
        this.socket = socket;
        this.out = out;
        this.in = in;
        this.owner = owner;
        startReader();
    }

    private void startReader(){
        Thread t = new Thread(() -> {
            try {
                while (alive) {
                    Packet m = (Packet) in.readObject();
                    if (m == null) break;
                    owner.processServerMessage(m, this);
                }
            } catch (Exception e){
                alive = false;
                owner.onConnectionLost(remoteId);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    public synchronized void send(Packet m){
        try {
            out.writeObject(m);
            out.flush();
        } catch (Exception e){
            alive = false;
            owner.onConnectionLost(remoteId);
        }
    }

    public void close(){
        alive = false;
        try{ socket.close(); } catch(Exception e){}
    }
}
