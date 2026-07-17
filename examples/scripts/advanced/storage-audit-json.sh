#!/bin/bash
# Capture bounded machine-readable storage metadata and inventory pages.
# Usage: bash storage-audit-json.sh <device-selector> [namespace]
if [ -z "$1" ]; then
  echo 'usage: storage-audit-json.sh <device-selector> [namespace]'
else
  mkdir -p /home/player/audits
  echo device=$1 > /home/player/audits/storage-audit.txt
  if storage info "$1" --json >> /home/player/audits/storage-audit.txt; then
    echo metadata=ok >> /home/player/audits/storage-audit.txt
  else
    echo metadata=failed >> /home/player/audits/storage-audit.txt
  fi
  if [ -z "$2" ]; then
    storage query "$1" --limit 32 --json >> /home/player/audits/storage-audit.txt
  else
    storage query "$1" --namespace "$2" --limit 32 --json >> /home/player/audits/storage-audit.txt
  fi
  cat /home/player/audits/storage-audit.txt
fi
