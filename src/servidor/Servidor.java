package servidor;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.nio.file.*;

public class Servidor {
    // Puerto por defecto
    private static final int PUERTO = 5000;
    
    // Estructura para almacenar los clientes conectados
    private static Map<String, ClienteHandler> clientesConectados = new ConcurrentHashMap<>();
    
    // Estructura para almacenar las salas de chat
    private static Map<String, Set<String>> salas = new ConcurrentHashMap<>();
    
    // Estructura para almacenar las transferencias de archivos pendientes
    private static Map<String, TransferenciaArchivo> transferenciasPendientes = new ConcurrentHashMap<>();
    
    // Servidor socket para mensajes
    private ServerSocket servidorSocket;
    
    // Servidor socket para transferencia de archivos
    private ServerSocket servidorSocketArchivos;
    
    // Constantes para transferencia de archivos
    private static final String COMANDO_ARCHIVO = "/archivo";
    private static final int TAMAÑO_BUFFER = 4096;
    
    // Constructor
    public Servidor(int puerto) {
        try {
            // Inicializar el servidor socket para mensajes
            servidorSocket = new ServerSocket(puerto);
            System.out.println("Servidor iniciado en el puerto: " + puerto);
            
            // Inicializar servidor socket para transferencia de archivos
            servidorSocketArchivos = new ServerSocket(puerto + 1);
            System.out.println("Servidor de archivos iniciado en el puerto: " + (puerto + 1));
            
            // Mostrar las direcciones IP del servidor
            mostrarDireccionesIP();
            
            // Inicializar salas predeterminadas
            inicializarSalas();
            
            // Iniciar hilo para manejar transferencias de archivos
            new Thread(() -> manejarTransferenciasArchivos()).start();
            
            // Iniciar el servidor de mensajes
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
    
    // Método para manejar transferencias de archivos
    private void manejarTransferenciasArchivos() {
        try {
            while (true) {
                Socket socketArchivo = servidorSocketArchivos.accept();
                // Manejar cada transferencia en un hilo separado para no bloquear
                new Thread(() -> procesarTransferenciaArchivo(socketArchivo)).start();
            }
        } catch (IOException e) {
            System.err.println("Error en el servidor de archivos: " + e.getMessage());
        }
    }
    
    // Método para procesar una transferencia de archivo
    private void procesarTransferenciaArchivo(Socket socketArchivo) {
        try {
            // Establecer tiempo de espera para evitar bloqueos indefinidos
            socketArchivo.setSoTimeout(30000); // 30 segundos
            
            BufferedReader entrada = new BufferedReader(new InputStreamReader(socketArchivo.getInputStream()));
            String identificacion = entrada.readLine();
            
            if (identificacion == null) {
                socketArchivo.close();
                return;
            }
            
            System.out.println("Procesando identificación para transferencia: " + identificacion);
            
            // Verificar si es un envío o una recepción
            if (identificacion.contains("_RECIBIR_")) {
                // Cliente solicita recibir un archivo
                String[] partes = identificacion.split("_RECIBIR_");
                String receptor = partes[0];
                String emisor = partes[1];
                
                // Buscar la transferencia pendiente
                String clave = emisor + "_" + receptor;
                
                System.out.println("Cliente solicita recibir archivo. Clave de búsqueda: " + clave);
                
                TransferenciaArchivo transferencia = transferenciasPendientes.get(clave);
                
                if (transferencia != null) {
                    System.out.println("Transferencia encontrada, enviando al cliente " + receptor);
                    
                    // Enviar el archivo al receptor sin notificación previa
                    enviarArchivoAlCliente(socketArchivo, transferencia);
                    transferenciasPendientes.remove(clave);
                } else {
                    System.out.println("No se encontró la transferencia pendiente para la clave: " + clave);
                }
            } else {                // Cliente envía un archivo
                String emisor = identificacion;
                
                System.out.println("Cliente " + emisor + " está enviando un archivo");
                
                // Buscar todas las transferencias pendientes para este emisor
                // Obtener una lista con todas las claves de transferencias pendientes para este emisor
                List<String> clavesEmisores = transferenciasPendientes.keySet().stream()
                    .filter(k -> k.startsWith(emisor + "_"))
                    .collect(Collectors.toList());
                
                if (!clavesEmisores.isEmpty()) {
                    // Tomar la primera transferencia para procesarla
                    String clave = clavesEmisores.get(0);
                    TransferenciaArchivo transferencia = transferenciasPendientes.get(clave);
                    
                    System.out.println("Transferencia pendiente encontrada: " + clave);
                    
                    // Recibir el archivo del emisor
                    recibirArchivoDeCliente(socketArchivo, transferencia);
                      // Si es mensaje para una sala, notificar a todos los usuarios de la sala
                    if (transferencia.esParaSala()) {
                        String sala = transferencia.getDestinatario();
                        notificarArchivoASala(sala, transferencia.getNombreArchivo(), transferencia.getTamaño(), emisor);
                    } else {
                        // Notificar al destinatario que hay un archivo disponible sin pedir confirmación
                        String destinatario = transferencia.getDestinatario();
                        notificarArchivoAUsuario(destinatario, transferencia.getNombreArchivo(), transferencia.getTamaño(), emisor);
                    }
                    
                    // Eliminar la transferencia pendiente después de procesarla
                    transferenciasPendientes.remove(clave);
                } else {
                    System.out.println("No se encontró transferencia pendiente para el emisor: " + emisor);
                }
            }
        } catch (IOException e) {
            System.err.println("Error procesando transferencia de archivo: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Asegurarse de cerrar el socket de archivo en todos los casos
            try {
                if (socketArchivo != null && !socketArchivo.isClosed()) {
                    socketArchivo.close();
                }
            } catch (IOException e) {
                System.err.println("Error cerrando socket de archivo: " + e.getMessage());
            }
        }
    }
    
    // Método para recibir un archivo de un cliente
    private void recibirArchivoDeCliente(Socket socket, TransferenciaArchivo transferencia) {
        try {
            // Crear directorio temporal si no existe
            Path directorioTemp = Paths.get("temp");
            if (!Files.exists(directorioTemp)) {
                Files.createDirectory(directorioTemp);
            }
            
            // Crear archivo temporal para almacenar los datos
            String nombreArchivo = transferencia.getNombreArchivo();
            long tamaño = transferencia.getTamaño();
            
            Path archivoTemp = Paths.get("temp", transferencia.getEmisor() + "_" + nombreArchivo);
            FileOutputStream fos = new FileOutputStream(archivoTemp.toFile());
            
            // Leer datos del socket
            InputStream is = socket.getInputStream();
            byte[] buffer = new byte[TAMAÑO_BUFFER];
            int bytesLeidos;
            long totalLeido = 0;
            
            while (totalLeido < tamaño && (bytesLeidos = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesLeidos);
                totalLeido += bytesLeidos;
            }
            
            fos.close();
            
            // Actualizar la transferencia con la ruta del archivo temporal
            transferencia.setRutaArchivo(archivoTemp.toString());
            
            System.out.println("Archivo recibido y almacenado temporalmente: " + archivoTemp);
        } catch (IOException e) {
            System.err.println("Error al recibir archivo: " + e.getMessage());
        }
    }
    
    // Método para enviar un archivo a un cliente
    private void enviarArchivoAlCliente(Socket socket, TransferenciaArchivo transferencia) {
        try {
            // Verificar que el archivo existe
            Path archivoTemp = Paths.get(transferencia.getRutaArchivo());
            if (!Files.exists(archivoTemp)) {
                System.err.println("Archivo no encontrado: " + archivoTemp);
                return;
            }
            
            // Leer archivo y enviarlo por el socket
            FileInputStream fis = new FileInputStream(archivoTemp.toFile());
            OutputStream os = socket.getOutputStream();
            
            byte[] buffer = new byte[TAMAÑO_BUFFER];
            int bytesLeidos;
            
            while ((bytesLeidos = fis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesLeidos);
            }
            
            os.flush();
            fis.close();
            
            System.out.println("Archivo enviado al cliente: " + transferencia.getDestinatario());
            
            // Eliminar archivo temporal después de enviarlo
            Files.deleteIfExists(archivoTemp);
        } catch (IOException e) {
            System.err.println("Error al enviar archivo al cliente: " + e.getMessage());
        }
    }
    
    // Método para notificar a un usuario que hay un archivo disponible
    private void notificarArchivoAUsuario(String usuario, String nombreArchivo, long tamaño, String remitente) {
        ClienteHandler destinatario = clientesConectados.get(usuario);
        if (destinatario != null) {
            // Enviar la notificación sin solicitar confirmación
            destinatario.enviarMensaje("ARCHIVO:" + remitente + ":" + nombreArchivo + ":" + tamaño);
        }
    }
    
    // Método para notificar a todos los usuarios de una sala que hay un archivo disponible
    private void notificarArchivoASala(String sala, String nombreArchivo, long tamaño, String remitente) {
        if (salas.containsKey(sala)) {
            for (String usuario : salas.get(sala)) {
                // No notificar al remitente
                if (!usuario.equals(remitente)) {
                    ClienteHandler cliente = clientesConectados.get(usuario);
                    if (cliente != null) {
                        // Enviar la notificación sin solicitar confirmación
                        cliente.enviarMensaje("ARCHIVO:" + remitente + ":" + nombreArchivo + ":" + tamaño);
                    }
                }
            }
        }
    }
      // Método para inicializar las salas predeterminadas
    private void inicializarSalas() {
        salas.put("Sala-General", new CopyOnWriteArraySet<>());
        salas.put("Java-Developers", new CopyOnWriteArraySet<>());
        salas.put("Networking", new CopyOnWriteArraySet<>());
        salas.put("ESCOM-Alumnos", new CopyOnWriteArraySet<>());
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
            if (servidorSocketArchivos != null && !servidorSocketArchivos.isClosed()) {
                servidorSocketArchivos.close();
                System.out.println("Servidor de archivos cerrado");
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
            this.salaActual = "Sala-General"; // Sala por defecto
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
            try {
                if (mensaje.startsWith(COMANDO_ARCHIVO)) {
                    System.out.println("Procesando comando de archivo: " + mensaje);
                    // Formato esperado: /archivo "destinatario" nombreArchivo tamaño
                    // Extraemos el comando primero
                    String mensajeSinComando = mensaje.substring(COMANDO_ARCHIVO.length()).trim();
                    
                    // Verificamos si el destinatario está entre comillas para manejar nombres con espacios
                    String destinatario;
                    String restoMensaje;
                    
                    if (mensajeSinComando.startsWith("\"")) {
                        // El destinatario está entre comillas
                        int finComillas = mensajeSinComando.indexOf("\"", 1);
                        if (finComillas > 0) {
                            destinatario = mensajeSinComando.substring(1, finComillas);
                            restoMensaje = mensajeSinComando.substring(finComillas + 1).trim();
                        } else {
                            enviarMensaje("Error: Formato incorrecto. El destinatario debe estar entre comillas.");
                            return;
                        }
                    } else {
                        // Formato antiguo - intentamos separar por el primer espacio
                        int primerEspacio = mensajeSinComando.indexOf(" ");
                        if (primerEspacio <= 0) {
                            enviarMensaje("Error: Formato incorrecto. Uso: /archivo \"destinatario\" nombreArchivo tamaño");
                            return;
                        }
                        destinatario = mensajeSinComando.substring(0, primerEspacio);
                        restoMensaje = mensajeSinComando.substring(primerEspacio + 1).trim();
                    }
                    
                    // Buscamos la última ocurrencia de espacio para separar el tamaño
                    int ultimoEspacio = restoMensaje.lastIndexOf(" ");
                    
                    if (ultimoEspacio > 0 && ultimoEspacio < restoMensaje.length() - 1) {
                        // Obtenemos la cadena del tamaño (último parámetro)
                        String tamañoStr = restoMensaje.substring(ultimoEspacio + 1);
                        
                        // El nombre del archivo es todo lo que hay antes del tamaño
                        String nombreArchivo = restoMensaje.substring(0, ultimoEspacio);
                        
                        long tamaño;
                        try {
                            tamaño = Long.parseLong(tamañoStr);
                        } catch (NumberFormatException e) {
                            System.err.println("Error al analizar el tamaño del archivo: " + e.getMessage());
                            enviarMensaje("Error: Formato de tamaño de archivo incorrecto.");
                            return;
                        }
                        
                        // Comprobar si el destinatario existe (sea una sala o un usuario)
                        if (!salas.containsKey(destinatario) && !clientesConectados.containsKey(destinatario)) {
                            enviarMensaje("Error: El destinatario '" + destinatario + "' no existe.");
                            return;
                        }
                        
                        // Crear objeto de transferencia
                        TransferenciaArchivo transferencia = new TransferenciaArchivo(
                            nombreUsuario, destinatario, nombreArchivo, tamaño);
                        
                        // Guardar la transferencia pendiente
                        String clave = nombreUsuario + "_" + destinatario;
                        transferenciasPendientes.put(clave, transferencia);
                        
                        System.out.println("Nueva transferencia pendiente: " + transferencia);
                        enviarMensaje("Preparando transferencia de archivo: " + nombreArchivo);
                    } else {
                        enviarMensaje("Error: Formato incorrecto para el comando de archivo.");
                        System.err.println("Formato incorrecto para el comando de archivo: " + mensaje);
                    }
                } else if (mensaje.startsWith("/privado ")) {
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
                    }                } else if (mensaje.startsWith("/crearsala ")) {
                    // Crear una nueva sala: /crearsala nombreSala
                    String nuevaSala = mensaje.substring(11).trim();
                    
                    // Validar que el nombre no contenga espacios
                    if (nuevaSala.contains(" ")) {
                        enviarMensaje("Error: El nombre de la sala no debe contener espacios. Usa guiones (-) en lugar de espacios.");
                        return;
                    }
                    
                    if (!salas.containsKey(nuevaSala)) {
                        salas.put(nuevaSala, new CopyOnWriteArraySet<>());
                        enviarMensaje("Has creado la sala: " + nuevaSala);
                        notificarListaSalas();
                    } else {
                        enviarMensaje("La sala " + nuevaSala + " ya existe.");
                    }                } else if (mensaje.startsWith("/ayuda")) {
                    // Mostrar comandos disponibles
                    enviarMensaje("Comandos disponibles:\n" +
                                "/privado nombreUsuario mensaje - Iniciar o continuar chat privado\n" +
                                "/sala nombreSala - Cambiar de sala\n" +
                                "/crearsala nombreSala - Crear una nueva sala (usa guiones en lugar de espacios, ej: Mi-Sala)\n" +
                                "/salas - Ver las salas disponibles\n" +
                                "/usuarios - Ver los usuarios conectados\n" +
                                "/salir - Desconectarse del servidor\n" +
                                "/archivo destinatario nombreArchivo tamaño - Enviar un archivo");
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
            } catch (Exception e) {
                System.err.println("Error al procesar mensaje del cliente " + nombreUsuario + ": " + e.getMessage());
                e.printStackTrace();
                try {
                    // En caso de error, notificar al cliente y continuar atendiendo mensajes
                    enviarMensaje("Error al procesar tu solicitud. Por favor, inténtalo de nuevo.");
                } catch (Exception ex) {
                    // Si no podemos enviar mensajes, la conexión probablemente está rota
                    cerrarConexion();
                }
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
    
    // Clase para representar una transferencia de archivo
    private static class TransferenciaArchivo {
        private String emisor;
        private String destinatario;
        private String nombreArchivo;
        private long tamaño;
        private String rutaArchivo;
        
        public TransferenciaArchivo(String emisor, String destinatario, String nombreArchivo, long tamaño) {
            this.emisor = emisor;
            this.destinatario = destinatario;
            this.nombreArchivo = nombreArchivo;
            this.tamaño = tamaño;
        }
        
        public String getEmisor() {
            return emisor;
        }
        
        public String getDestinatario() {
            return destinatario;
        }
        
        public String getNombreArchivo() {
            return nombreArchivo;
        }
        
        public long getTamaño() {
            return tamaño;
        }
        
        public String getRutaArchivo() {
            return rutaArchivo;
        }
        
        public void setRutaArchivo(String rutaArchivo) {
            this.rutaArchivo = rutaArchivo;
        }
        
        public boolean esParaSala() {
            // Verificar si el destinatario es una sala (no un usuario)
            return salas.containsKey(destinatario);
        }
        
        @Override
        public String toString() {
            return "TransferenciaArchivo{" +
                   "emisor='" + emisor + '\'' +
                   ", destinatario='" + destinatario + '\'' +
                   ", nombreArchivo='" + nombreArchivo + '\'' +
                   ", tamaño=" + tamaño +
                   ", rutaArchivo='" + rutaArchivo + '\'' +
                   '}';
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
