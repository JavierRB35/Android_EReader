# PROMPT MAESTRO DE DESARROLLO MULTIAGENTE — BIBLIOTECA IA PARA ANDROID

## DIRECTRIZ VINCULANTE POSTERIOR — SIN CUENTAS Y CON BACKUP COMPLETO (20-11-2026)

**Prevalece sobre TODAS las referencias contradictorias de este documento y sobre los incrementos antiguos.** Por decisión expresa del propietario:

1. **NO implementar ni utilizar OAuth, registro con Gmail, identificación Google, cuentas Supabase o autenticación de usuario en Biblioteca IA.** La PR #20 se canceló y cerró sin fusionar. Los módulos heredados de identidad/sincronización quedan fuera del flujo Android y no deben reactivarse sin nueva directriz.
2. **La base operativa es Room/SQLite local y los libros originales se conservan en el dispositivo para leer offline.** No se requiere cuenta ni conexión para abrir libros, gestionar subrayados/notas ni crear/restaurar copias.
3. **Backup íntegro obligatorio:** cada copia debe contener los originales físicos EPUB/PDF/MOBI de **todos los libros importados**, más catálogo, categorías, etiquetas, colecciones, relaciones, subrayados, anotaciones, versiones, marcadores y posiciones. La exportación falla si falta un original o su hash SHA-256 no coincide; no entregar una copia silenciosamente incompleta.
4. **El usuario selecciona el destino** mediante Storage Access Framework (`ACTION_CREATE_DOCUMENT` y `ACTION_OPEN_DOCUMENT`): Drive, dispositivo, USB u otro DocumentsProvider. La app NO accede con credenciales Google ni usa API Drive; Drive puede pedir sesión en su propia aplicación, ajena a Biblioteca IA.
5. **Restauración segura:** verificar todo el ZIP y hashes antes de modificar Room, reubicar originales offline en rutas privadas, conservar datos existentes y evitar duplicados por SHA-256. ZIP transportable con metadatos y binarios; no se incluyen claves IA, tokens o sesiones. Es un backup manual, NO sincronización entre dispositivos.
6. **Criterios de aceptación:** prueba con dispositivo Android, Drive como proveedor SAF cuando esté instalado, almacenamiento local alternativo, restauración en móvil limpio, integridad de los EPUB/PDF/MOBI y persistencia real de subrayados/notas después de reabrir. No declarar QA completada sin evidencia.

**Fuente única de la nueva arquitectura:** [ADR-008 revisada](../architecture/adr-008-saf-drive-byok-v1.md). Esta sección invalida expresamente los antiguos apartados de Google OAuth, registro, Supabase Sync y JSON sin binarios que sigan apareciendo en planes históricos de este documento.



## 0. MISIÓN PRINCIPAL

Actúa como **arquitecto principal de software, director técnico, ingeniero Android senior y coordinador de un equipo de agentes de inteligencia artificial**.

Tu misión es diseñar, implementar, integrar, probar, documentar y preparar para su distribución una aplicación Android denominada provisionalmente **Biblioteca IA**, tomando como referencia funcional la aplicación Scriptura para Android.

No debes limitarte a generar documentación, prototipos visuales, ejemplos o estructuras vacías. El resultado final debe ser una **aplicación funcional, coherente, segura, mantenible y preparada para utilizarse realmente en móviles y tablets Android**.

La aplicación combinará:

1. Lector de libros EPUB, MOBI y PDF.
2. Biblioteca personal organizada mediante categorías, etiquetas y colecciones.
3. Subrayados, anotaciones y marcadores.
4. Cuaderno global de conocimiento.
5. Consultas mediante inteligencia artificial sobre libros y anotaciones.
6. Análisis visual de imágenes, tablas, gráficos y páginas de PDF.
7. Gemini como proveedor principal y Groq como proveedor alternativo.
8. Originales de libros preservados localmente para lectura offline.
9. Copia completa transportable de originales y metadatos mediante el selector SAF en el destino escogido.
10. Funcionamiento íntegro sin registro, OAuth ni cuenta.
11. Restauración verificable en otro dispositivo, manteniendo libros y anotaciones.
12. Lectura de bajo consumo energético y modo OLED negro puro.
13. Copias de seguridad, versiones y recuperación de datos.
14. Actualizaciones detectadas desde la aplicación.
15. Arquitectura preparada para múltiples usuarios.
16. Compilación compatible con AndroidIDE-Rv2 directamente en un teléfono Android.

La aplicación debe desarrollarse mediante **incrementos completos y verificables**, integrados en una arquitectura coherente.

No consideres terminada la misión porque exista una estructura inicial, un APK que abre o varias pantallas simuladas.

El objetivo es cumplir realmente los requisitos funcionales.

---

# 1. INSTRUCCIONES OBLIGATORIAS Y JERARQUÍA

## 1.1. Protocolo Maestro de Desarrollo

Antes de modificar cualquier archivo debes localizar y leer COMPLETO el documento:

**PROTOCOLO DE DESARROLLO ASISTIDO POR INTELIGENCIA ARTIFICIAL**

Este documento ha sido facilitado por el propietario del proyecto y contiene el protocolo obligatorio de desarrollo asistido por IA.

No leas únicamente resúmenes.

No inventes su contenido.

Si no tienes acceso al documento, solicita su ubicación antes de comenzar la implementación.

Aplica especialmente:

- Autonomía responsable.
- Arquitectura proporcional.
- Análisis de impacto.
- Cambios incrementales.
- Reutilización.
- Verificaciones proporcionales al riesgo.
- Seguridad e integridad de datos.
- Gestión de Git.
- Documentación útil.
- Eficiencia de herramientas y tokens.
- Conservación del conocimiento.
- Recuperación ante errores.
- Criterios objetivos de finalización.

El protocolo tiene prioridad sobre las recomendaciones técnicas ordinarias de este prompt, respetando siempre las instrucciones superiores del entorno.

## 1.2. Autonomía

Tienes autorización para investigar, planificar, desarrollar, modificar archivos, crear pruebas y preparar entregas dentro del repositorio de trabajo.

No solicites confirmación para decisiones técnicas menores, convencionales y reversibles.

Solicita intervención humana únicamente cuando sea imprescindible por:

- Credenciales o permisos no disponibles.
- Decisiones funcionales importantes que no puedan inferirse.
- Operaciones destructivas.
- Costes externos significativos.
- Cambios de seguridad irreversibles.
- Publicaciones, despliegues o fusiones no autorizadas.
- Incompatibilidades técnicas que impidan satisfacer un requisito.
- Conflictos materiales entre requisitos.

No publiques secretos ni solicites al usuario que los pegue en conversaciones o archivos públicos.

**No hagas push, merge a main, publiques APK ni despliegues servicios externos sin autorización aplicable.**

## 1.3. Continuidad

Trabaja de forma autónoma hasta completar cada incremento autorizado.

Si una integración externa queda bloqueada por falta de credenciales, continúa con los componentes independientes, utilizando adaptadores verificables y pruebas con datos sintéticos.

Nunca declares funcional una integración real si solamente se ha probado con simulaciones.

---

# 2. REPOSITORIO OFICIAL Y FUENTE DE VERDAD

Repositorio canónico:

https://github.com/JavierRB35/Androdi_EReader

Nombre del repositorio:

`Androdi_EReader`

Rama estable:

`main`

Este repositorio público será la fuente de verdad de:

- Código fuente.
- Configuración Android y Gradle.
- Esquemas y migraciones de base de datos.
- Funciones de servidor.
- Documentación.
- Pruebas.
- Decisiones arquitectónicas.
- Historial de modificaciones.
- Configuración de distribución.
- Versiones publicadas, cuando se autorice.

## 2.1. Inicio obligatorio

Antes de empezar:

1. Comprueba acceso al repositorio.
2. Examina las ramas existentes.
3. Comprueba el estado real de Git.
4. Identifica archivos y cambios anteriores.
5. Localiza las instrucciones del proyecto.
6. Determina si existe código reutilizable.
7. No presupongas que el repositorio continúa vacío.
8. No sobrescribas trabajo existente.
9. Define el primer incremento verificable.

Si el repositorio está vacío, prepara una estructura mínima funcional conforme a las restricciones Android.

## 2.2. Estrategia de ramas

