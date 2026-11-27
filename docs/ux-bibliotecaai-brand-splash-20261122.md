# BibliotecaAI — identidad minimalista y splash no bloqueante (22/11/2026)

## Recursos visuales

- Icono **e-reader geométrico con líneas de texto y destello IA violeta**, sin libro abierto ni paleta dorada que recuerde a Scriptura.
- Icono adaptable Android 26+: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` y vectores `ic_biblioteca_mark.xml`, `ic_biblioteca_icon_background.xml`; canal monocromo Android 13+.
- Splash declarativo `activity_splash.xml` y tema Android 12+ en `res/values-v31/themes.xml`; fondo azul noche, blanco y violeta, con **BibliotecaAI** y subtítulo «E-reader con asistente IA».
- Identidad de autor visible al pie: **CJRB — Carlos Javier Ruiz Bermúdez**. Las iniciales son una representación tipográfica, no un logo personal externo inventado.

## Contrato funcional

1. El launcher abre `SplashActivity`, con pantalla de marca durante **2.000 ms desde onCreate**, sin esperar ningún trabajo de copia. Android 12+ podría añadir su animación nativa al inicio.
2. En `SplashActivity.onCreate` se llama inmediatamente a `BackupRoutine.refreshOnLibraryOpen()`; esta rutina mantiene su coroutine `Dispatchers.IO` independiente de la Activity, los permisos SAF persistidos y el `Mutex` de copia/restauración.
3. Con destino autorizado se muestra **«Comprobando copia de seguridad…»**, nunca un porcentaje simulado. Solo si cambia la huella se actualiza y verifica el **mismo ZIP**.
4. Sin destino o con permisos revocados se muestra **«Copia sin configurar · Ajustes»** y se oculta el indicador activo. No se solicita autorización desde splash ni se crea un ZIP implícito.
5. Tras dos segundos, se abre la biblioteca incluso si la copia sigue en curso o termina con error. Ajustes conserva el estado real de última copia/error. La rutina habitual de `MainActivity.onResume` sigue actuando sobre el mismo `Mutex` sin duplicar archivos.
6. No cambia la cobertura del ZIP (originales EPUB/PDF/MOBI, notas, marcas, etiquetas, posiciones y metadatos), ni el mecanismo de integridad/recuperación, ni se introduce OAuth, Gmail o API de Drive.

## Verificación

- Test instrumental nuevo `SplashActivityLaunchTest`: splash inflado, estado textual no vacío y transición tras dos segundos.
- Incluido en `android-startup-smoke.yml` junto a las pruebas de apertura existentes.
- **Pendientes de evidencia real:** compilación CI, máscaras y tamaños de icono de launchers Samsung, latencia en Android 12+, exportación SAF física/Drive y restauración de ZIP personal. No declarar una copia realizada sin verificar el archivo en Ajustes.
