#!/usr/bin/env python3
"""Local Graylog API fixture for trying the plugin without a real Graylog server."""
import argparse
import base64
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import csv
from collections import Counter
import io
from pathlib import Path
import re
from urllib.parse import parse_qs, urlparse

TOKEN = "demo-token"
STREAMS = [
    {"id": "000000000000000000000001", "title": "API"},
    {"id": "000000000000000000000002", "title": "Workers"},
    {"id": "000000000000000000000003", "title": "Inventory"},
]


def fixture_events():
    now = datetime.now(timezone.utc)
    rows = [
        (-8, 6, "demo-api", "POST /orders received", 0),
        (-7, 6, "demo-api", "Checkout started for order #1842", 0),
        (-6, 6, "demo-worker", "Reservation task queued", 1),
        (-5, 6, "inventory", "Stock reservation started", 2),
        (-3, 3, "demo-api", "Inventory request timed out", 0),
        (-2, 4, "demo-api", "Retry scheduled in 5 seconds", 0),
        (-1, 6, "demo-worker", "Retry task enqueued", 1),
    ]
    events = []
    for index, (delta, level, source, message, stream) in enumerate(rows):
        fields = {"_id": str(index), "timestamp": (now + timedelta(seconds=delta)).isoformat().replace("+00:00", "Z"),
                  "level": level, "source": source, "message": message, "request_id": "abc-123", "order_id": 1842,
                  "environment": "stage", "streams": [STREAMS[stream]["id"]]}
        if level == 3:
            fields.update({"file": "/application/src/StockClient.php", "line": 87,
                           "exception": "InventoryUnavailableException",
                           "stack_trace": "InventoryUnavailableException: Inventory request timed out\n"
                                          "#0 /application/src/StockClient.php(87): request()\n"
                                          "#1 /application/src/OrderService.php(142): reserveInventory()\n"
                                          "#2 /application/src/CheckoutController.php(56): placeOrder()"})
        events.append({"index": "graylog_demo_0", "message": fields})
    return events


def matches_query(fields, query):
    query = query.strip()
    def split(operator):
        depth = 0; quoted = False; escaped = False; start = 0; parts = []; index = 0
        while index < len(query):
            char = query[index]
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = not quoted
            elif not quoted:
                if char == "(": depth += 1
                elif char == ")": depth -= 1
                elif depth == 0 and query.startswith(operator, index):
                    parts.append(query[start:index]); index += len(operator); start = index; continue
            index += 1
        return parts + [query[start:]]
    for operator, combine in [(" AND ", all), (" OR ", any)]:
        parts = split(operator)
        if len(parts) > 1:
            return combine(matches_query(fields, part) for part in parts)
    if query.startswith("(") and query.endswith(")"):
        return matches_query(fields, query[1:-1])
    if query in {"", "*"}: return True
    exact = re.fullmatch(r'([\w.]+):("(?:\\.|[^"\\])*"|[\w.-]+)', query)
    if exact:
        field, value = exact.groups()
        value = json.loads(value) if value.startswith('"') else value
        return str(fields.get(field, "")) == value
    return query.strip('"').casefold() in json.dumps(fields).casefold()


