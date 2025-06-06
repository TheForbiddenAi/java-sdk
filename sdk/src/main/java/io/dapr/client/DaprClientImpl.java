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

package io.dapr.client;

import com.google.common.base.Strings;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import io.dapr.client.domain.ActorMetadata;
import io.dapr.client.domain.AppConnectionPropertiesHealthMetadata;
import io.dapr.client.domain.AppConnectionPropertiesMetadata;
import io.dapr.client.domain.BulkPublishEntry;
import io.dapr.client.domain.BulkPublishRequest;
import io.dapr.client.domain.BulkPublishResponse;
import io.dapr.client.domain.BulkPublishResponseFailedEntry;
import io.dapr.client.domain.CloudEvent;
import io.dapr.client.domain.ComponentMetadata;
import io.dapr.client.domain.ConfigurationItem;
import io.dapr.client.domain.ConversationInput;
import io.dapr.client.domain.ConversationOutput;
import io.dapr.client.domain.ConversationRequest;
import io.dapr.client.domain.ConversationResponse;
import io.dapr.client.domain.DaprMetadata;
import io.dapr.client.domain.DeleteJobRequest;
import io.dapr.client.domain.DeleteStateRequest;
import io.dapr.client.domain.ExecuteStateTransactionRequest;
import io.dapr.client.domain.GetBulkSecretRequest;
import io.dapr.client.domain.GetBulkStateRequest;
import io.dapr.client.domain.GetConfigurationRequest;
import io.dapr.client.domain.GetJobRequest;
import io.dapr.client.domain.GetJobResponse;
import io.dapr.client.domain.GetSecretRequest;
import io.dapr.client.domain.GetStateRequest;
import io.dapr.client.domain.HttpEndpointMetadata;
import io.dapr.client.domain.HttpExtension;
import io.dapr.client.domain.InvokeBindingRequest;
import io.dapr.client.domain.InvokeMethodRequest;
import io.dapr.client.domain.JobSchedule;
import io.dapr.client.domain.LockRequest;
import io.dapr.client.domain.PublishEventRequest;
import io.dapr.client.domain.QueryStateItem;
import io.dapr.client.domain.QueryStateRequest;
import io.dapr.client.domain.QueryStateResponse;
import io.dapr.client.domain.RuleMetadata;
import io.dapr.client.domain.SaveStateRequest;
import io.dapr.client.domain.ScheduleJobRequest;
import io.dapr.client.domain.State;
import io.dapr.client.domain.StateOptions;
import io.dapr.client.domain.SubscribeConfigurationRequest;
import io.dapr.client.domain.SubscribeConfigurationResponse;
import io.dapr.client.domain.SubscriptionMetadata;
import io.dapr.client.domain.TransactionalStateOperation;
import io.dapr.client.domain.UnlockRequest;
import io.dapr.client.domain.UnlockResponseStatus;
import io.dapr.client.domain.UnsubscribeConfigurationRequest;
import io.dapr.client.domain.UnsubscribeConfigurationResponse;
import io.dapr.client.resiliency.ResiliencyOptions;
import io.dapr.exceptions.DaprException;
import io.dapr.internal.exceptions.DaprHttpException;
import io.dapr.internal.grpc.DaprClientGrpcInterceptors;
import io.dapr.internal.resiliency.RetryPolicy;
import io.dapr.internal.resiliency.TimeoutPolicy;
import io.dapr.serializer.DaprObjectSerializer;
import io.dapr.serializer.DefaultObjectSerializer;
import io.dapr.utils.DefaultContentTypeConverter;
import io.dapr.utils.TypeRef;
import io.dapr.v1.CommonProtos;
import io.dapr.v1.DaprGrpc;
import io.dapr.v1.DaprProtos;
import io.dapr.v1.DaprProtos.ActiveActorsCount;
import io.dapr.v1.DaprProtos.ActorRuntime;
import io.dapr.v1.DaprProtos.AppConnectionHealthProperties;
import io.dapr.v1.DaprProtos.AppConnectionProperties;
import io.dapr.v1.DaprProtos.MetadataHTTPEndpoint;
import io.dapr.v1.DaprProtos.PubsubSubscription;
import io.dapr.v1.DaprProtos.PubsubSubscriptionRule;
import io.dapr.v1.DaprProtos.RegisteredComponents;
import io.grpc.Channel;
import io.grpc.Metadata;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.util.context.ContextView;
import reactor.util.retry.Retry;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.dapr.internal.exceptions.DaprHttpException.isSuccessfulHttpStatusCode;
import static io.dapr.internal.exceptions.DaprHttpException.isValidHttpStatusCode;
import static io.dapr.internal.exceptions.DaprHttpException.parseHttpStatusCode;

/**
 * Implementation of the Dapr client combining gRPC and HTTP (when applicable).
 *
 * @see io.dapr.v1.DaprGrpc
 * @see io.dapr.client.DaprClient
 */
public class DaprClientImpl extends AbstractDaprClient {

  private final Logger logger;

  /**
   * The GRPC managed channel to be used.
   */
  private final GrpcChannelFacade channel;

  /**
   * The retry policy.
   */
  private final RetryPolicy retryPolicy;

  /**
   * The async gRPC stub.
   */
  private final DaprGrpc.DaprStub asyncStub;

  /**
   * The HTTP client to be used for healthz and HTTP service invocation only.
   *
   * @see io.dapr.client.DaprHttp
   */
  private final DaprHttp httpClient;

  private final DaprClientGrpcInterceptors grpcInterceptors;

  /**
   * Default access level constructor, in order to create an instance of this class use io.dapr.client.DaprClientBuilder
   *
   * @param channel           Facade for the managed GRPC channel
   * @param asyncStub         async gRPC stub
   * @param objectSerializer  Serializer for transient request/response objects.
   * @param stateSerializer   Serializer for state objects.
   * @see DaprClientBuilder
   */
  DaprClientImpl(
      GrpcChannelFacade channel,
      DaprGrpc.DaprStub asyncStub,
      DaprHttp httpClient,
      DaprObjectSerializer objectSerializer,
      DaprObjectSerializer stateSerializer) {
    this(channel, asyncStub, httpClient, objectSerializer, stateSerializer, null, null);
  }

  /**
   * Default access level constructor, in order to create an instance of this class use io.dapr.client.DaprClientBuilder
   *
   * @param channel           Facade for the managed GRPC channel
   * @param asyncStub         async gRPC stub
   * @param objectSerializer  Serializer for transient request/response objects.
   * @param stateSerializer   Serializer for state objects.
   * @param daprApiToken      Dapr API Token.
   * @see DaprClientBuilder
   */
  DaprClientImpl(
      GrpcChannelFacade channel,
      DaprGrpc.DaprStub asyncStub,
      DaprHttp httpClient,
      DaprObjectSerializer objectSerializer,
      DaprObjectSerializer stateSerializer,
      String daprApiToken) {
    this(channel, asyncStub, httpClient, objectSerializer, stateSerializer, null, daprApiToken);
  }

  /**
   * Default access level constructor, in order to create an instance of this class use io.dapr.client.DaprClientBuilder
   *
   * @param channel           Facade for the managed GRPC channel
   * @param asyncStub         async gRPC stub
   * @param httpClient        client for http service invocation
   * @param objectSerializer  Serializer for transient request/response objects.
   * @param stateSerializer   Serializer for state objects.
   * @param resiliencyOptions Client-level override for resiliency options.
   * @param daprApiToken      Dapr API Token.
   * @see DaprClientBuilder
   */
  DaprClientImpl(
      GrpcChannelFacade channel,
      DaprGrpc.DaprStub asyncStub,
      DaprHttp httpClient,
      DaprObjectSerializer objectSerializer,
      DaprObjectSerializer stateSerializer,
      ResiliencyOptions resiliencyOptions,
      String daprApiToken) {
    this(
        channel,
        asyncStub,
        httpClient,
        objectSerializer,
        stateSerializer,
        new TimeoutPolicy(resiliencyOptions == null ? null : resiliencyOptions.getTimeout()),
        new RetryPolicy(resiliencyOptions == null ? null : resiliencyOptions.getMaxRetries()),
        daprApiToken);
  }

