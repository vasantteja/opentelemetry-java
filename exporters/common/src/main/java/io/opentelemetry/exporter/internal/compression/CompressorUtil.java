/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.internal.compression;

import static io.opentelemetry.api.internal.Utils.checkArgument;
import static java.util.stream.Collectors.joining;

import io.opentelemetry.sdk.common.spi.ComponentLoader;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Utilities for resolving SPI {@link Compressor}s.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 *
 * @see CompressorProvider
 */
public final class CompressorUtil {

  private CompressorUtil() {}

  /**
   * Validate that the {@code compressionMethod} is "none" or matches a registered compressor.
   *
   * @return {@code null} if {@code compressionMethod} is "none" or the registered compressor
   * @throws IllegalArgumentException if no match is found
   */
  @Nullable
  public static Compressor validateAndResolveCompressor(
      String compressionMethod, ComponentLoader componentLoader) {
    if ("none".equals(compressionMethod)) {
      return null;
    }

    Map<String, Compressor> compressors = new HashMap<>();
    // Load compressors from SPI
    for (CompressorProvider spi : componentLoader.load(CompressorProvider.class)) {
      Compressor compressor = spi.getInstance();
      compressors.put(compressor.getEncoding(), compressor);
    }
    // Hardcode gzip compressor
    compressors.put(GzipCompressor.getInstance().getEncoding(), GzipCompressor.getInstance());

    Compressor compressor = compressors.get(compressionMethod);
    checkArgument(
        compressor != null,
        "Unsupported compressionMethod. Compression method must be \"none\" or one of: "
            + compressors.keySet().stream().collect(joining(",", "[", "]")));
    return compressor;
  }
}
