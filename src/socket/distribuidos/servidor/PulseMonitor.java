package socket.distribuidos.servidor;

public class PulseMonitor implements Runnable {
    private final NodeServer node;
    private final int intervalMs;
    public PulseMonitor(NodeServer node, int intervalMs){
        this.node = node; this.intervalMs = intervalMs;
    }
    @Override
    public void run(){
        while (true){
            try {
                Thread.sleep(intervalMs);
                node.sendHeartbeats();
            } catch (InterruptedException e){ break; }
        }
    }
}
