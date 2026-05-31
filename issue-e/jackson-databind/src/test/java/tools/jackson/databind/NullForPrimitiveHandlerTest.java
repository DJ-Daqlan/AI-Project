package tools.jackson.databind.deser;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.*;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.testutil.DatabindTestUtil;

/**
 * Tests for the {@link DeserializationProblemHandler#handleUnexpectedNull} callback,
 * added for the case where a {@code null} is encountered for a non-nullable (primitive)
 * type while {@link DeserializationFeature#FAIL_ON_NULL_FOR_PRIMITIVES} is enabled.
 *<p>
 * Unlike the original submission, these tests exercise the callback through the path the
 * issue actually describes — a {@code null} for a primitive <b>bean property</b> — and
 * assert that a handler can both observe the problem and supply a replacement value.
 *<p>
 * Assumes the full fix is in place:
 *<ul>
 *  <li>{@code DeserializationContext.handleUnexpectedNull} guard accepts a boxed value
 *      for a primitive target (wrapper-aware {@code isInstance}).</li>
 *  <li>{@code PrimitiveOrWrapperDeserializer.getNullValue} routes through the handler.</li>
 *  <li>{@code _verifyNullForPrimitive} callers use the returned value.</li>
 *</ul>
 */
public class NullForPrimitiveHandlerTest extends DatabindTestUtil
{
    static class PrimitiveBean {
        public int intValue;
        public long longValue;
        public boolean boolValue;
        public double doubleValue;
    }

    /**
     * Records invocation and supplies a per-type replacement value. Returning a boxed
     * value for a primitive target is the whole point of the feature.
     */
    static class SupplyingHandler extends DeserializationProblemHandler {
        boolean called = false;
        JavaType lastType = null;
        String lastMsg = null;

        @Override
        public Object handleUnexpectedNull(DeserializationContext ctxt,
                JavaType targetType, String failureMsg) throws JacksonException {
            called = true;
            lastType = targetType;
            lastMsg = failureMsg;
            Class<?> raw = targetType.getRawClass();
            if (raw == Integer.TYPE || raw == Integer.class) return 42;
            if (raw == Long.TYPE    || raw == Long.class)    return 43L;
            if (raw == Boolean.TYPE || raw == Boolean.class) return true;
            if (raw == Double.TYPE  || raw == Double.class)  return 44.5d;
            return NOT_HANDLED;
        }
    }

    private ObjectMapper mapperWith(DeserializationProblemHandler h, boolean failOnNull) {
        JsonMapper.Builder b = jsonMapperBuilder();
        if (failOnNull) {
            b.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        } else {
            b.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        }
        if (h != null) {
            b.addHandler(h);
        }
        return b.build();
    }

    // 1) Default (no handler): behaviour is unchanged -> still throws.
    @Test
    public void testDefaultThrowsWhenNoHandler() throws Exception {
        ObjectMapper mapper = mapperWith(null, true);
        MismatchedInputException e = assertThrows(MismatchedInputException.class,
                () -> mapper.readValue("{\"intValue\": null}", PrimitiveBean.class));
        assertTrue(e.getMessage().contains("Cannot map `null`"),
                "Default failure message should describe the null mapping problem");
    }

    // 2) THE issue scenario: handler intercepts null for a primitive bean field and
    //    supplies a replacement value, which lands in the bean.
    @Test
    public void testHandlerSuppliesReplacementForBeanField() throws Exception {
        SupplyingHandler h = new SupplyingHandler();
        ObjectMapper mapper = mapperWith(h, true);

        PrimitiveBean bean = mapper.readValue(
                "{\"intValue\": null, \"longValue\": null, \"boolValue\": null, \"doubleValue\": null}",
                PrimitiveBean.class);

        assertTrue(h.called, "Handler must be consulted for a null primitive bean property");
        assertEquals(42,    bean.intValue);
        assertEquals(43L,   bean.longValue);
        assertEquals(true,  bean.boolValue);
        assertEquals(44.5d, bean.doubleValue, 0.0);
    }

