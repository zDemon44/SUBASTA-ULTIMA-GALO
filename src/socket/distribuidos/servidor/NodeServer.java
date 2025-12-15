package socket.distribuidos.servidor;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import socket.distribuidos.common.*;

public class NodeServer {
    public final int id;
    public final int port;
    private final Map<Integer, NodeInfo> known;
    private final Map<Integer, PeerConnection> connections = new ConcurrentHashMap<>();
    private final ServerSocket serverSocket;

    private final ReentrantLock auctionLock = new ReentrantLock();
    private int highest = 0;
    private int highestBidder = -1;

    private volatile boolean auctionClosed = false;
    private ScheduledFuture<?> auctionTimer;
    private static final long AUCTION_DURATION_MILLIS = 120000; 
    private volatile long startTime = 0; 

    private volatile int coordinatorId = -1;
    private final Object coordinatorLock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final AtomicBoolean electionInProgress = new AtomicBoolean(false);

    private final List<ObjectOutputStream> clientOutputStreams = new CopyOnWriteArrayList<>();

    // >>> CAMBIO: Solo coordinador estará activo
    private volatile boolean activeServer = false;
    // <<< FIN CAMBIO

    public NodeServer(int id, int port, Map<Integer, NodeInfo> known) throws IOException {
        this.id = id; this.port = port; this.known = known;
        this.serverSocket = new ServerSocket(port);
    }

    public void start() {
        log("Servidor iniciando en puerto " + port + ", id=" + id);
        Thread t1 = new Thread(this::acceptLoop); t1.setDaemon(true); t1.start();
        Thread t2 = new Thread(this::connectToPeersLoop); t2.setDaemon(true); t2.start();

        scheduler.schedule(() -> {
            boolean superiorVivo = false;
            for (int sid : known.keySet()) {
                if (sid > id) {
                    PeerConnection c = connections.get(sid);
                    if (c != null && c.alive) superiorVivo = true;
                }
            }
            if (!superiorVivo) {
                log("No hay nodos superiores activos. Activándome.");
                announceCoordinator(id); // esto activará activeServer = true
            }
        }, 1000, TimeUnit.MILLISECONDS);

        scheduler.scheduleWithFixedDelay(this::sendHeartbeats, 2000, 2000, TimeUnit.MILLISECONDS);
        scheduler.schedule(this::initialCoordinatorCheck, 5000, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::printStatus, 10000, 10000, TimeUnit.MILLISECONDS);
    }

    private void initialCoordinatorCheck() {
        log("Comprobando coordinador...");
        boolean superiorVivo = false;
        for (int sid : known.keySet()) {
            if (sid > id) {
                PeerConnection c = connections.get(sid);
                if (c != null && c.alive) { superiorVivo = true; break; }
            }
        }
        if (!superiorVivo) {
            log("Soy el nodo con mayor ID. Me asumo como coordinador.");
            announceCoordinator(id);   // activa activeServer
        } else {
            scheduler.schedule(() -> {
                if (getCoordinator() == -1) startElection();
            }, 2000, TimeUnit.MILLISECONDS);
        }
    }

    private void acceptLoop(){
        while (true) {
            try {
                Socket s = serverSocket.accept();
                ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(s.getInputStream());
                Packet m = (Packet) in.readObject();
                
                if (esMensajeDeServidor(m)) {
                    int remote = m.fromId;
                    synchronized(connections) {
                        if (connections.containsKey(remote)) { s.close(); continue; }
                        PeerConnection sc = new PeerConnection(remote, s, out, in, this);
                        connections.put(remote, sc);
                        log("Nodo conectado: " + remote);
                        
                        if (getCoordinator() == id) {
                            sc.send(new Packet(id, PacketType.COORDINATOR, String.valueOf(id)));
                            sendSyncTo(sc);
                        }
                        processServerMessage(m, sc);
                    }
                } else {

                    // >>> CAMBIO: Solo coordinador acepta clientes
                    if (!activeServer) {
                        log("Cliente rechazado: no soy el coordinador.");
                    }
                    // <<< FIN CAMBIO

                    log("Cliente aceptado: " + m.fromId);
                    handleClientConnection(m, s, out, in);
                }
            } catch (Exception e){ }
        }
    }

