# 🚗 ESP32-CAM Ultra-Low Power Dashcam (3 FPS)

Dashcam eficiente, estable y sin sobrecalentamiento basada en el **ESP32-CAM estándar (AI-Thinker con sensor OV2640)**. 

Diseñada específicamente para operar en automóviles durante horas continuas bajo el sol sin colgarse por *brownouts* (caídas de tensión) ni calentarse, grabando video **AVI (Motion-JPEG)** en bucle y permitiendo la descarga inalámbrica de los clips mediante un portal Wi-Fi activado por hardware.

---

## ✨ Características Principales

* **3 FPS Estables en formato AVI:** Graba clips independientes de 1 minuto en contenedor `.avi` estándar (reproducible directamente en PC, VLC, Android y iOS).
* **Control Térmico Total (Fría al tacto):**
  * Radios Wi-Fi y Bluetooth desactivados al 100% durante el viaje.
  * Reloj de CPU reducido a 160 MHz.
  * Consumo reducido a apenas ~110 mA a 5V (< 0.6W).
* **Grabación Cíclica (Loop Recording):** Cuando el espacio libre en la tarjeta MicroSD es menor a 100 MB, elimina automáticamente el clip más antiguo.
* **Flash LED Bloqueado:** Utiliza el bus SDMMC en modo de 1 bit para liberar el **GPIO 4** y mantener el potente flash apagado en todo momento.
* **Modo Descarga Wi-Fi por Pulsador en el Arranque:**
  * Al encender el coche normalmente, la cámara graba y el pulsador queda completamente inactivo durante la conducción (evita cortes accidentales).
  * Si mantienes presionado el pulsador **al momento de dar energía**, el microcontrolador no inicializa la cámara ni graba, sino que levanta un Punto de Acceso Wi-Fi (`Dashcam-WiFi`) con servidor web para ver y descargar los videos directamente al teléfono o PC.

---

## 🛠️ Conexiones y Hardware

### Diagrama del Pulsador de Modo Wi-Fi
En modo SDMMC de 1 bit, el pin **GPIO 13** queda totalmente libre:

```text
  [ ESP32-CAM ]
     IO13  o-------[ Pulsador Normal Abierto ]-------o  GND
```
*No requiere resistencia externa (el firmware utiliza la resistencia interna `INPUT_PULLUP`).*

### Alimentación y Estabilidad (¡Crucial!)
1. **Condensador de Desacoplo:** Soldar un capacitor electrolítico de **470 µF a 1000 µF (10V o 16V)** directamente entre los pines **5V y GND** del ESP32-CAM. La MicroSD genera picos transitorios de corriente al escribir; el capacitor evita que se dispare el *Brownout Detector* y la cámara se reinicie.
2. **Fuente de Alimentación:** Utilizar un convertidor DC-DC (12V a 5V / 2A mínimo) conectado al mechero o a la fusiblera del vehículo.
3. **Tarjeta MicroSD:** Formateada en FAT32 (recomendada Clase 10 o U1/U3 de 8 GB a 64 GB).

---

## 🚀 Cómo Funciona

### 1. Modo Normal (Dashcam)
1. Enciende el vehículo con normalidad (sin presionar ningún botón).
2. El sistema arranca en menos de 2 segundos.
3. Desactiva Wi-Fi/BT, bloquea el flash y comienza a grabar clips numerados:
   ```text
   /dash_0000.avi
   /dash_0001.avi
   /dash_0002.avi
   ...
   ```
4. El pulsador queda inerte en este modo; si alguien lo presiona mientras manejas, no afectará la grabación.

### 2. Modo Descarga Wi-Fi (Ver videos en el móvil)
1. Con la dashcam apagada, mantén presionado el **Pulsador (IO13 a GND)**.
2. Conecta la alimentación / pon contacto en el auto.
3. El LED rojo trasero (GPIO 33) se encenderá de forma fija indicando que está en **Modo Wi-Fi**. Ya puedes soltar el pulsador.
4. Conéctate desde tu teléfono móvil o laptop a la red:
   * **SSID:** `Dashcam-WiFi`
   * **Contraseña:** `12345678`
5. Abre el navegador web e ingresa a:
   ```text
   http://192.168.4.1
   ```
6. Se mostrará una lista con todos los videos guardados, su tamaño en MB y un botón para descargarlos inmediatamente.
7. Al apagar y volver a encender sin tocar el botón, volverá al modo dashcam habitual.

---

## ⚡ Cómo Flashear el Firmware

Tienes dos métodos disponibles:

### Método 1: Flasheo rápido con `esptool` (Recomendado)
El repositorio incluye el binario combinado listo para usar: `bin/merged.bin`.

1. Conecta el ESP32-CAM a tu PC mediante un módulo FTDI/USB-TTL o base ESP32-CAM-MB:
   * **GND** a GND
   * **5V** a 5V
   * **U0TXD** a RX del adaptador
   * **U0RXD** a TX del adaptador
   * **GPIO 0 conectado a GND** (para entrar en modo descarga/flash).
2. Presiona el botón *RST* del ESP32-CAM.
3. Ejecuta el comando de flasheo (ajusta `/dev/ttyUSB0` o `COM3` según corresponda):

```bash
# Flasheo completo con el archivo combinado (dirección 0x0)
python3 -m esptool --chip esp32 --port /dev/ttyUSB0 --baud 460800 write_flash 0x0 bin/merged.bin
```
4. Desconecta el cable entre **GPIO 0 y GND** y presiona *RST* para reiniciar en modo normal.

---

### Método 2: Compilar con Arduino IDE o Arduino-CLI

#### Con Arduino-CLI:
```bash
arduino-cli compile -b esp32:esp32:esp32cam esp32cam_dashcam.ino
arduino-cli upload -p /dev/ttyUSB0 -b esp32:esp32:esp32cam
```

#### Con Arduino IDE:
1. Instala el paquete **esp32** de Espressif en el Gestor de Tarjetas (versión 3.x recomendada).
2. Selecciona la placa: **AI Thinker ESP32-CAM**.
3. Opciones de placa:
   * **CPU Frequency:** 160MHz (o 240MHz)
   * **Flash Frequency:** 80MHz
   * **Flash Mode:** QIO
   * **Partition Scheme:** Huge APP (3MB No OTA / 1MB SPIFFS)
4. Abre `esp32cam_dashcam.ino` y haz clic en **Subir** (recuerda puentear GPIO 0 a GND antes de reiniciar para programar).

---

## 📄 Licencia

Código abierto bajo licencia MIT. ¡Siéntete libre de modificarlo o adaptarlo a tus necesidades!
