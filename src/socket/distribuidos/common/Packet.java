package socket.distribuidos.common;

import java.io.Serializable;

public class Packet implements Serializable {
    private static final long serialVersionUID = 1L;
    public int fromId;
    public PacketType type;
    public String payload;

    public Packet(int fromId, PacketType type, String payload){
        this.fromId = fromId;
        this.type = type;
        this.payload = payload;
    }

    public String toString(){ return "Packet[from="+fromId+",type="+type+",p='"+payload+"']"; }
}
