# Supabase — infraestructura histórica y coordinador privado

La infraestructura histórica de cuentas, OAuth y sincronización Supabase queda fuera del alcance vigente (ADR-008). No reactivar ni ejecutar `migrations/0001_initial_schema.sql` sobre infraestructura existente. Migraciones históricas y PR #5 se conservan como trazabilidad.

El nuevo coordinador privado de biblioteca única (#99/#103, ADR-009 en #98) tiene autorización limitada, separada de la arquitectura histórica. Solo se admite el trabajo aislado y pruebas sintéticas según su contrato de seguridad; no activar el cliente Android, exponer credenciales ni mover datos reales hasta validar el sistema integral. No modificar o eliminar infraestructura ajena.
