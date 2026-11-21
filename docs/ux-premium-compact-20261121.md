# Biblioteca IA — Biblioteca protagonista y lectura no invasiva (21/11/2026)

## Decisión técnica

Biblioteca IA ya utiliza **Material Components for Android 1.12.0**, con el tema
`Theme.Material3.Dark.NoActionBar`, tarjetas Material, campos Material 3 y
controles accesibles. Ese es el framework profesional elegido, incorporado
y descargado por Gradle durante la compilación. **No se añade un segundo
framework de diseño**: aumentaría el tamaño y el coste de mantenimiento de
la APK sin mejorar la lectura. Se conserva Kotlin + XML y compatibilidad
con AndroidIDE, SDK 34 y Java 17.

## Contrato visual implementado

- **Biblioteca protagonista:** cabecera editorial más baja, título de 27 sp y
  espacio vertical reducido alrededor de filtros y recuento.
- **«Continuar leyendo»:** acceso en la parte superior, solo cuando hay un
  último libro, con miniatura real de **32 × 44 dp**, título en una línea,
  porcentaje leído y barra fina. Tamaño táctil mínimo 56 dp, sin banners ni
  promociones. Un toque conserva la apertura del lector y el progreso real.
- **Rejilla:** la vista por defecto pasa a cuadrícula **solo si el usuario no
  eligió antes un modo**. El selector lista/cuadrícula y su preferencia siguen
  funcionando; el catálogo dispone de todo el alto flexible de la pantalla.
- **Tarjetas:** portadas sin recortes, separación discreta, tarjetas de rejilla
  menos altas, metadatos visibles y acento cálido moderado. Las portadas
  faltantes muestran tipografía neutra, no imágenes inventadas.
- **Navegación fija:** Biblioteca / Leer / Importar / Conocimiento se mantienen
  en el mismo orden, sin crear botones o funcionalidades nuevas.
- **Lectores:** se preservan los temas de lectura (incluido OLED), gestos,
  selección, subrayados, anotaciones y consulta de IA. Ningún ornamento nuevo
  invade el contenido del libro.

## Verificación exigida antes de publicar

1. Compilar app/debug con Gradle Java 17/SDK 34, AndroidIDE o CI oficial.
2. Ejecutar `CompactLibraryHomeDeviceTest` y
   `LibraryHomeUxDeviceTest`; confirmar cuatro acciones fijas y reanudación.
3. Inspeccionar el XML efectivamente renderizado en un emulador de ~360 dp,
   móvil pequeño, fuente al 200 %, y con títulos de libro muy largos.
4. Contrastar una APK **posterior a v0.1.35**, ya que esa versión fue
   reportada por el usuario como visualmente inalterada. No declarar éxito
   en dispositivo físico sin evidencia.

**Estado inicial:** implementación subida a rama feature, sin afirmar
validación física ni publicación.
