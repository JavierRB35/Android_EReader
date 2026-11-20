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

## Arquitectura objetivo vigente (directriz 20-11-2026)

```text
ANDROID: Biblioteca local · EPUB/PDF/MOBI · OCR · Cuaderno · IA BYOK opcional
         Room/SQLite · Originales internos · SAF CreateDocument/OpenDocument
COPIA ZIP: biblioteca.json + libros físicos originales, hash SHA-256 verificable
DESTINO: ubicación elegida por el usuario (Drive/USB/almacenamiento local/otro proveedor)
GITHUB: código · pruebas · documentación · releases
```

No existen cuentas de usuario, registro con Gmail/Google, OAuth, Drive API ni sincronización
obligatoria de datos. **La app usa el selector de Android para el único documento autorizado
por el usuario**, no necesita iniciar sesión en Google. La sesión que una app externa
Drive pueda pedir no es una sesión de Biblioteca IA.

## Persistencia y seguridad

- **Room/SQLite**: única fuente de verdad de catálogo, localizadores, notas, versiones,
  marcadores, categorías, etiquetas y colecciones.
- **Almacenamiento privado de Android**: originales EPUB/PDF/MOBI disponibles offline.
- **ZIP transportable elegido mediante SAF**: originales + datos; exportación aborta si falta
  alguno; restauración comprueba hashes antes de actualizar la base.
- **IA BYOK**: credenciales cifradas localmente y excluidas del backup. El ZIP no está cifrado,
  por lo que debe guardarse en una ubicación privada.
- **Supabase/Google OAuth heredados**: fuera de alcance y desconectados de la navegación V1;
  no eliminarlos del servidor sin una nueva autorización explícita.

## Roadmap de incrementos (detalle en Prompt Maestro §22)

0. Auditoría y base ← **actual**
1. Biblioteca local (Room, importación, catálogo)
2. Motor EPUB (Readium, verificar compatibilidad — ADR-005)
3. PDF y MOBI (prueba técnica temprana de MOBI)
4. Conocimiento (notas, relaciones, cuaderno global)
5. Sin cuentas: selector SAF para guardar/leer la copia completa
6. Restauración verificable de binarios + Room con idempotencia
7. IA textual (Gemini/Groq, RAG, referencias, anti-spoilers)
8. IA visual (PDF/imágenes/tablas)
9. Protección (copias, versiones, papelera, restauración verificada)
10. Actualizaciones y preparación final (firma, releases, migraciones)

Cada incremento debe dejar un recorrido funcional verificable y publicarse en el remoto con `docs/DEVELOPMENT_STATE.md` actualizado.