Utiliza ramas funcionales identificables.

Ejemplos:

- `develop/bootstrap`
- `feature/library`
- `feature/reader-epub`
- `feature/reader-pdf`
- `feature/import-mobi`
- `feature/google-auth`
- `feature/drive-sync`
- `feature/ai-rag`
- `feature/knowledge`
- `feature/backup`
- `feature/app-updates`

Adapta los nombres a las convenciones reales del repositorio.

Evita múltiples agentes modificando simultáneamente los mismos archivos.

Los cambios deben revisarse e integrarse de forma controlada.

---

# 3. ORGANIZACIÓN MULTIAGENTE

Debes crear o coordinar agentes especializados cuando el entorno lo permita.

La cantidad de agentes activos debe responder a las dependencias reales del trabajo, no a una cifra arbitraria.

Si el entorno no dispone de subagentes reales, ejecuta las mismas responsabilidades de forma secuencial, conservando los contratos y puntos de control.

## AGENTE 0 — COORDINADOR TÉCNICO Y ARQUITECTO PRINCIPAL

Responsabilidades:

- Aplicar el protocolo completo.
- Establecer arquitectura.
- Mantener el alcance funcional.
- Dividir tareas en incrementos.
- Coordinar dependencias.
- Definir contratos entre componentes.
- Controlar la integración.
- Revisar seguridad.
- Resolver decisiones arquitectónicas.
- Evitar duplicaciones.
- Mantener la continuidad.
- Validar los criterios de aceptación.

Este agente es el responsable final de la coherencia del producto.

No debe delegar la responsabilidad de integración.

## AGENTE 1 — ANDROID CORE Y EXPERIENCIA DE USUARIO

Especialidad: Kotlin/Java, Android SDK, interfaces nativas y arquitectura móvil.

Responsabilidades:

- Estructura de aplicación.
- Navegación.
- Pantallas principales.
- Estados de interfaz.
- Adaptación a móvil y tablet.
- Preferencias.
- Ciclo de vida.
- Accesibilidad.
- Gestión eficiente de memoria.
- Compatibilidad con AndroidIDE.
- Interacciones y errores recuperables.

Debe priorizar componentes nativos y dependencias estables.

## AGENTE 2 — MOTOR DE LECTURA Y FORMATOS

Responsabilidades:

- Lectura EPUB.
- Lectura PDF.
- Importación MOBI.
- Conversión de formatos.
- Extracción de texto.
- Interpretación de índices.
- Localización estable de fragmentos.
- Selección de texto.
- Subrayados.
- Navegación y búsqueda.
- OCR.
- Compatibilidad entre ediciones.

Debe investigar motores existentes antes de implementar uno nuevo.

## AGENTE 3 — BIBLIOTECA Y GESTIÓN DEL CONOCIMIENTO

Responsabilidades:

- Catálogo de libros.
- Categorías.
- Subcategorías.
- Etiquetas.
- Colecciones.
- Fichas de libros.
- Estados de lectura.
- Anotaciones.
- Marcadores.
- Relaciones entre ideas y libros.
- Cuaderno global de conocimiento.
- Búsquedas y filtros.

Debe garantizar que las anotaciones mantengan su relación con el documento de origen.

## AGENTE 4 — IDENTIDAD GOOGLE Y GOOGLE DRIVE

Responsabilidades:

- Inicio de sesión con Google.
- Integración con Supabase Auth.
- Autorización OAuth para Drive.
- Gestión de permisos.
- Creación de carpetas.
- Subida y descarga.
- Reanudación de transferencias.
- Identificación de duplicados.
- Caducidad y renovación de autorizaciones.
- Revocación de permisos.
- Integridad de archivos.

Debe mantener separadas la autenticación de identidad y la autorización de acceso a Drive.

## AGENTE 5 — SUPABASE Y SINCRONIZACIÓN

Responsabilidades:

- Diseño PostgreSQL.
- Esquemas y migraciones.
- Row Level Security.
- Persistencia por usuario.
- Base local Room/SQLite.
- Sincronización incremental.
- Resolución de conflictos.
- Funcionamiento offline.
- Asociación de datos locales con cuentas.
- Gestión de borrados y versiones.
- Recuperación tras interrupciones.

Debe diseñar la base de datos para múltiples usuarios desde el principio.

## AGENTE 6 — INTELIGENCIA ARTIFICIAL Y RAG

Responsabilidades:

- Integración Gemini.
- Integración Groq.
- Gestión segura de credenciales.
- Consultas sobre libros.
- Recuperación contextual.
- Embeddings y búsqueda semántica.
- Respuestas con referencias.
- Control de spoilers.
- Análisis visual.
- Historial de conversaciones.
- Control de consumo.
- Cambio automático de proveedor.
- Evaluación de calidad y alucinaciones.

Debe impedir que el contenido de los libros o PDF se convierta en instrucciones privilegiadas para el sistema.

## AGENTE 7 — SEGURIDAD, BACKUP Y RECUPERACIÓN

Responsabilidades:

- Análisis de amenazas.
- Privacidad.
- Gestión de secretos.
- Validación de acceso por usuario.
- Protección de credenciales.
- Copias de seguridad.
- Historial de versiones.
- Papelera.
- Restauración.
- Comprobaciones de integridad.
- Migraciones seguras.
- Recuperación ante fallos.

Debe verificar la restauración real, no limitarse a comprobar que se genera un archivo.

## AGENTE 8 — DISTRIBUCIÓN Y ACTUALIZACIONES

Responsabilidades:

- Control de versiones.
- Firma de APK.
- Conservación del applicationId.
- Preparación de GitHub Releases.
- Descarga verificada de actualizaciones.
- Instalación mediante mecanismos autorizados de Android.
- Compatibilidad entre versiones.
- Migraciones al actualizar.
- Preparación del ZIP instalable y compilable desde AndroidIDE.

## AGENTE 9 — QA, PRUEBAS Y RENDIMIENTO

Responsabilidades:

- Criterios de aceptación.
- Pruebas unitarias.
- Pruebas de integración.
- Pruebas de dispositivos.
- Seguridad.
- Pruebas de sincronización.
- Pruebas de restauración.
- Validación de EPUB, PDF y MOBI.
- Rendimiento y memoria.
- Consumo energético.
- Regresión.
- Comprobación de entregas.

Aplicará rigurosamente los niveles de prueba definidos por el Protocolo Maestro.

## AGENTE 10 — DOCUMENTACIÓN Y CONFIGURACIÓN GUIADA

Responsabilidades:

- Documentación de instalación.
- Manual de uso.
- Tutorial de Google Drive.
- Tutorial de Gemini.
- Tutorial de Groq.
- Configuración de Supabase.
- Guías para AndroidIDE.
- Solución de errores frecuentes.
- Instrucciones dentro de la propia aplicación.
- Ayuda contextual.
- Información de privacidad.
- Documentación de recuperación y actualizaciones.

Debe producir instrucciones utilizables por personas sin conocimientos técnicos.

## 3.1. Coordinación obligatoria

Cada agente recibirá:

1. Objetivo.
2. Componentes bajo su responsabilidad.
3. Interfaces y contratos.
4. Dependencias.
5. Criterios de aceptación.
6. Pruebas requeridas.
7. Restricciones técnicas.
8. Archivos que puede modificar.

Al completar un incremento, entregará:

- Funcionalidad implementada.
- Archivos afectados.
- Contratos creados o modificados.
- Pruebas realizadas.
- Resultados obtenidos.
- Riesgos pendientes.

El coordinador revisará la integración antes de aceptar el incremento.

No deben existir trabajos paralelos conflictivos.

---

# 4. ENTORNO ANDROID OBLIGATORIO

La aplicación debe poder compilarse en el siguiente entorno validado:

| Componente | Versión |
|---|---|
| IDE | AndroidIDE-Rv2 |
| Arquitectura | ARM64 / aarch64 |
| Java | OpenJDK 17 |
| Android SDK | 34 |
| Build Tools | 34.0.4 |
| Gradle | 8.9 |
| Android Gradle Plugin | 8.7.3 |
| SDK | `$HOME/android-sdk` |
| AAPT2 | `$HOME/.androidide/aapt2` |

Los proyectos estarán preparados para ubicarse en:

`/storage/emulated/0/Download/`

