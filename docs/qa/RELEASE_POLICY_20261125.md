# Control ejecutable de publicación y cierre

PR de cierre de directrices del 25/11/2026. Antes de compilar se compara la versión semántica con todas las releases Android oficiales paginadas; se inspeccionan APK históricas reales mediante aapt/apksigner y SHA-256, sin presuponer el versionCode a partir del tag. El cache de main contiene exclusivamente metadatos auditados y se invalida si cambian assets/certificado. Se vuelve a verificar el conjunto inmediatamente antes de publicar. Primera auditoría requiere descargar las APK históricas, por lo que puede tardar más. El fallo bloquea la release, sin reducir controles.

Tras comprobar release y ambos assets se limpia exclusivamente la rama de una PR fusionada de esta publicación, si su commit de integración es ancestro, HEAD remoto coincide, no está protegida ni sirve a PR abiertas; se usa lease de Git. Se excluyen las ramas WIP y las históricas que requieren coordinación adicional. No se modifican los datos del usuario. La verificación en los dispositivos físicos sigue pendiente.

Pruebas unitarias nuevas de incremento doble, falta de auditoría, paginación, checksum ausente y selección de ramas. CI y ejecución real pendientes al abrir la PR.

### Cierre de integraciones anteriores pendientes de limpieza
La primera publicación con automatización activa también contempla las ramas concretas revisadas de #93, #94, #101, #102 y #104, aunque ya estuvieran incluidas en una APK anterior. Aplica las mismas comprobaciones de identidad de PR/rama, ancestro publicado, HEAD exacto, protección y dependencias. No amplía el barrido a ramas históricas ajenas ni WIP. Se prueba la selección de backlog sin borrar ramas durante CI.
