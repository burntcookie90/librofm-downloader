# Libro.fm Audiobook Downloader

Small tool for checking your [libro.fm](https://libro.fm) library and downloading new books.

The tool is set to recheck the library every day and download new books. Books will be skipped if the `Author Name/Book Name` folder already exists.

## Features

---
### Format
- Select from `MP3`, `M4B_MP3_FALLBACK` or `M4B_CONVERT_FALLBACK` formats. 
- `M4B_MP3_FALLBACK` is the default, but can run into issues where libro.fm does not have a m4b packaged for the book. In that case we'll download MP3s.
- `M4B_CONVERT_FALLBACK` will download M4Bs and in the event of failure will download  MP3s and use ffmpeg to create an `M4B` file


#### MP3 - Extra - Rename Chapters / Write Title Tag

Enable `RENAME_CHAPTERS` to rename files from `Track - #.mp3` to `### <Book Title> - <Chapter Title>` as provided by libro.fm
Additionally, if you enable `WRITE_TITLE_TAG`, each track's ID3 `title` field will be set to `### <Chapter Title>` as provided by libro.fm.

----

### API Server
After the initial download of your library, the container will run a API server.
Bind a host port to `8080` to access the services.

Endpoints:
- GET: `/` opens a basic web interface showing the current config and a button to trigger an update
- POST: `/update` allows you to manually force a refresh (ie: when you just purchased a book).


----

### Path Patterns
The application supports the following path tokens:
```
FIRST_AUTHOR - The first author in the list of authors
ALL_AUTHORS - All authors in the list of authors separated by ','
SERIES_NAME - The series name, if it exists
BOOK_TITLE - The book title
ISBN - The ISBN of the book
FIRST_NARRATOR - The first narrator in the list of narrators
ALL_NARRATORS - All narrators in the list of narrators separated by ','
```

You can set the env var `PATH_PATTERN` to change the default path pattern. The default is:
`PATH_PATTERN=FIRST_AUTHOR/BOOK_TITLE`

Changing your path pattern down the road will cause books to redownload, as the existence of the known file structure is how we determine a book has already been downloaded.

### Docker Compose Example
```
services:
  librofm-downloader:
    image: ghcr.io/burntcookie90/librofm-downloader:latest
    volumes:
      - /mnt/runtime/appdata/librofm-downloader:/data
      - /mnt/user/media/audiobooks:/media
    ports:
      # optional if you want to use the /update webhook
      - 8080:8080 
    environment:
      - LIBRO_FM_USERNAME=<>
      - LIBRO_FM_PASSWORD=<>
      - FORMAT="MP3/M4B_MP3_FALLBACK/M4B_CONVERT_FALLBACK" #choose one `M4B_MP3_FALLBACK` is default
      # extra optional: setting these enables them, dont add them if you dont want them.
      - PARALLEL_COUNT="3" #increase parallel processing limit, default is 1
      - LOG_LEVEL="NONE/INFO/VERBOSE"
      - SYNC_INTERVAL="h/d/w" #choose one
      - LIMIT="1"
      # MP3 only
      - RENAME_CHAPTERS=true #renames downloaded files with the chapter name provided by libro.fm
      - WRITE_TITLE_TAG=true #this one requires RENAME_CHAPTERS to be true as well
```
