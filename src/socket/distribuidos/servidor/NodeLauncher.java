package socket.distribuidos.servidor;

import socket.distribuidos.common.*;
import java.util.*;

public class NodeLauncher {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("USO: NodeLauncher <id> <puerto> <peer1_id:host:puerto> <peer2:...>");
            return;
        }
        int id = Integer.parseInt(args[0]);
        int port = Integer.parseInt(args[1]);
        Map<Integer, NodeInfo> known = new HashMap<>();
        for (int i=2;i<args.length;i++){
            String[] p = args[i].split(":" );
            int sid = Integer.parseInt(p[0]);
            String host = p[1];
            int sport = Integer.parseInt(p[2]);
            known.put(sid, new NodeInfo(sid, host, sport));
        }
        // add self
        known.put(id, new NodeInfo(id, "localhost", port));
        NodeServer node = new NodeServer(id, port, known);
        node.start();
        // keep main alive
        Thread.currentThread().join();
    }
}
