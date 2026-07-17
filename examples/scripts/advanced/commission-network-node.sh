#!/bin/bash
# Commission a named workshop node with a status service and operator display.
# Usage: bash commission-network-node.sh <hostname> <network-name>
if [ -z "$2" ]; then
  echo 'usage: commission-network-node.sh <hostname> <network-name>'
else
  if modem hostname "$1"; then
    if modem network "$2"; then
      if modem open 80; then
        if modem open 81; then
          if modem service add status 80; then
            label "$1"
            monitor clear any
            monitor title any 'Network Node'
            monitor color any '#00ff66' '#001108'
            monitor set any 0 "Host: $1"
            monitor set any 1 "Network: $2"
            monitor set any 2 'Status service: port 80'
            modem interfaces
            modem service list
          else
            echo 'commission failed: service registration'
          fi
        else
          echo 'commission failed: reply port 81'
        fi
      else
        echo 'commission failed: service port 80'
      fi
    else
      echo 'commission failed: network name'
    fi
  else
    echo 'commission failed: hostname'
  fi
fi
