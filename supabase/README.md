# Supabase — infraestructura histórica, fuera del alcance vigente

**No desplegar, configurar credenciales ni habilitar sincronización remota Supabase para BibliotecaAI.** La decisión vinculante del propietario del 20/11/2026 excluye cuentas, OAuth y sincronización Supabase. Consultar [ADR-008](../docs/architecture/adr-008-saf-drive-byok-v1.md) y [sincronización de posiciones SAF](../docs/architecture/reading-position-sync-saf-20261123.md).

Se conserva `migrations/0001_initial_schema.sql` exclusivamente como referencia histórica y de trazabilidad. No ejecutarlo sobre infraestructura existente. El despliegue anterior se documentó en [PR #5](https://github.com/JavierRB35/Android_EReader/pull/5); las migraciones aplicadas fueron `20261120145506_initial_schema_hardened` y `20261120145627_secure_updated_at_search_path`. No se elimina ni modifica el servicio externo sin una orden específica.

El procedimiento de configuración anterior queda en el historial Git, no como instrucciones operativas. La aplicación vigente utiliza almacenamiento local y SAF, con claves IA BYOK independientes.
