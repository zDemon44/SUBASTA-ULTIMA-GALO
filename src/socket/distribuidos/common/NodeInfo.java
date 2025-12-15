package socket.distribuidos.common;

public class NodeInfo {
    public int id;
    public String host;
    public int port;

    public NodeInfo(int id, String host, int port){
        this.id = id; this.host = host; this.port = port;
    }

    public String toString(){ return id+"@"+host+":"+port; }
}
