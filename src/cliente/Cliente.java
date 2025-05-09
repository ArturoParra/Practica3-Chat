package cliente;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.border.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;
import java.nio.file.*;

public class Cliente extends JFrame {
    
    // Componentes de la interfaz gráfica
    private JPanel panelPrincipal;
    private JPanel panelLateral;
    private JPanel panelSalas;
    private JPanel panelUsuarios;
    private JPanel panelChat;
    private JTextArea areaMensajes;
    private JTextField campoMensaje;
    private JButton botonEnviar;
    private JList<String> listaSalas;
    private JList<String> listaUsuarios;
    private DefaultListModel<String> modeloSalas;
    private DefaultListModel<String> modeloUsuarios;
    
    // Componentes de red
    private Socket socket;    private PrintWriter salida;
    private BufferedReader entrada;
    private boolean conectado = false;
    private String nombreUsuario;
    private String salaActual = "Sala-General";
    
    // Constantes y variables de conexión
    private static String HOST = "localhost"; // Cambiado a variable no final
    private static final int PUERTO_DEFECTO = 5000;
    private int puerto = PUERTO_DEFECTO; // Variable de instancia para el puerto
    
    // Constantes para envío de archivos
    private static final String COMANDO_ARCHIVO = "/archivo";
    private static final int TAMAÑO_BUFFER = 4096;
    