No actualices SDK, JDK, Gradle ni AGP por simple novedad.

Cualquier dependencia añadida deberá ser compatible con este entorno o requerirá una decisión explícita fundamentada en pruebas.

## 4.1. Estructura mínima

El proyecto deberá incluir:

```text
Androdi_EReader/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── gradlew
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── build-on-phone.sh
├── app/
│   ├── build.gradle
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/
│       │   └── res/
│       ├── test/
│       └── androidTest/
├── docs/
├── supabase/
└── AGENTS.md
```

La estructura puede ampliarse con módulos adicionales cuando exista una razón concreta.

No introduzcas módulos artificiales o abstracciones innecesarias.

## 4.2. Configuración Gradle

En `settings.gradle` incluye los repositorios necesarios:

- `google()`
- `mavenCentral()`
- `gradlePluginPortal()`

Establece el nombre del proyecto y `include(":app")`.

En el Gradle raíz utiliza:

`com.android.application` versión `8.7.3` con `apply false`.

El wrapper deberá usar:

`https://services.gradle.org/distributions/gradle-8.9-bin.zip`

En `app/build.gradle`:

- `compileSdk 34`
- `targetSdk 34`
- `minSdk 26` como referencia.
- `namespace` único y estable.
- `applicationId` único y estable.
- `versionCode` creciente.
- `versionName` coherente.

Selecciona Kotlin o Java según compatibilidad demostrable con AndroidIDE-Rv2 y las dependencias necesarias. Kotlin es preferente cuando no comprometa dicha compatibilidad.

En `gradle.properties`:

```properties
org.gradle.daemon=false
org.gradle.workers.max=1
android.useAndroidX=true
```

No añadas `org.gradle.jvmargs=-Xmx2048m` por defecto, porque ese ajuste ha provocado bloqueos en el dispositivo de referencia.

## 4.3. Compilación

Comando obligatorio en el teléfono:

```sh
JAVA_OPTS="-Xmx768m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8" sh ./gradlew assembleDebug --no-daemon --max-workers=1 -Pandroid.aapt2FromMavenOverride=$HOME/.androidide/aapt2
```

Desde Descargas utiliza siempre:

`sh ./gradlew`

No presupongas permisos de ejecución sobre `gradlew`.

## 4.4. Script de compilación

Implementa `build-on-phone.sh` con:

- `#!/bin/sh`
- `set -e`
- Cambio a la raíz del proyecto.
- Variable `APP_NAME`.
- Exportación del `JAVA_OPTS` validado.
- Compilación con Gradle sin daemon.
- Comprobación real de existencia del APK.
- Copia del APK a Descargas.
- Mensaje `BUILD OK` únicamente tras éxito.

Nombre provisional de salida:

`/storage/emulated/0/Download/BibliotecaIA.apk`

El script debe detenerse si falla cualquier operación esencial.

No afirmes que se ha compilado correctamente en AndroidIDE cuando solo se haya compilado en un ordenador u otro entorno.

---

# 5. OBJETIVOS DE PRODUCTO Y MODELO DE USUARIO

## 5.1. Enfoque

El producto se utilizará inicialmente de forma personal.

Su arquitectura debe estar preparada para evolucionar hacia una aplicación multiusuario.

No implementar todavía:

- Pagos.
- Suscripciones.
- Tienda de libros.
- Redes sociales.
- Recomendaciones comerciales.
- Paneles complejos de administración.
- Funciones de colaboración no solicitadas.

## 5.2. Modo local

Sin iniciar sesión debe ser posible:

- Importar libros.
- Leerlos.
- Consultar índices.
- Buscar texto.
- Crear subrayados.
- Crear anotaciones.
- Crear marcadores.
- Gestionar categorías y etiquetas.
- Utilizar el cuaderno de conocimiento.
- Exportar copias.
- Restaurar copias locales.
- Leer sin conexión.

Los datos permanecerán en el dispositivo.

## 5.3. Modo único: biblioteca local sin cuentas

Toda función base, incluido backup íntegro y restauración, funciona sin iniciar sesión.
La aplicación no presenta pantalla de registro, Google OAuth, Gmail ni conexión obligatoria con Supabase.

## 5.4. Transporte voluntario de datos

En Ajustes, el usuario solicita una copia ZIP completa (catálogo y originales) y elige
documento de destino con SAF. En otro dispositivo selecciona ese ZIP y verifica
su restauración aditiva; no existe asociación automática a una identidad remota.


---

# 6. ARQUITECTURA GENERAL

La arquitectura funcional será:

```text
ANDROID
  |
  +-- Interfaz y navegación
  |
  +-- Motor EPUB
  |
  +-- Motor PDF
  |
  +-- Importador MOBI
  |
  +-- OCR
  |
  +-- Base local Room/SQLite
  |
  +-- Motor de sincronización
  |
  +-- Google Identity
  |
  +-- Autorización Drive
  |
  +-- Cliente de servicios IA
          |
          v
SUPABASE
  |
  +-- Auth
  |
  +-- PostgreSQL
  |
  +-- Políticas RLS
  |
  +-- Sincronización y metadatos
  |
  +-- Índices y fragmentos de búsqueda
  |
  +-- Edge Functions / Backend seguro
          |
          +-- Gemini
          |
          +-- Groq

GOOGLE DRIVE DEL USUARIO
  |
  +-- EPUB originales
  |
  +-- MOBI originales
  |
  +-- PDF originales
  |
  +-- Copias/exportaciones autorizadas

GITHUB
  |
  +-- Código
  |
  +-- Pruebas
  |
  +-- Documentación
  |
  +-- Releases
```

## 6.1. Principios

- Offline-first.
- Persistencia local inmediata.
- Sincronización eventual verificable.
- Separación de datos de usuario.
- Seguridad por defecto.
- Componentes reutilizables.
- Bajo consumo energético.
- Dependencias justificadas.
- Contratos entre módulos.
- Migraciones explícitas.
- Operaciones recuperables.

## 6.2. Persistencia

Google Drive almacenará los archivos originales.

Supabase almacenará:

- Usuarios.
- Catálogo.
- Clasificaciones.
- Posiciones.
- Subrayados.
- Anotaciones.
- Relaciones.
- Historial de IA.
- Metadatos de archivos.
- Referencias documentales.
- Información necesaria para sincronización.
- Índices privados de consulta cuando estén autorizados.

Room/SQLite almacenará la información local y los cambios pendientes.

No utilizar Drive como base de datos transaccional.

No utilizar GitHub para almacenar datos personales.

---

# 7. MÓDULO DE BIBLIOTECA

## 7.1. Biblioteca principal

Crear una pantalla con:

- Continuar leyendo.
- Lecturas recientes.
- Todos los libros.
- Favoritos.
- Pendientes.
- En lectura.
- Terminados.
- Abandonados.
- Colecciones.
- Categorías.
- Búsqueda.
- Importación de libros.

Permitir vista de cuadrícula y lista.

Optimizar la presentación para móvil y tablet.

## 7.2. Metadatos

Cada libro tendrá, cuando sea posible:

- Identificador interno.
- Identificador de propietario.
- Título.
- Autor o autores.
- ISBN, si existe.
- Editorial.
- Fecha de publicación.
- Idioma.
- Sinopsis.
- Portada.
- Formato.
- Hash de contenido.
- Edición.
- Fecha de importación.
- Fecha de última lectura.
- Categorías.
- Etiquetas.
- Colecciones.
- Estado.
- Progreso.
- Ubicación local.
- Referencia de Drive.
- Estado de sincronización.

Los datos deben poder editarse manualmente.

## 7.3. Categorías

Crear categorías iniciales:

- Novela.
- Aventura.
- Ciencia ficción.
- Fantasía.
- Historia.
- Filosofía.
- Ciencia.
- Tecnología.
- Inteligencia artificial.
- Economía.
- Finanzas.
- Empresa.
- Psicología.
- Biografías.
- Formación.
- Referencia.
- Otros.

Permitir:

- Crear categorías.
- Crear subcategorías.
- Renombrarlas.
- Reordenarlas.
- Asignar varias a un libro.
- Filtrar.
- Eliminar categorías sin eliminar los libros.

## 7.4. Etiquetas y colecciones

Diferenciar:

**Categoría:** naturaleza del libro.

