import hashlib
import unittest

from tools.georelays.validate_relays import relay_host_error, validate_csv, validate_lock


def csv_with(*rows: str, filler: int = 60) -> bytes:
    lines = ["Relay URL,Latitude,Longitude", *rows]
    lines += [f"relay{i}.example.com,10.0,20.0" for i in range(filler)]
    return ("\r\n".join(lines) + "\r\n").encode("ascii")


class RelayHostTest(unittest.TestCase):
    def test_accepts_public_hosts(self) -> None:
        for host in ("relay.example.com", "nostr.example.org:443", "wss://relay.example.net"):
            self.assertIsNone(relay_host_error(host), host)

    def test_rejects_plaintext_and_other_schemes(self) -> None:
        for host in ("ws://relay.example.com", "http://relay.example.com", "https://relay.example.com"):
            self.assertIsNotNone(relay_host_error(host), host)

    def test_rejects_ip_literals_and_local_names(self) -> None:
        for host in ("192.0.2.1", "10.0.0.1:7777", "wss://[::1]", "localhost", "relay.local", "box.internal", "router"):
            self.assertIsNotNone(relay_host_error(host), host)

    def test_rejects_paths_credentials_and_bad_ports(self) -> None:
        for host in ("relay.example.com/path", "user@relay.example.com", "relay.example.com:0", "relay.example.com:99999"):
            self.assertIsNotNone(relay_host_error(host), host)


class RelayCsvTest(unittest.TestCase):
    def test_valid_file(self) -> None:
        self.assertEqual([], validate_csv(csv_with("relay.example.com,37.4,-121.9")))

    def test_rejects_bad_rows(self) -> None:
        errors = validate_csv(csv_with("ws://evil.example.com,1,2", "relay.example.com,91,0", "x.example.com,1"))
        self.assertEqual(3, len(errors), errors)

    def test_rejects_wrong_header_and_too_few_rows(self) -> None:
        self.assertTrue(validate_csv(b"url,lat,lon\n"))
        self.assertTrue(validate_csv(csv_with(filler=3)))

    def test_lock_must_pin_commit_and_digest(self) -> None:
        data = csv_with()
        digest = hashlib.sha256(data).hexdigest()
        self.assertEqual([], validate_lock({"commit": "a" * 40, "sha256": digest}, data))
        self.assertTrue(validate_lock({"commit": "main", "sha256": digest}, data))
        self.assertTrue(validate_lock({"commit": "a" * 40, "sha256": "0" * 64}, data))


if __name__ == "__main__":
    unittest.main()
