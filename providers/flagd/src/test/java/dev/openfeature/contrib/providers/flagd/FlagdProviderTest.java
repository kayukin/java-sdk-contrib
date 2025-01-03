package dev.openfeature.contrib.providers.flagd;

import static dev.openfeature.contrib.providers.flagd.Config.CACHED_REASON;
import static dev.openfeature.contrib.providers.flagd.Config.STATIC_REASON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.Struct;
import dev.openfeature.contrib.providers.flagd.resolver.Resolver;
import dev.openfeature.contrib.providers.flagd.resolver.common.ConnectionEvent;
import dev.openfeature.contrib.providers.flagd.resolver.grpc.GrpcConnector;
import dev.openfeature.contrib.providers.flagd.resolver.grpc.GrpcResolver;
import dev.openfeature.contrib.providers.flagd.resolver.grpc.cache.Cache;
import dev.openfeature.contrib.providers.flagd.resolver.process.InProcessResolver;
import dev.openfeature.contrib.providers.flagd.resolver.process.MockStorage;
import dev.openfeature.contrib.providers.flagd.resolver.process.model.FeatureFlag;
import dev.openfeature.contrib.providers.flagd.resolver.process.storage.StorageState;
import dev.openfeature.contrib.providers.flagd.resolver.process.storage.StorageStateChange;
import dev.openfeature.flagd.grpc.evaluation.Evaluation.ResolveBooleanRequest;
import dev.openfeature.flagd.grpc.evaluation.Evaluation.ResolveBooleanResponse;
import dev.openfeature.flagd.grpc.evaluation.Evaluation.ResolveFloatResponse;
import dev.openfeature.flagd.grpc.evaluation.Evaluation.ResolveIntResponse;
import dev.openfeature.flagd.grpc.evaluation.Evaluation.ResolveObjectResponse;
import dev.openfeature.flagd.grpc.evaluation.Evaluation.ResolveStringResponse;
import dev.openfeature.flagd.grpc.evaluation.ServiceGrpc;
import dev.openfeature.flagd.grpc.evaluation.ServiceGrpc.ServiceBlockingStub;
import dev.openfeature.flagd.grpc.evaluation.ServiceGrpc.ServiceStub;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import io.cucumber.java.AfterAll;
import io.grpc.Channel;
import io.grpc.Deadline;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class FlagdProviderTest {
    private static final String FLAG_KEY = "some-key";
    private static final String FLAG_KEY_BOOLEAN = "some-key-boolean";
    private static final String FLAG_KEY_INTEGER = "some-key-integer";
    private static final String FLAG_KEY_DOUBLE = "some-key-double";
    private static final String FLAG_KEY_STRING = "some-key-string";
    private static final String FLAG_KEY_OBJECT = "some-key-object";
    private static final String BOOL_VARIANT = "on";
    private static final String DOUBLE_VARIANT = "half";
    private static final String INT_VARIANT = "one-hundred";
    private static final String STRING_VARIANT = "greeting";
    private static final String OBJECT_VARIANT = "obj";
    private static final Reason DEFAULT = Reason.DEFAULT;
    private static final Integer INT_VALUE = 100;
    private static final Double DOUBLE_VALUE = .5d;
    private static final String INNER_STRUCT_KEY = "inner_key";
    private static final String INNER_STRUCT_VALUE = "inner_value";
    private static final com.google.protobuf.Struct PROTOBUF_STRUCTURE_VALUE = Struct.newBuilder()
            .putFields(
                    INNER_STRUCT_KEY,
                    com.google.protobuf.Value.newBuilder()
                            .setStringValue(INNER_STRUCT_VALUE)
                            .build())
            .build();
    private static final String STRING_VALUE = "hi!";

    private static OpenFeatureAPI api;

    @BeforeAll
    public static void init() {
        api = OpenFeatureAPI.getInstance();
    }

    @AfterAll
    public static void cleanUp() {
        api.shutdown();
    }

    @Test
    void resolvers_call_grpc_service_and_return_details() {
        ResolveBooleanResponse booleanResponse = ResolveBooleanResponse.newBuilder()
                .setValue(true)
                .setVariant(BOOL_VARIANT)
                .setReason(DEFAULT.toString())
                .build();

        ResolveStringResponse stringResponse = ResolveStringResponse.newBuilder()
                .setValue(STRING_VALUE)
                .setVariant(STRING_VARIANT)
                .setReason(DEFAULT.toString())
                .build();

        ResolveIntResponse intResponse = ResolveIntResponse.newBuilder()
                .setValue(INT_VALUE)
                .setVariant(INT_VARIANT)
                .setReason(DEFAULT.toString())
                .build();

        ResolveFloatResponse floatResponse = ResolveFloatResponse.newBuilder()
                .setValue(DOUBLE_VALUE)
                .setVariant(DOUBLE_VARIANT)
                .setReason(DEFAULT.toString())
                .build();

        ResolveObjectResponse objectResponse = ResolveObjectResponse.newBuilder()
                .setValue(PROTOBUF_STRUCTURE_VALUE)
                .setVariant(OBJECT_VARIANT)
                .setReason(DEFAULT.toString())
                .build();

        ServiceBlockingStub serviceBlockingStubMock = mock(ServiceBlockingStub.class);

        when(serviceBlockingStubMock.withDeadlineAfter(anyLong(), any(TimeUnit.class)))
                .thenReturn(serviceBlockingStubMock);
        when(serviceBlockingStubMock.resolveBoolean(argThat(x -> FLAG_KEY_BOOLEAN.equals(x.getFlagKey()))))
                .thenReturn(booleanResponse);
        when(serviceBlockingStubMock.resolveFloat(argThat(x -> FLAG_KEY_DOUBLE.equals(x.getFlagKey()))))
                .thenReturn(floatResponse);
        when(serviceBlockingStubMock.resolveInt(argThat(x -> FLAG_KEY_INTEGER.equals(x.getFlagKey()))))
                .thenReturn(intResponse);
        when(serviceBlockingStubMock.resolveString(argThat(x -> FLAG_KEY_STRING.equals(x.getFlagKey()))))
                .thenReturn(stringResponse);
        when(serviceBlockingStubMock.resolveObject(argThat(x -> FLAG_KEY_OBJECT.equals(x.getFlagKey()))))
                .thenReturn(objectResponse);

        GrpcConnector grpc = mock(GrpcConnector.class);
        when(grpc.getResolver()).thenReturn(serviceBlockingStubMock);

        OpenFeatureAPI.getInstance().setProviderAndWait(createProvider(grpc));

        FlagEvaluationDetails<Boolean> booleanDetails = api.getClient().getBooleanDetails(FLAG_KEY_BOOLEAN, false);
        assertTrue(booleanDetails.getValue());
        assertEquals(BOOL_VARIANT, booleanDetails.getVariant());
        assertEquals(DEFAULT.toString(), booleanDetails.getReason());

        FlagEvaluationDetails<String> stringDetails = api.getClient().getStringDetails(FLAG_KEY_STRING, "wrong");
        assertEquals(STRING_VALUE, stringDetails.getValue());
        assertEquals(STRING_VARIANT, stringDetails.getVariant());
        assertEquals(DEFAULT.toString(), stringDetails.getReason());

        FlagEvaluationDetails<Integer> intDetails = api.getClient().getIntegerDetails(FLAG_KEY_INTEGER, 0);
        assertEquals(INT_VALUE, intDetails.getValue());
        assertEquals(INT_VARIANT, intDetails.getVariant());
        assertEquals(DEFAULT.toString(), intDetails.getReason());

        FlagEvaluationDetails<Double> floatDetails = api.getClient().getDoubleDetails(FLAG_KEY_DOUBLE, 0.1);
        assertEquals(DOUBLE_VALUE, floatDetails.getValue());
        assertEquals(DOUBLE_VARIANT, floatDetails.getVariant());
        assertEquals(DEFAULT.toString(), floatDetails.getReason());

        FlagEvaluationDetails<Value> objectDetails = api.getClient().getObjectDetails(FLAG_KEY_OBJECT, new Value());
        assertEquals(
                INNER_STRUCT_VALUE,
                objectDetails
                        .getValue()
                        .asStructure()
                        .asMap()
                        .get(INNER_STRUCT_KEY)
                        .asString());
        assertEquals(OBJECT_VARIANT, objectDetails.getVariant());
        assertEquals(DEFAULT.toString(), objectDetails.getReason());
    }

    @Test
    void zero_value() {
        ResolveBooleanResponse booleanResponse = ResolveBooleanResponse.newBuilder()
                .setVariant(BOOL_VARIANT)
                .setReason(DEFAULT.toString())
                .build();

        ResolveStringResponse stringResponse = ResolveStringResponse.newBuilder()
                .setVariant(STRING_VARIANT)
                .setReason(DEFAULT.toString())
                .build();

        ResolveIntResponse intResponse = ResolveIntResponse.newBuilder()
                .setVariant(INT_VARIANT)
                .setReason(DEFAULT.toString())
                .build();

        ResolveFloatResponse floatResponse = ResolveFloatResponse.newBuilder()
                .setVariant(DOUBLE_VARIANT)
                .setReason(DEFAULT.toString())
                .build();

        ResolveObjectResponse objectResponse = ResolveObjectResponse.newBuilder()
                .setVariant(OBJECT_VARIANT)
                .setReason(DEFAULT.toString())
                .build();

        ServiceBlockingStub serviceBlockingStubMock = mock(ServiceBlockingStub.class);
        when(serviceBlockingStubMock.withDeadlineAfter(anyLong(), any(TimeUnit.class)))
                .thenReturn(serviceBlockingStubMock);
        when(serviceBlockingStubMock.resolveBoolean(argThat(x -> FLAG_KEY_BOOLEAN.equals(x.getFlagKey()))))
                .thenReturn(booleanResponse);
        when(serviceBlockingStubMock.resolveFloat(argThat(x -> FLAG_KEY_DOUBLE.equals(x.getFlagKey()))))
                .thenReturn(floatResponse);
        when(serviceBlockingStubMock.resolveInt(argThat(x -> FLAG_KEY_INTEGER.equals(x.getFlagKey()))))
                .thenReturn(intResponse);
        when(serviceBlockingStubMock.resolveString(argThat(x -> FLAG_KEY_STRING.equals(x.getFlagKey()))))
                .thenReturn(stringResponse);
        when(serviceBlockingStubMock.resolveObject(argThat(x -> FLAG_KEY_OBJECT.equals(x.getFlagKey()))))
                .thenReturn(objectResponse);

        GrpcConnector grpc = mock(GrpcConnector.class);
        when(grpc.getResolver()).thenReturn(serviceBlockingStubMock);

        OpenFeatureAPI.getInstance().setProviderAndWait(createProvider(grpc));

        FlagEvaluationDetails<Boolean> booleanDetails = api.getClient().getBooleanDetails(FLAG_KEY_BOOLEAN, false);
        assertEquals(false, booleanDetails.getValue());
        assertEquals(BOOL_VARIANT, booleanDetails.getVariant());
        assertEquals(DEFAULT.toString(), booleanDetails.getReason());

        FlagEvaluationDetails<String> stringDetails = api.getClient().getStringDetails(FLAG_KEY_STRING, "wrong");
        assertEquals("", stringDetails.getValue());
        assertEquals(STRING_VARIANT, stringDetails.getVariant());
        assertEquals(DEFAULT.toString(), stringDetails.getReason());

        FlagEvaluationDetails<Integer> intDetails = api.getClient().getIntegerDetails(FLAG_KEY_INTEGER, 0);
        assertEquals(0, intDetails.getValue());
        assertEquals(INT_VARIANT, intDetails.getVariant());
        assertEquals(DEFAULT.toString(), intDetails.getReason());

        FlagEvaluationDetails<Double> floatDetails = api.getClient().getDoubleDetails(FLAG_KEY_DOUBLE, 0.1);
        assertEquals(0.0, floatDetails.getValue());
        assertEquals(DOUBLE_VARIANT, floatDetails.getVariant());
        assertEquals(DEFAULT.toString(), floatDetails.getReason());

        FlagEvaluationDetails<Value> objectDetails = api.getClient().getObjectDetails(FLAG_KEY_OBJECT, new Value());
        assertEquals(new MutableStructure(), objectDetails.getValue().asObject());
        assertEquals(OBJECT_VARIANT, objectDetails.getVariant());
        assertEquals(DEFAULT.toString(), objectDetails.getReason());
    }

    @Test
    void test_metadata_from_grpc_response() {
        // given
        final Map<String, com.google.protobuf.Value> metadataInput = new HashMap<>();

        com.google.protobuf.Value scope = com.google.protobuf.Value.newBuilder()
                .setStringValue("flagd-scope")
                .build();
        metadataInput.put("scope", scope);

        com.google.protobuf.Value bool =
                com.google.protobuf.Value.newBuilder().setBoolValue(true).build();
        metadataInput.put("boolean", bool);

        com.google.protobuf.Value number =
                com.google.protobuf.Value.newBuilder().setNumberValue(1).build();
        metadataInput.put("number", number);

        final Struct metadataStruct =
                Struct.newBuilder().putAllFields(metadataInput).build();

        ResolveBooleanResponse booleanResponse = ResolveBooleanResponse.newBuilder()
                .setValue(true)
                .setVariant(BOOL_VARIANT)
                .setReason(DEFAULT.toString())
                .setMetadata(metadataStruct)
                .build();

        ServiceBlockingStub serviceBlockingStubMock = mock(ServiceBlockingStub.class);

        when(serviceBlockingStubMock.withDeadlineAfter(anyLong(), any(TimeUnit.class)))
                .thenReturn(serviceBlockingStubMock);
        when(serviceBlockingStubMock.resolveBoolean(argThat(x -> FLAG_KEY_BOOLEAN.equals(x.getFlagKey()))))
                .thenReturn(booleanResponse);

        GrpcConnector grpc = mock(GrpcConnector.class);
        when(grpc.getResolver()).thenReturn(serviceBlockingStubMock);
        OpenFeatureAPI.getInstance().setProviderAndWait(createProvider(grpc));

        // when
        FlagEvaluationDetails<Boolean> booleanDetails = api.getClient().getBooleanDetails(FLAG_KEY_BOOLEAN, false);

        // then
        final ImmutableMetadata metadata = booleanDetails.getFlagMetadata();

        assertEquals("flagd-scope", metadata.getString("scope"));
        assertEquals(true, metadata.getBoolean("boolean"));
        assertEquals(1, metadata.getDouble("number"));
    }

    @Test
    void resolvers_cache_responses_if_static_and_event_stream_alive() {
        do_resolvers_cache_responses(STATIC_REASON, true, true);
    }

    @Test
    void resolvers_should_not_cache_responses_if_not_static() {
        do_resolvers_cache_responses(DEFAULT.toString(), true, false);
    }

    @Test
    void resolvers_should_not_cache_responses_if_event_stream_not_alive() {
        do_resolvers_cache_responses(STATIC_REASON, false, false);
    }

    @Test
    void context_is_parsed_and_passed_to_grpc_service() {
        final String BOOLEAN_ATTR_KEY = "bool-attr";
        final String INT_ATTR_KEY = "int-attr";
        final String STRING_ATTR_KEY = "string-attr";
        final String STRUCT_ATTR_KEY = "struct-attr";
        final String DOUBLE_ATTR_KEY = "double-attr";
        final String LIST_ATTR_KEY = "list-attr";
        final String STRUCT_ATTR_INNER_KEY = "struct-inner-key";

        final Boolean BOOLEAN_ATTR_VALUE = true;
        final int INT_ATTR_VALUE = 1;
        final String STRING_ATTR_VALUE = "str";
        final double DOUBLE_ATTR_VALUE = 0.5d;
        final List<Value> LIST_ATTR_VALUE = new ArrayList<Value>() {
            {
                add(new Value(1));
            }
        };
        final String STRUCT_ATTR_INNER_VALUE = "struct-inner-value";
        final Structure STRUCT_ATTR_VALUE = new MutableStructure().add(STRUCT_ATTR_INNER_KEY, STRUCT_ATTR_INNER_VALUE);
        final String DEFAULT_STRING = "DEFAULT";

        ResolveBooleanResponse booleanResponse = ResolveBooleanResponse.newBuilder()
                .setValue(true)
                .setVariant(BOOL_VARIANT)
                .setReason(DEFAULT_STRING.toString())
                .build();

        ServiceBlockingStub serviceBlockingStubMock = mock(ServiceBlockingStub.class);
        when(serviceBlockingStubMock.withDeadlineAfter(anyLong(), any(TimeUnit.class)))
                .thenReturn(serviceBlockingStubMock);
        when(serviceBlockingStubMock.resolveBoolean(argThat(x -> {
                    final Struct struct = x.getContext();
                    final Map<String, com.google.protobuf.Value> valueMap = struct.getFieldsMap();

                    return STRING_ATTR_VALUE.equals(
                                    valueMap.get(STRING_ATTR_KEY).getStringValue())
                            && INT_ATTR_VALUE == valueMap.get(INT_ATTR_KEY).getNumberValue()
                            && DOUBLE_ATTR_VALUE
                                    == valueMap.get(DOUBLE_ATTR_KEY).getNumberValue()
                            && valueMap.get(BOOLEAN_ATTR_KEY).getBoolValue()
                            && "MY_TARGETING_KEY"
                                    .equals(valueMap.get("targetingKey").getStringValue())
                            && LIST_ATTR_VALUE.get(0).asInteger()
                                    == valueMap.get(LIST_ATTR_KEY)
                                            .getListValue()
                                            .getValuesList()
                                            .get(0)
                                            .getNumberValue()
                            && STRUCT_ATTR_INNER_VALUE.equals(valueMap.get(STRUCT_ATTR_KEY)
                                    .getStructValue()
                                    .getFieldsMap()
                                    .get(STRUCT_ATTR_INNER_KEY)
                                    .getStringValue());
                })))
                .thenReturn(booleanResponse);

        GrpcConnector grpc = mock(GrpcConnector.class);
        when(grpc.getResolver()).thenReturn(serviceBlockingStubMock);

        OpenFeatureAPI.getInstance().setProviderAndWait(createProvider(grpc));

        final MutableContext context = new MutableContext("MY_TARGETING_KEY");
        context.add(BOOLEAN_ATTR_KEY, BOOLEAN_ATTR_VALUE);
        context.add(INT_ATTR_KEY, INT_ATTR_VALUE);
        context.add(DOUBLE_ATTR_KEY, DOUBLE_ATTR_VALUE);
        context.add(LIST_ATTR_KEY, LIST_ATTR_VALUE);
        context.add(STRING_ATTR_KEY, STRING_ATTR_VALUE);
        context.add(STRUCT_ATTR_KEY, STRUCT_ATTR_VALUE);

        FlagEvaluationDetails<Boolean> booleanDetails = api.getClient().getBooleanDetails(FLAG_KEY, false, context);
        assertTrue(booleanDetails.getValue());
        assertEquals(BOOL_VARIANT, booleanDetails.getVariant());
        assertEquals(DEFAULT.toString(), booleanDetails.getReason());
    }

    // Validates null handling -
    // https://github.com/open-feature/java-sdk-contrib/issues/258
    @Test
    void null_context_handling() {
        // given
        final String flagA = "flagA";
        final boolean defaultVariant = false;
        final boolean expectedVariant = true;

        final MutableContext context = new MutableContext();
        context.add("key", (String) null);

        final ServiceBlockingStub serviceBlockingStubMock = mock(ServiceBlockingStub.class);

        // when
        when(serviceBlockingStubMock.withDeadlineAfter(anyLong(), any(TimeUnit.class)))
                .thenReturn(serviceBlockingStubMock);
        when(serviceBlockingStubMock.resolveBoolean(any()))
                .thenReturn(ResolveBooleanResponse.newBuilder()
                        .setValue(expectedVariant)
                        .build());

        GrpcConnector grpc = mock(GrpcConnector.class);
        when(grpc.getResolver()).thenReturn(serviceBlockingStubMock);

        OpenFeatureAPI.getInstance().setProviderAndWait(createProvider(grpc));

        // then
        final Boolean evaluation = api.getClient().getBooleanValue(flagA, defaultVariant, context);

        assertNotEquals(evaluation, defaultVariant);
        assertEquals(evaluation, expectedVariant);
    }

    @Test
    void reason_mapped_correctly_if_unknown() {
        ResolveBooleanResponse badReasonResponse = ResolveBooleanResponse.newBuilder()
                .setValue(true)
                .setVariant(BOOL_VARIANT)
                .setReason("UNKNOWN") // set an invalid reason string
                .build();

        ServiceBlockingStub serviceBlockingStubMock = mock(ServiceBlockingStub.class);
        when(serviceBlockingStubMock.withDeadlineAfter(anyLong(), any(TimeUnit.class)))
                .thenReturn(serviceBlockingStubMock);
        when(serviceBlockingStubMock.resolveBoolean(any(ResolveBooleanRequest.class)))
                .thenReturn(badReasonResponse);

        GrpcConnector grpc = mock(GrpcConnector.class);
        when(grpc.getResolver()).thenReturn(serviceBlockingStubMock);

        OpenFeatureAPI.getInstance().setProviderAndWait(createProvider(grpc));

        FlagEvaluationDetails<Boolean> booleanDetails =
                api.getClient().getBooleanDetails(FLAG_KEY, false, new MutableContext());
        assertEquals(Reason.UNKNOWN.toString(), booleanDetails.getReason()); // reason should be converted to
        // UNKNOWN
    }

    @Test
    void invalidate_cache() {
        ResolveBooleanResponse booleanResponse = ResolveBooleanResponse.newBuilder()
                .setValue(true)
                .setVariant(BOOL_VARIANT)
                .setReason(STATIC_REASON)
                .build();

        ResolveStringResponse stringResponse = ResolveStringResponse.newBuilder()
                .setValue(STRING_VALUE)
                .setVariant(STRING_VARIANT)
                .setReason(STATIC_REASON)
                .build();

        ResolveIntResponse intResponse = ResolveIntResponse.newBuilder()
                .setValue(INT_VALUE)
                .setVariant(INT_VARIANT)
                .setReason(STATIC_REASON)
                .build();

        ResolveFloatResponse floatResponse = ResolveFloatResponse.newBuilder()
                .setValue(DOUBLE_VALUE)
                .setVariant(DOUBLE_VARIANT)
                .setReason(STATIC_REASON)
                .build();

        ResolveObjectResponse objectResponse = ResolveObjectResponse.newBuilder()
                .setValue(PROTOBUF_STRUCTURE_VALUE)
                .setVariant(OBJECT_VARIANT)
                .setReason(STATIC_REASON)
                .build();

        ServiceBlockingStub serviceBlockingStubMock = mock(ServiceBlockingStub.class);
        ServiceStub serviceStubMock = mock(ServiceStub.class);
        when(serviceStubMock.withWaitForReady()).thenReturn(serviceStubMock);
        doNothing().when(serviceStubMock).eventStream(any(), any());
        when(serviceStubMock.withDeadline(any(Deadline.class))).thenReturn(serviceStubMock);
        when(serviceBlockingStubMock.withWaitForReady()).thenReturn(serviceBlockingStubMock);
        when(serviceBlockingStubMock.withDeadline(any(Deadline.class))).thenReturn(serviceBlockingStubMock);
        when(serviceBlockingStubMock.withDeadlineAfter(anyLong(), any(TimeUnit.class)))
                .thenReturn(serviceBlockingStubMock);
        when(serviceBlockingStubMock.resolveBoolean(argThat(x -> FLAG_KEY_BOOLEAN.equals(x.getFlagKey()))))
                .thenReturn(booleanResponse);
        when(serviceBlockingStubMock.resolveFloat(argThat(x -> FLAG_KEY_DOUBLE.equals(x.getFlagKey()))))
                .thenReturn(floatResponse);
        when(serviceBlockingStubMock.resolveInt(argThat(x -> FLAG_KEY_INTEGER.equals(x.getFlagKey()))))
                .thenReturn(intResponse);
        when(serviceBlockingStubMock.resolveString(argThat(x -> FLAG_KEY_STRING.equals(x.getFlagKey()))))
                .thenReturn(stringResponse);
        when(serviceBlockingStubMock.resolveObject(argThat(x -> FLAG_KEY_OBJECT.equals(x.getFlagKey()))))
                .thenReturn(objectResponse);

        GrpcConnector grpc;
        try (MockedStatic<ServiceGrpc> mockStaticService = mockStatic(ServiceGrpc.class)) {
            mockStaticService
                    .when(() -> ServiceGrpc.newBlockingStub(any(Channel.class)))
                    .thenReturn(serviceBlockingStubMock);
            mockStaticService.when(() -> ServiceGrpc.newStub(any())).thenReturn(serviceStubMock);

            final Cache cache = new Cache("lru", 5);

            class NoopInitGrpcConnector extends GrpcConnector {
                public NoopInitGrpcConnector(
                        FlagdOptions options,
                        Cache cache,
                        Supplier<Boolean> connectedSupplier,
                        Consumer<ConnectionEvent> onConnectionEvent) {
                    super(options, cache, connectedSupplier, onConnectionEvent);
                }

                public void initialize() throws Exception {}
                ;
            }

            grpc = new NoopInitGrpcConnector(
                    FlagdOptions.builder().build(), cache, () -> true, (connectionEvent) -> {});
        }

        FlagdProvider provider = createProvider(grpc);
        OpenFeatureAPI.getInstance().setProviderAndWait(provider);

        HashMap<String, com.google.protobuf.Value> flagsMap = new HashMap<String, com.google.protobuf.Value>();
        HashMap<String, com.google.protobuf.Value> structMap = new HashMap<String, com.google.protobuf.Value>();

        flagsMap.put(
                FLAG_KEY_BOOLEAN,
                com.google.protobuf.Value.newBuilder().setStringValue("foo").build());
        flagsMap.put(
                FLAG_KEY_STRING,
                com.google.protobuf.Value.newBuilder().setStringValue("foo").build());
        flagsMap.put(
                FLAG_KEY_INTEGER,
                com.google.protobuf.Value.newBuilder().setStringValue("foo").build());
        flagsMap.put(
                FLAG_KEY_DOUBLE,
                com.google.protobuf.Value.newBuilder().setStringValue("foo").build());
        flagsMap.put(
                FLAG_KEY_OBJECT,
                com.google.protobuf.Value.newBuilder().setStringValue("foo").build());

        structMap.put(
                "flags",
                com.google.protobuf.Value.newBuilder()
                        .setStructValue(Struct.newBuilder().putAllFields(flagsMap))
                        .build());

        // should cache results
        FlagEvaluationDetails<Boolean> booleanDetails;
        FlagEvaluationDetails<String> stringDetails;
        FlagEvaluationDetails<Integer> intDetails;
        FlagEvaluationDetails<Double> floatDetails;
        FlagEvaluationDetails<Value> objectDetails;

        // assert cache has been invalidated
        booleanDetails = api.getClient().getBooleanDetails(FLAG_KEY_BOOLEAN, false);
        assertTrue(booleanDetails.getValue());
        assertEquals(BOOL_VARIANT, booleanDetails.getVariant());
        assertEquals(STATIC_REASON, booleanDetails.getReason());

        stringDetails = api.getClient().getStringDetails(FLAG_KEY_STRING, "wrong");
        assertEquals(STRING_VALUE, stringDetails.getValue());
        assertEquals(STRING_VARIANT, stringDetails.getVariant());
        assertEquals(STATIC_REASON, stringDetails.getReason());

        intDetails = api.getClient().getIntegerDetails(FLAG_KEY_INTEGER, 0);
        assertEquals(INT_VALUE, intDetails.getValue());
        assertEquals(INT_VARIANT, intDetails.getVariant());
        assertEquals(STATIC_REASON, intDetails.getReason());

        floatDetails = api.getClient().getDoubleDetails(FLAG_KEY_DOUBLE, 0.1);
        assertEquals(DOUBLE_VALUE, floatDetails.getValue());
        assertEquals(DOUBLE_VARIANT, floatDetails.getVariant());
        assertEquals(STATIC_REASON, floatDetails.getReason());

        objectDetails = api.getClient().getObjectDetails(FLAG_KEY_OBJECT, new Value());
        assertEquals(
                INNER_STRUCT_VALUE,
                objectDetails
                        .getValue()
                        .asStructure()
                        .asMap()
                        .get(INNER_STRUCT_KEY)
                        .asString());
        assertEquals(OBJECT_VARIANT, objectDetails.getVariant());
        assertEquals(STATIC_REASON, objectDetails.getReason());
    }

    private void do_resolvers_cache_responses(String reason, Boolean eventStreamAlive, Boolean shouldCache) {
        String expectedReason = CACHED_REASON;
        if (!shouldCache) {
            expectedReason = reason;
        }

        ResolveBooleanResponse booleanResponse = ResolveBooleanResponse.newBuilder()
                .setValue(true)
                .setVariant(BOOL_VARIANT)
                .setReason(reason)
                .build();

        ResolveStringResponse stringResponse = ResolveStringResponse.newBuilder()
                .setValue(STRING_VALUE)
                .setVariant(STRING_VARIANT)
                .setReason(reason)
                .build();

        ResolveIntResponse intResponse = ResolveIntResponse.newBuilder()
                .setValue(INT_VALUE)
                .setVariant(INT_VARIANT)
                .setReason(reason)
                .build();

        ResolveFloatResponse floatResponse = ResolveFloatResponse.newBuilder()
                .setValue(DOUBLE_VALUE)
                .setVariant(DOUBLE_VARIANT)
                .setReason(reason)
                .build();

        ResolveObjectResponse objectResponse = ResolveObjectResponse.newBuilder()
                .setValue(PROTOBUF_STRUCTURE_VALUE)
                .setVariant(OBJECT_VARIANT)
                .setReason(reason)
                .build();

        ServiceBlockingStub serviceBlockingStubMock = mock(ServiceBlockingStub.class);
        when(serviceBlockingStubMock.withDeadlineAfter(anyLong(), any(TimeUnit.class)))
                .thenReturn(serviceBlockingStubMock);
        when(serviceBlockingStubMock.resolveBoolean(argThat(x -> FLAG_KEY_BOOLEAN.equals(x.getFlagKey()))))
                .thenReturn(booleanResponse);
        when(serviceBlockingStubMock.resolveFloat(argThat(x -> FLAG_KEY_DOUBLE.equals(x.getFlagKey()))))
                .thenReturn(floatResponse);
        when(serviceBlockingStubMock.resolveInt(argThat(x -> FLAG_KEY_INTEGER.equals(x.getFlagKey()))))
                .thenReturn(intResponse);
        when(serviceBlockingStubMock.resolveString(argThat(x -> FLAG_KEY_STRING.equals(x.getFlagKey()))))
                .thenReturn(stringResponse);
        when(serviceBlockingStubMock.resolveObject(argThat(x -> FLAG_KEY_OBJECT.equals(x.getFlagKey()))))
                .thenReturn(objectResponse);

        GrpcConnector grpc = mock(GrpcConnector.class);
        when(grpc.getResolver()).thenReturn(serviceBlockingStubMock);
        FlagdProvider provider = createProvider(grpc, () -> eventStreamAlive);
        // provider.setState(eventStreamAlive); // caching only available when event
        // stream is alive
        OpenFeatureAPI.getInstance().setProviderAndWait(provider);

        FlagEvaluationDetails<Boolean> booleanDetails = api.getClient().getBooleanDetails(FLAG_KEY_BOOLEAN, false);
        booleanDetails =
                api.getClient().getBooleanDetails(FLAG_KEY_BOOLEAN, false); // should retrieve from cache on second
        // invocation
        assertTrue(booleanDetails.getValue());
        assertEquals(BOOL_VARIANT, booleanDetails.getVariant());
        assertEquals(expectedReason, booleanDetails.getReason());

        FlagEvaluationDetails<String> stringDetails = api.getClient().getStringDetails(FLAG_KEY_STRING, "wrong");
        stringDetails = api.getClient().getStringDetails(FLAG_KEY_STRING, "wrong");
        assertEquals(STRING_VALUE, stringDetails.getValue());
        assertEquals(STRING_VARIANT, stringDetails.getVariant());
        assertEquals(expectedReason, stringDetails.getReason());

        FlagEvaluationDetails<Integer> intDetails = api.getClient().getIntegerDetails(FLAG_KEY_INTEGER, 0);
        intDetails = api.getClient().getIntegerDetails(FLAG_KEY_INTEGER, 0);
        assertEquals(INT_VALUE, intDetails.getValue());
        assertEquals(INT_VARIANT, intDetails.getVariant());
        assertEquals(expectedReason, intDetails.getReason());

        FlagEvaluationDetails<Double> floatDetails = api.getClient().getDoubleDetails(FLAG_KEY_DOUBLE, 0.1);
        floatDetails = api.getClient().getDoubleDetails(FLAG_KEY_DOUBLE, 0.1);
        assertEquals(DOUBLE_VALUE, floatDetails.getValue());
        assertEquals(DOUBLE_VARIANT, floatDetails.getVariant());
        assertEquals(expectedReason, floatDetails.getReason());

        FlagEvaluationDetails<Value> objectDetails = api.getClient().getObjectDetails(FLAG_KEY_OBJECT, new Value());
        objectDetails = api.getClient().getObjectDetails(FLAG_KEY_OBJECT, new Value());
        assertEquals(
                INNER_STRUCT_VALUE,
                objectDetails
                        .getValue()
                        .asStructure()
                        .asMap()
                        .get(INNER_STRUCT_KEY)
                        .asString());
        assertEquals(OBJECT_VARIANT, objectDetails.getVariant());
        assertEquals(expectedReason, objectDetails.getReason());
    }

    @Test
    void disabled_cache() {
        ResolveBooleanResponse booleanResponse = ResolveBooleanResponse.newBuilder()
                .setValue(true)
                .setVariant(BOOL_VARIANT)
                .setReason(STATIC_REASON)
                .build();

        ResolveStringResponse stringResponse = ResolveStringResponse.newBuilder()
                .setValue(STRING_VALUE)
                .setVariant(STRING_VARIANT)
                .setReason(STATIC_REASON)
                .build();

        ResolveIntResponse intResponse = ResolveIntResponse.newBuilder()
                .setValue(INT_VALUE)
                .setVariant(INT_VARIANT)
                .setReason(STATIC_REASON)
                .build();

        ResolveFloatResponse floatResponse = ResolveFloatResponse.newBuilder()
                .setValue(DOUBLE_VALUE)
                .setVariant(DOUBLE_VARIANT)
                .setReason(STATIC_REASON)
                .build();

        ResolveObjectResponse objectResponse = ResolveObjectResponse.newBuilder()
                .setValue(PROTOBUF_STRUCTURE_VALUE)
                .setVariant(OBJECT_VARIANT)
                .setReason(STATIC_REASON)
                .build();

        ServiceBlockingStub serviceBlockingStubMock = mock(ServiceBlockingStub.class);
        ServiceStub serviceStubMock = mock(ServiceStub.class);
        when(serviceStubMock.withWaitForReady()).thenReturn(serviceStubMock);
        when(serviceStubMock.withDeadline(any(Deadline.class))).thenReturn(serviceStubMock);
        when(serviceBlockingStubMock.withWaitForReady()).thenReturn(serviceBlockingStubMock);
        when(serviceBlockingStubMock.withDeadline(any(Deadline.class))).thenReturn(serviceBlockingStubMock);
        when(serviceBlockingStubMock.withDeadlineAfter(anyLong(), any(TimeUnit.class)))
                .thenReturn(serviceBlockingStubMock);
        when(serviceBlockingStubMock.resolveBoolean(argThat(x -> FLAG_KEY_BOOLEAN.equals(x.getFlagKey()))))
                .thenReturn(booleanResponse);
        when(serviceBlockingStubMock.resolveFloat(argThat(x -> FLAG_KEY_DOUBLE.equals(x.getFlagKey()))))
                .thenReturn(floatResponse);
        when(serviceBlockingStubMock.resolveInt(argThat(x -> FLAG_KEY_INTEGER.equals(x.getFlagKey()))))
                .thenReturn(intResponse);
        when(serviceBlockingStubMock.resolveString(argThat(x -> FLAG_KEY_STRING.equals(x.getFlagKey()))))
                .thenReturn(stringResponse);
        when(serviceBlockingStubMock.resolveObject(argThat(x -> FLAG_KEY_OBJECT.equals(x.getFlagKey()))))
                .thenReturn(objectResponse);

        // disabled cache
        final Cache cache = new Cache("disabled", 0);

        GrpcConnector grpc;
        try (MockedStatic<ServiceGrpc> mockStaticService = mockStatic(ServiceGrpc.class)) {
            mockStaticService
                    .when(() -> ServiceGrpc.newBlockingStub(any(Channel.class)))
                    .thenReturn(serviceBlockingStubMock);
            mockStaticService.when(() -> ServiceGrpc.newStub(any())).thenReturn(serviceStubMock);

            class NoopInitGrpcConnector extends GrpcConnector {
                public NoopInitGrpcConnector(
                        FlagdOptions options,
                        Cache cache,
                        Supplier<Boolean> connectedSupplier,
                        Consumer<ConnectionEvent> onConnectionEvent) {
                    super(options, cache, connectedSupplier, onConnectionEvent);
                }

                public void initialize() throws Exception {}
                ;
            }

            grpc = new NoopInitGrpcConnector(
                    FlagdOptions.builder().build(), cache, () -> true, (connectionEvent) -> {});
        }

        FlagdProvider provider = createProvider(grpc, cache, () -> true);

        try {
            provider.initialize(null);
        } catch (Exception e) {
            // ignore exception if any
        }

        OpenFeatureAPI.getInstance().setProviderAndWait(provider);

        HashMap<String, com.google.protobuf.Value> flagsMap = new HashMap<>();
        HashMap<String, com.google.protobuf.Value> structMap = new HashMap<>();

        flagsMap.put(
                "foo",
                com.google.protobuf.Value.newBuilder()
                        .setStringValue("foo")
                        .build()); // assert that a configuration_change event works

        structMap.put(
                "flags",
                com.google.protobuf.Value.newBuilder()
                        .setStructValue(Struct.newBuilder().putAllFields(flagsMap))
                        .build());

        // should not cache results
        FlagEvaluationDetails<Boolean> booleanDetails = api.getClient().getBooleanDetails(FLAG_KEY_BOOLEAN, false);
        FlagEvaluationDetails<String> stringDetails = api.getClient().getStringDetails(FLAG_KEY_STRING, "wrong");
        FlagEvaluationDetails<Integer> intDetails = api.getClient().getIntegerDetails(FLAG_KEY_INTEGER, 0);
        FlagEvaluationDetails<Double> floatDetails = api.getClient().getDoubleDetails(FLAG_KEY_DOUBLE, 0.1);
        FlagEvaluationDetails<Value> objectDetails = api.getClient().getObjectDetails(FLAG_KEY_OBJECT, new Value());

        // assert values are not cached
        booleanDetails = api.getClient().getBooleanDetails(FLAG_KEY_BOOLEAN, false);
        assertTrue(booleanDetails.getValue());
        assertEquals(BOOL_VARIANT, booleanDetails.getVariant());
        assertEquals(STATIC_REASON, booleanDetails.getReason());

        stringDetails = api.getClient().getStringDetails(FLAG_KEY_STRING, "wrong");
        assertEquals(STRING_VALUE, stringDetails.getValue());
        assertEquals(STRING_VARIANT, stringDetails.getVariant());
        assertEquals(STATIC_REASON, stringDetails.getReason());

        intDetails = api.getClient().getIntegerDetails(FLAG_KEY_INTEGER, 0);
        assertEquals(INT_VALUE, intDetails.getValue());
        assertEquals(INT_VARIANT, intDetails.getVariant());
        assertEquals(STATIC_REASON, intDetails.getReason());

        floatDetails = api.getClient().getDoubleDetails(FLAG_KEY_DOUBLE, 0.1);
        assertEquals(DOUBLE_VALUE, floatDetails.getValue());
        assertEquals(DOUBLE_VARIANT, floatDetails.getVariant());
        assertEquals(STATIC_REASON, floatDetails.getReason());

        objectDetails = api.getClient().getObjectDetails(FLAG_KEY_OBJECT, new Value());
        assertEquals(
                INNER_STRUCT_VALUE,
                objectDetails
                        .getValue()
                        .asStructure()
                        .asMap()
                        .get(INNER_STRUCT_KEY)
                        .asString());
        assertEquals(OBJECT_VARIANT, objectDetails.getVariant());
        assertEquals(STATIC_REASON, objectDetails.getReason());
    }

    @Test
    void initializationAndShutdown() throws Exception {
        // given
        final FlagdProvider provider = new FlagdProvider();
        final EvaluationContext ctx = new ImmutableContext();

        final Resolver resolverMock = mock(Resolver.class);

        Field flagResolver = FlagdProvider.class.getDeclaredField("flagResolver");
        flagResolver.setAccessible(true);
        flagResolver.set(provider, resolverMock);

        // when

        // validate multiple initialization
        provider.initialize(ctx);
        provider.initialize(ctx);

        // validate multiple shutdowns
        provider.shutdown();
        provider.shutdown();

        // then
        verify(resolverMock, times(1)).init();
        verify(resolverMock, times(1)).shutdown();
    }

    @Test
    void contextEnrichment() throws Exception {

        final EvaluationContext ctx = new ImmutableContext();
        String key = "key1";
        String val = "val1";
        MutableStructure metadata = new MutableStructure();
        metadata.add(key, val);
        // given
        final Function<Structure, EvaluationContext> mockEnricher = mock(Function.class);

        // mock a resolver
        try (MockedConstruction<InProcessResolver> mockResolver =
                mockConstruction(InProcessResolver.class, (mock, context) -> {
                    Consumer<ConnectionEvent> onConnectionEvent;

                    // get a reference to the onConnectionEvent callback
                    onConnectionEvent =
                            (Consumer<ConnectionEvent>) context.arguments().get(2);

                    // when our mock resolver initializes, it runs the passed onConnectionEvent
                    // callback
                    doAnswer(invocation -> {
                                onConnectionEvent.accept(new ConnectionEvent(true, metadata));
                                return null;
                            })
                            .when(mock)
                            .init();
                })) {

            final FlagdProvider provider = new FlagdProvider(FlagdOptions.builder()
                    .resolverType(Config.Resolver.IN_PROCESS)
                    .contextEnricher(mockEnricher)
                    .build());
            provider.initialize(ctx);

            // the enricher should run with init events, and be passed the metadata
            verify(mockEnricher)
                    .apply(argThat(arg -> arg.getValue(key).asString().equals(val)));
        }
    }

    @Test
    void updatesSyncMetadataWithCallback() throws Exception {
        // given
        final EvaluationContext ctx = new ImmutableContext();
        String key = "key1";
        String val = "val1";
        MutableStructure metadata = new MutableStructure();
        metadata.add(key, val);

        // mock a resolver
        try (MockedConstruction<InProcessResolver> mockResolver =
                mockConstruction(InProcessResolver.class, (mock, context) -> {
                    Consumer<ConnectionEvent> onConnectionEvent;

                    // get a reference to the onConnectionEvent callback
                    onConnectionEvent =
                            (Consumer<ConnectionEvent>) context.arguments().get(2);

                    // when our mock resolver initializes, it runs the passed onConnectionEvent
                    // callback
                    doAnswer(invocation -> {
                                onConnectionEvent.accept(new ConnectionEvent(true, metadata));
                                return null;
                            })
                            .when(mock)
                            .init();
                })) {

            FlagdProvider provider = new FlagdProvider(FlagdOptions.builder()
                    .resolverType(Config.Resolver.IN_PROCESS)
                    .build());
            provider.initialize(ctx);

            // the onConnectionEvent should have updated the sync metadata and the
            assertEquals(val, provider.getSyncMetadata().getValue(key).asString());
            assertEquals(val, provider.getEnrichedContext().getValue(key).asString());

            // call the hook manually and make sure the enriched context is returned
            Optional<EvaluationContext> contextFromHook = provider.getProviderHooks()
                    .get(0)
                    .before(
                            HookContext.builder()
                                    .flagKey("some-flag")
                                    .defaultValue(false)
                                    .type(FlagValueType.BOOLEAN)
                                    .ctx(new ImmutableContext())
                                    .build(),
                            Collections.emptyMap());
            assertEquals(val, contextFromHook.get().getValue(key).asString());
        }
    }

    // test helper

    // create provider with given grpc connector
    private FlagdProvider createProvider(GrpcConnector grpc) {
        return createProvider(grpc, () -> true);
    }

    // create provider with given grpc provider and state supplier
    private FlagdProvider createProvider(GrpcConnector grpc, Supplier<Boolean> getConnected) {
        final Cache cache = new Cache("lru", 5);

        return createProvider(grpc, cache, getConnected);
    }

    // create provider with given grpc provider, cache and state supplier
    private FlagdProvider createProvider(GrpcConnector grpc, Cache cache, Supplier<Boolean> getConnected) {
        final FlagdOptions flagdOptions = FlagdOptions.builder().build();
        final GrpcResolver grpcResolver = new GrpcResolver(flagdOptions, cache, getConnected, (connectionEvent) -> {});

        final FlagdProvider provider = new FlagdProvider();

        try {
            Field connector = GrpcResolver.class.getDeclaredField("connector");
            connector.setAccessible(true);
            connector.set(grpcResolver, grpc);

            Field flagResolver = FlagdProvider.class.getDeclaredField("flagResolver");
            flagResolver.setAccessible(true);
            flagResolver.set(provider, grpcResolver);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        return provider;
    }

    // Create an in process provider
    private FlagdProvider createInProcessProvider() {

        final FlagdOptions flagdOptions = FlagdOptions.builder()
                .resolverType(Config.Resolver.IN_PROCESS)
                .deadline(1000)
                .build();
        final FlagdProvider provider = new FlagdProvider(flagdOptions);
        final MockStorage mockStorage = new MockStorage(
                new HashMap<String, FeatureFlag>(),
                new LinkedBlockingQueue<StorageStateChange>(Arrays.asList(new StorageStateChange(StorageState.OK))));

        try {
            final Field flagResolver = FlagdProvider.class.getDeclaredField("flagResolver");
            flagResolver.setAccessible(true);
            final Resolver resolver = (Resolver) flagResolver.get(provider);

            final Field flagStore = InProcessResolver.class.getDeclaredField("flagStore");
            flagStore.setAccessible(true);
            flagStore.set(resolver, mockStorage);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        return provider;
    }
}