  /**
   * Instantiates a new DaprClient.
   *
   * @param channel           Facade for the managed GRPC channel
   * @param asyncStub         async gRPC stub
   * @param httpClient        client for http service invocation
   * @param objectSerializer  Serializer for transient request/response objects.
   * @param stateSerializer   Serializer for state objects.
   * @param timeoutPolicy     Client-level timeout policy.
   * @param retryPolicy       Client-level retry policy.
   * @param daprApiToken      Dapr API Token.
   * @see DaprClientBuilder
   */
  private DaprClientImpl(
      GrpcChannelFacade channel,
      DaprGrpc.DaprStub asyncStub,
      DaprHttp httpClient,
      DaprObjectSerializer objectSerializer,
      DaprObjectSerializer stateSerializer,
      TimeoutPolicy timeoutPolicy,
      RetryPolicy retryPolicy,
      String daprApiToken) {
    super(objectSerializer, stateSerializer);
    this.channel = channel;
    this.asyncStub = asyncStub;
    this.httpClient = httpClient;
    this.retryPolicy = retryPolicy;
    this.grpcInterceptors = new DaprClientGrpcInterceptors(daprApiToken, timeoutPolicy);
    this.logger = LoggerFactory.getLogger(DaprClientImpl.class);
  }

  private CommonProtos.StateOptions.StateConsistency getGrpcStateConsistency(StateOptions options) {
    switch (options.getConsistency()) {
      case EVENTUAL:
        return CommonProtos.StateOptions.StateConsistency.CONSISTENCY_EVENTUAL;
      case STRONG:
        return CommonProtos.StateOptions.StateConsistency.CONSISTENCY_STRONG;
      default:
        throw new IllegalArgumentException("Missing Consistency mapping to gRPC Consistency enum");
    }
  }

  private CommonProtos.StateOptions.StateConcurrency getGrpcStateConcurrency(StateOptions options) {
    switch (options.getConcurrency()) {
      case FIRST_WRITE:
        return CommonProtos.StateOptions.StateConcurrency.CONCURRENCY_FIRST_WRITE;
      case LAST_WRITE:
        return CommonProtos.StateOptions.StateConcurrency.CONCURRENCY_LAST_WRITE;
      default:
        throw new IllegalArgumentException("Missing StateConcurrency mapping to gRPC Concurrency enum");
    }
  }

