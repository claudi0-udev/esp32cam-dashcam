#include "esp_camera.h"
#include "FS.h"
#include "SD_MMC.h"
#include "WiFi.h"
#include "WebServer.h"
#include "esp_bt.h"
#include <Update.h>
#include <vector>
#include <algorithm>

#define FIRMWARE_VERSION  "1.1.0"

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

// Pines para el pulsador Wi-Fi (aceptamos IO13 o el pin RX / IO3)
#define BUTTON_PIN_13     13    
#define BUTTON_PIN_3       3    

// ================= VALORES POR DEFECTO CONFIGURABLES =================
int cfg_fps = 3;
int cfg_clip_duration = 60;
int cfg_quality = 12;
String cfg_resolution = "VGA";
int cfg_vflip = 0;
int cfg_hmirror = 0;
int cfg_brightness = 0;
int cfg_contrast = 0;
int cfg_saturation = 0;
int cfg_wb_mode = 0;

int videoWidth = 640;
int videoHeight = 480;
framesize_t cameraFrameSize = FRAMESIZE_VGA;
int framesPerClip = 180;
unsigned long frameIntervalMs = 333;
const size_t AVI_HEADER_SIZE = 224;

int fileIndex = 0;
bool wifiMode = false;
bool sdMounted = false;
WebServer server(80);

void blinkError(int times) {
  while (true) {
    for (int i = 0; i < times; i++) {
      digitalWrite(ONBOARD_LED_PIN, LOW);
      delay(150);
      digitalWrite(ONBOARD_LED_PIN, HIGH);
      delay(150);
    }
    delay(1000);
  }
}

// Intento de montaje seguro de la MicroSD
bool tryMountSD() {
  if (sdMounted) return true;
  SD_MMC.setPins(14, 15, 2);
  if (SD_MMC.begin("/sdcard", true)) {
    sdMounted = true;
    Serial.println("✅ MicroSD montada exitosamente");
    return true;
  }
  return false;
}

// ================= GESTIÓN DEL ARCHIVO DASHCAM.CFG =================
void createDefaultConfigFile() {
  File cfgFile = SD_MMC.open("/dashcam.cfg", FILE_WRITE);
  if (!cfgFile) return;

  cfgFile.println("# ==========================================");
  cfgFile.println("#  Configuracion de la ESP32-CAM Dashcam");
  cfgFile.println("# ==========================================");
  cfgFile.println("# Resolucion: QVGA (320x240), CIF (400x296), VGA (640x480), SVGA (800x600), HD (1280x720)");
  cfgFile.println("resolution=VGA");
  cfgFile.println("");
  cfgFile.println("# Cuadros por segundo (1 a 10). Recomendado: 3");
  cfgFile.println("fps=3");
  cfgFile.println("");
  cfgFile.println("# Duracion de cada clip en segundos (10 a 300). Recomendado: 60");
  cfgFile.println("clip_duration=60");
  cfgFile.println("");
  cfgFile.println("# Calidad JPEG (10 = maxima calidad, 63 = minima calidad)");
  cfgFile.println("quality=12");
  cfgFile.println("");
  cfgFile.println("# Orientacion (util si montas la camara invertida en el parabrisas)");
  cfgFile.println("# 0 = Normal, 1 = Invertido");
  cfgFile.println("vflip=0");
  cfgFile.println("hmirror=0");
  cfgFile.println("");
  cfgFile.println("# Ajustes de imagen (-2 a 2, 0 = normal)");
  cfgFile.println("brightness=0");
  cfgFile.println("contrast=0");
  cfgFile.println("saturation=0");
  cfgFile.println("");
  cfgFile.println("# Balance de blancos: 0=Auto, 1=Soleado, 2=Nublado, 3=Oficina, 4=Hogar");
  cfgFile.println("wb_mode=0");

  cfgFile.close();
  Serial.println("📄 Creado archivo por defecto: /dashcam.cfg");
}

