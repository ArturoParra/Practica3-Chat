package servidor;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Servidor {
    // Puerto por defecto
    private static final int PUERTO = 5000;
    
    // Estructura para almacenar los clientes conectados
    private static Map<String, ClienteHandler> clientesConectados = new ConcurrentHashMap<>();
    
    // Estructura para almacenar las salas de chat
    private static Map<String, Set<String>> salas = new ConcurrentHashMap<>();
    
    // Servidor socket
    private ServerSocket servidorSocket;
    
    // Constructor
    public Servidor(int puerto) {
        try {
            // Inicializar el servidor socket
            servidorSocket = new ServerSocket(puerto);
            System.out.println("Servidor iniciado en el puerto: " + puerto);
            
            // Mostrar las direcciones IP del servidor
            mostrarDireccionesIP();
            
            // Inicializar salas predeterminadas
            inicializarSalas();
            
            // Iniciar el servidor
            iniciarServidor();
        } catch (IOException e) {
            System.err.println("Error al iniciar el servidor: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // Método para mostrar las direcciones IP del servidor
    private void mostrarDireccionesIP() {
        try {
            System.out.println("\nDirecciones IP disponibles para conexión:");
            System.out.println("==========================================");
            
            // Mostrar la dirección IP local (localhost)
            System.out.println("localhost: 127.0.0.1");
            
            // Obtener todas las interfaces de red
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                // Filtrar interfaces inactivas
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                    continue;
                }
                
                // Listar las direcciones de cada interfaz
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    // Filtrar direcciones IPv6 para simplificar (mantener solo IPv4)
                    if (addr instanceof Inet4Address) {
                        System.out.println(iface.getDisplayName() + ": " + addr.getHostAddress());
                    }
                }
            }
            
            System.out.println("==========================================\n");
        } catch (SocketException e) {
            System.err.println("Error al obtener interfaces de red: " + e.getMessage());
        }
    }
    
    // Método para inicializar las salas predeterminadas
    private void inicializarSalas() {
        salas.put("Sala General", new CopyOnWriteArraySet<>());
        salas.put("Java Developers", new CopyOnWriteArraySet<>());
        salas.put("Networking", new CopyOnWriteArraySet<>());
        salas.put("ESCOM Alumnos", new CopyOnWriteArraySet<>());
        System.out.println("Salas inicializadas: " + salas.keySet());
    }
    
    // Método para iniciar el servidor y esperar conexiones
    private void iniciarServidor() {
        try {
            while (true) {
                System.out.println("Esperando conexiones...");
                Socket clienteSocket = servidorSocket.accept();
                System.out.println("Nueva conexión desde: " + clienteSocket.getInetAddress().getHostAddress());
                
                // Crear un nuevo hilo para manejar la conexión
                ClienteHandler clienteHandler = new ClienteHandler(clienteSocket);
                new Thread(clienteHandler).start();
            }
        } catch (IOException e) {
            System.err.println("Error en la conexión: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cerrarServidor();
        }
    }
    
    // Método para cerrar el servidor
    private void cerrarServidor() {
        try {
            if (servidorSocket != null && !servidorSocket.isClosed()) {
                servidorSocket.close();
                System.out.println("Servidor cerrado");
            }
        } catch (IOException e) {
            System.err.println("Error al cerrar el servidor: " + e.getMessage());
        }
    }
    
    // Método para enviar mensaje a todos los usuarios en una sala
    public static void enviarMensajeASala(String sala, String mensaje, String remitente) {
        if (salas.containsKey(sala)) {
            for (String usuario : salas.get(sala)) {
                // Enviamos el mensaje a todos incluyendo el remitente
                ClienteHandler cliente = clientesConectados.get(usuario);
                if (cliente != null) {
                    cliente.enviarMensaje("[" + sala + "] " + remitente + ": " + mensaje);
                }
            }
        }
    }
    
    // Método para enviar mensaje privado
    public static void enviarMensajePrivado(String destinatario, String mensaje, String remitente) {
        ClienteHandler remitenteHandler = clientesConectados.get(remitente);
        ClienteHandler destinatarioHandler = clientesConectados.get(destinatario);
        
        // Verificar que tanto el remitente como el destinatario existan
        if (remitenteHandler != null && destinatarioHandler != null) {
            // Enviar mensaje al destinatario
            String mensajeFormateado = "[Privado con " + remitente + "] " + remitente + ": " + mensaje;
            destinatarioHandler.enviarMensaje(mensajeFormateado);
            
            // Enviar la confirmación al remitente (para que vea su propio mensaje en la ventana privada)
            String confirmacion = "[Privado con " + destinatario + "] " + remitente + ": " + mensaje;
            remitenteHandler.enviarMensaje(confirmacion);
            
            System.out.println("Mensaje privado enviado de " + remitente + " a " + destinatario + ": " + mensaje);
        } else if (remitenteHandler != null) {
            // Notificar al remitente que el destinatario no está disponible
            remitenteHandler.enviarMensaje("Error: El usuario " + destinatario + " no está disponible.");
        }
    }
    
    // Método para notificar a todos los usuarios la lista actualizada de salas
    public static void notificarListaSalas() {
        StringBuilder listaSalas = new StringBuilder("SALAS:");
        for (String sala : salas.keySet()) {
            listaSalas.append("|").append(sala);
        }
        
        for (ClienteHandler cliente : clientesConectados.values()) {
            cliente.enviarMensaje(listaSalas.toString());
        }
    }
    
    // Método para notificar a todos los usuarios la lista actualizada de usuarios
    public static void notificarListaUsuarios() {
        StringBuilder listaUsuarios = new StringBuilder("USUARIOS:");
        for (String usuario : clientesConectados.keySet()) {
            listaUsuarios.append("|").append(usuario);
        }
        
        for (ClienteHandler cliente : clientesConectados.values()) {
            cliente.enviarMensaje(listaUsuarios.toString());
        }
    }
    
    // Método para que un usuario se una a una sala
    public static void unirseASala(String sala, String usuario) {
        if (salas.containsKey(sala)) {
            salas.get(sala).add(usuario);
            // Notificar al usuario que se unió a la sala
            ClienteHandler cliente = clientesConectados.get(usuario);
            if (cliente != null) {
                cliente.enviarMensaje("Te has unido a la sala: " + sala);
                // Notificar a los demás usuarios en la sala
                enviarMensajeASala(sala, usuario + " se ha unido a la sala.", "SERVER");
            }
        }
    }
    
    // Método para que un usuario salga de una sala
    public static void salirDeSala(String sala, String usuario) {
        if (salas.containsKey(sala)) {
            salas.get(sala).remove(usuario);
            // Notificar al usuario que salió de la sala
            ClienteHandler cliente = clientesConectados.get(usuario);
            if (cliente != null) {
                cliente.enviarMensaje("Has salido de la sala: " + sala);
                // Notificar a los demás usuarios en la sala
                enviarMensajeASala(sala, usuario + " ha salido de la sala.", "SERVER");
            }
        }
    }
    
    // Clase interna para manejar cada cliente en un hilo separado
    private static class ClienteHandler implements Runnable {
        private Socket clienteSocket;
        private PrintWriter salida;
        private BufferedReader entrada;
        private String nombreUsuario;
        private String salaActual;
        
        // Constructor
        public ClienteHandler(Socket socket) {
            this.clienteSocket = socket;
            this.salaActual = "Sala General"; // Sala por defecto
        }
        
        @Override
        public void run() {
            try {
                // Inicializar flujos de entrada y salida
                salida = new PrintWriter(clienteSocket.getOutputStream(), true);
                entrada = new BufferedReader(new InputStreamReader(clienteSocket.getInputStream()));
                
                // Solicitar nombre de usuario
                salida.println("Ingresa tu nombre de usuario:");
                nombreUsuario = entrada.readLine();
                
                // Verificar si el nombre de usuario ya existe
                while (clientesConectados.containsKey(nombreUsuario)) {
                    salida.println("El nombre de usuario ya existe. Ingresa otro nombre:");
                    nombreUsuario = entrada.readLine();
                }
                
                // Registrar el usuario
                clientesConectados.put(nombreUsuario, this);
                
                // Unir al usuario a la sala general por defecto
                unirseASala(salaActual, nombreUsuario);
                
                // Notificar al nuevo usuario la lista de salas y usuarios
                notificarListaSalas();
                notificarListaUsuarios();
                
                // Notificar a todos los usuarios que hay un nuevo usuario
                for (ClienteHandler cliente : clientesConectados.values()) {
                    if (!cliente.nombreUsuario.equals(nombreUsuario)) {
                        cliente.enviarMensaje("El usuario " + nombreUsuario + " se ha conectado.");
                    }
                }
                
                // Esperar mensajes del cliente
                String mensaje;
                while ((mensaje = entrada.readLine()) != null) {
                    procesarMensaje(mensaje);
                }
                
            } catch (IOException e) {
                System.err.println("Error en la comunicación con el cliente: " + e.getMessage());
            } finally {
                cerrarConexion();
            }
        }
        
        // Método para procesar mensajes recibidos
        private void procesarMensaje(String mensaje) {
            if (mensaje.startsWith("/privado ")) {
                // Mensaje privado: /privado nombreUsuario mensaje
                String[] partes = mensaje.split(" ", 3);
                if (partes.length >= 3) {
                    String destinatario = partes[1];
                    String contenido = partes[2];
                    
                    // Verificar si el destinatario existe
                    if (clientesConectados.containsKey(destinatario)) {
                        enviarMensajePrivado(destinatario, contenido, nombreUsuario);
                    } else {
                        enviarMensaje("Error: El usuario " + destinatario + " no está conectado.");
                    }
                } else {
                    enviarMensaje("Formato incorrecto. Uso: /privado nombreUsuario mensaje");
                }
            } else if (mensaje.startsWith("/sala ")) {
                // Cambiar de sala: /sala nombreSala
                String nuevaSala = mensaje.substring(6).trim();
                if (salas.containsKey(nuevaSala)) {
                    // Salir de la sala actual
                    salirDeSala(salaActual, nombreUsuario);
                    // Entrar a la nueva sala
                    salaActual = nuevaSala;
                    unirseASala(salaActual, nombreUsuario);
                } else {
                    enviarMensaje("La sala " + nuevaSala + " no existe.");
                }
            } else if (mensaje.startsWith("/crearsala ")) {
                // Crear una nueva sala: /crearsala nombreSala
                String nuevaSala = mensaje.substring(11).trim();
                if (!salas.containsKey(nuevaSala)) {
                    salas.put(nuevaSala, new CopyOnWriteArraySet<>());
                    enviarMensaje("Has creado la sala: " + nuevaSala);
                    notificarListaSalas();
                } else {
                    enviarMensaje("La sala " + nuevaSala + " ya existe.");
                }
            } else if (mensaje.startsWith("/ayuda")) {
                // Mostrar comandos disponibles
                enviarMensaje("Comandos disponibles:\n" +
                              "/privado nombreUsuario mensaje - Iniciar o continuar chat privado\n" +
                              "/sala nombreSala - Cambiar de sala\n" +
                              "/crearsala nombreSala - Crear una nueva sala\n" +
                              "/salas - Ver las salas disponibles\n" +
                              "/usuarios - Ver los usuarios conectados\n" +
                              "/salir - Desconectarse del servidor");
            } else if (mensaje.startsWith("/salas")) {
                // Mostrar salas disponibles
                StringBuilder listaSalas = new StringBuilder("Salas disponibles:\n");
                for (String sala : salas.keySet()) {
                    listaSalas.append("- ").append(sala).append(" (").append(salas.get(sala).size()).append(" usuarios)\n");
                }
                enviarMensaje(listaSalas.toString());
            } else if (mensaje.startsWith("/usuarios")) {
                // Mostrar usuarios conectados
                StringBuilder listaUsuarios = new StringBuilder("Usuarios conectados:\n");
                for (String usuario : clientesConectados.keySet()) {
                    listaUsuarios.append("- ").append(usuario).append("\n");
                }
                enviarMensaje(listaUsuarios.toString());
            } else if (mensaje.startsWith("/salir")) {
                // Desconectar usuario
                cerrarConexion();
            } else {
                // Mensaje normal para la sala actual
                enviarMensajeASala(salaActual, mensaje, nombreUsuario);
            }
        }
        
        // Método para enviar un mensaje al cliente
        public void enviarMensaje(String mensaje) {
            salida.println(mensaje);
        }
        
        // Método para cerrar la conexión
        private void cerrarConexion() {
            try {
                // Eliminar de las salas
                for (Set<String> usuarios : salas.values()) {
                    usuarios.remove(nombreUsuario);
                }
                
                // Eliminar de los clientes conectados
                clientesConectados.remove(nombreUsuario);
                
                // Notificar a todos los usuarios
                for (ClienteHandler cliente : clientesConectados.values()) {
                    cliente.enviarMensaje("El usuario " + nombreUsuario + " se ha desconectado.");
                }
                
                // Actualizar listas de usuarios
                notificarListaUsuarios();
                
                // Cerrar recursos
                if (salida != null) salida.close();
                if (entrada != null) entrada.close();
                if (clienteSocket != null && !clienteSocket.isClosed()) clienteSocket.close();
                
                System.out.println("Conexión cerrada para el usuario: " + nombreUsuario);
            } catch (IOException e) {
                System.err.println("Error al cerrar la conexión: " + e.getMessage());
            }
        }
    }
    
    // Método principal
    public static void main(String[] args) {
        int puerto = PUERTO;
        
        // Si se proporciona un puerto como argumento, utilizarlo
        if (args.length > 0) {
            try {
                puerto = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Formato de puerto inválido. Usando puerto por defecto: " + PUERTO);
            }
        }
        
        // Iniciar el servidor
        new Servidor(puerto);
    }
}
