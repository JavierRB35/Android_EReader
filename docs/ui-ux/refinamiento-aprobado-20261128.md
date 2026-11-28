# Decisión del propietario — refinamiento editorial, 28/11/2026

Aprobado expresamente: refinar, no rediseñar integralmente. Apariencia editorial premium sin sacrificar funcionalidades existentes. La única ampliación funcional de este encargo es ajustar el tamaño repetidamente sin reabrir el menú (EPUB y MOBI), con aplicación inmediata y persistencia, preservando posición y anotaciones.

## Alcance aprobado
- S1/S2: escala tipográfica coherente, mínimo 11sp para texto de interfaz; respetar accesibilidad y preferencias.
- S3/S4: tokens cromáticos y radios comunes, reutilizar #156.
- S5: Literata/Inter solo si licencia OFL, compatibilidad, tamaño APK y preferencias existentes están acreditados; no descargar fuentes durante uso ni añadir dependencia.
- X1: alinear splash con grafito/dorado sin sustituir icono oficial (preservar identidad violeta del símbolo).
- X2/R4/R5/R6: controles 48dp, paleta común EPUB/MOBI, barras y marco según tema.
- B3: carátulas homogéneas sin recorte ni pérdida de miniaturas, no reducir visibilidad de selección por sombreado; coordinar #164.
- B4/S6: iconos vectoriales propios existentes donde sustituyen glifos; mantener nombres accesibles y acciones.
- B5: filtros visualmente distinguibles; no modificar selección de libros por sombreado.
- B6: transiciones discretas únicamente si respetan animación del sistema y no perjudican rendimiento/retorno de posición.
- R3 (parcial): solo tamaño de letra en menú persistente. No introducir brillo, márgenes, guionado, nuevas opciones ni rediseño de panel.

## Exclusiones vinculantes
No cambiar gestos laterales, navegación, menú de acciones, modo inmersivo, orden de controles, sincronización, Room, almacenamiento, arquitectura ni colores de subrayados. No alterar alcance de Conocimiento #153 ni Biblioteca #164; reutilizar mejoras ya integradas #156. No introducir costes.

## Estado y aceptación
Primer incremento registrado en #154: ajustes repetidos, paleta y barras, controles EPUB, filtros. Pendientes tipografía global y fuentes, radios pendientes, splash, iconos, carátulas y animaciones tras verificar lo ya integrado y las reservas. Pruebas de cambio consecutivo en ambos lectores, preservar preferencias/localizadores y anotaciones; verificar los cuatro temas, TalkBack, tamaños 320/360/412dp y fuente ampliada. Mantener los controles obligatorios de GitHub Actions. No declarar APK ni aceptación física antes de obtener evidencia.

## Segundo incremento en la misma rama
Recursos: superficies tonales y radios compartidos, splash cálido sin modificar símbolo violeta ni launcher, vector de importar, tarjeta de portada con borde redondeado y escala tipográfica 11sp mínima en tarjetas existentes. Se conserva fitCenter, identificadores de vistas y sombreado de selección. La rejilla dinámica 2:3 y adaptación del tercer modo se coordinan con #164; no cambiar su rama. Pendientes: resto de tipografía y glifos dispersos, fuentes embebidas con licencia y compatibilidad del lector, animaciones (baja prioridad). No afirmar entrega completa hasta gates y verificación.

## Tercer incremento
Inter y Literata originales, OFL 1.1, empaquetadas sin red; Literata en EPUB nativo/Readium y MOBI, títulos editoriales seleccionados, Inter en sistema visual. Se mantiene serif/sans y se reutiliza el asset de lectura. Vector de volver en EPUB. Fuentes: [procedencia y peso](font-sources.md). La aplicación real de fuentes y tamaño final de APK requieren CI; pruebas físicas pendientes. No modificar layouts reservados de #164 o #153.

## Cuarto incremento y criterios pendientes
Cuadrícula: contenedor 2:3 adaptativo con límite de altura anterior (172dp), centrado y fitCenter; no altera cargador ni selección. MOBI: reflujo de tamaño dentro del mismo bloque, conservando texto/posición y anotaciones; barras desplazables con controles 48dp. EPUB: registrar ancla si la actividad se detiene durante reflujo. Ajustes: vector volver y títulos Literata. Pruebas instrumentadas de fuentes locales/licencias, proporción y paleta incluidas en startup; recorrido MOBI verifica ancla de lectura. Se posponen transiciones adicionales hasta demostrar que aportan valor sin penalizar latencia; se conservan animaciones nativas. Restan los ajustes visuales en zonas reservadas #153/#164, solicitados al coordinador de sus PR; no crear ramas ni solapar. CI de aa69f8f: tres gates verdes; journeys falló por 429 de Maven Central (JUnit/Hamcrest) y cuatro controles cancelados por sustitución de HEAD. El nuevo HEAD deberá superar sus propios gates.
