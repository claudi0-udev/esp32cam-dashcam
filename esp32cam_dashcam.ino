#include "esp_camera.h"
#include "FS.h"
#include "SD_MMC.h"
#include "WiFi.h"
#include "WebServer.h"
#include "esp_bt.h"

// ================= PINES ESP32-CAM (AI-THINKER) =================
#define PWDN_GPIO_NUM     32
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM      0
#define SIOD_GPIO_NUM     26
#define SIOC_GPIO_NUM     27
#define Y9_GPIO_NUM       35
#define Y8_GPIO_NUM       34
#define Y7_GPIO_NUM       39
#define Y6_GPIO_NUM       36
#define Y5_GPIO_NUM       21
#define Y4_GPIO_NUM       19
#define Y3_GPIO_NUM       18
#define Y2_GPIO_NUM        5
#define VSYNC_GPIO_NUM    25
#define HREF_GPIO_NUM     23
#define PCLK_GPIO_NUM     22

#define FLASH_LED_PIN      4    // Flash frontal (blanco)
#define ONBOARD_LED_PIN   33    // LED rojo pequeño trasero (Activo en LOW)
#define BUTTON_PIN        13    // Pulsador a GND para modo Wi-Fi

// ================= CONFIGURACIÓN DASHCAM =================
const int FPS = 3;                       // 3 cuadros por segundo
const int CLIP_DURATION_SEC = 60;        // Duración de cada video (60 seg = 180 frames)
const int FRAMES_PER_CLIP = FPS * CLIP_DURATION_SEC;
const unsigned long FRAME_INTERVAL_MS = 1000 / FPS;
const size_t AVI_HEADER_SIZE = 176;

int fileIndex = 0;
bool wifiMode = false;
WebServer server(80);

// Señal luminosa de error (parpadeo rápido infinito en el LED rojo)
void blinkError(int times) {
  while (true) {
    for (int i = 0; i < times; i++) {
      digitalWrite(ONBOARD_LED_PIN, LOW);  // Encendido
      delay(150);
      digitalWrite(ONBOARD_LED_PIN, HIGH); // Apagado
      delay(150);
    }
    delay(1000);
  }
}

// Escribe la cabecera estándar AVI para Motion-JPEG (176 bytes)
static void writeAviHeader(File &file, int width, int height, int totalFrames, int fps) {
  uint32_t currentSize = file.size();
  uint32_t moviSize = (currentSize >= AVI_HEADER_SIZE) ? (currentSize - AVI_HEADER_SIZE) : (totalFrames * 30000);
  uint32_t riffSize = moviSize + AVI_HEADER_SIZE - 8;

  file.seek(0);
  
  file.write((const uint8_t*)"RIFF", 4);
  file.write((const uint8_t*)&riffSize, 4);
  file.write((const uint8_t*)"AVI ", 4);
  
  file.write((const uint8_t*)"LIST", 4);
  uint32_t hdrlSize = 4 + 8 + 56 + 8 + 4 + 8 + 56;
  file.write((const uint8_t*)&hdrlSize, 4);
  file.write((const uint8_t*)"hdrl", 4);
  
  file.write((const uint8_t*)"avih", 4);
  uint32_t avihSize = 56;
  file.write((const uint8_t*)&avihSize, 4);
  uint32_t usecPerFrame = 1000000 / fps;
  file.write((const uint8_t*)&usecPerFrame, 4);
  uint32_t maxBytes = 0; file.write((const uint8_t*)&maxBytes, 4);
  uint32_t padding = 0;  file.write((const uint8_t*)&padding, 4);
  uint32_t flags = 0x810; file.write((const uint8_t*)&flags, 4);
  file.write((const uint8_t*)&totalFrames, 4);
  uint32_t initialFrames = 0; file.write((const uint8_t*)&initialFrames, 4);
  uint32_t streams = 1; file.write((const uint8_t*)&streams, 4);
  uint32_t bufSize = 0; file.write((const uint8_t*)&bufSize, 4);
  file.write((const uint8_t*)&width, 4);
  file.write((const uint8_t*)&height, 4);
  uint32_t reserved[4] = {0, 0, 0, 0};
  file.write((const uint8_t*)reserved, 16);
  
  file.write((const uint8_t*)"LIST", 4);
  uint32_t strlSize = 4 + 8 + 56;
  file.write((const uint8_t*)&strlSize, 4);
  file.write((const uint8_t*)"strl", 4);
  
  file.write((const uint8_t*)"strh", 4);
  uint32_t strhSize = 56;
  file.write((const uint8_t*)&strhSize, 4);
  file.write((const uint8_t*)"vids", 4);
  file.write((const uint8_t*)"MJPG", 4);
  file.write((const uint8_t*)&padding, 4);
  file.write((const uint8_t*)&padding, 4);
  file.write((const uint8_t*)&padding, 4);
  uint32_t scale = 1; file.write((const uint8_t*)&scale, 4);
  uint32_t rate = fps; file.write((const uint8_t*)&rate, 4);
  uint32_t start = 0; file.write((const uint8_t*)&start, 4);
  file.write((const uint8_t*)&totalFrames, 4);
  file.write((const uint8_t*)&bufSize, 4);
  int32_t quality = -1; file.write((const uint8_t*)&quality, 4);
  uint32_t sampleSize = 0; file.write((const uint8_t*)&sampleSize, 4);
  int16_t rcFrame[4] = {0, 0, (int16_t)width, (int16_t)height};
  file.write((const uint8_t*)rcFrame, 8);
  
  file.write((const uint8_t*)"LIST", 4);
  file.write((const uint8_t*)&moviSize, 4);
  file.write((const uint8_t*)"movi", 4);
}

