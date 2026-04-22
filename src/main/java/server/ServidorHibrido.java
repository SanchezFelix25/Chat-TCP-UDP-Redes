
package server;

/**
 *
 * @author armandorayos
 */
import java.io.*;
import java.net.*;
import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ServidorHibrido {
    
    private static final int MAX_CLIENTES =5;
    private static Map<String, PrintWriter> mapaTCP = Collections.synchronizedMap(new HashMap<>());
    private static Map<String, String> mapaUDP = Collections.synchronizedMap(new HashMap<>()); 
    private static DatagramSocket udpSocketGlobal;

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.print("Puerto del servidor: ");
        int puerto = sc.nextInt();

        new Thread(() -> iniciarTCP(puerto)).start();
        new Thread(() -> iniciarUDP(puerto)).start();

        System.out.println("Servidor Chat Universal iniciado en puerto " + puerto);
    }

    private static String getFechaFormateada() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a"));
    }
    
    private static boolean hayCupo(){
        return mapaTCP.size() + mapaUDP.size() < MAX_CLIENTES;
    }

    private static boolean nombreExiste(String nombre) {
        return mapaTCP.containsKey(nombre) || mapaUDP.containsValue(nombre);
    }
    private static void enviarMensajePrivado(String remitente, String destinatario, String mensaje) {
        String formato = "[PRIVADO de " + remitente + "] " + getFechaFormateada() + ":\n" + mensaje;

        // Buscar en TCP
        PrintWriter outTCP = mapaTCP.get(destinatario);
        if (outTCP != null) {
            outTCP.println(formato);
            System.out.println("Mensaje privado enviado de " + remitente + " a " + destinatario);
            return;
        }

        // Buscar en UDP
        synchronized (mapaUDP) {
            for (Map.Entry<String, String> entry : mapaUDP.entrySet()) {
                if (entry.getValue().equals(destinatario)) {
                    try {
                        String[] partes = entry.getKey().split(":");
                        InetAddress addr = InetAddress.getByName(partes[0]);
                        int port = Integer.parseInt(partes[1]);
                        byte[] data = formato.getBytes();
                        udpSocketGlobal.send(new DatagramPacket(data, data.length, addr, port));
                        System.out.println("Mensaje privado enviado de " + remitente + " a " + destinatario);
                        return;
                    } catch (IOException e) {
                        System.out.println("Error enviando privado UDP");
                    }
                }
            }
        }

        // Si no se encontró el destinatario
        PrintWriter remitenteOut = mapaTCP.get(remitente);
        if (remitenteOut != null) {
            remitenteOut.println("ERROR: Usuario '" + destinatario + "' no encontrado o desconectado.");
        }
    }
    
    
    private static void enviarATodos(String msg) {
        synchronized (mapaTCP) {
            Iterator<Map.Entry<String, PrintWriter>> it = mapaTCP.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, PrintWriter> entry = it.next();
                entry.getValue().println(msg);
            }
        }
        synchronized (mapaUDP) {
            byte[] data = msg.getBytes();
            for (String id : mapaUDP.keySet()) {
                try {
                    String[] partes = id.split(":");
                    InetAddress addr = InetAddress.getByName(partes[0]);
                    int port = Integer.parseInt(partes[1]);
                    udpSocketGlobal.send(new DatagramPacket(data, data.length, addr, port));
                } catch (IOException e) { }
            }
        }
    }

    public static void iniciarTCP(int puerto) {
        try (ServerSocket serverSocket = new ServerSocket(puerto)) {
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> manejarClienteTCP(socket)).start();
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private static void manejarClienteTCP(Socket socket) {
        String nombre = "";
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            
            if(!hayCupo()){
                System.out.println("Error: Servidor lleno. Maximo 5 Clientes Permitidos");
                socket.close();
                return;
            }
            
            nombre = in.readLine();
            if (nombre == null || nombreExiste(nombre)) {
                out.println("ERROR: El nombre de usuario ya existe o es invalido.");
                socket.close();
                return;
            }

            out.println("OK");
            mapaTCP.put(nombre, out);
            System.out.println("[TCP] " + nombre + " conectado. (" + (mapaTCP.size() + mapaUDP.size()) + "/" + MAX_CLIENTES + ")");

            String msg;
            while ((msg = in.readLine()) != null) {
                if (msg.equalsIgnoreCase("exit")) break;
                
                // === NUEVO: DETECTAR COMANDO PRIVADO ===
                if (msg.startsWith("/priv ")) {
                    String[] partes = msg.substring(6).trim().split(" ", 2);
                    if (partes.length == 2) {
                        String destinatario = partes[0];
                        String mensajePrivado = partes[1];
                        enviarMensajePrivado(nombre, destinatario, mensajePrivado);
                        continue; // No enviar al broadcast
                    }
                }
                
                // Mensaje normal
                String formato = nombre + " " + getFechaFormateada() + ":\n" + msg;
                System.out.println(formato);
                enviarATodos(formato);
            }
        } catch (IOException e) { 
        } finally {
            if (nombre != null && !nombre.isEmpty()) {
                mapaTCP.remove(nombre);
                System.out.println("[TCP] Usuario " + nombre + " fuera.");
            }
        }
    }

    public static void iniciarUDP(int puerto) {
        try {
            udpSocketGlobal = new DatagramSocket(puerto);
            byte[] buffer = new byte[1024];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocketGlobal.receive(packet);

                String idCliente = packet.getAddress().getHostAddress() + ":" + packet.getPort();
                String recibido = new String(packet.getData(), 0, packet.getLength()).trim();

                if (recibido.startsWith("JOIN:")) {
                    String nombreSolicitado = recibido.substring(5);

                    if (!hayCupo()) {
                        byte[] err = "ERROR: Servidor lleno. Máximo 5 clientes permitidos.".getBytes();
                        udpSocketGlobal.send(new DatagramPacket(err, err.length, packet.getAddress(), packet.getPort()));
                        continue;
                    }

                    if (nombreExiste(nombreSolicitado)) {
                        byte[] err = "ERROR: Nombre ocupado".getBytes();
                        udpSocketGlobal.send(new DatagramPacket(err, err.length, packet.getAddress(), packet.getPort()));
                    } else {
                        mapaUDP.put(idCliente, nombreSolicitado);
                        byte[] ok = "OK".getBytes();
                        udpSocketGlobal.send(new DatagramPacket(ok, ok.length, packet.getAddress(), packet.getPort()));
                        System.out.println("[UDP] " + nombreSolicitado + " conectado. (" + (mapaTCP.size() + mapaUDP.size()) + "/" + MAX_CLIENTES + ")");
                    }
                } 
                else if (recibido.endsWith(":exit")) {
                    mapaUDP.remove(idCliente);
                } 
                else {
                    String nombreUdp = mapaUDP.get(idCliente);
                    if (nombreUdp != null) {

                        // === NUEVO: DETECTAR COMANDO PRIVADO en UDP ===
                        if (recibido.startsWith("/priv ")) {
                            String[] partes = recibido.substring(6).trim().split(" ", 2);
                            if (partes.length == 2) {
                                String destinatario = partes[0];
                                String mensajePrivado = partes[1];
                                enviarMensajePrivado(nombreUdp, destinatario, mensajePrivado);
                                continue;
                            }
                        }

                        // Mensaje normal (broadcast)
                        String formato = nombreUdp + " " + getFechaFormateada() + ":\n" + recibido;
                        System.out.println(formato);
                        enviarATodos(formato);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}