# Privacidad

Mi Campus funciona sin cuenta y mantiene eventos, borradores y ajustes en el dispositivo. No incluye analítica, publicidad ni registro de contenido importado.

## Importación de documentos

- La aplicación solo abre el PDF que la persona elige mediante el selector de documentos de Android.
- El PDF se lee en memoria y nunca se copia a la base de datos ni a archivos propios.
- El texto completo extraído se conserva únicamente durante esa importación. Room guarda solo campos editables del borrador y una evidencia breve con número de página; al confirmar o descartar el borrador, esa evidencia se elimina.
- Si la extracción u OCR falla, siempre existe entrada manual.

## IA opcional

- La IA está desactivada inicialmente. Primero se intenta Gemini Nano local cuando el dispositivo lo admite.
- La nube nunca se usa automáticamente. Cada PDF presenta una decisión independiente y solo con aceptación se envía el texto extraído; el archivo PDF original no se envía.
- La nube usa `gemini-3.5-flash` con nivel de razonamiento bajo y una clave proporcionada por la persona. La clave se cifra con AES-GCM respaldado por Android Keystore y el texto cifrado queda en `noBackupFilesDir`.
- Las respuestas de IA son borradores editables. No se crea ningún evento sin confirmación.

## Permisos y copias de seguridad

- Notificaciones y acceso a alarmas exactas se solicitan únicamente al activar recordatorios. Sin acceso exacto se usa entrega inexacta.
- Lectura/escritura del calendario Android se solicita únicamente al pulsar exportar. Mi Campus no elimina eventos externos automáticamente.
- Las reglas de copia y transferencia excluyen la base Room y DataStore. Android también excluye `noBackupFilesDir`, donde se conserva el cifrado de la clave.