void loadConfigFile() {
  if (!SD_MMC.exists("/dashcam.cfg")) {
    createDefaultConfigFile();
    return;
  }

  File cfgFile = SD_MMC.open("/dashcam.cfg", FILE_READ);
  if (!cfgFile) return;

  Serial.println("⚙️ Leyendo configuracion desde /dashcam.cfg...");

  while (cfgFile.available()) {
    String line = cfgFile.readStringUntil('\n');
    line.trim();
    if (line.length() == 0 || line.startsWith("#") || line.startsWith(";")) continue;

    int sep = line.indexOf('=');
    if (sep == -1) continue;

    String key = line.substring(0, sep);
    String val = line.substring(sep + 1);
    key.trim();
    val.trim();

    if (key.equalsIgnoreCase("fps")) cfg_fps = constrain(val.toInt(), 1, 15);
    else if (key.equalsIgnoreCase("clip_duration")) cfg_clip_duration = constrain(val.toInt(), 10, 600);
    else if (key.equalsIgnoreCase("quality")) cfg_quality = constrain(val.toInt(), 10, 63);
    else if (key.equalsIgnoreCase("resolution")) cfg_resolution = val;
    else if (key.equalsIgnoreCase("vflip")) cfg_vflip = val.toInt();
    else if (key.equalsIgnoreCase("hmirror")) cfg_hmirror = val.toInt();
    else if (key.equalsIgnoreCase("brightness")) cfg_brightness = constrain(val.toInt(), -2, 2);
    else if (key.equalsIgnoreCase("contrast")) cfg_contrast = constrain(val.toInt(), -2, 2);
    else if (key.equalsIgnoreCase("saturation")) cfg_saturation = constrain(val.toInt(), -2, 2);
    else if (key.equalsIgnoreCase("wb_mode")) cfg_wb_mode = constrain(val.toInt(), 0, 4);
  }
  cfgFile.close();

  cfg_resolution.toUpperCase();
  if (cfg_resolution == "QVGA") {
    cameraFrameSize = FRAMESIZE_QVGA; videoWidth = 320; videoHeight = 240;
  } else if (cfg_resolution == "CIF") {
    cameraFrameSize = FRAMESIZE_CIF; videoWidth = 400; videoHeight = 296;
  } else if (cfg_resolution == "SVGA") {
    cameraFrameSize = FRAMESIZE_SVGA; videoWidth = 800; videoHeight = 600;
  } else if (cfg_resolution == "HD") {
    cameraFrameSize = FRAMESIZE_HD; videoWidth = 1280; videoHeight = 720;
  } else {
    cameraFrameSize = FRAMESIZE_VGA; videoWidth = 640; videoHeight = 480;
  }

  framesPerClip = cfg_fps * cfg_clip_duration;
  frameIntervalMs = 1000 / cfg_fps;
}

// Escribe la cabecera estándar AVI para Motion-JPEG (224 bytes)
static void writeAviHeader(File &file, int width, int height, int totalFrames, int fps) {
  uint32_t currentSize = file.size();
  uint32_t moviSize = (currentSize >= AVI_HEADER_SIZE) ? (currentSize - AVI_HEADER_SIZE) : (totalFrames * 15000);
  uint32_t riffSize = moviSize + AVI_HEADER_SIZE - 8;

  file.seek(0);
  
  // 1. RIFF chunk
  file.write((const uint8_t*)"RIFF", 4);
  file.write((const uint8_t*)&riffSize, 4);
  file.write((const uint8_t*)"AVI ", 4);
  
  // 2. LIST hdrl
  file.write((const uint8_t*)"LIST", 4);
  uint32_t hdrlSize = 4 + 8 + 56 + 8 + (4 + 8 + 56 + 8 + 40);
  file.write((const uint8_t*)&hdrlSize, 4);
  file.write((const uint8_t*)"hdrl", 4);
  
  // 2.1. avih chunk (56 bytes)
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
  
  // 2.2. LIST strl (116 bytes)
  file.write((const uint8_t*)"LIST", 4);
  uint32_t strlSize = 4 + 8 + 56 + 8 + 40;
  file.write((const uint8_t*)&strlSize, 4);
  file.write((const uint8_t*)"strl", 4);
  
  // 2.2.1. strh chunk (56 bytes)
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
  
  // 2.2.2. strf chunk (40 bytes BITMAPINFOHEADER)
  file.write((const uint8_t*)"strf", 4);
  uint32_t strfSize = 40;
  file.write((const uint8_t*)&strfSize, 4);
  uint32_t biSize = 40; file.write((const uint8_t*)&biSize, 4);
  int32_t biWidth = width; file.write((const uint8_t*)&biWidth, 4);
  int32_t biHeight = height; file.write((const uint8_t*)&biHeight, 4);
  uint16_t biPlanes = 1; file.write((const uint8_t*)&biPlanes, 2);
  uint16_t biBitCount = 24; file.write((const uint8_t*)&biBitCount, 2);
  file.write((const uint8_t*)"MJPG", 4);
  uint32_t biSizeImage = width * height * 3; file.write((const uint8_t*)&biSizeImage, 4);
  int32_t biXPelsPerMeter = 0; file.write((const uint8_t*)&biXPelsPerMeter, 4);
  int32_t biYPelsPerMeter = 0; file.write((const uint8_t*)&biYPelsPerMeter, 4);
  uint32_t biClrUsed = 0; file.write((const uint8_t*)&biClrUsed, 4);
  uint32_t biClrImportant = 0; file.write((const uint8_t*)&biClrImportant, 4);
  
  // 3. LIST movi
  file.write((const uint8_t*)"LIST", 4);
  file.write((const uint8_t*)&moviSize, 4);
  file.write((const uint8_t*)"movi", 4);
}

