/*
 * Copyright (C) 2026 Alberto Lirussi
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.codingful.carve.extractor;

import com.codingful.carve.model.ExternalCallType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * User-defined external-call markers for vendor SDKs and custom libraries
 * that are not part of the Spring ecosystem detected by {@link com.codingful.carve.spring.SpringMarkers}.
 *
 * <h3>File format</h3>
 * A standard Java {@code .properties} file where each entry maps a
 * fully-qualified class name (or package prefix) to an {@link ExternalCallType} name.
 * Lines starting with {@code #} are comments.
 *
 * <pre>
 * # Vendor SDK that makes remote calls (SOAP over HTTPS)
 * com.acme.payments.client.PaymentGatewayClient = HTTP
 * com.acme.payments.client.RefundClient = HTTP
 *
 * # Match an entire vendor package with a trailing dot
 * com.acme.legacymq. = MESSAGING
 * </pre>
 *
 * <h3>Matching rules</h3>
 * <ol>
 *   <li>Exact FQN match — highest priority.</li>
 *   <li>Prefix match — if the FQN starts with a key that ends with {@code .},
 *       the entry matches the whole package subtree.</li>
 * </ol>
 */
public class UserDefinedMarkers {

    private static final Logger log = LoggerFactory.getLogger(UserDefinedMarkers.class);

    public static final UserDefinedMarkers EMPTY = new UserDefinedMarkers(Map.of());

    /** Exact-FQN matches, evaluated first. */
    private final Map<String, ExternalCallType> exact;
    /** Prefix matches (keys end with '.'), evaluated after exact. */
    private final List<Map.Entry<String, ExternalCallType>> prefixes;

    private UserDefinedMarkers(Map<String, ExternalCallType> entries) {
        Map<String, ExternalCallType> exactMap = new LinkedHashMap<>();
        List<Map.Entry<String, ExternalCallType>> prefixList = new ArrayList<>();

        for (var entry : entries.entrySet()) {
            if (entry.getKey().endsWith(".")) {
                prefixList.add(Map.entry(entry.getKey(), entry.getValue()));
            } else {
                exactMap.put(entry.getKey(), entry.getValue());
            }
        }
        this.exact    = Map.copyOf(exactMap);
        this.prefixes = List.copyOf(prefixList);
    }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /**
     * Loads markers from a {@code .properties} file on the filesystem.
     * Returns {@link #EMPTY} if the path is null.
     */
    public static UserDefinedMarkers fromFile(Path path) throws IOException {
        if (path == null) return EMPTY;
        try (InputStream in = Files.newInputStream(path)) {
            return fromStream(in, path.toString());
        }
    }

    private static UserDefinedMarkers fromStream(InputStream in, String source)
        throws IOException {

        Properties props = new Properties();
        props.load(in);

        Map<String, ExternalCallType> entries = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key).trim().toUpperCase(Locale.ROOT);
            try {
                ExternalCallType type = ExternalCallType.valueOf(value);
                entries.put(key.trim(), type);
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring unknown ExternalCallType '{}' for key '{}' in {}",
                    value, key, source);
            }
        }

        log.info("Loaded {} custom marker(s) from {}", entries.size(), source);
        return new UserDefinedMarkers(entries);
    }

    // -----------------------------------------------------------------------
    // Lookup
    // -----------------------------------------------------------------------

    /**
     * Returns the {@link ExternalCallType} for the given declaring-type FQN,
     * or {@code null} if no custom marker matches.
     */
    public ExternalCallType detect(String fqn) {
        if (fqn == null) return null;

        ExternalCallType exact = this.exact.get(fqn);
        if (exact != null) return exact;

        for (var entry : prefixes) {
            if (fqn.startsWith(entry.getKey())) return entry.getValue();
        }
        return null;
    }
}
