#!/usr/bin/env python3
"""Read manually submitted Sentry diagnostics; never print raw event data."""

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request


class TriageError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Sentry:
    def __init__(self, org, project):
        token = os.environ.get("SENTRY_AUTH_TOKEN")
        if not token:
            raise TriageError("Set SENTRY_AUTH_TOKEN locally; do not paste it into chat.")
        self.headers = {"Authorization": "Bearer " + token}
        self.opener = urllib.request.build_opener(NoRedirect())
        self.org = urllib.parse.quote(org, safe="")
        self.project = "/api/0/projects/" + self.org + "/" + urllib.parse.quote(project, safe="")

    def get(self, path, params=None, limit=4 * 1024 * 1024, attachment=False):
        url = "https://sentry.io" + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        request = urllib.request.Request(url, headers=self.headers, method="GET")
        try:
            response = self.opener.open(request, timeout=30)
        except urllib.error.HTTPError as error:
            # Sentry can return a signed storage URL for attachments. Follow one
            # HTTPS hop without Authorization; metadata requests never redirect.
            if not attachment or error.code not in (301, 302, 303, 307, 308):
                raise
            location = error.headers.get("Location", "")
            parsed = urllib.parse.urlsplit(location)
            if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
                raise TriageError("Unsafe attachment redirect; download incomplete.") from None
            response = self.opener.open(urllib.request.Request(location, method="GET"), timeout=30)
        with response:
            data = response.read(limit + 1)
            if len(data) > limit:
                raise TriageError("Sentry response exceeds the download limit.")
            return data, response.headers.get("Link", "")

    def pages(self, path, params=None):
        params = dict(params or {}, per_page=100)
        seen = set()
        while True:
            data, link = self.get(path, params)
            items = json.loads(data)
            if not isinstance(items, list):
                raise TriageError("Unexpected Sentry page format; search is incomplete.")
            yield from items
            next_link = next((part for part in link.split(",") if 'rel="next"' in part), "")
            cursor = re.search(r'cursor="([^"]+)"', next_link)
            if 'results="true"' not in next_link or cursor is None:
                return
            cursor = cursor.group(1)
            if cursor in seen:
                raise TriageError("Sentry repeated a pagination cursor; search is incomplete.")
            seen.add(cursor)
            # Reuse the known endpoint, never an arbitrary URL from a response.
            params["cursor"] = cursor


def list_reports(client, days, report_id=None, now=None):
    now = now or dt.datetime.now(dt.timezone.utc)
    cutoff = now - dt.timedelta(days=days)
    reports = {}
    # This endpoint accepts only '', '24h', or '14d' for chart statistics.
    # Omit charts and search all issue groups; bound the actual events below.
    issues = client.pages(client.project + "/issues/", {"query": "", "statsPeriod": ""})
    for issue in issues:
        if issue.get("title") != "User-initiated diagnostics":
            continue
        issue_id = urllib.parse.quote(str(issue["id"]), safe="")
        path = f"/api/0/organizations/{client.org}/issues/{issue_id}/events/"
        for event in client.pages(path, {"start": cutoff.isoformat(), "end": now.isoformat()}):
            timestamp = event.get("dateCreated") or event.get("dateReceived")
            if not timestamp:
                raise TriageError("An event has no timestamp; search is incomplete.")
            observed = dt.datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
            if observed < cutoff or observed > now:
                continue
            tags = {tag["key"]: tag["value"] for tag in event.get("tags", [])}
            if report_id and tags.get("report_id") != report_id:
                continue
            event_id = event.get("eventID") or event.get("id")
            if not event_id:
                raise TriageError("An event has no ID; search is incomplete.")
            reports[event_id] = {
                "event_id": event_id,
                "timestamp": timestamp,
                **{key: tags.get(key) for key in (
                    "report_id", "app_version", "device_model", "android_version", "connection_state",
                )},
            }
    return sorted(reports.values(), key=lambda report: report["timestamp"], reverse=True)


def download(client, event_id, output_dir=None):
    if not re.fullmatch(r"[a-fA-F0-9]{32}", event_id):
        raise TriageError("Event ID must contain 32 hexadecimal characters.")
    path = client.project + f"/events/{event_id}/attachments/"
    attachments = [item for item in client.pages(path) if item.get("name") == "diagnostics.txt"]
    if not attachments:
        raise TriageError("This event has no diagnostics.txt attachment.")
    directory = Path(tempfile.mkdtemp(prefix="karoo-diagnostics-", dir=output_dir))
    saved = []
    for index, item in enumerate(attachments):
        attachment_id = urllib.parse.quote(str(item["id"]), safe="")
        data, _ = client.get(path + attachment_id + "/", {"download": "1"}, limit=1024 * 1024, attachment=True)
        target = directory / f"{event_id}-{index}-diagnostics.txt"
        fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
        saved.append(str(target))
    return saved


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--org", default="self-vof")
    parser.add_argument("--project", default="android")
    commands = parser.add_subparsers(dest="command", required=True)
    listing = commands.add_parser("list", help="List sanitized report metadata without an environment filter")
    listing.add_argument("--days", type=int, choices=range(1, 91), default=1, metavar="1..90")
    listing.add_argument("--report-id", help="Match the exact support code, not the Sentry issue ID")
    fetching = commands.add_parser("download", help="Save diagnostics.txt in a new private temporary directory")
    fetching.add_argument("event_id")
    fetching.add_argument("--output-dir", type=Path, help="Parent directory for the new private directory")
    args = parser.parse_args()
    try:
        client = Sentry(args.org, args.project)
        if args.command == "list":
            result = {"searched_days": args.days, "reports": list_reports(client, args.days, args.report_id)}
        else:
            result = {"event_id": args.event_id, "files": download(client, args.event_id, args.output_dir)}
        print(json.dumps(result, indent=2))
        return 0
    except urllib.error.HTTPError as error:
        print(f"Sentry HTTP {error.code}; check access and project configuration. Search/download incomplete.", file=sys.stderr)
    except (urllib.error.URLError, TimeoutError):
        print("Sentry connection failed; search/download incomplete.", file=sys.stderr)
    except TriageError as error:
        print(str(error), file=sys.stderr)
    except (ValueError, OSError, KeyError, TypeError) as error:
        # Do not print server bodies, request headers, or raw event fields.
        print(f"{type(error).__name__}; search/download incomplete.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
