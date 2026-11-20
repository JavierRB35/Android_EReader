# PDF y MOBI: viabilidad e incremento de lectura

Fecha: 2026-11-20. Rama de trabajo: `feature/reader-pdf-mobi`. Integración y estado global: `docs/DEVELOPMENT_STATE.md`.

## Decisión y alcance real

Este incremento añade lectura real; no completa por sí solo todos los requisitos del incremento 3.

| Formato | Implementado | Pendiente |
|---|---|---|
| PDF | Renderizado local, navegación por página, zoom/pan, ajuste a anchura, posición persistente, nota referida a página | Extracción y búsqueda textual, selección, subrayado por coordenadas, OCR, índice, recorte de márgenes, IA visual |
| MOBI 1–6 sin DRM | Decodificación real de texto sin compresión o PalmDOC, UTF-8/Windows-1252, búsqueda, selección con notas, navegación por tramos, posición por carácter, tamaño de letra y cuatro temas | HUFF/CDIC, KF8, imágenes/tablas maquetadas, índice del documento y conversión a EPUB |

No se convierten libros en servidor ni se envían a terceros. Los originales permanecen intactos. La vista MOBI indica «MOBI texto»: no promete reproducir la maquetación del original.

## PDF

Se reutiliza [PdfRenderer de Android](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer), disponible desde API 21 y compatible con minSdk 26/compileSdk 34. No requiere librería, NDK ni cambio de baseline. Las APIs modernas de contenido/selección de página aparecen en SDK posteriores; no se invocan sobre SDK34 ni se simulan. La alternativa para completar selección/OCR se evaluará por separado.

El constructor y renderizado trabajan fuera del hilo principal, en un servicio aislado. Se entrega únicamente un descriptor de lectura del libro seleccionado; el proceso no recibe rutas de la biblioteca ni permisos de cuenta. Las respuestas transfieren PNG por un descriptor de pipe, evitando enviar bitmaps grandes en una transacción Binder. La imagen se limita a 3.000.000 píxeles y 4096 por lado. No se invierten los colores del PDF: sus figuras y colores conservan el significado.

Declaraciones necesarias en el Manifest integrado:

```xml
<activity android:name=".reader.pdf.PdfReaderActivity" android:exported="false" />
<service android:name=".reader.pdf.PdfRenderService"
    android:exported="false" android:isolatedProcess="true" android:process=":pdf_renderer" />
<activity android:name=".reader.mobi.MobiReaderActivity" android:exported="false" />
```

La protección/cifrado PDF incompatible produce un error explícito. No se implementa evasión de contraseñas. El aislamiento reduce el alcance del parser nativo; no constituye una afirmación de seguridad absoluta. El cierre de la Activity libera el servicio y los descriptores; las páginas se cierran después de cada renderizado. La interfaz conserva el último localizador verificado, nunca una página cuya carga falló.

## MOBI

Se investigaron [libmobi](https://github.com/bfabiszewski/libmobi), [KindleUnpack](https://github.com/kevinhendricks/KindleUnpack) y [KOReader](https://github.com/koreader/koreader). Libmobi es una biblioteca C LGPL que requiere integración nativa y validación de compilación ARM64; KindleUnpack usa Python; KOReader es una aplicación completa. No se incorporan ni se presupone que un ejecutable de escritorio funcione en AndroidIDE. La integración futura de libmobi continúa siendo una opción para el soporte amplio.

Para este incremento se implementa un subconjunto pequeño en Kotlin/JVM con límites explícitos, validable sin depender de otra toolchain. La estructura y campos se contrastaron con el lector de cabeceras de KindleUnpack. No se copia ni incorpora código de esas bibliotecas. La decodificación PalmDOC implementa los tokens del formato con comprobación de distancias y longitud.

Límites: texto máximo 8 MiB, registro máximo 1 MiB, salida descomprimida limitada al tamaño declarado del registro (hasta 65535 bytes). Se validan cabecera, tabla de registros ordenada, offsets dentro del fichero, firma, versión, codificación, longitudes, back-references y datos de cola. El cifrado se comprueba antes de interpretar una cabecera MOBI potencialmente cifrada. También se rechaza la declaración de registros DRM.

El parser rechaza HUFF/CDIC, KF8, DRM y codificaciones desconocidas de forma explícita. Los errores de UTF-8 no se sustituyen silenciosamente, para evitar alterar los localizadores. El HTML se reduce a texto nativo con Android Html; no hay WebView, JavaScript, imágenes remotas ni enlaces activados. Los tramos de 12.000 caracteres limitan el coste de layout; no se presentan como páginas o capítulos del libro.

## Contratos

- `PdfReaderActivity.start(context, bookId, locatorJson = null)` y `MobiReaderActivity.start(...)` abren un libro de Room.
- PDF: `{"format":"PDF","page":0}` (base cero). La nota refiere a toda la página; no inventa selección textual ni coordenadas.
- MOBI: `{"format":"MOBI","offset":123,"textVersion":1}`. Offset UTF-16 sobre texto extraído normalizado de esta versión; las citas conservan el fragmento en el modelo común. Una futura modificación del extractor debe respetar `textVersion` o migrar/verificar las citas.
- Ambos usan `ReadingRepository` para persistencia y `AnnotationEditor.show` para notas. No duplican bases de datos.

## Evidencias y límites de validación

- `:app:compileDebugKotlin`: **BUILD SUCCESSFUL** en Windows/JDK17/SDK34, sin elevar versiones. No es una compilación en AndroidIDE ni prueba ARM64.
- `:app:testDebugUnitTest --tests com.javierrb35.bibliotecaia.MobiDocumentTest`: **21/21 aprobadas**, contenedores sintéticos sin DRM, compresión y solapamiento, codificaciones, truncamientos, límites, offsets y rechazo de formatos no soportados. El primer pase capturó el caso DRM legado contra el parser anterior; el pase final recompilado incluye la corrección y aprueba la regresión.
- Prueba de viabilidad con muestras reales del proyecto libmobi (descargadas temporalmente; no incluidas en Git): `sample-cp1252.mobi` devuelve 89.348 caracteres; `sample-unicode-uncompressed.mobi` devuelve 111.701. HUFF se rechaza explícitamente. La muestra DRM v1 permitió detectar y corregir el orden de validación de la cabecera; el probe repetido sobre el parser final devuelve `Unsupported` con mensaje DRM.
- La comprobación visual/integración del servicio PDF, reapertura, anotaciones y navegación corresponde al coordinador sobre el APK integrado. No se declara ejecutada aquí.
- Pendiente dispositivo real ARM64/AndroidIDE, PDFs grandes o especialmente complejos, presión de memoria, accesibilidad TalkBack y corpus MOBI representativo. Las limitaciones funcionales de la tabla siguen abiertas aunque compile.