class Handler(BaseHTTPRequestHandler):
    events = fixture_events()

    def reply(self, status, value, content_type="application/json"):
        body = json.dumps(value).encode() if content_type == "application/json" else value.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        expected = "Basic " + base64.b64encode((TOKEN + ":token").encode()).decode()
        if self.headers.get("Authorization") != expected:
            self.reply(401, {"message": "Use demo-token as your access token"})
            return
        url = urlparse(self.path)
        if url.path == "/api/streams":
            self.reply(200, {"streams": STREAMS, "total": len(STREAMS)})
        elif url.path in {"/api/search/universal/absolute", "/api/search/universal/absolute/export"}:
            params = {key: values[0] for key, values in parse_qs(url.query).items()}
            found = list(self.events)
            stream = params.get("filter", "").removeprefix("streams:")
            if stream:
                found = [event for event in found if stream in event["message"]["streams"]]
            query = params.get("query", "*")
            found = [event for event in found if matches_query(event["message"], query)]
            for field, compare in [("from", lambda time, bound: time >= bound), ("to", lambda time, bound: time <= bound)]:
                if field in params:
                    bound = datetime.fromisoformat(params[field].replace("Z", "+00:00"))
                    found = [event for event in found if compare(datetime.fromisoformat(event["message"]["timestamp"].replace("Z", "+00:00")), bound)]
            found.sort(key=lambda event: event["message"]["timestamp"], reverse=True)
            if url.path.endswith("/export"):
                fields = params["fields"].split(",")
                output = io.StringIO(newline="")
                writer = csv.writer(output, quoting=csv.QUOTE_ALL)
                writer.writerow(fields)
                for event in found:
                    writer.writerow([str(event["message"].get(field, "")).replace("\n", "\\\\n").replace("\r", "\\\\r") for field in fields])
                self.reply(200, output.getvalue(), "text/csv")
            else:
                offset = int(params.get("offset", "0")); limit = int(params.get("limit", "100"))
                self.reply(200, {"total_results": len(found), "messages": found[offset:offset + limit],
                                 "from": params.get("from"), "to": params.get("to")})
        else:
            self.reply(404, {"message": "This local fixture only implements read-only search, export and streams endpoints."})

    def log_message(self, fmt, *args):
        pass  # Queries and authorization are deliberately not logged.

    def do_POST(self):
        expected = "Basic " + base64.b64encode((TOKEN + ":token").encode()).decode()
        if self.headers.get("Authorization") != expected:
            self.reply(401, {"message": "Unauthorized"}); return
        if urlparse(self.path).path != "/api/search/aggregate":
            self.reply(404, {"message": "Unknown aggregation endpoint"}); return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        field = body["group_by"][0]["field"]; limit = body["group_by"][0]["limit"]
        window = body["timerange"]
        start = datetime.fromisoformat(window["from"].replace("Z", "+00:00")); end = datetime.fromisoformat(window["to"].replace("Z", "+00:00"))
        found = [event["message"] for event in self.events if matches_query(event["message"], body.get("query", "*"))
                 and (not body.get("streams") or set(body["streams"]) & set(event["message"]["streams"]))
                 and start <= datetime.fromisoformat(event["message"]["timestamp"].replace("Z", "+00:00")) <= end]
        counts = Counter(str(event[field]) for event in found if field in event)
        self.reply(200, {"schema": [{"column_type": "grouping", "field": field}, {"column_type": "metric", "function": "count"}],
                         "datarows": [[name, count] for name, count in counts.most_common(limit)],
                         "metadata": {"effective_timerange": window}})


