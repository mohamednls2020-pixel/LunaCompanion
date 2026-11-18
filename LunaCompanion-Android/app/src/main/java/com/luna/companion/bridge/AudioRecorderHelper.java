package com.luna.companion.bridge;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Java helper wrapping Android TextToSpeech (automatically stripping *asterisk
 * roleplay actions*) and on-device/system SpeechRecognizer for voice input.
 */
public final class AudioRecorderHelper implements TextToSpeech.OnInitListener {
    private static final String TAG = "AudioRecorderHelper";
    private static final Pattern ASTERISK_ACTION_PATTERN = Pattern.compile("\\*[^*]*\\*");

    public interface VoiceCallback {
        void onSpeechPartial(String text);
        void onSpeechFinal(String text);
        void onSpeechStateChanged(boolean listening);
        void onSpeechError(String reason);
        void onTtsStateChanged(boolean speaking);
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final VoiceCallback callback;

    @Nullable
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;

    @Nullable
    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;

    public AudioRecorderHelper(Context context, VoiceCallback callback) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
        this.textToSpeech = new TextToSpeech(this.appContext, this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && textToSpeech != null) {
            int langResult = textToSpeech.setLanguage(Locale.US);
            ttsReady = (langResult != TextToSpeech.LANG_MISSING_DATA
                    && langResult != TextToSpeech.LANG_NOT_SUPPORTED);
            textToSpeech.setPitch(1.08f);
            textToSpeech.setSpeechRate(0.98f);
            textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    mainHandler.post(() -> callback.onTtsStateChanged(true));
                }

                @Override
                public void onDone(String utteranceId) {
                    mainHandler.post(() -> callback.onTtsStateChanged(false));
                }

                @Override
                public void onError(String utteranceId) {
                    mainHandler.post(() -> callback.onTtsStateChanged(false));
                }
            });
        } else {
            ttsReady = false;
            Log.w(TAG, "TextToSpeech initialization returned status: " + status);
        }
    }

    /**
     * Strips *asterisk roleplay actions* so Luna only speaks her dialogue aloud.
     */
    public static String stripRoleplayActions(String rawDialogue) {
        if (rawDialogue == null) return "";
        String cleaned = ASTERISK_ACTION_PATTERN.matcher(rawDialogue).replaceAll(" ");
        return cleaned.replaceAll("\\s{2,}", " ").trim();
    }

    public void speakDialogue(String rawDialogue) {
        String spokenOnly = stripRoleplayActions(rawDialogue);
        if (spokenOnly.isEmpty() || textToSpeech == null || !ttsReady) {
            return;
        }
        mainHandler.post(() -> {
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UUID.randomUUID().toString());
            textToSpeech.speak(spokenOnly, TextToSpeech.QUEUE_FLUSH, params, params.getString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID));
        });
    }

    public void stopSpeaking() {
        mainHandler.post(() -> {
            if (textToSpeech != null) {
                textToSpeech.stop();
            }
            callback.onTtsStateChanged(false);
        });
    }

    public void startListening() {
        mainHandler.post(() -> {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                callback.onSpeechError("Speech recognition unavailable on this device.");
                return;
            }
            if (isListening) {
                stopListening();
                return;
            }
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext);
                speechRecognizer.setRecognitionListener(createListener());
            }

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);

            isListening = true;
            callback.onSpeechStateChanged(true);
            speechRecognizer.startListening(intent);
        });
    }

    public void stopListening() {
        mainHandler.post(() -> {
            isListening = false;
            if (speechRecognizer != null) {
                try {
                    speechRecognizer.stopListening();
                } catch (Exception ignored) {}
            }
            callback.onSpeechStateChanged(false);
        });
    }

    public void release() {
        mainHandler.post(() -> {
            if (speechRecognizer != null) {
                try {
                    speechRecognizer.destroy();
                } catch (Exception ignored) {}
                speechRecognizer = null;
            }
            if (textToSpeech != null) {
                try {
                    textToSpeech.stop();
                    textToSpeech.shutdown();
                } catch (Exception ignored) {}
                textToSpeech = null;
            }
            ttsReady = false;
            isListening = false;
        });
    }

    private RecognitionListener createListener() {
        return new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                isListening = false;
                callback.onSpeechStateChanged(false);
            }

            @Override
            public void onError(int error) {
                isListening = false;
                callback.onSpeechStateChanged(false);
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    callback.onSpeechError("Voice input error code: " + error);
                }
            }

            @Override
            public void onResults(Bundle results) {
                isListening = false;
                callback.onSpeechStateChanged(false);
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    callback.onSpeechFinal(matches.get(0));
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (partial != null && !partial.isEmpty()) {
                    callback.onSpeechPartial(partial.get(0));
                }
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        };
    }
}
