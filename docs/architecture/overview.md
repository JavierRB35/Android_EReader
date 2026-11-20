# Arquitectura — visión general

## Estado actual (Incremento 0)

```text
Androdi_EReader/
├── settings.gradle / build.gradle / gradle.properties   (Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21)
├── gradle/wrapper + gradlew                             (distribución gradle-8.9-bin.zip)
├── build-on-phone.sh                                    (compilación AndroidIDE-Rv2)
├── app/                                                 (módulo único: pantalla base Biblioteca)
│   └── src/main  → com.javierrb35.bibliotecaia
├── docs/    (protocolo, requisitos, arquitectura, estado)
├── supabase/ (reservado, Incremento 5+)
└── AGENTS.md
```

## Arquitectura objetivo (Prompt Maestro §6)

```text
ANDROID: UI/navegación · Motor EPUB · Motor PDF · Importador MOBI · OCR
         Room/SQLite local · Motor de sincronización · Google Identity
         Autorización Drive · Cliente IA (abstracción de proveedores)
SUPABASE: Auth · PostgreSQL + RLS · metadatos y sincronización · índices
          Edge Functions → Gemini / Groq (claves nunca en cliente)
DRIVE (usuario): originales EPUB/MOBI/PDF y copias autorizadas
GITHUB: código · pruebas · documentación · releases
```

## Principios

Offline-first; persistencia local inmediata; sincronización eventual verificable; separación de datos por usuario desde el diseño; seguridad por defecto; contratos entre módulos; migraciones explícitas; operaciones recuperables; dependencias justificadas; bajo consumo energético.

## Persistencia (reparto)

- **Google Drive:** archivos originales de libros. Nunca como base de datos transaccional.
- **Supabase:** usuarios, catálogo, clasificaciones, posiciones, anotaciones, relaciones, historial IA, metadatos, información de sincronización.
- **Room/SQLite (local):** catálogo, posiciones, marcadores, anotaciones, preferencias, operaciones pendientes, índices locales.

## Roadmap de incrementos (detalle en Prompt Maestro §22)

0. Auditoría y base ← **actual**
1. Biblioteca local (Room, importación, catálogo)
2. Motor EPUB (Readium, verificar compatibilidad — ADR-005)
3. PDF y MOBI (prueba técnica temprana de MOBI)
4. Conocimiento (notas, relaciones, cuaderno global)
5. Identidad Google + Drive (Supabase Auth)
6. Sincronización + aislamiento multiusuario
7. IA textual (Gemini/Groq, RAG, referencias, anti-spoilers)
8. IA visual (PDF/imágenes/tablas)
9. Protección (copias, versiones, papelera, restauración verificada)
10. Actualizaciones y preparación final (firma, releases, migraciones)

Cada incremento debe dejar un recorrido funcional verificable y publicarse en el remoto con `docs/DEVELOPMENT_STATE.md` actualizado.
