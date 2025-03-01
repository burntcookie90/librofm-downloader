# Libro.fm Audiobook Downloader

Small tool for checking your [libro.fm](https://libro.fm) library and downloading new books.

The tool is set to recheck the library every day and download new books. Books will be skipped if the `Author Name/Book Name` folder already exists.

## Extra Features

### Rename Chapters
Enable `RENAME_CHAPTERS` to rename files from `Track - #.mp3` to `### <Book Title> - <Chapter Title>` as provided by libro.fm
Additionally, if you enable `WRITE_TITLE_TAG`, each track's ID3 `title` field will be set to `### <Chapter Title>` as provided by libro.fm.

### Formats
By default, libro.fm only supports downloading "chapterized" MP3 files i.e. every chapter is its own file.
When setting `FORMAT` or passing the `--format` flag you can control what format should be used
Formats supported: `mp3`, `m4b`, `both`

- `mp3` (default): Only downloads the "chapterized" MP3 files
- `m4b`: Downloads the "chapterized" MP3 files, then merges them into a single `m4b` file __and deletes the MP3 files__.
- `both`: Downloads the "chapterized" MP3 files, and creates a single `m4b` file out of them. No files are deleted.

When running this tool separately (i.e. not using the provided docker container), please ensure `ffmpeg` is installed on your system.
You can pass the paths for `ffmpeg` and `ffprobe` to the tool as well using the `--ffmpeg-path <path>` and `--ffprobe-path <path>` options.

### API Server
After the initial download of your library, the container will run a API server.
Bind a host port to `8080` to access the services.

Endpoints:
- `/update` allows you to manually force a refresh (ie: when you just purchased a book).
- `/convertToM4b/{isbn}` allows you to selectively convert audiobooks from MP3 to M4B by passing the book's ISBN. 
  - If you pass `all` it will convert ALL books. 
  - Note: Books that already have a `.m4b` file will be skipped by default.
  - You can optionally pass the `?overwrite=true` flag to allow existing m4b files to be overwritten

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
      # extra optional: setting these enables them, dont add them if you dont want them.
      - DRY_RUN=true 
      - VERBOSE=true
      - RENAME_CHAPTERS=true
      - WRITE_TITLE_TAG=true #this one requires RENAME_CHAPTERS to be true as well
      - SYNC_INTERVAL="h/d/w" #choose one
```
