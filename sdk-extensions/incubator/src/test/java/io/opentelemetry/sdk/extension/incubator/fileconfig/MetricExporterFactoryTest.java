/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.extension.incubator.fileconfig;

import static io.opentelemetry.sdk.extension.incubator.fileconfig.FileConfigTestUtil.createTempFileWithContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import io.opentelemetry.api.incubator.config.DeclarativeConfigException;
import io.opentelemetry.api.incubator.config.DeclarativeConfigProperties;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.internal.testing.CleanupExtension;
import io.opentelemetry.sdk.autoconfigure.internal.SpiHelper;
import io.opentelemetry.sdk.autoconfigure.spi.internal.ComponentProvider;
import io.opentelemetry.sdk.extension.incubator.fileconfig.component.MetricExporterComponentProvider;
import io.opentelemetry.sdk.extension.incubator.fileconfig.internal.model.ConsoleModel;
import io.opentelemetry.sdk.extension.incubator.fileconfig.internal.model.NameStringValuePairModel;
import io.opentelemetry.sdk.extension.incubator.fileconfig.internal.model.OtlpMetricModel;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.DefaultAggregationSelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetricExporterFactoryTest {

  @RegisterExtension
  static final SelfSignedCertificateExtension serverTls = new SelfSignedCertificateExtension();

  @RegisterExtension
  static final SelfSignedCertificateExtension clientTls = new SelfSignedCertificateExtension();

  @RegisterExtension CleanupExtension cleanup = new CleanupExtension();

  private final SpiHelper spiHelper =
      spy(SpiHelper.create(SpanExporterFactoryTest.class.getClassLoader()));
  private List<ComponentProvider<?>> loadedComponentProviders = Collections.emptyList();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    when(spiHelper.load(ComponentProvider.class))
        .thenAnswer(
            invocation -> {
              List<ComponentProvider<?>> result =
                  (List<ComponentProvider<?>>) invocation.callRealMethod();
              loadedComponentProviders =
                  result.stream().map(Mockito::spy).collect(Collectors.toList());
              return loadedComponentProviders;
            });
  }

  private ComponentProvider<?> getComponentProvider(String name, Class<?> type) {
    return loadedComponentProviders.stream()
        .filter(
            componentProvider ->
                componentProvider.getName().equals(name)
                    && componentProvider.getType().equals(type))
        .findFirst()
        .orElseThrow(IllegalStateException::new);
  }

  @Test
  void create_OtlpDefaults() {
    List<Closeable> closeables = new ArrayList<>();
    OtlpHttpMetricExporter expectedExporter = OtlpHttpMetricExporter.getDefault();
    cleanup.addCloseable(expectedExporter);

    MetricExporter exporter =
        MetricExporterFactory.getInstance()
            .create(
                new io.opentelemetry.sdk.extension.incubator.fileconfig.internal.model
                        .PushMetricExporterModel()
                    .withOtlp(new OtlpMetricModel()),
                spiHelper,
                closeables);
    cleanup.addCloseable(exporter);
    cleanup.addCloseables(closeables);

    assertThat(exporter.toString()).isEqualTo(expectedExporter.toString());

    ArgumentCaptor<DeclarativeConfigProperties> configCaptor =
        ArgumentCaptor.forClass(DeclarativeConfigProperties.class);
    ComponentProvider<?> componentProvider = getComponentProvider("otlp", MetricExporter.class);
    verify(componentProvider).create(configCaptor.capture());
    DeclarativeConfigProperties configProperties = configCaptor.getValue();
    assertThat(configProperties.getString("protocol")).isNull();
    assertThat(configProperties.getString("endpoint")).isNull();
    assertThat(configProperties.getStructured("headers")).isNull();
    assertThat(configProperties.getString("compression")).isNull();
    assertThat(configProperties.getInt("timeout")).isNull();
    assertThat(configProperties.getString("certificate")).isNull();
    assertThat(configProperties.getString("client_key")).isNull();
    assertThat(configProperties.getString("client_certificate")).isNull();
    assertThat(configProperties.getString("temporality_preference")).isNull();
    assertThat(configProperties.getString("default_histogram_aggregation")).isNull();
  }

  @Test
  void create_OtlpConfigured(@TempDir Path tempDir)
      throws CertificateEncodingException, IOException {
    List<Closeable> closeables = new ArrayList<>();
    OtlpHttpMetricExporter expectedExporter =
        OtlpHttpMetricExporter.builder()
            .setEndpoint("http://example:4318/v1/metrics")
            .addHeader("key1", "value1")
            .addHeader("key2", "value2")
            .setTimeout(Duration.ofSeconds(15))
            .setCompression("gzip")
            .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
            .setDefaultAggregationSelector(
                DefaultAggregationSelector.getDefault()
                    .with(InstrumentType.HISTOGRAM, Aggregation.base2ExponentialBucketHistogram()))
            .build();
    cleanup.addCloseable(expectedExporter);

    // Write certificates to temp files
    String certificatePath =
        createTempFileWithContent(
            tempDir, "certificate.cert", serverTls.certificate().getEncoded());
    String clientKeyPath =
        createTempFileWithContent(tempDir, "clientKey.key", clientTls.privateKey().getEncoded());
    String clientCertificatePath =
        createTempFileWithContent(
            tempDir, "clientCertificate.cert", clientTls.certificate().getEncoded());

    MetricExporter exporter =
        MetricExporterFactory.getInstance()
            .create(
                new io.opentelemetry.sdk.extension.incubator.fileconfig.internal.model
                        .PushMetricExporterModel()
                    .withOtlp(
                        new OtlpMetricModel()
                            .withProtocol("http/protobuf")
                            .withEndpoint("http://example:4318/v1/metrics")
                            .withHeaders(
                                Arrays.asList(
                                    new NameStringValuePairModel()
                                        .withName("key1")
                                        .withValue("value1"),
                                    new NameStringValuePairModel()
                                        .withName("key2")
                                        .withValue("value2")))
                            .withCompression("gzip")
                            .withTimeout(15_000)
                            .withCertificate(certificatePath)
                            .withClientKey(clientKeyPath)
                            .withClientCertificate(clientCertificatePath)
                            .withTemporalityPreference("delta")
                            .withDefaultHistogramAggregation(
                                OtlpMetricModel.DefaultHistogramAggregation
                                    .BASE_2_EXPONENTIAL_BUCKET_HISTOGRAM)),
                spiHelper,
                closeables);
    cleanup.addCloseable(exporter);
    cleanup.addCloseables(closeables);

    assertThat(exporter.toString()).isEqualTo(expectedExporter.toString());

    ArgumentCaptor<DeclarativeConfigProperties> configCaptor =
        ArgumentCaptor.forClass(DeclarativeConfigProperties.class);
    ComponentProvider<?> componentProvider = getComponentProvider("otlp", MetricExporter.class);
    verify(componentProvider).create(configCaptor.capture());
    DeclarativeConfigProperties configProperties = configCaptor.getValue();
    assertThat(configProperties.getString("protocol")).isEqualTo("http/protobuf");
    assertThat(configProperties.getString("endpoint")).isEqualTo("http://example:4318/v1/metrics");
    List<DeclarativeConfigProperties> headers = configProperties.getStructuredList("headers");
    assertThat(headers)
        .isNotNull()
        .satisfiesExactly(
            header -> {
              assertThat(header.getString("name")).isEqualTo("key1");
              assertThat(header.getString("value")).isEqualTo("value1");
            },
            header -> {
              assertThat(header.getString("name")).isEqualTo("key2");
              assertThat(header.getString("value")).isEqualTo("value2");
            });
    assertThat(configProperties.getString("compression")).isEqualTo("gzip");
    assertThat(configProperties.getInt("timeout")).isEqualTo(Duration.ofSeconds(15).toMillis());
    assertThat(configProperties.getString("certificate")).isEqualTo(certificatePath);
    assertThat(configProperties.getString("client_key")).isEqualTo(clientKeyPath);
    assertThat(configProperties.getString("client_certificate")).isEqualTo(clientCertificatePath);
    assertThat(configProperties.getString("temporality_preference")).isEqualTo("delta");
    assertThat(configProperties.getString("default_histogram_aggregation"))
        .isEqualTo("base2_exponential_bucket_histogram");
  }

  @Test
  void create_Console() {
    List<Closeable> closeables = new ArrayList<>();
    LoggingMetricExporter expectedExporter = LoggingMetricExporter.create();
    cleanup.addCloseable(expectedExporter);

    io.opentelemetry.sdk.metrics.export.MetricExporter exporter =
        MetricExporterFactory.getInstance()
            .create(
                new io.opentelemetry.sdk.extension.incubator.fileconfig.internal.model
                        .PushMetricExporterModel()
                    .withConsole(new ConsoleModel()),
                spiHelper,
                closeables);
    cleanup.addCloseable(exporter);
    cleanup.addCloseables(closeables);

    assertThat(exporter.toString()).isEqualTo(expectedExporter.toString());
  }

  @Test
  void create_SpiExporter_Unknown() {
    assertThatThrownBy(
            () ->
                MetricExporterFactory.getInstance()
                    .create(
                        new io.opentelemetry.sdk.extension.incubator.fileconfig.internal.model
                                .PushMetricExporterModel()
                            .withAdditionalProperty(
                                "unknown_key", ImmutableMap.of("key1", "value1")),
                        spiHelper,
                        new ArrayList<>()))
        .isInstanceOf(DeclarativeConfigException.class)
        .hasMessage(
            "No component provider detected for io.opentelemetry.sdk.metrics.export.MetricExporter with name \"unknown_key\".");
  }

  @Test
  void create_SpiExporter_Valid() {
    MetricExporter metricExporter =
        MetricExporterFactory.getInstance()
            .create(
                new io.opentelemetry.sdk.extension.incubator.fileconfig.internal.model
                        .PushMetricExporterModel()
                    .withAdditionalProperty("test", ImmutableMap.of("key1", "value1")),
                spiHelper,
                new ArrayList<>());
    assertThat(metricExporter)
        .isInstanceOf(MetricExporterComponentProvider.TestMetricExporter.class);
    assertThat(
            ((MetricExporterComponentProvider.TestMetricExporter) metricExporter)
                .config.getString("key1"))
        .isEqualTo("value1");
  }
}
