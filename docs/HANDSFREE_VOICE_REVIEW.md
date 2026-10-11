# Manos libres en AnkiDroid (plan de implementación)

Este fork es público y accesible. Base verificada: `main`, aplicación en `AnkiDroid/`, pantalla de repaso en `AbstractFlashcardViewer.kt`. El permiso `RECORD_AUDIO` ya está declarado en `AnkiDroid/src/main/AndroidManifest.xml`.

## Objetivo funcional

Modo opcional manos libres de revisión, completamente offline:
1. Lee el frente por TTS local de Android.
2. Cuando termina el TTS, activa reconocimiento de voz japonés local.
3. Detecta el fin del habla mediante el endpointing del motor.
4. Compara contra la respuesta esperada del reverso y/o un campo explícito de respuestas aceptadas.
5. En coincidencia, califica Good y avanza; si no, revela la respuesta y califica Again (configurable).
6. Incluye cancelación, indicador de escucha, control de permiso y opción para desactivar.
7. Nunca invoca servicios de nube, Gemini ni una LLM.

## Riesgo clave / decisión técnica

No asumir que la API Android `SpeechRecognizer` siempre opera offline: depende de proveedor/idioma/dispositivo. Hay que usar un motor embebido como Vosk (modelo japonés instalado previamente) o sherpa-onnx. Antes de integrar, verificar versión y licencia actuales de la librería, soporte ABI y API Java/Kotlin.

No comparar la transcripción cruda únicamente con el kanji del reverso. El campo esperado puede tener varias formas. Diseñar una normalización conservadora y un campo opcional `VoiceAccepted` de la tarjeta, separando alternativas por `|`, por ejemplo `行きます|いきます`. No admitir coincidencia difusa agresiva en respuestas cortas. Ofrecer alternativa de revisar manualmente cuando el modelo no devuelve texto con confianza suficiente.

## Áreas del código verificadas

- `AnkiDroid/src/main/java/com/ichi2/anki/AbstractFlashcardViewer.kt`
  - `displayCardQuestion()` muestra el frente (aprox. línea 1347).
  - `displayCardAnswer()` muestra el reverso (aprox. línea 1379).
  - `answerCard(rating: Rating)` califica y redibuja (aprox. línea 862).
  - TTS inicializado en `onCollectionLoaded()`; libera en `onDestroy()`.
- `AnkiDroid/src/main/AndroidManifest.xml`: `RECORD_AUDIO` ya declarado.
- `AnkiDroid/build.gradle.kts`: dependencias declaradas alrededor de línea 738. Catálogo en `gradle/libs.versions.toml`.
- Ajustes existentes: `AnkiDroid/src/main/res/xml/preferences_advanced.xml` y strings en `AnkiDroid/src/main/res/values/10-preferences.xml`.

## Plan de implementación

1. Añadir una preferencia de usuario "Manos libres" (apagada por defecto) en la pantalla de repaso / ajustes.
2. Crear una abstracción `OfflineSpeechRecognizer` e implementar un backend local, con carga/descarga inicial explícita del modelo; jamás descargar en segundo plano sin consentimiento.
3. Dar soporte a estados: disabled, speaking-question, listening, processing, showing-result, error.
4. Integrar el ciclo de vida con Reviewer: cancelar escucha en `onPause()`, liberar recursos en `onDestroy()`; no iniciar escucha hasta finalizar TTS; impedir dobles calificaciones.
5. Obtener la respuesta esperada a partir del contenido de tarjeta/notas sin alterar la colección; campo opcional para alternativas de voz.
6. Añadir tests unitarios para normalización y alternativas y tests del flujo de review; compilar debug APK.
7. Probar en dispositivo real, modo avión, japonés hablado, palabras cortas, ruido, silencio y cancelación.

## Reglas de aceptación

- En modo avión, una tarjeta de texto con español en el frente y japonés en el campo esperado se reproduce, escucha y califica sin red.
- No usa una API externa ni envía audio fuera del dispositivo.
- Silencios y errores de reconocimiento nunca deben auto-marcar como correctos.
- El usuario puede detener el modo manos libres o calificar manualmente.
- El build debug y tests relevantes pasan.

## Nota

Este archivo documenta la implementación acordada; por sí solo no agrega funcionalidad a la aplicación. La integración y la compilación deben realizarse y verificarse antes de afirmar que funciona.
