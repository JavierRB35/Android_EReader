# Fuentes locales del refinamiento editorial (#154)

Archivos originales no modificados obtenidos del repositorio oficial https://github.com/google/fonts (directorios ofl/inter y ofl/literata). Licencias SIL OFL 1.1 completas dentro de la APK, assets/licenses. Comprobación Git de identidad de cada binario origen/destino:

| Fuente | Blob SHA-1 Git | Bytes |
|---|---|---:|
| Inter variable regular | 047c92f6e2212473dc436020afed689527076d44 | 876576 |
| Literata variable regular | 0a81b4984a4a4eb101e0ddc28babc85bb137efef | 955132 |
| Literata variable cursiva | 8690209cd1bb4d9be1c1a0e976ee2f740c94093b | 902728 |

Total binario sin comprimir: 2.734.436 bytes. Se reutiliza el mismo asset Literata en Android nativo y Readium; no se duplica el fichero. Sin red, SDK ni dependencia nueva. Readium 3.0.3 permite addFontFamilyDeclaration y fuentes de readium/fonts (ver código fuente de EpubNavigatorFragment y FontFamilyDeclaration en el tag 3.0.3). La preferencia serif/sans sigue siendo la misma; la opción sans del lector conserva su tipografía existente. Inter se aplica en interfaz, no sustituye preferencias de lectura. Android minSdk 26 soporta fuentes variables; verificar renderizado y fuente ampliada en emulador/dispositivo. Incremento de tamaño efectivo de APK pendiente de compilación, no confundir con peso de fuentes.
