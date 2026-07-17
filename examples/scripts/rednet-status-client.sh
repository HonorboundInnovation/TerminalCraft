#!/bin/bash
if modem open 81 && modem ping "$1"; then
  modem call status 81 'status-request'
  modem recv 8
fi
