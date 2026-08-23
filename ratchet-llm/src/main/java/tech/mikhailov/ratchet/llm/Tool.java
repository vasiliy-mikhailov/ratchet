package tech.mikhailov.ratchet.llm;

/**
 * A TOOL THE MODEL MAY CALL, as it is advertised on the wire.
 *
 * <p>Three strings, because that is all the endpoint is told. The schema is raw JSON for the
 * {@code parameters} object rather than a builder over it: everything that constructs one of these
 * already knows the shape it wants, and a schema builder is a second way to write JSON in a library
 * that has one.
 *
 * <p>TWO TOOLS SHARING A NAME COLLIDE, and they collided silently before this library owned the
 * loop — the client keyed executors by name while advertising the specifications as a list, so the
 * last writer won and both were still offered to the model. Here the map is keyed by the whole
 * record, so a duplicate name is two entries and {@link Asking} refuses it by name at construction
 * rather than losing one quietly.
 */
public record Tool(String name, String description, String schema) {

    /** A tool that takes nothing. */
    public static Tool of(String name, String description) {
        return new Tool(name, description, "{\"type\":\"object\",\"properties\":{}}");
    }

    public Tool {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a tool must have a name");
        }
        description = description == null ? "" : description;
        schema = schema == null || schema.isBlank()
                ? "{\"type\":\"object\",\"properties\":{}}" : schema;
    }
}