// Limpieza de espacio robusta (Loop recording continuo garantizado)
void checkStorageSpace() {
  if (!sdMounted) return;
  uint64_t totalBytes = SD_MMC.totalBytes();
  uint64_t usedBytes = SD_MMC.usedBytes();
  if (totalBytes == 0) return;

  uint64_t freeBytes = (totalBytes > usedBytes) ? (totalBytes - usedBytes) : 0;
  // Mantener siempre al menos 400 MB libres para proteger la FAT32 y evitar corrupción
  const uint64_t MIN_FREE_BYTES = 400ULL * 1024ULL * 1024ULL;

  if (freeBytes < MIN_FREE_BYTES) {
    Serial.printf("[LOOP] Espacio libre bajo (%llu MB). Limpiando grabaciones antiguas...\n", freeBytes / (1024 * 1024));

    // Recolectar todos los archivos .avi existentes
    std::vector<String> aviList;
    File root = SD_MMC.open("/");
    if (root) {
      File f = root.openNextFile();
      while (f) {
        String fname = f.name();
        if (fname.endsWith(".avi")) {
          if (!fname.startsWith("/")) fname = "/" + fname;
          aviList.push_back(fname);
        }
        f = root.openNextFile();
      }
      root.close();
    }

    // Ordenar alfabéticamente (dash_0001 < dash_0002, o timestamp si renombra)
    std::sort(aviList.begin(), aviList.end());

    // Borrar los archivos más antiguos hasta recuperar al menos 600 MB libres
    const uint64_t TARGET_FREE_BYTES = 600ULL * 1024ULL * 1024ULL;
    for (size_t i = 0; i < aviList.size(); i++) {
      String toDelete = aviList[i];
      SD_MMC.remove(toDelete);
      Serial.printf("[LOOP] Borrado video antiguo: %s\n", toDelete.c_str());

      usedBytes = SD_MMC.usedBytes();
      freeBytes = (totalBytes > usedBytes) ? (totalBytes - usedBytes) : 0;
      if (freeBytes >= TARGET_FREE_BYTES) {
        break;
      }
    }
  }
}