  /**
   * {@inheritDoc}
   */
  public <T extends AbstractStub<T>> T newGrpcStub(String appId, Function<Channel, T> stubBuilder) {
    // Adds Dapr interceptors to populate gRPC metadata automatically.
    return this.grpcInterceptors.intercept(appId, stubBuilder.apply(this.channel.getGrpcChannel()));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Mono<Void> waitForSidecar(int timeoutInMilliseconds) {
    String[] pathSegments = new String[] { DaprHttp.API_VERSION, "healthz", "outbound"};

    // Do the Dapr Http endpoint check to have parity with Dotnet
    Mono<DaprHttp.Response> responseMono = this.httpClient.invokeApi(DaprHttp.HttpMethods.GET.name(), pathSegments,
        null, "", null, null);

    return responseMono
        // No method to "retry forever every 500ms", so we make it practically forever.
        // 9223372036854775807 * 500 ms = 1.46235604 x 10^11 years
        // If anyone needs to wait for the sidecar for longer than that, sorry.
        .retryWhen(
            Retry
                .fixedDelay(Long.MAX_VALUE, Duration.ofMillis(500))
                .doBeforeRetry(s -> {
                  this.logger.info("Retrying sidecar health check ...");
                }))
        .timeout(Duration.ofMillis(timeoutInMilliseconds))
        .onErrorResume(DaprException.class, e ->
            Mono.error(new RuntimeException(e)))
        .switchIfEmpty(DaprException.wrapMono(new RuntimeException("Health check timed out")))
        .then();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Mono<Void> publishEvent(PublishEventRequest request) {
    try {
      String pubsubName = request.getPubsubName();
      String topic = request.getTopic();
      Object data = request.getData();
      DaprProtos.PublishEventRequest.Builder envelopeBuilder = DaprProtos.PublishEventRequest.newBuilder()
          .setTopic(topic)
          .setPubsubName(pubsubName)
          .setData(ByteString.copyFrom(objectSerializer.serialize(data)));

      // Content-type can be overwritten on a per-request basis.
      // It allows CloudEvents to be handled differently, for example.
      String contentType = request.getContentType();
      if (contentType == null || contentType.isEmpty()) {
        contentType = objectSerializer.getContentType();
      }
      envelopeBuilder.setDataContentType(contentType);

      Map<String, String> metadata = request.getMetadata();
      if (metadata != null) {
        envelopeBuilder.putAllMetadata(metadata);
      }

      return Mono.deferContextual(
          context ->
              this.<Empty>createMono(
                  it -> intercept(context, asyncStub).publishEvent(envelopeBuilder.build(), it)
              )
      ).then();
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> Mono<BulkPublishResponse<T>> publishEvents(BulkPublishRequest<T> request) {
    try {
      String pubsubName = request.getPubsubName();
      String topic = request.getTopic();
      DaprProtos.BulkPublishRequest.Builder envelopeBuilder = DaprProtos.BulkPublishRequest.newBuilder();
      envelopeBuilder.setTopic(topic);
      envelopeBuilder.setPubsubName(pubsubName);

      if (Strings.isNullOrEmpty(pubsubName) || Strings.isNullOrEmpty(topic)) {
        throw new IllegalArgumentException("pubsubName and topic name cannot be null or empty");
      }

      for (BulkPublishEntry<?> entry : request.getEntries()) {
        Object event = entry.getEvent();
        byte[] data;
        String contentType = entry.getContentType();
        try {
          // Serialize event into bytes
          if (!Strings.isNullOrEmpty(contentType) && objectSerializer instanceof DefaultObjectSerializer) {
            // If content type is given by user and default object serializer is used
            data = DefaultContentTypeConverter.convertEventToBytesForGrpc(event, contentType);
          } else {
            // perform the serialization as per user given input of serializer
            // this is also the case when content type is empty

            data = objectSerializer.serialize(event);

            if (Strings.isNullOrEmpty(contentType)) {
              // Only override content type if not given in input by user
              contentType = objectSerializer.getContentType();
            }
          }
        } catch (IOException ex) {
          throw DaprException.propagate(ex);
        }

        DaprProtos.BulkPublishRequestEntry.Builder reqEntryBuilder = DaprProtos.BulkPublishRequestEntry.newBuilder()
            .setEntryId(entry.getEntryId())
            .setEvent(ByteString.copyFrom(data))
            .setContentType(contentType);
        Map<String, String> metadata = entry.getMetadata();
        if (metadata != null) {
          reqEntryBuilder.putAllMetadata(metadata);
        }
        envelopeBuilder.addEntries(reqEntryBuilder.build());
      }

      // Set metadata if available
      Map<String, String> metadata = request.getMetadata();
      if (metadata != null) {
        envelopeBuilder.putAllMetadata(metadata);
      }

      Map<String, BulkPublishEntry<T>> entryMap = new HashMap<>();
      for (BulkPublishEntry<T> entry : request.getEntries()) {
        entryMap.put(entry.getEntryId(), entry);
      }
      return Mono.deferContextual(
          context ->
              this.<DaprProtos.BulkPublishResponse>createMono(
                  it -> intercept(context, asyncStub).bulkPublishEventAlpha1(envelopeBuilder.build(), it)
              )
      ).map(
          it -> {
            List<BulkPublishResponseFailedEntry<T>> entries = new ArrayList<>();
            for (DaprProtos.BulkPublishResponseFailedEntry entry : it.getFailedEntriesList()) {
              BulkPublishResponseFailedEntry<T> domainEntry = new BulkPublishResponseFailedEntry<T>(
                  entryMap.get(entry.getEntryId()),
                  entry.getError());
              entries.add(domainEntry);
            }
            if (entries.size() > 0) {
              return new BulkPublishResponse<>(entries);
            }
            return new BulkPublishResponse<>();
          }
      );
    } catch (RuntimeException ex) {
      return DaprException.wrapMono(ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> Subscription subscribeToEvents(
      String pubsubName, String topic, SubscriptionListener<T> listener, TypeRef<T> type) {
    DaprProtos.SubscribeTopicEventsRequestInitialAlpha1 initialRequest =
        DaprProtos.SubscribeTopicEventsRequestInitialAlpha1.newBuilder()
            .setTopic(topic)
            .setPubsubName(pubsubName)
            .build();
    DaprProtos.SubscribeTopicEventsRequestAlpha1 request =
        DaprProtos.SubscribeTopicEventsRequestAlpha1.newBuilder()
            .setInitialRequest(initialRequest)
            .build();
    return buildSubscription(listener, type, request);
  }

  @Nonnull
  private <T> Subscription<T> buildSubscription(
      SubscriptionListener<T> listener,
      TypeRef<T> type,
      DaprProtos.SubscribeTopicEventsRequestAlpha1 request) {
    var interceptedStub = this.grpcInterceptors.intercept(this.asyncStub);
    Subscription<T> subscription = new Subscription<>(interceptedStub, request, listener, response -> {
      if (response.getEventMessage() == null) {
        return null;
      }

      var message = response.getEventMessage();
      if ((message.getPubsubName() == null) || message.getPubsubName().isEmpty()) {
        return null;
      }

      try {
        CloudEvent<T> cloudEvent = new CloudEvent<>();
        T object = null;
        if (type != null) {
          object = DaprClientImpl.this.objectSerializer.deserialize(message.getData().toByteArray(), type);
        }
        cloudEvent.setData(object);
        cloudEvent.setDatacontenttype(message.getDataContentType());
        cloudEvent.setId(message.getId());
        cloudEvent.setTopic(message.getTopic());
        cloudEvent.setSpecversion(message.getSpecVersion());
        cloudEvent.setType(message.getType());
        cloudEvent.setPubsubName(message.getPubsubName());
        return cloudEvent;
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    subscription.start();
    return subscription;
  }

  @Override
  public <T> Mono<T> invokeMethod(InvokeMethodRequest invokeMethodRequest, TypeRef<T> type) {
    try {
      final String appId = invokeMethodRequest.getAppId();
      final String method = invokeMethodRequest.getMethod();
      final Object request = invokeMethodRequest.getBody();
      final HttpExtension httpExtension = invokeMethodRequest.getHttpExtension();
      final String contentType = invokeMethodRequest.getContentType();
      final Map<String, String> metadata = invokeMethodRequest.getMetadata();

      if (httpExtension == null) {
        throw new IllegalArgumentException("HttpExtension cannot be null. Use HttpExtension.NONE instead.");
      }
      // If the httpExtension is not null, then the method will not be null based on checks in constructor
      final String httpMethod = httpExtension.getMethod().toString();
      if (appId == null || appId.trim().isEmpty()) {
        throw new IllegalArgumentException("App Id cannot be null or empty.");
      }
      if (method == null || method.trim().isEmpty()) {
        throw new IllegalArgumentException("Method name cannot be null or empty.");
      }


      String[] methodSegments = method.split("/");

      List<String> pathSegments = new ArrayList<>(Arrays.asList(DaprHttp.API_VERSION, "invoke", appId, "method"));
      pathSegments.addAll(Arrays.asList(methodSegments));

      final Map<String, String> headers = new HashMap<>();
      headers.putAll(httpExtension.getHeaders());
      if (metadata != null) {
        headers.putAll(metadata);
      }
      byte[] serializedRequestBody = objectSerializer.serialize(request);
      if (contentType != null && !contentType.isEmpty()) {
        headers.put(io.dapr.client.domain.Metadata.CONTENT_TYPE, contentType);
      } else {
        headers.put(io.dapr.client.domain.Metadata.CONTENT_TYPE, objectSerializer.getContentType());
      }
      Mono<DaprHttp.Response> response = Mono.deferContextual(
          context -> this.httpClient.invokeApi(httpMethod, pathSegments.toArray(new String[0]),
              httpExtension.getQueryParams(), serializedRequestBody, headers, context)
      );
      return response.flatMap(r -> getMonoForHttpResponse(type, r));
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  private <T> Mono<T> getMonoForHttpResponse(TypeRef<T> type, DaprHttp.Response r) {
    try {
      if (type == null) {
        return Mono.empty();
      }

      T object = objectSerializer.deserialize(r.getBody(), type);
      if (object == null) {
        return Mono.empty();
      }

      return Mono.just(object);
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> Mono<T> invokeBinding(InvokeBindingRequest request, TypeRef<T> type) {
    try {
      final String name = request.getName();
      final String operation = request.getOperation();
      final Object data = request.getData();
      final Map<String, String> metadata = request.getMetadata();
      if (name == null || name.trim().isEmpty()) {
        throw new IllegalArgumentException("Binding name cannot be null or empty.");
      }

      if (operation == null || operation.trim().isEmpty()) {
        throw new IllegalArgumentException("Binding operation cannot be null or empty.");
      }

      byte[] byteData = objectSerializer.serialize(data);
      DaprProtos.InvokeBindingRequest.Builder builder = DaprProtos.InvokeBindingRequest.newBuilder()
          .setName(name).setOperation(operation);
      if (byteData != null) {
        builder.setData(ByteString.copyFrom(byteData));
      }
      if (metadata != null) {
        builder.putAllMetadata(metadata);
      }
      DaprProtos.InvokeBindingRequest envelope = builder.build();

      Metadata responseMetadata = new Metadata();
      return Mono.deferContextual(
          context -> this.<DaprProtos.InvokeBindingResponse>createMono(
              responseMetadata,
              it -> intercept(context, asyncStub, m -> responseMetadata.merge(m)).invokeBinding(envelope, it)
          )
      ).flatMap(
          it -> {
            int httpStatusCode =
                parseHttpStatusCode(it.getMetadataMap().getOrDefault("statusCode", ""));
            if (isValidHttpStatusCode(httpStatusCode) && !isSuccessfulHttpStatusCode(httpStatusCode)) {
              // Exception condition in a successful request.
              // This is useful to send an exception due to an error from the HTTP binding component.
              throw DaprException.propagate(new DaprHttpException(httpStatusCode, it.getData().toByteArray()));
            }

            try {
              if (type == null) {
                return Mono.empty();
              }
              return Mono.justOrEmpty(objectSerializer.deserialize(it.getData().toByteArray(), type));
            } catch (IOException e) {
              throw DaprException.propagate(e);
            }
          }
      );
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> Mono<State<T>> getState(GetStateRequest request, TypeRef<T> type) {
    try {
      final String stateStoreName = request.getStoreName();
      final String key = request.getKey();
      final StateOptions options = request.getStateOptions();
      final Map<String, String> metadata = request.getMetadata();

      if ((stateStoreName == null) || (stateStoreName.trim().isEmpty())) {
        throw new IllegalArgumentException("State store name cannot be null or empty.");
      }
      if ((key == null) || (key.trim().isEmpty())) {
        throw new IllegalArgumentException("Key cannot be null or empty.");
      }
      DaprProtos.GetStateRequest.Builder builder = DaprProtos.GetStateRequest.newBuilder()
          .setStoreName(stateStoreName)
          .setKey(key);
      if (metadata != null) {
        builder.putAllMetadata(metadata);
      }
      if (options != null && options.getConsistency() != null) {
        builder.setConsistency(getGrpcStateConsistency(options));
      }

      DaprProtos.GetStateRequest envelope = builder.build();

      return Mono.deferContextual(
          context ->
              this.<DaprProtos.GetStateResponse>createMono(
                  it -> intercept(context, asyncStub).getState(envelope, it)
              )
      ).map(
          it -> {
            try {
              return buildStateKeyValue(it, key, options, type);
            } catch (IOException ex) {
              throw DaprException.propagate(ex);
            }
          }
      );
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> Mono<List<State<T>>> getBulkState(GetBulkStateRequest request, TypeRef<T> type) {
    try {
      final String stateStoreName = request.getStoreName();
      final List<String> keys = request.getKeys();
      final int parallelism = request.getParallelism();
      final Map<String, String> metadata = request.getMetadata();
      if ((stateStoreName == null) || (stateStoreName.trim().isEmpty())) {
        throw new IllegalArgumentException("State store name cannot be null or empty.");
      }
      if (keys == null || keys.isEmpty()) {
        throw new IllegalArgumentException("Key cannot be null or empty.");
      }

      if (parallelism < 0) {
        throw new IllegalArgumentException("Parallelism cannot be negative.");
      }
      DaprProtos.GetBulkStateRequest.Builder builder = DaprProtos.GetBulkStateRequest.newBuilder()
          .setStoreName(stateStoreName)
          .addAllKeys(keys)
          .setParallelism(parallelism);
      if (metadata != null) {
        builder.putAllMetadata(metadata);
      }

      DaprProtos.GetBulkStateRequest envelope = builder.build();

      return Mono.deferContextual(
          context -> this.<DaprProtos.GetBulkStateResponse>createMono(it -> intercept(context, asyncStub)
              .getBulkState(envelope, it)
          )
      ).map(
          it ->
              it
                  .getItemsList()
                  .stream()
                  .map(b -> {
                    try {
                      return buildStateKeyValue(b, type);
                    } catch (Exception e) {
                      throw DaprException.propagate(e);
                    }
                  })
                  .collect(Collectors.toList())
      );
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  private <T> State<T> buildStateKeyValue(
      DaprProtos.BulkStateItem item,
      TypeRef<T> type) throws IOException {
    String key = item.getKey();
    String error = item.getError();
    if (!Strings.isNullOrEmpty(error)) {
      return new State<>(key, error);
    }

    String etag = item.getEtag();
    if (etag.equals("")) {
      etag = null;
    }

    T value = null;
    if (type != null) {
      ByteString payload = item.getData();
      byte[] data = payload == null ? null : payload.toByteArray();
      value = stateSerializer.deserialize(data, type);
    }

    return new State<>(key, value, etag, item.getMetadataMap(), null);
  }

  private <T> State<T> buildStateKeyValue(
      DaprProtos.GetStateResponse response,
      String requestedKey,
      StateOptions stateOptions,
      TypeRef<T> type) throws IOException {
    ByteString payload = response.getData();
    byte[] data = payload == null ? null : payload.toByteArray();
    T value = null;
    if (type != null) {
      value = stateSerializer.deserialize(data, type);
    }

    String etag = response.getEtag();
    if (etag.equals("")) {
      etag = null;
    }
    return new State<>(requestedKey, value, etag, response.getMetadataMap(), stateOptions);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Mono<Void> executeStateTransaction(ExecuteStateTransactionRequest request) {
    try {
      final String stateStoreName = request.getStateStoreName();
      final List<TransactionalStateOperation<?>> operations = request.getOperations();
      final Map<String, String> metadata = request.getMetadata();
      if ((stateStoreName == null) || (stateStoreName.trim().isEmpty())) {
        throw new IllegalArgumentException("State store name cannot be null or empty.");
      }
      DaprProtos.ExecuteStateTransactionRequest.Builder builder = DaprProtos.ExecuteStateTransactionRequest
          .newBuilder();
      builder.setStoreName(stateStoreName);
      if (metadata != null) {
        builder.putAllMetadata(metadata);
      }
      for (TransactionalStateOperation<?> operation : operations) {
        DaprProtos.TransactionalStateOperation.Builder operationBuilder = DaprProtos.TransactionalStateOperation
            .newBuilder();
        operationBuilder.setOperationType(operation.getOperation().toString().toLowerCase());
        operationBuilder.setRequest(buildStateRequest(operation.getRequest()).build());
        builder.addOperations(operationBuilder.build());
      }
      DaprProtos.ExecuteStateTransactionRequest req = builder.build();

      return Mono.deferContextual(
          context -> this.<Empty>createMono(it -> intercept(context, asyncStub).executeStateTransaction(req, it))
      ).then();
    } catch (Exception e) {
      return DaprException.wrapMono(e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Mono<Void> saveBulkState(SaveStateRequest request) {
    try {
      final String stateStoreName = request.getStoreName();
      final List<State<?>> states = request.getStates();
      if ((stateStoreName == null) || (stateStoreName.trim().isEmpty())) {
        throw new IllegalArgumentException("State store name cannot be null or empty.");
      }
      DaprProtos.SaveStateRequest.Builder builder = DaprProtos.SaveStateRequest.newBuilder();
      builder.setStoreName(stateStoreName);
      for (State<?> state : states) {
        builder.addStates(buildStateRequest(state).build());
      }
      DaprProtos.SaveStateRequest req = builder.build();

      return Mono.deferContextual(
          context -> this.<Empty>createMono(it -> intercept(context, asyncStub).saveState(req, it))
      ).then();
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  private <T> CommonProtos.StateItem.Builder buildStateRequest(State<T> state) throws IOException {
    byte[] bytes = stateSerializer.serialize(state.getValue());

    CommonProtos.StateItem.Builder stateBuilder = CommonProtos.StateItem.newBuilder();
    if (state.getEtag() != null) {
      stateBuilder.setEtag(CommonProtos.Etag.newBuilder().setValue(state.getEtag()).build());
    }
    if (state.getMetadata() != null) {
      stateBuilder.putAllMetadata(state.getMetadata());
    }
    if (bytes != null) {
      stateBuilder.setValue(ByteString.copyFrom(bytes));
    }
    stateBuilder.setKey(state.getKey());
    CommonProtos.StateOptions.Builder optionBuilder = null;
    if (state.getOptions() != null) {
      StateOptions options = state.getOptions();
      optionBuilder = CommonProtos.StateOptions.newBuilder();
      if (options.getConcurrency() != null) {
        optionBuilder.setConcurrency(getGrpcStateConcurrency(options));
      }
      if (options.getConsistency() != null) {
        optionBuilder.setConsistency(getGrpcStateConsistency(options));
      }
    }
    if (optionBuilder != null) {
      stateBuilder.setOptions(optionBuilder.build());
    }
    return stateBuilder;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Mono<Void> deleteState(DeleteStateRequest request) {
    try {
      final String stateStoreName = request.getStateStoreName();
      final String key = request.getKey();
      final StateOptions options = request.getStateOptions();
      final String etag = request.getEtag();
      final Map<String, String> metadata = request.getMetadata();

      if ((stateStoreName == null) || (stateStoreName.trim().isEmpty())) {
        throw new IllegalArgumentException("State store name cannot be null or empty.");
      }
      if ((key == null) || (key.trim().isEmpty())) {
        throw new IllegalArgumentException("Key cannot be null or empty.");
      }

      CommonProtos.StateOptions.Builder optionBuilder = null;
      if (options != null) {
        optionBuilder = CommonProtos.StateOptions.newBuilder();
        if (options.getConcurrency() != null) {
          optionBuilder.setConcurrency(getGrpcStateConcurrency(options));
        }
        if (options.getConsistency() != null) {
          optionBuilder.setConsistency(getGrpcStateConsistency(options));
        }
      }
      DaprProtos.DeleteStateRequest.Builder builder = DaprProtos.DeleteStateRequest.newBuilder()
          .setStoreName(stateStoreName)
          .setKey(key);
      if (metadata != null) {
        builder.putAllMetadata(metadata);
      }
      if (etag != null) {
        builder.setEtag(CommonProtos.Etag.newBuilder().setValue(etag).build());
      }

      if (optionBuilder != null) {
        builder.setOptions(optionBuilder.build());
      }

      DaprProtos.DeleteStateRequest req = builder.build();

      return Mono.deferContextual(
          context -> this.<Empty>createMono(it -> intercept(context, asyncStub).deleteState(req, it))
      ).then();
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Mono<Map<String, String>> getSecret(GetSecretRequest request) {
    String secretStoreName = request.getStoreName();
    String key = request.getKey();
    Map<String, String> metadata = request.getMetadata();
    try {
      if ((secretStoreName == null) || (secretStoreName.trim().isEmpty())) {
        throw new IllegalArgumentException("Secret store name cannot be null or empty.");
      }
      if ((key == null) || (key.trim().isEmpty())) {
        throw new IllegalArgumentException("Secret key cannot be null or empty.");
      }
    } catch (Exception e) {
      return DaprException.wrapMono(e);
    }

    DaprProtos.GetSecretRequest.Builder requestBuilder = DaprProtos.GetSecretRequest.newBuilder()
        .setStoreName(secretStoreName)
        .setKey(key);

    if (metadata != null) {
      requestBuilder.putAllMetadata(metadata);
    }
    DaprProtos.GetSecretRequest req = requestBuilder.build();

    return Mono.deferContextual(
        context -> this.<DaprProtos.GetSecretResponse>createMono(it -> intercept(context, asyncStub).getSecret(req, it))
    ).map(DaprProtos.GetSecretResponse::getDataMap);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Mono<Map<String, Map<String, String>>> getBulkSecret(GetBulkSecretRequest request) {
    try {
      final String storeName = request.getStoreName();
      final Map<String, String> metadata = request.getMetadata();
      if ((storeName == null) || (storeName.trim().isEmpty())) {
        throw new IllegalArgumentException("Secret store name cannot be null or empty.");
      }

      DaprProtos.GetBulkSecretRequest.Builder builder = DaprProtos.GetBulkSecretRequest.newBuilder()
          .setStoreName(storeName);
      if (metadata != null) {
        builder.putAllMetadata(metadata);
      }

      DaprProtos.GetBulkSecretRequest envelope = builder.build();

      return Mono.deferContextual(
          context ->
              this.<DaprProtos.GetBulkSecretResponse>createMono(
                  it -> intercept(context, asyncStub).getBulkSecret(envelope, it)
              )
      ).map(it -> {
        Map<String, DaprProtos.SecretResponse> secretsMap = it.getDataMap();
        if (secretsMap == null) {
          return Collections.emptyMap();
        }
        return secretsMap
            .entrySet()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, s -> s.getValue().getSecretsMap()));
      });
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Mono<Boolean> tryLock(LockRequest request) {
    try {
      final String stateStoreName = request.getStoreName();
      final String resourceId = request.getResourceId();
      final String lockOwner = request.getLockOwner();
      final Integer expiryInSeconds = request.getExpiryInSeconds();

      if ((stateStoreName == null) || (stateStoreName.trim().isEmpty())) {
        throw new IllegalArgumentException("State store name cannot be null or empty.");
      }
      if (resourceId == null || resourceId.isEmpty()) {
        throw new IllegalArgumentException("ResourceId cannot be null or empty.");
      }
      if (lockOwner == null || lockOwner.isEmpty()) {
        throw new IllegalArgumentException("LockOwner cannot be null or empty.");
      }
      if (expiryInSeconds < 0) {
        throw new IllegalArgumentException("ExpiryInSeconds cannot be negative.");
      }

      DaprProtos.TryLockRequest.Builder builder = DaprProtos.TryLockRequest.newBuilder()
              .setStoreName(stateStoreName)
              .setResourceId(resourceId)
              .setLockOwner(lockOwner)
              .setExpiryInSeconds(expiryInSeconds);

      DaprProtos.TryLockRequest tryLockRequest = builder.build();

      return Mono.deferContextual(
              context -> this.<DaprProtos.TryLockResponse>createMono(
                      it -> intercept(context, asyncStub).tryLockAlpha1(tryLockRequest, it)
              )
      ).flatMap(response -> {
        try {
          return Mono.just(response.getSuccess());
        } catch (Exception ex) {
          return DaprException.wrapMono(ex);
        }
      });
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Mono<UnlockResponseStatus> unlock(UnlockRequest request) {
    try {
      final String stateStoreName = request.getStoreName();
      final String resourceId = request.getResourceId();
      final String lockOwner = request.getLockOwner();

      if ((stateStoreName == null) || (stateStoreName.trim().isEmpty())) {
        throw new IllegalArgumentException("State store name cannot be null or empty.");
      }
      if (resourceId == null || resourceId.isEmpty()) {
        throw new IllegalArgumentException("ResourceId cannot be null or empty.");
      }
      if (lockOwner == null || lockOwner.isEmpty()) {
        throw new IllegalArgumentException("LockOwner cannot be null or empty.");
      }

      DaprProtos.UnlockRequest.Builder builder = DaprProtos.UnlockRequest.newBuilder()
              .setStoreName(stateStoreName)
              .setResourceId(resourceId)
              .setLockOwner(lockOwner);

      DaprProtos.UnlockRequest unlockRequest = builder.build();

      return Mono.deferContextual(
              context -> this.<DaprProtos.UnlockResponse>createMono(
                      it -> intercept(context, asyncStub).unlockAlpha1(unlockRequest, it)
              )
      ).flatMap(response -> {
        try {
          return Mono.just(UnlockResponseStatus.valueOf(response.getStatus().getNumber()));
        } catch (Exception ex) {
          return DaprException.wrapMono(ex);
        }
      });
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> Mono<QueryStateResponse<T>> queryState(QueryStateRequest request, TypeRef<T> type) {
    try {
      if (request == null) {
        throw new IllegalArgumentException("Query state request cannot be null.");
      }
      final String storeName = request.getStoreName();
      final Map<String, String> metadata = request.getMetadata();
      if ((storeName == null) || (storeName.trim().isEmpty())) {
        throw new IllegalArgumentException("State store name cannot be null or empty.");
      }

      String queryString;
      if (request.getQuery() != null) {
        queryString = JSON_REQUEST_MAPPER.writeValueAsString(request.getQuery());
      } else if (request.getQueryString() != null) {
        queryString = request.getQueryString();
      } else {
        throw new IllegalArgumentException("Both query and queryString fields are not set.");
      }

      DaprProtos.QueryStateRequest.Builder builder = DaprProtos.QueryStateRequest.newBuilder()
          .setStoreName(storeName)
          .setQuery(queryString);
      if (metadata != null) {
        builder.putAllMetadata(metadata);
      }

      DaprProtos.QueryStateRequest envelope = builder.build();

      return Mono.deferContextual(
          context -> this.<DaprProtos.QueryStateResponse>createMono(
              it -> intercept(context, asyncStub).queryStateAlpha1(envelope, it)
          )
      ).map(
          it -> {
            Map<String, String> resultMeta = it.getMetadataMap();
            String token = it.getToken();
            List<QueryStateItem<T>> res = it.getResultsList()
                .stream()
                .map(v -> {
                  try {
                    return buildQueryStateKeyValue(v, type);
                  } catch (Exception e) {
                    throw DaprException.propagate(e);
                  }
                })
                .collect(Collectors.toList());
            return new QueryStateResponse<>(res, token).setMetadata(metadata);
          });
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  private <T> QueryStateItem<T> buildQueryStateKeyValue(
      DaprProtos.QueryStateItem item,
      TypeRef<T> type) throws IOException {
    String key = item.getKey();
    String error = item.getError();
    if (!Strings.isNullOrEmpty(error)) {
      return new QueryStateItem<>(key, null, error);
    }
    ByteString payload = item.getData();
    byte[] data = payload == null ? null : payload.toByteArray();
    T value = null;
    if (type != null) {
      value = stateSerializer.deserialize(data, type);
    }

    String etag = item.getEtag();
    if (etag.equals("")) {
      etag = null;
    }
    return new QueryStateItem<>(key, value, etag);
  }

  /**
   * Closes the ManagedChannel for GRPC.
   *
   * @throws IOException on exception.
   * @see io.grpc.ManagedChannel#shutdown()
   */
  @Override
  public void close() throws Exception {
    DaprException.wrap(() -> {
      if (channel != null) {
        channel.close();
      }
      if (httpClient != null) {
        httpClient.close();
      }
      return true;
    }).call();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Mono<Void> shutdown() {
    DaprProtos.ShutdownRequest shutdownRequest = DaprProtos.ShutdownRequest.newBuilder().build();
    return Mono.deferContextual(
        context -> this.<Empty>createMono(
            it -> intercept(context, asyncStub).shutdown(shutdownRequest, it))
    ).then();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Mono<Map<String, ConfigurationItem>> getConfiguration(GetConfigurationRequest request) {
    try {
      final String configurationStoreName = request.getStoreName();
      final Map<String, String> metadata = request.getMetadata();
      final List<String> keys = request.getKeys();
      if ((configurationStoreName == null) || (configurationStoreName.trim().isEmpty())) {
        throw new IllegalArgumentException("Configuration Store Name cannot be null or empty.");
      }

      DaprProtos.GetConfigurationRequest.Builder builder = DaprProtos.GetConfigurationRequest.newBuilder()
          .setStoreName(configurationStoreName).addAllKeys(keys);
      if (metadata != null) {
        builder.putAllMetadata(metadata);
      }

      DaprProtos.GetConfigurationRequest envelope = builder.build();
      return this.getConfiguration(envelope);

    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  private Mono<Map<String, ConfigurationItem>> getConfiguration(DaprProtos.GetConfigurationRequest envelope) {
    return Mono.deferContextual(
        context ->
            this.<DaprProtos.GetConfigurationResponse>createMono(
                it -> intercept(context, asyncStub).getConfiguration(envelope, it)
            )
    ).map(
        it -> {
          Map<String, ConfigurationItem> configMap = new HashMap<>();
          Iterator<Map.Entry<String, CommonProtos.ConfigurationItem>> itr = it.getItems().entrySet().iterator();
          while (itr.hasNext()) {
            Map.Entry<String, CommonProtos.ConfigurationItem> entry = itr.next();
            configMap.put(entry.getKey(), buildConfigurationItem(entry.getValue(), entry.getKey()));
          }
          return Collections.unmodifiableMap(configMap);
        }
    );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Flux<SubscribeConfigurationResponse> subscribeConfiguration(SubscribeConfigurationRequest request) {
    try {
      final String configurationStoreName = request.getStoreName();
      final List<String> keys = request.getKeys();
      final Map<String, String> metadata = request.getMetadata();

      if (configurationStoreName == null || (configurationStoreName.trim().isEmpty())) {
        throw new IllegalArgumentException("Configuration Store Name can not be null or empty.");
      }

      // keys can and empty list for subscribe all scenario, so we do not need check for empty keys.
      DaprProtos.SubscribeConfigurationRequest.Builder builder = DaprProtos.SubscribeConfigurationRequest.newBuilder()
          .setStoreName(configurationStoreName)
          .addAllKeys(keys);

      if (metadata != null) {
        builder.putAllMetadata(metadata);
      }

      DaprProtos.SubscribeConfigurationRequest envelope = builder.build();
      return this.<DaprProtos.SubscribeConfigurationResponse>createFlux(
          it -> intercept(null, asyncStub).subscribeConfiguration(envelope, it)
      ).map(
          it -> {
            Map<String, ConfigurationItem> configMap = new HashMap<>();
            Iterator<Map.Entry<String, CommonProtos.ConfigurationItem>> itr = it.getItemsMap().entrySet().iterator();
            while (itr.hasNext()) {
              Map.Entry<String, CommonProtos.ConfigurationItem> entry = itr.next();
              configMap.put(entry.getKey(), buildConfigurationItem(entry.getValue(), entry.getKey()));
            }
            return new SubscribeConfigurationResponse(it.getId(), Collections.unmodifiableMap(configMap));
          }
      );
    } catch (Exception ex) {
      return DaprException.wrapFlux(ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Mono<UnsubscribeConfigurationResponse> unsubscribeConfiguration(UnsubscribeConfigurationRequest request) {
    try {
      final String configurationStoreName = request.getStoreName();
      final String id = request.getSubscriptionId();

      if (configurationStoreName == null || (configurationStoreName.trim().isEmpty())) {
        throw new IllegalArgumentException("Configuration Store Name can not be null or empty.");
      }
      if (id.isEmpty()) {
        throw new IllegalArgumentException("Subscription id can not be null or empty.");
      }
      DaprProtos.UnsubscribeConfigurationRequest.Builder builder =
          DaprProtos.UnsubscribeConfigurationRequest.newBuilder()
              .setId(id)
              .setStoreName(configurationStoreName);

      DaprProtos.UnsubscribeConfigurationRequest envelope = builder.build();

      return this.<DaprProtos.UnsubscribeConfigurationResponse>createMono(
          it -> intercept(null, asyncStub).unsubscribeConfiguration(envelope, it)
      ).map(
          it -> new UnsubscribeConfigurationResponse(it.getOk(), it.getMessage())
      );
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  public Mono<Void> scheduleJob(ScheduleJobRequest scheduleJobRequest) {
    try {
      validateScheduleJobRequest(scheduleJobRequest);

      DaprProtos.Job.Builder scheduleJobRequestBuilder = DaprProtos.Job.newBuilder();
      scheduleJobRequestBuilder.setName(scheduleJobRequest.getName());

      DateTimeFormatter iso8601Formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
              .withZone(ZoneOffset.UTC);

      if (scheduleJobRequest.getData() != null) {
        scheduleJobRequestBuilder.setData(Any.newBuilder()
            .setValue(ByteString.copyFrom(scheduleJobRequest.getData())).build());
      }

      if (scheduleJobRequest.getSchedule() != null) {
        scheduleJobRequestBuilder.setSchedule(scheduleJobRequest.getSchedule().getExpression());
      }

      if (scheduleJobRequest.getTtl() != null) {
        scheduleJobRequestBuilder.setTtl(iso8601Formatter.format(scheduleJobRequest.getTtl()));
      }

      if (scheduleJobRequest.getRepeats() != null) {
        scheduleJobRequestBuilder.setRepeats(scheduleJobRequest.getRepeats());
      }

      if (scheduleJobRequest.getDueTime() != null) {
        scheduleJobRequestBuilder.setDueTime(iso8601Formatter.format(scheduleJobRequest.getDueTime()));
      }

      Mono<DaprProtos.ScheduleJobResponse> scheduleJobResponseMono =
          Mono.deferContextual(context -> this.createMono(
                  it -> intercept(context, asyncStub)
                      .scheduleJobAlpha1(DaprProtos.ScheduleJobRequest.newBuilder()
                          .setJob(scheduleJobRequestBuilder.build()).build(), it)
              )
          );

      return scheduleJobResponseMono.then();
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  public Mono<GetJobResponse> getJob(GetJobRequest getJobRequest) {
    try {
      validateGetJobRequest(getJobRequest);

      Mono<DaprProtos.GetJobResponse> getJobResponseMono =
          Mono.deferContextual(context -> this.createMono(
                  it -> intercept(context, asyncStub)
                      .getJobAlpha1(DaprProtos.GetJobRequest.newBuilder()
                          .setName(getJobRequest.getName()).build(), it)
              )
          );

      return getJobResponseMono.map(response -> {
        DaprProtos.Job job = response.getJob();
        GetJobResponse getJobResponse = null;

        if (job.hasSchedule() && job.hasDueTime()) {
          getJobResponse = new GetJobResponse(job.getName(), JobSchedule.fromString(job.getSchedule()));
          getJobResponse.setDueTime(Instant.parse(job.getDueTime()));
        } else if (job.hasSchedule()) {
          getJobResponse = new GetJobResponse(job.getName(), JobSchedule.fromString(job.getSchedule()));
        } else {
          getJobResponse = new GetJobResponse(job.getName(), Instant.parse(job.getDueTime()));
        }

        return getJobResponse
            .setTtl(job.hasTtl() ? Instant.parse(job.getTtl()) : null)
            .setData(job.hasData() ? job.getData().getValue().toByteArray() : null)
            .setRepeat(job.hasRepeats() ? job.getRepeats() : null);
      });
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  /**
   * {@inheritDoc}
   */
  public Mono<Void> deleteJob(DeleteJobRequest deleteJobRequest) {
    try {
      validateDeleteJobRequest(deleteJobRequest);

      Mono<DaprProtos.DeleteJobResponse> deleteJobResponseMono =
          Mono.deferContextual(context -> this.createMono(
                  it -> intercept(context, asyncStub)
                      .deleteJobAlpha1(DaprProtos.DeleteJobRequest.newBuilder()
                          .setName(deleteJobRequest.getName()).build(), it)
              )
          );

      return deleteJobResponseMono.then();
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  private void validateScheduleJobRequest(ScheduleJobRequest scheduleJobRequest) {
    if (scheduleJobRequest == null) {
      throw new IllegalArgumentException("scheduleJobRequest cannot be null");
    }

    if (scheduleJobRequest.getName() == null || scheduleJobRequest.getName().isEmpty()) {
      throw new IllegalArgumentException("Name in the request cannot be null or empty");
    }

    if (scheduleJobRequest.getSchedule() == null && scheduleJobRequest.getDueTime() == null) {
      throw new IllegalArgumentException("At least one of schedule or dueTime must be provided");
    }
  }

  private void validateGetJobRequest(GetJobRequest getJobRequest) {
    if (getJobRequest == null) {
      throw new IllegalArgumentException("getJobRequest cannot be null");
    }

    if (getJobRequest.getName() == null || getJobRequest.getName().isEmpty()) {
      throw new IllegalArgumentException("Name in the request cannot be null or empty");
    }
  }

  private void validateDeleteJobRequest(DeleteJobRequest deleteJobRequest) {
    if (deleteJobRequest == null) {
      throw new IllegalArgumentException("deleteJobRequest cannot be null");
    }

    if (deleteJobRequest.getName() == null || deleteJobRequest.getName().isEmpty()) {
      throw new IllegalArgumentException("Name in the request cannot be null or empty");
    }
  }

  /**
   * Build a new Configuration Item from provided parameter.
   *
   * @param configurationItem CommonProtos.ConfigurationItem
   * @return io.dapr.client.domain.ConfigurationItem
   */
  private ConfigurationItem buildConfigurationItem(
      CommonProtos.ConfigurationItem configurationItem, String key) {
    return new ConfigurationItem(
        key,
        configurationItem.getValue(),
        configurationItem.getVersion(),
        configurationItem.getMetadataMap());
  }

  /**
   * Populates GRPC client with interceptors for telemetry.
   *
   * @param context Reactor's context.
   * @param client  GRPC client for Dapr.
   * @return Client after adding interceptors.
   */
  private DaprGrpc.DaprStub intercept(ContextView context, DaprGrpc.DaprStub client) {
    return this.grpcInterceptors.intercept(client, context);
  }

  /**
   * Populates GRPC client with interceptors for telemetry.
   *
   * @param context Reactor's context.
   * @param client  GRPC client for Dapr.
   * @param metadataConsumer Consumer of gRPC metadata.
   * @return Client after adding interceptors.
   */
  private DaprGrpc.DaprStub intercept(
      ContextView context, DaprGrpc.DaprStub client, Consumer<Metadata> metadataConsumer) {
    return this.grpcInterceptors.intercept(client, context, metadataConsumer);
  }

  private <T> Mono<T> createMono(Consumer<StreamObserver<T>> consumer) {
    return this.createMono(null, consumer);
  }

  private <T> Mono<T> createMono(Metadata metadata, Consumer<StreamObserver<T>> consumer) {
    return retryPolicy.apply(
        Mono.create(sink -> DaprException.wrap(() -> consumer.accept(
            createStreamObserver(sink, metadata))).run()));
  }

  private <T> Flux<T> createFlux(Consumer<StreamObserver<T>> consumer) {
    return this.createFlux(null, consumer);
  }

  private <T> Flux<T> createFlux(Metadata metadata, Consumer<StreamObserver<T>> consumer) {
    return retryPolicy.apply(
        Flux.create(sink -> DaprException.wrap(() -> consumer.accept(createStreamObserver(sink, metadata))).run()));
  }

  private <T> StreamObserver<T> createStreamObserver(MonoSink<T> sink, Metadata grpcMetadata) {
    return new StreamObserver<T>() {
      @Override
      public void onNext(T value) {
        sink.success(value);
      }

      @Override
      public void onError(Throwable t) {
        sink.error(DaprException.propagate(DaprHttpException.fromGrpcExecutionException(grpcMetadata, t)));
      }

      @Override
      public void onCompleted() {
        sink.success();
      }
    };
  }

  private <T> StreamObserver<T> createStreamObserver(FluxSink<T> sink, final Metadata grpcMetadata) {
    return new StreamObserver<T>() {
      @Override
      public void onNext(T value) {
        sink.next(value);
      }

      @Override
      public void onError(Throwable t) {
        sink.error(DaprException.propagate(DaprHttpException.fromGrpcExecutionException(grpcMetadata, t)));
      }

      @Override
      public void onCompleted() {
        sink.complete();
      }
    };
  }

  @Override
  public Mono<DaprMetadata> getMetadata() {
    DaprProtos.GetMetadataRequest metadataRequest = DaprProtos.GetMetadataRequest.newBuilder().build();
    return Mono.deferContextual(
        context -> this.<DaprProtos.GetMetadataResponse>createMono(
            it -> intercept(context, asyncStub).getMetadata(metadataRequest, it)))
        .map(
            it -> {
              try {
                return buildDaprMetadata(it);
              } catch (IOException ex) {
                throw DaprException.propagate(ex);
              }
            });
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Mono<ConversationResponse> converse(ConversationRequest conversationRequest) {

    try {
      validateConversationRequest(conversationRequest);

      DaprProtos.ConversationRequest.Builder protosConversationRequestBuilder = DaprProtos.ConversationRequest
          .newBuilder().setTemperature(conversationRequest.getTemperature())
          .setScrubPII(conversationRequest.isScrubPii())
          .setName(conversationRequest.getName());

      if (conversationRequest.getContextId() != null) {
        protosConversationRequestBuilder.setContextID(conversationRequest.getContextId());
      }

      for (ConversationInput input : conversationRequest.getInputs()) {
        if (input.getContent() == null || input.getContent().isEmpty()) {
          throw new IllegalArgumentException("Conversation input content cannot be null or empty.");
        }

        DaprProtos.ConversationInput.Builder conversationInputOrBuilder = DaprProtos.ConversationInput.newBuilder()
            .setContent(input.getContent())
            .setScrubPII(input.isScrubPii());

        if (input.getRole() != null) {
          conversationInputOrBuilder.setRole(input.getRole().toString());
        }

        protosConversationRequestBuilder.addInputs(conversationInputOrBuilder.build());
      }

      Mono<DaprProtos.ConversationResponse> conversationResponseMono = Mono.deferContextual(
          context -> this.createMono(
              it -> intercept(context, asyncStub)
                  .converseAlpha1(protosConversationRequestBuilder.build(), it)
          )
      );

      return conversationResponseMono.map(conversationResponse -> {

        List<ConversationOutput> conversationOutputs = new ArrayList<>();
        for (DaprProtos.ConversationResult conversationResult : conversationResponse.getOutputsList()) {
          Map<String, byte[]> parameters = new HashMap<>();
          for (Map.Entry<String, Any> entrySet : conversationResult.getParametersMap().entrySet()) {
            parameters.put(entrySet.getKey(), entrySet.getValue().toByteArray());
          }

          ConversationOutput conversationOutput =
              new ConversationOutput(conversationResult.getResult(), parameters);
          conversationOutputs.add(conversationOutput);
        }

        return new ConversationResponse(conversationResponse.getContextID(), conversationOutputs);
      });
    } catch (Exception ex) {
      return DaprException.wrapMono(ex);
    }
  }

  private void validateConversationRequest(ConversationRequest conversationRequest) {
    if ((conversationRequest.getName() == null) || (conversationRequest.getName().trim().isEmpty())) {
      throw new IllegalArgumentException("LLM name cannot be null or empty.");
    }

    if ((conversationRequest.getInputs() == null) || (conversationRequest
        .getInputs().isEmpty())) {
      throw new IllegalArgumentException("Conversation inputs cannot be null or empty.");
    }
  }

  private DaprMetadata buildDaprMetadata(DaprProtos.GetMetadataResponse response) throws IOException {
    String id = response.getId();
    String runtimeVersion = response.getRuntimeVersion();
    List<String> enabledFeatures = response.getEnabledFeaturesList();
    List<ActorMetadata> actors = getActors(response);
    Map<String, String> attributes = response.getExtendedMetadataMap();
    List<ComponentMetadata> components = getComponents(response);
    List<HttpEndpointMetadata> httpEndpoints = getHttpEndpoints(response);
    List<SubscriptionMetadata> subscriptions = getSubscriptions(response);
    AppConnectionPropertiesMetadata appConnectionProperties = getAppConnectionProperties(response);

    return new DaprMetadata(id, runtimeVersion, enabledFeatures, actors, attributes, components, httpEndpoints,
      subscriptions, appConnectionProperties);
  }

  private List<ActorMetadata> getActors(DaprProtos.GetMetadataResponse response) {
    ActorRuntime actorRuntime = response.getActorRuntime();
    List<ActiveActorsCount> activeActorsList = actorRuntime.getActiveActorsList();

    List<ActorMetadata> actors = new ArrayList<>();
    for (ActiveActorsCount aac : activeActorsList) {
      actors.add(new ActorMetadata(aac.getType(), aac.getCount()));
    }

    return actors;
  }

  private List<ComponentMetadata> getComponents(DaprProtos.GetMetadataResponse response) {
    List<RegisteredComponents> registeredComponentsList = response.getRegisteredComponentsList();

    List<ComponentMetadata> components = new ArrayList<>();
    for (RegisteredComponents rc : registeredComponentsList) {
      components.add(new ComponentMetadata(rc.getName(), rc.getType(), rc.getVersion(), rc.getCapabilitiesList()));
    }

    return components;
  }

  private List<SubscriptionMetadata> getSubscriptions(DaprProtos.GetMetadataResponse response) {
    List<PubsubSubscription> subscriptionsList = response.getSubscriptionsList();

    List<SubscriptionMetadata> subscriptions = new ArrayList<>();
    for (PubsubSubscription s : subscriptionsList) {
      List<PubsubSubscriptionRule> rulesList = s.getRules().getRulesList();
      List<RuleMetadata> rules = new ArrayList<>();
      for (PubsubSubscriptionRule r : rulesList) {
        rules.add(new RuleMetadata(r.getMatch(), r.getPath()));
      }
      subscriptions.add(new SubscriptionMetadata(s.getPubsubName(), s.getTopic(), s.getMetadataMap(), rules,
          s.getDeadLetterTopic()));
    }

    return subscriptions;
  }

  private List<HttpEndpointMetadata> getHttpEndpoints(DaprProtos.GetMetadataResponse response) {
    List<MetadataHTTPEndpoint> httpEndpointsList = response.getHttpEndpointsList();

    List<HttpEndpointMetadata> httpEndpoints = new ArrayList<>();
    for (MetadataHTTPEndpoint m : httpEndpointsList) {
      httpEndpoints.add(new HttpEndpointMetadata(m.getName()));
    }

    return httpEndpoints;
  }

  private AppConnectionPropertiesMetadata getAppConnectionProperties(DaprProtos.GetMetadataResponse response) {
    AppConnectionProperties appConnectionProperties = response.getAppConnectionProperties();
    int port = appConnectionProperties.getPort();
    String protocol = appConnectionProperties.getProtocol();
    String channelAddress = appConnectionProperties.getChannelAddress();
    int maxConcurrency = appConnectionProperties.getMaxConcurrency();
    AppConnectionPropertiesHealthMetadata health = getAppConnectionPropertiesHealth(appConnectionProperties);

    return new AppConnectionPropertiesMetadata(port, protocol, channelAddress, maxConcurrency, health);
  }

  private AppConnectionPropertiesHealthMetadata getAppConnectionPropertiesHealth(
      AppConnectionProperties appConnectionProperties) {
    if (!appConnectionProperties.hasHealth()) {
      return null;
    }

    AppConnectionHealthProperties health = appConnectionProperties.getHealth();
    String healthCheckPath = health.getHealthCheckPath();
    String healthProbeInterval = health.getHealthProbeInterval();
    String healthProbeTimeout = health.getHealthProbeTimeout();
    int healthThreshold = health.getHealthThreshold();

    return new AppConnectionPropertiesHealthMetadata(healthCheckPath, healthProbeInterval, healthProbeTimeout,
        healthThreshold);
  }
}
