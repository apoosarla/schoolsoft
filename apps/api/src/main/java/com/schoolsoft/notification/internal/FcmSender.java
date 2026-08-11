package com.schoolsoft.notification.internal;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * FCM adapter. Firebase is only touched when a service-account JSON path is
 * configured; with the property unset (no Firebase project provisioned) this
 * reports itself disabled and {@link ChannelRouter} keeps the log-only stub.
 */
@Component
public class FcmSender {

    private static final String APP_NAME = "schoolsoft-notifications";

    private final String credentialsPath;
    private volatile FirebaseMessaging messaging;

    public FcmSender(@Value("${schoolsoft.notifications.fcm.credentials-path:}") String credentialsPath) {
        this.credentialsPath = credentialsPath == null ? "" : credentialsPath.trim();
    }

    public boolean isEnabled() { return !credentialsPath.isEmpty(); }

    public String send(String token, String templateCode, Map<String, Object> vars)
            throws FirebaseMessagingException, IOException {
        Message message = Message.builder()
            .setToken(token)
            .putAllData(dataPayload(templateCode, vars))
            .build();
        return messaging().send(message);
    }

    private static Map<String, String> dataPayload(String templateCode, Map<String, Object> vars) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("template", templateCode == null ? "" : templateCode);
        if (vars != null) {
            vars.forEach((k, v) -> data.put(k, String.valueOf(v)));
        }
        return data;
    }

    private FirebaseMessaging messaging() throws IOException {
        FirebaseMessaging local = messaging;
        if (local == null) {
            synchronized (this) {
                local = messaging;
                if (local == null) {
                    local = FirebaseMessaging.getInstance(firebaseApp());
                    messaging = local;
                }
            }
        }
        return local;
    }

    private FirebaseApp firebaseApp() throws IOException {
        for (FirebaseApp existing : FirebaseApp.getApps()) {
            if (APP_NAME.equals(existing.getName())) return existing;
        }
        try (FileInputStream in = new FileInputStream(credentialsPath)) {
            FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(in))
                .build();
            return FirebaseApp.initializeApp(options, APP_NAME);
        }
    }
}
