package socket.distribuidos.cliente;

import java.io.*;
import java.net.*;
import java.util.*;
import socket.distribuidos.common.*;

public class BidClient {
    // Identificador único del cliente
    private final int clientId;

    // Constructor que recibe el ID del cliente
    public BidClient(int clientId){ 
        this.clientId = clientId; 
    }

    // Método principal para iniciar la interacción con el servidor
    public void startInteractive(String host, int port){
        // Scanner para leer la entrada del usuario desde consola
        Scanner sc = new Scanner(System.in);

        // Bucle infinito hasta que el usuario decida salir
        while(true){
            try{
                // Solicita al usuario ingresar una puja o escribir 'exit'
                System.out.print("Ingrese una puja (número) o 'exit': ");
                String line = sc.nextLine();

                // Si no hay entrada, se rompe el ciclo
                if (line == null) break;

                // Elimina espacios en blanco al inicio y final
                line = line.trim();

                // Si el usuario escribe 'exit', se termina el ciclo
                if (line.equalsIgnoreCase("exit")) break;

                // Se crea un socket para conectarse al servidor en el host y puerto especificados
                Socket s = new Socket(host, port);

                // Flujo de salida para enviar objetos al servidor
                ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());

                // Flujo de entrada para recibir objetos desde el servidor
                ObjectInputStream in = new ObjectInputStream(s.getInputStream());

                // Se construye un mensaje de tipo BID con el ID del cliente y la puja ingresada
                Packet bid = new Packet(clientId, PacketType.BID, line);

                // Se envía el mensaje al servidor
                out.writeObject(bid);
                out.flush();

                // Se espera la respuesta del servidor
                Packet resp = (Packet) in.readObject();

                // Se muestra la respuesta recibida en consola
                System.out.println("Respuesta: " + resp.payload);

                // Se cierra la conexión con el servidor
                s.close();
            } catch(Exception e){
                // Si ocurre un error (por ejemplo, el servidor no está disponible)
                System.out.println("Error: no fue posible contactar al servidor: " + e.getMessage());

                // Espera 2 segundos antes de intentar nuevamente
                try{ Thread.sleep(2000); } catch(Exception ex){}
            }
        }

        // Mensaje final cuando el cliente termina su ejecución
        System.out.println("Cliente terminado");
    }
}
