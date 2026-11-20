# EPUB: viabilidad e implementación

Fecha: 2026-11-20. Rama: `feature/reader-epub`.

## Decisión

Readium Kotlin Toolkit **3.0.3** (shared, streamer, navigator), distribuido por Maven Central. La tabla oficial identifica la línea 3.0 como compatible con compileSdk 34. La línea 3.1 eleva el requisito; no se actualizan las versiones base del proyecto. Se activa core library desugaring con 2.0.4, la versión de la propia distribución 3.0.3. Jsoup 1.17.2 ya es dependencia de Readium y se declara directamente porque la capa de seguridad lo utiliza.

Fuentes consultadas:

- [Requisitos oficiales](https://github.com/readium/kotlin-toolkit/tree/3.0.3)
- [Apertura de publicaciones](https://github.com/readium/kotlin-toolkit/blob/3.0.3/docs/guides/getting-started.md)
- [Navigator, selección y localizadores](https://github.com/readium/kotlin-toolkit/blob/3.0.3/docs/guides/navigator/navigator.md)
- [Guía de migración 3.0](https://github.com/readium/kotlin-toolkit/blob/3.0.3/docs/migration-guide.md)
- [Versiones en Maven Central](https://repo.maven.apache.org/maven2/org/readium/kotlin-toolkit/readium-navigator/maven-metadata.xml)

## Recorrido implementado

`EpubReaderActivity.start(context, bookId, locatorJson?)` abre el original de la biblioteca. Integra índice, páginas anterior/siguiente y toque en bordes, desplazamiento vertical opcional, búsqueda acotada a 100 resultados, cuatro temas, tamaño y familia de letra, marcadores, selección → anotación y dibujo de subrayados persistentes. El toque central muestra u oculta los controles. Ajustes guardados localmente; OLED usa #000000 y texto #E8DFCE.

Posición y anotaciones utilizan Readium Locator JSON completo, no números de página dependientes de la tipografía. `ReadingRepository` es el contrato persistente compartido: posición con debounce de 900 ms y guardado al salir, observación de anotaciones y referencias al libro original. El padre registra la Activity en Manifest y conecta la ficha y el cuaderno.

La Activity recrea el navegador con su publicación después de rotación o muerte de proceso, restaurando el localizador guardado. No intenta restaurar un Fragment que carezca de Publication/FragmentFactory.

## Seguridad y límites

- No se modifica ni extrae el ZIP original. Se valida antes de abrir: 10 000 entradas, 32 MiB por recurso, 4 MiB por capítulo/CSS, 256 MiB inflados totales; se rechazan rutas peligrosas y duplicadas. Los límites se aplican a bytes leídos reales.
- Se eliminan scripts, manejadores de eventos, formularios, marcos y contenido activo del libro en la vista renderizada. Las páginas reciben una política CSP que limita los recursos a Readium local y bloquea conexiones externas. SVG elimina animaciones activas, estilos y referencias externas.
- El cliente HTTP del motor está bloqueado. Los enlaces externos no se abren automáticamente. El libro no decide permisos, peticiones IA ni operaciones del sistema.
- No se elude DRM. EPUB protegido/restringido se rechaza con explicación conservando el original.
- Los EPUB interactivos dependientes de scripts no son compatibles con este modo seguro. Los archivos que excedan los límites se rechazan explícitamente.

## Evidencia

- `:app:checkDebugAarMetadata` con Readium 3.0.3: **BUILD SUCCESSFUL**, 44 s, SDK34 / AGP8.7.3 / Gradle8.9 / JDK17, `JAVA_OPTS=-Xmx768m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8`, Windows. Esto acredita compatibilidad de metadatos, no funcionamiento en teléfono.
- Compilación del lector, pruebas de seguridad y recorrido en emulador: en curso al redactar; actualizar antes del cierre.
- AndroidIDE-Rv2 ARM64: **pendiente**. No hay evidencia de compilación ni instalación en el teléfono.

Fixture sintética sin libros privados: `app/src/androidTest/assets/reader-sample.epub`, tres capítulos con índice y texto repetido para búsqueda, selección, posición y notas. El coordinador verifica el recorrido integrado.

## Pendiente para dar por completo el motor

Verificación visual integrada, EPUB variados (imágenes, fuentes, fijo, RTL), accesibilidad ampliada, navegación histórica, TTS, ajustes de interlineado/márgenes/brillo y pruebas de memoria en el teléfono. Esta rebanada no debe etiquetarse como cumplimiento total de §8.2/9.
