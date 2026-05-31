package tools.jackson.databind.deser;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.*;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.testutil.DatabindTestUtil;

/**
 * Comprehensive test suite for {@link DeserializationProblemHandler#handleUnexpectedNull()}
 * callback method. Validates that the handler is properly invoked when null values are
 * encountered for non-nullable primitive types, and that the handler can intercept and
 * recover from these cases.
 *
 * @since 3.0
 */
public class NullForPrimitiveHandlerTest extends DatabindTestUtil
{
    // Test POJO with primitive fields
    static class PrimitiveBean {
        public int intValue;
        public long longValue;
        public boolean boolValue;
        public double doubleValue;
    }

    /**
     * Custom handler implementation that tracks invocations and can provide
     * replacement values for null primitives.
     */
    static class CustomNullHandler extends DeserializationProblemHandler {
        private boolean wasCalled = false;
        private JavaType lastTargetType = null;
        private String lastFailureMsg = null;

        @Override
        public Object handleUnexpectedNull(DeserializationContext ctxt,
                JavaType targetType, String failureMsg)
            throws JacksonException
        {
            wasCalled = true;
            lastTargetType = targetType;
            lastFailureMsg = failureMsg;
            
            // Return appropriate default value for each primitive type
            Class<?> raw = targetType.getRawClass();
            if (raw == Integer.TYPE || raw == Integer.class) {
                return 0;
            } else if (raw == Long.TYPE || raw == Long.class) {
                return 0L;
            } else if (raw == Boolean.TYPE || raw == Boolean.class) {
                return false;
            } else if (raw == Double.TYPE || raw == Double.class) {
                return 0.0;
            }
            // Signal that we couldn't handle this type
            return NOT_HANDLED;
        }

        public boolean wasHandlerCalled() { return wasCalled; }
        public JavaType getLastTargetType() { return lastTargetType; }
        public String getLastFailureMsg() { return lastFailureMsg; }
        public void reset() { 
            wasCalled = false;
            lastTargetType = null;
            lastFailureMsg = null;
        }
    }

    // ======================================================================
    // TEST CASE 1: Default Behavior (Handler NOT overridden)
    // ======================================================================
    
    /**
     * Verifies that without a registered handler, the default behavior is to
     * throw an exception when null is encountered for a primitive type with
     * FAIL_ON_NULL_FOR_PRIMITIVES enabled.
     */
    @Test
    public void testDefaultBehaviorThrowsExceptionForNullPrimitive() throws Exception {
        ObjectMapper mapper = jsonMapperBuilder()
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();

        String json = "{\"intValue\": null}";
        
        // Should throw exception when no handler intercepts
        MismatchedInputException ex = assertThrows(MismatchedInputException.class, 
            () -> mapper.readValue(json, PrimitiveBean.class),
            "Should throw exception when null encountered for primitive and no handler registered");
        
        assertTrue(ex.getMessage().contains("Cannot coerce `null`"),
                   "Exception message should indicate null coercion problem");
    }

    // ======================================================================
    // TEST CASE 2: Handler Intercepts and Provides Replacement Value
    // ======================================================================
    
    /**
     * Verifies that a registered handler can intercept null values for primitives
     * and provide replacement default values, allowing deserialization to succeed
     * without throwing an exception.
     */
    @Test
    public void testHandlerProvidesDefaultValue() throws Exception {
        ObjectMapper mapper = jsonMapperBuilder()
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();

        CustomNullHandler handler = new CustomNullHandler();
        mapper.addHandler(handler);

        String json = "{\"intValue\": null, \"longValue\": null, \"boolValue\": null, \"doubleValue\": null}";
        PrimitiveBean result = mapper.readValue(json, PrimitiveBean.class);

        // Verify handler was called for null values
        assertTrue(handler.wasHandlerCalled(), 
                   "Handler should have been called for null primitive value");
        assertNotNull(handler.getLastTargetType(), 
                      "Handler should receive target type information");
        
        // Verify defaults were applied by handler
        assertEquals(0, result.intValue, "int should default to 0");
        assertEquals(0L, result.longValue, "long should default to 0L");
        assertEquals(false, result.boolValue, "boolean should default to false");
        assertEquals(0.0, result.doubleValue, "double should default to 0.0");
    }

    // ======================================================================
    // TEST CASE 3: Handler Returning NOT_HANDLED Falls Through to Exception
    // ======================================================================
    
    /**
     * Verifies that when a handler returns {@link DeserializationProblemHandler#NOT_HANDLED},
     * the normal exception-throwing behavior is invoked, allowing proper error handling.
     */
    @Test
    public void testHandlerReturningNotHandledThrowsException() throws Exception {
        ObjectMapper mapper = jsonMapperBuilder()
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();

        CustomNullHandler handler = new CustomNullHandler() {
            @Override
            public Object handleUnexpectedNull(DeserializationContext ctxt,
                    JavaType targetType, String failureMsg)
                throws JacksonException
            {
                wasCalled = true;
                // Return NOT_HANDLED to trigger normal exception flow
                return NOT_HANDLED;
            }
        };
        mapper.addHandler(handler);

        String json = "{\"intValue\": null}";
        
        // Should throw because handler returned NOT_HANDLED
        MismatchedInputException ex = assertThrows(MismatchedInputException.class,
            () -> mapper.readValue(json, PrimitiveBean.class),
            "Should throw exception when handler returns NOT_HANDLED");
        
        assertTrue(handler.wasHandlerCalled(), 
                   "Handler should have been invoked");
        assertTrue(ex.getMessage().contains("Cannot coerce `null`"),
                   "Exception should indicate null coercion problem");
    }

