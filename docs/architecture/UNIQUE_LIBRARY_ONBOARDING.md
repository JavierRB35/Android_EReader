# Alta y vinculación única — #103

Incremento aislado de Agente 2. No reemplaza todavía el asistente legado y no activa conexiones reales.

| Estado | Presentación | Acción permitida |
|---|---|---|
| Choose | «Crear biblioteca nueva» / «Vincular a biblioteca existente» | Elegir una; vincular pide ID y código del dispositivo existente |
| Pending(CREATE) | «Creación pendiente de confirmar» | Reintentar la misma solicitud, no crear otra |
| Pending(JOIN) | «Vinculación pendiente de confirmar» | Reintentar la misma vinculación, no crear otra biblioteca |
| Linked | «Biblioteca vinculada» + ID de biblioteca | Continuar a inventario y reconciliación; no volver a crear |
| Unavailable | «No se puede comprobar tu biblioteca» | Volver a comprobar almacenamiento/conexión; nunca tratar como instalación limpia |

## Integración con Agente 1
OnboardingPort es una frontera propuesta, no una implementación de red. begin debe serializar la transición de Choose a Pending y persistir identidad, modo y los datos imprescindibles de alta de forma cifrada antes de cualquier envío. El adaptador debe incluir la autorización de bootstrap de un uso acordada en #103. Join usa el libraryId recibido y un nuevo deviceId propio, sin crear biblioteca, ZIP o JSON. resume conserva la identidad y solicitud exactas; una respuesta perdida no autoriza cambiar modo. read solo devuelve Linked tras confirmar y persistir pertenencia autenticada. Credenciales no pasan al estado visible, logs ni backups. Ejecutar I/O fuera de hilo UI. El código de vinculación solo se introduce y entrega al adaptador; nunca se muestra en mensajes de error.

La recuperación de un emparejamiento definitivamente caducado o una instalación con credencial inaccesible requiere un flujo explícito y seguro de reconciliación: no ofrecer reset automático ni crear otra biblioteca. Este incremento bloquea esas transiciones hasta que el contrato de backend permita resolverlas.

## Gates
Tests JVM con doble de puerto: instalación limpia vincula a ID existente, creación excluyente, entrada inválida, reintento después de reinicio, almacenamiento inaccesible, respuesta perdida tras confirmación y redacción de datos sensibles. No demuestran persistencia real, UI Android, gateway ni Drive. Pendientes: adaptador de Agente 1, pantalla conectada a la máquina de estados, autorización de alta, recuperación segura de credenciales, inventario #108, originales únicos, A/B y restauración #109.
