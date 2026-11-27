# Sistema visual de Biblioteca IA

## Dirección y referencias

La referencia recuperable es la descripción de la conversación: biblioteca oscura con portadas protagonistas, superficies grafito, acentos cálidos, jerarquía limpia y lector negro OLED inmersivo. La imagen original no está accesible; no se afirma haberla reproducido. Implementación nativa Kotlin + XML con Material Components 1.12.0 ya existente, tema Material 3 oscuro personalizado; sin nuevas dependencias.

## Contrato compartido

Recursos en `res/values/design.xml`; tema estable `Theme.BibliotecaIA` en `themes.xml`.

| Token | Valor | Uso |
|---|---|---|
| `bia_background` | #080807 | Fondo de superficies de la aplicación; OLED puro se resuelve en el lector |
| `bia_surface` | #1B1A18 | Tarjetas y paneles |
| `bia_surface_high` | #28241F | Portada ausente y superficies elevadas |
| `bia_text` | #F4E8D7 | Texto cálido principal |
| `bia_text_secondary` | #B8B0A6 | Autor, estados y ayudas |
| `bia_accent` | #E9C282 | Acción primaria, formato, selección |
| `bia_outline` | #49443D | Separadores y pistas de progreso |

Interfaz sans-serif del sistema, título de biblioteca y portada ausente con serif del sistema. Título de biblioteca 24sp; acceso reciente de 14sp; datos secundarios de 10–12sp. Espaciado base 4/8/12/16/20dp. Tarjetas 16dp de radio, acción primaria 18dp; controles táctiles de al menos 48dp. Sin fuentes descargadas, fondos animados ni conexiones de red para portadas.

## Evolución de noviembre de 2026

La biblioteca principal reutiliza **Material Components / Material 3**, sin añadir otro kit visual ni aumentar dependencias. La reanudación se muestra en una franja de 56 dp mínimos con miniportada de 32 × 44 dp; la rejilla tiene prioridad visual, con tarjetas más contenidas y acceso de un toque a la lectura. El modo predeterminado es rejilla si aún no existe preferencia personal de lista. Véase `docs/ux-premium-compact-20261121.md`. Esta mejora no modifica el contenido ni los gestos de lectura.

## Biblioteca implementada

- Encabezado editorial, aviso claro de almacenamiento local, búsqueda Material 3, estantes horizontales y contador de resultados.
- Cuadrícula por defecto; preferencia anterior de lista/cuadrícula conservada. Entre 2 y 6 columnas según ancho útil, con presentación de lista independiente.
- Portadas EPUB reales declaradas por EPUB2 (`meta name=cover`) o EPUB3 (`cover-image`). Escalado contenido para no recortar la portada.
- Cuando no hay portada compatible, ficha tipográfica con rótulo **SIN PORTADA**, sin imágenes ficticias. PDF/MOBI conservan esta presentación hasta incorporar un extractor específico.
- Título, autor, formato, favorito y progreso real. El catálogo continúa abriendo la ficha; el coordinador integra el acceso a motores de lectura.
- Estados de biblioteca vacía, filtros sin coincidencias e importación en curso; cancelar el selector no modifica datos. La importación bloquea un segundo toque mientras está activa.

## Seguridad y rendimiento de portadas

Extracción con ZipFile, sin escribir entradas ZIP en disco ni resolver URLs remotas. Máximo 10.000 entradas, XML 512 KiB por documento, imagen comprimida 8 MiB, dimensiones de entrada máximas 20.000 px. XML UTF-8 decodificado antes de analizar, DTD y declaraciones de entidades prohibidas; resolver de entidades rechaza recursos externos. Otras codificaciones se consideran portada no disponible. Referencias relativas normalizadas dentro del archivo, sin escapes de raíz.

Decodificación fuera del hilo UI, una extracción simultánea, muestreo a 360 × 540 px como máximo, caché de bitmaps de 8 MiB y caché de ausencia de 256 entradas. Reciclado cancela resultados de tarjetas anteriores; el ciclo de vida cancela tareas. No se altera el original ni la base de datos.

## Lector y navegación pendiente de integración

El lector debe reutilizar negro puro y texto cálido, mostrar controles a demanda y mantener índice/anotaciones accesibles. Los otros temas deben aplicarse al contenido, sin presentar blanco puro como tema OLED. Biblioteca, Leer y Conocimiento deben ofrecer acciones reales; no se añaden destinos simulados. Paneles de tablet deben integrarse con los motores, no únicamente dibujarse.

## Verificación y límites

Pruebas focalizadas: EPUB2/EPUB3, nombres codificados, ausencia de portada declarada, URL externa, escape de raíz, exceso de tamaño y entidades XML. Contraste calculado conforme a luminancia sRGB: texto/fondo 16.57:1; secundario/fondo 9.35:1; acento/fondo 11.93:1 (recalcular con los valores del archivo si se cambian).

Verificado localmente en Windows/JDK17: `:app:assembleDebug :app:testDebugUnitTest --tests '*EpubCoverSourceTest' --no-daemon --max-workers=1`, BUILD SUCCESSFUL; 8 tests, 0 fallos/errores; APK debug generado (5.971.710 bytes). La integración se registra en DEVELOPMENT_STATE por el coordinador. Revisión visual real, TalkBack, fuente ampliada, tablet y AndroidIDE ARM64 pendientes hasta disponer de dispositivo/emulador. No se afirma ahorro energético medido.

