package socket.distribuidos.common;

public enum PacketType {
    // Mensajes entre servidores
    HELLO, HEARTBEAT, HEARTBEAT_ACK, ELECTION, OK, COORDINATOR, ANNOUNCE, ERROR,
    
    // Nuevo mensaje para el fin de la subasta
    WINNER, 
    
    // Mensajes de cliente
    BID, BID_ACK, STATUS, STATUS_ACK,
    
    // Control
    EXIT
}
