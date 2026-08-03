# API citizenship notes

Changes on this branch reduce the load and the side effects this tool imposes on the two third-party
services it talks to: **Hardcover** (GraphQL API, authenticated with the user's personal token) and
**libro.fm** (the mobile app's private API).

Nothing here changes what the tool is for. It still downloads the user's own purchased, DRM-free
library using their own credentials.

---

## Hardcover

### 1. Edition creation is now idempotent (the important one)

`App.syncOwned()` and `App.syncWishlistToConnector()` both create *new editions in Hardcover's shared
book database* for any owned/wishlisted book whose ISBN isn't found there.

Previously, the owned path decided what to create purely from "does an ISBN lookup find it?", and
recorded nothing afterwards. The wishlist path recorded a row only when creation returned an edition
that carried an ISBN back.

That means any `insert_edition` that did not immediately become queryable by `isbn_13` — held for
moderation, silently rejected, a locked book, an ISBN normalized differently on Hardcover's side —
caused **a fresh duplicate edition to be written on every sync, forever, unattended**. With
`SYNC_INTERVAL=h` that is hourly, per affected book, per user running the container.

Both paths now share `App.createMissingEditions()`, which:

- skips any ISBN it has attempted before, successfully or not;
- records the attempt in a new `tracker_created_edition` table (migration `3.sqm`) on every outcome,
  including failure, so a failed insert is not retried on the next pulse;
- catches and logs failures per book rather than aborting the whole sync;
- resolves book details from libro.fm only for ISBNs that pass the guard, so the guard saves
  libro.fm requests too.

It also fixes a related gap: `SKIP_TRACKING_ISBNS` was only applied to the "mark owned" path, so an
ISBN the user explicitly excluded could still have an edition created for it on Hardcover. It is now
applied to edition creation as well.

### 2. Client-side rate limiting and 429 backoff

New `RateLimitInterceptor` paces outbound Hardcover requests (~40/min, under their published limit)
and honours `Retry-After` on a 429 instead of continuing at full speed. A first sync of a large
library previously issued one mutation per book back to back with nothing bounding the rate.

### 3. Removed a retry that could only ever fail

`AuthorizationInterceptor` re-fired every 401 once, immediately, with the same token and no backoff.
It could not succeed — it just doubled every failing request for an already-bad token. The 401 is now
surfaced to the caller.

### 4. Batched ISBN lookups

`getEditions()` passed the entire library into a single `_in` filter — a 2000-book library produced a
2000-element `_in`, twice per sync. Now chunked into 100s.

### 5. Safer default sync mode

`hardcoverSyncMode` fell back to `TrackerSyncMode.ALL` — the mode the README itself flags as "CAREFUL
as this can cause double syncs" — when the option group was absent. It now falls back to the
documented default, `LIBRO_OWNED_TO_HARDCOVER`.

---

## libro.fm

### 6. Overlapping full syncs are no longer possible

`GET /update` is unauthenticated, bound to `0.0.0.0`, and `?overwrite=true` re-downloads the entire
library. Because it is a side-effecting GET, any page the user visits can trigger it with a plain
`<img src="http://localhost:8080/update?overwrite=true">`.

There was no in-flight guard, so repeated calls stacked concurrent full syncs on top of each other.
`fullUpdate()` now takes a `Mutex` and concurrent requests no-op with a log line rather than queueing
another pass over the whole library.

**Still open:** the endpoint remains unauthenticated and reachable by cross-site GET. Adding an
optional shared secret and moving mutation to POST would close it properly, but both are breaking
changes for anyone using the documented webhook, so that call belongs to the maintainer.

### 7. Self-identifying User-Agent by default

The image shipped `User-Agent=okhttp/5.3.2` alongside `X-LibroFm-AppVer`, making traffic
indistinguishable from the official Android app in libro.fm's logs. The default is now
`librofm-downloader (+https://github.com/burntcookie90/librofm-downloader)`.

`X-LibroFm-AppVer` is left in place because the v10 endpoints appear to require it, and the value
stays overridable via `LIBRO_FM_HEADERS`. This is the one change here that is a policy decision
rather than a straight improvement: it gives libro.fm the ability to see, rate-limit, or contact this
client instead of having to guess. It also means they *can* block it, which is the point.

### 8. Zip Slip containment

`downloadMp3s()` extracted archive entries with `targetDirectory.toPath() / entry.name` and no
containment check — an entry named `../../…` would write outside the media directory. Entries are now
normalized and required to stay under the target root.

`downloadM4b()` similarly built a filename out of the `response-content-disposition` query parameter
with no sanitization; it now takes only the final path segment. Two `!!`s that crashed the download on
an unexpected URL shape were replaced with real errors.

These need a libro.fm-side compromise to exploit, since the archives come from their signed CDN URLs
over TLS. They're cheap defence in depth on untrusted input.

---

## Local security (affects the user running the container, not the services)

### 9. `/info` no longer serializes secrets

`InfoRouteHandler` responded with the whole `ServerInfo` object. `ServerInfo` is `@Serializable` and
carries `libroPassword` and `trackerToken` — and **`@Redacted` only rewrites `toString()`, it does not
affect kotlinx serialization**. That response body would have contained the user's libro.fm password
and Hardcover token in plaintext, on an unauthenticated endpoint bound to `0.0.0.0`.

The route is not currently registered in `setupServer()`, so this was latent rather than live. But the
handler is wired into the route map, so adding one `get<Info>` line to the routing block would have
exposed it. It now responds with a new `PublicServerInfo` that carries no secrets.

### 10. Bearer token no longer printed to logs

`LOG_LEVEL=VERBOSE` maps to ktor's `LogLevel.HEADERS`, which does not redact `Authorization` by
default — so the libro.fm bearer token was printed into container logs that people routinely paste
into bug reports. Added `sanitizeHeader`.

### 11. Header parsing

`LIBRO_FM_HEADERS` split on every `=`, so any header value containing one was silently truncated. Now
splits on the first only, trims, and gives a real error on a malformed entry.

---

## Recommended but not implemented here

- **Re-authentication on 401 from libro.fm.** `fetchLoginData()` only logs in when the stored token is
  empty, and the token is resolved once per process via `by lazy`. If libro.fm revokes or expires the
  token, the container keeps issuing requests with the dead token every sync cycle forever, never
  re-authenticating and never backing off. Fixing this properly means threading auth failures back to
  a re-login, which touches every call site — worth doing, but not a change to make blind.
- **Authentication on the HTTP server.** See #6. `DELETE /history/{isbn}` is also unauthenticated, and
  deleting history is what causes a re-download.
- **Books that permanently fail to download** are never recorded, so they are retried in full on every
  cycle indefinitely. A failure count with backoff would bound this.
