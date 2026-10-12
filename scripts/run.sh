#!/bin/sh
# Fail early with a clear message when the volumes still belong to root (images before the switch to a non-root user).
for path in /data /data/* /media; do
  if [ -e "$path" ] && ! [ -w "$path" ]; then
    echo "ERROR: $path is not writable by the container user (uid $(id -u), gid $(id -g))." >&2
    echo "Fix the volume's ownership (e.g. chown -R $(id -u):$(id -g) <host path>) or set the container's user to its owner." >&2
    exit 1
  fi
done
exec bin/app