def create_demo_project(root):
    src = root / "src"
    src.mkdir(parents=True, exist_ok=True)
    for file, line, code in [
        ("StockClient.php", 87, "        throw new InventoryUnavailableException('Inventory request timed out');"),
        ("OrderService.php", 142, "        $stockClient->request();"),
        ("CheckoutController.php", 56, "        $orderService->reserveInventory();"),
    ]:
        path = src / file
        if not path.exists():
            lines = ["<?php", "", "// Local demonstration source for Graylog Explorer."]
            lines += [""] * (line - len(lines) - 1)
            lines += [code, ""]
            if file == "StockClient.php":
                lines += [""] * (103 - len(lines) - 1)
                lines += ["        throw new DeferredReservationException('Older reservation failed');", ""]
            path.write_text("\n".join(lines))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=19000)
    parser.add_argument("--demo-project", type=Path, help="Optionally create example source files for source navigation")
    parser.add_argument("--extra-events", type=int, default=0, help="Include older events and an exception beyond the first page")
    parser.add_argument("--yii-event", action="store_true", help="Include a JSON-encoded Yii exception with a previous cause")
    args = parser.parse_args()
    if args.demo_project:
        create_demo_project(args.demo_project)
    if args.yii_event:
        fixture = Path(__file__).resolve().parents[1] / "src/test/resources/yii-exception.json"
        fields = json.loads(fixture.read_text())
        chain = json.loads(fields["ctxt_exception"])
        chain["previous"]["message"] = "Phone number validation failed."
        chain["previous"]["previous"] = {
            "class": "Demo\\Domain\\PhoneNumberInvalidException",
            "message": "Invalid phone: +12025550123", "code": 0,
            "file": "/application/src/Domain/Phone.php:46",
            "trace": ["/application/src/Domain/Phone.php:26", "/application/src/Profile/PhoneService.php:87"],
        }
        fields["ctxt_exception"] = json.dumps(chain)
        fields.update({"_id": "yii-error", "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                       "source": "demo-api", "request_id": "abc-123", "streams": [STREAMS[0]["id"]]})
        Handler.events.append({"index": "graylog_demo_0", "message": fields})
        if args.demo_project:
            for filename, line, code in [
                ("src/Graphql/Mutation.php", 59, "throw new UserError($message, 723, $previous);"),
                ("src/Profile/PhoneService.php", 91, "throw new PhoneInvalidException();"),
                ("src/Domain/Phone.php", 46, "throw new PhoneNumberInvalidException($raw);"),
            ]:
                path = args.demo_project / filename
                path.parent.mkdir(parents=True, exist_ok=True)
                if not path.exists():
                    lines = ["<?php"] + [""] * (line - 2) + [code, ""]
                    path.write_text("\n".join(lines))
    if args.demo_project and args.yii_event:
        phone = args.demo_project / "src/Domain/Phone.php"
        header = ["<?php", "declare(strict_types=1);", "namespace Demo\\Domain;",
                  "use Demo\\Domain\\PhoneNumberInvalidException;",
                  "final class Phone {", "    public static function fromE164(string $raw): void {",
                  "        // An exception value from the selected Graylog event is shown at its argument."]
        phone.write_text("\n".join(header + [""] * (45 - len(header)) + ["        throw new PhoneNumberInvalidException($raw);", "    }", "}", ""]))
        exception = args.demo_project / "src/Domain/PhoneNumberInvalidException.php"
        exception.parent.mkdir(parents=True, exist_ok=True)
        exception.write_text("<?php\nnamespace Demo\\Domain;\n"
                             "abstract class BasePhoneException extends \\RuntimeException {}\n"
                             "final class PhoneNumberInvalidException extends BasePhoneException {\n"
                             "    public function __construct(string $rawPhone) {\n"
                             "        $this->reason('phone.invalid');\n"
                             "        parent::__construct(sprintf('Invalid phone: %s', $rawPhone));\n    }\n"
                             "    private function reason(string $reason): void {}\n}\n")
        cases = Path(__file__).resolve().parents[1] / "src/test/resources/php-variable-cases.php"
        (args.demo_project / "variable-cases.php").write_text(cases.read_text())
    if args.extra_events:
        now = datetime.now(timezone.utc)
        for index in range(args.extra_events):
            fields = {"_id": f"older-{index}", "timestamp": (now - timedelta(seconds=100 + index)).isoformat().replace("+00:00", "Z"),
                      "level": 6, "source": "demo-api", "message": "Older application event", "request_id": "abc-123",
                      "streams": [STREAMS[0]["id"]]}
            if index == args.extra_events - 1:
                fields.update({"level": 3, "source": "archive-worker", "message": "Older reservation failed", "exception_class": "DeferredReservationException",
                               "exception_file": "/application/src/StockClient.php", "exception_line": 103})
            Handler.events.append({"index": "graylog_demo_0", "message": fields})
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Demo API: http://127.0.0.1:{server.server_port}/api", flush=True)
    print(f"Demo access token: {TOKEN}", flush=True)
    print("The fixture supports simple text / exact field queries, not the full Graylog query language.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
