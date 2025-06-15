/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.common.spi;

import java.util.ServiceLoader;

/**
 * Helper class for loading and managing SPI implementations.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class SpiHelper {

  private final ComponentLoader componentLoader;

  private SpiHelper(ComponentLoader componentLoader) {
    this.componentLoader = componentLoader;
  }

  /** Create a {@link SpiHelper} which loads SPIs using the {@code classLoader}. */
  public static SpiHelper create(ClassLoader classLoader) {
    return new SpiHelper(serviceComponentLoader(classLoader));
  }

  /** Create a {@link SpiHelper} which loads SPIs using the {@code componentLoader}. */
  public static SpiHelper create(ComponentLoader componentLoader) {
    return new SpiHelper(componentLoader);
  }

  /** Create a {@link ComponentLoader} which loads using the {@code classLoader}. */
  public static ComponentLoader serviceComponentLoader(ClassLoader classLoader) {
    return new ServiceLoaderComponentLoader(classLoader);
  }

  /** Return the backing underlying {@link ComponentLoader}. */
  public ComponentLoader getComponentLoader() {
    return componentLoader;
  }

  private static class ServiceLoaderComponentLoader implements ComponentLoader {
    private final ClassLoader classLoader;

    private ServiceLoaderComponentLoader(ClassLoader classLoader) {
      this.classLoader = classLoader;
    }

    @Override
    public <T> Iterable<T> load(Class<T> spiClass) {
      return ServiceLoader.load(spiClass, classLoader);
    }
  }
}