// ================= MODO SERVIDOR WEB CON DESCARGA INDIVIDUAL Y UNIFICADA =================
void handleRoot() {
  if (!sdMounted) {
    tryMountSD();
  }

  String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'>";
  html += "<meta name='viewport' content='width=device-width, initial-scale=1'>";
  html += "<title>Dashcam - Descarga de Videos</title>";
  html += "<style>";
  html += "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;margin:15px;background:#f4f6f9;color:#333}";
  html += "h1{color:#1a73e8;font-size:1.4rem;margin-bottom:4px}";
  html += ".subtitle{color:#666;font-size:0.85rem;margin-bottom:15px}";
  html += ".toolbar{background:white;padding:12px 14px;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,0.1);margin-bottom:15px;display:flex;flex-wrap:wrap;gap:10px;align-items:center;justify-content:space-between}";
  html += ".btn{background:#1a73e8;color:white;border:none;padding:9px 14px;border-radius:6px;font-weight:bold;cursor:pointer;text-decoration:none;font-size:0.85rem;display:inline-flex;align-items:center;gap:6px}";
  html += ".btn-green{background:#2e7d32}";
  html += ".btn:disabled{background:#9aa0a6;cursor:not-allowed}";
  html += ".btn-outline{background:transparent;border:1px solid #1a73e8;color:#1a73e8;padding:6px 12px}";
  html += "ul{list-style:none;padding:0;margin:0}";
  html += "li{background:white;padding:12px 14px;margin-bottom:8px;border-radius:8px;display:flex;justify-content:space-between;align-items:center;box-shadow:0 1px 3px rgba(0,0,0,0.06)}";
  html += ".file-info{display:flex;align-items:center;gap:12px}";
  html += ".file-info input[type=checkbox]{width:20px;height:20px;cursor:pointer;accent-color:#1a73e8}";
  html += ".filename{font-weight:500;font-size:0.95rem}";
  html += ".filesize{color:#777;font-size:0.85rem;margin-left:6px}";
  html += ".progress-box{display:none;background:#e8f0fe;color:#1a73e8;padding:12px;border-radius:6px;margin-bottom:15px;font-weight:bold;text-align:center}";
  html += ".alert{background:#ffebee;color:#c62828;padding:15px;border-radius:6px;margin-bottom:15px}";
  html += ".retry-btn{display:inline-block;margin-top:10px;background:#d32f2f;color:white;padding:8px 16px;border-radius:4px;text-decoration:none;font-weight:bold}";
  html += "</style></head><body>";
  html += "<h1>🚗 ESP32-CAM Dashcam</h1>";
  html += "<div class='subtitle'>Portal de descarga de videos</div>";
  html += "<div id='progBox' class='progress-box'>Descargando...</div>";

  if (!sdMounted) {
    html += "<div class='alert'>";
    html += "<strong>⚠️ Tarjeta MicroSD no detectada.</strong><br>";
    html += "Asegúrate de que esté insertada hasta el fondo y que hayas soltado el botón.<br>";
    html += "<a class='retry-btn' href='/'>🔄 Reintentar Detección</a>";
    html += "</div></body></html>";
    server.send(200, "text/html", html);
    return;
  }

  html += "<div class='toolbar'>";
  html += "<label style='display:flex;align-items:center;gap:8px;font-size:0.9rem;cursor:pointer;'>";
  html += "<input type='checkbox' id='selectAll' onchange='toggleSelectAll(this)' style='width:18px;height:18px;accent-color:#1a73e8;'>";
  html += "<span>Seleccionar todos</span></label>";
  html += "<div style='display:flex;gap:8px;flex-wrap:wrap;'>";
  html += "<button id='btnMerge' class='btn btn-green' onclick='downloadMerged()' disabled>🎬 Unir en 1 video (<span class='selCount'>0</span>)</button>";
  html += "<button id='btnDownload' class='btn' onclick='downloadSeparated()' disabled>⬇️ Separados (<span class='selCount'>0</span>)</button>";
  html += "</div></div>";

  html += "<ul>";
  File root = SD_MMC.open("/");
  File file = root.openNextFile();
  int count = 0;
  while (file) {
    String fname = file.name();
    if (fname.endsWith(".avi")) {
      count++;
      float sizeMB = file.size() / (1024.0 * 1024.0);
      String cleanName = fname.startsWith("/") ? fname.substring(1) : fname;
      html += "<li><div class='file-info'>";
      html += "<input type='checkbox' class='file-check' value='" + cleanName + "' onchange='updateCount()'>";
      html += "<div><span class='filename'>📹 " + cleanName + "</span>";
      html += "<span class='filesize'>(" + String(sizeMB, 2) + " MB)</span></div></div>";
      html += "<a class='btn btn-outline' href='/download?file=" + cleanName + "' download>Bajar</a></li>";
    }
    file = root.openNextFile();
  }
  root.close();

  if (count == 0) {
    html += "<p style='color:#666;'>No hay videos guardados aún.</p>";
  }
  html += "</ul>";

  // JavaScript para Ensamblado y Unión Instantánea de Videos AVI
  html += "<script>";
  html += "function updateCount(){";
  html += "  const checks=document.querySelectorAll('.file-check:checked');";
  html += "  const count=checks.length;";
  html += "  document.querySelectorAll('.selCount').forEach(el=>el.innerText=count);";
  html += "  document.getElementById('btnMerge').disabled=(count===0);";
  html += "  document.getElementById('btnDownload').disabled=(count===0);";
  html += "}";
  html += "function toggleSelectAll(el){";
  html += "  document.querySelectorAll('.file-check').forEach(c=>c.checked=el.checked);";
  html += "  updateCount();";
  html += "}";

  // Generador binario de cabecera AVI estándar
  html += "function buildAviHeader(w,h,totalFrames,fps,moviSize){";
  html += "  const buf=new ArrayBuffer(224);";
  html += "  const v=new DataView(buf);";
  html += "  const str=(pos,s)=>{for(let i=0;i<s.length;i++) v.setUint8(pos+i, s.charCodeAt(i));};";
  html += "  str(0,'RIFF'); v.setUint32(4, moviSize+224-8, true); str(8,'AVI ');";
  html += "  str(12,'LIST'); v.setUint32(16, 192, true); str(20,'hdrl');";
  html += "  str(24,'avih'); v.setUint32(28, 56, true);";
  html += "  v.setUint32(32, Math.floor(1000000/fps), true);";
  html += "  v.setUint32(44, 0x810, true); v.setUint32(48, totalFrames, true);";
  html += "  v.setUint32(56, 1, true); v.setUint32(64, w, true); v.setUint32(68, h, true);";
  html += "  str(88,'LIST'); v.setUint32(92, 116, true); str(96,'strl');";
  html += "  str(100,'strh'); v.setUint32(104, 56, true); str(108,'vids'); str(112,'MJPG');";
  html += "  v.setUint32(128, 1, true); v.setUint32(132, fps, true);";
  html += "  v.setUint32(140, totalFrames, true); v.setInt32(148, -1, true);";
  html += "  v.setInt16(160, w, true); v.setInt16(162, h, true);";
  html += "  str(164,'strf'); v.setUint32(168, 40, true); v.setUint32(172, 40, true);";
  html += "  v.setInt32(176, w, true); v.setInt32(180, h, true);";
  html += "  v.setUint16(184, 1, true); v.setUint16(186, 24, true); str(188,'MJPG');";
  html += "  v.setUint32(192, w*h*3, true);";
  html += "  str(212,'LIST'); v.setUint32(216, moviSize, true); str(220,'movi');";
  html += "  return buf;";
  html += "}";

  // Función 1: Unir y Descargar en 1 solo archivo
  html += "async function downloadMerged(){";
  html += "  const checks=Array.from(document.querySelectorAll('.file-check:checked'));";
  html += "  if(checks.length===0) return;";
  html += "  const prog=document.getElementById('progBox');";
  html += "  prog.style.display='block';";
  html += "  let payloads=[]; let totalFrames=0; let w=640; let h=480; let fps=3;";
  html += "  for(let i=0; i<checks.length; i++){";
  html += "    const fname=checks[i].value;";
  html += "    prog.innerText='⏳ Descargando clip '+(i+1)+' de '+checks.length+': '+fname+'...';";
  html += "    const res=await fetch('/download?file='+fname);";
  html += "    const ab=await res.arrayBuffer();";
  html += "    if(ab.byteLength>224){";
  html += "      const dv=new DataView(ab);";
  html += "      if(i===0){";
  html += "        w=dv.getUint32(64, true);";
  html += "        h=dv.getUint32(68, true);";
  html += "        fps=dv.getUint32(132, true) || 3;";
  html += "      }";
  html += "      const fCount=dv.getUint32(48, true);";
  html += "      totalFrames+=fCount;";
  html += "      payloads.push(new Uint8Array(ab, 224));";
  html += "    }";
  html += "  }";
  html += "  prog.innerText='⚡ Ensamblando video continuo en tu móvil...';";
  html += "  let moviSize=4;";
  html += "  payloads.forEach(p=>moviSize+=p.byteLength);";
  html += "  const headerBuf=buildAviHeader(w,h,totalFrames,fps,moviSize);";
  html += "  const finalBlob=new Blob([headerBuf, ...payloads], {type:'video/x-msvideo'});";
  html += "  const a=document.createElement('a');";
  html += "  a.href=URL.createObjectURL(finalBlob);";
  html += "  a.download='viaje_completo_'+checks[0].value;";
  html += "  document.body.appendChild(a); a.click(); a.remove();";
  html += "  URL.revokeObjectURL(a.href);";
  html += "  prog.innerText='✅ ¡Video unido y descargado con éxito!';";
  html += "  setTimeout(()=>{prog.style.display='none';},4000);";
  html += "}";

  // Función 2: Descargar archivos individuales en lote
  html += "async function downloadSeparated(){";
  html += "  const checks=document.querySelectorAll('.file-check:checked');";
  html += "  if(checks.length===0) return;";
  html += "  const prog=document.getElementById('progBox');";
  html += "  prog.style.display='block';";
  html += "  for(let i=0; i<checks.length; i++){";
  html += "    const fname=checks[i].value;";
  html += "    prog.innerText='⏳ Descargando ('+(i+1)+' de '+checks.length+'): '+fname+'...';";
  html += "    try{";
  html += "      const res=await fetch('/download?file='+fname);";
  html += "      const blob=await res.blob();";
  html += "      const a=document.createElement('a');";
  html += "      a.href=URL.createObjectURL(blob);";
  html += "      a.download=fname;";
  html += "      document.body.appendChild(a); a.click(); a.remove();";
  html += "      URL.revokeObjectURL(a.href);";
  html += "    }catch(err){console.error(err);}";
  html += "    await new Promise(r=>setTimeout(r,600));";
  html += "  }";
  html += "  prog.innerText='✅ ¡Descarga completada!';";
  html += "  setTimeout(()=>{prog.style.display='none';},4000);";
  html += "}";
  html += "</script>";

  html += "<p style='margin-top:25px;font-size:0.85em;color:#777;'>Para volver al modo grabación, apaga y enciende la dashcam sin mantener pulsado ningún botón.</p>";
  html += "</body></html>";
  server.send(200, "text/html", html);
}