// Limpieza de espacio (Loop recording)
void checkStorageSpace() {
  uint64_t totalBytes = SD_MMC.totalBytes();
  uint64_t usedBytes = SD_MMC.usedBytes();
  if (totalBytes > 0 && totalBytes > usedBytes) {
    uint64_t freeBytes = totalBytes - usedBytes;
    if (freeBytes < 100 * 1024 * 1024) {
      File root = SD_MMC.open("/");
      File file = root.openNextFile();
      if (file) {
        String oldestFile = file.name();
        file.close();
        root.close();
        if (oldestFile.endsWith(".avi")) {
          if (!oldestFile.startsWith("/")) oldestFile = "/" + oldestFile;
          SD_MMC.remove(oldestFile);
          Serial.printf("[LOOP] Borrado video antiguo: %s\n", oldestFile.c_str());
        }
      } else {
        root.close();
      }
    }
  }
}

// ================= MODO SERVIDOR WEB (DESCARGA WI-FI) =================
void handleRoot() {
  String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'>";
  html += "<meta name='viewport' content='width=device-width, initial-scale=1'>";
  html += "<title>Dashcam - Descarga de Videos</title>";
  html += "<style>body{font-family:sans-serif;margin:20px;background:#f0f2f5;color:#333}";
  html += "h1{color:#1a73e8}ul{list-style:none;padding:0}";
  html += "li{background:white;padding:12px;margin-bottom:8px;border-radius:6px;display:flex;justify-content:space-between;align-items:center;box-shadow:0 1px 3px rgba(0,0,0,0.1)}";
  html += "a.btn{background:#1a73e8;color:white;text-decoration:none;padding:8px 14px;border-radius:4px;font-weight:bold}";
  html += ".size{color:#777;font-size:0.9em;margin-left:10px}</style></head><body>";
  html += "<h1>🚗 Videos Grabados</h1><ul>";

  File root = SD_MMC.open("/");
  File file = root.openNextFile();
  int count = 0;
  while (file) {
    String fname = file.name();
    if (fname.endsWith(".avi")) {
      count++;
      float sizeMB = file.size() / (1024.0 * 1024.0);
      html += "<li><span>📹 " + fname + " <span class='size'>(" + String(sizeMB, 2) + " MB)</span></span>";
      html += "<a class='btn' href='/download?file=" + fname + "' download>Descargar</a></li>";
    }
    file = root.openNextFile();
  }
  root.close();

  if (count == 0) {
    html += "<p>No hay videos guardados aún.</p>";
  }
  html += "</ul><p style='margin-top:20px;font-size:0.85em;color:#666'>Apaga y enciende la dashcam sin mantener el botón presionado para volver a grabar.</p>";
  html += "</body></html>";
  server.send(200, "text/html", html);
}

void handleDownload() {
  if (!server.hasArg("file")) {
    server.send(400, "text/plain", "Falta parametro de archivo");
    return;
  }
  String filename = server.arg("file");
  if (!filename.startsWith("/")) {
    filename = "/" + filename;
  }
  if (!SD_MMC.exists(filename)) {
    server.send(404, "text/plain", "Archivo no encontrado");
    return;
  }

  File downloadFile = SD_MMC.open(filename, FILE_READ);
  server.sendHeader("Content-Disposition", "attachment; filename=" + filename.substring(1));
  server.streamFile(downloadFile, "video/x-msvideo");
  downloadFile.close();
}

