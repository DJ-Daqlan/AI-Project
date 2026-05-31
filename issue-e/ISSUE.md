# Issue: `DeserializationProblemHandler` has no callback for null values on non-nullable types

## Observed behaviour

`DeserializationProblemHandler` is Jackson's extension point for customising how deserialisation problems are handled — for example, reacting to unknown properties or unexpected tokens. However, it provides **no callback for the case where a `null` value is encountered for a type that does not accept nulls**.

Applications that need custom null-handling behaviour cannot intercept this case via the handler. Instead, they are forced to use less targeted workarounds such as custom deserialisers or post-processing hooks.

**Example scenario:**

```java
ObjectMapper mapper = new ObjectMapper();
mapper.addHandler(new DeserializationProblemHandler() {
    // There is currently no method to override for null-value problems.
    // If `null` is encountered for a primitive int field, for instance,
    // this handler has no way to intercept it and supply a default value.
});

// With FAIL_ON_NULL_FOR_PRIMITIVES enabled (or similar), this throws —
// but the handler is never consulted.
mapper.readValue("{\"count\": null}", MyBean.class);
```

## Expected behaviour

`DeserializationProblemHandler` should include a new callback method that is invoked when a `null` value is encountered for a type that does not permit nulls. The callback should:

- Receive enough context to identify the target type and the deserialisation context.
- Allow the handler to supply a replacement (non-null) value, or to signal that the problem should be escalated as normal.
- Have a sensible default implementation so that existing `DeserializationProblemHandler` subclasses are not broken (i.e., the default should preserve current behaviour).

## Notes

- Study the existing callback methods on `DeserializationProblemHandler` to understand the pattern — the new method should follow the same conventions.
- Identify where null values for non-nullable types are currently detected during deserialisation; that is where the new callback must be invoked.
- The default return value of the new method should cause Jackson to behave exactly as it does today (no behaviour change unless the handler overrides the method).
- New tests covering both the default (handler not overridden) and override cases are expected as part of the fix.