    private void sendSyncTo(PeerConnection sc) {
        auctionLock.lock();
        try {
            String estado = highest + ":" + highestBidder + ":" + startTime;
            if (auctionClosed) sc.send(new Packet(id, PacketType.WINNER, estado));
            else sc.send(new Packet(id, PacketType.ANNOUNCE, estado));
        } finally { auctionLock.unlock(); }
    }

    private boolean esMensajeDeServidor(Packet m) {
        return m.type != PacketType.BID && m.type != PacketType.STATUS; 
    }

    private void handleClientConnection(Packet first, Socket s, ObjectOutputStream out, ObjectInputStream in) {
        clientOutputStreams.add(out);
        Thread t = new Thread(() -> {
            try {
                handleClientMessage(first, out);
                while (!s.isClosed()) {
                    Packet m = (Packet) in.readObject();
                    if (m == null) break;
                    handleClientMessage(m, out);
                }
            } catch (Exception e) {
                 log("Cliente tuvo un error pero NO se cerrará la conexión automáticamente.");
            } 
        });
        t.setDaemon(true); t.start();
    }

    private void connectToPeersLoop(){
        while (true){
            for (NodeInfo info : known.values()){
                if (info.id == id) continue;
                synchronized(connections) { if (connections.containsKey(info.id)) continue; }
                try{
                    Socket s = new Socket(info.host, info.port);
                    ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
                    ObjectInputStream in = new ObjectInputStream(s.getInputStream());
                    out.writeObject(new Packet(id, PacketType.HELLO, "hello")); out.flush();
                    PeerConnection sc = new PeerConnection(info.id, s, out, in, this);
                    synchronized(connections) { connections.put(info.id, sc); }
                } catch(Exception e){ }
            }
            try{ Thread.sleep(2000); } catch(Exception e){ }
        }
    }

    public void sendHeartbeats(){
        List<Integer> dead = new ArrayList<>();
        for (PeerConnection sc : connections.values()){
            if (!sc.alive) dead.add(sc.remoteId);
            else sc.send(new Packet(id, PacketType.HEARTBEAT, "hb"));
        }
        if(!dead.isEmpty()) synchronized(connections){ for(int i:dead) connections.remove(i); }
    }

    public void processServerMessage(Packet m, PeerConnection sc){
        switch(m.type){
            case HEARTBEAT: sc.send(new Packet(id, PacketType.HEARTBEAT_ACK, "ok")); break;
            case HEARTBEAT_ACK: break;
            case ELECTION:
                if (m.fromId < id) {
                    sc.send(new Packet(id, PacketType.OK, "ok"));
                    if (!electionInProgress.get()) startElection();
                }
                break;
            case COORDINATOR:
                try {
                    int nc = Integer.parseInt(m.payload);
                    int cc = getCoordinator();
                    if (nc == cc) break; 
                    if (nc > cc || cc == -1) {

                        setCoordinator(nc);

                        // >>> CAMBIO: activar/desactivar servidores
                        activeServer = (nc == id);
                        // <<< FIN CAMBIO

                        log("Coordinador asignado: " + nc);
                        if (auctionTimer != null) auctionTimer.cancel(false);
                        for(PeerConnection c : connections.values()) 
                            if(c.remoteId!=m.fromId) c.send(new Packet(id, PacketType.COORDINATOR, m.payload));
                        if(nc!=id) sincronizarEstado();
                    }
                } catch(Exception e){}
                electionInProgress.set(false);
                break;

            case ANNOUNCE:
                updateBidInfo(m.payload);
                notifyClients(new Packet(id, PacketType.ANNOUNCE, m.payload));
                if (m.fromId == getCoordinator()) {
                    for(PeerConnection c : connections.values()) 
                        if(c.remoteId!=m.fromId && c.remoteId<=10) 
                            c.send(new Packet(id, PacketType.ANNOUNCE, m.payload));
                }
                break;

            case WINNER:
                updateBidInfo(m.payload);
                auctionClosed = true;
                if(auctionTimer!=null) auctionTimer.cancel(false);
                notifyClients(new Packet(id, PacketType.WINNER, m.payload));
                log("SUBASTA CERRADA");
                break;

            case BID: handleBidFromServer(m, sc); break;
            case STATUS: if(m.fromId<=10) sendSyncTo(sc); break;
            default: break;
        }
    }