**Etiqueta:** concepto o tema transversal.

**Colección:** agrupación personal.

Permitir múltiples relaciones.

No duplicar libros al incorporarlos a diferentes colecciones.

## 7.5. Búsqueda

Implementar búsqueda por:

- Título.
- Autor.
- Categoría.
- Etiqueta.
- Colección.
- Texto disponible e indexado.
- Notas.
- Subrayados.

En los resultados internos del libro, ofrecer navegación directa al fragmento.

---

# 8. IMPORTACIÓN Y PROCESAMIENTO DE LIBROS

## 8.1. Formatos obligatorios

La V1 debe admitir:

- EPUB.
- MOBI sin DRM compatible.
- PDF con texto.
- PDF escaneado mediante OCR.

No utilizar implementaciones simuladas.

## 8.2. EPUB

Investiga Readium Kotlin Toolkit como candidato preferente.

Antes de incorporarlo:

1. Verifica versiones compatibles.
2. Comprueba el SDK mínimo.
3. Comprueba Kotlin/Gradle/AGP.
4. Evalúa tamaño.
5. Comprueba búsqueda.
6. Comprueba selección y localizadores.
7. Comprueba subrayados y navegación.
8. Verifica una compilación mínima en el entorno objetivo.

Funcionalidades:

- Abrir libros.
- Navegar capítulos.
- Buscar.
- Pasar páginas.
- Desplazamiento vertical.
- Ajustar tipografía.
- Guardar posiciones.
- Seleccionar texto.
- Crear anotaciones.
- Ir a una cita.
- Leer offline.

## 8.3. MOBI

Investiga una solución compatible con Android ARM64.

Se requiere:

- Importación del archivo original.
- Detección de DRM.
- Conversión o interpretación real del contenido compatible.
- Extracción de índice.
- Extracción de metadatos.
- Conversión segura cuando sea necesaria.
- Conservación del archivo original.
- Lectura y anotaciones sobre la versión utilizable.

No supongas que Calibre de escritorio puede ejecutarse directamente dentro de Android.

Realiza una prueba técnica temprana de compatibilidad MOBI.

Si no existe una solución local razonable, evalúa una conversión segura en servidor únicamente con consentimiento explícito y análisis de privacidad y costes.

No anuncies soporte MOBI completo si solo existe un botón de importación o si la conversión no funciona.

Los archivos con DRM no se desbloquearán ni se eludirán sus protecciones.

## 8.4. PDF

El soporte PDF debe incluir:

- Visualización.
- Zoom.
- Paginación.
- Desplazamiento.
- Ajuste de anchura.
- Recorte visual de márgenes.
- Navegación por páginas.
- Búsqueda textual.
- Selección de texto.
- Subrayados.
- Notas.
- Referencias a página.
- Consultas de IA.
- Consultas visuales.

Verifica expresamente qué funcionalidades ofrece el motor PDF seleccionado.

No presupongas que el soporte PDF de Readium proporciona todas las anotaciones requeridas.

Si es necesario, integra un motor PDF adecuado y crea una capa propia de selección y anotación.

## 8.5. OCR

Para PDF escaneados:

- Detectar ausencia de capa textual.
- Ofrecer reconocimiento OCR.
- Procesar progresivamente.
- Permitir cancelar.
- Guardar resultados.
- Registrar idioma y fiabilidad cuando sea posible.
- Conservar coordenadas.
- Relacionar texto reconocido con páginas.
- Permitir navegación desde resultados.
- Evitar reprocesamientos innecesarios.

Investigar ML Kit u otra solución compatible.

No realizar OCR masivo en segundo plano sin necesidad.

## 8.6. Integridad

Cada importación debe:

1. Validar formato.
2. Validar tamaño y estructura.
3. Calcular hash.
4. Detectar duplicados.
5. Extraer metadatos.
6. Registrar estado del procesamiento.
7. Gestionar errores.
8. Preservar el original.
9. Permitir reintento.
10. Evitar registros incompletos visibles como libros válidos.

Conservar distintas ediciones cuando corresponda.

---

# 9. EXPERIENCIA DE LECTURA

## 9.1. Principio de diseño

La lectura debe ser cómoda, minimalista y libre de distracciones.

La interfaz avanzada aparecerá solamente cuando el usuario la necesite.

La aplicación tendrá tres espacios principales:

- Biblioteca.
- Leer.
- Conocimiento.

## 9.2. Herramientas del lector

Incluir:

- Índice.
- Búsqueda.
- Marcadores.
- Subrayados.
- Notas.
- Selección.
- Preguntar a IA.
- Compartir o exportar fragmentos cuando proceda.
- Configuración de lectura.
- Navegación por posiciones.
- Historial de ubicaciones consultadas.

## 9.3. Presentación

Permitir configurar:

- Tamaño de letra.
- Familia tipográfica.
- Interlineado.
- Márgenes.
- Anchura del texto.
- Alineación.
- Paginación.
- Desplazamiento.
- Orientación.
- Color de fondo.
- Color de texto.

Crear ajustes específicos para móvil y tablet.

## 9.4. Vista tablet

Cuando el tamaño de pantalla lo permita:

- Lector principal.
- Panel lateral opcional de IA.
- Panel lateral de anotaciones.
- Navegación de capítulos.
- Posibilidad de ocultar paneles.

En móvil, presentar paneles superpuestos o pantallas adaptadas sin perder la ubicación del libro.

## 9.5. Lectura en voz alta

Incorporar TTS del sistema Android cuando sea compatible con el contenido.

Permitir:

- Reproducir.
- Pausar.
- Reanudar.
- Ajustar velocidad.
- Continuar desde el fragmento correspondiente.

No depender innecesariamente de una API de IA de pago para esta función.

---

# 10. MODO OLED Y AHORRO DE ENERGÍA

Este apartado es obligatorio y prioritario.

La experiencia de referencia es el modo OLED de Scriptura para Android.

No asumir detalles internos no verificados de Scriptura.

## 10.1. Temas de lectura

Implementar:

1. OLED negro puro.
2. Oscuro suave.
3. Sepia.
4. Claro.

## 10.2. OLED

Configuración base:

- Fondo `#000000`.
- Texto cálido de contraste cómodo.
- Sin blanco puro como configuración inicial.
- Controles oscuros.
- Mínimas superficies iluminadas.
- Ausencia de animaciones superfluas.
- Subrayados legibles.
- Barras del sistema adaptadas.

Permitir modificar brillo y contraste dentro de las capacidades autorizadas de Android.

El usuario podrá guardar sus preferencias.

## 10.3. Ergonomía

Evitar fatiga visual mediante:

- Tipografía adecuada.
- Espaciado configurable.
- Contraste ajustable.
- Márgenes cómodos.
- Ausencia de parpadeos artificiales.
- Control de brillo.
- Temas alternativos.
- Compatibilidad con ajustes de accesibilidad.

No afirmar que un tema elimina por completo el cansancio ocular.

En PDF evitar inversiones de color que destruyan el significado de gráficos o imágenes.

## 10.4. Eficiencia

Durante la lectura:

- No mantener conexiones permanentes innecesarias.
- No ejecutar consultas a IA automáticamente.
- No sincronizar por cada movimiento de pantalla.
- Evitar escrituras redundantes.
- Agrupar actualizaciones de progreso.
- No mantener la CPU activa innecesariamente.
- Respetar las políticas de ahorro de Android.
- Evitar descargas no solicitadas.
- Aprovechar cachés locales.
- No forzar la pantalla encendida por defecto.

Probar consumo energético y rendimiento cuando existan dispositivos reales disponibles.

No prometer porcentajes de ahorro sin mediciones.

---

# 11. ANOTACIONES AVANZADAS

## 11.1. Subrayados

Permitir:

- Seleccionar fragmento.
- Aplicar color.
- Cambiar color.
- Añadir nota.
- Añadir etiquetas.
- Eliminar.
- Recuperar.
- Buscar.
- Navegar hasta el origen.

## 11.2. Localizadores estables

No guardar únicamente número de página para EPUB.

Utilizar localizadores adecuados al formato.

Guardar contexto textual y datos auxiliares que faciliten reconstruir la selección.

Para PDF guardar:

- Identificador de libro y edición.
- Página.
- Coordenadas normalizadas.
- Texto asociado.
- Contexto.
- Referencia al contenido original.

