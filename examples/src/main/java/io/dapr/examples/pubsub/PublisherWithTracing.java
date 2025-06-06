/*
 * Copyright 2021 The Dapr Authors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
limitations under the License.
*/

package io.dapr.examples.pubsub;

import io.dapr.client.DaprClient;
import io.dapr.client.DaprClientBuilder;
import io.dapr.examples.OpenTelemetryConfig;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import static io.dapr.examples.OpenTelemetryConfig.getReactorContext;

/**
 * Message publisher.
 * 1. Build and install jars:
 * mvn clean install
 * 2. cd [repo root]/examples
 * 3. Run the program:
 * dapr run --resources-path ./components/pubsub --app-id publisher-tracing -- \
 * java -jar target/dapr-java-sdk-examples-exec.jar io.dapr.examples.pubsub.PublisherWithTracing
 */
public class PublisherWithTracing {

  //Number of messages to be sent.
  private static final int NUM_MESSAGES = 10;

  //The title of the topic to be used for publishing
  private static final String TOPIC_NAME = "testingtopic";

  //The name of the pubsub
  private static final String PUBSUB_NAME = "messagebus";

  /**
   * This is the entry point of the publisher app example.
   *
   * @param args Args, unused.
   * @throws Exception A startup Exception.
   */
  public static void main(String[] args) throws Exception {
    OpenTelemetrySdk openTelemetrySdk = OpenTelemetryConfig.createOpenTelemetry();
    Tracer tracer = openTelemetrySdk.getTracer(PublisherWithTracing.class.getCanonicalName());
    Span span = tracer.spanBuilder("Publisher's Main").setSpanKind(SpanKind.CLIENT).startSpan();

    try (DaprClient client = new DaprClientBuilder().build()) {
      try (Scope scope = span.makeCurrent()) {
        for (int i = 0; i < NUM_MESSAGES; i++) {
          String message = String.format("This is message #%d", i);

          // Publishing messages, notice the use of subscriberContext() for tracing.
          client.publishEvent(
              PUBSUB_NAME,
              TOPIC_NAME,
              message).contextWrite(getReactorContext()).block();

          System.out.println("Published message: " + message);

          try {
            Thread.sleep((long) (1000 * Math.random()));
          } catch (InterruptedException e) {
            e.printStackTrace();
            Thread.currentThread().interrupt();

            return;
          }
        }
      }

      span.end();
      openTelemetrySdk.getSdkTracerProvider().shutdown();
      System.out.println("Done.");
    }
  }
}
