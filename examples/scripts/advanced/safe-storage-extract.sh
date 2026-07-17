#!/bin/bash
# Simulate first, then issue one authorized extraction and preserve both results.
# A successful command can still report a partial amount; inspect the JSON output.
# Usage: bash safe-storage-extract.sh <device> <item-id> <count>
if [ -z "$3" ]; then
  echo 'usage: safe-storage-extract.sh <device> <item-id> <count>'
else
  mkdir -p /home/player/operations
  echo operation=storage-extract > /home/player/operations/last-extract.txt
  echo device=$1,item=$2,requested=$3 >> /home/player/operations/last-extract.txt
  if storage simulate-extract "$1" "$2" "$3" --json >> /home/player/operations/last-extract.txt; then
    echo simulation=accepted >> /home/player/operations/last-extract.txt
    if storage extract "$1" "$2" "$3" --json >> /home/player/operations/last-extract.txt; then
      echo execution=accepted >> /home/player/operations/last-extract.txt
    else
      echo execution=failed >> /home/player/operations/last-extract.txt
    fi
  else
    echo simulation=failed >> /home/player/operations/last-extract.txt
  fi
  cat /home/player/operations/last-extract.txt
fi