void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n--- ESP32-CAM Dashcam Iniciando ---");

  // Configuración de LEDs
  pinMode(ONBOARD_LED_PIN, OUTPUT);
  digitalWrite(ONBOARD_LED_PIN, HIGH); // Apagado inicial
  pinMode(FLASH_LED_PIN, OUTPUT);
  digitalWrite(FLASH_LED_PIN, LOW);    // Flash frontal apagado

  // 1. Configurar pulsador
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  bool requestedWifi = (digitalRead(BUTTON_PIN) == LOW);

  // 2. Iniciar MicroSD en modo 1-Bit (CLK=14, CMD=15, D0=2)
  SD_MMC.setPins(14, 15, 2);
  if (!SD_MMC.begin("/sdcard", true, false, 20000)) {
    Serial.println("❌ ERROR: Fallo al montar MicroSD (¿Formateada en FAT32?)");
    // Parpadeo de error: 3 destellos rápidos continuos = ERROR SD
    blinkError(3);
    return;
  }

  uint8_t cardType = SD_MMC.cardType();
  if (cardType == CARD_NONE) {
    Serial.println("❌ ERROR: No se detecta tarjeta en la ranura MicroSD");
    blinkError(4);
    return;
  }
  Serial.printf("✅ MicroSD detectada correctamente. Capacidad: %llu MB\n", SD_MMC.cardSize() / (1024 * 1024));

  // 3. MODO WI-FI SI SE MANTUVO EL PULSADOR
  if (requestedWifi) {
    wifiMode = true;
    digitalWrite(ONBOARD_LED_PIN, LOW); // LED rojo encendido fijo
    Serial.println(">>> MODO WIFI ACTIVADO");

    WiFi.mode(WIFI_AP);
    WiFi.softAP("Dashcam-WiFi", "12345678");

    server.on("/", HTTP_GET, handleRoot);
    server.on("/download", HTTP_GET, handleDownload);
    server.begin();

    Serial.print("Conectate a 'Dashcam-WiFi' y entra a http://");
    Serial.println(WiFi.softAPIP());
    return;
  }

  // 4. MODO GRABACIÓN NORMAL
  wifiMode = false;
  WiFi.mode(WIFI_OFF);
  btStop();

  setCpuFrequencyMhz(160);

  // Buscar el siguiente nombre de archivo disponible (para no sobrescribir)
  while (fileIndex < 9999) {
    char testPath[32];
    sprintf(testPath, "/dash_%04d.avi", fileIndex);
    if (SD_MMC.exists(testPath)) {
      fileIndex++;
    } else {
      break;
    }
  }
  Serial.printf("Siguiente archivo a grabar: /dash_%04d.avi\n", fileIndex);

  camera_config_t config;
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
  config.fb_location = CAMERA_FB_IN_PSRAM;
  
  config.frame_size = FRAMESIZE_VGA;
  config.jpeg_quality = 12;
  config.fb_count = 2;

  if (esp_camera_init(&config) != ESP_OK) {
    Serial.println("❌ ERROR al iniciar la cámara OV2640");
    // Parpadeo de error: 2 destellos = ERROR CÁMARA
    blinkError(2);
    return;
  }

  Serial.println("📹 Grabando clips a 3 FPS...");
}

void recordClip() {
  checkStorageSpace();

  char filename[32];
  sprintf(filename, "/dash_%04d.avi", fileIndex++);
  File aviFile = SD_MMC.open(filename, FILE_WRITE);
  if (!aviFile) {
    Serial.printf("❌ Error al abrir %s para escritura\n", filename);
    delay(1000);
    return;
  }

  writeAviHeader(aviFile, 640, 480, FRAMES_PER_CLIP, FPS);
  aviFile.seek(AVI_HEADER_SIZE);

  int framesWritten = 0;
  Serial.printf("[REC] Grabando: %s\n", filename);

  for (int i = 0; i < FRAMES_PER_CLIP; i++) {
    unsigned long startMs = millis();

    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) {
      Serial.println("Error frame");
      continue;
    }

    aviFile.write((const uint8_t*)"00dc", 4);
    uint32_t frameSize = fb->len;
    aviFile.write((const uint8_t*)&frameSize, 4);
    aviFile.write(fb->buf, fb->len);

    if (frameSize % 2 != 0) {
      uint8_t zero = 0;
      aviFile.write(&zero, 1);
    }

    esp_camera_fb_return(fb);
    framesWritten++;

    // Asentar en la SD físicamente
    aviFile.flush();

    // Pequeño parpadeo indicador de actividad cada frame
    digitalWrite(ONBOARD_LED_PIN, LOW);
    delay(20);
    digitalWrite(ONBOARD_LED_PIN, HIGH);

    unsigned long elapsed = millis() - startMs;
    if (elapsed < FRAME_INTERVAL_MS) {
      delay(FRAME_INTERVAL_MS - elapsed);
    }
  }

  writeAviHeader(aviFile, 640, 480, framesWritten, FPS);
  aviFile.close();
  Serial.printf("[REC] Clip guardado: %s (%d frames)\n", filename, framesWritten);
}

void loop() {
  if (wifiMode) {
    server.handleClient();
  } else {
    recordClip();
  }
}
