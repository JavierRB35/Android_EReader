# Migrador Calibre → BibliotecaAI (Windows)

Herramienta Windows portátil, independiente y local. No requiere cuenta nueva ni instala servicios. No modifica Calibre ni escribe en la biblioteca activa desde Windows.

## Uso

1. Cierra Calibre. Selecciona la raíz de la biblioteca (la carpeta de metadata.db).
2. Selecciona una carpeta temporal **fuera de Calibre y de la carpeta activa de BibliotecaAI**. Puede ser una carpeta de intercambio de Google Drive.
3. Pulsa Analizar y Preparar para BibliotecaAI. Revisa Informe.csv y los ZIP verificados.
4. En un Android compatible ya vinculado a la biblioteca única: Ajustes → **Importar biblioteca de Calibre** → Seleccionar copia. Selecciona cada Migracion-NNN.zip. Este flujo llama a SharedLibrary.migrateZip y publica los originales y su catálogo en la biblioteca existente, no crea otra biblioteca. No usar la opción de restaurar/sustituir el respaldo activo.
5. Comprueba en ambos dispositivos los libros, títulos, autores y etiquetas, ausencia de duplicados, apertura de originales y conservación de notas previas. Prueba primero con una biblioteca de Calibre pequeña y sintética. Solo después de comprobar el resultado, elimina los ZIP temporales si no necesitas conservarlos.

## Contrato y límites

- Por defecto conserva todos los formatos compatibles EPUB y MOBI (PDF se informa y no se incorpora). Si se desmarca la opción, prefiere EPUB > MOBI y usa alternativas ante archivos inválidos. Cada formato distinto se registra como original independiente; SHA-256 deduplica contenido exacto, no títulos.
- Título, autor y etiquetas se recuperan del OPF y de metadata.db; el catálogo aporta nombres de autores y etiquetas aunque falte el OPF. Preserva las portadas integradas, no modifica originales. No importa cover.jpg independiente, series, comentarios, campos personalizados, DRM, posiciones ni anotaciones de Calibre. Los formatos incompatibles y errores se informan.
- Genera una nueva carpeta por ejecución con paquetes ZIP temporales (máx. 1 GiB/1000 originales por lote, 512 MiB por original, 16 MiB de manifiesto) e Informe.csv. BackupCodec v2 y layout books/UUID.bin, aceptados por CompleteBackupArchive.read. Verifica tamaño y hash durante escritura y reapertura; no sustituye un ZIP existente. Reintentar una migración no debe duplicar originales, pero revisar previamente las bajas históricas.
- No copiar originales sueltos directamente al árbol Drive: el catálogo compartido exige recibos de integridad y snapshots; la operación Android existente publica ambos. No generar archivos bia-state ni modificar _Sincronizacion desde Windows. Evitar otras escrituras durante una migración masiva hasta validar concurrencia real.

## Distribución y pruebas

GitHub Actions: Windows Calibre migrator. Genera BibliotecaAI-Migrador-Calibre.exe portable con checksum SHA-256 y ejecuta pruebas funcionales tanto en Python como dentro del EXE compilado. El artefacto de Actions caduca a los 30 días; el EXE no tiene firma Authenticode (SmartScreen puede advertir). No depende de una APK nueva. No confundir la preparación de paquetes con la importación real; pendiente comprobar el flujo con Windows físico, Drive y dos Android. No publicar una release Windows en el repositorio con el mismo target SHA que una APK mientras el publicador Android detecte cualquier release por SHA.
