"""Base class for online tab-site adapters (none ship enabled).

An adapter searches one site, reads its ratings, and fetches a tab as a file
the app can import (Guitar Pro, MusicXML, MIDI or plain-text tab). Enable one
by listing "module:Class" under "adapters" in ~/.config/neckscape-mcp/config.json.

Before writing or enabling an adapter, read the site's terms of use: many tab
sites forbid scraping or automated downloads, and user-submitted tabs of
recorded songs may not be licensed for redistribution. Prefer sites with an
official API or an explicit licence (e.g. public-domain or Creative Commons
collections).
"""
from __future__ import annotations

import urllib.request

MAX_BYTES = 8 * 1024 * 1024
TIMEOUT = 15


class TabSiteAdapter:
    #: hostnames this adapter fetches from — nothing else is ever requested
    hosts: tuple[str, ...] = ()

    def __init__(self, config: dict):
        self.config = config

    def handles(self, url: str) -> bool:
        host = url.split("://", 1)[-1].split("/", 1)[0].lower()
        return any(host == h or host.endswith("." + h) for h in self.hosts)

    def search(self, song: str, artist: str | None, part: str | None) -> list[dict]:
        """Candidates: [{"url", "title", "artist", "format", "rating", "score"}], best first."""
        raise NotImplementedError

    def fetch(self, url: str) -> tuple[bytes, str, dict]:
        """(file bytes, extension like ".gp5", {"title", "artist", "author", "rating", "source_url"})."""
        raise NotImplementedError

    def get(self, url: str) -> bytes:
        """HTTPS GET with the size limit and timeout; only for this adapter's hosts."""
        if not url.startswith("https://") or not self.handles(url):
            raise ValueError(f"{url} isn't one of {self.hosts}")
        req = urllib.request.Request(url, headers={"User-Agent": "neckscape-mcp/1.0"})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            data = r.read(MAX_BYTES + 1)
        if len(data) > MAX_BYTES:
            raise ValueError("response over 8 MB")
        return data
