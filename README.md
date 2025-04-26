# Libro.fm Audiobook Downloader

Small tool for checking your [libro.fm](https://libro.fm) library and downloading new books.

The tool is set to recheck the library every day and download new books. Books will be skipped if the `Author Name/Book Name` folder already exists.

## Features

---
### Format
- Select from either `MP3` or `M4B` downloads. `M4B` is the default


#### MP3 - Extra - Rename Chapters / Write Title Tag
Enable `RENAME_CHAPTERS` to rename files from `Track - #.mp3` to `### <Book Title> - <Chapter Title>` as provided by libro.fm
Additionally, if you enable `WRITE_TITLE_TAG`, each track's ID3 `title` field will be set to `### <Chapter Title>` as provided by libro.fm.
----

### API Server
After the initial download of your library, the container will run a API server.
Bind a host port to `8080` to access the services.

Endpoints:
- `/update` allows you to manually force a refresh (ie: when you just purchased a book).

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
      - FORMAT="MP3/M4B" #choose one
      # extra optional: setting these enables them, dont add them if you dont want them.
      - DRY_RUN=true 
      - LOG_LEVEL="NONE/INFO/VERBOSE"
      - SYNC_INTERVAL="h/d/w" #choose one
      # MP3 only
      - RENAME_CHAPTERS=true #renames downloaded files with the chapter name provided by libro.fm
      - WRITE_TITLE_TAG=true #this one requires RENAME_CHAPTERS to be true as well
```
