package com.example.lazyreps.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MappingDiscoveryService(private val context: Context) {
    private val DISCOVERY_PORT = 8888
    private val DISCOVERY_MSG = "MAPPING_SERVER_DISCOVERY"
    private var job: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var serverSocket: DatagramSocket? = null
    private var clientSocket: DatagramSocket? = null

    private fun getBroadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        addresses.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MappingDiscovery", "Error getting broadcast addresses: ${e.message}")
        }
        
        // Fallback or specific Hotspot subnet helper
        if (addresses.isEmpty()) {
            try {
                // Common Android Hotspot Gateway is often 192.168.43.1 -> Broadcast 192.168.43.255
                // But we can't be sure. Let's add 255.255.255.255 just in case.
                addresses.add(InetAddress.getByName("255.255.255.255"))
            } catch (e: Exception) { }
        }
        
        return addresses.distinct()
    }

    /**
     * El Servidor (Proyector) escucha anuncios UDP.
     */
    fun startServerDiscovery(onClientRequest: (address: InetAddress) -> Unit) {
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("MappingDiscoveryLock")
                multicastLock?.setReferenceCounted(true)
                multicastLock?.acquire()

                serverSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(DISCOVERY_PORT))
                    broadcast = true
                }
                val buffer = ByteArray(1024)
                
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    serverSocket?.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    
                        if (message == DISCOVERY_MSG) {
                        Log.d("MappingDiscovery", "Petición de descubrimiento recibida de ${packet.address}:${packet.port}")
                        withContext(Dispatchers.Main) {
                            onClientRequest(packet.address)
                        }
                        // Responder al cliente para confirmar presencia usando SU PUERTO
                        val response = "MAPPING_SERVER_HERE".toByteArray()
                        val responsePacket = DatagramPacket(response, response.size, packet.address, packet.port)
                        serverSocket?.send(responsePacket)
                    }
                }
            } catch (e: java.net.SocketException) {
                // Socket was likely closed, normal during shutdown
            } catch (e: Exception) {
                Log.e("MappingDiscovery", "Error en servidor de descubrimiento: ${e.message}")
            } finally {
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                }
            }
        }
    }

    /**
     * El Cliente (Celular) busca servidores enviando un broadcast.
     */
    fun findServers(onServerFound: (address: InetAddress) -> Unit, onDiscoveryFinished: () -> Unit) {
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("MappingDiscoveryClientLock")
                multicastLock?.setReferenceCounted(true)
                multicastLock?.acquire()

                clientSocket = DatagramSocket()
                clientSocket?.broadcast = true
                val message = DISCOVERY_MSG.toByteArray()
                val targetAddresses = getBroadcastAddresses().toMutableList()
                
                // Ensure generic broadcast is included for Hotspot scenarios
                try {
                    val genericBroadcast = InetAddress.getByName("255.255.255.255")
                    if (!targetAddresses.contains(genericBroadcast)) {
                        targetAddresses.add(genericBroadcast)
                    }
                } catch (e: Exception) {}

                Log.d("MappingDiscovery", "Enviando discovery a ${targetAddresses.size} direcciones...")

                val attempts = 3
                val discoveredIps = mutableSetOf<String>()

                // Try sending bursts
                launch {
                    for (i in 1..attempts) {
                        Log.d("MappingDiscovery", "Attempt $i/$attempts to send discovery packets")
                        for (addr in targetAddresses) {
                            try {
                                val packet = DatagramPacket(message, message.size, addr, DISCOVERY_PORT)
                                clientSocket?.send(packet)
                            } catch (e: Exception) {
                                Log.w("MappingDiscovery", "Failed to send to $addr: ${e.message}")
                            }
                        }
                        delay(1000) // Space out bursts
                    }
                }
                
                // Esperar respuesta (Listen for 6 seconds total)
                val buffer = ByteArray(1024)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                clientSocket?.soTimeout = 6000 
                
                val startTime = System.currentTimeMillis()
                while (isActive && (System.currentTimeMillis() - startTime < 6000)) {
                    try {
                        clientSocket?.receive(responsePacket)
                        val response = String(responsePacket.data, 0, responsePacket.length)
                        if (response == "MAPPING_SERVER_HERE") {
                            val ip = responsePacket.address.hostAddress
                            if (ip != null && discoveredIps.add(ip)) {
                                withContext(Dispatchers.Main) {
                                    onServerFound(responsePacket.address)
                                }
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        break 
                    } catch (e: java.net.SocketException) {
                        // Socket closed
                        break
                    } catch (e: Exception) {
                        Log.e("MappingDiscovery", "Error receiving: ${e.message}")
                    }
                }
                clientSocket?.close()
            } catch (e: Exception) {
                Log.e("MappingDiscovery", "Error en búsqueda de servidores: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    onDiscoveryFinished()
                }
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        serverSocket?.close()
        clientSocket?.close()
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
    }
}
