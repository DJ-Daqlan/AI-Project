package tools.jackson.databind.deser;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.*;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.testutil.DatabindTestUtil;

/**
 * Test suite for the {@link DeserializationProblemHandler#handleUnexpectedNull()}
 * callback.
 * <p>
 * IMPORTANT — how this callback is actually reached:
 * <ul>
 *   <li>For <b>bean properties</b> ({@code {"x": null}} into a POJO with a primitive
 *       field), a {@code VALUE_NULL} token short-circuits in
 *       {@code SettableBeanProperty.deserialize()} straight to
 *       {@code NullValueProvider.getNullValue()}. For primitives that method throws
 *       directly and never consults the handler.</li>
 *   <li>For <b>root-level</b> reads ({@code readValue("null", int.class)}), the
 *       {@code ObjectReader} also routes {@code VALUE_NULL} to
 *       {@code getNullValue()}, again bypassing the handler.</li>
 *   <li>The handler is only invoked from a deserializer's own {@code deserialize()}
 *       method via {@code _verifyNullForPrimitive()} — in practice for
 *       <b>primitive array elements</b> such as {@code [null]} into {@code int[]}.</li>
 * </ul>
 * These tests therefore exercise the handler through primitive arrays, which is the
 * path that genuinely calls it. They also document the second constraint: because the
 * context validates a handler's return value with {@code rawClass.isInstance(value)},
 * and {@code isInstance} is always {@code false} for primitive (and primitive-array)
 * classes, a handler can only return {@code null} or {@code NOT_HANDLED} without
 * tripping the type-mismatch safeguard.
 */
public class NullForPrimitiveHandlerTest extends DatabindTestUtil
{
    /**
     * Handler that records whether it was invoked and what context it received,
     * and (by default) returns {@code null} — the only non-{@code NOT_HANDLED}
     * value accepted for a primitive target.
     */
    static class RecordingNullHandler extends DeserializationProblemHandler {
        boolean wasCalled = false;
        JavaType lastTargetType = null;
        String lastFailureMsg = null;

        @Override
        public Object handleUnexpectedNull(DeserializationContext ctxt,
                JavaType targetType, String failureMsg)
            throws JacksonException
        {
            wasCalled = true;
            lastTargetType = targetType;
            lastFailureMsg = failureMsg;
            // null is accepted by the context (treated as "use type default");
            // returning a boxed primitive would be rejected by the isInstance guard.
            return null;
        }

        boolean wasHandlerCalled() { return wasCalled; }
        JavaType getLastTargetType() { return lastTargetType; }
        String getLastFailureMsg() { return lastFailureMsg; }
    }

    // ------------------------------------------------------------------
    // 1) Handler is invoked for a null primitive-array element, and a
    //    null return lets deserialization complete with the type default.
    // ------------------------------------------------------------------
    @Test
    public void testHandlerInvokedForNullArrayElement() throws Exception {
        RecordingNullHandler handler = new RecordingNullHandler();
        ObjectMapper mapper = jsonMapperBuilder()
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .addHandler(handler)
                .build();

        int[] result = mapper.readValue("[null]", int[].class);

        assertTrue(handler.wasHandlerCalled(),
                "Handler should be invoked for null element of a primitive array");
        assertNotNull(handler.getLastTargetType(),
                "Handler should receive a target type");
        assertNotNull(handler.getLastFailureMsg(),
                "Handler should receive a failure message");
        assertTrue(handler.getLastFailureMsg().contains("FAIL_ON_NULL_FOR_PRIMITIVES"),
                "Failure message should reference the controlling feature");
        // Return value is null -> array element falls back to the primitive default.
        assertEquals(1, result.length);
        assertEquals(0, result[0]);
    }