Las anotaciones deben conservar su ubicación al modificar tipografía o dispositivo.

No garantizar correspondencia automática exacta entre ediciones diferentes.

## 11.3. Notas

Permitir:

- Notas vinculadas a fragmentos.
- Notas vinculadas a capítulos.
- Notas generales sobre libros.
- Notas independientes.
- Etiquetas.
- Favoritos.
- Fechas de creación y modificación.
- Historial de versiones.

## 11.4. Relaciones

Crear relaciones entre:

- Dos anotaciones.
- Una nota y un libro.
- Dos conceptos.
- Fragmentos de diferentes libros.
- Respuestas de IA.
- Elementos del cuaderno global.

Las relaciones pueden tener un tipo y una descripción.

No generar relaciones automáticas irreversibles.

## 11.5. Cuaderno global

Debe reunir:

- Subrayados.
- Reflexiones.
- Notas.
- Respuestas de IA guardadas.
- Relaciones.
- Etiquetas.
- Favoritos.

Incluir búsqueda, filtros y exportación.

Diferenciar claramente:

- Texto original del libro.
- Texto escrito por el usuario.
- Texto generado por IA.

---

# 12. INTELIGENCIA ARTIFICIAL

## 12.1. Proveedores

Proveedor principal:

**Google Gemini API**

Proveedor secundario:

**GroqCloud API**

Importante: Groq no debe confundirse con Grok de xAI.

La integración debe realizarse mediante una abstracción estable de proveedores.

No fijar identificadores de modelos obsoletos sin consultar las APIs oficiales vigentes.

La selección tendrá en cuenta:

- Disponibilidad.
- Capacidad textual.
- Capacidad visual.
- Límites de contexto.
- Coste.
- Latencia.
- Compatibilidad.
- Calidad.

## 12.2. Modos de respuesta

### Modo A — Solo libro

La IA debe responder utilizando exclusivamente el contenido recuperado del libro o libros seleccionados.

Debe:

- Recuperar fragmentos.
- Identificar fuentes.
- Responder con referencias.
- Señalar insuficiencia de información.
- Evitar inventar citas.
- Mantener el alcance elegido.

Si no existen evidencias suficientes, indicarlo.

Implementar validación de referencias para evitar citas inexistentes.

### Modo B — Libro + conocimiento externo

La IA podrá complementar el contenido del libro.

Debe separar:

**Contenido documentado en el libro.**

**Explicación o conocimiento externo.**

Si utiliza fuentes externas realmente consultadas, incluir las referencias disponibles.

No presentar el conocimiento general del modelo como una fuente externa verificada.

## 12.3. Alcances

Permitir consultar:

- Fragmento seleccionado.
- Página.
- Capítulo.
- Libro completo.
- Varios libros.
- Subrayados.
- Anotaciones.
- Cuaderno global.

## 12.4. Sistema RAG

Diseñar un pipeline:

1. Importar documento.
2. Extraer texto.
3. Normalizar.
4. Segmentar con contexto.
5. Preservar localizadores.
6. Generar índices.
7. Generar embeddings cuando proceda.
8. Registrar versión del procesamiento.
9. Recuperar fragmentos relevantes.
10. Construir contexto acotado.
11. Consultar proveedor.
12. Validar referencias.
13. Guardar respuesta y evidencia.

No enviar libros completos a los modelos cuando pueda resolverse la consulta mediante recuperación selectiva.

Evitar reprocesamientos si el contenido y el índice siguen siendo válidos.

Mantener aislados los índices de cada usuario.

## 12.5. Referencias navegables

Las respuestas sobre libros incluirán, cuando sea posible:

- Título del libro.
- Capítulo.
- Página o localizador.
- Fragmento utilizado.
- Acción para abrir el origen.

La referencia debe apuntar a una ubicación real.

No inventar números de página.

## 12.6. Sin spoilers

En libros narrativos:

- Consultar solo hasta la ubicación de lectura actual cuando el modo esté activado.
- No utilizar fragmentos posteriores.
- Advertir si la pregunta requiere contenido no leído.
- Permitir desactivar expresamente el límite.

El filtro debe aplicarse antes de recuperar y enviar contexto a la IA.

## 12.7. Consultas visuales

Incluir análisis visual bajo demanda de:

- Páginas de PDF.
- Imágenes.
- Tablas.
- Gráficos.
- Esquemas.
- Fórmulas.
- Zonas seleccionadas.

Flujo:

1. Seleccionar elemento o región.
2. Obtener recorte.
3. Conservar página y coordenadas.
4. Obtener texto cercano cuando proceda.
5. Informar del procesamiento externo.
6. Enviar la consulta al modelo visual.
7. Recibir respuesta.
8. Guardar historial.
9. Vincularlo al origen.
10. Permitir incorporarlo al cuaderno.

Evitar analizar automáticamente todas las imágenes.

## 12.8. Cambio a Groq

Crear un mecanismo de respaldo.

Ante fallos recuperables de Gemini:

- Identificar el error.
- Determinar si es adecuado reintentar.
- Evitar duplicar operaciones.
- Comprobar disponibilidad de Groq.
- Comprobar capacidad requerida.
- Realizar un único cambio controlado.
- Informar al usuario cuando corresponda.

No cambiar automáticamente por errores de autorización, claves inválidas o facturación que requieran intervención humana sin explicar el problema.

Para consultas visuales comprobar que el modelo alternativo admite visión.

## 12.9. Historial

Guardar:

- Pregunta.
- Respuesta.
- Fecha.
- Usuario.
- Libro o libros.
- Fragmentos relacionados.
- Modo de respuesta.
- Proveedor.
- Modelo.
- Referencias.
- Uso de tokens cuando se disponga.
- Coste estimado cuando sea calculable.
- Elementos visuales asociados.
- Estado de sincronización.

Permitir continuar conversaciones anteriores.

---

# 13. CONFIGURACIÓN GUIADA DE GEMINI Y GROQ

La aplicación debe incluir un **Asistente de Configuración de Inteligencia Artificial**.

No limitarse a una pantalla que solicite una clave.

El asistente debe explicar cómo obtenerla, para qué se utiliza, cómo se protege y cómo comprobar su funcionamiento.

## 13.1. Pantalla principal

Crear:

`Ajustes > Inteligencia artificial`

Mostrar:

- Proveedor principal.
- Estado de Gemini.
- Estado de Groq.
- Modelo seleccionado.
- Estado de conexión.
- Prueba de funcionamiento.
- Preferencias de respaldo.
- Información de consumo.
- Ayuda para crear claves.
- Opción de sustituir o revocar credenciales.

## 13.2. Gemini

Incluir instrucciones guiadas:

1. Abrir Google AI Studio.
2. Iniciar sesión con una cuenta Google.
3. Acceder a la gestión de claves API.
4. Crear o seleccionar un proyecto.
5. Generar una clave habilitada para la Gemini API.
6. Comprobar los requisitos de facturación y cuotas.
7. Introducir la clave mediante un formulario seguro.
8. Registrar la clave mediante el mecanismo seguro elegido.
9. Realizar prueba de conexión.
10. Mostrar resultado.
11. Permitir cambiar proveedor o modelo.

Enlace de referencia:

https://aistudio.google.com/

Documentación:

https://ai.google.dev/gemini-api/docs

## 13.3. Groq

Incluir instrucciones guiadas:

1. Abrir GroqCloud Console.
2. Crear cuenta o iniciar sesión.
3. Acceder a API Keys.
4. Generar una clave.
5. Revisar modelos y límites disponibles.
6. Introducirla mediante formulario seguro.
7. Registrar credencial protegida.
8. Realizar prueba.
9. Comprobar capacidades.
10. Configurar el respaldo.

Enlace:

https://console.groq.com/keys

Documentación:

https://console.groq.com/docs

## 13.4. Seguridad de credenciales

La cuenta de Google no sustituye a las claves API de Gemini o Groq.

Implementar un modelo seguro de credenciales aportadas por el usuario, preparado para múltiples usuarios.

Recomendación arquitectónica:

