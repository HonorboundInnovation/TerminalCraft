#!/bin/bash
mkdir -p /home/player/bin
mkdir -p /home/player/logs
label "$1"
echo terminal=$1 > /home/player/logs/boot.txt
echo status=ready >> /home/player/logs/boot.txt
cat /home/player/logs/boot.txt