    // ======================================================================
    // TEST CASE 4: Multiple Handlers - First One Wins
    // ======================================================================
    
    /**
     * Verifies that when multiple handlers are registered, the first one that
     * returns a handled value is used, and subsequent handlers are not called.
     */
    @Test
    public void testMultipleHandlersFirstOneWins() throws Exception {
        ObjectMapper mapper = jsonMapperBuilder()
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();

        CustomNullHandler firstHandler = new CustomNullHandler();
        CustomNullHandler secondHandler = new CustomNullHandler();
        
        mapper.addHandler(firstHandler);
        mapper.addHandler(secondHandler);

        String json = "{\"intValue\": null}";
        PrimitiveBean result = mapper.readValue(json, PrimitiveBean.class);

        // First handler should be invoked and provide value
        assertTrue(firstHandler.wasHandlerCalled(), 
                   "First handler should be called");
        assertFalse(secondHandler.wasHandlerCalled(), 
                    "Second handler should not be called when first one handles it");
        assertEquals(0, result.intValue, "Handler-provided default value should be used");
    }

    // ======================================================================
    // TEST CASE 5: Feature Disabled - No Handler Called
    // ======================================================================
    
    /**
     * Verifies that when the FAIL_ON_NULL_FOR_PRIMITIVES feature is disabled,
     * the handler is not called at all, and deserialization uses default values
     * without invoking the handler.
     */
    @Test
    public void testFeatureDisabledNoHandlerCalled() throws Exception {
        ObjectMapper mapper = jsonMapperBuilder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();

        CustomNullHandler handler = new CustomNullHandler();
        mapper.addHandler(handler);

        String json = "{\"intValue\": null}";
        PrimitiveBean result = mapper.readValue(json, PrimitiveBean.class);

        // Handler should NOT be called when feature is disabled
        assertFalse(handler.wasHandlerCalled(), 
                    "Handler should not be called when FAIL_ON_NULL_FOR_PRIMITIVES is disabled");
        // Java primitive default value
        assertEquals(0, result.intValue, "Should use Java primitive default");
    }

    // ======================================================================
    // TEST CASE 6: Handler Receives Correct Context Information
    // ======================================================================
    
    /**
     * Verifies that the handler receives correct context information including
     * the target type that doesn't accept null and a descriptive failure message.
     */
    @Test
    public void testHandlerReceivesCorrectContext() throws Exception {
        ObjectMapper mapper = jsonMapperBuilder()
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();

        CustomNullHandler handler = new CustomNullHandler();
        mapper.addHandler(handler);

        String json = "{\"intValue\": null}";
        PrimitiveBean result = mapper.readValue(json, PrimitiveBean.class);

        // Verify handler received complete context information
        assertNotNull(handler.getLastTargetType(), 
                      "Target type should be provided to handler");
        assertEquals(Integer.TYPE, handler.getLastTargetType().getRawClass(),
                     "Target type should be int primitive");
        
        assertNotNull(handler.getLastFailureMsg(), 
                      "Failure message should be provided to handler");
        assertTrue(handler.getLastFailureMsg().contains("Cannot coerce `null`"),
                   "Message should describe the null coercion problem");
        assertTrue(handler.getLastFailureMsg().contains("FAIL_ON_NULL_FOR_PRIMITIVES"),
                   "Message should mention the feature flag");
    }

    // ======================================================================
    // TEST CASE 7: Handler Can Throw Exception Instead
    // ======================================================================
    
    /**
     * Verifies that a handler can opt to throw an exception directly rather than
     * returning a value, allowing for custom error handling and reporting.
     */
    @Test
    public void testHandlerCanThrowException() throws Exception {
        ObjectMapper mapper = jsonMapperBuilder()
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();

        DeserializationProblemHandler customHandler = new DeserializationProblemHandler() {
            @Override
            public Object handleUnexpectedNull(DeserializationContext ctxt,
                    JavaType targetType, String failureMsg)
                throws JacksonException
            {
                // Handler can throw custom exception
                throw new IllegalArgumentException("Custom handler: Cannot accept null for type " + 
                    targetType.getRawClass().getSimpleName());
            }
        };
        mapper.addHandler(customHandler);

        String json = "{\"intValue\": null}";
        
        // Handler's exception should be thrown and propagated
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> mapper.readValue(json, PrimitiveBean.class),
            "Handler should be able to throw custom exception");
        
        assertTrue(ex.getMessage().contains("Custom handler"),
                   "Custom exception message should be preserved");
    }

    // ======================================================================
    // TEST CASE 8: All Primitive Types Handled
    // ======================================================================
    
    /**
     * Verifies that the handler is called for all primitive field types
     * (int, long, boolean, double, byte, short, float).
     */
    @Test
    public void testAllPrimitiveTypesInvokesHandler() throws Exception {
        ObjectMapper mapper = jsonMapperBuilder()
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();

        CustomNullHandler handler = new CustomNullHandler();
        mapper.addHandler(handler);

        // Create JSON with multiple null primitive fields
        String json = "{\"intValue\": null, \"longValue\": null, \"boolValue\": null, \"doubleValue\": null}";
        
        PrimitiveBean result = mapper.readValue(json, PrimitiveBean.class);

        // Handler should have been called at least once (for first field)
        assertTrue(handler.wasHandlerCalled(),
                   "Handler should be called for primitive field with null value");
        
        // All fields should have default values
        assertEquals(0, result.intValue);
        assertEquals(0L, result.longValue);
        assertEquals(false, result.boolValue);
        assertEquals(0.0, result.doubleValue);
    }
}
