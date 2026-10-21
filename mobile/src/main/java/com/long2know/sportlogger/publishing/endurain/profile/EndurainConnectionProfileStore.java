package com.long2know.sportlogger.publishing.endurain.profile;

import android.content.Context;
import android.content.SharedPreferences;

import com.long2know.sportlogger.connector.endurain.EndurainConnectionConfig;
import com.long2know.sportlogger.connector.endurain.EndurainEndpoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EndurainConnectionProfileStore {
    private static final String PREFERENCES = "endurain_connection_profiles_v1";
    private static final String IDENTITIES = "identities";
    private static final int MAX_LABEL_LENGTH = 120;
    private static final int MAX_URL_LENGTH = 2_048;

    private final SharedPreferences preferences;

    public static EndurainConnectionProfileStore forApplication(Context context) {
        Context applicationContext =
                Objects.requireNonNull(context, "context").getApplicationContext();
        return new EndurainConnectionProfileStore(
                applicationContext.getSharedPreferences(
                        PREFERENCES,
                        Context.MODE_PRIVATE));
    }

    EndurainConnectionProfileStore(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    public synchronized EndurainConnectionProfile save(
            String existingConnectionIdentity,
            String label,
            String baseUrl,
            boolean enabled) {
        String normalizedLabel = normalizeLabel(label);
        EndurainEndpoint endpoint = EndurainEndpoint.parse(baseUrl);
        String normalizedBaseUrl = endpoint.normalizedBaseUrl();
        if (normalizedBaseUrl.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("Endurain base URL is too long");
        }

        String identity = existingConnectionIdentity;
        if (identity == null || identity.trim().isEmpty()) {
            identity = UUID.randomUUID().toString();
        }
        new EndurainConnectionConfig(endpoint, identity);

        EndurainConnectionProfile existing = get(identity);
        if (existing != null
                && !existing.getNormalizedBaseUrl().equals(normalizedBaseUrl)) {
            throw new IllegalArgumentException(
                    "A connection identity cannot be moved to another Endurain instance");
        }

        String credentialReference =
                existing == null
                        ? credentialReference(normalizedBaseUrl, identity)
                        : existing.getCredentialReference();
        Set<String> identities =
                new HashSet<>(preferences.getStringSet(
                        IDENTITIES,
                        Collections.emptySet()));
        identities.add(identity);
        SharedPreferences.Editor editor = preferences.edit()
                .putStringSet(IDENTITIES, identities)
                .putString(key(identity, "label"), normalizedLabel)
                .putString(key(identity, "base_url"), normalizedBaseUrl)
                .putBoolean(key(identity, "enabled"), enabled)
                .putString(
                        key(identity, "credential_reference"),
                        credentialReference);
        if (!editor.commit()) {
            throw new IllegalStateException(
                    "Could not persist Endurain connection settings");
        }
        return new EndurainConnectionProfile(
                identity,
                normalizedLabel,
                normalizedBaseUrl,
                enabled,
                credentialReference);
    }

    public synchronized EndurainConnectionProfile setEnabled(
            String connectionIdentity,
            boolean enabled) {
        EndurainConnectionProfile profile = require(connectionIdentity);
        if (!preferences.edit()
                .putBoolean(key(connectionIdentity, "enabled"), enabled)
                .commit()) {
            throw new IllegalStateException(
                    "Could not persist Endurain connection state");
        }
        return new EndurainConnectionProfile(
                profile.getConnectionIdentity(),
                profile.getLabel(),
                profile.getNormalizedBaseUrl(),
                enabled,
                profile.getCredentialReference());
    }

    public synchronized EndurainConnectionProfile get(String connectionIdentity) {
        if (connectionIdentity == null) {
            return null;
        }
        Set<String> identities =
                preferences.getStringSet(IDENTITIES, Collections.emptySet());
        if (!identities.contains(connectionIdentity)) {
            return null;
        }
        String label = preferences.getString(
                key(connectionIdentity, "label"),
                null);
        String baseUrl = preferences.getString(
                key(connectionIdentity, "base_url"),
                null);
        String credentialReference = preferences.getString(
                key(connectionIdentity, "credential_reference"),
                null);
        if (label == null || baseUrl == null || credentialReference == null) {
            throw new IllegalStateException(
                    "Stored Endurain connection settings are incomplete");
        }
        EndurainEndpoint endpoint = EndurainEndpoint.parse(baseUrl);
        new EndurainConnectionConfig(endpoint, connectionIdentity);
        return new EndurainConnectionProfile(
                connectionIdentity,
                label,
                endpoint.normalizedBaseUrl(),
                preferences.getBoolean(
                        key(connectionIdentity, "enabled"),
                        false),
                credentialReference);
    }

    public synchronized List<EndurainConnectionProfile> list() {
        List<String> identities = new ArrayList<>(
                preferences.getStringSet(
                        IDENTITIES,
                        Collections.emptySet()));
        Collections.sort(identities);
        List<EndurainConnectionProfile> profiles =
                new ArrayList<>(identities.size());
        for (String identity : identities) {
            profiles.add(require(identity));
        }
        return Collections.unmodifiableList(profiles);
    }

    public AutoCloseable observe(Executor executor, Runnable listener) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(listener, "listener");
        AtomicBoolean open = new AtomicBoolean(true);
        SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
                (ignored, key) -> {
                    if (open.get()) {
                        executor.execute(listener);
                    }
                };
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        return () -> {
            if (open.compareAndSet(true, false)) {
                preferences.unregisterOnSharedPreferenceChangeListener(
                        preferenceListener);
            }
        };
    }

    private EndurainConnectionProfile require(String connectionIdentity) {
        EndurainConnectionProfile profile = get(connectionIdentity);
        if (profile == null) {
            throw new IllegalArgumentException(
                    "Endurain connection does not exist");
        }
        return profile;
    }

    private static String normalizeLabel(String label) {
        Objects.requireNonNull(label, "label");
        String normalized = label.trim();
        if (normalized.isEmpty()
                || normalized.length() > MAX_LABEL_LENGTH
                || normalized.indexOf('\n') >= 0
                || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    "Connection label must be a non-empty single line");
        }
        return normalized;
    }

    private static String key(String identity, String field) {
        return "profile." + identity + "." + field;
    }

    private static String credentialReference(
            String normalizedBaseUrl,
            String connectionIdentity) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
        digest.update(normalizedBaseUrl.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        byte[] hash = digest.digest(
                connectionIdentity.getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder("endurain-v1-");
        for (byte current : hash) {
            value.append(String.format("%02x", current & 0xff));
        }
        return value.toString();
    }
}