    // ------------------------------------------------------------------
    // 2) NOT_HANDLED falls through to the standard input-mismatch error.
    // ------------------------------------------------------------------
    @Test
    public void testNotHandledFallsThroughToException() throws Exception {
        DeserializationProblemHandler handler = new DeserializationProblemHandler() {
            @Override
            public Object handleUnexpectedNull(DeserializationContext ctxt,
                    JavaType targetType, String failureMsg) throws JacksonException {
                return NOT_HANDLED;
            }
        };
        ObjectMapper mapper = jsonMapperBuilder()
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .addHandler(handler)
                .build();

        MismatchedInputException ex = assertThrows(MismatchedInputException.class,
                () -> mapper.readValue("[null]", int[].class),
                "NOT_HANDLED should let the default failure propagate");
        assertTrue(ex.getMessage().contains("Cannot coerce `null`"),
                "Default failure message should describe the null coercion problem");
    }

    // ------------------------------------------------------------------
    // 3) A handler returning a boxed value for a primitive target trips the
    //    rawClass.isInstance() safeguard (documents that boxed replacements
    //    are not accepted for primitive/primitive-array targets).
    // ------------------------------------------------------------------
    @Test
    public void testBoxedReturnValueIsRejected() throws Exception {
        DeserializationProblemHandler handler = new DeserializationProblemHandler() {
            @Override
            public Object handleUnexpectedNull(DeserializationContext ctxt,
                    JavaType targetType, String failureMsg) throws JacksonException {
                return Integer.valueOf(0); // boxed -> not assignable to a primitive type
            }
        };
        ObjectMapper mapper = jsonMapperBuilder()
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .addHandler(handler)
                .build();

        DatabindException ex = assertThrows(DatabindException.class,
                () -> mapper.readValue("[null]", int[].class),
                "A boxed return value should be rejected for a primitive target");
        assertTrue(ex.getMessage().contains("returned value of type"),
                "Error should explain the handler returned an incompatible type");
    }

    // ------------------------------------------------------------------
    // 4) A handler may throw; the cause is preserved through path wrapping.
    // ------------------------------------------------------------------
    @Test
    public void testHandlerMayThrow() throws Exception {
        DeserializationProblemHandler handler = new DeserializationProblemHandler() {
            @Override
            public Object handleUnexpectedNull(DeserializationContext ctxt,
                    JavaType targetType, String failureMsg) throws JacksonException {
                throw new IllegalStateException("custom-handler-failure");
            }
        };
        ObjectMapper mapper = jsonMapperBuilder()
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .addHandler(handler)
                .build();

        DatabindException ex = assertThrows(DatabindException.class,
                () -> mapper.readValue("[null]", int[].class),
                "Handler-thrown exception should surface as a binding failure");
        assertNotNull(ex.getCause(), "Original handler exception should be retained as cause");
        assertTrue(ex.getCause() instanceof IllegalStateException,
                "Cause should be the handler's own exception");
        assertEquals("custom-handler-failure", ex.getCause().getMessage());
    }

    // ------------------------------------------------------------------
    // 5) Feature disabled: handler is never consulted and defaults are used.
    // ------------------------------------------------------------------
    @Test
    public void testFeatureDisabledHandlerNotCalled() throws Exception {
        RecordingNullHandler handler = new RecordingNullHandler();
        ObjectMapper mapper = jsonMapperBuilder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .addHandler(handler)
                .build();

        int[] result = mapper.readValue("[null]", int[].class);

        assertFalse(handler.wasHandlerCalled(),
                "Handler must not be called when FAIL_ON_NULL_FOR_PRIMITIVES is disabled");
        assertEquals(1, result.length);
        assertEquals(0, result[0]);
    }

    // ------------------------------------------------------------------
    // 6) Regression guard: a null on a primitive BEAN property does NOT reach
    //    the handler (it is handled by getNullValue and fails directly).
    // ------------------------------------------------------------------
    static class PrimitiveBean {
        public int intValue;
    }

    @Test
    public void testBeanPropertyNullBypassesHandler() throws Exception {
        RecordingNullHandler handler = new RecordingNullHandler();
        ObjectMapper mapper = jsonMapperBuilder()
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .addHandler(handler)
                .build();

        assertThrows(DatabindException.class,
                () -> mapper.readValue("{\"intValue\": null}", PrimitiveBean.class),
                "Null for a primitive bean property should still fail");
        assertFalse(handler.wasHandlerCalled(),
                "Bean-property null path does not consult handleUnexpectedNull()");
    }
}
