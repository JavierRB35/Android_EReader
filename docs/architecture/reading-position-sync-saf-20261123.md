# Sincronización incremental BibliotecaAI — fase 1: posiciones de lectura

## Objetivo y límites

Se aprobó sincronizar progresivamente **posiciones, notas, subrayados, argumentos,
etiquetas y colecciones**. La primera fase implementa exclusivamente posiciones
de lectura entre 1–3 dispositivos de la misma biblioteca. No anuncia
sincronización de notas u otros metadatos todavía.

**Sin OAuth, sin cuenta dentro de BibliotecaAI, sin Cloudflare, Supabase ni Apps
Script.** Android Storage Access Framework (SAF) delega al proveedor de Drive
u otra ubicación la cuenta y los permisos.

## Una archivo por dispositivo, no un JSON compartido con varios escritores

Un único JSON o ZIP abierto por varios dispositivos puede sufrir sobrescrituras
simultáneas. Con SAF no existe compare-and-swap transaccional de documentos
remotos. Por eso **cada dispositivo crea un JSON propio**:
`BibliotecaIA-lectura-<id>.json`, que es el ÚNICO que escribe.
Cada dispositivo vincula como archivos **de solo lectura** los JSON de sus
otros dispositivos en Ajustes; los permisos SAF quedan persistidos. No se
requiere soporte de carpetas (`ACTION_OPEN_DOCUMENT_TREE`) del proveedor.
El vínculo requiere selección inicial manual de archivos en cada dispositivo;
SAF no permite descubrir libremente todos los archivos de Drive.

Al abrir la biblioteca se leen los JSON enlazados y se combinan por SHA-256
del contenido del libro —nunca por el UUID local, que puede variar entre
dispositivos—. Se incorpora a Room la posición más reciente para el mismo
libro. Mientras se lee, las posiciones se guardan localmente y se publica
el JSON propio de forma diferida. No se escribe ningún JSON ajeno.

La última posición **no es la más avanzada**, sino el último movimiento real.
El mero paso del tiempo o una consulta periódica no debe cambiar `updatedAt`.
El desempate de una marca temporal exacta es determinista.

## Concurrencia y limitaciones explícitas

- Al usar archivos diferentes por dispositivo, no hay dos escritores del
  mismo JSON; las lecturas pueden fallar transitoriamente ante retrasos de
  Google Drive y reintentarse.
- Sin un servidor con reloj y transacciones, movimientos simultáneos realizados
  sin conectividad o con relojes desajustados no tienen un orden global
  demostrable. El primer piloto no debe prometer orden perfecto en tales casos.
- El usuario debe seleccionar los propios documentos en todos sus dispositivos.
  No debe escoger el mismo JSON como archivo de escritura en dos dispositivos.
- No se envían ebooks ni secretos en los JSON. Los localizadores pueden contener
  información de lectura y el archivo **no está cifrado**. Elegir ubicación
  privada y no compartir los documentos públicamente.
- El ZIP de copia completa es un mecanismo de recuperación diferente.
  **Cuando hay pares vinculados se desactiva su sobrescritura automática al
  entrar en la biblioteca**, para evitar que un dispositivo antiguo sobrescriba
  el ZIP más reciente. La exportación manual en Ajustes sigue disponible, pero
  **todavía no cuenta con una conciliación multi-dispositivo segura**: no usarla
  para turnarse entre dispositivos hasta implementar la fase de protección de ZIP.
  La restauración completa sigue siendo manual. Antes de ampliar la sincronización a todos los metadatos es obligatorio
  impedir que un dispositivo sobrescriba un ZIP compartido nuevo con una copia
  antigua y definir la política de conflictos de anotaciones.
- El soporte de documentos SAF de Drive depende de su proveedor, disponibilidad
  de red y permisos. No se garantiza la sincronización durante segundo plano
  indefinido ni ejecución exacta de tareas periódicas.

## Siguientes fases

1. Posiciones (esta fase): URI persistentes, propiedad del fichero, identidad
   por hash, importación aditiva y tests JVM e instrumentados.
2. Anotaciones/subrayados: registros inmutables y lápidas/versiones sin perder
   ediciones concurrentes, con reconciliación idempotente.
3. Argumentos, etiquetas y colecciones, incluidas bajas y conflictos;
   respaldo ZIP revisado para evitar sobrescrituras entre dispositivos.
4. QA real con dos móviles conectados al mismo Drive, ambos lectores abiertos,
   lectura offline, reconexión, borrado de archivos locales, revocación SAF y
   relojes desajustados. No publicar como robustez probada sin ese ensayo.
