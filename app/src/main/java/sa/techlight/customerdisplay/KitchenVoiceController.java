package sa.techlight.customerdisplay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Locale;

/** Safe push-to-talk plus optional wake-listening controller. */
public final class KitchenVoiceController implements RecognitionListener,
        TextToSpeech.OnInitListener {
    public interface Listener {
        void onVoiceText(String text, boolean fromWakeMode);
        void onVoiceState(String state, boolean error);
    }

    public static final int REQUEST_RECORD_AUDIO = 9127;

    private final Activity activity;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean destroyed;
    private boolean listening;
    private boolean wakeEnabled;
    private boolean currentWakeAttempt;
    private String languageTag = "ar-SA";

    public KitchenVoiceController(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        try { tts = new TextToSpeech(activity.getApplicationContext(), this); }
        catch (Throwable error) { state("TTS unavailable", true); }
    }

    public boolean isAvailable() {
        try { return SpeechRecognizer.isRecognitionAvailable(activity); }
        catch (Throwable ignored) { return false; }
    }

    public void setLanguage(boolean arabic) {
        languageTag = arabic ? "ar-SA" : "en-US";
        if (ttsReady && tts != null) {
            try { tts.setLanguage(arabic ? new Locale("ar", "SA") : Locale.US); }
            catch (Throwable ignored) { }
        }
    }

    public void listenOnce() {
        currentWakeAttempt = false;
        startListening(false);
    }

    public void setWakeEnabled(boolean enabled) {
        wakeEnabled = enabled;
        if (!enabled) {
            if (currentWakeAttempt) stopListening();
            state("Wake listening off", false);
            return;
        }
        currentWakeAttempt = true;
        scheduleWake(250L);
    }

    public boolean isWakeEnabled() { return wakeEnabled; }

    public void onPermissionResult(boolean granted) {
        if (granted) {
            if (wakeEnabled) scheduleWake(150L);
            else listenOnce();
        } else state("Microphone permission denied", true);
    }

    private void startListening(boolean wakeAttempt) {
        if (destroyed || listening) return;
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            try { activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO); }
            catch (Throwable error) { state("Microphone permission unavailable", true); }
            return;
        }
        if (!isAvailable()) {
            state("Speech recognition is not installed", true);
            return;
        }
        try {
            ensureRecognizer();
            currentWakeAttempt = wakeAttempt;
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag);
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                    languageTag.startsWith("ar") ? "قل الأمر" : "Say a command");
            recognizer.startListening(intent);
            listening = true;
            state(wakeAttempt ? "Listening for Hi TechPro" : "Listening", false);
        } catch (Throwable error) {
            listening = false;
            state(error.getClass().getSimpleName(), true);
            if (wakeEnabled) scheduleWake(1800L);
        }
    }

    private void ensureRecognizer() {
        if (recognizer != null) return;
        recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
        recognizer.setRecognitionListener(this);
    }

    public void stopListening() {
        listening = false;
        try { if (recognizer != null) recognizer.cancel(); } catch (Throwable ignored) { }
    }

    public void speak(String text, boolean arabic) {
        if (destroyed || !ttsReady || tts == null || text == null || text.trim().isEmpty()) return;
        try {
            tts.setLanguage(arabic ? new Locale("ar", "SA") : Locale.US);
            tts.setSpeechRate(1.02f);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kds-" + System.currentTimeMillis());
        } catch (Throwable error) { state("Voice response unavailable", true); }
    }

    private void scheduleWake(long delay) {
        handler.removeCallbacksAndMessages(null);
        if (!wakeEnabled || destroyed) return;
        handler.postDelayed(() -> startListening(true), Math.max(100L, delay));
    }

    private void state(String value, boolean error) {
        if (listener != null) listener.onVoiceState(value, error);
    }

    @Override public void onReadyForSpeech(Bundle params) { state("Ready", false); }
    @Override public void onBeginningOfSpeech() { state("Hearing", false); }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { state("Processing", false); }

    @Override public void onError(int error) {
        listening = false;
        boolean quiet = currentWakeAttempt && (error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || error == SpeechRecognizer.ERROR_CLIENT);
        if (!quiet) state(errorLabel(error), true);
        if (wakeEnabled) scheduleWake(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 2200L : 900L);
    }

    @Override public void onResults(Bundle results) {
        listening = false;
        ArrayList<String> values = results == null ? null
                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String selected = values == null || values.isEmpty() ? "" : values.get(0);
        boolean wakeAttempt = currentWakeAttempt;
        if (!selected.trim().isEmpty() && listener != null) {
            KitchenVoiceParser.Intent parsed = KitchenVoiceParser.parse(selected);
            if (!wakeAttempt || parsed.wakePhrase) listener.onVoiceText(selected, wakeAttempt);
        }
        if (wakeEnabled) scheduleWake(900L);
        else state("Idle", false);
    }

    @Override public void onPartialResults(Bundle partialResults) { }
    @Override public void onEvent(int eventType, Bundle params) { }

    @Override public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS;
        if (ttsReady) setLanguage(languageTag.startsWith("ar"));
    }

    public void shutdown() {
        destroyed = true;
        wakeEnabled = false;
        handler.removeCallbacksAndMessages(null);
        try { if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); } }
        catch (Throwable ignored) { }
        recognizer = null;
        try { if (tts != null) { tts.stop(); tts.shutdown(); } }
        catch (Throwable ignored) { }
        tts = null;
    }

    private static String errorLabel(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "Audio error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Microphone permission required";
            case SpeechRecognizer.ERROR_NETWORK: return "Voice network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Voice network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH: return "Command not recognized";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "Voice recognizer busy";
            case SpeechRecognizer.ERROR_SERVER: return "Voice service error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "No speech heard";
            default: return "Voice error " + error;
        }
    }
}
