Comandos

Servidor 1
java -cp bin socket.distribuidos.servidor.NodeLauncher 1 5000 2:localhost:5001

Servidor 2
java -cp bin socket.distribuidos.servidor.NodeLauncher 2 5001 1:localhost:5000

Cliente
java -cp bin socket.distribuidos.cliente.BidClientMain 1 localhost 5000
java -cp bin socket.distribuidos.cliente.BidClientMain 2 localhost 5000
