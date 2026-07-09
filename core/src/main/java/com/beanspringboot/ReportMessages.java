package com.beanspringboot;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Localised messages for report generation, loaded from classpath property bundles.
 * Italian is the default language; unsupported or missing language codes fail safe
 * to Italian so report generation never breaks on misconfiguration.
 */
public final class ReportMessages {

    /** Default report language (Italian). */
    public static final String DEFAULT_LANGUAGE = "it";

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("it", "en");
    private static final String BUNDLE_PATH_TEMPLATE = "/report-messages_%s.properties";

    private final String languageCode;
    private final Properties messages;

    private ReportMessages(final String languageCode, final Properties messages) {
        this.languageCode = languageCode;
        this.messages = messages;
    }

    /**
     * Creates the message set for the requested two-character language code.
     * Blank, {@code null}, or unsupported codes fall back to Italian with a warning.
     *
     * @param requestedLanguage the requested language code (for example {@code "en"})
     * @param log optional sink for diagnostic messages; may be {@code null}
     * @return the resolved messages, never {@code null}
     */
    public static ReportMessages forLanguage(final String requestedLanguage, final Consumer<String> log) {
        final String resolved = resolveLanguageCode(requestedLanguage, log);
        return new ReportMessages(resolved, loadBundle(resolved));
    }

    /**
     * Resolves the effective language code, warning and defaulting to Italian when the
     * requested code is absent or not supported.
     */
    private static String resolveLanguageCode(final String requestedLanguage, final Consumer<String> log) {
        if (null == requestedLanguage || requestedLanguage.isBlank()) {
            return DEFAULT_LANGUAGE;
        }
        final String normalised = requestedLanguage.trim().toLowerCase(Locale.ROOT);
        if (SUPPORTED_LANGUAGES.contains(normalised)) {
            return normalised;
        }
        if (null != log) {
            log.accept("⚠️ Unsupported report language '" + requestedLanguage
                    + "'. Falling back to '" + DEFAULT_LANGUAGE + "'. Supported: " + SUPPORTED_LANGUAGES);
        }
        return DEFAULT_LANGUAGE;
    }

    /**
     * Loads the property bundle for a supported language code from the classpath using UTF-8.
     *
     * @param languageCode - The two-letter code of the language to use
     * @throws IllegalStateException if the bundle resource is missing from the classpath
     */
    private static Properties loadBundle(final String languageCode) {
        final String resource = String.format(BUNDLE_PATH_TEMPLATE, languageCode);
        try (final InputStream stream = ReportMessages.class.getResourceAsStream(resource)) {
            if (null == stream) {
                throw new IllegalStateException(resource + " not found in classpath!");
            }
            final Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return properties;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to load report messages from " + resource, e);
        }
    }

    /**
     * Returns the resolved two-character language code (post-fallback), suitable for
     * the HTML {@code lang} attribute.
     */
    public String languageCode() {
        return languageCode;
    }

    /**
     * Returns the message for the given key, or the key itself if it is missing,
     * so report generation never fails on an incomplete bundle.
     *
     * @param key the message key
     */
    public String get(final String key) {
        return messages.getProperty(key, key);
    }

    /**
     * Returns the message for the given key formatted with the supplied arguments.
     * Arguments destined for HTML output must be escaped by the caller beforehand.
     *
     * @param key the message key of a {@link String#format(String, Object...)} pattern
     * @param arguments the format arguments
     */
    public String format(final String key, final Object... arguments) {
        return String.format(get(key), arguments);
    }
}
