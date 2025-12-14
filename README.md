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

### API Server
After the initial download of your library, the container will run a API server.
Bind a host port to `8080` to access the services.

Endpoints:
- GET: `/` opens a basic web interface showing the current config and a button to trigger an update
- GET: `/update` allows you to manually force a refresh (ie: when you just purchased a book). Pass `?overwrite=true` to force download your library.


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

Changing your path pattern down the road will cause books to redownload, as the existence of the known file structure is how we determine a book has already been downloaded.

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
    image: ghcr.io/burntcookie90/librofm-downloader:latest
    volumes:
      - /mnt/runtime/appdata/librofm-downloader:/data
      - /mnt/user/media/audiobooks:/media
    ports:
      # optional if you want to use the /update webhook or webui
      - 8080:8080 
    environment:
      - LIBRO_FM_USERNAME=<>
      - LIBRO_FM_PASSWORD=<>
      # extra optional: setting these enables them, dont add them if you dont want them.
      - FORMAT="M4B_MP3_FALLBACK/MP3/M4B_CONVERT_FALLBACK" #choose one `M4B_MP3_FALLBACK` is default
      - PARALLEL_COUNT="2" #increase parallel processing limit, default is 1, careful with memory usage!
      - LOG_LEVEL="NONE/INFO/VERBOSE"
      - SYNC_INTERVAL="h/d/w" #choose one
      # MP3 / M4B_MP3_FALLBACK only
      - RENAME_CHAPTERS=true #renames downloaded files with the chapter name provided by libro.fm
      - WRITE_TITLE_TAG=true #this one requires RENAME_CHAPTERS to be true as well
      - HARDCOVER_TOKEN=<>
      - SKIP_TRACKING_ISBNS=<>
      - HEALTHCHECK_ID=<>
```

To be notified when sync is failing visit https://healthchecks.io, create a check, and specify
the ID to the container using the `HEALTHCHECK_ID` environment variable.
