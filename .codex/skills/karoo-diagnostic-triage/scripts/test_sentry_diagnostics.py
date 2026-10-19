import datetime as dt
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
import urllib.error
from unittest.mock import MagicMock, patch

import sentry_diagnostics as triage


class DiagnosticHelperTests(unittest.TestCase):
    def client(self):
        with patch.dict(os.environ, {"SENTRY_AUTH_TOKEN": "test-token"}):
            return triage.Sentry("example", "android")

    def test_pagination_uses_cursor_and_never_adds_an_environment(self):
        client = self.client()
        calls = []

        def get(path, params):
            calls.append((path, dict(params)))
            if "cursor" not in params:
                return b'[1]', '<https://other.invalid/>; rel="next"; results="true"; cursor="next-page"'
            return b'[2]', ''

        client.get = get
        self.assertEqual([1, 2], list(client.pages("/known/", {"query": ""})))
        self.assertEqual(["/known/", "/known/"], [path for path, _ in calls])
        self.assertEqual("next-page", calls[1][1]["cursor"])
        self.assertTrue(all("environment" not in params for _, params in calls))
        self.assertEqual("", calls[0][1]["query"])

    def test_repeated_pagination_cursor_is_not_reported_as_complete(self):
        client = self.client()
        client.get = lambda *_: (b'[]', '<https://sentry.io/>; rel="next"; results="true"; cursor="same"')
        with self.assertRaises(triage.TriageError):
            list(client.pages("/known/"))

    def test_matching_is_exact_deduplicated_and_excludes_server_user_data(self):
        client = self.client()
        now = dt.datetime(2026, 10, 19, 18, tzinfo=dt.timezone.utc)

        def event(code, timestamp="2026-10-19T16:50:50Z"):
            return {"eventID": code * 2, "dateCreated": timestamp,
                    "tags": [{"key": "report_id", "value": code},
                             {"key": "app_version", "value": "0.5.0"}],
                    "user": {"ip_address": "private-value", "email": "private-value"}}

        def pages(path, params):
            self.assertNotIn("environment", params)
            if path.endswith("/issues/"):
                self.assertEqual("", params["query"])
                self.assertEqual("", params["statsPeriod"])
                return [{"id": "1", "title": "User-initiated diagnostics", "status": "resolved"},
                        {"id": "2", "title": "Other error"}]
            self.assertIn("/issues/1/events/", path)
            self.assertEqual((now - dt.timedelta(days=1)).isoformat(), params["start"])
            self.assertEqual(now.isoformat(), params["end"])
            return [event("abc"), event("abc"), event("abcd"),
                    event("abc", "2026-10-10T16:50:50Z")]

        client.pages = pages
        reports = triage.list_reports(client, 1, "abc", now)
        self.assertEqual(1, len(reports))
        self.assertEqual("abc", reports[0]["report_id"])
        self.assertEqual("0.5.0", reports[0]["app_version"])
        self.assertNotIn("private-value", json.dumps(reports))

    def test_attachment_download_is_private_and_preserves_contents(self):
        client = self.client()
        client.pages = lambda _: [{"id": "123", "name": "diagnostics.txt"},
                                  {"id": "456", "name": "other.txt"}]
        calls = []

        def get(path, params, limit, attachment):
            self.assertTrue(attachment)
            calls.append((path, params))
            return b"diagnostic log\n", ""

        client.get = get
        with tempfile.TemporaryDirectory() as parent:
            files = triage.download(client, "a" * 32, parent)
            self.assertEqual(1, len(files))
            target = Path(files[0])
            self.assertEqual(b"diagnostic log\n", target.read_bytes())
            self.assertEqual(0o600, stat.S_IMODE(target.stat().st_mode))
            self.assertEqual(0o700, stat.S_IMODE(target.parent.stat().st_mode))
            self.assertTrue(calls[0][0].endswith("/attachments/123/"))
            self.assertEqual({"download": "1"}, calls[0][1])

    def test_missing_attachment_is_explicit_and_invalid_event_id_is_rejected(self):
        client = self.client()
        client.pages = lambda _: []
        with self.assertRaisesRegex(triage.TriageError, "no diagnostics.txt"):
            triage.download(client, "a" * 32)
        with self.assertRaisesRegex(triage.TriageError, "32 hexadecimal"):
            triage.download(client, "../other-event")

    def test_redirects_are_not_followed_with_credentials(self):
        handler = triage.NoRedirect()
        self.assertIsNone(handler.redirect_request(None, None, 302, "", {}, "https://other.invalid"))

    def test_signed_attachment_redirect_does_not_receive_the_auth_token(self):
        client = self.client()
        response = MagicMock()
        response.read.return_value = b"log"
        response.headers = {}
        redirect = urllib.error.HTTPError("https://sentry.io/", 302, "", {
            "Location": "https://storage.example/log?signature=private",
        }, None)
        client.opener = MagicMock()
        client.opener.open.side_effect = [redirect, response]
        self.assertEqual(b"log", client.get("/attachment/", attachment=True)[0])
        calls = client.opener.open.call_args_list
        self.assertIsNotNone(calls[0].args[0].get_header("Authorization"))
        self.assertIsNone(calls[1].args[0].get_header("Authorization"))

    def test_metadata_redirect_is_not_followed(self):
        client = self.client()
        client.opener = MagicMock()
        client.opener.open.side_effect = urllib.error.HTTPError("https://sentry.io/", 302, "", {}, None)
        with self.assertRaises(urllib.error.HTTPError):
            client.get("/metadata/")
        self.assertEqual(1, client.opener.open.call_count)


if __name__ == "__main__":
    unittest.main()
