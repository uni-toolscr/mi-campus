# Privacidad

Mi Campus funciona sin cuenta y mantiene eventos, borradores, ajustes y la biblioteca de PDF en el dispositivo. No incluye analítica, publicidad ni registro remoto de contenido importado.

## Importación de documentos

- La aplicación solo abre el PDF que la persona elige mediante el selector de documentos de Android.
- Se pueden seleccionar hasta 10 PDF por lote. La aplicación copia cada original a almacenamiento privado de la app para permitir reintentos y funciones futuras. Los archivos no son accesibles para otras apps salvo cuando la persona pulsa **Abrir**, que concede acceso temporal de solo lectura a un visor de PDF.
- Los PDF permanecen en el dispositivo hasta que la persona los elimina desde la biblioteca de importaciones o desinstala Mi Campus. Al eliminar un PDF se conservan los borradores y eventos que produjo.
- Para permitir el chat local con documentos, Room conserva fragmentos del texto extraído mientras el PDF permanezca en la biblioteca. Esos fragmentos se guardan únicamente en el dispositivo, se excluyen de copias de seguridad y se eliminan junto con el PDF. Room también guarda metadatos del archivo, el estado de los intentos, campos editables del borrador y una evidencia breve con número de página.
- Si la extracción u OCR falla, siempre existe entrada manual.

## IA opcional

- **Modelo local (Gemini Nano)** y **API de Google Gemini (nube)** son controles separados y están desactivados inicialmente. Una actualización conserva el valor del control antiguo como preferencia inicial de ambos controles nuevos.
- Gemini Nano se ejecuta mediante AICore. `checkStatus()` determina la compatibilidad en tiempo de ejecución sin asumir fabricante, modelo de teléfono o versión de Nano. Sus prompts, el texto completo del documento y la respuesta sin procesar solo existen en memoria y no se guardan.
- En dispositivos compatibles, la primera descarga del modelo requiere red y confirmación explícita. La extracción posterior puede funcionar sin conexión, pero debe ejecutarse con Mi Campus en primer plano. Si la app pasa a segundo plano, el trabajo local se cancela de forma segura y se ofrece reintentarlo al volver.
- La nube nunca se usa sin una decisión explícita. Durante una importación, la decisión corresponde al lote seleccionado; en cada solicitud se envía un fragmento de texto extraído, el contrato fijo de extracción y, si se eligió una institución, solo su identificador corto canónico (por ejemplo, `UCR` o `UNA`). En el chat, se solicita autorización para cada pregunta: esa solicitud incluye la pregunta sin modificar, las siglas de las instituciones seleccionadas, los fragmentos locales más pertinentes (hasta 20 000 caracteres), sus nombres de documento y páginas, y hasta los cuatro mensajes recientes. Los archivos PDF originales nunca se envían.
- La nube intenta `gemini-3.5-flash`, `gemini-3.1-flash-lite` y `gemini-2.5-flash` en ese orden cuando hay límites de cuota o fallos transitorios. Todos usan la misma clave proporcionada por la persona. La clave se cifra con AES-GCM respaldado por Android Keystore y el texto cifrado queda en `noBackupFilesDir`.
- Las respuestas de IA son borradores editables que se revisan antes de guardar. No se crea ningún evento, recordatorio ni exportación de calendario sin confirmación de la persona.
- Solo las compilaciones de prueba incluyen un diagnóstico exportable y local de los últimos 10 intentos o autotests. Registra operaciones, tamaños numéricos, categorías de excepción permitidas, códigos de ML Kit, modelos y códigos HTTP, pero excluye nombres y contenido de PDF, prompts, respuestas, mensajes y trazas de excepciones, evidencias, claves, credenciales y datos del calendario. **Probar Gemini Nano** usa una solicitud constante sin datos personales y descarta la respuesta. No se transmite nada hasta que la persona pulsa **Exportar diagnóstico** y elige una app en la hoja de compartir; la compilación de producción usa un recorder inactivo.

## Permisos y copias de seguridad

- Notificaciones y acceso a alarmas exactas se solicitan únicamente al activar recordatorios. Sin acceso exacto se usa entrega inexacta.
- Lectura/escritura del calendario Android se solicita únicamente al pulsar exportar. Mi Campus no elimina eventos externos automáticamente.
- Las reglas de copia y transferencia excluyen la base Room, DataStore y cualquier ruta de PDF bajo el dominio normal de archivos. Android excluye por diseño `noBackupFilesDir`, donde se conservan las copias de PDF y el cifrado de la clave. Los PDF no se transfieren ni sincronizan a otro dispositivo.