- Autenticar la aplicación.
- Enviar la credencial mediante TLS al servicio seguro de configuración.
- Guardarla cifrada en un almacén del servidor con gestión adecuada de claves.
- Restringir operaciones por usuario.
- Ejecutar consultas desde funciones de servidor.
- Evitar devolver claves completas al cliente.
- Permitir sustitución y revocación.
- No incluir claves en APK, repositorio, backups, logs ni telemetría.

El cifrado de credenciales debe usar mecanismos criptográficos consolidados, no implementaciones caseras.

Si se plantea guardar credenciales exclusivamente en Android Keystore, documentar sus limitaciones y el impacto sobre la sincronización multidispositivo.

No introducir directamente claves de prueba en código fuente.

## 13.5. Diagnóstico

La interfaz debe distinguir:

- Clave inválida.
- Permiso insuficiente.
- Cuota agotada.
- Facturación no disponible.
- Modelo inexistente.
- Error temporal del proveedor.
- Problema de conexión.
- Tiempo de espera agotado.
- Capacidad visual no disponible.
- Configuración incompleta.

Los mensajes deben ofrecer instrucciones de resolución.

---

# 14. COPIA ÍNTEGRA MEDIANTE STORAGE ACCESS FRAMEWORK

## 14.1. Sin identidad obligatoria

Prohibido solicitar OAuth, crear cuenta Gmail o utilizar Google/Supabase Auth.
La navegación de Ajustes ofrece copia/restauración y elección de destino, no registro.

## 14.2. Destinos elegidos por el usuario

Usar SAF de Android para seleccionar documento ZIP en Drive u otro proveedor instalado;
no usar API Drive ni scopes; no pedir permiso de acceso global a carpetas.
El proveedor externo puede requerir su propia sesión independientemente de la app.

## 14.3. Contenido e integridad

El ZIP siempre incluye originales EPUB/PDF/MOBI, metadatos, notas, subrayados,
marcadores, localizadores, versiones y relaciones. Verificar tamaño/hash SHA-256
y rechazar ausencias, alteraciones, rutas no declaradas y compresión maliciosa.
No copiar claves IA, tokens ni credenciales.

## 14.4. Restauración

Materializar originales en almacenamiento privado y actualizar Room en una transacción;
preservar ejemplares ya existentes, reintentos y posiciones más recientes.
No prometer sincronización automática. Registrar evidencia en Android real.

---

# 15. SUPABASE Y MODELO DE DATOS

## 15.1. Backend

Supabase proporcionará:

- Auth.
- PostgreSQL.
- Row Level Security.
- Funciones de servidor.
- Índices de búsqueda y vectores, cuando proceda.
- Sincronización de metadatos.

Los archivos principales de libros permanecerán en Google Drive.

## 15.2. Modelo lógico mínimo

Diseñar entidades equivalentes a:

```text
users / profiles
books
book_files
book_editions
authors
book_authors
categories
book_categories
tags
book_tags
collections
collection_books
reading_progress
reading_positions
bookmarks
highlights
notes
note_versions
knowledge_links
knowledge_items
ai_conversations
ai_messages
ai_references
ai_visual_regions
ai_usage
document_processing_jobs
document_chunks
document_embeddings
sync_operations
sync_conflicts
deleted_items
backup_snapshots
app_settings
```

Estos nombres son orientativos.

Normaliza donde sea necesario y evita duplicaciones innecesarias.

Todas las entidades privadas deben tener propietario o una relación de autorización inequívoca.

## 15.3. Multiusuario

Diseñar desde el inicio para múltiples usuarios.

No utilizar un único usuario codificado.

No compartir datos accidentalmente.

Aplicar RLS para:

- Lectura.
- Creación.
- Actualización.
- Eliminación.
- Relaciones entre entidades.

Verificar que un usuario no puede acceder a registros o referencias pertenecientes a otro.

No confiar únicamente en filtros de interfaz.

## 15.4. Base local

Usar Room/SQLite para:

- Catálogo.
- Posiciones.
- Marcadores.
- Anotaciones.
- Preferencias.
- Operaciones pendientes.
- Índices locales.
- Información de sincronización.

No guardar claves privadas sin protección.

## 15.5. Sincronización

Implementar:

- Identificadores estables.
- Registro de cambios.
- Estados pendientes.
- Confirmación remota.
- Reintentos.
- Idempotencia.
- Detección de conflictos.
- Control de versiones.
- Borrado lógico cuando corresponda.
- Recuperación de errores.

No utilizar sin más una política universal de "última escritura gana" para datos importantes.

Especialmente, no perder anotaciones concurrentes por sobrescritura silenciosa.

## 15.6. Posición de lectura

Permitir continuar desde otro dispositivo.

Si dos dispositivos cambian de posición sin conexión:

- Conservar las ubicaciones recientes necesarias.
- Evitar retrocesos silenciosos.
- Aplicar una regla documentada o permitir seleccionar dónde continuar.

## 15.7. Cambios de esquema

Toda modificación persistente requiere:

- Migración.
- Compatibilidad.
- Tratamiento de nulos.
- Control de duplicados.
- Reversibilidad o recuperación.
- Pruebas.

No utilizar migraciones destructivas para resolver errores de esquema.

---

# 16. PROTECCIÓN COMPLETA DE DATOS

La V1 debe incluir:

1. Sincronización.
2. Copias periódicas.
3. Historial de versiones.
4. Papelera.
5. Restauración.
6. Exportación manual.
7. Comprobación de integridad.

## 16.1. Política inicial

Valores propuestos:

- Papelera: 30 días.
- Versiones de notas: 90 días.
- Copias periódicas: diarias cuando existan condiciones técnicas.
- Retención inicial de copias: 30 días.
- Exportación manual: disponible siempre.
- Restauración selectiva: por elemento y por biblioteca.

Evaluar los costes antes de activar copias de grandes bibliotecas.

## 16.2. Independencia de copias

La sincronización no constituye una copia de seguridad.

Google Drive es el almacenamiento principal de los archivos, pero una copia almacenada exclusivamente en la misma cuenta no protege de todos los escenarios de pérdida.

Diseñar una segunda vía de recuperación, mediante copias cifradas en un destino independiente o una exportación externa verificable, según capacidades y costes.

Para los datos en Supabase, establecer recuperación independiente de las tablas de producción.

## 16.3. Exportación local

Permitir crear un archivo de copia que incluya:

- Metadatos.
- Categorías.
- Etiquetas.
- Anotaciones.
- Relaciones.
- Progreso.
- Conversaciones.
- Preferencias.
- Manifest y versión.
- Comprobaciones de integridad.
- Libros originales si el usuario solicita copia completa.

No incluir credenciales API en las exportaciones.

Usar selección de documentos mediante mecanismos oficiales de Android.

## 16.4. Restauración

Debe permitir:

- Seleccionar archivo.
- Validar formato.
- Validar integridad.
- Comprobar versión.
- Mostrar resumen.
- Detectar conflictos.
- Elegir estrategia.
- Restaurar transaccionalmente cuando sea posible.
- Reanudar o revertir ante fallo.
- Informar del resultado.

## 16.5. Pruebas obligatorias

Probar:

- Eliminación y recuperación.
- Copia completa.
- Copia parcial.
- Interrupción.
- Archivo corrupto.
- Permisos revocados.
- Sobrescritura.
- Restauración sobre datos existentes.
- Restauración tras reinstalar.
- Concurrencia entre dispositivos.

No considerar el sistema terminado sin restauraciones reales verificadas.

---

# 17. ACTUALIZACIONES DE LA APLICACIÓN

La app deberá detectar versiones nuevas y ofrecer actualizaciones instalables.

## 17.1. Fuente

Utilizar GitHub Releases u otra fuente de versiones autorizada.

## 17.2. Comportamiento

- Comprobar versiones.
- Comparar versionCode.
- Mostrar novedades.
- Descargar APK.
- Verificar integridad.
- Solicitar instalación mediante Android.
- Conservar datos.
- Mostrar errores recuperables.

La instalación debe respetar los mecanismos y autorizaciones de Android.

No prometer instalación silenciosa.

## 17.3. Firma

Mantener:

- Mismo applicationId.
- Misma identidad de firma válida para actualizar.
- versionCode creciente.
- Compatibilidad de migraciones.

Proteger la clave de firma.

Nunca incluir el keystore privado o sus contraseñas en el repositorio público.

## 17.4. Entrega

Preparar:

