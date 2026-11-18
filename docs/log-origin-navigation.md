# Log statement navigation and logging guidance

Graylog Explorer adapts to an existing logging format. No plugin-specific payload is required. Configure a project default profile or override it for a connection in **Settings → Tools → Graylog Explorer → Project settings**.

## Source semantics

An exception origin identifies a throw site. A log origin identifies an application logging call. Message construction can happen elsewhere, including exception constructors and translations. **Find log statement** is separate from exception navigation and does not create exception marks or infer runtime variable values.

Reported coordinates are evidence from the event. They do not prove that local sources match the deployment. Use the corresponding checkout/revision.

## Resolution

1. Read configured caller coordinates, optionally recognizing ECS/OpenTelemetry pairs. Never mix a file from one schema with a line from another. Reject invalid/nonpositive/overflowing lines, apply mappings and check the local file and line. A single explicit location opens directly; conflicting locations require choosing a candidate.
2. Without available explicit coordinates, consult optional language adapters. PHP supports `Class::method` and separate mapped class/function fields. Find resolved logger calls inside that method, excluding nested closures. Known Yii, PSR-3 and Monolog types are optional; custom classes/interfaces and method names can replace them.
3. Every method-derived result requires choosing a candidate. If a logger call cannot be identified, label the method declaration as a **method-only anchor**. Generic source coordinates remain separately labelled candidates and do not suppress method results.
4. **Search message in project** opens native Find in Files. The literal query uses the first message line, capped at 160 characters; a sufficiently long prefix before `: ` can omit a changing suffix. Queries shorter than eight characters are not offered. A text match can be construction or translation, and is never automatically declared a logger call.

Metadata navigation works during indexing. Method lookup asks the user to retry when indexes are ready. On-demand work runs in a background committed read action, without a project-wide text scan or a Graylog request. Selecting another event cancels stale results. Disposing the view disposes its candidate popup.

## Mapping existing fields

Defaults are convenience aliases, not a required schema:

| Meaning | Default | Alternative example |
| --- | --- | --- |
| Caller file | `log_file` | `context.caller.path` |
| Caller line | `log_line` | `context.caller.line` |
| Function/method | `log_function` | `context.method` |
| Class, if separate | `log_class` | `context.class` |
| Category | `ctxt_category` | `logger.category` |

Configured caller fields are not silently replaced by default names when absent. Exact dotted names and nested objects are supported. Paths use existing remote-to-local mappings; there is no basename guessing.

Automatic coordinate pairs are ECS `log.origin.file.name` / `log.origin.file.line`, OpenTelemetry `code.file.path` / `code.line.number`, and legacy OpenTelemetry `code.filepath` / `code.lineno`. Code attributes must describe a logging call to serve as its caller coordinates. Exception fields and stacks are never promoted to logger coordinates.

## Behavior profiles

| Setting | Default | Purpose |
| --- | --- | --- |
| Standard source fields | Enabled | Disable automatic ECS/OTel/common symbol recognition and use explicit field mappings. |
| Method/category lookup | Enabled | Disable inference and use reported coordinates only. |
| Generic source role | Source candidate | Use **Log statement coordinates** only when your producer defines that contract; use **Ignore** when generic fields mean something else. This applies to the existing configurable Source file/line fields. |
| Known PHP loggers | Enabled | Disable framework defaults when using only custom loggers. |
| Additional PHP logger classes | Empty | Comma-separated resolved class/interface names, such as `Acme\Logging\Logger`. |
| PHP logger methods | Common severity names and `log` | Replace with your own names, such as `write,publish`. No regular expressions or executable rules. |

Profiles inherit project defaults unless a connection overrides them. Local behavior changes refresh event guidance without restarting searches or rebuilding the exception index. Field mapping changes reparse events through the existing Settings flow.

## Recommendations when data is missing

The **Logging guidance** tab shows recognized coordinates, method scope, invalid metadata and paths outside configured roots. **Configure log navigation** opens project settings. Existing fields should be mapped first; instrumenting the producer is optional when the required data is absent.

**Copy logging fields example** uses the configured names and synthetic values. It never copies user IDs, messages, paths or other values from the selected event. It represents fields in Graylog search results, not a wire-ready GELF payload.

When exact navigation is needed, capture application caller coordinates in a formatter/processor. Skip logging wrappers so they do not identify themselves. Full stacks are unnecessary for INFO/DEBUG/WARN navigation. Keep exception objects/previous causes separate. Request/trace IDs support correlation; a deployment revision helps select the corresponding checkout.

An existing producer might expose the following fields:

```json
{
  "message": "Import completed",
  "caller_path": "/srv/app/src/Worker.php",
  "caller_line": 42,
  "caller_function": "App\\Worker::run",
  "request_id": "example-request",
  "service_version": "example-revision"
}
```

Map `caller_path`, `caller_line` and `caller_function` in the profile. Existing ECS/OTel payloads need no conversion. Do not add personal data or secrets for navigation.

GELF custom fields use an underscore prefix on the wire. Configure names actually received by Graylog: formatters/pipelines can prefix, flatten or rename context fields.

References: [ECS log origins](https://www.elastic.co/docs/reference/ecs/ecs-log), [OpenTelemetry code attributes](https://opentelemetry.io/docs/specs/semconv/registry/attributes/code/), [GELF format](https://go2docs.graylog.org/current/getting_in_log_data/gelf_format.html).
