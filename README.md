# Salud Batería

App Android (Kotlin, sin librerías externas) que hace con el móvil algo parecido a `powercfg /batteryreport` de Windows:

- **Ahora mismo**: nivel, estado, corriente (mA), potencia, voltaje, temperatura, contador de carga, ciclos y salud que informe el sistema.
- **Registro de cargas**: un servicio en segundo plano apunta cada carga (inicio, fin, % de subida, mAh entrados, temperatura máxima).
- **Capacidad real estimada** con dos métodos independientes:
  - *Corriente medida*: integra la corriente cada 20 s mientras carga (como AccuBattery).
  - *Contador del chip*: diferencia del contador de carga del medidor de la batería.
- **Salud estimada** = capacidad estimada / capacidad de diseño (media ponderada de las 10 últimas cargas de ≥ 15 %).
- Gráfica de evolución y exportación del historial a CSV.

Requisitos: Android 12 o superior (probado para Android 16 / MagicOS 10).

## Compilar en un PC (Android Studio)

1. Instala [Android Studio](https://developer.android.com/studio).
2. *File > Open* y elige esta carpeta. Deja que sincronice (descarga Gradle y el SDK la primera vez).
3. *Build > Build App Bundle(s) / APK(s) > Build APK(s)*.
4. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Compilar sin instalar nada (GitHub Actions)

Cada `push` lanza el workflow **Compilar APK**. En la pestaña *Actions* del repositorio, abre la última ejecución y descarga el artefacto `SaludBateria-apk` (es un .zip con el APK dentro). También se puede lanzar a mano con *Run workflow*.

## Instalar en el móvil

1. Pasa el APK al móvil y ábrelo; permite "instalar apps desconocidas" para el gestor de archivos o el navegador.
2. Abre la app y acepta las notificaciones.
3. Toca **No optimizar batería**.
4. En MagicOS: *Ajustes > Batería > Inicio de aplicaciones > Salud Batería > Gestionar manualmente* y activa las tres opciones.

## Cómo leer los datos

- La primera estimación aparece tras una carga de al menos un 15 % seguido. Con 5–10 cargas el dato es estable.
- Si "Capacidad según el chip" coincide siempre con la de diseño, el fabricante calcula el contador a partir del %: fíate más de la estimación por corriente medida.
- La capacidad de diseño se intenta leer del sistema; si no aparece o no es correcta, indícala en *Ajustes*.