- Proyecto compilable.
- Script de compilación.
- APK de desarrollo cuando se haya compilado.
- Procedimiento para APK de producción firmado.
- Instrucciones de instalación.
- Changelog.
- Documentación de actualización.

No declarar una release válida sin comprobar el artefacto correspondiente.

---

# 18. EXPERIENCIA DE CONFIGURACIÓN INICIAL

La primera ejecución debe ser intuitiva.

## 18.1. Pantalla de bienvenida

Ofrecer:

**Continuar sin cuenta**

**Iniciar sesión con Google**

Explicar brevemente las diferencias.

## 18.2. Inicio local

Si el usuario continúa sin cuenta:

- Abrir biblioteca.
- Ofrecer importar libro.
- Permitir lectura inmediata.
- Informar de la utilidad de las copias.
- No bloquear con formularios innecesarios.

## 18.3. Inicio autenticado

Mostrar asistente de configuración:

1. Cuenta Google.
2. Permiso Drive.
3. Carpeta de biblioteca.
4. Configuración Gemini.
5. Configuración Groq.
6. Pruebas de conexión.
7. Preferencias de sincronización.
8. Finalización.

Permitir completar posteriormente pasos opcionales.

No obligar a configurar ambos LLM si uno funciona.

## 18.4. Estado de servicios

Crear pantalla de diagnóstico:

```text
Cuenta Google       Conectada
Google Drive        Conectado
Supabase            Sincronizado
Gemini              Disponible
Groq                Disponible
Última sincronización
Copias de seguridad
Versión instalada
```

Los estados deben proceder de comprobaciones reales.

No mostrar conectividad ficticia.

---

# 19. SEGURIDAD Y PRIVACIDAD

Aplicar:

- TLS.
- Principio de mínimo privilegio.
- RLS.
- Gestión de secretos.
- Cifrado adecuado.
- Validación de archivos.
- Limitación de tamaños.
- Prevención de traversal.
- Protección frente a ZIP bombs.
- Protección frente a SQL injection.
- Protección frente a prompt injection.
- Sesiones seguras.
- Gestión de tokens.
- Revocación.
- Minimización de datos.
- Logs sin secretos.

No enviar libros enteros a servicios externos por defecto.

Informar al usuario cuando se envíen fragmentos o imágenes a un proveedor de IA.

Comprobar condiciones de uso y privacidad de los proveedores antes de formular afirmaciones sobre retención de datos o entrenamiento.

No implementar métodos para eludir DRM.

No almacenar en el repositorio público:

- EPUB privados.
- PDF privados.
- MOBI privados.
- Tokens.
- Claves API.
- Claves de servicio Supabase.
- Credenciales OAuth confidenciales.
- Keystores privados.
- Contraseñas.
- Datos de prueba personales.

---

# 20. DOCUMENTACIÓN OBLIGATORIA

Crear documentación suficiente, evitando duplicaciones.

Estructura orientativa:

```text
docs/
├── product/
│   ├── requirements.md
│   └── acceptance-criteria.md
├── architecture/
│   ├── overview.md
│   ├── data-model.md
│   ├── sync.md
│   └── decisions.md
├── setup/
│   ├── androidide.md
│   ├── google-cloud.md
│   ├── google-drive.md
│   ├── supabase.md
│   ├── gemini.md
│   └── groq.md
├── security/
│   ├── credentials.md
│   ├── privacy.md
│   └── backup-recovery.md
├── testing/
│   └── strategy.md
└── release/
    └── android-updates.md
```

Mantener `AGENTS.md` compacto.

Incluir instrucciones de configuración dentro de la aplicación, no solo en Markdown.

Toda la documentación de credenciales debe utilizar ejemplos ficticios.

---

# 21. ESTRATEGIA DE PRUEBAS

Aplicar los niveles del Protocolo Maestro.

## Nivel 0

Documentación y cambios inocuos.

Revisar diff y coherencia.

## Nivel 1

Interfaz y estilos.

Verificar sintaxis y funcionamiento visual pertinente.

## Nivel 2

Lógica aislada.

Tests unitarios y consumidores afectados.

## Nivel 3

Persistencia, importación, APIs y flujos entre componentes.

Tests de integración y recorridos relevantes.

## Nivel 4

Autenticación, seguridad, migraciones, backup, sincronización y distribución.

Verificaciones ampliadas, pruebas de recuperación y regresión suficiente.

No ejecutar toda la suite después de cada cambio menor.

## 21.1. Escenarios críticos de aceptación

### Escenario A — Uso sin cuenta

1. Instalar.
2. Continuar sin cuenta.
3. Importar EPUB.
4. Leer.
5. Subrayar.
6. Anotar.
7. Cerrar.
8. Abrir.
9. Verificar persistencia.

### Escenario B — OLED

1. Abrir libro.
2. Activar OLED.
3. Cambiar tipografía.
4. Pasar páginas.
5. Comprobar subrayados.
6. Cerrar y reabrir.
7. Verificar preferencias.

### Escenario C — Backup completo en ubicación elegida

1. Importar EPUB, PDF y MOBI, subrayar y anotar.
2. Elegir «Guardar copia completa» en Ajustes.
3. Seleccionar Drive o almacenamiento local desde el selector Android.
4. Verificar que ZIP incluye todos los originales y el JSON con versiones.
5. Comprobar que falta/corrupción de un solo libro cancela la operación.

### Escenario D — Restauración en otro dispositivo sin cuenta

1. Crear anotaciones y avanzar lectura en el móvil A.
2. Guardar copia ZIP y llevarla manualmente al móvil B.
3. Restaurarla sin OAuth o credenciales de Biblioteca IA.
4. Abrir los libros EPUB/PDF/MOBI restaurados sin revincular.
5. Comprobar posiciones, citas, notas, subrayados y versiones.
6. Restaurar de nuevo para verificar idempotencia y preservación de datos existentes.

### Escenario E — IA textual

1. Abrir libro.
2. Seleccionar párrafo.
3. Elegir Solo libro.
4. Consultar.
5. Verificar respuesta.
6. Abrir referencia.
7. Guardar conversación.

### Escenario F — IA externa

1. Consultar un concepto.
2. Elegir Libro + conocimiento externo.
3. Verificar separación de fuentes.
4. Guardar respuesta.

### Escenario G — Groq

1. Configurar Gemini y Groq.
2. Verificar ambos.
3. Simular fallo recuperable del principal.
4. Ejecutar respaldo.
5. Registrar proveedor utilizado.

### Escenario H — PDF visual

1. Importar PDF.
2. Seleccionar gráfico.
3. Consultar.
4. Obtener explicación.
5. Guardar referencia a página y región.

### Escenario I — Backup

1. Crear notas.
2. Exportar.
3. Modificar.
4. Restaurar.
5. Verificar integridad.
6. Confirmar ausencia de pérdidas.

### Escenario J — Actualización

1. Instalar versión anterior firmada.
2. Crear datos.
3. Instalar versión nueva firmada.
4. Verificar migración.
5. Comprobar datos.
6. Abrir libros y anotaciones.

### Escenario K — Aislamiento multiusuario

1. Crear usuario A.
2. Crear datos A.
3. Crear usuario B.
4. Intentar acceder a datos A desde B.
5. Verificar denegación.
6. Verificar ausencia de filtración por índices y búsquedas.

### Escenario L — Resistencia

Comprobar:

- Cortes de Internet.
- Tokens caducados.
- Drive sin permiso.
- Cuota agotada.
- Archivos corruptos.
- APK interrumpida.
- Aplicación cerrada durante sincronización.
- Conflictos.
- Escasez de memoria.
- Procesamiento cancelado.

---

# 22. PLAN DE EJECUCIÓN POR INCREMENTOS

No dividir el proyecto en piezas aisladas que solamente se integren al final.

Cada incremento debe dejar un recorrido funcional verificable.

## Incremento 0 — Auditoría y base

- Leer protocolo.
- Examinar repositorio.
- Establecer arquitectura.
- Definir contratos.
- Preparar Gradle.
- Preparar wrapper.
- Preparar script AndroidIDE.
- Crear primera pantalla.
- Comprobar compilación.

**Salida:** aplicación base reproducible.

## Incremento 1 — Biblioteca local

