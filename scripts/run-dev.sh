#!/bin/sh
[ ! -f .env ] || export $(grep -v '^#' .env| xargs)
server/build/install/server/bin/server \
  --data-dir "./run/data/" \
  --media-dir "./run/media"