    private void updateBidInfo(String payload) {
        try {
            String[] parts = payload.split(":");
            if (parts.length >= 2) {
                int am = Integer.parseInt(parts[0]);
                int bi = Integer.parseInt(parts[1]);
                auctionLock.lock();
                try {
                    if (am > highest) { highest = am; highestBidder = bi; }
                    if (parts.length >= 3) {
                        long t = Long.parseLong(parts[2]);
                        if (startTime == 0 || (t > 0 && t < startTime)) startTime = t;
                    }
                } finally { auctionLock.unlock(); }
            }
        } catch(Exception e){}
    }
    
    public void onConnectionLost(int remoteId){
        synchronized(connections) { connections.remove(remoteId); }
        if (remoteId == getCoordinator()) startElection();
    }

    private void handleClientMessage(Packet m, ObjectOutputStream out){
        try{
            synchronized(out) {
                if (m.type == PacketType.STATUS) {
                    auctionLock.lock();
                    try {
                        String st = auctionClosed ? "CERRADA. Ganador: "+highestBidder+" ($"+highest+")" 
                                                  : highest+":"+highestBidder+":"+startTime;
                        out.writeObject(new Packet(id, PacketType.STATUS_ACK, st));
                        out.flush();
                    } finally { auctionLock.unlock(); }
                    return;
                }
                if (auctionClosed) {
                    out.writeObject(new Packet(id, PacketType.BID_ACK, "SUBASTA_CERRADA:"+highestBidder));
                    out.flush(); return;
                }
                if (m.type == PacketType.BID) {
                    int cc = getCoordinator();
                    if (cc == -1) { out.writeObject(new Packet(id, PacketType.BID_ACK, "NO_COORDINATOR")); out.flush(); return; }
                    if (cc != id) {
                        PeerConnection c = connections.get(cc);
                        if(c!=null && c.alive) {
                            c.send(new Packet(m.fromId, PacketType.BID, m.payload));
                            out.writeObject(new Packet(id, PacketType.BID_ACK, "REDIRECTED:"+cc));
                        } else {
                            out.writeObject(new Packet(id, PacketType.BID_ACK, "COORDINATOR_UNREACHABLE"));
                            startElection();
                        }
                        out.flush(); return;
                    }
                    
                    int am = Integer.parseInt(m.payload);
                    boolean ok = false;
                    auctionLock.lock();
                    try{ if(!auctionClosed && am>highest){highest=am; highestBidder=m.fromId; ok=true;} } finally{auctionLock.unlock(); }
                    
                    if(ok){
                        String pl = am+":"+m.fromId+":"+startTime;
                        log("Nueva oferta registrada: " + am + " de " + m.fromId);
                        for(PeerConnection c : connections.values()) if(c.alive && c.remoteId<=10) c.send(new Packet(id, PacketType.ANNOUNCE, pl));
                        notifyClients(new Packet(id, PacketType.ANNOUNCE, pl));
                        
                        out.writeObject(new Packet(id, PacketType.BID_ACK, "ACCEPTED:"+am));
                    } else {
                        out.writeObject(new Packet(id, PacketType.BID_ACK, "REJECTED:Max="+highest));
                    }
                    out.flush();
                }
            }
        } catch(Exception e){ }
    }

    private void handleBidFromServer(Packet m, PeerConnection sc) {
        if(auctionClosed)return;
        try{
            int am = Integer.parseInt(m.payload);
            boolean ok=false;
            auctionLock.lock();
            try{if(am>highest){highest=am; highestBidder=m.fromId; ok=true;}}finally{auctionLock.unlock();}
            if(ok){
                String pl = am+":"+m.fromId+":"+startTime;
                for(PeerConnection c:connections.values()) if(c.remoteId!=sc.remoteId && c.remoteId<=10) c.send(new Packet(id, PacketType.ANNOUNCE, pl));
                notifyClients(new Packet(id, PacketType.ANNOUNCE, pl));
                log("Oferta replicada: " + am);
            }
        }catch(Exception e){}
    }