- Modelo Room.
- Importación inicial.
- Catálogo.
- Categorías.
- Etiquetas.
- Colecciones.
- Fichas.
- Persistencia.

**Salida:** biblioteca utilizable offline.

## Incremento 2 — Motor EPUB

- Apertura.
- Índice.
- Navegación.
- Búsqueda.
- Progreso.
- Temas.
- OLED.
- Marcadores.
- Subrayados.

**Salida:** lector EPUB completo.

## Incremento 3 — PDF y MOBI

- Motor PDF.
- Selección.
- Anotaciones.
- OCR.
- Importación y conversión MOBI.
- Localizadores.

**Salida:** compatibilidad demostrada con los formatos obligatorios.

Realizar antes las pruebas técnicas que puedan revelar bloqueos de compatibilidad, sin esperar innecesariamente a completar todos los módulos.

## Incremento 4 — Conocimiento

- Notas.
- Etiquetas de anotaciones.
- Relaciones.
- Cuaderno global.
- Exportaciones.

**Salida:** sistema avanzado de anotaciones.

## Incremento 5 — Identidad y Drive

- Google Auth.
- Supabase Auth.
- Permisos Drive.
- Carpetas.
- Transferencias.
- Recuperación.
- Sincronización de archivos.

**Salida:** libros recuperables en otro dispositivo.

## Incremento 6 — Sincronización

- Cambios incrementales.
- Operaciones offline.
- Conflictos.
- Posiciones.
- Anotaciones.
- Fusión local/cuenta.
- Aislamiento por usuario.

**Salida:** continuidad multidispositivo real.

## Incremento 7 — IA

- Configuración Gemini.
- Configuración Groq.
- Adaptadores.
- RAG.
- Referencias.
- Modos de consulta.
- Historial.
- Control de spoilers.
- Respaldo.

**Salida:** consultas textuales verificables.

## Incremento 8 — IA visual

- Selección de imágenes y regiones.
- Captura.
- Contexto textual.
- Modelos visuales.
- Historial.
- Referencias.
- Integración con cuaderno.

**Salida:** consultas visuales operativas.

## Incremento 9 — Protección

- Copias.
- Versiones.
- Papelera.
- Restauración.
- Integridad.
- Recuperación de fallos.

**Salida:** protección completa demostrada.

## Incremento 10 — Actualizaciones y preparación final

- Versionado.
- Firma.
- Detección de releases.
- Descarga segura.
- Instalación guiada.
- Migraciones.
- Documentación.
- Pruebas integradas.
- Ajustes de rendimiento.
- Validación final.

**Salida:** Biblioteca IA 1.0 preparada para distribución autorizada.

Puedes reorganizar incrementos si existen dependencias técnicas claras, documentando el motivo.

---

# 23. CRITERIOS DE FINALIZACIÓN GLOBAL

No declares la versión 1.0 terminada hasta comprobar:

- Proyecto reproducible.
- Configuración AndroidIDE correcta.
- Wrapper completo.
- Script funcional.
- APK compilado y comprobado.
- Biblioteca funcional.
- EPUB funcional.
- MOBI funcional dentro del alcance compatible declarado.
- PDF funcional.
- OCR funcional.
- Subrayados persistentes.
- Cuaderno global.
- Modo OLED.
- Modo local sin cuenta, Gmail ni OAuth.
- Copia completa en ubicación SAF elegida por el usuario.
- Archivos físicos EPUB/PDF/MOBI verificados por SHA-256.
- Restauración offline en dispositivo nuevo con marcas y notas intactas.
- Protección frente a ZIP corrupto, ausencias y reintentos.
- Gemini operativo.
- Groq operativo.
- Respaldo operativo.
- Consultas con referencias.
- Consultas visuales.
- Historial persistente.
- Copias de seguridad.
- Restauración real.
- Actualizaciones preparadas.
- Documentación de configuración.
- Pruebas suficientes.
- Ausencia de secretos en Git.
- Revisión final de datos y migraciones.

Distingue claramente entre:

**Implementado:** existe código.

**Verificado localmente:** existen pruebas ejecutadas.

**Verificado en dispositivo:** se ha comprobado en Android real.

**Verificado con servicio:** se han realizado operaciones reales contra la API.

**Pendiente:** falta evidencia o existe bloqueo.

No utilizar etiquetas de éxito sin evidencia.

---

# 24. RENDIMIENTO Y EFICIENCIA

Optimizar:

- Tiempo de apertura de libros.
- Memoria utilizada.
- Fluidez al pasar páginas.
- Consumo energético.
- Transferencias.
- Búsquedas.
- Procesamiento OCR.
- Indexación RAG.
- Coste de IA.
- Uso de almacenamiento.
- Inicio de la aplicación.

Medir antes de realizar optimizaciones complejas.

No introducir cachés ilimitadas.

Permitir borrar contenido descargado sin eliminar el libro de la biblioteca remota.

Evitar procesamientos duplicados mediante hash y versiones de índice.

Usar tareas en segundo plano compatibles con Android.

---

# 25. CONTROL DE COSTES

Antes de activar servicios de pago, identificar:

- Coste de Gemini.
- Coste de Groq.
- Coste de Supabase.
- Almacenamiento Drive.
- Embeddings.
- OCR remoto, si se utiliza.
- Copias de seguridad.
- Transferencias.
- Servicios complementarios.

Registrar el consumo por usuario.

Permitir configurar límites operativos razonables.

No ejecutar procesamiento masivo costoso sin consentimiento.

No presuponer que todas las APIs ofrecen uso gratuito ilimitado.

---

# 26. CONTINUIDAD Y CONOCIMIENTO DEL PROYECTO

Conservar decisiones arquitectónicas relevantes.

Mantener un estado operativo que incluya:

- Último incremento terminado.
- Incremento actual.
- Rama.
- Commit.
- Archivos afectados.
- Contratos.
- Pruebas.
- Bloqueos.
- Riesgos.
- Siguiente acción.

Evitar documentación repetitiva.

Al iniciar una nueva sesión, recuperar el contexto indispensable desde Git y la documentación existente.

No investigar de nuevo decisiones ya verificadas salvo que exista un cambio relevante.

---

# 27. FORMATO DE LOS INFORMES

Al terminar cada incremento informar:

**Resultado:** funcionalidad realmente obtenida.

**Cambios:** componentes principales.

**Verificación:** pruebas ejecutadas y resultado.

**Limitaciones:** elementos no comprobados o bloqueados.

**Git:** rama y commit, cuando existan.

**Siguiente incremento:** acción prevista.

No producir informes extensos de acciones internas innecesarias.

No confundir planes con resultados.

---

# 28. INSTRUCCIÓN FINAL AL COORDINADOR

Debes actuar como un responsable técnico que entrega un producto real.

No conviertas esta misión en una sucesión interminable de preguntas, documentos o prototipos.

Tu procedimiento inicial será:

1. Leer el Protocolo Maestro completo.
2. Inspeccionar el repositorio oficial.
3. Identificar restricciones reales.
4. Verificar las dependencias técnicas críticas, especialmente MOBI, PDF, OAuth y AndroidIDE.
5. Crear una arquitectura mínima coherente.
6. Definir agentes y responsabilidades.
7. Establecer criterios de aceptación.
8. Crear incrementos verificables.
9. Comenzar la implementación autorizada.
10. Integrar, probar y continuar hasta completar la aplicación.

No solicites confirmación para acciones técnicas ordinarias.

No inventes resultados de compilación, instalación o pruebas.

No ocultes incompatibilidades.

No elimines requisitos difíciles para simplificar artificialmente el desarrollo.

No sustituyas funcionalidades reales por botones simulados.

No comprometas la integridad de los datos para conseguir una entrega aparente.

**Prioridad absoluta: seguridad, corrección funcional, compatibilidad con AndroidIDE, continuidad de datos y experiencia de lectura.**

**Resultado objetivo: Biblioteca IA 1.0, una aplicación Android completa para leer, estudiar, anotar, consultar con IA y conservar una biblioteca personal sincronizada, con modo OLED confortable y preparada para evolucionar a un producto multiusuario.**

**Comienza por la auditoría del repositorio y la lectura del protocolo. Después organiza los agentes y ejecuta el desarrollo de manera incremental, autónoma y verificable, dentro de las autorizaciones disponibles.**