    // 3) Handler returning null means "use the Java primitive default" (no NPE on unbox).
    @Test
    public void testHandlerReturningNullUsesPrimitiveDefault() throws Exception {
        DeserializationProblemHandler h = new DeserializationProblemHandler() {
            @Override
            public Object handleUnexpectedNull(DeserializationContext ctxt,
                    JavaType targetType, String failureMsg) {
                return null;
            }
        };
        ObjectMapper mapper = mapperWith(h, true);
        PrimitiveBean bean = mapper.readValue("{\"intValue\": null}", PrimitiveBean.class);
        assertEquals(0, bean.intValue);
    }

    // 4) NOT_HANDLED falls through to the standard failure.
    @Test
    public void testNotHandledFallsThrough() throws Exception {
        DeserializationProblemHandler h = new DeserializationProblemHandler() {
            @Override
            public Object handleUnexpectedNull(DeserializationContext ctxt,
                    JavaType targetType, String failureMsg) {
                return NOT_HANDLED;
            }
        };
        ObjectMapper mapper = mapperWith(h, true);
        assertThrows(MismatchedInputException.class,
                () -> mapper.readValue("{\"intValue\": null}", PrimitiveBean.class));
    }

    // 5) The deserialize()/array path also honours the handler's replacement value.
    @Test
    public void testHandlerForPrimitiveArrayElement() throws Exception {
        DeserializationProblemHandler h = new DeserializationProblemHandler() {
            @Override
            public Object handleUnexpectedNull(DeserializationContext ctxt,
                    JavaType targetType, String failureMsg) {
                return 7; // boxed Integer for an int[] element
            }
        };
        ObjectMapper mapper = mapperWith(h, true);
        int[] result = mapper.readValue("[null]", int[].class);
        assertArrayEquals(new int[] { 7 }, result);
    }

    // 6) Handler may throw; the cause is preserved.
    @Test
    public void testHandlerMayThrow() throws Exception {
        DeserializationProblemHandler h = new DeserializationProblemHandler() {
            @Override
            public Object handleUnexpectedNull(DeserializationContext ctxt,
                    JavaType targetType, String failureMsg) {
                throw new IllegalStateException("nope");
            }
        };
        ObjectMapper mapper = mapperWith(h, true);
        DatabindException e = assertThrows(DatabindException.class,
                () -> mapper.readValue("{\"intValue\": null}", PrimitiveBean.class));
        assertTrue(e.getCause() instanceof IllegalStateException);
        assertEquals("nope", e.getCause().getMessage());
    }

    // 7) Feature disabled: handler is never consulted, defaults are used.
    @Test
    public void testFeatureDisabledHandlerNotCalled() throws Exception {
        SupplyingHandler h = new SupplyingHandler();
        ObjectMapper mapper = mapperWith(h, false);
        PrimitiveBean bean = mapper.readValue("{\"intValue\": null}", PrimitiveBean.class);
        assertFalse(h.called, "Handler must not fire when FAIL_ON_NULL_FOR_PRIMITIVES is off");
        assertEquals(0, bean.intValue);
    }

    // 8) Handler receives the target type and a feature-referencing message.
    @Test
    public void testHandlerReceivesContext() throws Exception {
        SupplyingHandler h = new SupplyingHandler();
        ObjectMapper mapper = mapperWith(h, true);
        mapper.readValue("{\"intValue\": null}", PrimitiveBean.class);
        assertNotNull(h.lastType);
        assertEquals(Integer.TYPE, h.lastType.getRawClass(), "Target type should be primitive int");
        assertNotNull(h.lastMsg);
        assertTrue(h.lastMsg.contains("FAIL_ON_NULL_FOR_PRIMITIVES"),
                "Failure message should reference the controlling feature");
    }
}
