# Libro.fm Audiobook Downloader

Small tool for checking your [libro.fm](https://libro.fm) library and downloading new books.

The tool is set to recheck the library every day and download new books. Books will be skipped if the folder already exists.

## Features

### Format
- Select from `MP3`, `M4B_MP3_FALLBACK` or `M4B_CONVERT_FALLBACK` formats. 
- `M4B_MP3_FALLBACK` is the default, but can run into issues where libro.fm does not have a m4b packaged for the book. In that case we'll download MP3s.
- `M4B_CONVERT_FALLBACK` will download M4Bs and in the event of failure will download  MP3s and use ffmpeg to create an `M4B` file
- `MP3` will download and unzip MP3s.


#### `MP3` / `M4B_MP3_FALLBACK` - Extra - Rename Chapters / Write Title Tag

Enable `RENAME_CHAPTERS` to rename files from `Track - #.mp3` to `### <Book Title> - <Chapter Title>` as provided by libro.fm
Additionally, if you enable `WRITE_TITLE_TAG`, each track's ID3 `title` field will be set to `### <Chapter Title>` as provided by libro.fm.

----

### Web UI
After the initial download of your library, the container runs a web server.
Bind a host port to `8080` and open it in a browser (it works on phones too).

- **Dashboard** - library totals, sync status and recent activity.
- **Library** - search, filter and sort your libro.fm library. Download, re-download or retry any book, or select several and download them in bulk.
- **Book page** - details, the files on disk and every attempt for that book. Choose a one-off format (MP3, M4B, ...) for a manual download, or forget a download so the next sync fetches it again.
- **Queue** - live view of running and waiting downloads with cancel buttons. Download everything that's missing, retry everything that failed, or fetch a book by ISBN.
- **History** - every download attempt, successful or not, with the error for failures. Filter by status, search, retry or delete records, and clear old ones.
- **Sync** - run a library sync now (optionally re-downloading everything) and see wishlist and Hardcover sync status.
- **Settings** - the active configuration (read-only; configure with environment variables).

A failing book no longer stops the rest of a sync: its error is recorded and you can retry it from the UI. Downloads left running when the container stopped are marked as failed on the next start.

#### Protecting the web UI
Set `WEBUI_PASSWORD` to require a password for the web UI. Sessions use a signed, HTTP-only cookie and last 30 days.
The JSON endpoints below also require the password in that case, sent as `Authorization: Bearer <password>` or `X-Api-Key: <password>`
(for example `curl -H "X-Api-Key: ..." http://host:8080/update`). Without a password, anyone who can reach the port has full access, so don't expose it directly to the internet.

### API Server
Endpoints:
- `GET`: `/` redirects to the web UI at `/ui`
- `GET`: `/update` allows you to manually force a refresh (ie: when you just purchased a book). Pass `?overwrite=true` to force download your library.
- `GET`: `/history` returns a json with your download history
- `GET`: `/history/{isbn}` returns the single entry for an isbn
- `DELETE`: `/history/{isbn}` deletes the history entry for an isbn


----

### Path Patterns
The application supports the following path tokens:
```
FIRST_AUTHOR - The first author in the list of authors
ALL_AUTHORS - All authors in the list of authors separated by ','
SERIES_NAME - The series name, if it exists
SERIES_NUM - The books respective series number, if it exists
BOOK_TITLE - The book title
ISBN - The ISBN of the book
FIRST_NARRATOR - The first narrator in the list of narrators
ALL_NARRATORS - All narrators in the list of narrators separated by ','
PUBLICATION_YEAR - Year book was published
PUBLICATION_MONTH - Month book was published (numerical)
PUBLICATION_DAY - Day book was published (numerical)
```

You can set the env var `PATH_PATTERN` to change the default path pattern. The default is:
`PATH_PATTERN=FIRST_AUTHOR/BOOK_TITLE`

### Tracker Syncing

#### Hardcover

`HARDCOVER_SYNC_MODE` controls the sync mode to your hardcover book tracker:
```declarative
  LIBRO_WISHLISTS_TO_HARDCOVER // Sync Libro wishlists to Hardcover
  LIBRO_OWNED_TO_HARDCOVER // DEFAULT Sync Libro owned books to Hardcover
  LIBRO_ALL_TO_HARDCOVER // Sync both Libro wishlist and owned books to Hardcover
  HARDCOVER_WANT_TO_READ_TO_LIBRO // Sync Hardcover want-to-read books to Libro
  ALL // CAREFUL as this can cause double syncs. The service will remember what was synced, but you'll need to manaully clean up anything on hardcover or librofm
```

### Docker Compose Example
```
services:
  librofm-downloader:
    image: git.lumu.au/luke/librofm-downloader:latest
    volumes:
      - /mnt/runtime/appdata/librofm-downloader:/data
      - /mnt/user/media/audiobooks:/media
    ports:
      # optional if you want to use the web UI or the /update webhook
      - 8080:8080 
    environment:
      - LIBRO_FM_USERNAME=<>
      - LIBRO_FM_PASSWORD=<>
      # extra optional: setting these enables them, dont add them if you dont want them.
      - FORMAT="M4B_MP3_FALLBACK/MP3/M4B_CONVERT_FALLBACK" #choose one `M4B_MP3_FALLBACK` is default
      - PARALLEL_COUNT="2" #increase parallel processing limit, default is 1, careful with memory usage!
      - LOG_LEVEL="NONE/INFO/VERBOSE"
      - SYNC_INTERVAL="h/d/w" #choose one
      - WEBUI_PASSWORD=<> #optional, require a password for the web UI and API
      # MP3 / M4B_MP3_FALLBACK only
      - RENAME_CHAPTERS=true #renames downloaded files with the chapter name provided by libro.fm
      - WRITE_TITLE_TAG=true #this one requires RENAME_CHAPTERS to be true as well
      - HARDCOVER_TOKEN=<>
      - SKIP_TRACKING_ISBNS=<>
      - HEALTHCHECK_ID=<>
```

To be notified when sync is failing visit https://healthchecks.io, create a check, and specify
the ID to the container using the `HEALTHCHECK_ID` environment variable.
