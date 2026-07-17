#!/bin/bash
if mount; then
  mkdir -p /disk/backup
  cat /home/player/config.txt | write /disk/backup/config.txt
  cat /home/player/logs/boot.txt | write /disk/backup/boot.txt
  disk sync
  umount
fi
