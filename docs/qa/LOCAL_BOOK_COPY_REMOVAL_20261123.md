# BibliotecaAI — retirar únicamente la copia local de un libro

## Comportamiento

- En la **ficha del libro**, cuando el archivo está disponible, aparece «Eliminar del dispositivo», debajo de la información del archivo.
- Es una acción confirmada, restringida a `filesDir/books/<bookId>/`. No invoca `ContentResolver.delete()`, ningún proveedor SAF ni las APIs de Drive.
- El archivo se borra físicamente y **solo** se limpia `books.localPath`; se conserva la ficha, el hash para revinculación, las categorías, etiquetas, colecciones, notas, subrayados, argumento, favoritos y progreso.
- Tras el borrado desaparece «Eliminar del dispositivo» y aparece «Vincular archivo original (Drive u otro origen)…». El nuevo archivo se comprueba por SHA-256 antes de revincularlo.
- Si el registro apunta fuera de la carpeta privada correspondiente, la operación **se rechaza** sin tocarlo.
- Si se cancela el diálogo, no se elimina nada.

## Integridad del backup

El **backup completo** actual requiere todos los originales físicos; sin uno de ellos aborta para no crear una copia incompleta. Por tanto, mientras un libro esté retirado localmente, no podrá generarse un ZIP completo de la biblioteca hasta revincularlo. Se avisa en el diálogo. Esta función **no verifica** que el usuario tenga otra copia en Drive; el usuario debe comprobarlo antes de eliminar la única copia local.

## Pruebas

- JVM: elimina solo la copia interna correcta, rechaza archivos fuera de la app y no permite afectar a otro libro.
- Instrumentación: interacción real con el botón y confirmación; comprueba que el archivo desaparece, el libro persiste con favorito/progreso y el usuario puede revincular.
- Validación manual en Android: importar un EPUB/PDF/MOBI, abrir ficha, cancelar, confirmar, revisar archivo ausente y ficha intacta, revincular el original desde SAF y volver a leerlo.
