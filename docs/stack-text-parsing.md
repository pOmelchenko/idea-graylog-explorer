# Runtime stack syntax and log schemas

The log schema and the source runtime are separate dimensions. Top-level `stack_trace` fields and ECS `error.stack_trace` strings use the same Kotlin `TextStackParser`. Schema adapters select fields and preserve explicit metadata; they do not choose frame order based on the enclosing JSON shape.

`ExceptionHeaderParser` recognizes structural JVM/cause markers, then separates a type identifier from its message. Names following an uncaught-exception or cause marker need no `Exception`/`Error` suffix. Metadata can also identify a custom bare type. Unstructured bare headers remain conservative, so an ordinary line such as `INFO: request completed` does not create an exception. Unattributed frames preceding a cause are never assigned to that cause; known schema metadata may retain their parent.

Python frame and cause order is normalized only when an actual traceback header line is present. Mentioning the phrase inside a message does not select Python ordering. .NET inner-stack boundaries retain enclosing frame ownership. Explicit ECS type/message/code/origin metadata wins over a printed root; explicit structured causes take precedence over duplicate printed causes. The event document and its original stack string are not modified.

Missing or blank ECS type metadata leaves a recognized stack header's type intact; a frame-only stack still uses the generic fallback. Explicitly reporting `Exception` remains authoritative. Virtual URI filtering applies to individual locations, so a URL argument cannot hide a filesystem frame on the same line. File URL decoding is confined to the matching location.

Small built-in frame regexes still recognize runtime grammars and source-path syntax. They are compiled once and checked with synthetic fixtures. They are not application-specific logger rules. Field names, path mappings and consumer behavior belong to profiles; adding a runtime grammar belongs to a parser with representative and negative fixtures. A global user-editable regex would not describe cause ownership, frame direction or schema precedence, so no such setting is introduced here.

The fixture corpus checks top-level, native ECS and dotted ECS representations, including whole-period CSV export/hydration. Additional tests cover custom throwable names, unusual thread names, unknown parent headers, explicit metadata and cause precedence. Structured trace arrays keep their existing semantics; custom text rendering and omitted-frame reconstruction are not inferred.

References: [JDK Throwable stack format](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Throwable.html), [Python traceback chains](https://docs.python.org/3/library/traceback.html), [ECS error fields](https://www.elastic.co/docs/reference/ecs/ecs-error).
