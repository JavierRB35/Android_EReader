# Biblioteca IA — UX de la biblioteca principal (v0.1)

**Rama:** feature/ux-library-reading-first-20261120  
**Integración:** coordinador ZCode mediante PR; no publicar una versión ni fusionar sin validación.  
**Base:** main @ 2ef20417465af6fde80a0e8a24249f414dd25fe4.

## Objetivo y cambios

- **Retomar lectura:** tarjeta «Continuar leyendo» con el título real del último libro abierto; un toque abre el lector existente. No aparece sin lectura previa ni en una vista restringida a una colección.
- **Biblioteca antes que configuración:** cabecera compacta; «Organizar» y «Ajustes» como iconos accesibles de 48 dp; importación visible junto al recuento sin reservar un bloque grande junto a la navegación inferior.
- **Filtros recuperables:** los resultados vacíos distinguen biblioteca sin libros, colección vacía y búsqueda filtrada. «Limpiar filtros» restablece texto y estante, pero no abandona una colección ni borra datos.
- **Continuidad:** búsqueda y filtro se conservan tras recreación (giro del dispositivo); la vista lista/cuadrícula mantiene su preferencia y procura conservar el primer libro visible.
- **Adaptación:** la cuadrícula usa una columna cuando dos no serían legibles. El estado vacío puede desplazarse con letra ampliada. Se conservan los tres destinos de navegación y el contrato de importación SAF.

## Alcance excluido por coordinación

Sin cambios en ficha de libro (rama feature/compact-book-detail-inline-organize-20261120), lectores EPUB/PDF/MOBI, anotaciones, backup, Room, credenciales o manifest. No modifica strings.xml compartido con la ficha; las cadenas específicas están en ux_library.xml.

## Verificación

- Pruebas instrumentadas focalizadas: LibraryHomeUxDeviceTest (rotación y conmutador de vista).
- La CI deberá compilar recursos y código y ejecutar los tests disponibles; no equivale a inspección visual en Samsung.
- Antes de integrar v0.1: comprobar manualmente con teléfono de ~320–360 dp, fuente normal y 200 %, TalkBack, teclado abierto, colección con/sin libros, búsqueda sin resultados, importación y reanudación de la lectura. La revisión física y el merge corresponden al coordinador.

**No declarar v0.1 lista hasta resolver los P0 de marcado visual, apertura en Samsung y copia completa.** Ver issue #24.