void handleDownload() {
  if (!sdMounted && !tryMountSD()) {
    server.send(400, "text/plain", "MicroSD no disponible");
    return;
  }
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

void handleGetConfig() {
  if (!sdMounted && !tryMountSD()) {
    server.send(400, "application/json", "{\"error\":\"MicroSD no disponible\"}");
    return;
  }
  loadConfigFile();
  String json = "{";
  json += "\"resolution\":\"" + cfg_resolution + "\",";
  json += "\"fps\":" + String(cfg_fps) + ",";
  json += "\"clip_duration\":" + String(cfg_clip_duration) + ",";
  json += "\"quality\":" + String(cfg_quality) + ",";
  json += "\"vflip\":" + String(cfg_vflip) + ",";
  json += "\"hmirror\":" + String(cfg_hmirror) + ",";
  json += "\"brightness\":" + String(cfg_brightness) + ",";
  json += "\"contrast\":" + String(cfg_contrast) + ",";
  json += "\"saturation\":" + String(cfg_saturation) + ",";
  json += "\"wb_mode\":" + String(cfg_wb_mode) + ",";
  json += "\"firmware_version\":\"" FIRMWARE_VERSION "\"";
  json += "}";
  server.send(200, "application/json", json);
}

void handleSaveConfig() {
  if (!sdMounted && !tryMountSD()) {
    server.send(400, "application/json", "{\"error\":\"MicroSD no disponible\"}");
    return;
  }
  if (server.hasArg("fps")) cfg_fps = constrain(server.arg("fps").toInt(), 1, 15);
  if (server.hasArg("clip_duration")) cfg_clip_duration = constrain(server.arg("clip_duration").toInt(), 10, 600);
  if (server.hasArg("quality")) cfg_quality = constrain(server.arg("quality").toInt(), 10, 63);
  if (server.hasArg("resolution")) cfg_resolution = server.arg("resolution");
  if (server.hasArg("vflip")) cfg_vflip = server.arg("vflip").toInt();
  if (server.hasArg("hmirror")) cfg_hmirror = server.arg("hmirror").toInt();
  if (server.hasArg("brightness")) cfg_brightness = constrain(server.arg("brightness").toInt(), -2, 2);
  if (server.hasArg("contrast")) cfg_contrast = constrain(server.arg("contrast").toInt(), -2, 2);
  if (server.hasArg("saturation")) cfg_saturation = constrain(server.arg("saturation").toInt(), -2, 2);
  if (server.hasArg("wb_mode")) cfg_wb_mode = constrain(server.arg("wb_mode").toInt(), 0, 4);

  File cfgFile = SD_MMC.open("/dashcam.cfg", FILE_WRITE);
  if (!cfgFile) {
    server.send(500, "application/json", "{\"error\":\"Fallo al escribir dashcam.cfg\"}");
    return;
  }
  cfgFile.println("# Configuracion ESP32-CAM Dashcam");
  cfgFile.println("resolution=" + cfg_resolution);
  cfgFile.println("fps=" + String(cfg_fps));
  cfgFile.println("clip_duration=" + String(cfg_clip_duration));
  cfgFile.println("quality=" + String(cfg_quality));
  cfgFile.println("vflip=" + String(cfg_vflip));
  cfgFile.println("hmirror=" + String(cfg_hmirror));
  cfgFile.println("brightness=" + String(cfg_brightness));
  cfgFile.println("contrast=" + String(cfg_contrast));
  cfgFile.println("saturation=" + String(cfg_saturation));
  cfgFile.println("wb_mode=" + String(cfg_wb_mode));
  cfgFile.close();

  Serial.println("💾 Configuracion actualizada en /dashcam.cfg desde Web/App");
  server.send(200, "application/json", "{\"status\":\"ok\",\"message\":\"Configuracion guardada correctamente\"}");
}

void handleDeleteFile() {
  if (!sdMounted && !tryMountSD()) {
    server.send(400, "application/json", "{\"error\":\"MicroSD no disponible\"}");
    return;
  }
  if (!server.hasArg("file")) {
    server.send(400, "application/json", "{\"error\":\"Falta nombre de archivo\"}");
    return;
  }
  String filename = server.arg("file");
  if (!filename.startsWith("/")) filename = "/" + filename;

  if (!SD_MMC.exists(filename)) {
    server.send(404, "application/json", "{\"error\":\"Archivo no encontrado\"}");
    return;
  }
  if (SD_MMC.remove(filename)) {
    Serial.printf("🗑️ Archivo borrado de SD: %s\n", filename.c_str());
    server.send(200, "application/json", "{\"status\":\"ok\",\"message\":\"Archivo borrado\"}");
  } else {
    server.send(500, "application/json", "{\"error\":\"Error al borrar archivo\"}");
  }
}

void handleRenameFile() {
  if (!sdMounted && !tryMountSD()) {
    server.send(400, "application/json", "{\"error\":\"MicroSD no disponible\"}");
    return;
  }
  if (!server.hasArg("old") || !server.hasArg("new")) {
    server.send(400, "application/json", "{\"error\":\"Faltan parametros old y new\"}");
    return;
  }
  String oldName = server.arg("old");
  String newName = server.arg("new");
  if (!oldName.startsWith("/")) oldName = "/" + oldName;
  if (!newName.startsWith("/")) newName = "/" + newName;
  if (!newName.endsWith(".avi")) newName += ".avi";

  if (!SD_MMC.exists(oldName)) {
    server.send(404, "application/json", "{\"error\":\"Archivo original no existe\"}");
    return;
  }
  if (SD_MMC.exists(newName)) {
    server.send(409, "application/json", "{\"error\":\"Ya existe un archivo con ese nombre\"}");
    return;
  }
  if (SD_MMC.rename(oldName, newName)) {
    Serial.printf("✏️ Archivo renombrado en SD: %s -> %s\n", oldName.c_str(), newName.c_str());
    server.send(200, "application/json", "{\"status\":\"ok\",\"message\":\"Archivo renombrado\"}");
  } else {
    server.send(500, "application/json", "{\"error\":\"Fallo al renombrar archivo\"}");
  }
}

// Devuelve la versión y estado del firmware
void handleVersion() {
  server.sendHeader("Connection", "close");
  server.send(200, "application/json", "{\"status\":\"ok\",\"version\":\"" FIRMWARE_VERSION "\",\"model\":\"ESP32-CAM-DASHCAM\"}");
}

// Manejador de actualización de firmware por Wi-Fi (OTA)
void handleOtaResponse() {
  server.sendHeader("Connection", "close");
  if (Update.hasError()) {
    server.send(500, "application/json", "{\"status\":\"error\",\"message\":\"Fallo en la verificacion del firmware\"}");
  } else {
    server.send(200, "application/json", "{\"status\":\"ok\",\"message\":\"Firmware actualizado. Reiniciando...\"}");
    delay(500);
    ESP.restart();
  }
}

void handleOtaUpload() {
  HTTPUpload& upload = server.upload();
  if (upload.status == UPLOAD_FILE_START) {
    Serial.printf("[OTA] Iniciando actualizacion: %s\n", upload.filename.c_str());
    // Iniciar Update con partición U_FLASH
    if (!Update.begin(UPDATE_SIZE_UNKNOWN, U_FLASH)) {
      Update.printError(Serial);
    }
  } else if (upload.status == UPLOAD_FILE_WRITE) {
    if (Update.write(upload.buf, upload.currentSize) != upload.currentSize) {
      Update.printError(Serial);
    }
  } else if (upload.status == UPLOAD_FILE_END) {
    if (Update.end(true)) { // true para indicar que el tamaño se ajusta
      Serial.printf("[OTA] Firmware recibido con exito (%u bytes). Reiniciando...\n", upload.totalSize);
    } else {
      Update.printError(Serial);
    }
  } else if (upload.status == UPLOAD_FILE_ABORTED) {
    Update.end();
    Serial.println("[OTA] Actualizacion abortada");
  }
}

void setup() {
  Serial.begin(115200);

  pinMode(ONBOARD_LED_PIN, OUTPUT);
  digitalWrite(ONBOARD_LED_PIN, HIGH);
  pinMode(FLASH_LED_PIN, OUTPUT);
  digitalWrite(FLASH_LED_PIN, LOW);

  pinMode(BUTTON_PIN_13, INPUT_PULLUP);
  pinMode(BUTTON_PIN_3, INPUT_PULLUP);

  delay(150);

  bool requestedWifi = (digitalRead(BUTTON_PIN_13) == LOW || digitalRead(BUTTON_PIN_3) == LOW);

  // ================= 1. MODO WI-FI =================
  if (requestedWifi) {
    wifiMode = true;
    digitalWrite(ONBOARD_LED_PIN, LOW);

    Serial.println("\n>>> MODO WIFI ACTIVADO");
    WiFi.mode(WIFI_AP);
    WiFi.softAP("Dashcam-WiFi", "12345678");

    delay(500);
    tryMountSD();

    server.on("/", HTTP_GET, handleRoot);
    server.on("/download", HTTP_GET, handleDownload);
    server.on("/delete", HTTP_POST, handleDeleteFile);
    server.on("/rename", HTTP_POST, handleRenameFile);
    server.on("/config", HTTP_GET, handleGetConfig);
    server.on("/config", HTTP_POST, handleSaveConfig);
    server.on("/version", HTTP_GET, handleVersion);
    server.on("/status", HTTP_GET, handleVersion);
    server.on("/update", HTTP_POST, handleOtaResponse, handleOtaUpload);
    server.begin();

    Serial.println("📡 Portal listo en http://192.168.4.1");
    return;
  }

  // ================= 2. MODO DASHCAM NORMAL =================
  wifiMode = false;
  WiFi.mode(WIFI_OFF);
  btStop();

  setCpuFrequencyMhz(160);

  if (!tryMountSD()) {
    Serial.println("❌ ERROR: Fallo al montar MicroSD (¿Formateada en FAT32?)");
    blinkError(3);
    return;
  }

  uint8_t cardType = SD_MMC.cardType();
  if (cardType == CARD_NONE) {
    Serial.println("❌ ERROR: No se detecta tarjeta en la ranura");
    blinkError(4);
    return;
  }

  loadConfigFile();

  while (fileIndex < 9999) {
    char testPath[32];
    sprintf(testPath, "/dash_%04d.avi", fileIndex);
    if (SD_MMC.exists(testPath)) {
      fileIndex++;
    } else {
      break;
    }
  }

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
  
  config.frame_size = cameraFrameSize;
  config.jpeg_quality = cfg_quality;
  config.fb_count = 2;

  if (esp_camera_init(&config) != ESP_OK) {
    Serial.println("❌ ERROR al iniciar la cámara OV2640");
    blinkError(2);
    return;
  }

  sensor_t *s = esp_camera_sensor_get();
  if (s != NULL) {
    s->set_vflip(s, cfg_vflip);
    s->set_hmirror(s, cfg_hmirror);
    s->set_brightness(s, cfg_brightness);
    s->set_contrast(s, cfg_contrast);
    s->set_saturation(s, cfg_saturation);
    s->set_wb_mode(s, cfg_wb_mode);
  }

  Serial.printf("📹 Grabando clips a %d FPS (%dx%d)...\n", cfg_fps, videoWidth, videoHeight);
}

void recordClip() {
  checkStorageSpace();

  // Comprobar que realmente haya espacio para iniciar un nuevo clip (mínimo 100 MB libres)
  uint64_t totalBytes = SD_MMC.totalBytes();
  uint64_t usedBytes = SD_MMC.usedBytes();
  if (totalBytes > 0 && (totalBytes - usedBytes) < 100ULL * 1024ULL * 1024ULL) {
    Serial.println("⚠️ Espacio crítico insuficiente en MicroSD. Esperando loop cleanup...");
    delay(2000);
    return;
  }

  char filename[32];
  sprintf(filename, "/dash_%04d.avi", fileIndex++);
  File aviFile = SD_MMC.open(filename, FILE_WRITE);
  if (!aviFile) {
    Serial.printf("❌ Error al abrir %s para escritura\n", filename);
    delay(1000);
    return;
  }

  writeAviHeader(aviFile, videoWidth, videoHeight, framesPerClip, cfg_fps);
  aviFile.seek(AVI_HEADER_SIZE);

  int framesWritten = 0;
  Serial.printf("[REC] Grabando: %s (%d cuadros)\n", filename, framesPerClip);

  for (int i = 0; i < framesPerClip; i++) {
    unsigned long startMs = millis();

    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) {
      Serial.println("Error frame");
      continue;
    }

    size_t w1 = aviFile.write((const uint8_t*)"00dc", 4);
    uint32_t frameSize = fb->len;
    size_t w2 = aviFile.write((const uint8_t*)&frameSize, 4);
    size_t w3 = aviFile.write(fb->buf, fb->len);

    if (frameSize % 2 != 0) {
      uint8_t zero = 0;
      aviFile.write(&zero, 1);
    }

    esp_camera_fb_return(fb);

    // Si falló la escritura en la SD (tarjeta llena o error I/O), detener el clip para no corromper la FAT
    if (w1 != 4 || w2 != 4 || w3 != frameSize) {
      Serial.println("❌ Fallo en escritura física SD. Cerrando clip preventivamente...");
      break;
    }

    framesWritten++;

    // Flush periódico cada 30 cuadros (~10 segundos) para no estresar el controlador SD con 3 flush/seg
    if (framesWritten % 30 == 0) {
      aviFile.flush();
    }

    digitalWrite(ONBOARD_LED_PIN, LOW);
    delay(20);
    digitalWrite(ONBOARD_LED_PIN, HIGH);

    unsigned long elapsed = millis() - startMs;
    if (elapsed < frameIntervalMs) {
      delay(frameIntervalMs - elapsed);
    }
  }

  writeAviHeader(aviFile, videoWidth, videoHeight, framesWritten, cfg_fps);
  aviFile.flush();
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
