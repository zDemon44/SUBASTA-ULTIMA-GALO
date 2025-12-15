package socket.distribuidos.cliente;

import java.io.*;
import java.net.*;
import java.util.*;
import socket.distribuidos.common.*;

public class BidClientMain {
    private static final String PROMPT = "Ingrese monto ";
    
    private static int clientId;
    private static String host;
    private static int port;
    private static Socket socket;
    private static ObjectOutputStream out;
    private static ObjectInputStream in;
    private static volatile boolean connected = false;
    private static List<String> knownServers = new ArrayList<>();

    // Variables locales para el estado de la subasta
    private static volatile int currentHighest = 0;
    private static volatile int currentWinner = -1;
    private static volatile long auctionStartTime = 0;
    private static volatile boolean auctionEnded = false;

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("Uso: java BidClientMain <id_cliente> <host> <puerto>");
            return;
        }

        clientId = Integer.parseInt(args[0]);
        host = args[1];
        port = Integer.parseInt(args[2]);
        knownServers.add("localhost:5001"); knownServers.add("localhost:5002"); knownServers.add("localhost:5003");

        System.out.println("=== CLIENTE DE SUBASTA (ID " + clientId + ") ===");
        if (!conectarAServidor()) return;

        // Hilo de escucha del servidor
        Thread listener = new Thread(BidClientMain::escucharServidor);
        listener.setDaemon(true);
        listener.start();
        
        // Hilo de reloj visual (imprime alertas de tiempo)
        Thread timer = new Thread(BidClientMain::mostrarCuentaRegresiva);
        timer.setDaemon(true);
        timer.start();

        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.print(PROMPT);
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("exit")) break;
            if (input.equalsIgnoreCase("status")) { enviarComando(PacketType.STATUS, ""); continue; }
            if (input.equalsIgnoreCase("reconnect")) { reconectar(); continue; }

            try {
                int amount = Integer.parseInt(input);
                if (amount > currentHighest) enviarPuja(amount);
                else System.out.println("Oferta demasiado baja. El tope actual es $" + currentHighest);
            } catch (NumberFormatException e) {
                if(!input.isEmpty()) System.out.println("Comando no reconocido.");
            }
        }
        desconectar();
    }

    private static void escucharServidor() {
        while (true) {
            if (!connected) { try{Thread.sleep(1000);}catch(Exception e){} continue; }
            try {
                Packet msg = (Packet) in.readObject();
                if (msg == null) break;
                procesarMensajeServidor(msg);
                System.out.print(PROMPT); // Repintar prompt
            } catch (Exception e) {
                System.out.println("\nProblema con el servidor. Reintentando conexión...");
                connected = false;
                reconectar();
                continue;
            }
        }
    }

    private static void procesarMensajeServidor(Packet msg) {
        // Limpia la línea actual para que el mensaje se vea bien
        System.out.print("\r"); 

        switch (msg.type) {
            case ANNOUNCE:
                actualizarEstadoLocal(msg.payload);
                long left = calcularTiempoRestante();
                System.out.println("\nNUEVA OFERTA: De $" + currentHighest + " del cliente " + currentWinner + " (Quedan " + left + " s)");
                break;

            case WINNER:
                actualizarEstadoLocal(msg.payload);
                auctionEnded = true;
                System.out.println("\n¡SUBASTA FINALIZADA!");
                System.out.println("GANADOR: Cliente " + currentWinner + " con un monto de $" + currentHighest);
                break;

            case BID_ACK:
                if (msg.payload.startsWith("ACCEPTED")) System.out.println("Puja registrada.");
                else if (msg.payload.startsWith("REJECTED")) System.out.println("Oferta rechazada: " + msg.payload.split(":" )[1]);
                else if (msg.payload.startsWith("REDIRECTED")) System.out.println("Puja enviada");
                else if (msg.payload.startsWith("SUBASTA_CERRADA")) System.out.println("La subasta ya se cerró.");
                else System.out.println("Info: " + msg.payload);
                break;

            case STATUS_ACK:
                if (msg.payload.contains(":")) {
                    actualizarEstadoLocal(msg.payload);
                    System.out.println("Puja mas alta actual $" + currentHighest + " (" + currentWinner + ") | Tiempo: " + calcularTiempoRestante() + " s");
                } else {
                    System.out.println("ESTADO: " + msg.payload);
                }
                break;

            case ERROR:
                if (msg.payload.equals("SERVER_INACTIVE")) {
                    System.out.println("\n⚠ Este nodo NO es el coordinador. Reconectando...");
                    connected = false;
                    reconectar();
                } else {
                    System.out.println("Error: " + msg.payload);
                }
                break;

            default:
                break;
        }
    }

    private static void actualizarEstadoLocal(String payload) {
        try {
            String[] parts = payload.split(":");
            if (parts.length >= 2) {
                currentHighest = Integer.parseInt(parts[0]);
                currentWinner = Integer.parseInt(parts[1]);
            }
            if (parts.length >= 3) {
                long t = Long.parseLong(parts[2]);
                if (auctionStartTime == 0 || (t > 0 && t < auctionStartTime)) auctionStartTime = t;
            }
        } catch (Exception e) {}
    }
    
    private static long calcularTiempoRestante() {
        if (auctionStartTime == 0) return 60;
        long elapsed = System.currentTimeMillis() - auctionStartTime;
        long remaining = 120000 - elapsed;
        return remaining > 0 ? remaining / 1000 : 0;
    }

    // Muestra alertas de tiempo periódicas
    private static void mostrarCuentaRegresiva() {
        long lastPrint = 60;
        while (true) {
            try {
                Thread.sleep(1000);
                if (connected && !auctionEnded && auctionStartTime > 0) {
                    long sec = calcularTiempoRestante();
                    // Alertas a los 30, 10 y 5 segundos (para no spamear cada segundo)
                    if ((sec == 30 || sec == 10 || sec <= 5) && sec != lastPrint && sec > 0) {
                        System.out.print("\rQuedan " + sec + " segundos...     \n" + PROMPT);
                        lastPrint = sec;
                    }
                }
            } catch (Exception e) {}
        }
    }

    private static void enviarPuja(int amount) {
        enviarComando(PacketType.BID, String.valueOf(amount));
    }

    private static void enviarComando(PacketType type, String payload) {
        if (!connected) return;
        try {
            out.writeObject(new Packet(clientId, type, payload));
            out.flush();
        } catch (Exception e) { connected = false; }
    }

    private static boolean conectarAServidor() {
        if (intentarConexion(host, port)) return true;
        for (String server : knownServers) {
            String[] parts = server.split(":" );
            if (intentarConexion(parts[0], Integer.parseInt(parts[1]))) {
                host = parts[0]; port = Integer.parseInt(parts[1]);
                return true;
            }
        }
        return false;
    }

    private static boolean intentarConexion(String targetHost, int targetPort) {
        try {
            desconectar();
            socket = new Socket(targetHost, targetPort);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
            // Pedir estado inicial para sincronizar reloj
            out.writeObject(new Packet(clientId, PacketType.STATUS, "init"));
            out.flush();
            connected = true;
            return true;
        } catch (Exception e) { return false; }
    }

    private static void desconectar() {
        connected = false;
        try { if(out!=null)out.close(); if(in!=null)in.close(); if(socket!=null)socket.close(); } catch(Exception e){}
    }
    private static void reconectar() { conectarAServidor(); }
    private static void listarServidores() { for(String s:knownServers) System.out.println("Servidor conocido: " + s); }
}
