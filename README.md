# Comandos tlf

# Servidor 1
java -cp bin socket.distribuidos.servidor.NodeLauncher 1 5000 2:localhost:5001

# Servidor 2
java -cp bin socket.distribuidos.servidor.NodeLauncher 2 5001 1:localhost:5000

# Cliente
java -cp bin socket.distribuidos.cliente.BidClientMain 1 localhost 5000
java -cp bin socket.distribuidos.cliente.BidClientMain 2 localhost 5000


# Comandos ifab

# PASO 1: Iniciar PRIMERO el nodo con ID más ALTO (será coordinador)
java -cp bin socket.distribuidos.servidor.NodeLauncher 2 5001 1:localhost:5000

# PASO 2: Esperar 3-5 segundos, luego iniciar el nodo 1
java -cp bin socket.distribuidos.servidor.NodeLauncher 1 5000 2:localhost:5001

# PASO 3: Esperar a ver en consola "Coordinador asignado: 2"

# PASO 4: Conectar clientes AL COORDINADOR (nodo 2, puerto 5001)
java -cp bin socket.distribuidos.cliente.BidClientMain 101 localhost 5001
java -cp bin socket.distribuidos.cliente.BidClientMain 102 localhost 5001

java -cp bin socket.distribuidos.servidor.NodeLauncher 2 5000 1:localhost:5001
java -cp bin socket.distribuidos.servidor.NodeLauncher 1 5001 2:localhost:5000
