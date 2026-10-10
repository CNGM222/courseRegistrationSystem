"""Independent synthetic billing receiver with durable event and business-key deduplication."""
import hashlib
import json
import sqlite3
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from decimal import Decimal


database = Path(__file__).resolve().parent.parent / "target" / "mock-billing.sqlite"
database.parent.mkdir(parents=True, exist_ok=True)
with sqlite3.connect(database) as connection:
    connection.execute("CREATE TABLE IF NOT EXISTS invoices (event_id TEXT PRIMARY KEY, business_key TEXT UNIQUE, digest TEXT NOT NULL, payload TEXT NOT NULL)")


class Receiver(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, data):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != "/integration/invoices":
            return self.reply(404, {"accepted": False})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 1 <= length <= 1_000_000:
                raise ValueError()
            payload = json.loads(self.rfile.read(length))
            event = payload["eventId"]
            assert self.headers.get("Idempotency-Key") == event
            assert payload["eventType"] == "INVOICE_FINALIZED"
            total = sum((Decimal(i["amount"]) for i in payload["items"]), Decimal("0.00"))
            assert total == Decimal(payload["totalAmount"]) and total >= 0
            business_key = payload["studentId"] + ":" + payload["semesterCode"]
            canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
            digest = hashlib.sha256(canonical.encode()).hexdigest()
            with sqlite3.connect(database) as connection:
                connection.execute("BEGIN IMMEDIATE")
                old = connection.execute("SELECT event_id, digest FROM invoices WHERE event_id=? OR business_key=?", (event, business_key)).fetchone()
                if old and old != (event, digest):
                    return self.reply(409, {"accepted": False, "code": "INVOICE_CONFLICT"})
                if not old:
                    connection.execute("INSERT INTO invoices VALUES (?,?,?,?)", (event, business_key, digest, canonical))
            self.reply(200, {"accepted": True, "eventId": event})
        except (KeyError, ValueError, AssertionError):
            self.reply(422, {"accepted": False, "code": "INVALID_INVOICE"})


if __name__ == "__main__":
    print("Synthetic billing receiver: http://127.0.0.1:18081/integration/invoices")
    HTTPServer(("127.0.0.1", 18081), Receiver).serve_forever()