    // Constructor
    public Cliente() {
        this(HOST, PUERTO_DEFECTO);
    }
      // Constructor con parámetros
    public Cliente(String host, int puerto) {
        // Configuración básica de la ventana
        super("Cliente de Chat");
        Cliente.HOST = host;
        this.puerto = puerto; // Guardamos el puerto recibido
        
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        
        // Inicializar componentes
        inicializarComponentes();
        
        // Diseño general
        setLayout(new BorderLayout());
        add(panelLateral, BorderLayout.WEST);
        add(panelChat, BorderLayout.CENTER);
        
        // Conectar al servidor
        conectarAlServidor();
        
        // Agregar evento de cierre para desconectar del servidor
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                desconectar();
            }
        });
        
        // Hacer visible la ventana
        setVisible(true);
    }
    
    // Método para inicializar todos los componentes
    private void inicializarComponentes() {
        // Panel lateral (izquierda)
        panelLateral = new JPanel();
        panelLateral.setLayout(new GridLayout(2, 1));
        panelLateral.setPreferredSize(new Dimension(200, getHeight()));
        
        // Panel de salas (parte superior del panel lateral)
        panelSalas = new JPanel(new BorderLayout());
        panelSalas.setBorder(BorderFactory.createTitledBorder("Salas de Chat"));
        modeloSalas = new DefaultListModel<>();
        listaSalas = new JList<>(modeloSalas);
        listaSalas.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        // Agregar listener para cambiar de sala
        listaSalas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int index = listaSalas.locationToIndex(e.getPoint());
                    if (index >= 0) {
                        String salaNueva = listaSalas.getModel().getElementAt(index);
                        cambiarSala(salaNueva);
                    }
                }
            }
        });
        
        JScrollPane scrollSalas = new JScrollPane(listaSalas);
        panelSalas.add(scrollSalas, BorderLayout.CENTER);
          // Botón para crear salas
        JButton botonCrearSala = new JButton("Crear Sala");
        botonCrearSala.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String mensaje = "Ingrese nombre de la nueva sala:\n(No use espacios, use guiones (-) para separar palabras)";
                String nombreSala = JOptionPane.showInputDialog(Cliente.this, 
                    mensaje, "Crear Sala", JOptionPane.PLAIN_MESSAGE);
                
                if (nombreSala != null && !nombreSala.trim().isEmpty()) {
                    // Validar que no contenga espacios
                    if (nombreSala.contains(" ")) {
                        JOptionPane.showMessageDialog(Cliente.this,
                            "El nombre de la sala no debe contener espacios.\nUsa guiones (-) en lugar de espacios.",
                            "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    
                    enviarMensajeAlServidor("/crearsala " + nombreSala);
                }
            }
        });
        
        JPanel panelBotonesSala = new JPanel();
        panelBotonesSala.add(botonCrearSala);
        panelSalas.add(panelBotonesSala, BorderLayout.SOUTH);
        
        // Panel de usuarios (parte inferior del panel lateral)
        panelUsuarios = new JPanel(new BorderLayout());
        panelUsuarios.setBorder(BorderFactory.createTitledBorder("Usuarios en línea"));
        modeloUsuarios = new DefaultListModel<>();
        listaUsuarios = new JList<>(modeloUsuarios);
        listaUsuarios.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        // Agregar listener para iniciar chats privados
        listaUsuarios.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int index = listaUsuarios.locationToIndex(e.getPoint());
                    if (index >= 0) {
                        String destinatario = listaUsuarios.getModel().getElementAt(index);
                        if (!destinatario.equals(nombreUsuario)) {
                            // Mostrar ventana de diálogo para mensaje privado
                            mostrarVentanaMensajePrivado(destinatario);
                        }
                    }
                }
            }
        });
        
        JScrollPane scrollUsuarios = new JScrollPane(listaUsuarios);
        panelUsuarios.add(scrollUsuarios, BorderLayout.CENTER);
        
        // Agregar paneles al panel lateral
        panelLateral.add(panelSalas);
        panelLateral.add(panelUsuarios);
        
        // Panel de chat (derecha)
        panelChat = new JPanel();
        panelChat.setLayout(new BorderLayout());
        panelChat.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // Área de mensajes
        areaMensajes = new JTextArea();
        areaMensajes.setEditable(false);
        areaMensajes.setLineWrap(true);
        areaMensajes.setWrapStyleWord(true);
        JScrollPane scrollMensajes = new JScrollPane(areaMensajes);
        scrollMensajes.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Mensajes"),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        
        // Panel para enviar mensajes
        JPanel panelEnvio = new JPanel(new BorderLayout());
        campoMensaje = new JTextField();
        
        // Panel de botones
        JPanel panelBotones = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        // Botón para adjuntar archivos
        JButton botonAdjuntar = new JButton("Adjuntar");
        botonAdjuntar.setToolTipText("Enviar un archivo a la sala actual");
        botonAdjuntar.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                enviarArchivo();
            }
        });
        
        botonEnviar = new JButton("Enviar");
        botonEnviar.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                enviarMensaje();
            }
        });
        
        // Añadir acción para enviar con Enter
        campoMensaje.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    enviarMensaje();
                }
            }
        });
        
        // Agregar botones al panel de botones
        panelBotones.add(botonAdjuntar);
        panelBotones.add(botonEnviar);
        
        panelEnvio.add(campoMensaje, BorderLayout.CENTER);
        panelEnvio.add(panelBotones, BorderLayout.EAST);
        panelEnvio.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        
        // Añadir componentes al panel de chat
        panelChat.add(scrollMensajes, BorderLayout.CENTER);
        panelChat.add(panelEnvio, BorderLayout.SOUTH);
    }
    
    // Método para mostrar ventana de mensaje privado
    private void mostrarVentanaMensajePrivado(String destinatario) {
        // Crear el panel para el mensaje
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        
        // Crear el área de texto para el mensaje
        JTextArea textArea = new JTextArea(5, 30);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(textArea);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        // Título con el nombre del destinatario
        JLabel label = new JLabel("Mensaje privado para " + destinatario + ":");
        panel.add(label, BorderLayout.NORTH);
        
        // Panel para agregar botón de adjuntar archivo
        JPanel panelOpciones = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton botonAdjuntar = new JButton("Adjuntar archivo");
        
        final File[] archivoSeleccionado = new File[1]; // Array para almacenar la referencia
        
        botonAdjuntar.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser fileChooser = new JFileChooser();
                int resultado = fileChooser.showOpenDialog(panel);
                if (resultado == JFileChooser.APPROVE_OPTION) {
                    archivoSeleccionado[0] = fileChooser.getSelectedFile();
                    botonAdjuntar.setText("Archivo: " + archivoSeleccionado[0].getName());
                }
            }
        });
        
        panelOpciones.add(botonAdjuntar);
        panel.add(panelOpciones, BorderLayout.SOUTH);
        
        // Mostrar el panel en un diálogo
        int resultado = JOptionPane.showConfirmDialog(
            this, 
            panel, 
            "Enviar mensaje privado a " + destinatario, 
            JOptionPane.OK_CANCEL_OPTION, 
            JOptionPane.PLAIN_MESSAGE
        );
        
        // Si el usuario hizo clic en OK
        if (resultado == JOptionPane.OK_OPTION) {
            // Si hay un archivo seleccionado, enviarlo
            if (archivoSeleccionado[0] != null) {
                enviarArchivoPrivado(destinatario, archivoSeleccionado[0]);
            }
            
            // Enviar mensaje de texto si no está vacío
            String mensaje = textArea.getText().trim();
            if (!mensaje.isEmpty()) {
                enviarMensajeAlServidor("/privado " + destinatario + " " + mensaje);
            }
        }
    }
    
    // Método para conectar al servidor
    private void conectarAlServidor() {
        try {
            socket = new Socket(HOST, puerto); // Usar el puerto de instancia, no la constante
            entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            salida = new PrintWriter(socket.getOutputStream(), true);
            
            // Recibir solicitud de nombre de usuario
            String mensaje = entrada.readLine();
            mostrarMensaje(mensaje);
            
            // Solicitar nombre de usuario al cliente
            nombreUsuario = JOptionPane.showInputDialog(this, "Ingresa tu nombre de usuario:", "Conexión", JOptionPane.PLAIN_MESSAGE);
            
            // Si no se ingresó un nombre, usar uno generado aleatoriamente
            if (nombreUsuario == null || nombreUsuario.trim().isEmpty()) {
                nombreUsuario = "Usuario" + new Random().nextInt(1000);
            }
            
            // Enviar nombre de usuario al servidor
            salida.println(nombreUsuario);
            
            // Marcar como conectado
            conectado = true;
            
            // Actualizar título de la ventana
            setTitle("Cliente de Chat - " + nombreUsuario + " - " + salaActual + " (" + HOST + ":" + puerto + ")");
            
            // Iniciar hilo para recibir mensajes
            new Thread(new ReceptorMensajes()).start();
            
        } catch (Exception e) {
            mostrarMensaje("Error al conectar con el servidor: " + e.getMessage());
            e.printStackTrace();
            
            // Mostrar mensaje de error y opción para reintentar
            int opcion = JOptionPane.showConfirmDialog(this,
                "No se pudo conectar al servidor " + HOST + ":" + puerto + "\n" +
                "¿Desea intentar con otra dirección?",
                "Error de conexión",
                JOptionPane.YES_NO_OPTION);
            
            if (opcion == JOptionPane.YES_OPTION) {
                // Solicitar nueva dirección IP
                String nuevoHost = JOptionPane.showInputDialog(this,
                    "Ingrese la dirección IP del servidor:",
                    "Configuración de conexión",
                    JOptionPane.QUESTION_MESSAGE);
                
                if (nuevoHost != null && !nuevoHost.trim().isEmpty()) {
                    HOST = nuevoHost.trim();
                    conectarAlServidor(); // Reintentar conexión
                } else {
                    System.exit(0); // Salir si no se proporciona dirección
                }
            } else {
                System.exit(0); // Salir si el usuario decide no reintentar
            }
        }
    }
    
    // Método para desconectar del servidor
    private void desconectar() {
        try {
            if (conectado) {
                // Enviar comando de salida al servidor
                enviarMensajeAlServidor("/salir");
                
                // Cerrar recursos
                if (salida != null) salida.close();
                if (entrada != null) entrada.close();
                if (socket != null) socket.close();
                
                conectado = false;
            }
        } catch (Exception e) {
            mostrarMensaje("Error al desconectar: " + e.getMessage());
        }
    }
    
    // Método para enviar mensajes
    private void enviarMensaje() {
        String mensaje = campoMensaje.getText().trim();
        if (!mensaje.isEmpty() && conectado) {
            // Enviar el mensaje a la sala actual
            enviarMensajeAlServidor(mensaje);
            
            // Limpiar el campo de mensaje
            campoMensaje.setText("");
        }
    }
    
    // Método para enviar mensajes al servidor
    private void enviarMensajeAlServidor(String mensaje) {
        if (conectado && salida != null) {
            salida.println(mensaje);
        }
    }
    
    // Método para enviar un archivo
    private void enviarArchivo() {
        if (!conectado) {
            mostrarMensaje("Error: No estás conectado al servidor.");
            return;
        }
        
        JFileChooser fileChooser = new JFileChooser();
        int resultado = fileChooser.showOpenDialog(this);
        
        if (resultado == JFileChooser.APPROVE_OPTION) {
            File archivo = fileChooser.getSelectedFile();
            
            // Verificar tamaño del archivo (límite de 10MB para este ejemplo)
            if (archivo.length() > 10 * 1024 * 1024) {
                JOptionPane.showMessageDialog(this, 
                    "El archivo es demasiado grande. El límite es de 10MB.",
                    "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // Iniciar la transferencia en un hilo separado para no bloquear la UI
            new Thread(() -> {                try {                    // Notificar al servidor que vamos a enviar un archivo
                    String nombreArchivo = archivo.getName().replace(" ", "_"); // Reemplazar espacios
                    // Si la sala contiene guiones, no necesitamos comillas
                    String comando = COMANDO_ARCHIVO + " " + salaActual + " " + nombreArchivo + " " + archivo.length();
                    System.out.println("Enviando comando: " + comando);
                    enviarMensajeAlServidor(comando);
                    
                    // Esperar un breve momento para que el servidor procese el comando
                    Thread.sleep(500);
                    
                    // Crear socket para transmisión de archivos
                    Socket socketArchivo = null;
                    try {
                        socketArchivo = new Socket(HOST, puerto + 1); // Puerto para archivos = puerto normal + 1
                        
                        // Enviar nombre de usuario para identificación
                        PrintWriter salidaArchivo = new PrintWriter(socketArchivo.getOutputStream(), true);
                        salidaArchivo.println(nombreUsuario);
                        
                        // Enviar datos del archivo
                        FileInputStream fis = new FileInputStream(archivo);
                        OutputStream os = socketArchivo.getOutputStream();
                        
                        byte[] buffer = new byte[TAMAÑO_BUFFER];
                        int bytesLeidos;
                        
                        // Mensaje de progreso (sólo en consola, no en UI para no bloquear)
                        System.out.println("Enviando archivo: " + archivo.getName());
                        
                        while ((bytesLeidos = fis.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesLeidos);
                        }
                        
                        // Cerrar recursos
                        os.flush();
                        fis.close();
                        
                        SwingUtilities.invokeLater(() -> {
                            mostrarMensaje("Has enviado el archivo: " + archivo.getName());
                        });
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> {
                            mostrarMensaje("Error al enviar el archivo: " + e.getMessage());
                        });
                        e.printStackTrace();
                    } finally {
                        // Asegurarnos de cerrar el socket incluso si hay un error
                        if (socketArchivo != null && !socketArchivo.isClosed()) {
                            try {
                                socketArchivo.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        mostrarMensaje("Error preparando envío de archivo: " + e.getMessage());
                    });
                    e.printStackTrace();
                }
            }).start();
        }
    }
    
    // Método para enviar un archivo privado a un usuario específico
    private void enviarArchivoPrivado(String destinatario, File archivo) {
        if (!conectado) {
            mostrarMensaje("Error: No estás conectado al servidor.");
            return;
        }
        
        // Verificar tamaño del archivo (límite de 10MB para este ejemplo)
        if (archivo.length() > 10 * 1024 * 1024) {
            JOptionPane.showMessageDialog(this, 
                "El archivo es demasiado grande. El límite es de 10MB.",
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        // Iniciar la transferencia en un hilo separado para no bloquear la UI
        new Thread(() -> {            try {                // Notificar al servidor que vamos a enviar un archivo privado
                String nombreArchivo = archivo.getName().replace(" ", "_"); // Reemplazar espacios
                // Si el destinatario contiene espacios, añadir comillas
                String formattedDestinatario = destinatario.contains(" ") ? "\"" + destinatario + "\"" : destinatario;
                String comando = COMANDO_ARCHIVO + " " + formattedDestinatario + " " + nombreArchivo + " " + archivo.length();
                System.out.println("Enviando comando: " + comando);
                enviarMensajeAlServidor(comando);
                
                // Esperar un breve momento para que el servidor procese el comando
                Thread.sleep(500);
                
                // Crear socket para transmisión de archivos
                Socket socketArchivo = null;
                try {
                    socketArchivo = new Socket(HOST, puerto + 1); // Puerto para archivos = puerto normal + 1
                    
                    // Enviar nombre de usuario para identificación
                    PrintWriter salidaArchivo = new PrintWriter(socketArchivo.getOutputStream(), true);
                    salidaArchivo.println(nombreUsuario);
                    
                    // Enviar datos del archivo
                    FileInputStream fis = new FileInputStream(archivo);
                    OutputStream os = socketArchivo.getOutputStream();
                    
                    byte[] buffer = new byte[TAMAÑO_BUFFER];
                    int bytesLeidos;
                    
                    // Mensaje de progreso (sólo en consola, no en UI para no bloquear)
                    System.out.println("Enviando archivo privado a " + destinatario + ": " + archivo.getName());
                    
                    while ((bytesLeidos = fis.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesLeidos);
                    }
                    
                    // Cerrar recursos
                    os.flush();
                    fis.close();
                    
                    SwingUtilities.invokeLater(() -> {
                        mostrarMensaje("Has enviado el archivo " + archivo.getName() + " a " + destinatario);
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        mostrarMensaje("Error al enviar el archivo: " + e.getMessage());
                    });
                    e.printStackTrace();
                } finally {
                    // Asegurarnos de cerrar el socket incluso si hay un error
                    if (socketArchivo != null && !socketArchivo.isClosed()) {
                        try {
                            socketArchivo.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    mostrarMensaje("Error preparando envío de archivo: " + e.getMessage());
                });
                e.printStackTrace();
            }
        }).start();
    }
    
    // Método para recibir un archivo (sin notificación, guardado automático)
    private void recibirArchivo(String remitente, String nombreArchivo, long tamaño) {
        // Crear directorio para archivos del usuario si no existe
        File directorioUsuario = new File("Archivos" + nombreUsuario);
        if (!directorioUsuario.exists()) {
            directorioUsuario.mkdirs();
        }
        
        // Archivo de destino
        File archivoDestino = new File(directorioUsuario, nombreArchivo);
        
        // Comprobar si ya existe el archivo y renombrarlo si es necesario
        if (archivoDestino.exists()) {
            int contador = 1;
            String nombreBase = nombreArchivo;
            String extension = "";
            
            int indexPunto = nombreArchivo.lastIndexOf(".");
            if (indexPunto > 0) {
                nombreBase = nombreArchivo.substring(0, indexPunto);
                extension = nombreArchivo.substring(indexPunto);
            }
            
            while (archivoDestino.exists()) {
                archivoDestino = new File(directorioUsuario, nombreBase + "(" + contador + ")" + extension);
                contador++;
            }
        }
        
        // Iniciar la recepción en un hilo separado para no bloquear
        final File archivoFinal = archivoDestino;
        new Thread(() -> {
            Socket socketArchivo = null;
            try {
                // Esperar un breve momento para asegurarnos que el servidor está listo
                Thread.sleep(1000);
                
                // Crear socket para recepción de archivos
                socketArchivo = new Socket(HOST, puerto + 1);
                
                // Identificar que estamos listos para recibir
                PrintWriter salidaArchivo = new PrintWriter(socketArchivo.getOutputStream(), true);
                salidaArchivo.println(nombreUsuario + "_RECIBIR_" + remitente);
                
                // Preparar para recibir
                InputStream is = socketArchivo.getInputStream();
                FileOutputStream fos = new FileOutputStream(archivoFinal);
                
                byte[] buffer = new byte[TAMAÑO_BUFFER];
                int bytesLeidos;
                long totalRecibido = 0;
                
                // Mensaje de progreso (sólo en consola, no en UI para no bloquear)
                System.out.println("Recibiendo archivo de " + remitente + ": " + archivoFinal.getName());
                
                // Establecer un tiempo límite de lectura para evitar bloqueos indefinidos
                socketArchivo.setSoTimeout(30000); // 30 segundos
                
                while (totalRecibido < tamaño && (bytesLeidos = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesLeidos);
                    totalRecibido += bytesLeidos;
                }
                
                // Cerrar recursos
                fos.close();
                
                SwingUtilities.invokeLater(() -> {
                    mostrarMensaje("Archivo recibido de " + remitente + ": " + archivoFinal.getName() + " - Guardado en " + archivoFinal.getAbsolutePath());
                });
                
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    mostrarMensaje("Error al recibir el archivo: " + e.getMessage());
                });
                e.printStackTrace();
                
                // Si hubo un error, eliminar el archivo parcial
                if (archivoFinal.exists()) {
                    archivoFinal.delete();
                }
            } finally {
                // Asegurarnos de cerrar el socket incluso si hay un error
                if (socketArchivo != null && !socketArchivo.isClosed()) {
                    try {
                        socketArchivo.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }
    
    // Método para cambiar de sala
    private void cambiarSala(String nuevaSala) {
        if (!nuevaSala.equals(salaActual)) {
            enviarMensajeAlServidor("/sala " + nuevaSala);
            salaActual = nuevaSala;
            setTitle("Cliente de Chat - " + nombreUsuario + " - " + salaActual);
            areaMensajes.setText(""); // Limpiar mensajes al cambiar de sala
        }
    }
    
    // Método para mostrar mensajes en el área de chat
    private void mostrarMensaje(String mensaje) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                // Determinar si el mensaje es para destacarse (mensaje privado)
                if (mensaje.contains("[Privado")) {
                    // Destacar mensajes privados con un color diferente
                    areaMensajes.append("----------------------------------\n");
                    areaMensajes.append(mensaje + "\n");
                    areaMensajes.append("----------------------------------\n");
                } else {
                    areaMensajes.append(mensaje + "\n");
                }
                
                // Desplazar automáticamente al último mensaje
                areaMensajes.setCaretPosition(areaMensajes.getDocument().getLength());
            }
        });
    }
    
    // Clase interna para recibir mensajes del servidor
    private class ReceptorMensajes implements Runnable {
        @Override
        public void run() {
            try {
                String mensaje;
                while (conectado && (mensaje = entrada.readLine()) != null) {
                    if (mensaje.startsWith("SALAS:")) {
                        // Actualizar lista de salas
                        actualizarListaSalas(mensaje.substring(6).split("\\|"));
                    } else if (mensaje.startsWith("USUARIOS:")) {
                        // Actualizar lista de usuarios
                        actualizarListaUsuarios(mensaje.substring(9).split("\\|"));
                    } else if (mensaje.startsWith("ARCHIVO:")) {                        // Formato: ARCHIVO:remitente:nombreArchivo:tamaño
                        String[] partes = mensaje.substring(8).split(":", 3);
                        if (partes.length >= 3) {
                            String remitente = partes[0];
                            String nombreArchivo = partes[1];
                            
                            try {
                                long tamaño = Long.parseLong(partes[2]);
                                
                                // Recibir archivo automáticamente sin preguntar
                                recibirArchivo(remitente, nombreArchivo, tamaño);
                            } catch (NumberFormatException e) {
                                mostrarMensaje("Error al procesar el tamaño del archivo: " + e.getMessage());
                                System.err.println("Error al analizar el tamaño del archivo: " + e.getMessage());
                            }
                        }
                    } else {
                        // Mostrar todos los mensajes (incluyendo privados) en la ventana principal
                        mostrarMensaje(mensaje);
                    }
                }
            } catch (IOException e) {
                if (conectado) {
                    mostrarMensaje("Conexión perdida con el servidor: " + e.getMessage());
                }
            } finally {
                // Si la conexión se pierde inesperadamente, cerrar recursos
                if (conectado) {
                    desconectar();
                }
            }
        }
    }
    
    // Método para actualizar la lista de salas
    private void actualizarListaSalas(String[] listaSalas) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                modeloSalas.clear();
                for (String sala : listaSalas) {
                    if (!sala.trim().isEmpty() && !sala.startsWith("_PRIVADO_")) {
                        modeloSalas.addElement(sala);
                    }
                }
            }
        });
    }
    
    // Método para actualizar la lista de usuarios
    private void actualizarListaUsuarios(String[] listaUsuarios) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                modeloUsuarios.clear();
                for (String usuario : listaUsuarios) {
                    if (!usuario.trim().isEmpty()) {
                        modeloUsuarios.addElement(usuario);
                    }
                }
            }
        });
    }
    
    // Método principal
    public static void main(String[] args) {
        final String host;
        final int puerto;
        int puerto1;

        if (args.length >= 1) {
            host = args[0];
        } else {
            // Solicitar dirección IP del servidor
            String input = JOptionPane.showInputDialog(null, 
                "Ingrese la dirección IP del servidor:", 
                "Configuración de conexión", 
                JOptionPane.QUESTION_MESSAGE);
            
            host = (input != null && !input.trim().isEmpty()) ? input.trim() : "localhost";
        }
        
        if (args.length >= 2) {
            try {
                puerto1 = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(null, 
                    "Puerto inválido, se usará el puerto predeterminado: " + PUERTO_DEFECTO, 
                    "Error", JOptionPane.ERROR_MESSAGE);
                puerto1 = PUERTO_DEFECTO;
            }
        } else {
            puerto1 = PUERTO_DEFECTO;
        }
        
        puerto = puerto1;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new Cliente(host, puerto);
            }
        });
    }
}