    private void notifyClients(Packet m) {
        for(ObjectOutputStream o : clientOutputStreams) {
            try { synchronized(o) { o.writeObject(m); o.flush(); } } catch(Exception e){}
        }
    }
    
    private void startAuctionTimer() {
        if (auctionTimer != null) auctionTimer.cancel(false);
        if (auctionClosed) return;
        if (startTime == 0) startTime = System.currentTimeMillis();
        
        long elapsed = System.currentTimeMillis() - startTime;
        long remaining = AUCTION_DURATION_MILLIS - elapsed;
        
        if (remaining <= 0) finishAuction();
        else {
            log("Temporizador: " + (remaining/1000) + " s restantes.");
            auctionTimer = scheduler.schedule(this::finishAuction, remaining, TimeUnit.MILLISECONDS);
        }
    }

    private void finishAuction() {
        auctionLock.lock();
        try {
            if (auctionClosed) return;
            auctionClosed = true;
            String res = highest + ":" + highestBidder + ":" + startTime;
            log("FINALIZADA. Ganador: " + highestBidder);
            Packet w = new Packet(id, PacketType.WINNER, res);
            for(PeerConnection c : connections.values()) if(c.remoteId<=10) c.send(w);
            notifyClients(w);
        } finally { auctionLock.unlock(); }
    }

    private void sincronizarEstado() {
        int cc = getCoordinator();
        if(cc!=-1 && cc!=id) {
            PeerConnection c = connections.get(cc);
            if(c!=null) c.send(new Packet(id, PacketType.STATUS, "sync"));
        }
    }

    public void startElection(){
        if(!electionInProgress.compareAndSet(false, true)) return;
        setCoordinator(-1);
        activeServer = false; // <<<<< CAMBIO: servidor queda INACTIVO hasta que gane
        boolean sent=false;
        for(int sid:known.keySet()) if(sid>id){
            PeerConnection c=connections.get(sid);
            if(c!=null && c.alive){c.send(new Packet(id, PacketType.ELECTION, "")); sent=true;}
        }
        if(!sent) announceCoordinator(id);
        else scheduler.schedule(()->{if(getCoordinator()==-1) announceCoordinator(id); electionInProgress.set(false);}, 3000, TimeUnit.MILLISECONDS);
    }

    public void announceCoordinator(int who){
        setCoordinator(who);

        // >>> CAMBIO: Activar solo el coordinador
        activeServer = (who == id);
        // <<< FIN CAMBIO

        log("Coordinador asignado: " + who);
        if(who==id) startAuctionTimer();
        for(PeerConnection c:connections.values()) if(c.remoteId<=10) c.send(new Packet(id, PacketType.COORDINATOR, String.valueOf(who)));
        electionInProgress.set(false);
    }

    private void setCoordinator(int c){ synchronized(coordinatorLock){coordinatorId=c;} }
    private int getCoordinator(){ synchronized(coordinatorLock){return coordinatorId;} }
    private void log(String s){ System.out.println("[Nodo-"+id+"] "+s); }
    public void printStatus(){
        auctionLock.lock();
        try{
            long rest = startTime > 0 ? (AUCTION_DURATION_MILLIS - (System.currentTimeMillis() - startTime))/1000 : 60;
            if(rest < 0) rest = 0;
            log("Nodo activo:"+getCoordinator()+" | Máx:$"+highest+"(Va ganando el cliente "+highestBidder+") | Tiempo:"+rest+" s | Activo="+activeServer);
        }finally{auctionLock.unlock();}
    }
}

/**# Iniciar servidores

java -cp bin socket.distribuidos.servidor.NodeLauncher 3 5003 1:localhost:5001 2:localhost:5002

java -cp bin socket.distribuidos.servidor.NodeLauncher 2 5002 1:localhost:5001 3:localhost:5003  

java -cp bin socket.distribuidos.servidor.NodeLauncher 1 5001 2:localhost:5002 3:localhost:5003



# Probar cliente

java -cp bin socket.distribuidos.cliente.BidClientMain 101 localhost 5001

*/
